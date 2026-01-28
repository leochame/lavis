package com.lavis.skills.event;

import com.lavis.skills.model.SkillToolDefinition;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Skills 更新事件。
 *
 * 当 SkillLoader 检测到文件变更时触发此事件，
 * AgentService 监听此事件以动态更新工具列表。
 *
 * 这是实现"真正热重载"的关键：
 * - 文件变更 -> SkillLoader 检测 -> 发布事件 -> AgentService 更新工具
 */
@Getter
public class SkillsUpdatedEvent extends ApplicationEvent {

    /** 更新后的所有技能工具定义 */
    private final List<SkillToolDefinition> skillTools;

    /** 更新类型 */
    private final UpdateType updateType;

    /** 变更的技能名称（可选） */
    private final String changedSkillName;

    public enum UpdateType {
        /** 初始加载 */
        INITIAL_LOAD,
        /** 新增技能 */
        SKILL_ADDED,
        /** 技能修改 */
        SKILL_MODIFIED,
        /** 技能删除 */
        SKILL_REMOVED,
        /** 完全重载 */
        FULL_RELOAD
    }

    public SkillsUpdatedEvent(Object source, List<SkillToolDefinition> skillTools,
                               UpdateType updateType, String changedSkillName) {
        super(source);
        this.skillTools = skillTools;
        this.updateType = updateType;
        this.changedSkillName = changedSkillName;
    }

    public SkillsUpdatedEvent(Object source, List<SkillToolDefinition> skillTools, UpdateType updateType) {
        this(source, skillTools, updateType, null);
    }
}
