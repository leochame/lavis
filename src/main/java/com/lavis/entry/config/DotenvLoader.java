package com.lavis.entry.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight .env loader.
 *
 * Loads key/value pairs from project-root .env into JVM system properties
 * before Spring starts, so backend configuration can be sourced from .env.
 */
@Slf4j
public final class DotenvLoader {

    private static final Path DOTENV_PATH = Path.of(".env");

    /**
     * Convenience aliases for common env keys used by Lavis.
     * Users can either provide direct Spring keys in .env (app.llm.*),
     * or provide these shorthand keys.
     */
    private static final Map<String, String> ALIAS_TO_PROPERTY = Map.ofEntries(
            Map.entry("LAVIS_CHAT_API_KEY", "app.llm.models.fast-model.api-key"),
            Map.entry("LAVIS_CHAT_BASE_URL", "app.llm.models.fast-model.base-url"),
            Map.entry("LAVIS_CHAT_MODEL_NAME", "app.llm.models.fast-model.model-name"),
            Map.entry("LAVIS_STT_API_KEY", "app.llm.models.whisper.api-key"),
            Map.entry("LAVIS_STT_BASE_URL", "app.llm.models.whisper.base-url"),
            Map.entry("LAVIS_STT_MODEL_NAME", "app.llm.models.whisper.model-name"),
            Map.entry("LAVIS_TTS_API_KEY", "app.llm.models.tts.api-key"),
            Map.entry("LAVIS_TTS_MODEL_NAME", "app.llm.models.tts.model-name"),
            Map.entry("LAVIS_TTS_VOICE", "app.llm.models.tts.voice"),
            Map.entry("LAVIS_TTS_FORMAT", "app.llm.models.tts.format")
    );

    private DotenvLoader() {
    }

    public static void loadFromProjectRoot() {
        if (!Files.isRegularFile(DOTENV_PATH)) {
            return;
        }

        try {
            Map<String, String> parsed = parseDotenv(DOTENV_PATH);
            int loaded = 0;

            // 1) Load raw keys (supports direct Spring keys like app.llm.models.*)
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                if (setPropertyIfMissing(entry.getKey(), entry.getValue())) {
                    loaded++;
                }
            }

            // 2) Load alias keys (LAVIS_CHAT_API_KEY -> app.llm.models.fast-model.api-key, etc.)
            for (Map.Entry<String, String> alias : ALIAS_TO_PROPERTY.entrySet()) {
                String sourceValue = resolveValue(parsed, alias.getKey());
                if (hasText(sourceValue) && setPropertyIfMissing(alias.getValue(), sourceValue)) {
                    loaded++;
                }
            }

            // 3) Backward-compat: GEMINI_API_KEY can serve as a shared key.
            String geminiApiKey = resolveValue(parsed, "GEMINI_API_KEY");
            if (hasText(geminiApiKey)) {
                if (setPropertyIfMissing("app.llm.models.fast-model.api-key", geminiApiKey)) {
                    loaded++;
                }
                if (setPropertyIfMissing("app.llm.models.whisper.api-key", geminiApiKey)) {
                    loaded++;
                }
                if (setPropertyIfMissing("app.llm.models.tts.api-key", geminiApiKey)) {
                    loaded++;
                }
            }

            log.info("✅ Loaded {} config entries from {}", loaded, DOTENV_PATH.toAbsolutePath());
        } catch (IOException e) {
            log.warn("⚠️ Failed to load .env file from {}", DOTENV_PATH.toAbsolutePath(), e);
        }
    }

    private static Map<String, String> parseDotenv(Path path) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();

        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }

            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            String key = line.substring(0, separator).trim();
            if (key.isEmpty()) {
                continue;
            }

            String value = normalizeValue(line.substring(separator + 1).trim());
            entries.put(key, value);
        }

        return entries;
    }

    private static String normalizeValue(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }

        int commentStart = value.indexOf(" #");
        if (commentStart >= 0) {
            return value.substring(0, commentStart).trim();
        }

        return value;
    }

    private static String resolveValue(Map<String, String> parsed, String key) {
        String systemValue = System.getProperty(key);
        if (hasText(systemValue)) {
            return systemValue;
        }

        String envValue = System.getenv(key);
        if (hasText(envValue)) {
            return envValue;
        }

        return parsed.get(key);
    }

    private static boolean setPropertyIfMissing(String key, String value) {
        if (!hasText(key)) {
            return false;
        }

        if (System.getProperty(key) != null) {
            return false;
        }

        String envValue = System.getenv(key);
        if (hasText(envValue)) {
            return false;
        }

        System.setProperty(key, value != null ? value : "");
        return true;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
