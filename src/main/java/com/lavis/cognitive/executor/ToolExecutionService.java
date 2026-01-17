package com.lavis.cognitive.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.cognitive.AgentTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 工具执行服务 - 统一封装工具调用逻辑
 * 
 * 职责：
 * 1. 管理工具元数据（toolSpecifications, toolMethods）
 * 2. 通过反射执行工具方法
 * 3. 参数解析和类型转换
 * 4. 判断工具是否影响屏幕（用于决定是否重新截图）
 * 
 * 设计原则：
 * - 无状态 Singleton，可被多个服务共享
 * - AgentTools 是纯粹的"工具箱"，本服务负责"工具调度"
 */
@Slf4j
@Service
public class ToolExecutionService {

    private final AgentTools agentTools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具规格列表（供 LLM 使用） */
    @Getter
    private List<ToolSpecification> toolSpecifications;
    
    /** 工具名称 -> Method 映射 */
    private Map<String, Method> toolMethods;

    public ToolExecutionService(AgentTools agentTools) {
        this.agentTools = agentTools;
    }

    @PostConstruct
    public void init() {
        // 初始化工具规格
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(agentTools);

        // 建立工具名称到方法的映射
        this.toolMethods = new HashMap<>();
        for (Method method : AgentTools.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                toolMethods.put(method.getName(), method);
            }
        }

        log.info("✅ ToolExecutionService 初始化完成，工具数: {}", toolSpecifications.size());
        log.info("📦 可用工具: {}", toolMethods.keySet());
    }

    /**
     * 获取工具数量
     */
    public int getToolCount() {
        return toolMethods != null ? toolMethods.size() : 0;
    }

    /**
     * 获取所有工具名称
     */
    public Set<String> getToolNames() {
        return toolMethods != null ? toolMethods.keySet() : Set.of();
    }

    /**
     * 通过反射执行工具方法
     * 
     * @param toolName 工具名称
     * @param argsJson 参数 JSON 字符串
     * @return 执行结果字符串
     */
    public String execute(String toolName, String argsJson) {
        try {
            Method method = toolMethods.get(toolName);
            if (method == null) {
                return "错误: 未找到工具 " + toolName;
            }

            // 解析参数
            JsonNode argsNode = objectMapper.readTree(argsJson);
            Class<?>[] paramTypes = method.getParameterTypes();
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < params.length; i++) {
                String paramName = params[i].getName();
                JsonNode valueNode = argsNode.get(paramName);

                if (valueNode == null) {
                    // 尝试用参数位置匹配
                    Iterator<JsonNode> elements = argsNode.elements();
                    int idx = 0;
                    while (elements.hasNext() && idx <= i) {
                        if (idx == i) {
                            valueNode = elements.next();
                            break;
                        }
                        elements.next();
                        idx++;
                    }
                }

                if (valueNode != null) {
                    args[i] = convertValue(valueNode, paramTypes[i]);
                } else {
                    args[i] = getDefaultValue(paramTypes[i]);
                }
            }

            // 调用方法
            Object result = method.invoke(agentTools, args);
            return result != null ? result.toString() : "执行完成";

        } catch (Exception e) {
            log.error("工具执行失败: {} - {}", toolName, e.getMessage(), e);
            return "工具执行错误: " + e.getMessage();
        }
    }

    /**
     * 判断工具是否可能影响屏幕显示
     * 
     * 用于决定工具执行后是否需要重新截图
     * 
     * @param toolName 工具名称
     * @return true 表示可能影响屏幕，需要重新截图
     */
    public boolean isVisualImpactTool(String toolName) {
        return switch (toolName) {
            // 鼠标操作 - 影响屏幕
            case "click", "doubleClick", "rightClick", "drag" -> true;
            // 键盘操作 - 影响屏幕
            case "typeText", "pressEnter", "pressTab", "pressEscape", "pressBackspace" -> true;
            // 系统操作 - 影响屏幕
            case "openApplication", "quitApplication", "openURL", "openFile" -> true;
            case "scroll", "paste", "selectAll", "save", "undo" -> true;
            case "executeAppleScript", "executeShell", "revealInFinder" -> true;
            // wait 通常用于等待屏幕状态变化，需要重新截图以观察变化
            case "wait" -> true;
            // 这些工具只是获取信息，不改变屏幕
            case "moveMouse" -> true;
            case "getMouseInfo", "verifyClickPosition", "captureScreen" -> false;
            case "getActiveApp", "getActiveWindowTitle", "copy" -> false;
            case "showNotification" -> false;
            // 未知工具默认认为有影响
            default -> true;
        };
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String toolName) {
        return toolMethods != null && toolMethods.containsKey(toolName);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 转换 JSON 值到 Java 类型
     */
    private Object convertValue(JsonNode node, Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return node.asInt();
        } else if (type == long.class || type == Long.class) {
            return node.asLong();
        } else if (type == double.class || type == Double.class) {
            return node.asDouble();
        } else if (type == boolean.class || type == Boolean.class) {
            return node.asBoolean();
        } else if (type == String.class) {
            return node.asText();
        }
        // === 新增：处理 int[] 数组 ===
        else if (type == int[].class && node.isArray()) {
            int[] arr = new int[node.size()];
            for (int i = 0; i < node.size(); i++) {
                arr[i] = node.get(i).asInt();
            }
            return arr;
        }

        return node.asText();
    }
    /**
     * 获取基本类型的默认值
     */
    private Object getDefaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == boolean.class) return false;
        return null;
    }
}

