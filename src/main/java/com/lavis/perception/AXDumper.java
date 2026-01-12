package com.lavis.perception;

import com.lavis.action.AppleScriptExecutor;
import com.lavis.action.AppleScriptExecutor.ExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M1 感知模块 - UI 结构提取器
 * 通过 AppleScript 调用 macOS Accessibility API 获取 UI 元素信息
 * 
 * Level 1 (快速): 获取当前前台窗口基本信息
 * Level 2 (深度): 递归扫描窗口内所有可交互元素
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AXDumper {

    private final AppleScriptExecutor appleScriptExecutor;
    
    // 缓存 UI 元素 (ID -> UIElement)
    private final Map<String, UIElement> elementCache = new ConcurrentHashMap<>();
    
    // 上次扫描的元素列表
    private volatile List<UIElement> lastScanResult = new ArrayList<>();
    
    // 元素计数器 (用于生成唯一 ID)
    private int elementCounter = 0;
    
    // 支持的可交互角色
    private static final Set<String> INTERACTIVE_ROLES = Set.of(
        "AXButton", "AXPopUpButton", "AXMenuButton", "AXRadioButton",
        "AXCheckBox", "AXTextField", "AXTextArea", "AXSearchField",
        "AXSecureTextField", "AXLink", "AXSlider", "AXIncrementor",
        "AXComboBox", "AXTabGroup", "AXTab", "AXMenuItem"
    );
    
    /**
     * Level 1: 快速获取当前活动窗口信息
     */
    public WindowInfo getActiveWindowInfo() {
        String script = """
            tell application "System Events"
                set frontApp to first application process whose frontmost is true
                set appName to name of frontApp
                
                tell frontApp
                    if (count of windows) > 0 then
                        set frontWindow to window 1
                        set winName to name of frontWindow
                        set winPos to position of frontWindow
                        set winSize to size of frontWindow
                        return appName & "|" & winName & "|" & (item 1 of winPos) & "|" & (item 2 of winPos) & "|" & (item 1 of winSize) & "|" & (item 2 of winSize)
                    else
                        return appName & "|NO_WINDOW|0|0|0|0"
                    end if
                end tell
            end tell
            """;
        
        ExecutionResult result = appleScriptExecutor.executeAppleScript(script);
        
        if (result.success() && !result.output().isEmpty()) {
            String[] parts = result.output().split("\\|");
            if (parts.length >= 6) {
                return new WindowInfo(
                    parts[0].trim(),  // appName
                    parts[1].trim(),  // windowTitle
                    parseInt(parts[2]),  // x
                    parseInt(parts[3]),  // y
                    parseInt(parts[4]),  // width
                    parseInt(parts[5])   // height
                );
            }
        }
        
        log.warn("无法获取窗口信息: {}", result.output());
        return null;
    }
    
    /**
     * Level 2: 深度扫描 - 获取当前窗口所有可交互 UI 元素
     * @param maxElements 最大元素数量限制
     */
    public List<UIElement> scanInteractiveElements(int maxElements) {
        log.info("开始扫描 UI 元素 (最大: {})", maxElements);
        long startTime = System.currentTimeMillis();
        
        elementCache.clear();
        elementCounter = 0;
        List<UIElement> elements = new ArrayList<>();
        
        // 首先获取窗口信息
        WindowInfo windowInfo = getActiveWindowInfo();
        if (windowInfo == null) {
            log.warn("无法获取窗口信息，跳过扫描");
            return elements;
        }
        
        // 使用优化的 AppleScript 批量获取元素
        String script = buildElementScanScript(maxElements);
        ExecutionResult result = appleScriptExecutor.executeAppleScript(script, 30);
        
        if (result.success() && !result.output().isEmpty()) {
            elements = parseElementOutput(result.output(), windowInfo);
        }
        
        // 更新缓存
        lastScanResult = new ArrayList<>(elements);
        for (UIElement element : elements) {
            elementCache.put(element.getId(), element);
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("UI 扫描完成: {} 个元素, 耗时 {}ms", elements.size(), duration);
        
        return elements;
    }
    
    /**
     * 快速扫描 - 仅获取主要可交互元素
     */
    public List<UIElement> quickScan() {
        return scanInteractiveElements(50);
    }
    
    /**
     * 完整扫描 - 获取所有 UI 元素
     */
    public List<UIElement> fullScan() {
        return scanInteractiveElements(200);
    }
    
    /**
     * 根据 ID 获取缓存的元素
     */
    public UIElement getElementById(String id) {
        return elementCache.get(id);
    }
    
    /**
     * 获取上次扫描的结果
     */
    public List<UIElement> getLastScanResult() {
        return new ArrayList<>(lastScanResult);
    }
    
    /**
     * 根据名称模糊查找元素
     */
    public List<UIElement> findElementsByName(String namePart) {
        String lowerPart = namePart.toLowerCase();
        return lastScanResult.stream()
            .filter(e -> e.getName() != null && e.getName().toLowerCase().contains(lowerPart))
            .toList();
    }
    
    /**
     * 根据角色类型查找元素
     */
    public List<UIElement> findElementsByRole(String role) {
        return lastScanResult.stream()
            .filter(e -> role.equals(e.getRole()))
            .toList();
    }
    
    /**
     * 查找指定坐标附近的元素
     */
    public UIElement findElementAtPoint(int x, int y) {
        for (UIElement element : lastScanResult) {
            if (element.contains(x, y)) {
                return element;
            }
        }
        return null;
    }
    
    /**
     * 构建元素扫描的 AppleScript
     */
    private String buildElementScanScript(int maxElements) {
        return String.format("""
            set outputList to {}
            set elementCount to 0
            set maxElements to %d
            
            tell application "System Events"
                set frontApp to first application process whose frontmost is true
                set appName to name of frontApp
                
                tell frontApp
                    if (count of windows) > 0 then
                        set frontWindow to window 1
                        set winName to name of frontWindow
                        
                        -- 获取所有 UI 元素
                        repeat with uiElement in entire contents of frontWindow
                            if elementCount >= maxElements then exit repeat
                            
                            try
                                set elemRole to role of uiElement
                                
                                -- 只处理可交互元素
                                if elemRole is in {"AXButton", "AXPopUpButton", "AXTextField", "AXTextArea", "AXSearchField", "AXSecureTextField", "AXLink", "AXCheckBox", "AXRadioButton", "AXSlider", "AXComboBox", "AXMenuItem", "AXStaticText", "AXImage"} then
                                    
                                    set elemName to ""
                                    set elemValue to ""
                                    set elemDesc to ""
                                    set elemX to 0
                                    set elemY to 0
                                    set elemW to 0
                                    set elemH to 0
                                    set isEnabled to true
                                    
                                    try
                                        set elemName to name of uiElement
                                    end try
                                    
                                    try
                                        set elemValue to value of uiElement as text
                                    end try
                                    
                                    try
                                        set elemDesc to description of uiElement
                                    end try
                                    
                                    try
                                        set elemPos to position of uiElement
                                        set elemX to item 1 of elemPos
                                        set elemY to item 2 of elemPos
                                    end try
                                    
                                    try
                                        set elemSize to size of uiElement
                                        set elemW to item 1 of elemSize
                                        set elemH to item 2 of elemSize
                                    end try
                                    
                                    try
                                        set isEnabled to enabled of uiElement
                                    end try
                                    
                                    -- 只保留有位置信息的元素
                                    if elemW > 0 and elemH > 0 then
                                        set elemInfo to elemRole & "§" & elemName & "§" & elemValue & "§" & elemDesc & "§" & elemX & "§" & elemY & "§" & elemW & "§" & elemH & "§" & isEnabled
                                        set end of outputList to elemInfo
                                        set elementCount to elementCount + 1
                                    end if
                                end if
                            end try
                        end repeat
                    end if
                end tell
            end tell
            
            -- 返回用换行分隔的结果
            set AppleScript's text item delimiters to linefeed
            return outputList as text
            """, maxElements);
    }
    
    /**
     * 解析 AppleScript 输出为 UIElement 列表
     */
    private List<UIElement> parseElementOutput(String output, WindowInfo windowInfo) {
        List<UIElement> elements = new ArrayList<>();
        String[] lines = output.split("\n");
        
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            String[] parts = line.split("§");
            if (parts.length >= 9) {
                try {
                    String role = parts[0].trim();
                    String name = parts[1].trim();
                    String value = parts[2].trim();
                    String desc = parts[3].trim();
                    int x = parseInt(parts[4]);
                    int y = parseInt(parts[5]);
                    int w = parseInt(parts[6]);
                    int h = parseInt(parts[7]);
                    boolean enabled = "true".equalsIgnoreCase(parts[8].trim());
                    
                    // 生成唯一 ID
                    String id = generateElementId(role);
                    
                    UIElement element = UIElement.builder()
                        .id(id)
                        .role(role)
                        .name(name.isEmpty() ? null : name)
                        .value(value.isEmpty() ? null : value)
                        .description(desc.isEmpty() ? null : desc)
                        .x(x)
                        .y(y)
                        .width(w)
                        .height(h)
                        .enabled(enabled)
                        .clickable(isClickableRole(role))
                        .focusable(isFocusableRole(role))
                        .windowTitle(windowInfo.windowTitle())
                        .appName(windowInfo.appName())
                        .build();
                    
                    elements.add(element);
                } catch (Exception e) {
                    log.debug("解析元素失败: {} - {}", line, e.getMessage());
                }
            }
        }
        
        return elements;
    }
    
    /**
     * 生成元素 ID
     */
    private String generateElementId(String role) {
        String prefix = switch (role) {
            case "AXButton", "AXPopUpButton", "AXMenuButton" -> "btn";
            case "AXTextField", "AXTextArea", "AXSearchField", "AXSecureTextField" -> "txt";
            case "AXLink" -> "lnk";
            case "AXCheckBox", "AXRadioButton" -> "chk";
            case "AXStaticText" -> "lbl";
            case "AXImage" -> "img";
            default -> "elm";
        };
        return prefix + "_" + (elementCounter++);
    }
    
    /**
     * 判断角色是否可点击
     */
    private boolean isClickableRole(String role) {
        return Set.of("AXButton", "AXPopUpButton", "AXMenuButton", "AXLink", 
                      "AXCheckBox", "AXRadioButton", "AXMenuItem").contains(role);
    }
    
    /**
     * 判断角色是否可聚焦
     */
    private boolean isFocusableRole(String role) {
        return Set.of("AXTextField", "AXTextArea", "AXSearchField", 
                      "AXSecureTextField", "AXComboBox").contains(role);
    }
    
    /**
     * 安全解析整数
     */
    private int parseInt(String str) {
        try {
            return (int) Double.parseDouble(str.trim());
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 将 UI 元素列表转换为 JSON 字符串 (用于发送给 LLM)
     */
    public String toJsonForLLM(List<UIElement> elements) {
        if (elements.isEmpty()) {
            return "[]";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < elements.size(); i++) {
            sb.append("  ").append(elements.get(i).toCompactJson());
            if (i < elements.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * 将 UI 元素列表转换为人类可读的文本
     */
    public String toHumanReadable(List<UIElement> elements) {
        if (elements.isEmpty()) {
            return "无可交互元素";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("可交互元素列表:\n");
        for (UIElement element : elements) {
            sb.append("  ").append(element.toHumanReadable()).append("\n");
        }
        return sb.toString();
    }
    
    /**
     * 窗口信息记录
     */
    public record WindowInfo(
        String appName,
        String windowTitle,
        int x,
        int y,
        int width,
        int height
    ) {
        public String toJson() {
            return String.format(
                "{\"app\":\"%s\",\"title\":\"%s\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}",
                appName, windowTitle, x, y, width, height
            );
        }
    }
}

