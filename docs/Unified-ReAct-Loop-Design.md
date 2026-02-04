# Unified ReAct Decision Loop Design

> One-layer architecture refactoring proposal: Merge Planner and Executor into a unified decision loop.

**Status**: ✅ Completed (Core Implementation + Testing + JSON Schema)
**Created**: 2026-02-03
**Updated**: 2026-02-04
**Branch**: `feature/context-engineering`

---

## Implementation Status

### ✅ Completed

| Item | File | Description |
|------|------|-------------|
| DecisionBundle | `cognitive/react/DecisionBundle.java` | LLM 输出结构：thought, last_action_result, execute_now, is_goal_complete |
| ExecuteNow | `cognitive/react/ExecuteNow.java` | 本轮动作集合：intent + actions list |
| Action | `cognitive/react/Action.java` | 单个动作定义，支持 click/type/key/scroll/drag/wait |
| ReactTaskContext | `cognitive/react/ReactTaskContext.java` | 简化版任务上下文 |
| LocalExecutor | `cognitive/react/LocalExecutor.java` | 批量执行器，语义边界检测 |
| DecisionBundleSchema | `cognitive/react/DecisionBundleSchema.java` | JSON 解析验证 + ResponseFormat API |
| executeGoal() | `orchestrator/TaskOrchestrator.java` | 统一决策循环主方法 |
| **JSON Schema** | `DecisionBundleSchema.createResponseFormat()` | API 层面强制 JSON 输出 |
| **Unit Tests** | `test/cognitive/react/*.java` | 181 个单元测试，覆盖所有核心类 |
| **Integration Tests** | `test/cognitive/react/UnifiedReActLoopIntegrationTest.java` | 端到端集成测试 |
| **Cleanup** | Removed deprecated files | PlannerService, MicroExecutorService, TaskPlan, PlanStep 已删除 |

### 📊 Test Coverage

| Test Class | Tests | Description |
|------------|-------|-------------|
| `DecisionBundleSchemaTest` | 25 | JSON 解析、验证、边界情况 |
| `ActionTest` | 35 | 静态工厂方法、边界动作检测、描述生成 |
| `ExecuteNowTest` | 30 | 动作集合管理、边界检测、工厂方法 |
| `DecisionBundleTest` | 20 | 序列化/反序列化、Builder 模式 |
| `ReactTaskContextTest` | 27 | 意图管理、动作记录、上下文注入 |
| `LocalExecutorTest` | 34 | 批量执行、边界暂停、错误处理 |
| `UnifiedReActLoopIntegrationTest` | 10 | 端到端流程、错误恢复、中断处理 |
| **Total** | **182** | **All Passing** ✅ |

### ⏳ Future Improvements

| Item | Priority | Description |
|------|----------|-------------|
| **性能对比测试** | Medium | 对比新旧模式的 LLM 调用次数和执行时间 |
| **WebSocket 事件** | Low | 统一模式下的前端进度推送 |
| **TTS 通知** | Low | 统一模式完成时的语音通知（已基本实现） |
| **错误恢复策略** | Medium | 连续失败时的智能恢复（当前仅简单中止） |
| **上下文窗口管理** | Low | 长任务的消息历史裁剪策略 |

### 🔧 Known Issues

1. **坐标系统**：需确保 LLM 输出 Gemini 坐标 (0-1000) 而非像素坐标
2. **动作验证**：部分动作参数验证不够严格（如 drag 的 toCoords）

---

## 1. Background & Motivation

### Current Architecture (Two-Layer)

```
TaskOrchestrator
    → PlannerService.generatePlan()     // LLM call #1: Generate N steps
    → while loop
        → MicroExecutorService.executeStep()  // LLM call #2~N: Execute each step
            → Internal OODA loop (multiple LLM calls per step)
```

### Problems

| Problem | Description |
|---------|-------------|
| **High RTT** | Each action requires LLM round-trip; 10-step task = 10+ LLM calls |
| **Rigid Planning** | Pre-generated plan may not match actual screen state |
| **Context Fragmentation** | Planner and Executor have separate contexts |
| **Redundant Verification** | Local UIStatusChecker (pixel hash) is unreliable for semantic verification |

### Goal

