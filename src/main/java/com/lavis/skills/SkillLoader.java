package com.lavis.skills;

import com.lavis.skills.event.SkillsUpdatedEvent;
import com.lavis.skills.model.ParsedSkill;
import com.lavis.skills.model.SkillToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skill 加载器 - 重构版本
 *
 * 核心改进：
 * 1. 热重载事件传播 - 文件变更时发布 Spring Event
 * 2. 维护实时的 ToolSpecification 列表
 * 3. 支持监听器模式，通知 AgentService 更新工具
 */
@Component
public class SkillLoader {

    private static final Logger logger = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    private final Yaml yaml = new Yaml();
    private final Map<String, ParsedSkill> loadedSkills = new ConcurrentHashMap<>();

    /** 缓存的 ToolSpecification 列表（供 LLM 使用） */
    private final List<ToolSpecification> cachedToolSpecifications = new CopyOnWriteArrayList<>();

    /** Spring 事件发布器 */
    private final ApplicationEventPublisher eventPublisher;

    /** 变更监听器列表 */
    private final List<SkillChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    @Value("${skills.directory:${user.home}/.lavis/skills}")
    private String skillsDirectory;

    @Value("${skills.hot-reload.enabled:true}")
    private boolean hotReloadEnabled;

    @Value("${skills.hot-reload.interval.ms:5000}")
    private long hotReloadInterval;

    private WatchService watchService;
    private Thread watchThread;

    public SkillLoader(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public Path getSkillsDirectory() {
        return Paths.get(skillsDirectory);
    }

    // ==================== 核心加载方法 ====================

    /**
     * Load all skills from the skills directory.
     * 加载完成后会发布 SkillsUpdatedEvent 事件。
     */
    public Map<String, ParsedSkill> loadAllSkills() {
        loadedSkills.clear();
        cachedToolSpecifications.clear();
        Path skillsPath = getSkillsDirectory();

        if (!Files.exists(skillsPath)) {
            logger.info("Skills directory does not exist: {}", skillsPath);
            try {
                Files.createDirectories(skillsPath);
                logger.info("Created skills directory: {}", skillsPath);
            } catch (IOException e) {
                logger.error("Failed to create skills directory: {}", e.getMessage());
            }
            return loadedSkills;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsPath)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Path skillFile = entry.resolve(SKILL_FILE_NAME);
                    if (Files.exists(skillFile)) {
                        try {
                            ParsedSkill skill = loadSkill(skillFile);
                            if (skill != null) {
                                loadedSkills.put(skill.getName(), skill);
                                // 同时缓存 ToolSpecification
                                cachedToolSpecifications.add(skill.toToolSpecification());
                                logger.info("Loaded skill: {} from {}", skill.getName(), skillFile);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to load skill from {}: {}", skillFile, e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to scan skills directory: {}", e.getMessage());
        }

        logger.info("Loaded {} skills from {}", loadedSkills.size(), skillsPath);

        // 发布初始加载事件
        publishSkillsUpdatedEvent(SkillsUpdatedEvent.UpdateType.INITIAL_LOAD, null);

        return loadedSkills;
    }

    /**
     * Load a single skill from a SKILL.md file.
     */
    public ParsedSkill loadSkill(Path skillFile) throws IOException {
        String content = Files.readString(skillFile);
        return parseSkillFile(content, skillFile);
    }

    /**
     * Parse a SKILL.md file content.
     */
    public ParsedSkill parseSkillFile(String content, Path sourcePath) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.matches()) {
            logger.warn("Invalid skill file format (no frontmatter): {}", sourcePath);
            return null;
        }

        String frontmatter = matcher.group(1);
        String body = matcher.group(2);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = yaml.load(frontmatter);

            ParsedSkill.ParsedSkillBuilder builder = ParsedSkill.builder()
                    .name(getString(metadata, "name"))
                    .description(getString(metadata, "description"))
                    .category(getString(metadata, "category"))
                    .version(getString(metadata, "version"))
                    .author(getString(metadata, "author"))
                    .command(getString(metadata, "command"))
                    .content(body.trim())
                    .sourcePath(sourcePath);

            // Parse parameters if defined
            List<ParsedSkill.SkillParameter> params = parseParameters(metadata);
            builder.parameters(params);

            return builder.build();
        } catch (Exception e) {
            logger.error("Failed to parse skill frontmatter: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 工具规格获取 ====================

    /**
     * 获取所有 Skill 的 ToolSpecification 列表。
     * 这是 AgentService 用于动态挂载工具的入口。
     */
    public List<ToolSpecification> getToolSpecifications() {
        return Collections.unmodifiableList(cachedToolSpecifications);
    }

    /**
     * 获取所有 Skill 的 SkillToolDefinition 列表（用于序列化）
     */
    public List<SkillToolDefinition> getSkillToolDefinitions() {
        return loadedSkills.values().stream()
                .map(ParsedSkill::toSkillToolDefinition)
                .collect(Collectors.toList());
    }

    // ==================== 监听器模式 ====================

    /**
     * 添加变更监听器
     */
    public void addChangeListener(SkillChangeListener listener) {
        changeListeners.add(listener);
        logger.info("Added skill change listener: {}", listener.getClass().getSimpleName());
    }

    /**
     * 移除变更监听器
     */
    public void removeChangeListener(SkillChangeListener listener) {
        changeListeners.remove(listener);
    }

    /**
     * Skill 变更监听器接口
     */
    @FunctionalInterface
    public interface SkillChangeListener {
        void onSkillsChanged(List<ToolSpecification> newToolSpecs);
    }

    // ==================== 热重载 ====================

    /**
     * Start watching for file changes (hot reload).
     */
    public void startWatching() {
        if (!hotReloadEnabled) {
            logger.info("Hot reload is disabled");
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path skillsPath = getSkillsDirectory();

            if (!Files.exists(skillsPath)) {
                logger.warn("Skills directory does not exist, cannot start watching: {}", skillsPath);
                return;
            }

            // Register the skills directory and all subdirectories
            skillsPath.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsPath)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        entry.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                    }
                }
            }

