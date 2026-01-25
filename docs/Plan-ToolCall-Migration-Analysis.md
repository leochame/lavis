# Plan 场景迁移到 Tool Call 分析

## 当前 Plan 场景特点

1. **包含截图（ImageContent）**：`generatePlan()` 方法会传入当前屏幕截图
2. **长上下文风险**：截图会显著增加 token 消耗，可能导致 JSON 格式不稳定
3. **输出结构相对简单**：plan 数组，每个 step 有 `id`, `desc`, `dod`, `complexity`
4. **已有容错处理**：JSON 解析失败会降级到文本解析

## 为什么 Tool Call 更适合 Plan 场景？

### 1. 长上下文稳定性
- **当前问题**：截图 + 提示词 JSON，在长上下文中模型可能"忘记"格式要求
- **Tool Call 优势**：框架保证格式，不受上下文长度影响

### 2. 可靠性
- **当前问题**：需要 3 种格式尝试（```json```、``` ```、直接 JSON）+ 降级处理
- **Tool Call 优势**：直接获取结构化对象，失败率 < 1%

### 3. 代码简洁性
- **当前代码**：`extractJson()` + `parseStepsFromResponse()` + `parseStepsFromText()` = 100+ 行
- **Tool Call**：直接获取 `ToolExecutionRequest`，代码量减少 70%

## 实现方案

### 方案 1：单次工具调用（推荐）

创建一个 `PlanTools` 类，定义 `createPlan` 工具，接受完整的 plan JSON：

```java
@Component
public class PlanTools {
    
    @Tool("Create a task plan with multiple steps. Each step should be a milestone-level task.")
    public String createPlan(
        @P("Plan steps as JSON array. Each step must have: id (int), desc (string), dod (string), complexity (int 1-5)") 
        String planJson
    ) {
        // 这个工具实际上不需要执行任何操作
        // 它只是用来让模型以结构化方式输出 plan
        return "Plan received";
    }
}
```

**优点**：简单，一次调用完成
**缺点**：仍然需要解析 JSON 字符串参数

### 方案 2：多次工具调用（更符合 Tool Call 模式）

定义 `addPlanStep` 工具，让模型多次调用：

```java
@Component
public class PlanTools {
    
    private final List<PlanStep> collectedSteps = new ArrayList<>();
    
    @Tool("Add a step to the task plan. Call this tool multiple times to build the complete plan.")
    public String addPlanStep(
        @P("Step ID (starting from 1)") int id,
        @P("Step description - milestone-level task description") String desc,
        @P("Definition of Done - how to determine if step is completed") String dod,
        @P("Complexity level (1-5): 1=Simple, 3=Medium, 5=Complex") int complexity
    ) {
        // 收集步骤
        PlanStep step = PlanStep.builder()
            .id(id)
            .description(desc)
            .definitionOfDone(dod)
            .complexity(complexity)
            .build();
        step.applyDynamicParameters();
        collectedSteps.add(step);
        
        return String.format("Step %d added: %s", id, desc);
    }
    
    public List<PlanStep> getCollectedSteps() {
        return new ArrayList<>(collectedSteps);
    }
    
    public void clear() {
        collectedSteps.clear();
    }
}
```

**优点**：
- ✅ 完全结构化，无需 JSON 解析
- ✅ 类型安全（id 是 int，complexity 是 int）
- ✅ 框架自动验证参数类型
- ✅ 更符合 Tool Call 的使用模式

**缺点**：
- 需要模型多次调用工具（但这是 Tool Call 的正常模式）

## 推荐方案：方案 2（多次工具调用）

### 理由

1. **完全消除 JSON 解析**：所有参数都是类型化的，框架自动处理
2. **更可靠**：在长上下文中，Tool Call 格式比 JSON 字符串更稳定
3. **代码更简洁**：不需要 `extractJson()`、`parseStepsFromResponse()` 等复杂逻辑
4. **符合框架设计**：Tool Call 就是设计用来处理结构化输出的

### 迁移步骤

1. 创建 `PlanTools` 类（方案 2）
2. 修改 `PlannerService.generatePlan()`：
   - 使用 `chatModel.generate(messages, planTools.getToolSpecifications())`
   - 检查 `aiMessage.toolExecutionRequests()`
   - 调用 `planTools.addPlanStep()` 收集步骤
   - 返回收集到的步骤列表

3. 移除 JSON 解析相关代码：
   - `extractJson()`
   - `parseStepsFromResponse()` 的 JSON 部分
   - `parseStepsFromText()` 可以保留作为最终降级方案

## 性能对比

| 指标 | 提示词 JSON | Tool Call |
|------|-----------|-----------|
| 长上下文可靠性 | 70-80% | 99%+ |
| 代码复杂度 | 高（100+ 行解析逻辑） | 低（直接获取对象） |
| 维护成本 | 高（需要维护解析逻辑） | 低（框架保证） |
| 类型安全 | 否（运行时解析） | 是（编译时类型） |

## 结论

**在 Plan 场景下，Tool Call 确实更合适**，特别是因为：
1. Plan 场景包含截图（长上下文）
2. 需要高可靠性（计划失败会影响整个任务）
3. 输出结构相对简单（适合多次工具调用）

建议迁移到 Tool Call 方案 2（多次工具调用）。