Reduce LLM calls by 50-70% while maintaining execution reliability through:
1. Unified decision loop (merge Planner + Executor)
2. Action Bundle (batch multiple actions per LLM call)
3. LLM-based verification (next-round LLM judges previous action result)

---

## 2. New Architecture (One-Layer)

### Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         TaskOrchestrator                            │
│                        (Unified Decision Loop)                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   while (!completed && !timeout) {                                  │
│       1. Perceive    → ScreenCapturer.capture()                     │
│       2. Decide      → LLM outputs DecisionBundle                   │
│       3. Execute     → LocalExecutor.executeBatch()                 │
│       // Verification happens in next LLM call                      │
│   }                                                                 │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Flow Diagram

```
┌────────────────────────────────────────────────────────────────────┐
│                          Main Loop                                  │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  ┌──────────┐                                                      │
│  │  START   │                                                      │
│  └────┬─────┘                                                      │
│       ▼                                                            │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  1. Perceive: Capture screenshot                              │ │
│  └──────────────────────────────────────────────────────────────┘ │
│       ▼                                                            │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  2. Decide: LLM analyzes screenshot                           │ │
│  │     Input:                                                    │ │
│  │       - GlobalGoal                                            │ │
│  │       - Last round actions (lastActions)                      │ │
│  │       - Current screenshot                                    │ │
│  │     Output:                                                   │ │
│  │       - thought: Analysis + verify if last step succeeded     │ │
│  │       - execute_now: Actions to execute this round            │ │
│  │       - is_goal_complete: Whether goal is achieved            │ │
│  └──────────────────────────────────────────────────────────────┘ │
│       ▼                                                            │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  3. Check: is_goal_complete?                                  │ │
│  │     - true  → END (Success)                                   │ │
│  │     - false → Continue execution                              │ │
│  └──────────────────────────────────────────────────────────────┘ │
│       ▼                                                            │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  4. Execute: Batch execute actions in execute_now             │ │
│  │     - Record lastActions for next LLM round                   │ │
│  └──────────────────────────────────────────────────────────────┘ │
│       │                                                            │
│       └──────────────────▶ Back to step 1                          │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

---

## 3. Core Data Structures

### TaskContext (Context Brain)

```java
public class TaskContext {
    // Goal layer
    private String globalGoal;           // "Login to Taobao and search for phone"
    private String currentIntent;        // "Enter username" (dynamically updated)

    // Memory layer
    private List<IntentRecord> completedIntents;  // Completed intents
    private List<ActionRecord> recentActions;     // Last 5 actions
    private String lastScreenSummary;             // Previous screen summary

    // State layer
    private int totalActions;
    private int failedActions;
    private int consecutiveFailures;
    private Instant startTime;
    private Instant deadline;
}
```

### DecisionBundle (LLM Output)

```json
{
  "thought": "I see the login page. Last step (enter username) succeeded - input field shows 'admin'. Now I need to enter password.",

  "last_action_result": "success",

  "execute_now": {
    "intent": "Enter password and submit",
    "actions": [
      {"type": "key", "value": "tab"},
      {"type": "type", "text": "123456"},
      {"type": "key", "value": "enter"}
    ]
  },

  "is_goal_complete": false
}
```

### Action Types

| Type | Parameters | Example |
|------|------------|---------|
| `click` | `x`, `y` | `{"type": "click", "x": 500, "y": 300}` |
| `doubleClick` | `x`, `y` | `{"type": "doubleClick", "x": 500, "y": 300}` |
| `rightClick` | `x`, `y` | `{"type": "rightClick", "x": 500, "y": 300}` |
| `type` | `text` | `{"type": "type", "text": "hello"}` |
| `key` | `value` | `{"type": "key", "value": "enter"}` |
| `scroll` | `x`, `y`, `direction`, `amount` | `{"type": "scroll", "direction": "down", "amount": 3}` |
| `drag` | `fromX`, `fromY`, `toX`, `toY` | `{"type": "drag", "fromX": 100, "fromY": 100, "toX": 200, "toY": 200}` |

---

## 4. Prompt Design

> **Note**: JSON 输出格式由 API 层的 `ResponseFormat` + JSON Schema 强制保证，Prompt 只需关注任务逻辑。

```text
You are a task execution agent operating in a continuous loop.

