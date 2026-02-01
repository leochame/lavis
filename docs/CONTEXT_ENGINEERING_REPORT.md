# Context Engineering 开发报告

> 分支: `feature/context-engineering`
> 开发日期: 2026-02-01
> 状态: ✅ 已完成

---

## 1. 项目概述

本次开发实现了 Lavis 的上下文工程（Context Engineering）改造，通过上下文隔离、即时可逆压缩和时序感知视觉演进，确保系统在长路径、高视觉负载任务中保持高性能。

### 1.1 解决的核心问题

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| 模型"失明" | `ImageCleanupService` 定时盲删图片 | 改为事件驱动，Turn 结束时智能压缩 |
| Token 膨胀 | 历史图片全量保留 | 首尾锚点保留，中间替换为占位符 |
| 缺乏时序感知 | 按消息计数清理，无法识别关键帧 | 引入 Turn 概念，按任务周期管理 |
| 冗余截图 | 屏幕无变化时仍生成新图片 | 感知哈希去重，相似图片复用 |

### 1.2 预期效果

- **Token 效率**: 历史视觉开销降低 95%+
- **逻辑健壮性**: 彻底解决"图片被误删导致任务中断"
- **信息深度**: 子代理 5 轮迭代确保网络信息可靠性

---

## 2. 完成的工作

### Phase 1: Turn 基础设施 ✅

建立 Turn（交互轮次）概念，为后续压缩提供基础。

**实现内容:**
- 数据库迁移：新增 `turn_id`, `image_id`, `is_compressed`, `turn_position` 字段
- `TurnContext` 类：管理 Turn 生命周期（begin/current/end）
- `AgentService` 集成：请求入口生成 turnId，结束时触发 onTurnEnd
- `SessionStore` 扩展：saveMessage 支持 Turn 追踪

### Phase 2: 视觉压缩引擎 ✅

实现基于 Turn 的视觉内容压缩。

**实现内容:**
- `ColdStorage` 服务：将压缩的图片归档到文件系统（~/.lavis/cold-storage/）
- `VisualCompactor` 服务：首尾保留算法 + 异常帧保留
- `ImageContentCleanableChatMemory` 重构：改为时序感知版本
- `ImageCleanupService` 改造：移除 @Scheduled，改为事件驱动
- `SessionMessageRepository` 扩展：Turn 相关查询方法

**压缩策略:**
| 图片类型 | 处理方式 |
|----------|----------|
| 首张图片 (Anchor) | 保留完整 Base64 |
| 末张图片 (Result) | 保留完整 Base64 |
| 中间图片 (Process) | 替换为 `[Visual_Placeholder: {imageId}]` |
| 异常帧 (Error) | 保留完整 Base64 |

### Phase 3: 感知去重 ✅

减少冗余截图生成，降低 Token 消耗。

**实现内容:**
- dHash（差异哈希）算法：纯 Java 实现，无外部依赖
- `captureWithDedup()` 方法：支持屏幕变化检测
- `ImageCapture` 记录：包含 imageId、base64、isReused、hash
- 配置项支持：`lavis.perception.dedup.enabled/threshold`

**dHash 算法:**
1. 缩小图片到 9x8
2. 转为灰度
3. 比较相邻像素生成 64 位哈希
4. 计算汉明距离判断相似度

### Phase 4: 网络搜索子代理 ✅

实现深度优先的网络搜索能力。

**实现内容:**
- `WebSearchService`：支持 DuckDuckGo（免费）和 Tavily（需 API Key）
- `SearchAgent`：最多 5 轮迭代，置信度驱动终止
- `internetSearch` 工具：深度搜索，返回 ~200 字摘要
- `quickSearch` 工具：快速单次搜索

**搜索流程:**
1. 分析原始查询，生成搜索策略
2. 执行搜索，评估结果质量
3. 如果信息不足，优化查询并重新搜索
4. 达到置信度阈值（0.8）或最大轮次后，生成最终报告

