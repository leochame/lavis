package com.lavis.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.entity.AgentSkillEntity;
import com.lavis.skills.dto.CreateSkillRequest;
import com.lavis.skills.dto.SkillResponse;
import com.lavis.skills.dto.UpdateSkillRequest;
import com.lavis.skills.event.SkillsUpdatedEvent;
import com.lavis.skills.model.ParsedSkill;
import com.lavis.skills.model.SkillExecutionContext;
import com.lavis.skills.model.SkillToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * Skill 服务 - 重构版本
 *
 * 核心职责：
 * 1. 维护实时更新的 ToolSpecification 列表（供 AgentService 使用）
 * 2. 处理 Skill 的 CRUD 操作
 * 3. 执行 Skill（带上下文注入）
 * 4. 响应热重载事件
 *
 * 这是"技能即工具"架构的核心服务。
 */
@Service
public class SkillService {

    private static final Logger logger = LoggerFactory.getLogger(SkillService.class);

    private final SkillStore skillStore;
    private final SkillLoader skillLoader;
    private final SkillExecutor skillExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 实时更新的工具规格列表 */
    private final List<ToolSpecification> currentToolSpecifications = new CopyOnWriteArrayList<>();

    /** 工具更新监听器 */
    private final List<ToolUpdateListener> toolUpdateListeners = new CopyOnWriteArrayList<>();

    public SkillService(SkillStore skillStore, SkillLoader skillLoader, SkillExecutor skillExecutor) {
        this.skillStore = skillStore;
        this.skillLoader = skillLoader;
        this.skillExecutor = skillExecutor;
    }

    @PostConstruct
    public void init() {
        logger.info("Initializing SkillService...");

        // Load skills from filesystem
        Map<String, ParsedSkill> fileSkills = skillLoader.loadAllSkills();

        // Sync with database
        syncSkillsToDatabase(fileSkills);

        // Initialize tool specifications
        currentToolSpecifications.clear();
        currentToolSpecifications.addAll(skillLoader.getToolSpecifications());

        // Start hot reload watcher
        skillLoader.startWatching();

        logger.info("SkillService initialized with {} skills, {} tool specifications",
                fileSkills.size(), currentToolSpecifications.size());
    }

    @PreDestroy
    public void destroy() {
        skillLoader.stopWatching();
    }

    // ==================== 工具规格管理 ====================

    /**
     * 获取当前所有 Skill 的 ToolSpecification 列表。
     * AgentService 调用此方法获取可用工具。
     */
    public List<ToolSpecification> getToolSpecifications() {
        return Collections.unmodifiableList(currentToolSpecifications);
    }

    /**
     * 获取所有 Skill 的 SkillToolDefinition 列表（用于 API 响应）
     */
    public List<SkillToolDefinition> getSkillToolDefinitions() {
        return skillLoader.getSkillToolDefinitions();
    }

    /**
     * 添加工具更新监听器。
     * AgentService 调用此方法注册监听器，以便在工具列表变更时收到通知。
     */
    public void addToolUpdateListener(ToolUpdateListener listener) {
        toolUpdateListeners.add(listener);
        logger.info("Added tool update listener: {}", listener.getClass().getSimpleName());
    }

    /**
     * 移除工具更新监听器
     */
    public void removeToolUpdateListener(ToolUpdateListener listener) {
        toolUpdateListeners.remove(listener);
    }

    /**
     * 设置上下文注入回调。
     * AgentService 调用此方法注册回调，用于在执行 Skill 时注入知识。
     */
    public void setContextInjectionCallback(BiFunction<SkillExecutionContext, String, String> callback) {
        skillExecutor.setContextInjectionCallback(callback);
    }

    /**
     * 工具更新监听器接口
     */
    @FunctionalInterface
    public interface ToolUpdateListener {
        void onToolsUpdated(List<ToolSpecification> newTools);
    }

    // ==================== 事件处理 ====================

    /**
     * 处理 Skills 更新事件（来自 SkillLoader 的热重载）
     */
    @EventListener
    public void handleSkillsUpdatedEvent(SkillsUpdatedEvent event) {
        logger.info("Received SkillsUpdatedEvent: type={}, changed={}",
                event.getUpdateType(), event.getChangedSkillName());

        // 更新工具规格列表
        currentToolSpecifications.clear();
        currentToolSpecifications.addAll(skillLoader.getToolSpecifications());

        // 同步到数据库
        syncSkillsToDatabase(skillLoader.getLoadedSkills());

        // 通知所有监听器
        notifyToolUpdateListeners();

        logger.info("Tool specifications updated, total: {}", currentToolSpecifications.size());
    }