## Your Task
Global Goal: {globalGoal}

## Last Round Actions (verify the result by observing the screenshot)
{lastActions}

## Instructions
1. **First, verify**: Look at the screenshot and judge if your last actions succeeded
2. **Then, decide**: What actions to take next (or declare completion)
3. **Execute**: Output 1-5 logically connected actions for this round

## Coordinate System
- Use Gemini normalized coordinates (0-1000), NOT pixel coordinates
- Red cross marker: Current mouse position
- Green circle marker: Last click position

## Critical Rules
- Set is_goal_complete=true ONLY when you visually confirm the goal is achieved
- If last action failed, try a DIFFERENT approach (don't repeat the same action)
- Maximum 5 actions per round
- Actions should be logically connected (e.g., click input → type text)

## Coordinate System
- Screen coordinates: X: 0-1000, Y: 0-1000 (Gemini normalized)
- Red cross marker: Current mouse position
- Green circle marker: Last click position
```

---

## 5. Action Bundle Granularity Control

### The Problem

Action Bundle 粒度是核心设计决策：

| 粒度 | 优点 | 缺点 |
|------|------|------|
| **1 action/round** | 每步都有视觉反馈，失败可立即纠正 | RTT 高，效率低 |
| **5 actions/round** | RTT 低，效率高 | 中间失败会导致后续动作在错误状态下执行 |

### Design Decision: LLM 自主决定粒度

**核心原则**：让 LLM 根据操作的"确定性"自主决定 Bundle 大小，而非硬编码固定数量。

#### 高确定性场景 → 允许多动作

```json
{
  "thought": "输入框已聚焦，光标可见，这是标准登录表单",
  "execute_now": {
    "intent": "输入用户名和密码",
    "actions": [
      {"type": "type", "text": "admin"},
      {"type": "key", "value": "tab"},
      {"type": "type", "text": "123456"},
      {"type": "key", "value": "enter"}
    ]
  }
}
```

**为什么可以批量**：
- 输入框状态明确（已聚焦）
- 动作序列是确定性的（type → tab → type → enter）
- 中间状态可预测

#### 低确定性场景 → 单动作

```json
{
  "thought": "页面有多个按钮，不确定哪个是登录按钮",
  "execute_now": {
    "intent": "点击登录按钮",
    "actions": [
      {"type": "click", "x": 500, "y": 300}
    ]
  }
}
```

**为什么要单步**：
- 目标位置不确定
- 点击后可能触发页面跳转、弹窗等不可预测变化
- 需要视觉反馈确认

### Prompt 中的粒度引导

在 Prompt 中添加粒度决策指导：

```text
## Action Bundle Guidelines

Decide how many actions to include based on certainty:

**Bundle multiple actions (2-5) when:**
- Input field is focused and visible
- Actions are deterministic sequence (type → tab → type)
- No page navigation or popup expected
- Example: Filling a form field

**Use single action when:**
- Clicking buttons (may trigger navigation/popup)
- Scrolling to find elements
- First interaction with a new screen
- Uncertain about element position
- Example: Clicking "Submit" button

**Never bundle across uncertainty boundaries:**
- Don't combine "click button" + "type in result popup"
- Don't combine "scroll" + "click found element"
```

### 语义边界规则

系统层面定义"语义边界"，即使 LLM 输出多个动作，也在边界处暂停：

```java
public class LocalExecutor {

    // 语义边界动作：执行后必须等待视觉反馈
    private static final Set<String> BOUNDARY_ACTIONS = Set.of(
        "click",        // 可能触发导航
        "doubleClick",  // 可能打开应用
        "enter",        // 可能提交表单
        "scroll"        // 改变可见区域
    );

    public ExecuteResult executeBatch(ExecuteNow executeNow) {
        List<Action> actions = executeNow.getActions();
        List<Action> executed = new ArrayList<>();

        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);

            // 执行动作
            String result = toolService.execute(action);
            executed.add(action);

            // 检查是否到达语义边界
            if (BOUNDARY_ACTIONS.contains(action.getType())) {
                // 如果还有后续动作，中断并返回
                if (i < actions.size() - 1) {
                    return ExecuteResult.partial(executed, actions.subList(i + 1, actions.size()));
                }
            }
        }

        return ExecuteResult.success(executed);
    }
}
```

### 执行结果处理

```java
// 在 TaskOrchestrator 中
ExecuteResult result = localExecutor.executeBatch(decision.getExecuteNow());

