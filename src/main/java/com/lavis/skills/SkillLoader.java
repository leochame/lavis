package com.lavis.skills;

import com.lavis.skills.model.ParsedSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and parses SKILL.md files from the skills directory.
 * Supports hot reload via file system watching.
 */
@Component
public class SkillLoader {

    private static final Logger logger = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    private final Yaml yaml = new Yaml();
    private final Map<String, ParsedSkill> loadedSkills = new ConcurrentHashMap<>();

    @Value("${skills.directory:${user.home}/.lavis/skills}")
    private String skillsDirectory;

    @Value("${skills.hot-reload.enabled:true}")
    private boolean hotReloadEnabled;

    @Value("${skills.hot-reload.interval.ms:5000}")
    private long hotReloadInterval;

    private WatchService watchService;
    private Thread watchThread;

    public Path getSkillsDirectory() {
        return Paths.get(skillsDirectory);
    }

    /**
     * Load all skills from the skills directory.
     */
    public Map<String, ParsedSkill> loadAllSkills() {
        loadedSkills.clear();
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
                params.add(ParsedSkill.SkillParameter.builder()
                        .name(getString(paramMap, "name"))
                        .description(getString(paramMap, "description"))
                        .defaultValue(getString(paramMap, "default"))
                        .required(Boolean.TRUE.equals(paramMap.get("required")))
                        .build());
            }
        }
        return params;
    }

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

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (changed.toString().equals(SKILL_FILE_NAME) ||
                            event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
                            event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        shouldReload = true;
                    }
                }

                if (shouldReload) {
                    logger.info("Detected skill file changes, reloading...");
                    loadAllSkills();
                }

                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
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
}
