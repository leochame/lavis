package com.lavis.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.agent.AgentTools;
import com.lavis.feature.skills.SkillService;
import com.lavis.feature.skills.SkillExecutor;
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
    private final SkillService skillService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 基础工具规格列表（供 LLM 使用） */
    @Getter
    private List<ToolSpecification> toolSpecifications;

    /** Skill 工具规格列表（来自 SkillService，实时更新） */
    private final List<ToolSpecification> skillToolSpecifications = new ArrayList<>();
    
    /** 基础工具名称 -> Method 映射 */
    private Map<String, Method> toolMethods;

    public ToolExecutionService(AgentTools agentTools, SkillService skillService) {
        this.agentTools = agentTools;
        this.skillService = skillService;
    }

    @PostConstruct
    public void init() {
        // 初始化基础工具规格
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(agentTools);

        // 建立工具名称到方法的映射
        this.toolMethods = new HashMap<>();
        for (Method method : AgentTools.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                toolMethods.put(method.getName(), method);
            }
        }

        // 初始化 Skill 工具规格
        this.skillToolSpecifications.clear();
        this.skillToolSpecifications.addAll(skillService.getToolSpecifications());

        // 注册 Skill 工具更新监听器，保持与 SkillService 同步
        skillService.addToolUpdateListener(newTools -> {
            synchronized (skillToolSpecifications) {
                skillToolSpecifications.clear();
                skillToolSpecifications.addAll(newTools);
            }
        });
    }

    // ==================== 统一工具视图 ====================

    /**
     * 获取基础工具数量（不含 Skill 工具）
     */
    public int getToolCount() {
        return toolMethods != null ? toolMethods.size() : 0;
    }

    /**
     * 获取所有基础工具名称
     */
    public Set<String> getToolNames() {
        return toolMethods != null ? toolMethods.keySet() : Set.of();
    }

    /**
     * 获取合并后的工具规格列表（基础工具 + Skill 工具）
     */
    public List<ToolSpecification> getCombinedToolSpecifications() {
        List<ToolSpecification> combined = new ArrayList<>();
        if (toolSpecifications != null) {
            combined.addAll(toolSpecifications);
        }
        synchronized (skillToolSpecifications) {
            combined.addAll(skillToolSpecifications);
        }
        return combined;
    }

    /**
     * 统一执行入口：根据名称路由到基础工具或 Skill 工具。
     */
    public String executeUnified(String toolName, String argsJson) {
        // 基础工具优先
        if (hasTool(toolName)) {
            return executeBaseTool(toolName, argsJson);
        }

        // Skill 工具
        if (isSkillTool(toolName)) {
            SkillExecutor.ExecutionResult result = skillService.executeByToolName(toolName, argsJson);
            return result.isSuccess()
                    ? result.getOutput()
                    : " " + result.getError();
        }

        return " Unknown tool: " + toolName;
    }

    /**
     * 通过反射执行基础工具方法
     */
    private String executeBaseTool(String toolName, String argsJson) {
        try {
            Method method = toolMethods.get(toolName);
            if (method == null) {
                return "Error: Tool not found " + toolName;
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
            return result != null ? result.toString() : "Execution completed";

        } catch (Exception e) {
            log.error("工具执行失败: {} - {}", toolName, e.getMessage(), e);
            return "Tool execution error: " + e.getMessage();
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
        // 基础工具的判断逻辑保持不变
        if (hasTool(toolName)) {
            return switch (toolName) {
                // 鼠标操作 - 影响屏幕
                case "click", "doubleClick", "rightClick", "drag" -> true;
                // 键盘操作 - 影响屏幕
                case "type_text_at", "keyCombination" -> true;
                // 系统操作 - 影响屏幕
                case "openApplication", "quitApplication", "openURL", "openFile" -> true;
                case "scroll" -> true;
                case "executeAppleScript", "executeShell", "revealInFinder" -> true;
                // wait 通常用于等待屏幕状态变化，需要重新截图以观察变化
                case "wait" -> true;
                // 这些工具只是获取信息，不改变屏幕
                case "moveMouse" -> true;
                case "getMouseInfo", "captureScreen" -> false;
                case "getActiveApp", "getActiveWindowTitle" -> false;
                case "showNotification" -> false;
                // 认知类工具：仅记录思考，不改变屏幕
                case "think_tool" -> false;
                // 任务终止标记工具：只写日志，不改变屏幕
                case "complete_tool" -> false;
                // 搜索工具 - 不影响屏幕
                case "internetSearch", "quickSearch" -> false;
                // 未知基础工具默认认为有影响
                default -> true;
            };
        }

        // Skill 工具默认认为有视觉影响（可能触发 agent: 命令）
        if (isSkillTool(toolName)) {
            return true;
        }

        // 未知工具默认认为有影响
        return true;
    }

    /**
     * 检查是否为基础工具
     */
    public boolean hasTool(String toolName) {
        return toolMethods != null && toolMethods.containsKey(toolName);
    }

    /**
     * 判断是否为 Skill 工具（基于当前 Skill ToolSpecifications）
     */
    public boolean isSkillTool(String toolName) {
        synchronized (skillToolSpecifications) {
            return skillToolSpecifications.stream()
                    .anyMatch(spec -> spec.name().equals(toolName));
        }
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

