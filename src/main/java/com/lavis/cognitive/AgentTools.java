package com.lavis.cognitive;

import com.lavis.action.AppleScriptExecutor;
import com.lavis.action.RobotDriver;
import com.lavis.cognitive.agent.SearchAgent;
import com.lavis.perception.ScreenCapturer;
import com.lavis.service.llm.LlmFactory;
import com.lavis.service.search.WebSearchService;
import com.lavis.skills.SkillExecutor;
import com.lavis.skills.SkillService;
import com.lavis.skills.dto.SkillResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * AI 可调用的工具集 - 改进版
 * * 改进核心：
 * 修改了工具的返回值，从 "Success" 改为 "Action Performed"，
 * 并明确提示 AI 需要通过视觉反馈来验证操作结果。
 */
@Slf4j
@Component
public class AgentTools {

    private final RobotDriver robotDriver;
    private final AppleScriptExecutor appleScriptExecutor;
    private final ScreenCapturer screenCapturer;
    private final SkillService skillService;
    private final SearchAgent searchAgent;
    private final WebSearchService webSearchService;
    private final LlmFactory llmFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentTools(RobotDriver robotDriver, AppleScriptExecutor appleScriptExecutor,
                      ScreenCapturer screenCapturer, SkillService skillService,
                      SearchAgent searchAgent, WebSearchService webSearchService,
                      LlmFactory llmFactory) {
        this.robotDriver = robotDriver;
        this.appleScriptExecutor = appleScriptExecutor;
        this.screenCapturer = screenCapturer;
        this.skillService = skillService;
        this.searchAgent = searchAgent;
        this.webSearchService = webSearchService;
        this.llmFactory = llmFactory;
    }

    // ==================== 认知 / 反思工具 ====================

    /**
     * 纯思考/反思/规划工具（无任何外部副作用）
     *
     * 用途：
     * - 在「开始执行前」先梳理整体思路和步骤规划。
     * - 在「执行过程中」对当前进展进行反思、调整策略。
     * - 在「出现异常或卡住」时，总结问题、提出假设和下一步计划。
     *
     * 设计约束：
     * - 本工具绝不调用任何外部系统，不做点击/输入/网络请求等操作，仅在内部记录一段文字。
     * - 每次调用时，入参中的思考内容会被**原样作为 tool_result 返回**，便于编排器/上层系统记录和展示思考链路。
     *
     * 使用建议（对 LLM）：
     * - 当任务复杂、多步骤或存在不确定性时，优先调用本工具进行显式规划。
     * - 反思内容应尽量结构化，例如：
     *   1) 当前目标
     *   2) 已知信息
     *   3) 风险与不确定点
     *   4) 接下来 1~3 步的具体行动计划（对应可调用的工具）
     */
    @Tool("Reflect, analyze, and plan before or during actions. This tool has NO side effects: it only records your structured thinking. The input reflection text will be returned verbatim as the tool_result so orchestrators can log your reasoning.")
    public String think_tool(
            @P("Your detailed reflection or step-by-step plan. Suggest structure: (1) goal, (2) known info, (3) risks/uncertainties, (4) next 1-3 concrete tool calls you plan to make.") String reflection
    ) {
        // 关键点：不做任何外部动作，只是把思考原样返回作为 tool_result
        return reflection == null ? "" : reflection;
    }

    // ==================== 鼠标操作 (反馈语调更加中性) ====================

    /**
     * 将 Gemini 归一化坐标 (0-999) 转为 macOS AWT Robot 使用的逻辑屏幕坐标 (points)。
     * 说明：
     * - 屏幕截图叠加网格/模型输出使用 Gemini 坐标系 (0-999)；
     * - Java 9+ macOS 下 AWT Robot 使用逻辑坐标，不是物理像素；
     * - 因此这里需要做"坐标系转换"，而不是乘以 Retina 缩放因子。
     * 
     * 【修复】添加坐标验证和钳制，确保输入坐标在有效范围内 (0-999)
     */
    private Point toLogicalPoint(int[] geminiCoords) {
        if (geminiCoords == null || geminiCoords.length < 2) return null;
        
        int geminiX = geminiCoords[0];
        int geminiY = geminiCoords[1];
        
        // 验证并钳制 Gemini 坐标到有效范围 (0-999)
        boolean clamped = false;
        if (geminiX < 0 || geminiX > ScreenCapturer.COORD_MAX) {
            geminiX = Math.max(0, Math.min(ScreenCapturer.COORD_MAX, geminiX));
            clamped = true;
        }
        if (geminiY < 0 || geminiY > ScreenCapturer.COORD_MAX) {
            geminiY = Math.max(0, Math.min(ScreenCapturer.COORD_MAX, geminiY));
            clamped = true;
        }
        
        if (clamped) {
            log.warn("⚠️ Gemini 坐标超出范围，已自动钳制: 原始[{}, {}] -> 修正[{}, {}] (有效范围: 0-{})",
                    geminiCoords[0], geminiCoords[1], geminiX, geminiY, ScreenCapturer.COORD_MAX);
        }
        
        // 使用 ScreenCapturer 内置转换（含边界/安全区处理）
        Point logical = screenCapturer.toLogicalSafe(geminiX, geminiY);
        log.info("🎯 坐标校准: Gemini[{}, {}] -> 逻辑坐标[{}, {}]",
                geminiX, geminiY, logical.x, logical.y);
        return logical;
    }