---

## 3. 文件变更清单

### 3.1 新增文件 (8 个)

| 文件路径 | 说明 |
|----------|------|
| `src/main/resources/db/migration/V2__add_turn_context.sql` | 数据库迁移脚本 |
| `src/main/java/com/lavis/memory/TurnContext.java` | Turn 生命周期管理 |
| `src/main/java/com/lavis/memory/ColdStorage.java` | 冷存储服务 |
| `src/main/java/com/lavis/memory/VisualCompactor.java` | 视觉压缩服务 |
| `src/main/java/com/lavis/service/search/WebSearchService.java` | 网络搜索服务 |
| `src/main/java/com/lavis/cognitive/agent/SearchAgent.java` | 搜索子代理 |
| `docs/CONTEXT_ENGINEERING.md` | 开发计划文档 |
| `docs/CONTEXT_ENGINEERING_REPORT.md` | 本报告 |

### 3.2 修改文件 (8 个)

| 文件路径 | 修改内容 |
|----------|----------|
| `src/main/java/com/lavis/entity/SessionMessageEntity.java` | 新增 turnId, imageId, isCompressed, turnPosition 字段 |
| `src/main/java/com/lavis/repository/SessionMessageRepository.java` | 新增 Turn 相关查询方法 |
| `src/main/java/com/lavis/memory/SessionStore.java` | saveMessage 支持 Turn 追踪 |
| `src/main/java/com/lavis/memory/MemoryManager.java` | 新增 onTurnEnd, saveMessageWithImage 方法 |
| `src/main/java/com/lavis/memory/ImageCleanupService.java` | 移除 @Scheduled，标记 deprecated |
| `src/main/java/com/lavis/cognitive/memory/ImageContentCleanableChatMemory.java` | 重构为时序感知版本 |
| `src/main/java/com/lavis/perception/ScreenCapturer.java` | 新增 captureWithDedup, dHash 算法 |
| `src/main/java/com/lavis/cognitive/AgentTools.java` | 新增 internetSearch, quickSearch 工具 |
| `src/main/java/com/lavis/cognitive/executor/ToolExecutionService.java` | 搜索工具不触发重新截图 |
| `src/main/resources/application.properties` | 新增 dedup, cold-storage, search 配置 |

### 3.3 Git 提交记录

```
47f3156 docs: Mark all phases as completed
c8e14d3 feat: Implement Phase 4 - Web Search Agent
33d2f5c feat: Implement Phase 3 - Perceptual Deduplication
e0d3c83 docs: Update development plan - Phase 1 & 2 completed
86321ac feat: Implement Phase 2 - Visual Compaction Engine
0f4fa13 feat: Implement Phase 1 - Turn Context Infrastructure
```

---

## 4. 配置项说明

```properties
# ====================================
# Context Engineering: Perceptual Deduplication
# ====================================
lavis.perception.dedup.enabled=true      # 启用感知去重
lavis.perception.dedup.threshold=10      # 汉明距离阈值 (0-64)

# ====================================
# Context Engineering: Cold Storage
# ====================================
lavis.storage.cold-path=${user.home}/.lavis/cold-storage  # 冷存储路径
lavis.storage.retention-days=30          # 保留天数

# ====================================
# Context Engineering: Web Search
# ====================================
lavis.search.provider=duckduckgo         # 搜索提供商
lavis.search.tavily.api-key=             # Tavily API Key (可选)
lavis.search.timeout-seconds=30          # 搜索超时
lavis.search.max-results=5               # 最大结果数
```

---

## 5. 未完成 / 待改进项

### 5.1 未完成项

| 项目 | 说明 | 优先级 |
|------|------|--------|
| 单元测试 | 未编写 TurnContext, VisualCompactor, SearchAgent 的单元测试 | 高 |
| 集成测试 | 未验证 50 轮连续操作场景下的 Token 节省效果 | 中 |
| 前端回溯 | 未实现 UI 层的历史图片回溯展示功能 | 低 |
| 搜索结果持久化 | SearchAgent 的 Snippets 未存入持久区 | 低 |

