package com.lavis.feature.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lavis.infra.llm.LlmFactory;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.output.JsonSchemas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskInterpretService {

    private static final Logger logger = LoggerFactory.getLogger(TaskInterpretService.class);
    private static final Duration SHORT_TERM_MEMORY_TTL = Duration.ofMinutes(20);
    private static final int MAX_MEMORY_INPUTS = 6;

    private final LlmFactory llmFactory;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, MemoryState> shortTermMemory = new ConcurrentHashMap<>();

    public TaskInterpretService(LlmFactory llmFactory) {
        this.llmFactory = llmFactory;
        this.objectMapper = new ObjectMapper();
    }

    public TaskInterpretResult interpret(TaskInterpretRequest request) {
        String text = request != null && request.getText() != null ? request.getText().trim() : "";
        String memoryKey = normalizeMemoryKey(request != null ? request.getMemoryKey() : null);
        boolean clearMemory = request != null && Boolean.TRUE.equals(request.getClearMemory());
        TaskInterpretResult.TaskDraft explicitDraft = request != null ? request.getDraft() : null;

        cleanupExpiredMemory();
        if (clearMemory && memoryKey != null) {
            shortTermMemory.remove(memoryKey);
        }

        TaskInterpretResult.TaskDraft memoryDraft = readMemoryDraft(memoryKey);
        TaskInterpretResult.TaskDraft base = merge(memoryDraft, explicitDraft);
        List<String> recentInputs = readRecentInputs(memoryKey);

        TaskInterpretResult.TaskDraft modelDraft = callModelForDraft(text, base, recentInputs);
        TaskInterpretResult.TaskDraft merged = merge(base, modelDraft);
        updateMemory(memoryKey, text, merged);

        TaskInterpretResult result = new TaskInterpretResult();
        result.setDraft(merged);

        String missingField = findMissingField(merged);
        if (missingField != null) {
            result.setReady(false);
            result.setMissingField(missingField);
            result.setMessage(missingPrompt(missingField));
            return result;
        }

        TaskRequest taskRequest = toTaskRequest(merged);
        result.setReady(true);
        result.setMissingField(null);
        result.setMessage("任务信息已齐全，准备创建。");
        result.setTask(taskRequest);
        return result;
    }

    private TaskInterpretResult.TaskDraft callModelForDraft(String userText,
                                                            TaskInterpretResult.TaskDraft baseDraft,
                                                            List<String> recentInputs) {
        ChatLanguageModel model = llmFactory.getModel();
        String currentDate = LocalDate.now(ZoneId.systemDefault()).toString();
        String timezone = ZoneId.systemDefault().toString();
        String userPayload = toJson(Map.of(
                "current_date", currentDate,
                "timezone", timezone,
                "existing_draft", baseDraft == null ? new TaskInterpretResult.TaskDraft() : baseDraft,
                "recent_inputs", recentInputs,
                "latest_input", userText
        ));

        JsonSchema jsonSchema = JsonSchemas.jsonSchemaFrom(StructuredInterpretOutput.class)
                .orElseThrow(() -> new IllegalArgumentException("Task interpret failed: cannot build JSON schema"));
        ResponseFormat responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(jsonSchema)
                .build();
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(UserMessage.from(userPayload))
                .responseFormat(responseFormat)
                .build();

        String raw;
        try {
            ChatResponse response = model.chat(chatRequest);
            raw = response.aiMessage() != null ? response.aiMessage().text() : null;
        } catch (Exception e) {
            logger.error("Task interpret LLM call failed: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Task interpret failed: LLM call error: " + e.getMessage(), e);
        }

        JsonNode root = parseModelJson(raw);
        JsonNode draftNode = root.path("draft");
        TaskInterpretResult.TaskDraft draft = new TaskInterpretResult.TaskDraft();
        draft.setName(readTextOrNull(draftNode, "name"));
        draft.setScheduleMode(normalizeScheduleMode(readTextOrNull(draftNode, "scheduleMode")));
        draft.setCronExpression(readTextOrNull(draftNode, "cronExpression"));
        draft.setIntervalSeconds(readIntOrNull(draftNode, "intervalSeconds"));
        draft.setRequestContent(readTextOrNull(draftNode, "requestContent"));
        draft.setRequestUseOrchestrator(readBoolOrNull(draftNode, "requestUseOrchestrator"));
        draft.setEnabled(readBoolOrNull(draftNode, "enabled"));
        return draft;
    }

    private JsonNode parseModelJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception firstError) {
            String candidate = extractFirstJsonObject(raw);
            if (candidate == null) {
                throw new IllegalArgumentException("Task interpret failed: model did not return valid JSON");
            }
            try {
                return objectMapper.readTree(candidate);
            } catch (Exception secondError) {
                throw new IllegalArgumentException("Task interpret failed: malformed JSON from model", secondError);
            }
        }
    }

    private String extractFirstJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private TaskInterpretResult.TaskDraft merge(TaskInterpretResult.TaskDraft base, TaskInterpretResult.TaskDraft update) {
        TaskInterpretResult.TaskDraft safeBase = base != null ? base : new TaskInterpretResult.TaskDraft();
        TaskInterpretResult.TaskDraft safeUpdate = update != null ? update : new TaskInterpretResult.TaskDraft();
        TaskInterpretResult.TaskDraft merged = new TaskInterpretResult.TaskDraft();
        merged.setName(first(nonBlank(safeUpdate.getName()), nonBlank(safeBase.getName())));
        merged.setScheduleMode(first(nonBlank(safeUpdate.getScheduleMode()), nonBlank(safeBase.getScheduleMode())));
        merged.setCronExpression(first(nonBlank(safeUpdate.getCronExpression()), nonBlank(safeBase.getCronExpression())));
        merged.setIntervalSeconds(first(safeUpdate.getIntervalSeconds(), safeBase.getIntervalSeconds()));
        merged.setRequestContent(first(nonBlank(safeUpdate.getRequestContent()), nonBlank(safeBase.getRequestContent())));
        merged.setRequestUseOrchestrator(first(safeUpdate.getRequestUseOrchestrator(), safeBase.getRequestUseOrchestrator(), true));
        merged.setEnabled(first(safeUpdate.getEnabled(), safeBase.getEnabled(), true));

        if (TaskRules.SCHEDULE_MODE_LOOP.equals(merged.getScheduleMode())) {
            merged.setCronExpression(null);
        } else if (TaskRules.SCHEDULE_MODE_CRON.equals(merged.getScheduleMode())) {
            merged.setIntervalSeconds(null);
        }

        if (!hasText(merged.getName()) && hasText(merged.getRequestContent())) {
            String content = merged.getRequestContent().trim();
            merged.setName("Task: " + content.substring(0, Math.min(24, content.length())));
        }
        return merged;
    }

    private String findMissingField(TaskInterpretResult.TaskDraft draft) {
        if (!hasText(draft.getScheduleMode())) {
            return "schedule";
        }
        if (TaskRules.SCHEDULE_MODE_CRON.equals(draft.getScheduleMode()) && !hasText(draft.getCronExpression())) {
            return "schedule";
        }
        if (TaskRules.SCHEDULE_MODE_LOOP.equals(draft.getScheduleMode())
                && (draft.getIntervalSeconds() == null || draft.getIntervalSeconds() <= 0)) {
            return "schedule";
        }
        if (!hasText(draft.getRequestContent())) {
            return "requestContent";
        }
        return null;
    }

    private String missingPrompt(String missingField) {
        if ("schedule".equals(missingField)) {
            return "还缺少执行时间。请补充例如“每天 9:30”或“每 30 分钟”。";
        }
        return "还缺少任务内容。请告诉我这个任务要执行什么，例如“提醒我写日报”。";
    }

    private TaskRequest toTaskRequest(TaskInterpretResult.TaskDraft draft) {
        TaskRequest taskRequest = new TaskRequest();
        taskRequest.setName(hasText(draft.getName()) ? draft.getName().trim() : "Untitled Task");
        taskRequest.setScheduleMode(draft.getScheduleMode());
        taskRequest.setCronExpression(draft.getCronExpression());
        taskRequest.setIntervalSeconds(draft.getIntervalSeconds());
        taskRequest.setExecutionMode(TaskRules.EXECUTION_MODE_REQUEST);
        taskRequest.setRequestContent(draft.getRequestContent() != null ? draft.getRequestContent().trim() : null);
        taskRequest.setRequestUseOrchestrator(Boolean.TRUE.equals(draft.getRequestUseOrchestrator()));
        taskRequest.setEnabled(Boolean.TRUE.equals(draft.getEnabled()));
        return taskRequest;
    }

    private String readTextOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Integer readIntOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isInt() ? value.intValue() : null;
    }

    private Boolean readBoolOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isBoolean() ? value.booleanValue() : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String nonBlank(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String normalizeScheduleMode(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (TaskRules.SCHEDULE_MODE_CRON.equals(normalized) || TaskRules.SCHEDULE_MODE_LOOP.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> T first(T left, T right) {
        return left != null ? left : right;
    }

    private <T> T first(T left, T right, T defaultValue) {
        if (left != null) {
            return left;
        }
        return right != null ? right : defaultValue;
    }

    private String normalizeMemoryKey(String memoryKey) {
        if (!hasText(memoryKey)) {
            return null;
        }
        return memoryKey.trim();
    }

    private TaskInterpretResult.TaskDraft readMemoryDraft(String memoryKey) {
        if (memoryKey == null) {
            return null;
        }
        MemoryState state = shortTermMemory.get(memoryKey);
        if (state == null || state.draft == null) {
            return null;
        }
        return cloneDraft(state.draft);
    }

    private List<String> readRecentInputs(String memoryKey) {
        if (memoryKey == null) {
            return List.of();
        }
        MemoryState state = shortTermMemory.get(memoryKey);
        if (state == null || state.recentInputs.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(state.recentInputs);
    }

    private void updateMemory(String memoryKey, String input, TaskInterpretResult.TaskDraft draft) {
        if (memoryKey == null) {
            return;
        }
        shortTermMemory.compute(memoryKey, (key, state) -> {
            MemoryState next = state != null ? state : new MemoryState();
            next.updatedAt = Instant.now();
            next.draft = cloneDraft(draft);
            if (hasText(input)) {
                next.recentInputs.addLast(input.trim());
                while (next.recentInputs.size() > MAX_MEMORY_INPUTS) {
                    next.recentInputs.removeFirst();
                }
            }
            return next;
        });
    }

    private void cleanupExpiredMemory() {
        Instant cutoff = Instant.now().minus(SHORT_TERM_MEMORY_TTL);
        shortTermMemory.entrySet().removeIf(entry -> entry.getValue().updatedAt.isBefore(cutoff));
    }

    private TaskInterpretResult.TaskDraft cloneDraft(TaskInterpretResult.TaskDraft source) {
        if (source == null) {
            return null;
        }
        TaskInterpretResult.TaskDraft cloned = new TaskInterpretResult.TaskDraft();
        cloned.setName(source.getName());
        cloned.setScheduleMode(source.getScheduleMode());
        cloned.setCronExpression(source.getCronExpression());
        cloned.setIntervalSeconds(source.getIntervalSeconds());
        cloned.setRequestContent(source.getRequestContent());
        cloned.setRequestUseOrchestrator(source.getRequestUseOrchestrator());
        cloned.setEnabled(source.getEnabled());
        return cloned;
    }

    public static class StructuredInterpretOutput {
        public StructuredDraft draft;
        public String message;
    }

    public static class StructuredDraft {
        public String name;
        public String scheduleMode;
        public String cronExpression;
        public Integer intervalSeconds;
        public String requestContent;
        public Boolean requestUseOrchestrator;
        public Boolean enabled;
    }

    private static class MemoryState {
        private TaskInterpretResult.TaskDraft draft;
        private final Deque<String> recentInputs = new ArrayDeque<>();
        private Instant updatedAt = Instant.now();
    }
}