    public String moveMouse(@P("Coordinate position array [x, y] in Gemini format (0-999)") int[] coords) {
        if (coords == null || coords.length < 2) return "❌ 错误: 坐标无效";
        try {
            Point logical = toLogicalPoint(coords);
            if (logical == null) return "❌ 错误: 坐标无效";
            robotDriver.moveTo(logical.x, logical.y);
            return String.format("鼠标已移动到 逻辑坐标(%d, %d)（输入Gemini:%d,%d）",
                    logical.x, logical.y, coords[0], coords[1]);
        } catch (Exception e) {
            return "❌ 移动失败: " + e.getMessage();
        }
    }

    @Tool("Click at specified screen position. Coordinates must be in Gemini format [x, y] where x and y are integers between 0 and 999. Note: After click operation executes must observe screen changes such as button color change page jump popup disappearance to confirm if click took effect")
    public String click(@P("Coordinate position array [x, y] in Gemini format (0-999)") int[] coords) {
        if (coords == null || coords.length < 2) {
            return String.format("❌ 错误: 坐标无效 (需要 [x, y] 数组，Gemini 格式 0-%d)", ScreenCapturer.COORD_MAX);
        }
        // 检查坐标范围（在 toLogicalPoint 中会自动钳制，但这里先给出警告）
        if (coords[0] < 0 || coords[0] > ScreenCapturer.COORD_MAX || 
            coords[1] < 0 || coords[1] > ScreenCapturer.COORD_MAX) {
            log.warn("⚠️ 坐标超出范围: [{}, {}] (有效范围: 0-{})，将自动钳制", 
                    coords[0], coords[1], ScreenCapturer.COORD_MAX);
        }
        try {
            Point logical = toLogicalPoint(coords);
            if (logical == null) {
                return String.format("❌ 错误: 坐标转换失败 (输入: [%d, %d]，Gemini 格式应为 0-%d)", 
                        coords[0], coords[1], ScreenCapturer.COORD_MAX);
            }
            robotDriver.clickAt(logical.x, logical.y);
            // 记录逻辑坐标（截图侧会再转回 Gemini 做标注）
            screenCapturer.recordClickPosition(logical.x, logical.y);
            // 关键修改：不再仅仅说"成功"，而是提示动作已完成，暗示需要验证
            return String.format("🖱️ 已在 逻辑坐标(%d, %d) 执行点击（输入Gemini:%d,%d）。请等待下一次截图以验证UI是否响应。",
                    logical.x, logical.y, coords[0], coords[1]);
        } catch (Exception e) {
            log.error("点击失败", e);
            return "❌ 点击操作执行异常: " + e.getMessage();
        }
    }

    @Tool("Double click at specified screen position. Coordinates must be in Gemini format [x, y] where x and y are integers between 0 and 999. If single click did not trigger expected UI changes try using this tool")
    public String doubleClick(@P("Coordinate position array [x, y] in Gemini format (0-999)") int[] coords) {
        if (coords == null || coords.length < 2) {
            return String.format("❌ 错误: 坐标无效 (需要 [x, y] 数组，Gemini 格式 0-%d)", ScreenCapturer.COORD_MAX);
        }
        if (coords[0] < 0 || coords[0] > ScreenCapturer.COORD_MAX || 
            coords[1] < 0 || coords[1] > ScreenCapturer.COORD_MAX) {
            log.warn("⚠️ 坐标超出范围: [{}, {}] (有效范围: 0-{})，将自动钳制", 
                    coords[0], coords[1], ScreenCapturer.COORD_MAX);
        }
        try {
            Point logical = toLogicalPoint(coords);
            if (logical == null) {
                return String.format("❌ 错误: 坐标转换失败 (输入: [%d, %d]，Gemini 格式应为 0-%d)", 
                        coords[0], coords[1], ScreenCapturer.COORD_MAX);
            }
            robotDriver.doubleClickAt(logical.x, logical.y);
            screenCapturer.recordClickPosition(logical.x, logical.y);
            return String.format("🖱️ 已在 逻辑坐标(%d, %d) 执行双击（输入Gemini:%d,%d）。请检查屏幕变化。",
                    logical.x, logical.y, coords[0], coords[1]);
        } catch (Exception e) {
            return "❌ 双击异常: " + e.getMessage();
        }
    }

