package com.lavis.action;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * M3 执行模块 - AppleScript 执行器
 * 通过 Runtime.exec 执行 macOS 系统指令
 * 采用指令合并策略优化性能
 */
@Slf4j
@Component
public class AppleScriptExecutor {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public AppleScriptExecutor() {
        log.info("AppleScriptExecutor 初始化完成");
    }

    /**
     * 执行 AppleScript 脚本
     */
    public ExecutionResult executeAppleScript(String script) {
        return executeAppleScript(script, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 执行 AppleScript 脚本 (带超时)
     */
    public ExecutionResult executeAppleScript(String script, int timeoutSeconds) {
        log.info("执行 AppleScript: {}", script.length() > 100 ? script.substring(0, 100) + "..." : script);
        
        try {
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            
            if (!completed) {
                process.destroyForcibly();
                return new ExecutionResult(false, "执行超时", -1);
            }
            
            int exitCode = process.exitValue();
            String result = output.toString().trim();
            
            log.debug("AppleScript 执行结果: exitCode={}, output={}", exitCode, result);
            
            return new ExecutionResult(exitCode == 0, result, exitCode);
            
        } catch (IOException | InterruptedException e) {
            log.error("AppleScript 执行失败", e);
            return new ExecutionResult(false, e.getMessage(), -1);
        }
    }

    /**
     * 执行 Shell 命令
     */
    public ExecutionResult executeShell(String command) {
        return executeShell(command, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 执行 Shell 命令 (带超时)
     * 
     * 改进：使用交互式 shell 模式，加载用户的 shell 配置文件
     * 这样可以访问用户的环境变量、PATH、别名等配置
     */
    public ExecutionResult executeShell(String command, int timeoutSeconds) {
        log.info("执行 Shell: {}", command);
        
        try {
            // 使用 -l (login shell) 或 -i (interactive) 参数加载用户配置
            // 或者通过 source ~/.zshrc 来加载配置
            // 这里使用 -l 参数，它会加载 ~/.zprofile 和 ~/.zshrc
            ProcessBuilder pb = new ProcessBuilder("/bin/zsh", "-l", "-c", command);
            pb.redirectErrorStream(true);
            
            // 继承当前进程的环境变量，确保可以访问系统环境
            pb.environment().putAll(System.getenv());
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            
            if (!completed) {
                process.destroyForcibly();
                return new ExecutionResult(false, "执行超时", -1);
            }
            
            int exitCode = process.exitValue();
            String result = output.toString().trim();
            
            log.debug("Shell 执行结果: exitCode={}, output={}", exitCode, result);
            
            return new ExecutionResult(exitCode == 0, result, exitCode);
            
        } catch (IOException | InterruptedException e) {
            log.error("Shell 执行失败", e);
            return new ExecutionResult(false, e.getMessage(), -1);
        }
    }

    // ==================== 常用系统操作 ====================

    /**
     * 打开应用程序
     */
    public ExecutionResult openApplication(String appName) {
        String script = String.format("tell application \"%s\" to activate", appName);
        return executeAppleScript(script);
    }

    /**
     * 关闭应用程序
     */
    public ExecutionResult quitApplication(String appName) {
        String script = String.format("tell application \"%s\" to quit", appName);
        return executeAppleScript(script);
    }

    /**
     * 获取当前活动应用程序名称
     */
    public String getActiveApplication() {
        String script = """
            tell application "System Events"
                set frontApp to name of first application process whose frontmost is true
            end tell
            return frontApp
            """;
        ExecutionResult result = executeAppleScript(script);
        return result.success ? result.output : null;
    }

    /**
     * 获取当前活动窗口标题
     */
    public String getActiveWindowTitle() {
        String script = """
            tell application "System Events"
                set frontApp to first application process whose frontmost is true
                tell frontApp
                    if (count of windows) > 0 then
                        return name of window 1
                    else
                        return ""
                    end if
                end tell
            end tell
            """;
        ExecutionResult result = executeAppleScript(script);
        return result.success ? result.output : null;
    }

    /**
     * 打开 URL
     */
    public ExecutionResult openURL(String url) {
        String script = String.format("open location \"%s\"", url);
        return executeAppleScript(script);
    }

    /**
     * 打开文件
     */
    public ExecutionResult openFile(String filePath) {
        return executeShell("open \"" + filePath + "\"");
    }

    /**
     * 打开 Finder 并定位到文件
     */
    public ExecutionResult revealInFinder(String filePath) {
        return executeShell("open -R \"" + filePath + "\"");
    }

    /**
     * 显示系统通知
     */
    public ExecutionResult showNotification(String title, String message) {
        String script = String.format(
            "display notification \"%s\" with title \"%s\"",
            message.replace("\"", "\\\""),
            title.replace("\"", "\\\"")
        );
        return executeAppleScript(script);
    }

    /**
     * 显示对话框
     */
    public ExecutionResult showDialog(String message, String title) {
        String script = String.format(
            "display dialog \"%s\" with title \"%s\" buttons {\"OK\"} default button \"OK\"",
            message.replace("\"", "\\\""),
            title.replace("\"", "\\\"")
        );
        return executeAppleScript(script);
    }

    /**
     * 获取剪贴板内容
     */
    public String getClipboard() {
        ExecutionResult result = executeAppleScript("the clipboard");
        return result.success ? result.output : null;
    }

    /**
     * 设置剪贴板内容
     */
    public ExecutionResult setClipboard(String text) {
        String script = String.format("set the clipboard to \"%s\"", text.replace("\"", "\\\""));
        return executeAppleScript(script);
    }

    /**
     * 获取系统音量 (0-100)
     */
    public int getVolume() {
        ExecutionResult result = executeAppleScript("output volume of (get volume settings)");
        if (result.success) {
            try {
                return Integer.parseInt(result.output.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 设置系统音量 (0-100)
     */
    public ExecutionResult setVolume(int level) {
        level = Math.max(0, Math.min(100, level));
        return executeAppleScript(String.format("set volume output volume %d", level));
    }

    /**
     * 切换勿扰模式
     */
    public ExecutionResult toggleDoNotDisturb() {
        // macOS Monterey 及以上版本
        String script = """
            tell application "System Events"
                tell process "ControlCenter"
                    click menu bar item "Control Center" of menu bar 1
                    delay 0.5
                    click checkbox "Focus" of window 1
                end tell
            end tell
            """;
        return executeAppleScript(script);
    }

    /**
     * 截图并保存到指定路径
     */
    public ExecutionResult takeScreenshot(String savePath) {
        return executeShell("screencapture -x \"" + savePath + "\"");
    }

    /**
     * 合并执行多条 AppleScript 命令 (性能优化)
     */
    public ExecutionResult executeBatchAppleScript(String... scripts) {
        StringBuilder combined = new StringBuilder();
        for (String script : scripts) {
            combined.append(script).append("\n");
        }
        return executeAppleScript(combined.toString());
    }

    /**
     * 执行结果包装类
     */
    public record ExecutionResult(boolean success, String output, int exitCode) {
        @Override
        public String toString() {
            return String.format("ExecutionResult{success=%s, exitCode=%d, output='%s'}", 
                success, exitCode, output);
        }
    }
}

