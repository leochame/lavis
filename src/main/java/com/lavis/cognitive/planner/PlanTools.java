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
 * 规划工具集 - 用于 Tool Call 方式生成计划
 * 
 * 设计：使用多次工具调用模式，让模型逐个添加步骤
 * 优点：
 * - 完全结构化，无需 JSON 解析
 * - 类型安全，框架自动验证
 * - 在长上下文中更可靠
 */
@Slf4j
@Component
public class PlanTools {

    /** 收集的步骤列表 */
    @Getter
    private final List<PlanStep> collectedSteps = new ArrayList<>();

    /**
     * 添加一个步骤到任务计划
     * 
     * 模型会多次调用此工具来构建完整的计划
     * 
     * @param id 步骤 ID（从 1 开始）
     * @param desc 步骤描述 - 里程碑级的任务描述，例如："导航到个人主页"、"完成发布表单填写并提交"
     * @return 确认消息
     */
    @Tool("Add a step to the task plan. Call this tool multiple times to build the complete plan. Each step should be a milestone-level task description (what to do, not how to do it).")
    public String addPlanStep(
            @P("Step ID (starting from 1)") int id,
            @P("Step description - milestone-level task description, e.g., 'Navigate to profile page', 'Complete and submit the form'. Should describe what to do, not specific coordinates or atomic actions.") String desc
    ) {
        // 创建步骤
        PlanStep step = PlanStep.builder()
                .id(id)
                .description(desc)
                .build();
        
        collectedSteps.add(step);
        
        log.debug("📝 添加计划步骤: Step[{}] - {}", id, desc);
        return String.format("Step %d added: %s", id, desc);
    }

    /**
     * 清空收集的步骤（用于新计划生成前）
     */
    public void clear() {
        collectedSteps.clear();
        log.debug("🔄 PlanTools 步骤列表已清空");
    }
}


