package com.lavis.cognitive;

import com.lavis.action.AppleScriptExecutor;
import com.lavis.action.RobotDriver;
import com.lavis.perception.ScreenCapturer;
import dev.langchain4j.agent.tool.Tool;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * M2 思考模块 - AI 可调用的工具集
 * 使用 LangChain4j @Tool 注解定义
 * 
 * 【重要改进】
 * - 所有操作返回详细的执行结果，包括偏差信息
 * - 集成 TaskContext 进行上下文管理和死循环检测
 * - 支持 Action-Observation-Correction 反思循环
 */
@Slf4j
@Component
public class AgentTools {

    private final RobotDriver robotDriver;
    private final AppleScriptExecutor appleScriptExecutor;
    private final ScreenCapturer screenCapturer;
    
    // 任务上下文（由 AgentService 注入）
    @Setter
    private TaskContext taskContext;
    
    public AgentTools(RobotDriver robotDriver, AppleScriptExecutor appleScriptExecutor, 
                      ScreenCapturer screenCapturer) {
        this.robotDriver = robotDriver;
        this.appleScriptExecutor = appleScriptExecutor;
        this.screenCapturer = screenCapturer;
    }

    // ==================== 鼠标操作 ====================

    @Tool("将鼠标移动到屏幕上的指定位置。x和y是逻辑屏幕坐标，参考截图中红色十字显示的坐标进行定位。返回移动结果。")
    public String moveMouse(int x, int y) {
        try {
            // 检查是否有重复操作
            if (taskContext != null && taskContext.hasRecentSameAction("moveMouse", x, y, 20)) {
                log.warn("⚠️ 检测到重复移动操作: ({},{})", x, y);
            }
            
            RobotDriver.ExecutionResult result = robotDriver.moveTo(x, y);
            
            // 记录到上下文
            if (taskContext != null) {
                Map<String, Object> params = new HashMap<>();
                params.put("x", x);
                params.put("y", y);
                TaskContext.ExecutionDetails details = new TaskContext.ExecutionDetails();
                details.setDeviation(result.getDeviationDescription());
                details.setTargetHit(result.isSuccess());
                details.setExecutionTimeMs(result.getExecutionTimeMs());
                
                TaskContext.ActionResult actionResult = taskContext.recordAction(
                    "moveMouse", params, result.isSuccess(), result.getMessage(), details);
                return actionResult.toFeedback();
            }
            
            return result.toFeedback();
        } catch (Exception e) {
            log.error("鼠标移动失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }

    @Tool("在屏幕上的指定位置执行鼠标左键单击。x和y是逻辑屏幕坐标，参考截图中红色十字显示的坐标进行定位。如果同一位置多次点击无效，请尝试调整坐标（基于当前位置微调5-30像素）或使用双击。")
    public String click(int x, int y) {
        try {
            // 检查是否有重复操作
            if (taskContext != null && taskContext.hasRecentSameAction("click", x, y, 20)) {
                log.warn("⚠️ 检测到重复点击操作: ({},{})，建议调整坐标或尝试其他操作", x, y);
            }
            
            RobotDriver.ExecutionResult result = robotDriver.clickAt(x, y);
            
            // 记录点击位置，用于下次截图时显示标记
            screenCapturer.recordClickPosition(x, y);
            
            // 记录到上下文
            if (taskContext != null) {
                Map<String, Object> params = new HashMap<>();
                params.put("x", x);
                params.put("y", y);
                TaskContext.ExecutionDetails details = new TaskContext.ExecutionDetails();
                details.setDeviation(result.getDeviationDescription());
                details.setTargetHit(result.isSuccess());
                details.setExecutionTimeMs(result.getExecutionTimeMs());
                
                TaskContext.ActionResult actionResult = taskContext.recordAction(
                    "click", params, result.isSuccess(), result.getMessage(), details);
                return actionResult.toFeedback();
            }
            
            return result.toFeedback();
        } catch (Exception e) {
            log.error("点击失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }

    @Tool("在屏幕上的指定位置执行鼠标双击。x和y是逻辑屏幕坐标，参考截图中红色十字显示的坐标进行定位。当单击无效时可尝试双击。")
    public String doubleClick(int x, int y) {
        try {
            robotDriver.doubleClickAt(x, y);
            screenCapturer.recordClickPosition(x, y);
            
            String message = String.format("✅ 已在位置 (%d, %d) 执行双击", x, y);
            
            // 记录到上下文
            if (taskContext != null) {
                Map<String, Object> params = new HashMap<>();
                params.put("x", x);
                params.put("y", y);
                taskContext.recordAction("doubleClick", params, true, message, null);
            }
            
            return message;
        } catch (Exception e) {
            log.error("双击失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }

    @Tool("在屏幕上的指定位置执行鼠标右键单击。x和y是逻辑屏幕坐标，参考截图中红色十字显示的坐标进行定位。用于打开右键菜单。")
    public String rightClick(int x, int y) {
        try {
            robotDriver.rightClickAt(x, y);
            screenCapturer.recordClickPosition(x, y);
            
            String message = String.format("✅ 已在位置 (%d, %d) 执行右键单击", x, y);
            
            // 记录到上下文
            if (taskContext != null) {
                Map<String, Object> params = new HashMap<>();
                params.put("x", x);
                params.put("y", y);
                taskContext.recordAction("rightClick", params, true, message, null);
            }
            
            return message;
        } catch (Exception e) {
            log.error("右键点击失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }

    @Tool("从一个位置拖拽到另一个位置。所有坐标都是逻辑屏幕坐标，参考截图中红色十字显示的坐标进行定位。")
    public String drag(int fromX, int fromY, int toX, int toY) {
        try {
            RobotDriver.ExecutionResult result = robotDriver.drag(fromX, fromY, toX, toY);
            
            // 记录到上下文
            if (taskContext != null) {
                Map<String, Object> params = new HashMap<>();
                params.put("fromX", fromX);
                params.put("fromY", fromY);
                params.put("toX", toX);
                params.put("toY", toY);
                TaskContext.ExecutionDetails details = new TaskContext.ExecutionDetails();
                details.setDeviation(result.getDeviationDescription());
                details.setTargetHit(result.isSuccess());
                details.setExecutionTimeMs(result.getExecutionTimeMs());
                
                TaskContext.ActionResult actionResult = taskContext.recordAction(
                    "drag", params, result.isSuccess(), result.getMessage(), details);
                return actionResult.toFeedback();
            }
            
            return result.toFeedback();
        } catch (Exception e) {
            log.error("拖拽失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }

    @Tool("滚动鼠标滚轮。正数向下滚动，负数向上滚动。")
    public String scroll(int amount) {
        try {
            robotDriver.scroll(amount);
            return String.format("已滚动 %d 单位", amount);
        } catch (Exception e) {
            log.error("滚动失败", e);
            return "错误: " + e.getMessage();
        }
    }

    // ==================== 键盘操作 ====================

    @Tool("输入文本内容。支持中英文。在调用前请确保已点击到正确的输入框。")
    public String typeText(String text) {
        try {
            robotDriver.type(text);
            return String.format("已输入文本: %s", text);
        } catch (Exception e) {
            log.error("输入文本失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("按下回车键 (Enter)")
    public String pressEnter() {
        try {
            robotDriver.pressEnter();
            return "已按下 Enter 键";
        } catch (Exception e) {
            log.error("按键失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("按下 Escape 键，通常用于关闭对话框或取消操作")
    public String pressEscape() {
        try {
            robotDriver.pressEscape();
            return "已按下 Escape 键";
        } catch (Exception e) {
            log.error("按键失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("按下 Tab 键，用于切换焦点")
    public String pressTab() {
        try {
            robotDriver.pressTab();
            return "已按下 Tab 键";
        } catch (Exception e) {
            log.error("按键失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("按下退格键 (Backspace)，删除光标前的字符")
    public String pressBackspace() {
        try {
            robotDriver.pressBackspace();
            return "已按下 Backspace 键";
        } catch (Exception e) {
            log.error("按键失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("执行复制操作 (Command+C)")
    public String copy() {
        try {
            robotDriver.copy();
            return "已执行复制操作";
        } catch (Exception e) {
            log.error("复制失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("执行粘贴操作 (Command+V)")
    public String paste() {
        try {
            robotDriver.paste();
            return "已执行粘贴操作";
        } catch (Exception e) {
            log.error("粘贴失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("执行全选操作 (Command+A)")
    public String selectAll() {
        try {
            robotDriver.selectAll();
            return "已执行全选操作";
        } catch (Exception e) {
            log.error("全选失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("执行保存操作 (Command+S)")
    public String save() {
        try {
            robotDriver.save();
            return "已执行保存操作";
        } catch (Exception e) {
            log.error("保存失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("执行撤销操作 (Command+Z)")
    public String undo() {
        try {
            robotDriver.undo();
            return "已执行撤销操作";
        } catch (Exception e) {
            log.error("撤销失败", e);
            return "错误: " + e.getMessage();
        }
    }

    // ==================== 系统操作 ====================

    @Tool("打开指定的应用程序")
    public String openApplication(String appName) {
        try {
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.openApplication(appName);
            return result.success() ? String.format("已打开应用: %s", appName) : "打开失败: " + result.output();
        } catch (Exception e) {
            log.error("打开应用失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("关闭指定的应用程序")
    public String quitApplication(String appName) {
        try {
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.quitApplication(appName);
            return result.success() ? String.format("已关闭应用: %s", appName) : "关闭失败: " + result.output();
        } catch (Exception e) {
            log.error("关闭应用失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("获取当前活动的应用程序名称")
    public String getActiveApp() {
        try {
            String appName = appleScriptExecutor.getActiveApplication();
            return appName != null ? String.format("当前活动应用: %s", appName) : "无法获取当前应用";
        } catch (Exception e) {
            log.error("获取活动应用失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("获取当前活动窗口的标题")
    public String getActiveWindowTitle() {
        try {
            String title = appleScriptExecutor.getActiveWindowTitle();
            return title != null ? String.format("当前窗口标题: %s", title) : "无法获取窗口标题";
        } catch (Exception e) {
            log.error("获取窗口标题失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("在默认浏览器中打开指定的 URL")
    public String openURL(String url) {
        try {
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.openURL(url);
            return result.success() ? String.format("已打开 URL: %s", url) : "打开失败: " + result.output();
        } catch (Exception e) {
            log.error("打开 URL 失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("打开指定路径的文件")
    public String openFile(String filePath) {
        try {
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.openFile(filePath);
            return result.success() ? String.format("已打开文件: %s", filePath) : "打开失败: " + result.output();
        } catch (Exception e) {
            log.error("打开文件失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("在 Finder 中显示并选中指定文件")
    public String revealInFinder(String filePath) {
        try {
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.revealInFinder(filePath);
            return result.success() ? String.format("已在 Finder 中显示: %s", filePath) : "显示失败: " + result.output();
        } catch (Exception e) {
            log.error("在 Finder 中显示失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("显示系统通知")
    public String showNotification(String title, String message) {
        try {
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.showNotification(title, message);
            return result.success() ? "通知已显示" : "通知显示失败: " + result.output();
        } catch (Exception e) {
            log.error("显示通知失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("执行 AppleScript 脚本，用于执行复杂的 macOS 自动化操作")
    public String executeAppleScript(String script) {
        try {
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.executeAppleScript(script);
            return result.success() ? "执行成功: " + result.output() : "执行失败: " + result.output();
        } catch (Exception e) {
            log.error("执行 AppleScript 失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("执行 Shell 命令")
    public String executeShell(String command) {
        try {
            AppleScriptExecutor.ExecutionResult result = appleScriptExecutor.executeShell(command);
            return result.success() ? "执行成功: " + result.output() : "执行失败: " + result.output();
        } catch (Exception e) {
            log.error("执行 Shell 命令失败", e);
            return "错误: " + e.getMessage();
        }
    }

    // ==================== 感知操作 ====================

    @Tool("获取当前屏幕截图的 Base64 编码，用于视觉分析")
    public String captureScreen() {
        try {
            String base64 = screenCapturer.captureScreenAsBase64();
            return "截图已获取 (Base64长度: " + base64.length() + ")";
        } catch (IOException e) {
            log.error("截图失败", e);
            return "错误: " + e.getMessage();
        }
    }

    @Tool("等待指定的毫秒数")
    public String wait(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return String.format("已等待 %d 毫秒", milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "等待被中断";
        }
    }
    
    // ==================== 诊断工具 ====================
    
    @Tool("获取当前鼠标位置和坐标系统信息，用于调试和校准点击位置。返回鼠标的逻辑坐标。")
    public String getMouseInfo() {
        try {
            java.awt.Point mousePos = java.awt.MouseInfo.getPointerInfo().getLocation();
            java.awt.Dimension screenSize = screenCapturer.getScreenSize();
            
            return String.format("""
                🖱️ 鼠标位置诊断:
                - 当前坐标: (%d, %d)
                - 屏幕尺寸: %d x %d
                
                💡 使用建议:
                - 截图中红色十字显示的坐标就是当前鼠标位置
                - 点击时使用截图中显示的坐标
                - 如果点击偏离目标，基于当前位置微调 5-30 像素""",
                mousePos.x, mousePos.y,
                screenSize.width, screenSize.height);
        } catch (Exception e) {
            log.error("获取鼠标信息失败", e);
            return "错误: " + e.getMessage();
        }
    }
    
    @Tool("验证点击坐标：输入逻辑屏幕坐标，检查是否在屏幕范围内")
    public String verifyClickPosition(int x, int y) {
        try {
            java.awt.Dimension screenSize = screenCapturer.getScreenSize();
            
            boolean inRange = x >= 0 && x <= screenSize.width &&
                             y >= 0 && y <= screenSize.height;
            
            return String.format("""
                🎯 坐标验证:
                - 输入坐标: (%d, %d)
                - 屏幕范围: 0-%d x 0-%d
                - 是否在屏幕内: %s
                
                %s""",
                x, y,
                screenSize.width, screenSize.height,
                inRange ? "✅ 是" : "❌ 否（超出范围！）",
                inRange ? "此坐标可以安全点击" : "⚠️ 请调整坐标到有效范围内");
        } catch (Exception e) {
            log.error("验证坐标失败", e);
            return "错误: " + e.getMessage();
        }
    }
}

