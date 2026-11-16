# Kotatsu-Redo 的热门源与本地常用源

核对时间：2026-11-16。依据本地 `../Kotatsu-Redo` 的 `origin/devel`，提交 `9ef707775`（9.8.7）。

## 自动靠前的火焰源

这不是设备上按使用次数产生的置顶，而是社区服务器提供的地区热度：

- `SourceRank.hotSources` 从全部有评分的源中选择，要求 `samples >= 5`、`popularity >= 0.8`。
- 优先系统语言，然后按热度排序，最多 10 个；最后才与当前展示的源取交集。
- 源列表采用评分排序时，顺序是：手动置顶 → 火焰热门源 → 综合评分。
- 综合评分由 40% 社区稳定性、20% 社区热度、30% 本地稳定性、10% 亲和度组成。
  本地稳定性使用成功率的 Wilson 下界、延迟、空结果与 Cloudflare 比例，不是个人使用次数。
  没有足够本地观测时，使用社区稳定性作为先验。
  当前源列表的调用没有传入亲和度，使用默认值 0；这一项也不能解释个人常用程度。

客户端代码能够确认以上选择与加权规则；这里没有核实社区服务器生成热度分数的完整统计过程。

源码：
[SourceRank.kt](https://github.com/Kotatsu-Redo/Kotatsu-Redo/blob/9ef707775/app/src/main/kotlin/org/koitharu/kotatsu/sourcescore/domain/SourceRank.kt)、
[SourceRanker.kt](https://github.com/Kotatsu-Redo/Kotatsu-Redo/blob/9ef707775/app/src/main/kotlin/org/koitharu/kotatsu/sourcescore/domain/SourceRanker.kt)、
[SourceScoreEntity.kt](https://github.com/Kotatsu-Redo/Kotatsu-Redo/blob/9ef707775/app/src/main/kotlin/org/koitharu/kotatsu/sourcescore/data/SourceScoreEntity.kt)。

## “常用源”是另一套规则

`MangaSourcesRepository.getTopSources(limit)` 调用 `findLastUsed(limit)`，SQL 实际按 `sources.used_at DESC`
排序。`trackUsage` 写入最近使用时间，没有累计次数。因此这个入口应理解为“最近使用”，不能据此声称
“使用率最高”。

源码：
[MangaSourcesRepository.kt](https://github.com/Kotatsu-Redo/Kotatsu-Redo/blob/9ef707775/app/src/main/kotlin/org/koitharu/kotatsu/explore/data/MangaSourcesRepository.kt)、
[MangaSourcesDao.kt](https://github.com/Kotatsu-Redo/Kotatsu-Redo/blob/9ef707775/app/src/main/kotlin/org/koitharu/kotatsu/core/db/dao/MangaSourcesDao.kt)。

## Kototoro 可以复用的部分

- 语言优先、手动置顶优先于自动排序、低样本回退、稳定性计算可以移植为共享纯函数。
- 社区热度需要社区数据，不能用个人书库数据冒充。纯本地产品可以另设“最近常用”分组。
- 当前 `core-db/MangaSourcesDao.findLastUsed` 已有相同的 `used_at` 查询；Android 的
  `ContentSourcesRepository.trackUsage` 在打开源列表时记录时间，并尊重隐身模式。
- iOS 的 `IosLibrary.recordSourceUsage` 也能写入该字段；本次检索没有找到桌面运行时对浏览行为的
  同类写入，桌面不能直接假设这份数据完整。
- 如需真正的个人使用频率，建议按近期实际读过的不同作品数、最近阅读时间计算，并做时间衰减。
  仅打开一次列表不等于持续阅读；直接数所有网络请求又会把分页、自动刷新和失败重试当成喜好。
- 可以从已有历史与阅读统计派生本地常用排序，先保证三端行为采集一致，再接入浏览页；本次没有
  顺带修改浏览页的源排序。