    /**
     * 通知所有工具更新监听器
     */
    private void notifyToolUpdateListeners() {
        List<ToolSpecification> specs = new ArrayList<>(currentToolSpecifications);
        for (ToolUpdateListener listener : toolUpdateListeners) {
            try {
                listener.onToolsUpdated(specs);
            } catch (Exception e) {
                logger.error("Error notifying tool update listener: {}", e.getMessage());
            }
        }
    }

    // ==================== Skill 执行 ====================

    /**
     * 执行 Skill（通过工具名称，使用 JSON 参数）。
     * 这是 LLM Function Call 的入口点。
     *
     * @param toolName   工具名称（snake_case 格式）
     * @param paramsJson JSON 格式的参数
     * @return 执行结果
     */
    public SkillExecutor.ExecutionResult executeByToolName(String toolName, String paramsJson) {
        // 根据工具名称查找 Skill
        Optional<ParsedSkill> skillOpt = skillLoader.getSkillByToolName(toolName);

        if (skillOpt.isEmpty()) {
            logger.warn("Skill not found for tool name: {}", toolName);
            return new SkillExecutor.ExecutionResult(false, null,
                    "Skill not found: " + toolName, 0);
        }

        ParsedSkill skill = skillOpt.get();

        // 检查是否启用
        Optional<AgentSkillEntity> entityOpt = skillStore.getSkillByName(skill.getName());
        if (entityOpt.isPresent() && !entityOpt.get().getEnabled()) {
            return new SkillExecutor.ExecutionResult(false, null,
                    "Skill is disabled: " + skill.getName(), 0);
        }

        // 执行（带上下文注入）
        SkillExecutor.ExecutionResult result = skillExecutor.executeFromJson(skill, paramsJson);

        // 更新使用统计
        entityOpt.ifPresent(entity -> skillStore.incrementUseCount(entity.getId()));

        return result;
    }

    /**
     * 执行 Skill（通过 ID 或名称，使用 Map 参数）- 兼容旧 API
     */
    public SkillExecutor.ExecutionResult executeSkill(String idOrName, Map<String, String> params) {
        // Try to find by ID first, then by name
        Optional<AgentSkillEntity> entityOpt = skillStore.getSkill(idOrName);
        if (entityOpt.isEmpty()) {
            entityOpt = skillStore.getSkillByName(idOrName);
        }

        if (entityOpt.isEmpty()) {
            return new SkillExecutor.ExecutionResult(false, null, "Skill not found: " + idOrName, 0);
        }

        AgentSkillEntity entity = entityOpt.get();
        if (!entity.getEnabled()) {
            return new SkillExecutor.ExecutionResult(false, null, "Skill is disabled: " + entity.getName(), 0);
        }

        // 优先从 SkillLoader 获取（包含完整的 content）
        Optional<ParsedSkill> loadedSkill = skillLoader.getSkill(entity.getName());
        ParsedSkill skill;

        if (loadedSkill.isPresent()) {
            skill = loadedSkill.get();
        } else {
            // 从数据库实体构建
            skill = ParsedSkill.builder()
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .command(entity.getCommand())
                    .content(entity.getContent())
                    .build();
        }

        // Execute with context injection
        SkillExecutor.ExecutionResult result = skillExecutor.execute(skill, params);

        // Update usage stats
        skillStore.incrementUseCount(entity.getId());

        return result;
    }

    // ==================== CRUD Operations ====================

    public List<SkillResponse> getAllSkills() {
        return skillStore.getAllSkills().stream()
                .map(SkillResponse::from)
                .toList();
    }

    public List<SkillResponse> getEnabledSkills() {
        return skillStore.findEnabledSkills().stream()
                .map(SkillResponse::from)
                .toList();
    }

    public Optional<SkillResponse> getSkill(String id) {
        return skillStore.getSkill(id).map(SkillResponse::from);
    }

    public Optional<SkillResponse> getSkillByName(String name) {
        return skillStore.getSkillByName(name).map(SkillResponse::from);
    }

