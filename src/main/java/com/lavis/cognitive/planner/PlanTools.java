package com.lavis.cognitive.planner;

import com.lavis.cognitive.model.PlanStep;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划工具集 - 用于 Tool Call 方式生成待办事项列表
 * 
 * 设计：使用单次工具调用模式，一次性接收所有待办事项数组
 * 优点：
 * - 完全结构化，无需 JSON 解析
 * - 类型安全，框架自动验证
 * - 单次调用，更简洁高效
 */
@Slf4j
@Component
public class PlanTools {

    /** 收集的步骤列表 */
    @Getter
    private final List<PlanStep> collectedSteps = new ArrayList<>();

    /**
     * 添加待办事项列表到任务计划
     * 
     * 一次性接收所有待办事项，每个待办事项应该是里程碑级的任务描述
     * 
     * @param todoItems 待办事项描述数组 - 里程碑级的任务描述，例如："导航到个人主页"、"完成发布表单填写并提交"
     * @return 确认消息
     */
    @Tool("Create a todo list for the task. Call this tool once with an array of todo items. Each item should be a milestone-level task description (what to do, not how to do it).")
    public String createTodoList(
            @P("Array of todo item descriptions - milestone-level task descriptions, e.g., ['Navigate to profile page', 'Complete and submit the form']. Should describe what to do, not specific coordinates or atomic actions.") String[] todoItems
    ) {
        // 清空之前的步骤
        collectedSteps.clear();
        
        // 为每个待办事项创建步骤
        for (int i = 0; i < todoItems.length; i++) {
            PlanStep step = PlanStep.builder()
                    .id(i + 1)
                    .description(todoItems[i])
                    .build();
            collectedSteps.add(step);
            log.debug("📝 添加待办事项: Todo[{}] - {}", i + 1, todoItems[i]);
        }
        
        return String.format("Todo list created with %d items", todoItems.length);
    }

    /**
     * 清空收集的待办事项（用于新待办列表生成前）
     */
    public void clear() {
        collectedSteps.clear();
        log.debug("🔄 PlanTools 待办事项列表已清空");
    }


    public void addTodoItem(PlanStep fallbackStep) {
        collectedSteps.add(fallbackStep);
    }
}