### 5.2 已知问题 / 待改进

| 问题 | 现状 | 改进建议 |
|------|------|----------|
| **captureWithDedup 未集成** | 新增了 `captureWithDedup()` 方法，但 `AgentService` 仍使用 `captureScreenWithCursorAsBase64()` | 需要修改 AgentService 使用新方法，并传递 imageId 到 MemoryManager |
| **压缩时机的并发安全** | `MemoryManager.onTurnEnd` 使用简单的 try-catch，未实现压缩锁 | 建议添加 `ReentrantLock` 或使用异步队列 |
| **DuckDuckGo 解析脆弱** | HTML 解析依赖正则，可能因页面结构变化而失效 | 建议优先使用 Tavily API，或引入 Jsoup 库 |
| **SearchAgent JSON 解析** | 使用简单正则解析 LLM 返回的 JSON，可能解析失败 | 建议使用 Jackson 或 Gson 进行结构化解析 |
| **感知哈希阈值** | 默认阈值 10 可能对某些 UI 场景过于宽松或严格 | 建议根据实际使用情况调整，或提供自适应阈值 |
| **冷存储检索效率** | 当前使用 `Files.walk()` 遍历查找，大量文件时效率低 | 建议维护索引文件或使用数据库索引 |

### 5.3 技术债务

1. **ImageContentCleanableChatMemory 复杂度增加**
   - 新增了 `messageTurnMap` 和 `turnImagePositions` 两个 Map
   - 索引更新逻辑（`updateIndicesAfterRemoval`）可能存在边界问题
   - 建议后续重构为更清晰的数据结构

2. **AgentTools 依赖膨胀**
   - 构造函数参数从 4 个增加到 7 个
   - 建议考虑使用 Builder 模式或拆分为多个 Tool 类

3. **缺少监控指标**
   - 未暴露压缩率、去重命中率等指标
   - 建议集成 Micrometer 进行监控

---

## 6. 后续建议

### 6.1 短期 (1-2 周)

1. **补充单元测试**
   - TurnContext 生命周期测试
   - VisualCompactor 首尾保留算法测试
   - dHash 相似度判定测试

2. **集成 captureWithDedup**
   - 修改 `AgentService.processWithTools()` 使用 `captureWithDedup()`
   - 将 imageId 传递给 `MemoryManager.saveMessageWithImage()`

3. **添加压缩锁**
   - 在 `MemoryManager.onTurnEnd()` 中添加并发控制

### 6.2 中期 (1 个月)

1. **性能基准测试**
   - 验证 50 轮连续操作的 Token 节省效果
   - 测量 dHash 计算和冷存储读写的延迟

2. **搜索功能增强**
   - 引入 Tavily API 作为主要搜索后端
   - 使用 Jackson 进行 JSON 解析

3. **前端集成**
   - 实现历史图片回溯 UI
   - 显示压缩统计信息

### 6.3 长期

1. **自适应压缩策略**
   - 根据任务类型动态调整保留策略
   - 支持用户手动标记重要帧

2. **分布式冷存储**
   - 支持云存储后端（S3, OSS）
   - 实现跨设备同步

---

## 7. 总结

本次 Context Engineering 改造成功实现了 4 个阶段的核心功能：

| 阶段 | 核心价值 |
|------|----------|
| Phase 1 | 建立 Turn 概念，为精细化管理奠定基础 |
| Phase 2 | 实现智能压缩，预计节省 95%+ 历史视觉 Token |
| Phase 3 | 感知去重，减少冗余截图生成 |
| Phase 4 | 网络搜索能力，扩展信息获取渠道 |

主要遗留问题是 **captureWithDedup 未完全集成** 和 **缺少单元测试**，建议在合并到 master 前优先解决。
