Project J-Agent: macOS 系统级自主智能体开发文档 (Java版)

> **2026-01-13 更新**: 已完成 Plan-Execute 双层架构重构！  
> **2026-01-XX 更新**: 已完成"双层大脑"架构升级，实现里程碑级规划与 OODA 闭环执行！

## 📊 开发进度

### 阶段一：感知与执行基座升级 ✅
- [x] **M3-1**: 拟人化鼠标驱动 - 贝塞尔曲线 + 随机延迟 + 改进拖拽
- [x] **M3-2**: 智能坐标转换 - 越界保护 + 安全区限制

### 阶段二：微观执行器与上下文隔离 ✅
- [x] **M2-1**: AtomicTask / PlanStep 数据结构
- [x] **M2-2**: MicroExecutorService 微观执行器
- [x] **M2-3**: 独立上下文管理 (Micro-Context)

### 阶段三：战略规划层 ✅
- [x] **M1-1**: PlannerService 规划器
- [x] **M1-2**: TaskOrchestrator 状态机控制器

### 阶段四：双层大脑架构升级 ✅ (2026-01-XX)
- [x] **M4-1**: Planner 升级为里程碑级规划（禁止微操）
- [x] **M4-2**: PlanStep 模型增强（DoD、复杂度、PostMortem）
- [x] **M4-3**: Executor 实现 OODA 闭环（观察-判断-决策-行动）
- [x] **M4-4**: 锚点定位策略（基于视觉特征，非盲目坐标）
- [x] **M4-5**: 验尸报告机制（PostMortem）与智能恢复决策

---

## 1. 项目概述

J-Agent 是一个驻留在 macOS 菜单栏的 AI 智能体。不同于传统的 Chatbot，它具备"手"和"眼"，通过 macOS 底层辅助功能接口（Accessibility API）理解屏幕内容，并直接控制鼠标键盘执行任务。

2. 核心架构设计

系统采用 感知-决策-执行 (Perception-Brain-Action) 闭环架构。

模块一：感知层 (The Hybrid Eye)

负责将屏幕像素转化为结构化数据。

截图服务 (Snapshooter):

使用 java.awt.Robot.createScreenCapture。

关键处理： 必须检测 DPI缩放比例，确保截图与坐标系统一致。

UI 结构提取器 (AX-Dumper):

Level 1 (快速): 使用 AppleScript 获取当前前台窗口位置、尺寸。

Level 2 (深度): 递归扫描窗口内的 AXButton, AXTextField, AXTextArea, AXLink 等可交互元素。

输出： 生成一份扁平化的 UI 元素列表 (JSON)，包含元素的 (x, y, w, h) 和 description。

模块二：决策层 (The Brain)

集成 LLM (Gemini 2.0 Flash 或 GPT-4o)。

Prompt 策略 (Grounding):

输入： Screenshot (当前屏幕截图) + UI_Elements_JSON (文本化的 UI 树)。

System Prompt: > "你是一个 macOS 操作助手。我将提供当前屏幕截图和一份可交互元素的坐标列表。用户会给出指令。请判断用户意图，并从列表中找到最匹配的元素 ID。如果列表中没有匹配项，请返回 'USE_VISION_GRID'。"

坐标映射 (The Mapper):

如果 LLM 返回 ID，直接查表获得精确中心坐标。

如果 LLM 返回 'USE_VISION_GRID'，则启动传统的 10x10 网格视觉定位（作为兜底）。

模块三：执行层 (The Hand)

动作驱动 (ActionDriver):

封装 java.awt.Robot。

实现平滑鼠标移动 (Bezier 曲线轨迹)，模拟人类操作，避免触发某些软件的反脚本检测。

实现键盘输入（支持 Clipboard 粘贴，比逐字输入更快更准）。

3. 详细技术实现方案

3.1 核心数据结构

// 代表屏幕上的一个可操作元素
public class UIElement {
    public String id;       // 唯一标识符 (生成的索引)
    public String role;     // AXButton, AXTextField 等
    public String name;     // 按钮文字或标签
    public int x, y;        // 屏幕绝对坐标 (Points)
    public int w, h;        // 尺寸
    