    @Tool("Right click at specified screen position. Coordinates must be in Gemini format [x, y] where x and y are integers between 0 and 999")
    public String rightClick(@P("Coordinate position array [x, y] in Gemini format (0-999)") int[] coords) {
        if (coords == null || coords.length < 2) return "❌ 错误: 坐标无效";
        try {
            Point logical = toLogicalPoint(coords);
            if (logical == null) return "❌ 错误: 坐标无效";
            robotDriver.rightClickAt(logical.x, logical.y);
            screenCapturer.recordClickPosition(logical.x, logical.y);
            return String.format("🖱️ 已在 逻辑坐标(%d, %d) 执行右键点击（输入Gemini:%d,%d）。请寻找上下文菜单。",
                    logical.x, logical.y, coords[0], coords[1]);
        } catch (Exception e) {
            return "❌ 右键点击异常: " + e.getMessage();
        }
    }

    @Tool("Drag operation. Coordinates must be in Gemini format [x, y] where x and y are integers between 0 and 999")
    public String drag(@P("Start position [x, y] in Gemini format (0-999)") int[] from, @P("Target position [x, y] in Gemini format (0-999)") int[] to) {
        try {
            if (from == null || from.length < 2 || to == null || to.length < 2) return "❌ 错误: 坐标无效";
            Point fromLogical = toLogicalPoint(from);
            Point toLogical = toLogicalPoint(to);
            if (fromLogical == null || toLogical == null) return "❌ 错误: 坐标无效";
            robotDriver.drag(fromLogical.x, fromLogical.y, toLogical.x, toLogical.y);
            return "已执行拖拽操作。请确认对象位置是否改变。";
        } catch (Exception e) {
            return "❌ 拖拽异常: " + e.getMessage();
        }
    }

    @Tool("Scroll screen")
    public String scroll(@P("Scroll amount: positive down, negative up") int amount) {
        try {
            robotDriver.scroll(amount);
            return "已执行滚动操作。请检查可视区域是否更新。";
        } catch (Exception e) {
            return "❌ 滚动异常: " + e.getMessage();
        }
    }

    // ==================== 键盘操作 ====================

    public String type_at(String text) {
        try {
            robotDriver.type(text);
            return String.format("⌨️ 键盘敲击已发送: \"%s\"。请通过截图验证文字是否上屏。", text);
        } catch (Exception e) {
            return "❌ 输入异常: " + e.getMessage();
        }
    }

    /**
     * 在指定坐标输入文本（1000x1000 Gemini 坐标系）
     *
     * 说明：
     * - x、y 使用与点击工具相同的 Gemini 坐标（0-1000），内部会自动转换为逻辑坐标。
     * - 默认会先点击该坐标位置以聚焦输入框。
     * - 默认会先全选并清空原有内容（clear_before_typing = true）。
     *
     * 【关键约束】
     * - 即使传入 press_enter=true，本工具也绝不会自动按下 Enter。
     * - 如需回车提交，请单独调用 keyCombination("enter")。
     */
    @Tool("Type text at a specific 1000x1000 Gemini coordinate. This tool NEVER presses Enter automatically, even if press_enter is true. Use keyCombination('enter') explicitly if you need to submit.")
    public String type_text_at(
            @P("y: int (0-999) Gemini Y coordinate") int y,
            @P("x: int (0-999) Gemini X coordinate") int x,
            @P("Text to input") String text,
            @P("Whether to clear existing text before typing; default true if null") Boolean clear_before_typing
    ) {
        try {
            int[] coords = new int[]{x, y};
            Point logical = toLogicalPoint(coords);
            if (logical == null) {
                return "❌ 错误: 坐标无效，无法转换为逻辑坐标";
            }

            // 1. 在该位置点击以获得输入焦点
            robotDriver.clickAt(logical.x, logical.y);

            // 2. 是否清空原有内容（默认 true）
            boolean shouldClear = clear_before_typing == null || clear_before_typing;
            if (shouldClear) {
                robotDriver.selectAll();
                robotDriver.pressBackspace();
            }

            // 3. 输入文本（不包含 Enter）
            robotDriver.type(text != null ? text : "");

            // 4. 返回提示信息，强调不会自动按 Enter
            return String.format(
                    "⌨️ 已在 逻辑坐标(%d, %d) 输入文本: \"%s\"（Gemini 坐标:%d,%d）。本工具不会自动按下 Enter，如需提交请单独调用 keyCombination(\"enter\")。",
                    logical.x, logical.y, text, x, y
            );
        } catch (Exception e) {
            return "❌ 坐标输入异常: " + e.getMessage();
        }
    }

