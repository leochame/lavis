# Lavis 上下文工程（Context Engineering）开发计划

> 版本: 1.0
> 创建日期: 2026-02-01
> 状态: 规划中

## 1. 背景与目标

### 1.1 现状问题

| 问题 | 现有代码 | 影响 |
|------|----------|------|
| 图片盲删 | `ImageCleanupService` 定时删除 | 模型"失明"，任务中断 |
| 缺乏时序感知 | `ImageContentCleanableChatMemory` 按计数清理 | 无法识别关键帧 |
| Token 膨胀 | 历史图片全量保留 | 长任务 OOM 或超限 |
| 无 Turn 概念 | `SessionMessageEntity` 缺少 turnId | 无法实现精细化压缩 |

### 1.2 目标

- **Token 效率**: 历史视觉开销降低 95%+
- **逻辑健壮性**: 彻底解决"图片被误删导致任务中断"
- **可追溯性**: 支持历史数据回溯查阅

---

## 2. 架构设计

### 2.1 上下文三段式管理

```
┌─────────────────────────────────────────────────────────────┐
│                     Active Zone (活跃区)                      │
│  当前 Turn: 全量保留所有工具调用、结果、高清图片                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ Turn 结束触发
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Compact Zone (压缩区)                      │
│  历史 Turn: 首尾图片保留，中间图片替换为占位符                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ 冷数据卸载
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Offloaded Zone (持久区)                     │
│  文件系统: 原始二进制数据，数据库仅保留索引元数据                  │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Turn 生命周期

```
用户请求到达
     │
     ▼
┌──────────────┐
│ 生成 turnId  │  ← AgentService.processUserRequest() 入口
│ (UUID)       │
└──────────────┘
     │
     ▼
┌──────────────┐
│  工具调用    │  ← 所有消息携带当前 turnId
│  循环执行    │
└──────────────┘
     │
     ▼
┌──────────────┐
│  最终回复    │
└──────────────┘
     │
     ▼
┌──────────────┐
│ Turn 结束    │  ← 触发 onTurnEnd 事件
│ 压缩上一轮   │
└──────────────┘
```

### 2.3 视觉压缩策略

对于进入压缩区的历史 Turn:

| 图片类型 | 处理方式 |
|----------|----------|
| 首张图片 (Anchor) | 保留完整 Base64，作为环境基准 |
| 中间图片 (Process) | 替换为 `[Visual_Placeholder: {imageId}]` |
| 末张图片 (Result) | 保留完整 Base64，作为执行结果证明 |
| 异常帧 (Error) | 保留完整 Base64，用于调试 |

---

## 3. 开发阶段

### Phase 1: Turn 基础设施 (Foundation)

**目标**: 建立 Turn 概念，为后续压缩提供基础

#### 1.1 数据库迁移

**文件**: `src/main/resources/db/migration/V5__add_turn_id.sql`

```sql
ALTER TABLE session_messages ADD COLUMN turn_id VARCHAR(36);
ALTER TABLE session_messages ADD COLUMN image_id VARCHAR(36);
ALTER TABLE session_messages ADD COLUMN is_compressed BOOLEAN DEFAULT FALSE;

CREATE INDEX idx_session_messages_turn_id ON session_messages(turn_id);
CREATE INDEX idx_session_messages_image_id ON session_messages(image_id);
```

#### 1.2 实体修改

**文件**: `src/main/java/com/lavis/entity/SessionMessageEntity.java`

新增字段:
- `turnId: String` - Turn 标识
- `imageId: String` - 图片唯一标识（用于占位符引用）
- `isCompressed: Boolean` - 是否已压缩

#### 1.3 Turn 上下文管理

**新建文件**: `src/main/java/com/lavis/memory/TurnContext.java`

```java
public class TurnContext {
    private final String turnId;
    private final String sessionId;
    private final LocalDateTime startTime;
    private final List<String> imageIds;  // 本轮所有图片ID

    // ThreadLocal 存储当前 Turn
    private static final ThreadLocal<TurnContext> CURRENT = new ThreadLocal<>();