    public Point getCenter() {
        return new Point(x + w / 2, y + h / 2);
    }
}

---

## 4. 双层大脑架构升级 (2026-01-XX)

### 4.1 核心初衷

本次架构升级旨在落实"双层大脑"架构，将规划层（Planner）提升为里程碑式导航，将执行层（Executor）进化为自主闭环。

**解决的核心痛点：**
1. **拒绝"微操"导致的死循环**：Planner 不再控制每个鼠标点击的细节，只负责定方向（如"去发布页"），不再纠结具体的像素点
2. **赋予执行层"肌肉记忆"**：Executor 进化为具备基本反应能力的"熟练工"，在小范围内拥有自主的 OODA 闭环，能够自行解决弹窗、加载延迟、按钮位置偏移等琐碎问题
3. **提升复杂任务的鲁棒性**：通过分层，让上层专注业务逻辑（做对的事），下层专注交互细节（把事做对）

### 4.2 阶段二：战略层升级 (Macro-Planner Upgrade)

#### 4.2.1 Planner Prompt 重构

**核心改动：**
- **从"动作级"提升为"里程碑级"规划**
- **禁止输出坐标、像素位置或原子动作**（如"点击 (300, 200)"）
- **只定方向**：Planner 只负责"做什么"，不关心"怎么做"
- **必须定义完成标准**：每个步骤必须包含 Definition of Done（如何判断该步骤已完成）

**新增里程碑类型：**
- `LAUNCH_APP`: 启动并确保应用就绪
- `NAVIGATE_TO`: 导航至特定功能区（如"个人主页"、"设置页"）
- `EXECUTE_WORKFLOW`: 执行完整业务流程（如"填写表单并提交"）
- `VERIFY_STATE`: 验证当前状态（如"确认已登录"）

**Prompt 示例：**
```
用户目标: "打开微信发送消息给张三"

输出:
{
  "plan": [
    {
      "id": 1, 
      "desc": "启动微信应用并等待主界面就绪", 
      "type": "LAUNCH_APP",
      "dod": "看到微信主界面，包含聊天列表和搜索框",
      "complexity": 1
    },
    {
      "id": 2, 
      "desc": "搜索并进入与张三的聊天", 
      "type": "NAVIGATE_TO",
      "dod": "进入与张三的聊天窗口，看到聊天记录和输入框",
      "complexity": 3
    }
  ]
}
```

#### 4.2.2 PlanStep 模型增强

**新增字段：**
- `definitionOfDone`: 完成状态定义（Definition of Done）
- `complexity`: 步骤复杂度（1-5），用于动态计算重试次数和超时
- `postMortem`: 验尸报告（失败时的详细诊断信息）

**动态参数计算：**
- 复杂度 1 → 3次重试，30秒超时
- 复杂度 3 → 8次重试，60秒超时
- 复杂度 5 → 15次重试，120秒超时

**验尸报告结构：**
```java
public static class PostMortem {
    private String lastScreenState;           // 最后一次看到的屏幕状态
    private List<String> attemptedStrategies; // 尝试过的策略列表
    private FailureReason failureReason;      // 失败原因（6种类型）
    private String errorDetail;               // 详细错误信息
    private String suggestedRecovery;         // 建议的恢复策略
}
```

### 4.3 阶段三：战术层进化 (Micro-Executor Evolution)

#### 4.3.1 OODA 闭环实现

Executor 实现完整的 OODA 循环（Observe-Orient-Decide-Act）：

1. **Observe（观察）**: 分析截图，识别 UI 元素
2. **Orient（判断）**: 评估当前状态与目标的差距
3. **Decide（决策）**: 自主决定操作序列（无需上报给 Planner）
4. **Act（行动）**: 执行原子操作并验证结果

**自主处理能力：**
- 弹窗/对话框：自行关闭或确认
- 加载延迟：自行等待并重新截图
- 点击偏移：自行微调坐标重试
- 滚动查找：自行滚动寻找目标元素