            watchThread = new Thread(this::watchLoop, "skill-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
            logger.info("Started watching skills directory for changes: {}", skillsPath);
        } catch (IOException e) {
            logger.error("Failed to start file watcher: {}", e.getMessage());
        }
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                boolean shouldReload = false;
                SkillsUpdatedEvent.UpdateType updateType = SkillsUpdatedEvent.UpdateType.SKILL_MODIFIED;
                String changedSkillName = null;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    String fileName = changed.toString();

                    if (fileName.equals(SKILL_FILE_NAME)) {
                        shouldReload = true;
                        // 尝试获取变更的 skill 名称
                        Watchable watchable = key.watchable();
                        if (watchable instanceof Path parentPath) {
                            changedSkillName = parentPath.getFileName().toString();
                        }
                    } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        shouldReload = true;
                        updateType = SkillsUpdatedEvent.UpdateType.SKILL_ADDED;
                        changedSkillName = fileName;
                        // 注册新目录的监听
                        registerNewDirectory(changed);
                    } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        shouldReload = true;
                        updateType = SkillsUpdatedEvent.UpdateType.SKILL_REMOVED;
                        changedSkillName = fileName;
                    }
                }

                if (shouldReload) {
                    logger.info("Detected skill file changes ({}), reloading...", updateType);
                    reloadAndNotify(updateType, changedSkillName);
                }

                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 重新加载并通知所有监听器
     */
    private void reloadAndNotify(SkillsUpdatedEvent.UpdateType updateType, String changedSkillName) {
        // 重新加载所有 skills
        loadedSkills.clear();
        cachedToolSpecifications.clear();

        Path skillsPath = getSkillsDirectory();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsPath)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Path skillFile = entry.resolve(SKILL_FILE_NAME);
                    if (Files.exists(skillFile)) {
                        try {
                            ParsedSkill skill = loadSkill(skillFile);
                            if (skill != null) {
                                loadedSkills.put(skill.getName(), skill);
                                cachedToolSpecifications.add(skill.toToolSpecification());
                            }
                        } catch (Exception e) {
                            logger.error("Failed to reload skill from {}: {}", skillFile, e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to scan skills directory during reload: {}", e.getMessage());
        }

        logger.info("Reloaded {} skills", loadedSkills.size());

        // 发布事件
        publishSkillsUpdatedEvent(updateType, changedSkillName);

        // 通知所有监听器
        notifyListeners();
    }

    /**
     * 发布 Skills 更新事件
     */
    private void publishSkillsUpdatedEvent(SkillsUpdatedEvent.UpdateType updateType, String changedSkillName) {
        List<SkillToolDefinition> toolDefs = getSkillToolDefinitions();
        SkillsUpdatedEvent event = new SkillsUpdatedEvent(this, toolDefs, updateType, changedSkillName);
        eventPublisher.publishEvent(event);
        logger.info("Published SkillsUpdatedEvent: type={}, changed={}, total={}",
                updateType, changedSkillName, toolDefs.size());
    }

    /**
     * 通知所有监听器
     */
    private void notifyListeners() {
        List<ToolSpecification> specs = new ArrayList<>(cachedToolSpecifications);
        for (SkillChangeListener listener : changeListeners) {
            try {
                listener.onSkillsChanged(specs);
            } catch (Exception e) {
                logger.error("Error notifying skill change listener: {}", e.getMessage());
            }
        }
    }

    /**
     * 注册新目录的监听
     */
    private void registerNewDirectory(Path newDir) {
        try {
            Path fullPath = getSkillsDirectory().resolve(newDir);
            if (Files.isDirectory(fullPath)) {
                fullPath.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                logger.info("Registered new skill directory for watching: {}", fullPath);
            }
        } catch (IOException e) {
            logger.error("Failed to register new directory for watching: {}", e.getMessage());
        }
    }

    /**
     * Stop watching for file changes.
     */
    public void stopWatching() {
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.error("Error closing watch service: {}", e.getMessage());
            }
            watchService = null;
        }
    }

    // ==================== Getter Methods ====================

    /**
     * Get all currently loaded skills.
     */
    public Map<String, ParsedSkill> getLoadedSkills() {
        return Collections.unmodifiableMap(loadedSkills);
    }

    /**
     * Get a loaded skill by name.
     */
    public Optional<ParsedSkill> getSkill(String name) {
        return Optional.ofNullable(loadedSkills.get(name));
    }

    /**
     * 根据工具名称获取 Skill（支持 snake_case 格式）
     */
    public Optional<ParsedSkill> getSkillByToolName(String toolName) {
        return loadedSkills.values().stream()
                .filter(skill -> skill.toToolName().equals(toolName))
                .findFirst();
    }

    // ==================== Private Helper Methods ====================

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private List<ParsedSkill.SkillParameter> parseParameters(Map<String, Object> metadata) {
        List<ParsedSkill.SkillParameter> params = new ArrayList<>();
        Object paramsObj = metadata.get("parameters");
        if (paramsObj instanceof List) {
            List<Map<String, Object>> paramsList = (List<Map<String, Object>>) paramsObj;
            for (Map<String, Object> paramMap : paramsList) {
                ParsedSkill.SkillParameter.SkillParameterBuilder builder = ParsedSkill.SkillParameter.builder()
                        .name(getString(paramMap, "name"))
                        .description(getString(paramMap, "description"))
                        .defaultValue(getString(paramMap, "default"))
                        .required(Boolean.TRUE.equals(paramMap.get("required")));

                // 解析类型
                String type = getString(paramMap, "type");
                if (type != null) {
                    builder.type(type);
                }

                // 解析枚举值
                Object enumObj = paramMap.get("enum");
                if (enumObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> enumValues = ((List<?>) enumObj).stream()
                            .map(Object::toString)
                            .collect(Collectors.toList());
                    builder.enumValues(enumValues);
                }

                params.add(builder.build());
            }
        }
        return params;
    }
}