    public static TurnContext begin(String sessionId);
    public static TurnContext current();
    public static void end();
}
```

#### 1.4 AgentService 集成

**修改文件**: `src/main/java/com/lavis/cognitive/AgentService.java`

```java
public String processUserRequest(String userInput) {
    // 开始新 Turn
    TurnContext turn = TurnContext.begin(currentSessionKey);
    try {
        // ... 现有逻辑 ...
    } finally {
        // Turn 结束，触发压缩
        memoryManager.onTurnEnd(turn);
        TurnContext.end();
    }
}
```

#### 1.5 任务清单

- [ ] 创建数据库迁移脚本 V5
- [ ] 修改 `SessionMessageEntity` 添加新字段
- [ ] 创建 `TurnContext` 类
- [ ] 修改 `AgentService.processUserRequest()` 集成 Turn 生命周期
- [ ] 修改 `MemoryManager` 添加 `onTurnEnd()` 方法
- [ ] 修改 `SessionStore.saveMessage()` 支持 turnId
- [ ] 单元测试: Turn 生命周期正确性

---

### Phase 2: 视觉压缩引擎 (Visual Compaction)

**目标**: 实现基于 Turn 的视觉内容压缩

#### 2.1 压缩服务

**新建文件**: `src/main/java/com/lavis/memory/VisualCompactor.java`

```java
@Service
public class VisualCompactor {

    /**
     * 压缩指定 Turn 的视觉内容
     * 保留首张、末张、异常帧，中间替换为占位符
     */
    public CompactionResult compactTurn(String turnId);

    /**
     * 判断图片是否为异常帧（包含错误信息）
     */
    private boolean isErrorFrame(SessionMessageEntity message);

    /**
     * 生成占位符
     */
    private String createPlaceholder(String imageId);
}
```

#### 2.2 重构 ImageContentCleanableChatMemory

**修改文件**: `src/main/java/com/lavis/cognitive/memory/ImageContentCleanableChatMemory.java`

改造为 `TemporalContextMemory`:
- 按 `turnId` 而非消息计数进行过滤
- 识别"首尾锚点"并保留其内容
- 支持从压缩区恢复完整图片（按需加载）

#### 2.3 废弃定时清理

**修改文件**: `src/main/java/com/lavis/memory/ImageCleanupService.java`

- 移除 `@Scheduled` 定时任务
- 保留清理方法，改为由 `MemoryManager.onTurnEnd()` 调用
- 物理文件移入冷存储目录而非删除

#### 2.4 冷存储管理

**新建文件**: `src/main/java/com/lavis/memory/ColdStorage.java`

```java
@Service
public class ColdStorage {
    private final Path coldStoragePath;  // ~/.lavis/cold-storage/

    /**
     * 将图片移入冷存储
     */
    public void archive(String imageId, byte[] data);

    /**
     * 从冷存储恢复图片
     */
    public Optional<byte[]> retrieve(String imageId);

    /**
     * 清理过期冷存储（默认30天）
     */
    public void cleanup(int retentionDays);
}
```

#### 2.5 任务清单

- [ ] 创建 `VisualCompactor` 服务
- [ ] 重构 `ImageContentCleanableChatMemory` → `TemporalContextMemory`
- [ ] 修改 `ImageCleanupService` 移除定时逻辑
- [ ] 创建 `ColdStorage` 服务
- [ ] 实现"首尾保留 + 异常帧保留"算法
- [ ] 集成测试: 长任务场景下的压缩效果

---

### Phase 3: 感知去重 (Perceptual Deduplication)

**目标**: 减少冗余截图生成

#### 3.1 感知哈希集成

**修改文件**: `src/main/java/com/lavis/perception/ScreenCapturer.java`

```java
public class ScreenCapturer {
    private String lastImageHash;
    private String lastImageId;

    /**
     * 计算感知哈希 (pHash)
     */
    private String computePerceptualHash(BufferedImage image);

    /**
     * 判断是否需要生成新图片
     * @param threshold 汉明距离阈值，默认 10 (约 5% 变化)
     */
    private boolean shouldCaptureNew(String currentHash, int threshold);

    /**
     * 捕获屏幕，支持去重
     * @return ImageCapture 包含 imageId 和 base64（可能为 null 表示复用）
     */
    public ImageCapture captureWithDedup();
}
```

#### 3.2 配置项

**修改文件**: `src/main/resources/application.yml`

```yaml
lavis:
  perception:
    dedup:
      enabled: true
      threshold: 10  # 汉明距离阈值
      algorithm: phash  # phash | dhash | ahash
```

#### 3.3 任务清单

- [ ] 引入感知哈希库依赖 (如 JImageHash)
- [ ] 实现 `computePerceptualHash()` 方法
- [ ] 实现 `shouldCaptureNew()` 判断逻辑
- [ ] 添加配置项支持
- [ ] 性能测试: 哈希计算开销

---

### Phase 4: 网络搜索子代理 (Search Agent) [可选]

**目标**: 实现深度优先的网络搜索能力

#### 4.1 Tavily 集成

**新建文件**: `src/main/java/com/lavis/service/TavilySearchService.java`

```java
@Service
public class TavilySearchService {

