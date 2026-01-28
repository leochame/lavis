package com.lavis.skills;

import com.lavis.entity.AgentSkillEntity;
import com.lavis.skills.dto.CreateSkillRequest;
import com.lavis.skills.dto.SkillResponse;
import com.lavis.skills.dto.UpdateSkillRequest;
import com.lavis.skills.model.ParsedSkill;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core service for skill management.
 * Integrates SkillStore, SkillLoader, and SkillExecutor.
 */
@Service
public class SkillService {

    private static final Logger logger = LoggerFactory.getLogger(SkillService.class);

    private final SkillStore skillStore;
    private final SkillLoader skillLoader;
    private final SkillExecutor skillExecutor;

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

        // Start hot reload watcher
        skillLoader.startWatching();
        logger.info("SkillService initialized with {} skills", fileSkills.size());
    }

    @PreDestroy
    public void destroy() {
        skillLoader.stopWatching();
    }

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
            }
        }
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

    // ==================== Execution ====================

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

        // Build ParsedSkill from entity
        ParsedSkill skill = ParsedSkill.builder()
                .name(entity.getName())
                .description(entity.getDescription())
                .command(entity.getCommand())
                .content(entity.getContent())
                .build();

        // Execute
        SkillExecutor.ExecutionResult result = skillExecutor.execute(skill, params);

        // Update usage stats
        skillStore.incrementUseCount(entity.getId());

        return result;
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
}