if (result.isPartial()) {
    // 部分执行：记录已执行的动作，下一轮 LLM 会看到中间状态
    lastActions = result.getExecutedActions();
    // 被截断的动作不会自动重试，LLM 下一轮会重新决策
} else {
    lastActions = result.getExecutedActions();
}
```

### 总结

| 层级 | 控制方式 | 说明 |
|------|----------|------|
| **LLM 层** | Prompt 引导 | LLM 根据确定性自主决定 Bundle 大小 |
| **系统层** | 语义边界 | 在 click/enter/scroll 后强制暂停 |
| **上限** | 硬编码 | 单轮最多 5 个动作（防止失控） |

这样既保留了 LLM 的灵活性，又通过系统层的语义边界提供了安全保障。

---

## 6. Core Implementation

### TaskOrchestrator (Refactored)

```java
@Service
public class TaskOrchestrator {

    private final ScreenCapturer screenCapturer;
    private final ChatLanguageModel chatModel;
    private final LocalExecutor localExecutor;
    private final ObjectMapper objectMapper;

    private static final int MAX_ITERATIONS = 50;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    public OrchestratorResult executeGoal(String userGoal) {
        TaskContext context = new TaskContext(userGoal);
        List<Action> lastActions = Collections.emptyList();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (context.getConsecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
                return OrchestratorResult.failed("Too many consecutive failures");
            }

            // 1. Perceive
            String screenshot = screenCapturer.captureAsBase64();

            // 2. Decide (LLM: verify last step + plan next step)
            DecisionBundle decision = callLLM(context, lastActions, screenshot);

            // 3. Check completion
            if (decision.isGoalComplete()) {
                return OrchestratorResult.success(decision.getThought());
            }

            // 4. Execute
            ExecuteResult result = localExecutor.executeBatch(decision.getExecuteNow());
            lastActions = decision.getExecuteNow().getActions();

            // Update context
            context.recordActions(lastActions, result.isSuccess());
            if (!result.isSuccess()) {
                context.incrementConsecutiveFailures();
            } else {
                context.resetConsecutiveFailures();
            }
        }

        return OrchestratorResult.failed("Max iterations reached");
    }

    private DecisionBundle callLLM(TaskContext context, List<Action> lastActions, String screenshot) {
        String prompt = buildPrompt(context.getGlobalGoal(), lastActions);

        UserMessage userMessage = UserMessage.from(
            TextContent.from(prompt),
            ImageContent.from(screenshot, "image/jpeg")
        );

        List<ChatMessage> messages = List.of(
            SystemMessage.from(SYSTEM_PROMPT),
            userMessage
        );

        Response<AiMessage> response = chatModel.generate(messages);
        return parseDecisionBundle(response.content().text());
    }
}
```

### DecisionBundle JSON Schema (API-Level Enforcement)

使用 LangChain4j 的 `ResponseFormat` + JSON Schema 在 API 层面强制输出格式，比纯 Prompt 约束更可靠：

```java
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.*;

public class DecisionBundleSchema {