#### 4.3.2 锚点定位策略

**核心原则：禁止盲目猜测坐标，必须基于视觉锚点定位**

**定位步骤：**
1. **寻找锚点**: 识别目标按钮/输入框的视觉特征（颜色、文字、图标）
2. **相对定位**: 基于锚点估算目标的精确位置
3. **验证命中**: 执行后观察绿色圆环是否落在目标上
4. **微调修正**: 如果偏离，基于红色十字当前位置 +/- 5-30 像素微调

**示例：**
- ✅ 正确："找到'发送'按钮，它是蓝色背景、位于输入框右侧，红色十字在 (200, 150)，目标在其右下方约 20px → click(220, 170)"
- ❌ 错误："直接猜测 click(800, 600)"

#### 4.3.3 验尸报告机制

当 Executor 失败时，生成详细的 PostMortem 报告：

**失败原因类型：**
- `ELEMENT_NOT_FOUND`: 找不到目标元素
- `CLICK_MISSED`: 点击未命中
- `INFINITE_LOOP`: 陷入死循环
- `APP_NOT_RESPONDING`: 应用无响应
- `UNEXPECTED_DIALOG`: 意外弹窗
- `TIMEOUT`: 超时

**报告内容：**
- 最后一次看到的屏幕状态描述
- 尝试过的策略列表（最后5条）
- 失败的具体原因
- 建议的恢复策略

### 4.4 阶段四：架构整合与观测

#### 4.4.1 TaskOrchestrator 智能恢复决策

基于验尸报告进行智能决策，4种决策类型：

1. **RETRY_STEP**: 重试当前步骤
2. **SKIP_STEP**: 跳过当前步骤
3. **CONTINUE**: 继续执行下一步
4. **ABORT**: 中止任务

**决策逻辑：**
```java
switch (postMortem.getFailureReason()) {
    case ELEMENT_NOT_FOUND -> 
        step.getComplexity() <= 2 ? SKIP_STEP : CONTINUE;
    case INFINITE_LOOP -> 
        totalStepsFailed >= 2 ? ABORT : SKIP_STEP;
    case APP_NOT_RESPONDING -> ABORT;
    case TIMEOUT -> 
        step.getComplexity() >= 4 ? SKIP_STEP : CONTINUE;
    // ...
}
```

#### 4.4.2 状态透传增强

**Executor → Planner 反馈：**
- 成功：返回简要说明
- 失败：返回验尸报告（PostMortem）

**Planner 决策流程：**
1. 接收 Executor 的验尸报告
2. 分析失败原因和尝试过的策略
3. 根据步骤复杂度和全局状态决定：
   - 重试该步骤
   - 跳过该步骤
   - 重新生成整个计划
   - 中止任务

### 4.5 关键变更文件

| 文件 | 变更内容 |
|------|----------|
| `PlanStep.java` | 新增 DoD、复杂度、PostMortem 内部类 |
| `PlannerService.java` | 里程碑级 Prompt、解析新字段、复杂度评估 |
| `MicroExecutorService.java` | OODA 闭环、锚点定位、验尸报告生成 |
| `TaskOrchestrator.java` | 智能恢复决策、验尸报告处理 |

### 4.6 架构对比

**升级前：**
- Planner 输出原子动作（CLICK、TYPE）
- Executor 机械执行，失败即上报
- 缺乏自主修正能力
- 失败时只返回简单错误信息

**升级后：**
- Planner 输出里程碑（LAUNCH_APP、NAVIGATE_TO）
- Executor 自主 OODA 闭环，自行解决琐碎问题
- 基于锚点定位，非盲目坐标
- 失败时返回详细验尸报告，支持智能恢复决策

### 4.7 效果预期

1. **减少死循环**：Planner 不再控制细节，避免因界面微调导致的任务崩溃
2. **提升鲁棒性**：Executor 自主处理弹窗、延迟、偏移等问题
3. **增强可观测性**：验尸报告提供详细的失败诊断信息
4. **智能恢复**：基于验尸报告的决策机制，提高任务成功率