    /**
     * 执行搜索，自动追加时间戳
     */
    public SearchResult search(String query);

    /**
     * 深度抓取指定 URL
     */
    public String fetchContent(String url);
}
```

#### 4.2 搜索子代理

**新建文件**: `src/main/java/com/lavis/cognitive/agent/SearchAgent.java`

```java
public class SearchAgent {
    private static final int MAX_ITERATIONS = 5;
    private static final double CONFIDENCE_THRESHOLD = 0.8;

    /**
     * 执行搜索任务
     * 迭代直到达到置信度阈值或最大轮次
     */
    public SearchReport execute(String query);
}
```

#### 4.3 任务清单

- [ ] 添加 Tavily SDK 依赖
- [ ] 创建 `TavilySearchService`
- [ ] 创建 `SearchAgent` 子代理
- [ ] 实现迭代搜索逻辑（置信度驱动）
- [ ] 实现搜索结果持久化
- [ ] 前端: 搜索结果回溯展示

---

## 4. 技术细节

### 4.1 并发安全

Turn 压缩操作需要保证原子性:

```java
public class MemoryManager {
    private final ReentrantLock compressionLock = new ReentrantLock();

    public void onTurnEnd(TurnContext turn) {
        if (compressionLock.tryLock()) {
            try {
                visualCompactor.compactTurn(turn.getTurnId());
            } finally {
                compressionLock.unlock();
            }
        } else {
            // 压缩任务排队，异步执行
            compressionQueue.offer(turn.getTurnId());
        }
    }
}
```

### 4.2 占位符格式

```
[Visual_Placeholder: {imageId}]
[Visual_Placeholder: img_a1b2c3d4]
```

恢复时通过 `ColdStorage.retrieve(imageId)` 获取原始数据。

### 4.3 Token 估算

| 内容类型 | 估算 Token |
|----------|------------|
| 768px 宽度截图 | ~1,500 tokens |
| 占位符文本 | ~10 tokens |
| 压缩比 | **99.3%** |

---

## 5. 测试计划

### 5.1 单元测试

| 测试项 | 覆盖范围 |
|--------|----------|
| TurnContext 生命周期 | begin/current/end 正确性 |
| VisualCompactor | 首尾保留算法 |
| ColdStorage | 存取一致性 |
| PerceptualHash | 相似图片判定 |

### 5.2 集成测试

| 场景 | 验证点 |
|------|--------|
| 50 轮连续操作 | Token 总量、内存占用 |
| 压缩后回溯 | 图片可恢复 |
| 并发请求 | 压缩锁正确性 |

### 5.3 性能基准

| 指标 | 目标 |
|------|------|
| 单次压缩耗时 | < 100ms |
| 感知哈希计算 | < 50ms |
| 冷存储读取 | < 200ms |

---

## 6. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| 压缩丢失关键信息 | 保留异常帧，支持手动标记 |
| 感知哈希误判 | 可配置阈值，默认保守 |
| 冷存储磁盘占用 | 定期清理，可配置保留天数 |
| 迁移兼容性 | 旧数据 turnId 设为 null，不参与压缩 |

---

## 7. 里程碑

| 阶段 | 交付物 | 状态 |
|------|--------|------|
| Phase 1 | Turn 基础设施 | 🔲 待开始 |
| Phase 2 | 视觉压缩引擎 | 🔲 待开始 |
| Phase 3 | 感知去重 | 🔲 待开始 |
| Phase 4 | 网络搜索子代理 | 🔲 可选 |

---

## 附录

### A. 相关文件清单

| 文件 | 改动类型 |
|------|----------|
| `SessionMessageEntity.java` | 修改 |
| `AgentService.java` | 修改 |
| `MemoryManager.java` | 修改 |
| `ImageCleanupService.java` | 修改 |
| `ImageContentCleanableChatMemory.java` | 重构 |
| `ScreenCapturer.java` | 修改 |
| `TurnContext.java` | 新建 |
| `VisualCompactor.java` | 新建 |
| `ColdStorage.java` | 新建 |
| `V5__add_turn_id.sql` | 新建 |

### B. 依赖项

```xml
<!-- 感知哈希 -->
<dependency>
    <groupId>dev.brachtendorf</groupId>
    <artifactId>JImageHash</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Tavily SDK (Phase 4) -->
<dependency>
    <groupId>com.tavily</groupId>
    <artifactId>tavily-java</artifactId>
    <version>1.0.0</version>
</dependency>
```