    public static ResponseFormat createResponseFormat() {
        JsonSchema schema = JsonSchema.builder()
            .name("DecisionBundle")
            .rootElement(JsonObjectSchema.builder()
                // thought: 分析和验证
                .addProperty("thought", JsonStringSchema.builder()
                    .description("Analysis of current screen and verification of last action result")
                    .build())
                // last_action_result: 上一步结果
                .addProperty("last_action_result", JsonEnumSchema.builder()
                    .enumValues("success", "failed", "partial", "none")
                    .description("Result of last round actions")
                    .build())
                // execute_now: 本轮要执行的动作
                .addProperty("execute_now", JsonObjectSchema.builder()
                    .addProperty("intent", JsonStringSchema.builder()
                        .description("What this round of actions aims to achieve")
                        .build())
                    .addProperty("actions", JsonArraySchema.builder()
                        .items(JsonObjectSchema.builder()
                            .addProperty("type", JsonEnumSchema.builder()
                                .enumValues("click", "doubleClick", "rightClick",
                                           "type", "key", "scroll", "drag")
                                .build())
                            .addProperty("x", JsonIntegerSchema.builder().build())
                            .addProperty("y", JsonIntegerSchema.builder().build())
                            .addProperty("text", JsonStringSchema.builder().build())
                            .addProperty("value", JsonStringSchema.builder().build())
                            .build())
                        .build())
                    .required("intent", "actions")
                    .build())
                // is_goal_complete: 是否完成
                .addProperty("is_goal_complete", JsonBooleanSchema.builder()
                    .description("Whether the global goal is achieved")
                    .build())
                .required("thought", "last_action_result", "execute_now", "is_goal_complete")
                .build())
            .build();

        return ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(schema)
            .build();
    }
}
```

**使用方式**：

```java
// 在 TaskOrchestrator 中
private DecisionBundle callLLM(TaskContext context, List<Action> lastActions, String screenshot) {
    ChatRequest request = ChatRequest.builder()
        .messages(buildMessages(context, lastActions, screenshot))
        .responseFormat(DecisionBundleSchema.createResponseFormat())  // API 层面强制 JSON
        .build();

    ChatResponse response = chatModel.chat(request);
    return objectMapper.readValue(response.aiMessage().text(), DecisionBundle.class);
}
```

**优势**：
- API 层面强制，不依赖 Prompt 约束
- 类型安全，枚举值受限
- 解析失败由 API 层处理，减少应用层错误处理

---

### LocalExecutor

```java
@Service
public class LocalExecutor {

    private final ToolExecutionService toolService;

    public ExecuteResult executeBatch(ExecuteNow executeNow) {
        List<String> results = new ArrayList<>();

        for (Action action : executeNow.getActions()) {
            String result = toolService.execute(action.getType(), action.toJson());
            results.add(result);

            // Stop on critical failure
            if (isCriticalFailure(result)) {
                return ExecuteResult.failed(action, result, results);
            }

            // Brief pause between actions for UI to respond
            sleepBetweenActions(action.getType());
        }

        return ExecuteResult.success(results);
    }