    @Tool("Press keyboard keys or combinations. Use '+' to join keys for combinations, e.g. 'cmd+c', 'cmd+shift+p', 'ctrl+alt+delete'. Useful for submitting forms (\"enter\"), clipboard operations, navigation, etc.")
    public String keyCombination(@P("Keyboard key or combination, e.g. 'enter', 'esc', 'tab', 'backspace', 'cmd+c', 'cmd+v', 'cmd+a', 'cmd+s', 'cmd+z', 'cmd+shift+p'") String keys) {
        if (keys == null || keys.isEmpty()) {
            return "❌ 错误: keys 不能为空，例如 'enter' 或 'cmd+c'";
        }

        String normalized = keys.trim().toLowerCase();

        try {
            // 支持任意按键组合：使用 '+' 分隔，如 "cmd+shift+p"、"ctrl+alt+delete"
            String[] parts = normalized.split("\\+");
            int[] keyCodes = new int[parts.length];

            for (int i = 0; i < parts.length; i++) {
                String token = parts[i].trim();
                Integer code = mapKeyToken(token);
                if (code == null) {
                    return "❌ 暂不支持的按键或别名: '" + token + "' 于组合 \"" + keys +
                            "\"。请使用常见写法，如 cmd/ctrl/alt/shift + 字母/数字/enter/esc/tab/backspace 等。";
                }
                keyCodes[i] = code;
            }

            if (keyCodes.length == 1) {
                // 单键
                robotDriver.pressKey(keyCodes[0]);
                return "已按下按键: " + normalized;
            } else {
                // 组合键：同时按下
                robotDriver.pressKeys(keyCodes);
                return "已发送组合键: " + normalized;
            }
        } catch (Exception e) {
            return "❌ 按键/组合键执行异常: " + e.getMessage();
        }
    }