    public SkillResponse createSkill(CreateSkillRequest request) {
        AgentSkillEntity entity = new AgentSkillEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setCategory(request.getCategory());
        entity.setVersion(request.getVersion());
        entity.setAuthor(request.getAuthor());
        entity.setCommand(request.getCommand());
        entity.setContent(request.getContent());
        entity.setInstallSource("api");
        entity.setEnabled(true);

        AgentSkillEntity saved = skillStore.saveSkill(entity);
        return SkillResponse.from(saved);
    }

    public Optional<SkillResponse> updateSkill(String id, UpdateSkillRequest request) {
        return skillStore.getSkill(id).map(entity -> {
            if (request.getName() != null) entity.setName(request.getName());
            if (request.getDescription() != null) entity.setDescription(request.getDescription());
            if (request.getCategory() != null) entity.setCategory(request.getCategory());
            if (request.getVersion() != null) entity.setVersion(request.getVersion());
            if (request.getAuthor() != null) entity.setAuthor(request.getAuthor());
            if (request.getCommand() != null) entity.setCommand(request.getCommand());
            if (request.getContent() != null) entity.setContent(request.getContent());
            if (request.getEnabled() != null) entity.setEnabled(request.getEnabled());

            AgentSkillEntity saved = skillStore.saveSkill(entity);
            return SkillResponse.from(saved);
        });
    }

    public boolean deleteSkill(String id) {
        if (skillStore.getSkill(id).isPresent()) {
            skillStore.deleteSkill(id);
            return true;
        }
        return false;
    }

    // ==================== Categories ====================

    public List<String> getAllCategories() {
        return skillStore.getAllCategories();
    }

    public List<SkillResponse> getSkillsByCategory(String category) {
        return skillStore.findSkillsByCategory(category).stream()
                .map(SkillResponse::from)
                .toList();
    }

    // ==================== Reload ====================

    public int reloadSkills() {
        Map<String, ParsedSkill> fileSkills = skillLoader.loadAllSkills();
        syncSkillsToDatabase(fileSkills);

        // 更新工具规格
        currentToolSpecifications.clear();
        currentToolSpecifications.addAll(skillLoader.getToolSpecifications());

        // 通知监听器
        notifyToolUpdateListeners();

        return fileSkills.size();
    }

    // ==================== File Operations ====================

    /**
     * Create a skill file in the skills directory.
     */
    public void createSkillFile(String skillName, String content) throws IOException {
        Path skillDir = skillLoader.getSkillsDirectory().resolve(skillName);
        Files.createDirectories(skillDir);
        Path skillFile = skillDir.resolve("SKILL.md");
        Files.writeString(skillFile, content);
        logger.info("Created skill file: {}", skillFile);
    }

    /**
     * Get the skills directory path.
     */
    public Path getSkillsDirectory() {
        return skillLoader.getSkillsDirectory();
    }

    // ==================== Private Methods ====================

    /**
     * Sync skills from filesystem to database.
     */
    private void syncSkillsToDatabase(Map<String, ParsedSkill> fileSkills) {
        for (ParsedSkill parsed : fileSkills.values()) {
            boolean exists = skillStore.existsByName(parsed.getName());
            if (!exists) {
                // Create new skill in database
                AgentSkillEntity entity = new AgentSkillEntity();
                entity.setId(UUID.randomUUID().toString());
                entity.setName(parsed.getName());
                entity.setDescription(parsed.getDescription());
                entity.setCategory(parsed.getCategory());
                entity.setVersion(parsed.getVersion());
                entity.setAuthor(parsed.getAuthor());
                entity.setCommand(parsed.getCommand());
                entity.setContent(parsed.getContent());
                entity.setInstallSource("file:" + parsed.getSourcePath());
                entity.setEnabled(true);
                skillStore.saveSkill(entity);
                logger.info("Synced new skill to database: {}", parsed.getName());
            } else {
                // 更新已存在的 skill（保持 content 同步）
                skillStore.getSkillByName(parsed.getName()).ifPresent(entity -> {
                    if (!Objects.equals(entity.getContent(), parsed.getContent()) ||
                        !Objects.equals(entity.getDescription(), parsed.getDescription())) {
                        entity.setContent(parsed.getContent());
                        entity.setDescription(parsed.getDescription());
                        entity.setCommand(parsed.getCommand());
                        skillStore.saveSkill(entity);
                        logger.info("Updated skill in database: {}", parsed.getName());
                    }
                });
            }
        }
    }
}