    private void sleepBetweenActions(String actionType) {
        int delay = switch (actionType) {
            case "click", "doubleClick" -> 300;  // Wait for UI response
            case "type" -> 50;                    // Fast typing
            case "key" -> 100;                    // Key press
            case "scroll" -> 200;                 // Scroll animation
            default -> 100;
        };

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

---

## 6. Component Relationship

### Before (Two-Layer)

```
┌─────────────────────────────────────────────────────────────────┐
│                      Components (Before)                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  TaskOrchestrator ──────▶ PlannerService                        │
│        │                       │                                │
│        │                       ▼                                │
│        │                 TaskPlan (N steps)                     │
│        │                       │                                │
│        ▼                       ▼                                │
│  MicroExecutorService ◀─── PlanStep                             │
│        │                                                        │
│        ▼                                                        │
│  ToolExecutionService                                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### After (One-Layer)

```
┌─────────────────────────────────────────────────────────────────┐
│                      Components (After)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  TaskOrchestrator (Unified Loop)                                │
│        │                                                        │
│        ├──────▶ ScreenCapturer                                  │
│        │                                                        │
│        ├──────▶ ChatLanguageModel (LLM)                         │
│        │              │                                         │
│        │              ▼                                         │
│        │        DecisionBundle                                  │
│        │                                                        │
│        └──────▶ LocalExecutor                                   │
│                      │                                          │
│                      ▼                                          │
│                ToolExecutionService                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Migration Plan

| Component | Action | Notes |
|-----------|--------|-------|
| `TaskOrchestrator` | **Refactor** | Remove PlannerService dependency, implement unified loop |
| `PlannerService` | **Deprecate** | Keep for backward compatibility, mark as @Deprecated |
| `MicroExecutorService` | **Deprecate** | Functionality merged into TaskOrchestrator + LocalExecutor |
| `LocalExecutor` | **New** | Simple batch executor, no LLM calls |
| `DecisionBundle` | **New** | LLM output structure |
| `TaskContext` | **Evolve** | Simplified from GlobalContext |
| `ScreenCapturer` | **Keep** | Reuse existing implementation |
| `ToolExecutionService` | **Keep** | Reuse existing implementation |
| `AgentService` | **Keep** | Reuse for LLM calls |

---

## 7. Development Phases

### Phase 1: Core Loop Refactoring ✅ COMPLETED

**Goal**: Implement unified decision loop in TaskOrchestrator

**Deliverables**:
- ✅ `src/main/java/com/lavis/cognitive/react/DecisionBundle.java`
- ✅ `src/main/java/com/lavis/cognitive/react/ExecuteNow.java`
- ✅ `src/main/java/com/lavis/cognitive/react/Action.java`
- ✅ `src/main/java/com/lavis/cognitive/react/ReactTaskContext.java`
- ✅ `src/main/java/com/lavis/cognitive/react/LocalExecutor.java`
- ✅ `src/main/java/com/lavis/cognitive/react/DecisionBundleSchema.java`
- ✅ Refactored `TaskOrchestrator.java`

### Phase 2: Prompt Engineering ✅ COMPLETED

**Goal**: Design and test LLM prompt for reliable decision-making

**Deliverables**:
- ✅ Prompt template in `TaskOrchestrator.generateSystemPrompt()`
- ✅ JSON schema documentation (in this file)
- ✅ Test results (see Phase 3)

### Phase 3: Integration & Testing ✅ COMPLETED

**Goal**: End-to-end testing and performance validation

**Deliverables**:
- ✅ Test cases in `src/test/java/com/lavis/cognitive/react/`
  - `DecisionBundleSchemaTest.java` - 25 tests
  - `ActionTest.java` - 35 tests
  - `ExecuteNowTest.java` - 30 tests
  - `DecisionBundleTest.java` - 20 tests
  - `ReactTaskContextTest.java` - 27 tests
  - `LocalExecutorTest.java` - 34 tests
  - `UnifiedReActLoopIntegrationTest.java` - 10 tests
- ✅ Updated documentation

### Phase 4: JSON Schema Enforcement ✅ COMPLETED

**Goal**: Use LangChain4j ResponseFormat API for API-level JSON enforcement

**Deliverables**:
- ✅ `DecisionBundleSchema.createResponseFormat()` method
- ✅ `TaskOrchestrator` uses `ChatRequest` + `ResponseFormat`
- ✅ Simplified system prompt (JSON format enforced by API)

### Phase 5: Cleanup ✅ COMPLETED

**Goal**: Remove deprecated code

**Removed Files**:
- ✅ `PlannerService.java`
- ✅ `MicroExecutorService.java`
- ✅ `TaskPlan.java`
- ✅ `PlanStep.java`
- ✅ `GlobalContext.java`
- ✅ `PlanTools.java`
- ✅ `ReflectionTools.java`

---

## 8. Expected Benefits

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| LLM calls per task | N (plan) + M×K (execute) | M (decide only) | 50-70% reduction |
| Average RTT | 1 per action | 1 per intent (1-5 actions) | 60% reduction |
| Plan rigidity | Pre-generated, may mismatch | Dynamic per-round | Eliminated |
| Code complexity | 3 layers | 1 layer | Simplified |
| Verification reliability | Pixel hash (unreliable) | LLM visual (reliable) | Improved |

---

## 9. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Action Bundle too large | Cascading failures | Limit to 5 actions max; LLM verifies in next round |
| LLM outputs invalid JSON | Execution blocked | JSON schema validation + retry with error feedback |
| Infinite loop | Resource exhaustion | MAX_ITERATIONS (50) + consecutive failure limit (5) |
| Backward compatibility | Existing integrations break | Keep old APIs, mark as @Deprecated |

---

## 10. Open Questions

1. **Action Bundle granularity**: Should we allow LLM to dynamically adjust bundle size based on confidence?
2. **Partial success handling**: If 3/5 actions succeed, should we report partial success or retry all?
3. **Context window management**: How to handle long-running tasks that exceed context limits?

---

## References

- Current architecture: [ARCHITECTURE.md](ARCHITECTURE.md)
- Context Engineering: [ARCHITECTURE.md#context-engineering](ARCHITECTURE.md#context-engineering)
- Existing TaskOrchestrator: `src/main/java/com/lavis/cognitive/orchestrator/TaskOrchestrator.java`
- Existing MicroExecutorService: `src/main/java/com/lavis/cognitive/executor/MicroExecutorService.java`