    /**
     * 将字符串按键标识映射为 KeyEvent keyCode
     * 支持：
     * - 修饰键：cmd/command/meta, ctrl/control, alt/option, shift
     * - 功能键：enter/return, esc/escape, tab, backspace/delete/del
     * - 字母：a-z
     * - 数字：0-9
     */
    private Integer mapKeyToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }

        // 修饰键
        switch (token) {
            case "cmd", "command", "meta", "⌘":
                return KeyEvent.VK_META;
            case "ctrl", "control":
                return KeyEvent.VK_CONTROL;
            case "alt", "option":
                return KeyEvent.VK_ALT;
            case "shift":
                return KeyEvent.VK_SHIFT;
            case "enter", "return":
                return KeyEvent.VK_ENTER;
            case "esc", "escape":
                return KeyEvent.VK_ESCAPE;
            case "tab":
                return KeyEvent.VK_TAB;
            case "backspace", "delete", "del":
                return KeyEvent.VK_BACK_SPACE;
        }

        // 单个字母 a-z
        if (token.length() == 1) {
            char c = token.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return KeyEvent.VK_A + (c - 'a');
            }
            if (c >= '0' && c <= '9') {
                return KeyEvent.VK_0 + (c - '0');
            }
        }

        return null;
    }

    // ==================== 系统操作 ====================

    @Tool("Open application")
    public String openApplication(@P("Application name") String appName) {
        try {
            var result = appleScriptExecutor.openApplication(appName);
            return result.success() ?
                    "已发送打开指令给: " + appName + "。请等待UI加载。" :
                    "❌ 打开失败: " + result.output();
        } catch (Exception e) {
            return "❌ 异常: " + e.getMessage();
        }
    }

    @Tool("List installed applications")
    public String listInstalledApplications() {
        try {
            var result = appleScriptExecutor.executeShell("ls /Applications | grep '.app'");
            return result.success() ? "应用列表:\n" + result.output() : "❌ 获取列表失败";
        } catch (Exception e) {
            return "❌ 异常: " + e.getMessage();
        }
    }

    @Tool("Quit application")
    public String quitApplication(@P("Application name") String appName) {
        try {
            var result = appleScriptExecutor.quitApplication(appName);
            return result.success() ? "已发送关闭指令。" : "❌ 关闭失败: " + result.output();
        } catch (Exception e) {
            return "❌ 异常: " + e.getMessage();
        }
    }

    @Tool("Get current active application")
    public String getActiveApp() {
        try {
            return "当前活动应用: " + appleScriptExecutor.getActiveApplication();
        } catch (Exception e) {
            return "❌ 获取失败: " + e.getMessage();
        }
    }

    @Tool("Get current window title")
    public String getActiveWindowTitle() {
        try {
            return "窗口标题: " + appleScriptExecutor.getActiveWindowTitle();
        } catch (Exception e) {
            return "❌ 获取失败: " + e.getMessage();
        }
    }

    @Tool("Open URL")
    public String openURL(@P("URL address") String url) {
        try {
            var result = appleScriptExecutor.openURL(url);
            return result.success() ? "已请求打开 URL: " + url + "。请检查浏览器是否已加载页面。" : "❌ 打开失败";
        } catch (Exception e) {
            return "❌ 异常: " + e.getMessage();
        }
    }

    @Tool("Open file")
    public String openFile(@P("File path") String filePath) {
        try {
            var result = appleScriptExecutor.openFile(filePath);
            return result.success() ? "已请求打开文件: " + filePath : "❌ 打开失败";
        } catch (Exception e) {
            return "❌ 异常: " + e.getMessage();
        }
    }

    @Tool("Reveal in Finder")
    public String revealInFinder(@P("Path") String filePath) {
        try {
            var result = appleScriptExecutor.revealInFinder(filePath);
            return result.success() ? "已在 Finder 中选中。" : "❌ 操作失败";
        } catch (Exception e) {
            return "❌ 异常: " + e.getMessage();
        }
    }


    @Tool("Execute AppleScript")
    public String executeAppleScript(@P("Script") String script) {
        try {
            var result = appleScriptExecutor.executeAppleScript(script);
            return "脚本执行结果: " + result.output();
        } catch (Exception e) {
            return "❌ 异常: " + e.getMessage();
        }
    }

    @Tool("Execute Shell command")
    public String executeShell(@P("Command") String command) {
        try {
            var result = appleScriptExecutor.executeShell(command);
            return "Shell 输出: " + result.output();
        } catch (Exception e) {
            return "❌ 异常: " + e.getMessage();
        }
    }

    // ==================== 感知操作 ====================

    @Tool("Get screen screenshot")
    public String captureScreen() {
        try {
            String base64 = screenCapturer.captureScreenAsBase64();
            return "截图已获取 (Base64长度: " + base64.length() + ")";
        } catch (IOException e) {
            return "❌ 截图失败: " + e.getMessage();
        }
    }

    @Tool("Wait Used to wait for UI animation or loading")
    public String wait(@P("Milliseconds") int milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return String.format("⏳ 已等待 %d ms。请检查屏幕是否已就绪。", milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "等待被中断";
        }
    }

    @Tool("Get mouse information")
    public String getMouseInfo() {
        try {
            java.awt.Point mousePos = java.awt.MouseInfo.getPointerInfo().getLocation();
            java.awt.Dimension screenSize = screenCapturer.getScreenSize();
            return String.format("🖱️ 当前鼠标: (%d, %d), 屏幕: %d x %d",
                    mousePos.x, mousePos.y, screenSize.width, screenSize.height);
        } catch (Exception e) {
            return "❌ 获取失败: " + e.getMessage();
        }
    }
    // ==================== 任务完成工具 ====================

    /**
     * 任务完成工具（单层架构下，表示“整个用户任务”已经完成）
     *
     * 【架构规则】
     * 1. 单层任务：当前架构下不存在子任务/里程碑分层；调用本工具 == 当前用户给定的整个任务已完成。
     * 2. 必须在「观察轮」调用：只能在收到最新截图并确认界面已达到最终目标状态后调用。
     * 3. 严禁在「执行动作的同一轮」调用：如果本轮你刚刚执行了 click / type / keyCombination / scroll 等会改变界面的动作，
     *    必须等待下一轮截图验证动作结果，再根据截图决定是否调用本工具。
     * 4. 调用本工具即视为整个任务成功结束，编排器会退出当前任务循环，不再规划或调用任何其他工具。
     */
    @Tool("Use ONLY when the entire user task is fully completed and the latest screenshot clearly proves the final goal state. This tool marks the WHOLE task as SUCCESS and ends the current task loop completely. CRITICAL ARCHITECTURE RULE: never call this tool in the same turn as any visual-impact action (click, type_text_at, keyCombination, scroll, drag, etc). Always wait for the next screenshot, verify full task success, then (and only then) call this tool. After calling this tool, you must NOT plan or call any further tools.")
    public String complete_tool(
            @P("Summarize: (1) concrete visual evidence from the latest screenshot proving that the ENTIRE user task is completed; (2) the final completed state / user goal in natural language.") String summary) {
        log.info("✅ 里程碑完成: {}", summary);
        return "Milestone marked as completed: " + summary;
    }

    // ==================== Skills 工具 ====================

    @Tool("Execute a skill by name. Skills are pre-defined automation commands that can perform complex tasks.")
    public String executeSkill(
            @P("Skill name to execute") String skillName,
            @P("Parameters as JSON object, e.g. {\"key\": \"value\"}. Pass null or empty string if no parameters needed.") String params) {
        try {
            Map<String, String> paramMap = null;
            if (params != null && !params.isEmpty() && !params.equals("null")) {
                paramMap = objectMapper.readValue(params, new TypeReference<Map<String, String>>() {});
            }
            SkillExecutor.ExecutionResult result = skillService.executeSkill(skillName, paramMap);
            if (result.isSuccess()) {
                return String.format("✅ 技能 '%s' 执行成功。输出: %s", skillName, result.getOutput());
            } else {
                return String.format("❌ 技能 '%s' 执行失败: %s", skillName, result.getError());
            }
        } catch (Exception e) {
            log.error("执行技能失败: {}", skillName, e);
            return "❌ 执行技能异常: " + e.getMessage();
        }
    }

    @Tool("List all available skills. Returns skill names, descriptions, and categories.")
    public String listSkills() {
        try {
            List<SkillResponse> skills = skillService.getEnabledSkills();
            if (skills.isEmpty()) {
                return "当前没有可用的技能。";
            }
            StringBuilder sb = new StringBuilder("📋 可用技能列表:\n");
            for (SkillResponse skill : skills) {
                sb.append(String.format("- %s", skill.getName()));
                if (skill.getCategory() != null) {
                    sb.append(String.format(" [%s]", skill.getCategory()));
                }
                if (skill.getDescription() != null) {
                    sb.append(String.format(": %s", skill.getDescription()));
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取技能列表失败", e);
            return "❌ 获取技能列表异常: " + e.getMessage();
        }
    }

    // ==================== 网络搜索工具 ====================

    // @Tool("Search the internet for information. Uses iterative deep search with up to 5 rounds. Returns a synthesized summary (~200 words) of the findings.")
    // public String internetSearch(
    //         @P("Search query - be specific and include relevant keywords") String query,
    //         @P("Whether to use deep search with multiple iterations (true) or quick single search (false)") boolean deepSearch) {
    //     try {
    //         log.info("🔍 Internet search: query={}, deepSearch={}", query, deepSearch);

    //         if (deepSearch) {
    //             // 使用 SearchAgent 进行深度搜索
    //             var chatModel = llmFactory.getModel();
    //             var report = searchAgent.execute(query, chatModel);
    //             return report.toCompactSummary();
    //         } else {
    //             // 快速单次搜索
    //             var result = webSearchService.search(query);
    //             return result.toSummary();
    //         }
    //     } catch (Exception e) {
    //         log.error("Internet search failed: {}", e.getMessage(), e);
    //         return "❌ 搜索失败: " + e.getMessage();
    //     }
    // }

    @Tool("Quick web search - single query, no iteration. Use this for simple factual queries.")
    public String quickSearch(@P("Search query") String query) {
        try {
            log.info("🔍 Quick search: {}", query);
            var result = webSearchService.search(query);
            return result.toSummary();
        } catch (Exception e) {
            log.error("Quick search failed: {}", e.getMessage(), e);
            return "❌ 搜索失败: " + e.getMessage();
        }
    }
}