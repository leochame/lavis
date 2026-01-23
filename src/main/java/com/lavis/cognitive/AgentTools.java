package com.lavis.cognitive;

import com.lavis.action.AppleScriptExecutor;
import com.lavis.action.RobotDriver;
import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Point;
import java.io.IOException;

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

    public AgentTools(RobotDriver robotDriver, AppleScriptExecutor appleScriptExecutor,
                      ScreenCapturer screenCapturer) {
        this.robotDriver = robotDriver;
        this.appleScriptExecutor = appleScriptExecutor;
        this.screenCapturer = screenCapturer;
    }

    // ==================== 鼠标操作 (反馈语调更加中性) ====================

    /**
     * 将 Gemini 归一化坐标 (0-1000) 转为 macOS AWT Robot 使用的逻辑屏幕坐标 (points)。
     * 说明：
     * - 屏幕截图叠加网格/模型输出使用 Gemini 坐标系 (0-1000)；
     * - Java 9+ macOS 下 AWT Robot 使用逻辑坐标，不是物理像素；
     * - 因此这里需要做“坐标系转换”，而不是乘以 Retina 缩放因子。
     */
    private Point toLogicalPoint(int[] geminiCoords) {
        if (geminiCoords == null || geminiCoords.length < 2) return null;
        // 使用 ScreenCapturer 内置转换（含边界/安全区处理）
        Point logical = screenCapturer.toLogicalSafe(geminiCoords[0], geminiCoords[1]);
        log.info("🎯 坐标校准: Gemini[{}, {}] -> 逻辑坐标[{}, {}]",
                geminiCoords[0], geminiCoords[1], logical.x, logical.y);
        return logical;
    }

    public String moveMouse(@P("Coordinate position array [x, y]") int[] coords) {
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

    @Tool("Click at specified screen position Note After click operation executes must observe screen changes such as button color change page jump popup disappearance to confirm if click took effect")
    public String click(@P("Coordinate position array [x, y]") int[] coords) {
        if (coords == null || coords.length < 2) return "❌ 错误: 坐标无效";
        try {
            Point logical = toLogicalPoint(coords);
            if (logical == null) return "❌ 错误: 坐标无效";
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

    @Tool("Double click at specified screen position If single click did not trigger expected UI changes try using this tool")
    public String doubleClick(@P("Coordinate position array [x, y]") int[] coords) {
        if (coords == null || coords.length < 2) return "❌ 错误: 坐标无效";
        try {
            Point logical = toLogicalPoint(coords);
            if (logical == null) return "❌ 错误: 坐标无效";
            robotDriver.doubleClickAt(logical.x, logical.y);
            screenCapturer.recordClickPosition(logical.x, logical.y);
            return String.format("🖱️ 已在 逻辑坐标(%d, %d) 执行双击（输入Gemini:%d,%d）。请检查屏幕变化。",
                    logical.x, logical.y, coords[0], coords[1]);
        } catch (Exception e) {
            return "❌ 双击异常: " + e.getMessage();
        }
    }

    @Tool("Right click")
    public String rightClick(@P("Coordinate position array [x, y]") int[] coords) {
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

    @Tool("Drag operation")
    public String drag(@P("Start position [x, y]") int[] from, @P("Target position [x, y]") int[] to) {
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

    @Tool("Input text. Note: Ensure input box is focused. After input check if text is correctly displayed on screen")
    public String typeText(@P("Text to input") String text) {
        try {
            robotDriver.type(text);
            return String.format("⌨️ 键盘敲击已发送: \"%s\"。请通过截图验证文字是否上屏。", text);
        } catch (Exception e) {
            return "❌ 输入异常: " + e.getMessage();
        }
    }

    @Tool("Press Enter key")
    public String pressEnter() {
        try {
            robotDriver.pressEnter();
            return "已按下 Enter 键。请观察是否提交表单或换行。";
        } catch (Exception e) {
            return "❌ 按键异常: " + e.getMessage();
        }
    }

    @Tool("Press Escape key")
    public String pressEscape() {
        try {
            robotDriver.pressEscape();
            return "已按下 ESC 键。";
        } catch (Exception e) {
            return "❌ 按键异常: " + e.getMessage();
        }
    }

    @Tool("Press Tab key")
    public String pressTab() {
        try {
            robotDriver.pressTab();
            return "已按下 Tab 键。请检查焦点位置。";
        } catch (Exception e) {
            return "❌ 按键异常: " + e.getMessage();
        }
    }

    @Tool("Press Backspace key")
    public String pressBackspace() {
        try {
            robotDriver.pressBackspace();
            return "已按下 Backspace 键。请检查字符是否被删除。";
        } catch (Exception e) {
            return "❌ 按键异常: " + e.getMessage();
        }
    }

    @Tool("Copy Cmd+C")
    public String copy() {
        try {
            robotDriver.copy();
            return "已发送复制快捷键。";
        } catch (Exception e) {
            return "❌ 复制异常: " + e.getMessage();
        }
    }

    @Tool("Paste Cmd+V")
    public String paste() {
        try {
            robotDriver.paste();
            return "已发送粘贴快捷键。请检查内容是否出现。";
        } catch (Exception e) {
            return "❌ 粘贴异常: " + e.getMessage();
        }
    }

    @Tool("Select All Cmd+A")
    public String selectAll() {
        try {
            robotDriver.selectAll();
            return "已发送全选快捷键。请检查高亮区域。";
        } catch (Exception e) {
            return "❌ 全选异常: " + e.getMessage();
        }
    }

    @Tool("Save Cmd+S")
    public String save() {
        try {
            robotDriver.save();
            return "已发送保存快捷键。";
        } catch (Exception e) {
            return "❌ 保存异常: " + e.getMessage();
        }
    }

    @Tool("Undo Cmd+Z")
    public String undo() {
        try {
            robotDriver.undo();
            return "已发送撤销快捷键。";
        } catch (Exception e) {
            return "❌ 撤销异常: " + e.getMessage();
        }
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

    @Tool("Show notification")
    public String showNotification(@P("Title") String title, @P("Content") String message) {
        try {
            appleScriptExecutor.showNotification(title, message);
            return "通知已发送。";
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

    // ==================== 诊断工具 ====================

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

    @Tool("Verify if coordinates are within screen")
    public String verifyClickPosition(@P("Coordinates [x, y]") int[] coords) {
        if (coords == null || coords.length < 2) return "❌ 错误: 坐标无效";
        try {
            java.awt.Dimension screenSize = screenCapturer.getScreenSize();
            Point logical = toLogicalPoint(coords);
            if (logical == null) return "❌ 错误: 坐标无效";
            boolean inRange = logical.x >= 0 && logical.x < screenSize.width &&
                    logical.y >= 0 && logical.y < screenSize.height;
            return inRange
                    ? String.format("✅ 坐标有效：逻辑(%d,%d) in %dx%d（输入Gemini:%d,%d）",
                    logical.x, logical.y, screenSize.width, screenSize.height, coords[0], coords[1])
                    : String.format("⚠️ 坐标超出屏幕范围：逻辑(%d,%d) vs %dx%d（输入Gemini:%d,%d）",
                    logical.x, logical.y, screenSize.width, screenSize.height, coords[0], coords[1]);
        } catch (Exception e) {
            return "❌ 验证失败: " + e.getMessage();
        }
    }
}