# Kototoro OCR Roadmap Review Pack (Current Branch Status)

> Archived: this March 2026 roadmap review describes a pre-refactor branch state. The OCR and translation architecture has since been substantially reworked; do not use its implementation status or priorities as the current plan.

## 1. 当前已落地

- **Hybrid fallback 已实现**（NCNN 主 + TFLite 低置信 fallback）
  - 相关文件：
    - [HybridReaderOcrEngine.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/HybridReaderOcrEngine.kt)
    - [NcnnReaderOcrEngine.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/NcnnReaderOcrEngine.kt)
    - [AppSettings.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/core/prefs/AppSettings.kt)
    - [TranslationSettingsFragment.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/settings/TranslationSettingsFragment.kt)
    - [pref_translation.xml](../../app/src/main/res/xml/pref_translation.xml)
  - 状态说明：
    - 已可灰度调节 fallback threshold
    - 当前分支已再次通过 `:app:compileDebugKotlin`（2026-03-11 本地核验）
    - 尚未完成真机 benchmark 与识别回归抽样
    - 无需 JNI 改动
    - 不依赖新模型或 cache schema 变更

- **按页并发已基本落地**
  - 相关文件：
    - [ReaderPageTranslationProcessor.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/ReaderPageTranslationProcessor.kt)
    - [PageLoader.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/domain/PageLoader.kt)
  - 状态说明：
    - 已不存在文档中提到的 `processingMutex`
    - 当前实现为 `processingSemaphore(MAX_PARALLEL_TRANSLATION_PAGES = 2)`，并由 `PageLoader` 为每页单独调度翻译任务
    - 这意味着“全局串行 -> 按页并发”的主体改造已经完成
    - 后续更适合继续调优并发度、真实设备吞吐和内存占用，而不是重复做一次并发架构改造

- **单页诊断埋点已接入**
  - 相关文件：
    - [ReaderPageTranslationProcessor.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/ReaderPageTranslationProcessor.kt)
    - [HybridReaderOcrEngine.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/HybridReaderOcrEngine.kt)
    - [ReaderTranslationDebugLogStore.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/ReaderTranslationDebugLogStore.kt)
    - [ReaderConfigSheet.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/ui/config/ReaderConfigSheet.kt)
  - 状态说明：
    - 已记录单页 `ocr.total_ms`、`process.total_ms`、`ocr.cache_hit`、`render_cache.hit`
    - Hybrid 路径已记录 `hybrid.fallback_rate`、`hybrid.tflite_fallbacks`、`hybrid.feature_cache_hits`
    - 当前这些指标主要以“按页调试日志 + UI 汇总”的形式消费
    - 仍缺少跨设备、跨样本的聚合统计与基线报表

## 2. 埋点实施现状

| 项目 | 当前状态 | 说明 |
| --- | --- | --- |
| **Fallback rate** | **已接入单页埋点** | Hybrid 已记录 `hybrid.fallback_rate` 与 `hybrid.tflite_fallbacks` |
| **Single-page OCR latency** | **已接入单页埋点** | 已记录 `ocr.total_ms`、`hybrid.ncnn_ms`、`hybrid.tflite_ms` |
| **Cache hit** | **已接入单页埋点** | 已记录 `ocr.cache_hit`、`render_cache.hit`，Hybrid 另有 `feature_cache_hits` |
| **p50 / p95 latency** | **未实现** | 当前没有跨页/跨设备聚合统计 |
| **识别回归样本** | **未实现** | 当前没有专门的 OCR 回归样本集或标注流程 |

> 结论：埋点“接入”已经不是当前瓶颈，真正缺的是“聚合统计 + 真机样本基线”。

## 3. 下一阶段优先级

| 优先级 | 阶段 / 功能 | 说明 | 文件 / 位置 |
| --- | --- | --- | --- |
| 1 | **真机 benchmark / 聚合统计** | 现有单页埋点已足够支撑第一轮实验，下一步应补 p50 / p95、设备分层和阈值对比基线 | [ReaderPageTranslationProcessor.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/ReaderPageTranslationProcessor.kt) / [HybridReaderOcrEngine.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/HybridReaderOcrEngine.kt) / [ReaderConfigSheet.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/ui/config/ReaderConfigSheet.kt) |
| 2 | **并发度与吞吐调优** | 按页并发已在位，后续重点应转向 `MAX_PARALLEL_TRANSLATION_PAGES`、模型并发、内存占用和缓存命中表现的实测调优 | [ReaderPageTranslationProcessor.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/ReaderPageTranslationProcessor.kt) / [PageLoader.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/domain/PageLoader.kt) |
| 3 | **CV Bubble Detector** | 已接入第一版纯启发式 MVP：基于整页亮区连通域生成 bubble candidate，并优先吸附 OCR fragments；未命中 fragments 仍回退旧分组逻辑。当前已补 `bubble.detector.candidates`、`matched_fragments`、`coverage_rate`、`used_groups` 埋点，下一步应做真机样本调参与误检分析 | [ReaderPageTranslationProcessor.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/ReaderPageTranslationProcessor.kt) / [CvBubbleDetector.kt](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/CvBubbleDetector.kt) |
| 4 | **YOLO fallback** | 仍建议等待 CV detector 数据后再决定是否落地，目前仓库中尚无相关主流程接入 | - |

> 说明：排序按“当前仓库可落地成本 + 预期收益 + 风险”排列。

## 4. 决策建议

1. **先收集真机数据**：通过埋点获取 fallback rate、latency、cache 命中率等指标，建立真实基线。
2. **先补聚合视角**：把现有单页埋点沉淀成 p50 / p95、设备分层和阈值对照结果，否则难以做阶段性判断。
3. **Hybrid fallback 阈值调优**：在 A/B 测试下确认不同 threshold 对 fallback 频率和延迟的影响。
4. **CV / YOLO 阶段**：
   - 等待 CV bubble detector recall 数据出来后再决定是否推进 YOLO fallback。
   - 避免在没有 benchmark 的情况下写死延迟或吞吐改善预期，例如 `500ms -> 120ms`。
5. **灰度可控**：所有 fallback / threshold / 并发调优均可逐步灰度上线，保证回归风险可控。

## 总结

- 当前仓库已实现核心 Hybrid fallback，也已具备基础的按页并发与单页诊断能力。
- 当前最缺的不是“再接一层埋点”或“再做一次并发重构”，而是把现有数据能力转化为真机 benchmark 与聚合基线。
- CV bubble detector 已完成第一版主流程接入，但仍属于启发式 MVP，是否继续做更重的 detector / YOLO fallback，应该基于真机 recall、误检率与延迟数据再决定。
- 现有能力已经足够支持灰度测试，风险总体可控。
