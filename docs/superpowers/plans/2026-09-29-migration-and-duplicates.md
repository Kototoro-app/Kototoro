# 批量迁移、失效源检测与收藏去重提醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仿照 Mihon 实现批量换源（迁移）、离线失效源检测与收藏时重复提醒。

**Architecture:** 新建 `migration/` 功能模块。纯逻辑（标题规范化、相似度、章节映射、迁移计划、健康分类、重复匹配、智能匹配）全部是无 Android 依赖的纯函数/类，用 JUnit5 测试；数据库访问集中在新的 `MigrationDao`；`MigrateUseCase` 改为「快照 → 计划 → 事务内应用」。UI 为 Compose：设置面板（BottomSheet，通过 `showComposeModal` 显示）、迁移列表 Activity、按来源迁移 Activity、重复提醒面板。匹配与执行都由 `MigrationListViewModel` 承载。

**Tech Stack:** Kotlin 2.4、Jetpack Compose + Material3、Hilt、Room 2.8、Coil3、JUnit5 + MockK、kotlinx-coroutines-test。

**Spec:** `docs/superpowers/specs/2026-09-29-migration-and-duplicates-design.md`

**中文术语：** 应用内已有 `migrate` = 「换源」，所有新文案沿用「换源」（例如「换源 26 部」），不引入「迁移」一词，保持零学习成本。

**构建/测试命令（Windows Git Bash，需要 Java 17；网络失败时加代理参数 `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`）：**
- 单测：`./gradlew :app:testDebugUnitTest --tests "<FQCN>" --no-daemon`
- 编译：`./gradlew :app:compileDebugKotlin --no-daemon`

---

## 文件结构

新建（`app/src/main/kotlin/org/skepsun/kototoro/migration/`）：

| 文件 | 职责 |
|---|---|
| `domain/MigrationTypes.kt` | `MigrationDataFlag`、`MigrationMode`、`MatchMode` 枚举 |
| `domain/TitleNormalizer.kt` | 标题规范化（NFKC + 小写 + 只保留字母数字） |
| `domain/TitleSimilarity.kt` | 标准化 Levenshtein、深度搜索清洗与查询生成（移植 Mihon） |
| `domain/ChapterIdMapper.kt` | 旧章节 id → 新章节 id 映射 |
| `domain/MigrationPlanner.kt` | 纯函数：快照 + 模式 + flags → `MigrationPlan` |
| `domain/SmartMatchEngine.kt` | 对来源列表执行匹配，返回最佳结果与候选 |
| `domain/SourceHealth.kt` | `SourceHealthStatus`、`SourceHealthClassifier`（纯） |
| `domain/SourceHealthUseCase.kt` | 读库 + 解析来源 → `List<SourceHealth>` |
| `domain/DuplicateMatcher.kt` | 纯：规范化 key 与重复判定 |
| `domain/FindLibraryDuplicatesUseCase.kt` | 读库 → 重复的收藏条目 |
| `domain/MigrationSettings.kt` | SharedPreferences 持久化 |
| `data/MigrationDao.kt` | 迁移/健康/查重所需的 SQL |
| `data/LibraryRow.kt` | 收藏行投影 POJO |
| `ui/config/MigrationConfigViewModel.kt` + `MigrationConfigSheet.kt` | 设置面板与来源选择 |
| `ui/list/MigrationListState.kt` | 列表状态 + 纯 reducer |
| `ui/list/MigrationListViewModel.kt` | 匹配与执行 |
| `ui/list/MigrationListActivity.kt` + `MigrationListScreen.kt` + `MigrationCandidatesSheet.kt` | 迁移列表 UI |
| `ui/sources/MigrationSourcesViewModel.kt` + `MigrationSourcesActivity.kt` + `MigrationSourcesScreen.kt` | 按来源换源页 |
| `ui/health/SourceHealthBanner.kt` + `SourceHealthBannerViewModel.kt` | 收藏页浮动提示卡 |
| `ui/duplicate/DuplicateFavouriteViewModel.kt` + `DuplicateFavouriteSheet.kt` | 收藏时的重复提醒 |

修改：
- `core/model/ContentTypeHeuristics.kt`（公开内容类型族）
- `core/model/ContentSource.kt`（`isUnresolved` 扩展）
- `core/db/MangaDatabase.kt`（注册 `MigrationDao`）
- `alternatives/domain/MigrateUseCase.kt`（重写为计划应用器）
- `list/ui/compose/KototoroSelectionTopBar.kt`、`list/ui/compose/AppContentListRoute.kt`（FIX → MIGRATE）
- `core/nav/AppRouter.kt`（新入口 + 单本收藏查重）
- `main/ui/compose/MainShellScene.kt`（收藏页菜单「换源」）
- `favourites/ui/compose/FavoritesHostScreen.kt`（浮动提示卡）
- `details/ui/compose/DetailsScreen.kt`（失效提示 + 收藏查重）
- `AndroidManifest.xml`、`res/values/strings.xml`、`res/values-zh-rCN/strings.xml`

测试（`app/src/test/kotlin/org/skepsun/kototoro/migration/`）：`TitleNormalizerTest`、`TitleSimilarityTest`、`ChapterIdMapperTest`、`MigrationPlannerTest`、`SmartMatchEngineTest`、`SourceHealthClassifierTest`、`DuplicateMatcherTest`、`MigrationListReducerTest`，以及 `core/model/ContentFamilyTest`。

## 与设计文档的细化（实现时以本节为准）

1. 来源排序：来源选择页用「按勾选顺序排序」（勾选的来源依次编号，取消勾选后重新编号）。项目中没有拖拽排序库，这样零学习成本，也不引入新依赖。
2. 「收藏时检查重复」开关放在两个就地位置：重复提醒面板里的「不再提醒」，以及设置面板「更多选项」里的开关。不新增设置页条目。
3. 「最快命中」命中后即停止，不再在后台搜索其余来源。候选面板里可以手动搜索全部目标来源作为补充。
4. 收藏页的失效源提示是底部浮动卡片（位于底部导航上方），不修改列表的顶部 padding。
5. 详情页的失效源警告使用 Snackbar（带「换源」动作），不修改体量很大的 `DetailsHeader`。

---

### Task 1: 公开内容类型族，新增 `isUnresolved`

**Files:**
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/core/model/ContentTypeHeuristics.kt:62-91`
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/core/model/ContentSource.kt`（在 `fun ContentSource.getContentType()` 之后）
- Test: `app/src/test/kotlin/org/skepsun/kototoro/core/model/ContentFamilyTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class ContentFamilyTest {
    @Test
    fun `manga like types share the manga family`() {
        assertEquals(ContentTypeFamily.MANGA, ContentType.MANHWA.contentFamily())
        assertEquals(ContentTypeFamily.MANGA, ContentType.HENTAI_MANGA.contentFamily())
    }

    @Test
    fun `novel and video families are distinct`() {
        assertEquals(ContentTypeFamily.NOVEL, ContentType.HENTAI_NOVEL.contentFamily())
        assertEquals(ContentTypeFamily.VIDEO, ContentType.HENTAI_VIDEO.contentFamily())
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.core.model.ContentFamilyTest" --no-daemon`
Expected: 编译失败，`contentFamily` / `ContentTypeFamily` 是 private。

- [ ] **Step 3: 实现**

在 `ContentTypeHeuristics.kt` 中，把 `private fun ContentType.contentFamily()` 改为 `fun ContentType.contentFamily()`，把 `private enum class ContentTypeFamily` 改为 `enum class ContentTypeFamily`（函数体和枚举值不变）。

在 `ContentSource.kt` 的 `fun ContentSource.getContentType()` 之后加入：

```kotlin
/**
 * True when the source name could not be resolved to a loaded source: the extension was
 * uninstalled, the rule was deleted, or the stored name is unknown. Local sources are
 * never unresolved.
 */
val ContentSource.isUnresolved: Boolean
    get() {
        val resolved = unwrap()
        return resolved === UnknownContentSource || resolved is AnonymousContentSource
    }
```

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/core/model/ContentTypeHeuristics.kt app/src/main/kotlin/org/skepsun/kototoro/core/model/ContentSource.kt app/src/test/kotlin/org/skepsun/kototoro/core/model/ContentFamilyTest.kt
git commit -m "refactor(core): expose content type family and unresolved source check"
```

---

### Task 2: 迁移枚举与 `TitleNormalizer`

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/MigrationTypes.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/TitleNormalizer.kt`
- Test: `app/src/test/kotlin/org/skepsun/kototoro/migration/TitleNormalizerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.TitleNormalizer

class TitleNormalizerTest {
    @Test
    fun `full width latin becomes lowercase half width without spaces or punctuation`() {
        assertEquals("onepiece", TitleNormalizer.normalize("ＯＮＥ　ＰＩＥＣＥ！"))
    }

    @Test
    fun `cjk letters are kept`() {
        assertEquals("葬送的芙莉莲", TitleNormalizer.normalize(" 葬送的芙莉莲 "))
    }

    @Test
    fun `punctuation and separators are dropped`() {
        assertEquals("sousounofrieren", TitleNormalizer.normalize("Sousou no Frieren: -"))
    }

    @Test
    fun `blank title normalizes to empty`() {
        assertEquals("", TitleNormalizer.normalize("  ・ "))
    }

    @Test
    fun `data flags round trip through bits`() {
        val flags = setOf(MigrationDataFlag.PROGRESS, MigrationDataFlag.NOTES)
        assertEquals(flags + MigrationDataFlag.CATEGORIES, MigrationDataFlag.fromBits(MigrationDataFlag.toBits(flags)))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.TitleNormalizerTest" --no-daemon`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

`MigrationTypes.kt`：

```kotlin
package org.skepsun.kototoro.migration.domain

/** User data carried from the old entry to the new one. [CATEGORIES] is always on. */
enum class MigrationDataFlag(val bit: Int) {
    CATEGORIES(1),
    PROGRESS(1 shl 1),
    TRACKING(1 shl 2),
    NOTES(1 shl 3),
    STATS(1 shl 4),
    ;

    companion object {
        val ALL: Set<MigrationDataFlag> = entries.toSet()

        fun toBits(flags: Set<MigrationDataFlag>): Int = flags.fold(0) { acc, flag -> acc or flag.bit }

        fun fromBits(bits: Int): Set<MigrationDataFlag> =
            entries.filterTo(mutableSetOf()) { bits and it.bit != 0 } + CATEGORIES
    }
}

/** [REPLACE] removes the old entry from favourites and history; [COPY] keeps it. */
enum class MigrationMode { REPLACE, COPY }

/** [FIRST_HIT] stops at the first source with a good enough match; [MOST_CHAPTERS] searches all sources. */
enum class MatchMode { FIRST_HIT, MOST_CHAPTERS }
```

`TitleNormalizer.kt`：

```kotlin
package org.skepsun.kototoro.migration.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical form used to compare titles across sources: NFKC folds full-width forms,
 * lowercasing removes case, and only letters and digits survive, so spacing and
 * punctuation differences between sources do not matter.
 */
object TitleNormalizer {

    fun normalize(title: String): String {
        val folded = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildString(folded.length) {
            for (char in folded) {
                if (char.isLetterOrDigit()) append(char)
            }
        }
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/domain app/src/test/kotlin/org/skepsun/kototoro/migration/TitleNormalizerTest.kt
git commit -m "feat(migration): add migration enums and title normalizer"
```

---

### Task 3: `TitleSimilarity`（移植 Mihon 智能搜索）

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/TitleSimilarity.kt`
- Test: `app/src/test/kotlin/org/skepsun/kototoro/migration/TitleSimilarityTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.TitleSimilarity

class TitleSimilarityTest {
    @Test
    fun `identical titles after normalization score one`() {
        assertEquals(1.0, TitleSimilarity.similarity("One Piece", "ONE-PIECE"), 1e-9)
    }

    @Test
    fun `empty title scores zero`() {
        assertEquals(0.0, TitleSimilarity.similarity("", "One Piece"), 1e-9)
    }

    @Test
    fun `best similarity uses alternative titles`() {
        val score = TitleSimilarity.bestSimilarity(
            listOf("葬送的芙莉莲", "Sousou no Frieren"),
            listOf("Sousou no Frieren"),
        )
        assertEquals(1.0, score, 1e-9)
    }

    @Test
    fun `unrelated titles fall below the eligibility threshold`() {
        assertTrue(TitleSimilarity.similarity("Chainsaw Man", "Blue Lock") < TitleSimilarity.MIN_ELIGIBLE)
    }

    @Test
    fun `deep search cleaning removes bracketed text`() {
        assertEquals("solo leveling", TitleSimilarity.cleanDeepSearchTitle("Solo Leveling (Official) [Webtoon]"))
    }

    @Test
    fun `deep search cleaning falls back to backward parsing for short titles`() {
        assertEquals("tail", TitleSimilarity.cleanDeepSearchTitle("(a long bracketed prefix) tail"))
    }

    @Test
    fun `deep search queries follow mihon order and are distinct`() {
        assertEquals(
            listOf("the eminence in shadow", "eminence shadow", "eminence", "the eminence", "the"),
            TitleSimilarity.deepSearchQueries("the eminence in shadow"),
        )
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.TitleSimilarityTest" --no-daemon`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.parsers.util.levenshteinDistance
import java.util.Locale

/**
 * Title matching helpers ported from Mihon's BaseSmartSearchEngine. Similarity is a
 * normalized Levenshtein score in 0..1 computed on [TitleNormalizer] output.
 */
object TitleSimilarity {

    const val MIN_ELIGIBLE = 0.4

    private val titleRegex = Regex("[^a-zA-Z0-9- ]")
    private val titleUnicodeRegex = Regex("[^\\p{L}0-9- ]")
    private val consecutiveSpacesRegex = Regex(" +")
    private val chapterRefCyrillicRegex = Regex("""((- часть|- глава) \d*)""")

    fun similarity(a: String, b: String): Double {
        val left = TitleNormalizer.normalize(a)
        val right = TitleNormalizer.normalize(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val maxLength = maxOf(left.length, right.length)
        return 1.0 - left.levenshteinDistance(right).toDouble() / maxLength
    }

    fun bestSimilarity(left: Collection<String>, right: Collection<String>): Double {
        var best = 0.0
        for (a in left) {
            for (b in right) {
                val score = similarity(a, b)
                if (score > best) best = score
                if (best == 1.0) return best
            }
        }
        return best
    }

    fun cleanDeepSearchTitle(title: String): String {
        val preTitle = title.lowercase(Locale.getDefault())
        var cleaned = removeTextInBrackets(preTitle, readForward = true)
        if (cleaned.length <= 5) {
            cleaned = removeTextInBrackets(preTitle, readForward = false)
        }
        cleaned = cleaned.replace(chapterRefCyrillicRegex, " ").trim()
        val latinOnly = cleaned.replace(titleRegex, " ")
        cleaned = if (latinOnly.trim().length <= 5) {
            cleaned.replace(titleUnicodeRegex, " ")
        } else {
            latinOnly
        }
        return cleaned.trim().replace(" - ", " ").replace(consecutiveSpacesRegex, " ").trim()
    }

    fun deepSearchQueries(cleanedTitle: String): List<String> {
        val words = cleanedTitle.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val byLength = words.sortedByDescending { it.length }
        return listOf(
            listOf(cleanedTitle),
            byLength.take(2),
            byLength.take(1),
            words.take(2),
            words.take(1),
        ).map { it.joinToString(" ").trim() }.distinct()
    }

    private fun removeTextInBrackets(text: String, readForward: Boolean): String {
        val openingChars = if (readForward) "([<{" else ")]}>"
        val closingChars = if (readForward) ")]}>" else "([<{"
        var depth = 0
        val builder = StringBuilder()
        val chars = if (readForward) text else text.reversed()
        for (char in chars) {
            when (char) {
                in openingChars -> depth++
                in closingChars -> if (depth > 0) depth--
                else -> if (depth == 0) {
                    if (readForward) builder.append(char) else builder.insert(0, char)
                }
            }
        }
        return builder.toString()
    }
}
```

注意：`"eminence shadow"` 中两个最长词按长度排序（`eminence`=8，`shadow`=6），与 Mihon 行为一致。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS（7 个测试）。如果 `deep search cleaning falls back...` 失败，检查反向解析：输入 `(a long bracketed prefix) tail` 正向解析得到 ` tail`（长度 5 ≤ 5），反向解析结果相同，经 trim 后为 `tail`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/domain/TitleSimilarity.kt app/src/test/kotlin/org/skepsun/kototoro/migration/TitleSimilarityTest.kt
git commit -m "feat(migration): port smart search title similarity from Mihon"
```

---

### Task 4: `ChapterIdMapper`

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/ChapterIdMapper.kt`
- Test: `app/src/test/kotlin/org/skepsun/kototoro/migration/ChapterIdMapperTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.migration.domain.ChapterIdMapper
import org.skepsun.kototoro.parsers.model.ContentChapter

internal fun chapter(id: Long, number: Float, volume: Int = 0, branch: String? = null) = ContentChapter(
    id = id,
    title = "Ch $number",
    number = number,
    volume = volume,
    url = "/c/$id",
    scanlator = null,
    uploadDate = 0L,
    branch = branch,
    source = TestContentSource,
)

class ChapterIdMapperTest {
    @Test
    fun `maps by volume and number first`() {
        val old = listOf(chapter(1, 1f), chapter(2, 2f), chapter(3, 3f))
        val new = listOf(chapter(10, 0.5f), chapter(11, 1f), chapter(12, 2f), chapter(13, 3f))
        assertEquals(mapOf(1L to 11L, 2L to 12L, 3L to 13L), ChapterIdMapper.map(old, new))
    }

    @Test
    fun `falls back to index and clamps to the last chapter`() {
        val old = listOf(chapter(1, 0f), chapter(2, 0f), chapter(3, 0f))
        val new = listOf(chapter(10, 0f), chapter(11, 0f))
        assertEquals(mapOf(1L to 10L, 2L to 11L, 3L to 11L), ChapterIdMapper.map(old, new))
    }

    @Test
    fun `uses the same branch when the new content has it`() {
        val old = listOf(chapter(1, 1f, branch = "EN"))
        val new = listOf(chapter(10, 1f, branch = "RU"), chapter(20, 1f, branch = "EN"))
        assertEquals(mapOf(1L to 20L), ChapterIdMapper.map(old, new))
    }

    @Test
    fun `empty new chapters produce empty map`() {
        assertTrue(ChapterIdMapper.map(listOf(chapter(1, 1f)), emptyList()).isEmpty())
    }

    @Test
    fun `index lookup picks from the largest branch`() {
        val new = listOf(chapter(10, 1f, branch = "A"), chapter(20, 1f, branch = "B"), chapter(21, 2f, branch = "B"))
        assertEquals(21L, ChapterIdMapper.idAtIndex(new, 5))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.ChapterIdMapperTest" --no-daemon`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.parsers.model.ContentChapter

/**
 * Maps chapters of the old entry to chapters of the new one. A chapter keeps its branch
 * when the new entry has it, otherwise it lands in the new entry's largest branch; inside
 * the branch the same volume and positive number wins, then the same position (clamped).
 */
object ChapterIdMapper {

    fun map(old: List<ContentChapter>, new: List<ContentChapter>): Map<Long, Long> {
        if (old.isEmpty() || new.isEmpty()) return emptyMap()
        val newByBranch = new.groupBy { it.branch }
        val fallback = largestBranch(newByBranch)
        val oldByBranch = old.groupBy { it.branch }
        val result = HashMap<Long, Long>(old.size)
        for ((branch, oldChapters) in oldByBranch) {
            val target = newByBranch[branch] ?: fallback
            oldChapters.forEachIndexed { index, chapter ->
                val byNumber = if (chapter.number > 0f) {
                    target.firstOrNull { it.volume == chapter.volume && it.number == chapter.number }
                } else {
                    null
                }
                result[chapter.id] = (byNumber ?: target.getOrNull(index) ?: target.last()).id
            }
        }
        return result
    }

    fun idAtIndex(new: List<ContentChapter>, index: Int): Long? {
        if (new.isEmpty()) return null
        val branch = largestBranch(new.groupBy { it.branch })
        return (branch.getOrNull(index) ?: branch.last()).id
    }

    private fun largestBranch(byBranch: Map<String?, List<ContentChapter>>): List<ContentChapter> =
        byBranch.values.maxBy { it.size }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/domain/ChapterIdMapper.kt app/src/test/kotlin/org/skepsun/kototoro/migration/ChapterIdMapperTest.kt
git commit -m "feat(migration): add chapter id mapper"
```

---

### Task 5: `MigrationPlanner`（纯迁移计划）

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/MigrationPlanner.kt`
- Test: `app/src/test/kotlin/org/skepsun/kototoro/migration/MigrationPlannerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationPlanner
import org.skepsun.kototoro.migration.domain.MigrationSnapshot
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.tracker.data.TrackEntity

class MigrationPlannerTest {

    private fun content(id: Long, vararg chapters: Long) = Content(
        id = id, title = "T", altTitles = emptySet(), url = "/$id", publicUrl = "", rating = -1f,
        contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
        chapters = chapters.mapIndexed { i, c -> chapter(c, (i + 1).toFloat()) }, source = TestContentSource,
    )

    private val old = content(1, 101, 102, 103)
    private val new = content(2, 201, 202, 203, 204)

    private val favourite = FavouriteEntity(
        mangaId = 1, categoryId = 7, sortKey = 3, isPinned = true, createdAt = 10, deletedAt = 0, updatedAt = 10,
    )
    private val history = HistoryEntity(
        mangaId = 1, createdAt = 5, updatedAt = 6, chapterId = 102, page = 4, scroll = 0f, percent = 0.5f,
        deletedAt = 0, chaptersCount = 3,
    )
    private val note = MediaNoteEntity(
        id = 55, mangaId = 1, chapterId = 103, chapterIndex = 2, mediaType = 0, createdAt = 1, updatedAt = 1,
    )
    private val session = ReadingRecordEntity(
        id = 9, mangaId = 1, startAt = 1, endAt = 2, startChapterId = 101, startPage = 0, startScroll = 0,
        endChapterId = 102, endPage = 0, endScroll = 0, startPercent = 0f, endPercent = 0f,
    )
    private val link = TrackingSiteLinkEntity(
        service = 1, remoteId = 99, mangaId = 1, sourceName = "OLD", confidence = 1f, isManual = true,
        createdAt = 1, updatedAt = 1,
    )
    private val track = TrackEntity(1, 103, 0, 0, 0, TrackEntity.RESULT_FAILED, "boom")

    private val snapshot = MigrationSnapshot(
        favourites = listOf(favourite), history = history, prefs = null, trackingLinks = listOf(link),
        track = track, notes = listOf(note), sessions = listOf(session), jumpPoints = emptyList(),
    )

    @Test
    fun `replace moves favourites and deletes old ones`() {
        val plan = MigrationPlanner.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        assertEquals(listOf(favourite.copy(mangaId = 2, updatedAt = 100)), plan.favouritesToUpsert)
        assertTrue(plan.deleteOldFavourites)
    }

    @Test
    fun `copy keeps old favourites`() {
        val plan = MigrationPlanner.plan(old, new, snapshot, MigrationMode.COPY, MigrationDataFlag.ALL, now = 100)
        assertFalse(plan.deleteOldFavourites)
        assertFalse(plan.deleteOldHistory)
        assertEquals(2L, plan.favouritesToUpsert.single().mangaId)
    }

    @Test
    fun `history is remapped by chapter number`() {
        val plan = MigrationPlanner.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        val newHistory = checkNotNull(plan.historyToUpsert)
        assertEquals(2L, newHistory.mangaId)
        assertEquals(202L, newHistory.chapterId)
        assertEquals(4, newHistory.chaptersCount)
    }

    @Test
    fun `progress flag off skips history`() {
        val flags = MigrationDataFlag.ALL - MigrationDataFlag.PROGRESS
        val plan = MigrationPlanner.plan(old, new, snapshot, MigrationMode.REPLACE, flags, now = 100)
        assertNull(plan.historyToUpsert)
        assertFalse(plan.deleteOldHistory)
    }

    @Test
    fun `replace updates notes in place with remapped chapter`() {
        val plan = MigrationPlanner.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        assertEquals(listOf(note.copy(mangaId = 2, chapterId = 203, updatedAt = 100)), plan.notesToUpdate)
        assertTrue(plan.notesToInsert.isEmpty())
    }

    @Test
    fun `copy inserts note copies with fresh ids`() {
        val plan = MigrationPlanner.plan(old, new, snapshot, MigrationMode.COPY, MigrationDataFlag.ALL, now = 100)
        assertEquals(0L, plan.notesToInsert.single().id)
        assertTrue(plan.notesToUpdate.isEmpty())
    }

    @Test
    fun `stats move only in replace mode`() {
        val replace = MigrationPlanner.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        val copy = MigrationPlanner.plan(old, new, snapshot, MigrationMode.COPY, MigrationDataFlag.ALL, now = 100)
        assertTrue(replace.moveStats)
        assertEquals(listOf(session.copy(mangaId = 2, startChapterId = 201, endChapterId = 202)), replace.sessionsToUpdate)
        assertFalse(copy.moveStats)
        assertTrue(copy.sessionsToUpdate.isEmpty())
    }

    @Test
    fun `tracking is moved in replace and copied in copy`() {
        val replace = MigrationPlanner.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        val copy = MigrationPlanner.plan(old, new, snapshot, MigrationMode.COPY, MigrationDataFlag.ALL, now = 100)
        assertEquals(listOf(link), replace.trackingLinksToDelete)
        assertEquals(2L, replace.trackingLinksToUpsert.single().mangaId)
        assertTrue(replace.deleteOldTrack)
        assertTrue(copy.trackingLinksToDelete.isEmpty())
        assertFalse(copy.deleteOldTrack)
        assertEquals(2L, copy.trackToUpsert?.mangaId)
        assertEquals(204L, copy.trackToUpsert?.lastChapterId)
    }

    @Test
    fun `tracking flag off leaves tracking untouched`() {
        val flags = MigrationDataFlag.ALL - MigrationDataFlag.TRACKING
        val plan = MigrationPlanner.plan(old, new, snapshot, MigrationMode.REPLACE, flags, now = 100)
        assertTrue(plan.trackingLinksToUpsert.isEmpty())
        assertNull(plan.trackToUpsert)
        assertFalse(plan.deleteOldTrack)
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.MigrationPlannerTest" --no-daemon`
Expected: 编译失败。如果实体构造器参数名与测试不符（例如 `MediaNoteEntity` 缺少 `createdAt`/`updatedAt` 或需要额外参数），打开对应实体，按真实参数补齐测试里的命名参数，**不要改实体**。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.readingrecord.data.ReadingJumpPointEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import java.time.Instant

/** Everything the old entry owns that a migration may carry over. */
data class MigrationSnapshot(
    val favourites: List<FavouriteEntity>,
    val history: HistoryEntity?,
    val prefs: MangaPrefsEntity?,
    val trackingLinks: List<TrackingSiteLinkEntity>,
    val track: TrackEntity?,
    val notes: List<MediaNoteEntity>,
    val sessions: List<ReadingRecordEntity>,
    val jumpPoints: List<ReadingJumpPointEntity>,
)

/** Database writes for one migration, applied inside a single transaction. */
data class MigrationPlan(
    val favouritesToUpsert: List<FavouriteEntity>,
    val deleteOldFavourites: Boolean,
    val historyToUpsert: HistoryEntity?,
    val deleteOldHistory: Boolean,
    val prefsToUpsert: MangaPrefsEntity?,
    val trackingLinksToUpsert: List<TrackingSiteLinkEntity>,
    val trackingLinksToDelete: List<TrackingSiteLinkEntity>,
    val trackToUpsert: TrackEntity?,
    val deleteOldTrack: Boolean,
    val notesToInsert: List<MediaNoteEntity>,
    val notesToUpdate: List<MediaNoteEntity>,
    val moveStats: Boolean,
    val sessionsToUpdate: List<ReadingRecordEntity>,
    val jumpPointsToUpdate: List<ReadingJumpPointEntity>,
)

object MigrationPlanner {

    fun plan(
        old: Content,
        new: Content,
        snapshot: MigrationSnapshot,
        mode: MigrationMode,
        flags: Set<MigrationDataFlag>,
        now: Long,
    ): MigrationPlan {
        val replace = mode == MigrationMode.REPLACE
        val oldChapters = old.chapters.orEmpty()
        val newChapters = new.chapters.orEmpty()
        val chapterMap = ChapterIdMapper.map(oldChapters, newChapters)
        fun remap(chapterId: Long) = chapterMap[chapterId] ?: chapterId

        val progress = MigrationDataFlag.PROGRESS in flags
        val tracking = MigrationDataFlag.TRACKING in flags
        val notes = MigrationDataFlag.NOTES in flags
        val stats = MigrationDataFlag.STATS in flags && replace

        val newHistory = snapshot.history
            ?.takeIf { progress && newChapters.isNotEmpty() }
            ?.let { makeNewHistory(old, new, it) }

        val movedNotes = if (notes) {
            snapshot.notes.map { note ->
                val chapterId = chapterMap[note.chapterId]
                    ?: ChapterIdMapper.idAtIndex(newChapters, note.chapterIndex)
                    ?: note.chapterId
                note.copy(mangaId = new.id, chapterId = chapterId, updatedAt = now)
            }
        } else {
            emptyList()
        }

        val lastNewChapter = newChapters.lastOrNull()
        return MigrationPlan(
            favouritesToUpsert = snapshot.favourites.map { it.copy(mangaId = new.id, updatedAt = now) },
            deleteOldFavourites = replace && snapshot.favourites.isNotEmpty(),
            historyToUpsert = newHistory,
            deleteOldHistory = replace && newHistory != null,
            prefsToUpsert = snapshot.prefs?.copy(mangaId = new.id),
            trackingLinksToUpsert = if (tracking) {
                snapshot.trackingLinks.map { it.copy(mangaId = new.id, sourceName = new.source.name, updatedAt = now) }
            } else {
                emptyList()
            },
            trackingLinksToDelete = if (tracking && replace) snapshot.trackingLinks else emptyList(),
            trackToUpsert = snapshot.track?.takeIf { tracking }?.let {
                TrackEntity(
                    mangaId = new.id,
                    lastChapterId = lastNewChapter?.id ?: 0L,
                    newChapters = 0,
                    lastCheckTime = now,
                    lastChapterDate = lastNewChapter?.uploadDate ?: 0L,
                    lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
                    lastError = null,
                )
            },
            deleteOldTrack = tracking && replace && snapshot.track != null,
            notesToInsert = if (replace) emptyList() else movedNotes.map { it.copy(id = 0L) },
            notesToUpdate = if (replace) movedNotes else emptyList(),
            moveStats = stats,
            sessionsToUpdate = if (stats) {
                snapshot.sessions.map {
                    it.copy(mangaId = new.id, startChapterId = remap(it.startChapterId), endChapterId = remap(it.endChapterId))
                }
            } else {
                emptyList()
            },
            jumpPointsToUpdate = if (stats) {
                snapshot.jumpPoints.map {
                    it.copy(mangaId = new.id, fromChapterId = remap(it.fromChapterId), toChapterId = remap(it.toChapterId))
                }
            } else {
                emptyList()
            },
        )
    }

    // Moved verbatim from the previous MigrateUseCase implementation.
    private fun makeNewHistory(oldContent: Content, newContent: Content, history: HistoryEntity): HistoryEntity {
        if (oldContent.chapters.isNullOrEmpty()) {
            val branch = newContent.getPreferredBranch(null)
            val chapters = checkNotNull(newContent.getChapters(branch))
            val currentChapter = if (history.percent in 0f..1f) {
                chapters[(chapters.lastIndex * history.percent).toInt()]
            } else {
                chapters.first()
            }
            return history.copy(
                mangaId = newContent.id,
                chapterId = currentChapter.id,
                deletedAt = 0,
                chaptersCount = chapters.count { it.branch == currentChapter.branch },
            )
        }
        val branch = oldContent.getPreferredBranch(history.toContentHistory())
        val oldChapters = checkNotNull(oldContent.getChapters(branch))
        var index = oldChapters.indexOfFirst { it.id == history.chapterId }
        if (index < 0) {
            index = if (history.percent in 0f..1f) (oldChapters.lastIndex * history.percent).toInt() else 0
        }
        val newChapters = checkNotNull(newContent.chapters).groupBy { it.branch }
        val newBranch = if (newChapters.containsKey(branch)) branch else newContent.getPreferredBranch(null)
        val branchChapters = checkNotNull(newChapters[newBranch])
        val oldChapter = oldChapters[index]
        val newChapterId = (branchChapters.findByNumber(oldChapter.volume, oldChapter.number)
            ?: branchChapters.getOrNull(index)
            ?: branchChapters.last()).id
        return history.copy(
            mangaId = newContent.id,
            chapterId = newChapterId,
            percent = PROGRESS_NONE,
            deletedAt = 0,
            chaptersCount = branchChapters.size,
        )
    }

    private fun HistoryEntity.toContentHistory() = ContentHistory(
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        chapterId = chapterId,
        page = page,
        scroll = scroll.toInt(),
        percent = percent,
        chaptersCount = chaptersCount,
        parentChapterId = parentChapterId,
    )

    private fun List<ContentChapter>.findByNumber(volume: Int, number: Float): ContentChapter? =
        if (number <= 0f) null else firstOrNull { it.volume == volume && it.number == number }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS（9 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/domain/MigrationPlanner.kt app/src/test/kotlin/org/skepsun/kototoro/migration/MigrationPlannerTest.kt
git commit -m "feat(migration): plan per-entry migration writes as a pure function"
```

---

### Task 6: `MigrationDao` 与 `LibraryRow`

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/data/LibraryRow.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/data/MigrationDao.kt`
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/core/db/MangaDatabase.kt`（在 `abstract fun getMediaNoteDao()` 之后）

- [ ] **Step 1: 写 `LibraryRow`**

```kotlin
package org.skepsun.kototoro.migration.data

/** One favourited entry with the signals migration, health and duplicate checks need. */
data class LibraryRow(
    val id: Long,
    val title: String,
    val altTitles: String?,
    val source: String,
    val contentType: String?,
    val coverUrl: String,
    val chaptersCount: Int,
    val trackResult: Int?,
    val trackCheckTime: Long?,
    val trackError: String?,
    val historyPercent: Float?,
    val historyChapterNumber: Float?,
) {
    val altTitleList: List<String>
        get() = altTitles?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
```

- [ ] **Step 2: 写 `MigrationDao`**

```kotlin
package org.skepsun.kototoro.migration.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.readingrecord.data.ReadingJumpPointEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity

@Dao
abstract class MigrationDao {

    @Query(
        """
        SELECT m.manga_id AS id, m.title AS title, m.alt_title AS altTitles, m.source AS source,
            m.content_type AS contentType, m.cover_url AS coverUrl,
            (SELECT COUNT(*) FROM chapters c WHERE c.manga_id = m.manga_id) AS chaptersCount,
            t.last_result AS trackResult, t.last_check_time AS trackCheckTime, t.last_error AS trackError,
            h.percent AS historyPercent,
            (SELECT c2.number FROM chapters c2 WHERE c2.manga_id = m.manga_id AND c2.chapter_id = h.chapter_id)
                AS historyChapterNumber
        FROM manga m
        LEFT JOIN tracks t ON t.manga_id = m.manga_id
        LEFT JOIN history h ON h.manga_id = m.manga_id AND h.deleted_at = 0
        WHERE m.manga_id IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)
        """,
    )
    abstract suspend fun findLibraryRows(): List<LibraryRow>

    @Query("SELECT COUNT(*) FROM chapters WHERE manga_id = :mangaId")
    abstract suspend fun countChapters(mangaId: Long): Int

    @Query("SELECT * FROM media_notes WHERE manga_id = :mangaId")
    abstract suspend fun findNotes(mangaId: Long): List<MediaNoteEntity>

    @Insert
    abstract suspend fun insertNotes(notes: List<MediaNoteEntity>)

    @Update
    abstract suspend fun updateNotes(notes: List<MediaNoteEntity>)

    @Query("UPDATE OR IGNORE stats SET manga_id = :newId WHERE manga_id = :oldId")
    abstract suspend fun moveStats(oldId: Long, newId: Long)

    @Query("SELECT * FROM reading_sessions WHERE manga_id = :mangaId")
    abstract suspend fun findSessions(mangaId: Long): List<ReadingRecordEntity>

    @Update
    abstract suspend fun updateSessions(sessions: List<ReadingRecordEntity>)

    @Query("SELECT * FROM reading_jump_points WHERE manga_id = :mangaId")
    abstract suspend fun findJumpPoints(mangaId: Long): List<ReadingJumpPointEntity>

    @Update
    abstract suspend fun updateJumpPoints(points: List<ReadingJumpPointEntity>)
}
```

- [ ] **Step 3: 注册到数据库**

在 `MangaDatabase.kt` 的 `abstract fun getMediaNoteDao(): MediaNoteDao` 之后加入：

```kotlin
    abstract fun getMigrationDao(): org.skepsun.kototoro.migration.data.MigrationDao
```

不需要改 `DATABASE_VERSION`：只新增 DAO，schema 不变。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL。Room KSP 会校验 SQL；如果报列名错误，对照 `core/db/entity/*Entity.kt` 的 `@ColumnInfo(name=...)` 修正 SQL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/data app/src/main/kotlin/org/skepsun/kototoro/core/db/MangaDatabase.kt
git commit -m "feat(migration): add migration dao for library rows and data moves"
```

---

### Task 7: 重写 `MigrateUseCase` 为「快照 → 计划 → 应用」

**Files:**
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/alternatives/domain/MigrateUseCase.kt`（整个文件替换）

- [ ] **Step 1: 替换实现**

```kotlin
package org.skepsun.kototoro.alternatives.domain

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationPlanner
import org.skepsun.kototoro.migration.domain.MigrationSnapshot
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import javax.inject.Inject

/**
 * Moves (or copies) one entry's user data onto another entry. Everything is written in a
 * single transaction; the old manga row itself is never deleted so local downloads and
 * foreign-key children that migration does not carry (bookmarks) survive.
 */
class MigrateUseCase @Inject constructor(
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val mangaDataRepository: ContentDataRepository,
    private val database: MangaDatabase,
    private val progressUpdateUseCase: ProgressUpdateUseCase,
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {

    suspend operator fun invoke(
        oldContent: Content,
        newContent: Content,
        mode: MigrationMode = MigrationMode.REPLACE,
        flags: Set<MigrationDataFlag> = MigrationDataFlag.ALL,
    ) {
        val oldDetails = if (oldContent.chapters.isNullOrEmpty()) {
            mangaDataRepository.findContentById(oldContent.id, withChapters = true)
                ?.takeUnless { it.chapters.isNullOrEmpty() }
                ?: runCatchingCancellable {
                    mangaRepositoryFactory.create(oldContent.source).getDetails(oldContent)
                }.getOrDefault(oldContent)
        } else {
            oldContent
        }
        val newDetails = if (newContent.chapters.isNullOrEmpty()) {
            mangaRepositoryFactory.create(newContent.source).getDetails(newContent)
        } else {
            newContent
        }
        val stored = mangaDataRepository.storeContentAndReturn(newDetails, replaceExisting = true)
        val migrationDao = database.getMigrationDao()
        val oldId = oldDetails.id
        val plan = database.withTransaction {
            val snapshot = MigrationSnapshot(
                favourites = database.getFavouritesDao().findActiveByMangaId(oldId),
                history = database.getHistoryDao().find(oldId),
                prefs = database.getPreferencesDao().find(oldId),
                trackingLinks = database.getTrackingSiteDao().findLinksByManga(oldId),
                track = database.getTracksDao().find(oldId),
                notes = migrationDao.findNotes(oldId),
                sessions = migrationDao.findSessions(oldId),
                jumpPoints = migrationDao.findJumpPoints(oldId),
            )
            val plan = MigrationPlanner.plan(oldDetails, stored, snapshot, mode, flags, System.currentTimeMillis())

            val favouritesDao = database.getFavouritesDao()
            if (plan.deleteOldFavourites) favouritesDao.delete(oldId)
            plan.favouritesToUpsert.forEach { favouritesDao.upsert(it) }

            val historyDao = database.getHistoryDao()
            if (plan.deleteOldHistory) historyDao.delete(oldId)
            plan.historyToUpsert?.let { historyDao.upsert(it) }

            plan.prefsToUpsert?.let { database.getPreferencesDao().upsert(it) }

            val trackingSiteDao = database.getTrackingSiteDao()
            plan.trackingLinksToDelete.forEach { trackingSiteDao.deleteLink(it.service, it.remoteId, it.mangaId) }
            plan.trackingLinksToUpsert.forEach { trackingSiteDao.upsertLink(it) }

            val tracksDao = database.getTracksDao()
            if (plan.deleteOldTrack) tracksDao.delete(oldId)
            plan.trackToUpsert?.let { tracksDao.upsert(it) }

            if (plan.notesToUpdate.isNotEmpty()) migrationDao.updateNotes(plan.notesToUpdate)
            if (plan.notesToInsert.isNotEmpty()) migrationDao.insertNotes(plan.notesToInsert)
            if (plan.moveStats) migrationDao.moveStats(oldId, stored.id)
            if (plan.sessionsToUpdate.isNotEmpty()) migrationDao.updateSessions(plan.sessionsToUpdate)
            if (plan.jumpPointsToUpdate.isNotEmpty()) migrationDao.updateJumpPoints(plan.jumpPointsToUpdate)
            plan
        }
        if (MigrationDataFlag.TRACKING in flags) {
            migrateScrobbling(oldId, stored, mode, plan.historyToUpsert?.chapterId, plan.historyToUpsert?.percent)
        }
        progressUpdateUseCase(stored)
    }

    private suspend fun migrateScrobbling(
        oldId: Long,
        stored: Content,
        mode: MigrationMode,
        historyChapterId: Long?,
        historyPercent: Float?,
    ) {
        for (scrobbler in scrobblers) {
            if (!scrobbler.isEnabled) continue
            val prevInfo = scrobbler.getScrobblingInfoOrNull(oldId) ?: continue
            if (mode == MigrationMode.REPLACE) {
                scrobbler.unregisterScrobbling(oldId)
            }
            scrobbler.linkContent(
                stored.id,
                ScrobblerContent(
                    id = prevInfo.targetId,
                    name = prevInfo.title,
                    altName = null,
                    cover = prevInfo.coverUrl,
                    url = prevInfo.externalUrl,
                ),
            )
            scrobbler.updateScrobblingInfo(
                mangaId = stored.id,
                rating = prevInfo.rating,
                status = prevInfo.status ?: when {
                    historyChapterId == null -> ScrobblingStatus.PLANNED
                    historyPercent == 1f -> ScrobblingStatus.COMPLETED
                    else -> ScrobblingStatus.READING
                },
                comment = prevInfo.comment,
            )
            if (historyChapterId != null) {
                scrobbler.scrobble(manga = stored, chapterId = historyChapterId)
            }
        }
    }
}
```

说明：
- `tracksDao.delete(oldId)` 与原实现相同。如果 `TracksDao` 的删除方法签名不同，用原文件里调用的同名方法。
- `historyDao.delete` 是软删除（`setDeletedAt`），与原实现一致。

- [ ] **Step 2: 编译并运行已有测试**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`，然后运行 `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.*" --no-daemon`
Expected: BUILD SUCCESSFUL；migration 测试全部 PASS。`AlternativesViewModel` 和 `AutoFixUseCase` 的调用 `migrateUseCase(old, new)` 使用默认参数，无需修改。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/alternatives/domain/MigrateUseCase.kt
git commit -m "feat(migration): apply planned migration with copy mode and data flags"
```

---

### Task 8: `SmartMatchEngine`

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/SmartMatchEngine.kt`
- Test: `app/src/test/kotlin/org/skepsun/kototoro/migration/SmartMatchEngineTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.migration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.MatchMode
import org.skepsun.kototoro.migration.domain.SmartMatchEngine
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

private data class FakeSource(override val name: String) : ContentSource {
    override val locale = ""
    override val contentType = ContentType.MANGA
}

private fun item(id: Long, title: String, source: ContentSource, chapters: Int = 0) = Content(
    id = id, title = title, altTitles = emptySet(), url = "/$id", publicUrl = "", rating = -1f,
    contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
    chapters = List(chapters) { chapter(id * 1000 + it, (it + 1).toFloat()) }, source = source,
)

class SmartMatchEngineTest {
    private val a = FakeSource("A")
    private val b = FakeSource("B")
    private val c = FakeSource("C")
    private val origin = item(1, "Frieren", FakeSource("OLD"))

    private fun engine(results: Map<ContentSource, List<Content>>, failing: Set<ContentSource> = emptySet()) =
        SmartMatchEngine(
            search = { source, _ ->
                if (source in failing) error("down")
                results[source].orEmpty()
            },
            fetchDetails = { it },
        )

    @Test
    fun `first hit stops at the first eligible source`() = runTest {
        val searched = mutableListOf<String>()
        val engine = SmartMatchEngine(
            search = { source, _ ->
                searched += source.name
                if (source == b) listOf(item(20, "Frieren", b, 3)) else emptyList()
            },
            fetchDetails = { it },
        )
        val result = engine.match(origin, listOf(a, b, c), MatchMode.FIRST_HIT, extraQuery = "", deepSearch = false)
        assertEquals(20L, result.best?.id)
        assertEquals(listOf("A", "B"), searched)
    }

    @Test
    fun `most chapters picks the longest eligible candidate`() = runTest {
        val engine = engine(
            mapOf(
                a to listOf(item(10, "Frieren", a, 5)),
                b to listOf(item(20, "Frieren", b, 9)),
            ),
        )
        val result = engine.match(origin, listOf(a, b), MatchMode.MOST_CHAPTERS, extraQuery = "", deepSearch = false)
        assertEquals(20L, result.best?.id)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `candidates below threshold are ignored`() = runTest {
        val engine = engine(mapOf(a to listOf(item(10, "Blue Lock", a), item(11, "Chainsaw Man", a))))
        val result = engine.match(origin, listOf(a), MatchMode.FIRST_HIT, extraQuery = "", deepSearch = false)
        assertNull(result.best)
    }

    @Test
    fun `origin itself is excluded`() = runTest {
        val engine = engine(mapOf(a to listOf(origin.copy(source = a, id = 1))))
        val result = engine.match(origin, listOf(a), MatchMode.FIRST_HIT, extraQuery = "", deepSearch = false)
        assertNull(result.best)
    }

    @Test
    fun `failing source is recorded and skipped`() = runTest {
        val engine = engine(mapOf(b to listOf(item(20, "Frieren", b, 1))), failing = setOf(a))
        val result = engine.match(origin, listOf(a, b), MatchMode.FIRST_HIT, extraQuery = "", deepSearch = false)
        assertEquals(20L, result.best?.id)
        assertEquals(setOf("A"), result.errors.keys)
    }

    @Test
    fun `extra query is appended to the title`() = runTest {
        val queries = mutableListOf<String>()
        val engine = SmartMatchEngine(search = { _, q -> queries += q; emptyList() }, fetchDetails = { it })
        engine.match(origin, listOf(a), MatchMode.FIRST_HIT, extraQuery = "official", deepSearch = false)
        assertEquals(listOf("Frieren official"), queries)
    }

    @Test
    fun `search source returns scored candidates sorted`() = runTest {
        val engine = engine(mapOf(a to listOf(item(10, "Frieren 2", a), item(11, "Frieren", a))))
        val outcome = engine.searchSource(origin, a, query = "Frieren")
        assertEquals(listOf(11L, 10L), outcome.candidates.map { it.content.id })
        assertTrue(outcome.error == null)
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.SmartMatchEngineTest" --no-daemon`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.migration.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

data class MatchCandidate(val content: Content, val score: Double)

data class SourceOutcome(val source: ContentSource, val candidates: List<MatchCandidate>, val error: Throwable?)

data class MatchResult(
    /** Best match with details (chapters) loaded, or null when nothing was eligible. */
    val best: Content?,
    /** Eligible candidates from every searched source, best first. */
    val candidates: List<MatchCandidate>,
    /** Source name → error for sources that failed. */
    val errors: Map<String, Throwable>,
)

/**
 * Finds the entry on other sources that most likely is the same work. [search] and
 * [fetchDetails] are injected so the engine stays free of Android and network types;
 * [withSourcePermit] lets the caller throttle requests per source.
 */
class SmartMatchEngine(
    private val search: suspend (ContentSource, String) -> List<Content>,
    private val fetchDetails: suspend (Content) -> Content,
    private val withSourcePermit: suspend (ContentSource, suspend () -> Unit) -> Unit = { _, block -> block() },
) {

    suspend fun match(
        origin: Content,
        sources: List<ContentSource>,
        mode: MatchMode,
        extraQuery: String,
        deepSearch: Boolean,
    ): MatchResult = when (mode) {
        MatchMode.FIRST_HIT -> matchFirstHit(origin, sources, extraQuery, deepSearch)
        MatchMode.MOST_CHAPTERS -> matchMostChapters(origin, sources, extraQuery, deepSearch)
    }

    /** Searches one source with one query (used by manual search). */
    suspend fun searchSource(origin: Content, source: ContentSource, query: String): SourceOutcome =
        searchSource(origin, source, listOf(query), deepSearch = false)

    private suspend fun matchFirstHit(
        origin: Content,
        sources: List<ContentSource>,
        extraQuery: String,
        deepSearch: Boolean,
    ): MatchResult {
        val candidates = mutableListOf<MatchCandidate>()
        val errors = mutableMapOf<String, Throwable>()
        for (source in sources) {
            val outcome = searchSource(origin, source, queriesFor(origin, extraQuery, deepSearch), deepSearch)
            outcome.error?.let { errors[source.name] = it }
            candidates += outcome.candidates
            val top = outcome.candidates.firstOrNull() ?: continue
            val details = runCatchingCancellable { fetchDetails(top.content) }
                .onFailure { errors[source.name] = it }
                .getOrNull() ?: continue
            return MatchResult(details, candidates.sortedByDescending { it.score }, errors)
        }
        return MatchResult(null, candidates.sortedByDescending { it.score }, errors)
    }

    private suspend fun matchMostChapters(
        origin: Content,
        sources: List<ContentSource>,
        extraQuery: String,
        deepSearch: Boolean,
    ): MatchResult = coroutineScope {
        val queries = queriesFor(origin, extraQuery, deepSearch)
        val outcomes = sources.map { source ->
            async {
                val outcome = searchSource(origin, source, queries, deepSearch)
                val top = outcome.candidates.firstOrNull()
                val details = top?.let {
                    runCatchingCancellable { fetchDetails(it.content) }.getOrNull()
                }
                Triple(outcome, top, details)
            }
        }.awaitAll()
        val errors = outcomes.mapNotNull { (o, _, _) -> o.error?.let { o.source.name to it } }.toMap()
        val best = outcomes
            .mapNotNull { (_, top, details) -> if (top != null && details != null) top.score to details else null }
            .maxWithOrNull(compareBy<Pair<Double, Content>> { it.second.chaptersCount() }.thenBy { it.first })
            ?.second
        MatchResult(best, outcomes.flatMap { it.first.candidates }.sortedByDescending { it.score }, errors)
    }

    private suspend fun searchSource(
        origin: Content,
        source: ContentSource,
        queries: List<String>,
        deepSearch: Boolean,
    ): SourceOutcome {
        var error: Throwable? = null
        val originTitles = originTitles(origin, deepSearch)
        val found = LinkedHashMap<Long, MatchCandidate>()
        for (query in queries) {
            var results: List<Content> = emptyList()
            withSourcePermit(source) {
                results = runCatchingCancellable { search(source, query) }
                    .onFailure { error = it }
                    .getOrDefault(emptyList())
            }
            val filtered = results.filterNot { it.isSameEntryAs(origin) }
            for (candidate in filtered) {
                val score = if (queries.size == 1 && filtered.size == 1) {
                    1.0
                } else {
                    TitleSimilarity.bestSimilarity(originTitles, candidateTitles(candidate, deepSearch))
                }
                if (score < TitleSimilarity.MIN_ELIGIBLE) continue
                val previous = found[candidate.id]
                if (previous == null || previous.score < score) {
                    found[candidate.id] = MatchCandidate(candidate, score)
                }
            }
        }
        return SourceOutcome(source, found.values.sortedByDescending { it.score }.take(MAX_PER_SOURCE), error)
    }

    private fun queriesFor(origin: Content, extraQuery: String, deepSearch: Boolean): List<String> {
        val base = if (deepSearch) {
            TitleSimilarity.deepSearchQueries(TitleSimilarity.cleanDeepSearchTitle(origin.title))
                .ifEmpty { listOf(origin.title) }
        } else {
            listOf(origin.title)
        }
        val extra = extraQuery.trim()
        return if (extra.isEmpty()) base else base.map { "$it $extra" }
    }

    private fun originTitles(origin: Content, deepSearch: Boolean): List<String> {
        val titles = listOf(origin.title) + origin.altTitles
        return if (deepSearch) titles.map(TitleSimilarity::cleanDeepSearchTitle) else titles
    }

    private fun candidateTitles(candidate: Content, deepSearch: Boolean): List<String> {
        val titles = listOf(candidate.title) + candidate.altTitles
        return if (deepSearch) titles.map(TitleSimilarity::cleanDeepSearchTitle) else titles
    }

    private fun Content.isSameEntryAs(origin: Content): Boolean =
        id == origin.id || (source.name == origin.source.name && url == origin.url)

    private companion object {
        const val MAX_PER_SOURCE = 3
    }
}
```

说明：`chaptersCount()` 来自 `core/model/Content.kt:161`。如果它依赖 Android API 导致 JVM 测试失败，把排序键改为 `it.second.chapters?.size ?: 0`。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS（7 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/domain/SmartMatchEngine.kt app/src/test/kotlin/org/skepsun/kototoro/migration/SmartMatchEngineTest.kt
git commit -m "feat(migration): add smart match engine with first-hit and most-chapters modes"
```

---

### Task 9: 来源健康分类

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/SourceHealth.kt`
- Test: `app/src/test/kotlin/org/skepsun/kototoro/migration/SourceHealthClassifierTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.SourceHealthClassifier
import org.skepsun.kototoro.migration.domain.SourceHealthStatus
import org.skepsun.kototoro.migration.domain.SourceSignals
import org.skepsun.kototoro.migration.domain.TrackSignal
import org.skepsun.kototoro.tracker.data.TrackEntity

class SourceHealthClassifierTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val ok = SourceSignals(isUnresolved = false, isBroken = false, isDisabled = false)
    private fun failed(daysAgo: Long, error: String? = "HTTP 403") =
        TrackSignal(TrackEntity.RESULT_FAILED, now - daysAgo * day, error)

    @Test
    fun `unresolved wins over everything`() {
        val signals = SourceSignals(isUnresolved = true, isBroken = true, isDisabled = true)
        assertEquals(SourceHealthStatus.UNINSTALLED, SourceHealthClassifier.classify(signals, emptyList(), now).status)
    }

    @Test
    fun `broken wins over failing and disabled`() {
        val signals = ok.copy(isBroken = true, isDisabled = true)
        assertEquals(SourceHealthStatus.BROKEN, SourceHealthClassifier.classify(signals, emptyList(), now).status)
    }

    @Test
    fun `two recent failures and no success mean failing with summary`() {
        val verdict = SourceHealthClassifier.classify(ok, listOf(failed(1), failed(3)), now)
        assertEquals(SourceHealthStatus.FAILING, verdict.status)
        assertEquals("HTTP 403", verdict.errorSummary)
    }

    @Test
    fun `a single failure is not enough`() {
        assertEquals(SourceHealthStatus.HEALTHY, SourceHealthClassifier.classify(ok, listOf(failed(1)), now).status)
    }

    @Test
    fun `a recent success clears failing`() {
        val success = TrackSignal(TrackEntity.RESULT_NO_UPDATE, now - day, null)
        assertEquals(
            SourceHealthStatus.HEALTHY,
            SourceHealthClassifier.classify(ok, listOf(failed(1), failed(2), success), now).status,
        )
    }

    @Test
    fun `failures older than fourteen days are ignored`() {
        assertEquals(
            SourceHealthStatus.HEALTHY,
            SourceHealthClassifier.classify(ok, listOf(failed(20), failed(30)), now).status,
        )
    }

    @Test
    fun `disabled is reported when nothing worse applies`() {
        assertEquals(
            SourceHealthStatus.DISABLED,
            SourceHealthClassifier.classify(ok.copy(isDisabled = true), emptyList(), now).status,
        )
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.SourceHealthClassifierTest" --no-daemon`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.tracker.data.TrackEntity

enum class SourceHealthStatus {
    UNINSTALLED,
    BROKEN,
    FAILING,
    DISABLED,
    HEALTHY,
    ;

    val needsAttention: Boolean get() = this != HEALTHY
}

data class SourceSignals(val isUnresolved: Boolean, val isBroken: Boolean, val isDisabled: Boolean)

data class TrackSignal(val lastResult: Int?, val lastCheckTime: Long?, val lastError: String?)

data class SourceVerdict(val status: SourceHealthStatus, val errorSummary: String?)

data class SourceHealth(
    val source: ContentSource,
    val status: SourceHealthStatus,
    val errorSummary: String?,
    val contentIds: List<Long>,
) {
    val favouriteCount: Int get() = contentIds.size
}

/** Offline verdict for one source, in priority order uninstalled > broken > failing > disabled. */
object SourceHealthClassifier {

    private const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    private const val MIN_FAILURES = 2

    fun classify(signals: SourceSignals, tracks: List<TrackSignal>, now: Long): SourceVerdict {
        if (signals.isUnresolved) return SourceVerdict(SourceHealthStatus.UNINSTALLED, null)
        if (signals.isBroken) return SourceVerdict(SourceHealthStatus.BROKEN, null)
        val recent = tracks.filter { track ->
            val checkedAt = track.lastCheckTime ?: return@filter false
            val result = track.lastResult ?: return@filter false
            result != TrackEntity.RESULT_NONE && now - checkedAt <= WINDOW_MS
        }
        if (recent.size >= MIN_FAILURES && recent.all { it.lastResult == TrackEntity.RESULT_FAILED }) {
            val summary = recent.mapNotNull { it.lastError?.takeIf(String::isNotBlank) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            return SourceVerdict(SourceHealthStatus.FAILING, summary)
        }
        if (signals.isDisabled) return SourceVerdict(SourceHealthStatus.DISABLED, null)
        return SourceVerdict(SourceHealthStatus.HEALTHY, null)
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS（7 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/domain/SourceHealth.kt app/src/test/kotlin/org/skepsun/kototoro/migration/SourceHealthClassifierTest.kt
git commit -m "feat(migration): classify source health offline"
```

---

### Task 10: `DuplicateMatcher`

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/DuplicateMatcher.kt`
- Test: `app/src/test/kotlin/org/skepsun/kototoro/migration/DuplicateMatcherTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.domain.DuplicateMatcher
import org.skepsun.kototoro.parsers.model.ContentType

class DuplicateMatcherTest {
    private fun row(id: Long, title: String, alt: String? = null, type: String? = "MANGA") = LibraryRow(
        id = id, title = title, altTitles = alt, source = "S", contentType = type, coverUrl = "",
        chaptersCount = 0, trackResult = null, trackCheckTime = null, trackError = null,
        historyPercent = null, historyChapterNumber = null,
    )

    @Test
    fun `matches normalized title`() {
        val result = DuplicateMatcher.find(
            id = 99, title = "ＯＮＥ ＰＩＥＣＥ", altTitles = emptySet(), family = ContentType.MANGA,
            library = listOf(row(1, "One Piece"), row(2, "Naruto")),
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `matches through alternative titles on either side`() {
        val result = DuplicateMatcher.find(
            id = 99, title = "葬送的芙莉莲", altTitles = setOf("Sousou no Frieren"), family = ContentType.MANGA,
            library = listOf(row(1, "Sousou no Frieren"), row(2, "フリーレン", alt = "葬送的芙莉莲")),
        )
        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun `different content family is ignored`() {
        val result = DuplicateMatcher.find(
            id = 99, title = "Frieren", altTitles = emptySet(), family = ContentType.MANGA,
            library = listOf(row(1, "Frieren", type = "VIDEO")),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `self is excluded`() {
        val result = DuplicateMatcher.find(
            id = 1, title = "Frieren", altTitles = emptySet(), family = ContentType.MANGA,
            library = listOf(row(1, "Frieren")),
        )
        assertTrue(result.isEmpty())
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.DuplicateMatcherTest" --no-daemon`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.parsers.model.ContentType

/** Exact (normalized) title or alt-title matching; no fuzzy matching on purpose. */
object DuplicateMatcher {

    fun keys(title: String, altTitles: Collection<String>): Set<String> =
        (listOf(title) + altTitles).map(TitleNormalizer::normalize).filterTo(mutableSetOf()) { it.isNotEmpty() }

    fun find(
        id: Long,
        title: String,
        altTitles: Collection<String>,
        family: ContentType?,
        library: List<LibraryRow>,
    ): List<LibraryRow> {
        val targetKeys = keys(title, altTitles)
        if (targetKeys.isEmpty()) return emptyList()
        val targetFamily = family?.contentFamily()
        return library.filter { row ->
            row.id != id &&
                row.contentFamily() == targetFamily &&
                keys(row.title, row.altTitleList).any { it in targetKeys }
        }
    }

    private fun LibraryRow.contentFamily() =
        contentType?.let { name -> ContentType.entries.firstOrNull { it.name == name } }?.contentFamily()
}
```

注意：`contentType` 为 null 的旧数据族为 null，只会与同样为 null 的目标匹配。实际调用时，`family` 取自 `content.source.getContentType()`，不会为 null，所以这类旧数据不会被报为重复（宁可漏报，也不误报）。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS（4 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/domain/DuplicateMatcher.kt app/src/test/kotlin/org/skepsun/kototoro/migration/DuplicateMatcherTest.kt
git commit -m "feat(migration): add exact duplicate matcher"
```

---

### Task 11: `MigrationSettings`、`SourceHealthUseCase`、`FindLibraryDuplicatesUseCase`

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/MigrationSettings.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/SourceHealthUseCase.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/domain/FindLibraryDuplicatesUseCase.kt`

这三个类是胶水代码（读偏好设置、读库、解析来源），核心逻辑已在 Task 9、10 中测试。本任务只做编译验证。

- [ ] **Step 1: `MigrationSettings`**

```kotlin
package org.skepsun.kototoro.migration.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.core.model.ContentTypeFamily
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MigrationSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("migration", Context.MODE_PRIVATE)

    /** Ordered target source names for a content family, or null when never chosen. */
    fun getTargetSourceNames(family: ContentTypeFamily): List<String>? =
        prefs.getString(KEY_SOURCES_PREFIX + family.name, null)?.split('\n')?.filter { it.isNotEmpty() }

    fun setTargetSourceNames(family: ContentTypeFamily, names: List<String>) =
        prefs.edit { putString(KEY_SOURCES_PREFIX + family.name, names.joinToString("\n")) }

    var dataFlags: Set<MigrationDataFlag>
        get() = MigrationDataFlag.fromBits(prefs.getInt(KEY_FLAGS, MigrationDataFlag.toBits(MigrationDataFlag.ALL)))
        set(value) = prefs.edit { putInt(KEY_FLAGS, MigrationDataFlag.toBits(value)) }

    var matchMode: MatchMode
        get() = prefs.getString(KEY_MATCH_MODE, null)
            ?.let { name -> MatchMode.entries.firstOrNull { it.name == name } } ?: MatchMode.FIRST_HIT
        set(value) = prefs.edit { putString(KEY_MATCH_MODE, value.name) }

    var extraQuery: String
        get() = prefs.getString(KEY_EXTRA_QUERY, null).orEmpty()
        set(value) = prefs.edit { putString(KEY_EXTRA_QUERY, value) }

    var isDeepSearch: Boolean
        get() = prefs.getBoolean(KEY_DEEP_SEARCH, false)
        set(value) = prefs.edit { putBoolean(KEY_DEEP_SEARCH, value) }

    var hideUnmatched: Boolean
        get() = prefs.getBoolean(KEY_HIDE_UNMATCHED, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_UNMATCHED, value) }

    var hideWithoutUpdates: Boolean
        get() = prefs.getBoolean(KEY_HIDE_NO_UPDATES, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_NO_UPDATES, value) }

    var isDuplicateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_DUPLICATE_CHECK, true)
        set(value) = prefs.edit { putBoolean(KEY_DUPLICATE_CHECK, value) }

    /** Key of the unhealthy-source set the user dismissed on the favourites banner. */
    var dismissedHealthKey: String?
        get() = prefs.getString(KEY_DISMISSED_HEALTH, null)
        set(value) = prefs.edit { putString(KEY_DISMISSED_HEALTH, value) }

    private companion object {
        const val KEY_SOURCES_PREFIX = "sources_"
        const val KEY_FLAGS = "flags"
        const val KEY_MATCH_MODE = "match_mode"
        const val KEY_EXTRA_QUERY = "extra_query"
        const val KEY_DEEP_SEARCH = "deep_search"
        const val KEY_HIDE_UNMATCHED = "hide_unmatched"
        const val KEY_HIDE_NO_UPDATES = "hide_no_updates"
        const val KEY_DUPLICATE_CHECK = "duplicate_check"
        const val KEY_DISMISSED_HEALTH = "dismissed_health"
    }
}
```

- [ ] **Step 2: `SourceHealthUseCase`**

```kotlin
package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.extensions.PluginContentSource
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isUnresolved
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.migration.data.LibraryRow
import javax.inject.Inject

class SourceHealthUseCase @Inject constructor(
    private val database: MangaDatabase,
    private val sourcesRepository: ContentSourcesRepository,
) {

    /** Health of every non-local source that has favourites; attention-needing first, then by count. */
    suspend operator fun invoke(now: Long = System.currentTimeMillis()): List<SourceHealth> {
        val rows = database.getMigrationDao().findLibraryRows()
        val disabled = sourcesRepository.getDisabledSources().mapTo(HashSet()) { it.name }
        return rows.groupBy { it.source }
            .mapNotNull { (name, group) -> evaluate(name, group, disabled, now) }
            .sortedWith(compareBy<SourceHealth> { !it.status.needsAttention }.thenByDescending { it.favouriteCount })
    }

    /** Verdict for a single source, used by the details screen. */
    suspend fun forSource(sourceName: String, now: Long = System.currentTimeMillis()): SourceHealthStatus {
        val rows = database.getMigrationDao().findLibraryRows().filter { it.source == sourceName }
        val disabled = sourcesRepository.getDisabledSources().mapTo(HashSet()) { it.name }
        return evaluate(sourceName, rows, disabled, now)?.status ?: SourceHealthStatus.HEALTHY
    }

    private fun evaluate(name: String, rows: List<LibraryRow>, disabled: Set<String>, now: Long): SourceHealth? {
        val source = ContentSource(name)
        if (source.isLocal) return null
        val signals = SourceSignals(
            isUnresolved = source.isUnresolved,
            isBroken = (source as? PluginContentSource)?.isBroken == true,
            isDisabled = name in disabled,
        )
        val tracks = rows.map { TrackSignal(it.trackResult, it.trackCheckTime, it.trackError) }
        val verdict = SourceHealthClassifier.classify(signals, tracks, now)
        return SourceHealth(source, verdict.status, verdict.errorSummary, rows.map { it.id })
    }
}
```

`forSource` 在来源下没有收藏作品时，只能检查来源是否未安装或已损坏。本计划只在收藏作品的详情页显示警告，所以这种情况直接返回 `HEALTHY` 即可。

- [ ] **Step 3: `FindLibraryDuplicatesUseCase`**

```kotlin
package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

class FindLibraryDuplicatesUseCase @Inject constructor(
    private val database: MangaDatabase,
    private val settings: MigrationSettings,
) {

    /**
     * Favourited entries that look like the same work as [content]. Empty when the check is
     * disabled, when [content] is already favourited, or on any error (never block favouriting).
     */
    suspend operator fun invoke(content: Content): List<LibraryRow> {
        if (!settings.isDuplicateCheckEnabled) return emptyList()
        return runCatchingCancellable {
            if (database.getFavouritesDao().findCategories(content.id).isNotEmpty()) {
                return@runCatchingCancellable emptyList()
            }
            DuplicateMatcher.find(
                id = content.id,
                title = content.title,
                altTitles = content.altTitles,
                family = content.source.getContentType(),
                library = database.getMigrationDao().findLibraryRows(),
            )
        }.getOrDefault(emptyList())
    }
}
```

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/domain
git commit -m "feat(migration): add settings, source health and duplicate use cases"
```

---

### Task 12: 字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（追加到 `</resources>` 之前）
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`（追加到 `</resources>` 之前）

- [ ] **Step 1: 英文**

```xml
    <string name="migration_title">Migrate</string>
    <string name="migration_title_progress">Matching %1$d / %2$d</string>
    <string name="migration_config_title">Migrate %d entries</string>
    <string name="migration_config_subtitle">%1$s · from %2$s</string>
    <string name="migration_config_sources">Search these sources (in order)</string>
    <string name="migration_config_edit">Edit</string>
    <string name="migration_config_data">Also migrate</string>
    <string name="migration_flag_categories">Categories</string>
    <string name="migration_flag_progress">Reading progress</string>
    <string name="migration_flag_tracking">Tracking &amp; sync</string>
    <string name="migration_flag_notes">Notes</string>
    <string name="migration_flag_stats">Reading stats</string>
    <string name="migration_config_match">Matching</string>
    <string name="migration_match_first_hit">First match</string>
    <string name="migration_match_most_chapters">Most chapters</string>
    <string name="migration_config_more">More options</string>
    <string name="migration_extra_query">Extra search keywords</string>
    <string name="migration_deep_search">Deep search</string>
    <string name="migration_deep_search_summary">Try shortened titles when the full title finds nothing. Slower</string>
    <string name="migration_hide_unmatched">Hide entries with no match</string>
    <string name="migration_hide_without_updates">Hide entries without new chapters</string>
    <string name="migration_duplicate_check">Check for duplicates when favouriting</string>
    <string name="migration_start">Start matching</string>
    <string name="migration_no_sources">Pick at least one source</string>
    <string name="migration_sources_picker_title">Sources</string>
    <string name="migration_sources_picker_hint">Sources are searched in the order you tick them</string>
    <string name="migration_select_all">All</string>
    <string name="migration_select_pinned">Pinned</string>
    <string name="migration_select_enabled">Enabled</string>
    <string name="migration_select_none">Clear</string>
    <string name="migration_filter_all">All %d</string>
    <string name="migration_filter_matched">Matched %d</string>
    <string name="migration_filter_not_found">Not found %d</string>
    <string name="migration_filter_fewer">Fewer chapters %d</string>
    <string name="migration_status_waiting">Waiting</string>
    <string name="migration_status_searching">Matching…</string>
    <string name="migration_status_not_found">Not found</string>
    <string name="migration_status_failed">Failed: %s</string>
    <string name="migration_chapters">%1$s · %2$d ch.</string>
    <string name="migration_more_candidates">%d more candidates ›</string>
    <string name="migration_tap_to_search">Tap to search manually</string>
    <string name="migration_action_search">Search manually</string>
    <string name="migration_action_skip">Skip</string>
    <string name="migration_action_migrate_now">Migrate this one now</string>
    <string name="migration_action_open">Open original</string>
    <string name="migration_copy_n">Copy %d</string>
    <string name="migration_migrate_n">Migrate %d</string>
    <string name="migration_confirm_title">Migrate %d entries?</string>
    <string name="migration_confirm_skipped">%d entries without a match will be skipped.</string>
    <string name="migration_confirm_replace">Old entries will be removed from favourites and history.</string>
    <string name="migration_confirm_copy">Old entries stay in your library.</string>
    <string name="migration_progress">Migrating…</string>
    <string name="migration_result">Done: %1$d succeeded, %2$d failed</string>
    <string name="migration_exit_title">Abandon this migration?</string>
    <string name="migration_exit_message">Matching results will be lost.</string>
    <string name="migration_abandon">Abandon</string>
    <string name="migration_candidates_title">Candidates</string>
    <string name="migration_search_hint">Search in target sources</string>
    <string name="migration_sources_title">Migrate by source</string>
    <string name="migration_health_banner">%1$d sources may be broken, affecting %2$d favourites</string>
    <string name="migration_health_banner_action">Migrate these %d</string>
    <string name="migration_health_needs_attention">Needs attention</string>
    <string name="migration_health_ok">OK</string>
    <string name="migration_health_uninstalled">Not installed</string>
    <string name="migration_health_uninstalled_summary">Extension removed or source deleted</string>
    <string name="migration_health_broken">Marked broken</string>
    <string name="migration_health_broken_summary">The extension repository marks this source broken</string>
    <string name="migration_health_failing">Updates failing</string>
    <string name="migration_health_disabled">Disabled</string>
    <string name="migration_health_disabled_summary">You turned this source off</string>
    <string name="migration_next">Next</string>
    <string name="migration_health_hint">%d sources may be broken</string>
    <string name="migration_health_details_warning">This source may be broken (%s)</string>
    <string name="duplicate_title">This may already be in your library</string>
    <string name="duplicate_subtitle">Favouriting: %1$s · %2$s · %3$d ch.</string>
    <string name="duplicate_read_to">Read to ch. %s</string>
    <string name="duplicate_unread">Unread</string>
    <string name="duplicate_switch_source">Switch to this source</string>
    <string name="duplicate_add_anyway">Favourite anyway</string>
    <string name="duplicate_dont_ask">Don\'t ask again</string>
    <string name="duplicate_source_broken">Source broken</string>
```

- [ ] **Step 2: 简体中文**

```xml
    <string name="migration_title">换源</string>
    <string name="migration_title_progress">匹配中 %1$d / %2$d</string>
    <string name="migration_config_title">换源 %d 部作品</string>
    <string name="migration_config_subtitle">%1$s · 来自 %2$s</string>
    <string name="migration_config_sources">搜索这些来源（按顺序）</string>
    <string name="migration_config_edit">编辑</string>
    <string name="migration_config_data">一并迁移</string>
    <string name="migration_flag_categories">收藏分类</string>
    <string name="migration_flag_progress">阅读进度</string>
    <string name="migration_flag_tracking">追踪与同步</string>
    <string name="migration_flag_notes">笔记</string>
    <string name="migration_flag_stats">阅读统计</string>
    <string name="migration_config_match">匹配方式</string>
    <string name="migration_match_first_hit">最快命中</string>
    <string name="migration_match_most_chapters">章节最多</string>
    <string name="migration_config_more">更多选项</string>
    <string name="migration_extra_query">附加搜索关键词</string>
    <string name="migration_deep_search">深度搜索</string>
    <string name="migration_deep_search_summary">完整标题搜不到时尝试缩短后的标题，较慢</string>
    <string name="migration_hide_unmatched">隐藏未找到的作品</string>
    <string name="migration_hide_without_updates">隐藏没有新章节的作品</string>
    <string name="migration_duplicate_check">收藏时检查重复</string>
    <string name="migration_start">开始匹配</string>
    <string name="migration_no_sources">至少选择一个来源</string>
    <string name="migration_sources_picker_title">来源</string>
    <string name="migration_sources_picker_hint">按勾选的先后顺序搜索</string>
    <string name="migration_select_all">全选</string>
    <string name="migration_select_pinned">仅置顶</string>
    <string name="migration_select_enabled">仅已启用</string>
    <string name="migration_select_none">清空</string>
    <string name="migration_filter_all">全部 %d</string>
    <string name="migration_filter_matched">已匹配 %d</string>
    <string name="migration_filter_not_found">未找到 %d</string>
    <string name="migration_filter_fewer">章节变少 %d</string>
    <string name="migration_status_waiting">等待中</string>
    <string name="migration_status_searching">匹配中…</string>
    <string name="migration_status_not_found">未找到</string>
    <string name="migration_status_failed">失败：%s</string>
    <string name="migration_chapters">%1$s %2$d 话</string>
    <string name="migration_more_candidates">还有 %d 个候选 ›</string>
    <string name="migration_tap_to_search">点击手动搜索</string>
    <string name="migration_action_search">手动搜索</string>
    <string name="migration_action_skip">跳过</string>
    <string name="migration_action_migrate_now">立即换源这本</string>
    <string name="migration_action_open">打开原作品</string>
    <string name="migration_copy_n">复制 %d</string>
    <string name="migration_migrate_n">换源 %d 部</string>
    <string name="migration_confirm_title">换源 %d 部作品？</string>
    <string name="migration_confirm_skipped">%d 部未找到匹配，将被跳过。</string>
    <string name="migration_confirm_replace">旧条目会从收藏和历史中移除。</string>
    <string name="migration_confirm_copy">旧条目会保留在书架中。</string>
    <string name="migration_progress">正在换源…</string>
    <string name="migration_result">完成：成功 %1$d，失败 %2$d</string>
    <string name="migration_exit_title">放弃这次换源？</string>
    <string name="migration_exit_message">匹配结果将会丢失。</string>
    <string name="migration_abandon">放弃</string>
    <string name="migration_candidates_title">候选</string>
    <string name="migration_search_hint">在目标来源中搜索</string>
    <string name="migration_sources_title">按来源换源</string>
    <string name="migration_health_banner">%1$d 个来源可能已失效，涉及 %2$d 部收藏</string>
    <string name="migration_health_banner_action">一键换源这 %d 部</string>
    <string name="migration_health_needs_attention">需要处理</string>
    <string name="migration_health_ok">正常</string>
    <string name="migration_health_uninstalled">未安装</string>
    <string name="migration_health_uninstalled_summary">扩展已卸载或来源已删除</string>
    <string name="migration_health_broken">已标记损坏</string>
    <string name="migration_health_broken_summary">扩展仓库声明此来源已损坏</string>
    <string name="migration_health_failing">连续更新失败</string>
    <string name="migration_health_disabled">已停用</string>
    <string name="migration_health_disabled_summary">你在来源管理中关闭了它</string>
    <string name="migration_next">下一步</string>
    <string name="migration_health_hint">%d 个来源可能失效</string>
    <string name="migration_health_details_warning">此来源可能已失效（%s）</string>
    <string name="duplicate_title">书架里可能已经有这部作品</string>
    <string name="duplicate_subtitle">你正在收藏：%1$s · %2$s · %3$d 话</string>
    <string name="duplicate_read_to">读到 第 %s 话</string>
    <string name="duplicate_unread">未读</string>
    <string name="duplicate_switch_source">换成新来源</string>
    <string name="duplicate_add_anyway">仍然收藏</string>
    <string name="duplicate_dont_ask">不再提醒</string>
    <string name="duplicate_source_broken">来源失效</string>
```

- [ ] **Step 3: 检查名称冲突**

Run: `grep -c "name=\"migration_title\"\|name=\"duplicate_title\"" app/src/main/res/values/strings.xml`
Expected: `2`（每个名称只出现一次）。如果已有同名资源，给新资源加前缀 `mig_`，并同步修改后续任务里的引用。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin --no-daemon` → BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(migration): add migration and duplicate prompt strings"
```

---

### Task 13: 迁移列表状态与 reducer

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/list/MigrationListState.kt`
- Test: `app/src/test/kotlin/org/skepsun/kototoro/migration/MigrationListReducerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.migration.ui.list.MigrationFilter
import org.skepsun.kototoro.migration.ui.list.MigrationItemState
import org.skepsun.kototoro.migration.ui.list.MigrationItemStatus
import org.skepsun.kototoro.migration.ui.list.MigrationListState
import org.skepsun.kototoro.parsers.model.Content

class MigrationListReducerTest {
    private fun content(id: Long) = Content(
        id = id, title = "T$id", altTitles = emptySet(), url = "/$id", publicUrl = "", rating = -1f,
        contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
        source = TestContentSource,
    )

    private fun item(id: Long, status: MigrationItemStatus, old: Int = 10, new: Int? = null) = MigrationItemState(
        origin = content(id), originChapters = old, status = status,
        target = if (new != null) content(id + 100) else null, targetChapters = new,
    )

    private val state = MigrationListState(
        items = listOf(
            item(1, MigrationItemStatus.MATCHED, old = 10, new = 12),
            item(2, MigrationItemStatus.MATCHED, old = 10, new = 8),
            item(3, MigrationItemStatus.NOT_FOUND),
            item(4, MigrationItemStatus.SEARCHING),
        ),
    )

    @Test
    fun `counts per filter`() {
        assertEquals(4, state.count(MigrationFilter.ALL))
        assertEquals(2, state.count(MigrationFilter.MATCHED))
        assertEquals(1, state.count(MigrationFilter.NOT_FOUND))
        assertEquals(1, state.count(MigrationFilter.FEWER_CHAPTERS))
    }

    @Test
    fun `visible items follow the selected filter`() {
        assertEquals(listOf(2L), state.copy(filter = MigrationFilter.FEWER_CHAPTERS).visibleItems.map { it.origin.id })
    }

    @Test
    fun `ready count is matched entries and progress counts settled entries`() {
        assertEquals(2, state.readyCount)
        assertEquals(3, state.settledCount)
    }

    @Test
    fun `chapter delta is new minus old`() {
        assertEquals(2, state.items[0].chapterDelta)
        assertEquals(-2, state.items[1].chapterDelta)
        assertEquals(null, state.items[2].chapterDelta)
    }

    @Test
    fun `update item replaces by origin id`() {
        val updated = state.updateItem(3) { it.copy(status = MigrationItemStatus.SEARCHING) }
        assertEquals(MigrationItemStatus.SEARCHING, updated.items[2].status)
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.migration.MigrationListReducerTest" --no-daemon`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.migration.ui.list

import org.skepsun.kototoro.migration.domain.MatchCandidate
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.parsers.model.Content

enum class MigrationItemStatus { WAITING, SEARCHING, MATCHED, NOT_FOUND, MIGRATING, FAILED }

enum class MigrationFilter { ALL, MATCHED, NOT_FOUND, FEWER_CHAPTERS }

data class MigrationItemState(
    val origin: Content,
    val originChapters: Int,
    val status: MigrationItemStatus = MigrationItemStatus.WAITING,
    val target: Content? = null,
    val targetChapters: Int? = null,
    val candidates: List<MatchCandidate> = emptyList(),
    val sourceErrors: List<String> = emptyList(),
    val failure: String? = null,
) {
    val chapterDelta: Int?
        get() = if (target != null && targetChapters != null) targetChapters - originChapters else null

    val otherCandidatesCount: Int
        get() = candidates.count { it.content.id != target?.id }
}

sealed interface MigrationDialog {
    data class Confirm(val mode: MigrationMode, val readyCount: Int, val skippedCount: Int) : MigrationDialog
    data class Progress(val done: Int, val total: Int) : MigrationDialog
    data class Result(val succeeded: Int, val failed: Int) : MigrationDialog
    data object Exit : MigrationDialog
}

data class MigrationListState(
    val items: List<MigrationItemState> = emptyList(),
    val filter: MigrationFilter = MigrationFilter.ALL,
    val dialog: MigrationDialog? = null,
    val isLoading: Boolean = true,
) {
    val visibleItems: List<MigrationItemState>
        get() = items.filter { it.matches(filter) }

    val readyCount: Int
        get() = items.count { it.status == MigrationItemStatus.MATCHED }

    val settledCount: Int
        get() = items.count { it.status != MigrationItemStatus.WAITING && it.status != MigrationItemStatus.SEARCHING }

    val isMatching: Boolean
        get() = items.any { it.status == MigrationItemStatus.WAITING || it.status == MigrationItemStatus.SEARCHING }

    fun count(filter: MigrationFilter): Int = items.count { it.matches(filter) }

    fun updateItem(originId: Long, transform: (MigrationItemState) -> MigrationItemState): MigrationListState =
        copy(items = items.map { if (it.origin.id == originId) transform(it) else it })

    fun removeItem(originId: Long): MigrationListState = copy(items = items.filterNot { it.origin.id == originId })

    private fun MigrationItemState.matches(filter: MigrationFilter): Boolean = when (filter) {
        MigrationFilter.ALL -> true
        MigrationFilter.MATCHED -> status == MigrationItemStatus.MATCHED
        MigrationFilter.NOT_FOUND -> status == MigrationItemStatus.NOT_FOUND
        MigrationFilter.FEWER_CHAPTERS -> status == MigrationItemStatus.MATCHED && (chapterDelta ?: 0) < 0
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/ui/list/MigrationListState.kt app/src/test/kotlin/org/skepsun/kototoro/migration/MigrationListReducerTest.kt
git commit -m "feat(migration): add migration list state and reducer"
```

---

### Task 14: `MigrationListViewModel`

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/list/MigrationListViewModel.kt`

- [ ] **Step 1: 实现**

```kotlin
package org.skepsun.kototoro.migration.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.skepsun.kototoro.alternatives.domain.MigrateUseCase
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.migration.domain.MatchCandidate
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.migration.domain.SmartMatchEngine
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.SearchV2Helper
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class MigrationListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: MangaDatabase,
    private val contentDataRepository: ContentDataRepository,
    private val repositoryFactory: ContentRepository.Factory,
    private val searchHelperFactory: SearchV2Helper.Factory,
    private val migrateUseCase: MigrateUseCase,
    private val settings: MigrationSettings,
) : BaseViewModel() {

    private val ids: LongArray = savedStateHandle.get<LongArray>(MigrationListActivity.EXTRA_IDS) ?: LongArray(0)

    private val _state = MutableStateFlow(MigrationListState())
    val state: StateFlow<MigrationListState> = _state

    val onFinished = MutableEventFlow<Unit>()

    private val itemJobs = ConcurrentHashMap<Long, Job>()
    private val sourcePermits = ConcurrentHashMap<String, Semaphore>()
    private val itemPermits = Semaphore(MAX_PARALLEL_ITEMS)
    private var migrateJob: Job? = null

    private val engine = SmartMatchEngine(
        search = { source, query ->
            searchHelperFactory.create(source)(query, SearchKind.TITLE)?.manga.orEmpty()
        },
        fetchDetails = { content -> repositoryFactory.create(content.source).getDetails(content) },
        withSourcePermit = { source, block ->
            sourcePermits.getOrPut(source.name) { Semaphore(MAX_PER_SOURCE) }.withPermit { block() }
        },
    )

    init {
        launchJob(Dispatchers.Default) {
            val chaptersDao = database.getMigrationDao()
            val items = ids.toList().mapNotNull { id ->
                val content = contentDataRepository.findContentById(id, withChapters = false) ?: return@mapNotNull null
                MigrationItemState(origin = content, originChapters = chaptersDao.countChapters(id))
            }
            _state.update { it.copy(items = items, isLoading = false) }
            if (items.isEmpty()) onFinished.call(Unit)
            items.forEach { startMatching(it.origin) }
        }
    }

    fun setFilter(filter: MigrationFilter) = _state.update { it.copy(filter = filter) }

    fun skip(originId: Long) {
        itemJobs.remove(originId)?.cancel()
        _state.update { it.removeItem(originId) }
        finishIfEmpty()
    }

    fun selectCandidate(originId: Long, candidate: MatchCandidate) {
        itemJobs.remove(originId)?.cancel()
        _state.update { s -> s.updateItem(originId) { it.copy(status = MigrationItemStatus.SEARCHING) } }
        itemJobs[originId] = launchJob(Dispatchers.Default) {
            val details = runCatchingCancellable {
                val content = candidate.content
                if (content.chapters.isNullOrEmpty()) repositoryFactory.create(content.source).getDetails(content) else content
            }.getOrNull()
            _state.update { s ->
                s.updateItem(originId) {
                    if (details == null) {
                        it.copy(status = MigrationItemStatus.NOT_FOUND, target = null, targetChapters = null)
                    } else {
                        it.copy(status = MigrationItemStatus.MATCHED, target = details, targetChapters = details.chaptersCount())
                    }
                }
            }
        }
    }

    /** Manual search across the target sources; replaces the item's candidate list. */
    fun manualSearch(originId: Long, query: String) {
        val item = _state.value.items.firstOrNull { it.origin.id == originId } ?: return
        itemJobs.remove(originId)?.cancel()
        _state.update { s -> s.updateItem(originId) { it.copy(status = MigrationItemStatus.SEARCHING, candidates = emptyList()) } }
        itemJobs[originId] = launchJob(Dispatchers.Default) {
            val outcomes = targetSources(item.origin).map { engine.searchSource(item.origin, it, query) }
            val candidates = outcomes.flatMap { it.candidates }.sortedByDescending { it.score }
            _state.update { s ->
                s.updateItem(originId) {
                    it.copy(
                        status = if (it.target != null) MigrationItemStatus.MATCHED else MigrationItemStatus.NOT_FOUND,
                        candidates = candidates,
                    )
                }
            }
        }
    }

    fun requestMigrate(mode: MigrationMode) {
        val s = _state.value
        _state.update {
            it.copy(
                dialog = MigrationDialog.Confirm(
                    mode = mode,
                    readyCount = s.readyCount,
                    skippedCount = s.items.count { item -> item.status == MigrationItemStatus.NOT_FOUND },
                ),
            )
        }
    }

    fun migrateNow(originId: Long, mode: MigrationMode = MigrationMode.REPLACE) {
        launchJob(Dispatchers.Default) { migrateItems(listOf(originId), mode, showProgress = false) }
    }

    fun confirmMigrate(mode: MigrationMode) {
        if (migrateJob?.isActive == true) return
        val ready = _state.value.items.filter { it.status == MigrationItemStatus.MATCHED }.map { it.origin.id }
        migrateJob = launchJob(Dispatchers.Default) { migrateItems(ready, mode, showProgress = true) }
    }

    fun cancelMigrate() {
        migrateJob?.cancel()
        _state.update { it.copy(dialog = null) }
    }

    fun requestExit(): Boolean {
        if (!_state.value.isMatching) return false
        _state.update { it.copy(dialog = MigrationDialog.Exit) }
        return true
    }

    fun dismissDialog() {
        val wasResult = _state.value.dialog is MigrationDialog.Result
        _state.update { it.copy(dialog = null) }
        if (wasResult) finishIfEmpty()
    }

    private suspend fun migrateItems(originIds: List<Long>, mode: MigrationMode, showProgress: Boolean) {
        val flags = settings.dataFlags
        var succeeded = 0
        var failed = 0
        originIds.forEachIndexed { index, originId ->
            if (showProgress) _state.update { it.copy(dialog = MigrationDialog.Progress(index, originIds.size)) }
            val item = _state.value.items.firstOrNull { it.origin.id == originId } ?: return@forEachIndexed
            val target = item.target ?: return@forEachIndexed
            _state.update { s -> s.updateItem(originId) { it.copy(status = MigrationItemStatus.MIGRATING) } }
            runCatchingCancellable {
                migrateUseCase(item.origin, target, mode, flags)
            }.onSuccess {
                succeeded++
                _state.update { it.removeItem(originId) }
            }.onFailure { e ->
                failed++
                _state.update { s ->
                    s.updateItem(originId) {
                        it.copy(status = MigrationItemStatus.FAILED, failure = e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
        if (showProgress) {
            _state.update { it.copy(dialog = MigrationDialog.Result(succeeded, failed)) }
        } else {
            finishIfEmpty()
        }
    }

    private fun startMatching(origin: Content) {
        itemJobs[origin.id] = viewModelScope.launch(Dispatchers.Default) {
            itemPermits.withPermit {
                _state.update { s -> s.updateItem(origin.id) { it.copy(status = MigrationItemStatus.SEARCHING) } }
                val result = runCatchingCancellable {
                    engine.match(origin, targetSources(origin), settings.matchMode, settings.extraQuery, settings.isDeepSearch)
                }.getOrNull()
                val best = result?.best
                _state.update { s ->
                    s.updateItem(origin.id) {
                        it.copy(
                            status = if (best != null) MigrationItemStatus.MATCHED else MigrationItemStatus.NOT_FOUND,
                            target = best,
                            targetChapters = best?.chaptersCount(),
                            candidates = result?.candidates.orEmpty(),
                            sourceErrors = result?.errors?.map { (name, e) -> "$name: ${e.message}" }.orEmpty(),
                        )
                    }
                }
                applyHideRules(origin.id)
            }
        }
    }

    private fun applyHideRules(originId: Long) {
        val item = _state.value.items.firstOrNull { it.origin.id == originId } ?: return
        val hide = (item.status == MigrationItemStatus.NOT_FOUND && settings.hideUnmatched) ||
            (item.status == MigrationItemStatus.MATCHED && settings.hideWithoutUpdates && (item.chapterDelta ?: 0) <= 0)
        if (hide) {
            _state.update { it.removeItem(originId) }
            finishIfEmpty()
        }
    }

    private fun targetSources(origin: Content): List<ContentSource> {
        val family = origin.source.getContentType().contentFamily()
        return settings.getTargetSourceNames(family).orEmpty()
            .filter { it != origin.source.name }
            .map { ContentSource(it) }
    }

    private fun finishIfEmpty() {
        if (_state.value.items.isEmpty()) onFinished.call(Unit)
    }

    private companion object {
        const val MAX_PARALLEL_ITEMS = 4
        const val MAX_PER_SOURCE = 2
    }
}
```

注意：`ContentSource` 同时是 `core.model` 中的工厂函数和 `parsers.model` 中的接口，这里两者都要 import，Kotlin 能够区分。

- [ ] **Step 2: 编译**

这一步会引用 `MigrationListActivity.EXTRA_IDS`，所以先放一个最小的 Activity 桩（Task 15 会补全）：

```kotlin
package org.skepsun.kototoro.migration.ui.list

import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class MigrationListActivity : BaseComposeActivity() {
    companion object {
        const val EXTRA_IDS = "ids"
    }
}
```

Run: `./gradlew :app:compileDebugKotlin --no-daemon` → BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/ui/list
git commit -m "feat(migration): add migration list view model"
```

---

### Task 15: 迁移列表 UI（行布局 B + 候选面板）

**Files:**
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/list/MigrationListActivity.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/list/MigrationListScreen.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/list/MigrationCandidatesSheet.kt`
- Modify: `app/src/main/AndroidManifest.xml`（在 `StatsActivity` 声明之后）

- [ ] **Step 1: Activity**

```kotlin
package org.skepsun.kototoro.migration.ui.list

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class MigrationListActivity : BaseComposeActivity() {

    private val viewModel: MigrationListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.requestExit()) finish()
        }
        setComposeContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel.onFinished) {
                viewModel.onFinished.collect { event -> event?.consume { finish() } }
            }
            MigrationListScreen(
                state = state,
                onNavigateUp = { if (!viewModel.requestExit()) finish() },
                onFilter = viewModel::setFilter,
                onSkip = viewModel::skip,
                onSelectCandidate = viewModel::selectCandidate,
                onManualSearch = viewModel::manualSearch,
                onMigrateNow = { viewModel.migrateNow(it) },
                onOpenOriginal = { router.openDetails(it) },
                onRequestMigrate = viewModel::requestMigrate,
                onConfirmMigrate = viewModel::confirmMigrate,
                onCancelMigrate = viewModel::cancelMigrate,
                onDismissDialog = viewModel::dismissDialog,
                onAbandon = ::finish,
            )
        }
    }

    companion object {
        const val EXTRA_IDS = "ids"

        fun newIntent(context: Context, ids: LongArray): Intent =
            Intent(context, MigrationListActivity::class.java).putExtra(EXTRA_IDS, ids)
    }
}
```

`router` 是 `core/nav/NavUtil.kt` 中定义的 `FragmentActivity` 扩展属性（`StatsActivity` 也是这样用的）。

- [ ] **Step 2: Screen**

```kotlin
package org.skepsun.kototoro.migration.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.migration.domain.MatchCandidate
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold

@Composable
fun MigrationListScreen(
    state: MigrationListState,
    onNavigateUp: () -> Unit,
    onFilter: (MigrationFilter) -> Unit,
    onSkip: (Long) -> Unit,
    onSelectCandidate: (Long, MatchCandidate) -> Unit,
    onManualSearch: (Long, String) -> Unit,
    onMigrateNow: (Long) -> Unit,
    onOpenOriginal: (Content) -> Unit,
    onRequestMigrate: (MigrationMode) -> Unit,
    onConfirmMigrate: (MigrationMode) -> Unit,
    onCancelMigrate: () -> Unit,
    onDismissDialog: () -> Unit,
    onAbandon: () -> Unit,
) {
    var sheetItemId by remember { mutableStateOf<Long?>(null) }
    val title = if (state.isMatching) {
        stringResource(R.string.migration_title_progress, state.settledCount, state.items.size)
    } else {
        stringResource(R.string.migration_title)
    }
    SettingsTopBarScaffold(title = title, onNavigateUp = onNavigateUp) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.isMatching && state.items.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { state.settledCount.toFloat() / state.items.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FilterRow(state, onFilter)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(state.visibleItems, key = { it.origin.id }) { item ->
                    MigrationRow(
                        item = item,
                        onClick = { sheetItemId = item.origin.id },
                        onSkip = { onSkip(item.origin.id) },
                        onMigrateNow = { onMigrateNow(item.origin.id) },
                        onOpenOriginal = { onOpenOriginal(item.origin) },
                    )
                    HorizontalDivider()
                }
            }
            BottomBar(state.readyCount, onRequestMigrate)
        }
    }

    sheetItemId?.let { id ->
        val item = state.items.firstOrNull { it.origin.id == id }
        if (item == null) {
            sheetItemId = null
        } else {
            MigrationCandidatesSheet(
                item = item,
                onSelect = { candidate ->
                    onSelectCandidate(id, candidate)
                    sheetItemId = null
                },
                onSearch = { query -> onManualSearch(id, query) },
                onDismiss = { sheetItemId = null },
            )
        }
    }

    MigrationDialogs(state.dialog, onConfirmMigrate, onCancelMigrate, onDismissDialog, onAbandon)
}

@Composable
private fun FilterRow(state: MigrationListState, onFilter: (MigrationFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(MigrationFilter.entries) { filter ->
            val label = when (filter) {
                MigrationFilter.ALL -> R.string.migration_filter_all
                MigrationFilter.MATCHED -> R.string.migration_filter_matched
                MigrationFilter.NOT_FOUND -> R.string.migration_filter_not_found
                MigrationFilter.FEWER_CHAPTERS -> R.string.migration_filter_fewer
            }
            FilterChip(
                selected = state.filter == filter,
                onClick = { onFilter(filter) },
                label = { Text(stringResource(label, state.count(filter))) },
            )
        }
    }
}

@Composable
private fun MigrationRow(
    item: MigrationItemState,
    onClick: () -> Unit,
    onSkip: () -> Unit,
    onMigrateNow: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    val context = LocalContext.current
    val matched = item.target != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = (item.target ?: item.origin).coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (matched) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(13f / 18f)
                .clip(RoundedCornerShape(8.dp))
                .alpha(if (matched) 1f else 0.5f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val newTitle = item.target?.title?.takeIf { it != item.origin.title }
            Text(
                text = if (newTitle != null) "${item.origin.title} → $newTitle" else item.origin.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.migration_chapters, item.origin.source.getTitle(context), item.originChapters) + " → ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                StatusText(item)
            }
            val hint = when {
                item.status == MigrationItemStatus.NOT_FOUND -> stringResource(R.string.migration_tap_to_search)
                item.otherCandidatesCount > 0 -> stringResource(R.string.migration_more_candidates, item.otherCandidatesCount)
                else -> null
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        RowMenu(item, onSkip, onMigrateNow, onOpenOriginal)
    }
}

@Composable
private fun StatusText(item: MigrationItemState) {
    val context = LocalContext.current
    when (item.status) {
        MigrationItemStatus.WAITING -> SecondaryText(stringResource(R.string.migration_status_waiting))
        MigrationItemStatus.SEARCHING, MigrationItemStatus.MIGRATING -> {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(4.dp))
            SecondaryText(stringResource(R.string.migration_status_searching))
        }
        MigrationItemStatus.NOT_FOUND -> Text(
            stringResource(R.string.migration_status_not_found),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        MigrationItemStatus.FAILED -> Text(
            stringResource(R.string.migration_status_failed, item.failure.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MigrationItemStatus.MATCHED -> {
            val target = item.target ?: return
            Text(
                stringResource(R.string.migration_chapters, target.source.getTitle(context), item.targetChapters ?: 0),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            item.chapterDelta?.let { DeltaBadge(it) }
        }
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeltaBadge(delta: Int) {
    val (bg, fg) = when {
        delta > 0 -> Color(0xFFDFF5E6) to Color(0xFF1D7A3E)
        delta < 0 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(start = 4.dp)) {
        Text(
            text = if (delta > 0) "+$delta" else if (delta < 0) "−${-delta}" else "±0",
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun RowMenu(item: MigrationItemState, onSkip: () -> Unit, onMigrateNow: () -> Unit, onOpenOriginal: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.migration_action_skip)) },
                onClick = { expanded = false; onSkip() },
            )
            if (item.status == MigrationItemStatus.MATCHED) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.migration_action_migrate_now)) },
                    onClick = { expanded = false; onMigrateNow() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.migration_action_open)) },
                onClick = { expanded = false; onOpenOriginal() },
            )
        }
    }
}

@Composable
private fun BottomBar(readyCount: Int, onRequestMigrate: (MigrationMode) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onRequestMigrate(MigrationMode.COPY) },
                enabled = readyCount > 0,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.migration_copy_n, readyCount)) }
            Button(
                onClick = { onRequestMigrate(MigrationMode.REPLACE) },
                enabled = readyCount > 0,
                modifier = Modifier.weight(1.4f),
            ) { Text(stringResource(R.string.migration_migrate_n, readyCount)) }
        }
    }
}

@Composable
private fun MigrationDialogs(
    dialog: MigrationDialog?,
    onConfirmMigrate: (MigrationMode) -> Unit,
    onCancelMigrate: () -> Unit,
    onDismissDialog: () -> Unit,
    onAbandon: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is MigrationDialog.Confirm -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.migration_confirm_title, dialog.readyCount)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (dialog.skippedCount > 0) Text(stringResource(R.string.migration_confirm_skipped, dialog.skippedCount))
                    Text(
                        stringResource(
                            if (dialog.mode == MigrationMode.REPLACE) R.string.migration_confirm_replace else R.string.migration_confirm_copy,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirmMigrate(dialog.mode) }) {
                    Text(
                        stringResource(
                            if (dialog.mode == MigrationMode.REPLACE) R.string.migration_migrate_n else R.string.migration_copy_n,
                            dialog.readyCount,
                        ),
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text(stringResource(android.R.string.cancel)) } },
        )
        is MigrationDialog.Progress -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.migration_progress)) },
            text = {
                LinearProgressIndicator(
                    progress = { if (dialog.total == 0) 0f else dialog.done.toFloat() / dialog.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelMigrate) { Text(stringResource(android.R.string.cancel)) } },
        )
        is MigrationDialog.Result -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.migration_result, dialog.succeeded, dialog.failed)) },
            confirmButton = { TextButton(onClick = onDismissDialog) { Text(stringResource(android.R.string.ok)) } },
        )
        MigrationDialog.Exit -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.migration_exit_title)) },
            text = { Text(stringResource(R.string.migration_exit_message)) },
            confirmButton = { TextButton(onClick = onAbandon) { Text(stringResource(R.string.migration_abandon)) } },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
```

- [ ] **Step 3: 候选面板**

```kotlin
package org.skepsun.kototoro.migration.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.migration.domain.MatchCandidate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationCandidatesSheet(
    item: MigrationItemState,
    onSelect: (MatchCandidate) -> Unit,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember(item.origin.id) { mutableStateOf(item.origin.title) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(0.85f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.migration_candidates_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.migration_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        LazyColumn {
            items(item.candidates, key = { it.content.id }) { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(candidate) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = candidate.content.id == item.target?.id, onClick = { onSelect(candidate) })
                    AsyncImage(
                        model = candidate.content.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(40.dp).aspectRatio(13f / 18f).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(candidate.content.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${candidate.content.source.getTitle(context)} · ${(candidate.score * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 注册 Activity**

在 `AndroidManifest.xml` 的 `StatsActivity` 声明后面加入：

```xml
		<activity
			android:name="org.skepsun.kototoro.migration.ui.list.MigrationListActivity"
			android:label="@string/migration_title" />
```

- [ ] **Step 5: 编译并提交**

Run: `./gradlew :app:compileDebugKotlin --no-daemon` → BUILD SUCCESSFUL。

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/ui/list app/src/main/AndroidManifest.xml
git commit -m "feat(migration): add migration list screen with candidate sheet"
```

---

### Task 16: 设置面板与来源选择

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/config/MigrationConfigViewModel.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/config/MigrationConfigSheet.kt`

- [ ] **Step 1: ViewModel**

```kotlin
package org.skepsun.kototoro.migration.ui.config

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.migration.domain.MatchMode
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

data class FamilySources(
    val family: ContentTypeFamily,
    val available: List<ContentSource>,
    val pinned: Set<String>,
    val selected: List<String>,
)

data class MigrationConfigState(
    val count: Int = 0,
    val originSourceNames: List<String> = emptyList(),
    val families: List<FamilySources> = emptyList(),
    val flags: Set<MigrationDataFlag> = MigrationDataFlag.ALL,
    val matchMode: MatchMode = MatchMode.FIRST_HIT,
    val extraQuery: String = "",
    val deepSearch: Boolean = false,
    val hideUnmatched: Boolean = false,
    val hideWithoutUpdates: Boolean = false,
    val duplicateCheck: Boolean = true,
    val isLoading: Boolean = true,
) {
    val canStart: Boolean get() = !isLoading && families.all { it.selected.isNotEmpty() }
}

@HiltViewModel
class MigrationConfigViewModel @Inject constructor(
    private val contentDataRepository: ContentDataRepository,
    private val sourcesRepository: ContentSourcesRepository,
    private val settings: MigrationSettings,
) : BaseViewModel() {

    private val _state = MutableStateFlow(MigrationConfigState())
    val state: StateFlow<MigrationConfigState> = _state

    private var loadedIds: LongArray? = null

    fun load(ids: LongArray) {
        if (loadedIds?.contentEquals(ids) == true) return
        loadedIds = ids
        launchJob(Dispatchers.Default) {
            val contents = ids.toList().mapNotNull { contentDataRepository.findContentById(it, withChapters = false) }
            val originNames = contents.mapTo(LinkedHashSet()) { it.source.name }
            val enabled = sourcesRepository.getEnabledSources()
            val pinned = sourcesRepository.getPinnedSources().mapTo(HashSet()) { it.name }
            val families = contents.map { it.source.getContentType().contentFamily() }.distinct().map { family ->
                val available = enabled
                    .filter { it.getContentType().contentFamily() == family && it.name !in originNames }
                    .sortedByDescending { it.name in pinned }
                val availableNames = available.mapTo(HashSet()) { it.name }
                val saved = settings.getTargetSourceNames(family)?.filter { it in availableNames }
                val selected = saved?.takeIf { it.isNotEmpty() } ?: available.map { it.name }
                FamilySources(family, available, pinned, selected)
            }
            _state.value = MigrationConfigState(
                count = contents.size,
                originSourceNames = originNames.toList(),
                families = families,
                flags = settings.dataFlags,
                matchMode = settings.matchMode,
                extraQuery = settings.extraQuery,
                deepSearch = settings.isDeepSearch,
                hideUnmatched = settings.hideUnmatched,
                hideWithoutUpdates = settings.hideWithoutUpdates,
                duplicateCheck = settings.isDuplicateCheckEnabled,
                isLoading = false,
            )
        }
    }

    /** Tick order is search order: ticking appends, unticking removes. */
    fun toggleSource(family: ContentTypeFamily, name: String) = updateFamily(family) {
        it.copy(selected = if (name in it.selected) it.selected - name else it.selected + name)
    }

    fun selectPreset(family: ContentTypeFamily, preset: SourcePreset) = updateFamily(family) { f ->
        f.copy(
            selected = when (preset) {
                SourcePreset.ALL, SourcePreset.ENABLED -> f.available.map { it.name }
                SourcePreset.PINNED -> f.available.filter { it.name in f.pinned }.map { it.name }
                SourcePreset.NONE -> emptyList()
            },
        )
    }

    fun toggleFlag(flag: MigrationDataFlag) {
        if (flag == MigrationDataFlag.CATEGORIES) return
        _state.update { it.copy(flags = if (flag in it.flags) it.flags - flag else it.flags + flag) }
    }

    fun setMatchMode(mode: MatchMode) = _state.update { it.copy(matchMode = mode) }
    fun setExtraQuery(value: String) = _state.update { it.copy(extraQuery = value) }
    fun setDeepSearch(value: Boolean) = _state.update { it.copy(deepSearch = value) }
    fun setHideUnmatched(value: Boolean) = _state.update { it.copy(hideUnmatched = value) }
    fun setHideWithoutUpdates(value: Boolean) = _state.update { it.copy(hideWithoutUpdates = value) }
    fun setDuplicateCheck(value: Boolean) = _state.update { it.copy(duplicateCheck = value) }

    /** Persists the options; the list screen reads them from [MigrationSettings]. */
    fun save() {
        val s = _state.value
        s.families.forEach { settings.setTargetSourceNames(it.family, it.selected) }
        settings.dataFlags = s.flags
        settings.matchMode = s.matchMode
        settings.extraQuery = s.extraQuery
        settings.isDeepSearch = s.deepSearch
        settings.hideUnmatched = s.hideUnmatched
        settings.hideWithoutUpdates = s.hideWithoutUpdates
        settings.isDuplicateCheckEnabled = s.duplicateCheck
    }

    private fun updateFamily(family: ContentTypeFamily, transform: (FamilySources) -> FamilySources) =
        _state.update { s -> s.copy(families = s.families.map { if (it.family == family) transform(it) else it }) }
}

enum class SourcePreset { ALL, PINNED, ENABLED, NONE }
```

`getEnabledSources()` 返回的只有已启用的来源，所以「全选」与「仅已启用」效果相同。按钮仍然都保留，与 Mihon 一致，也符合用户预期。

- [ ] **Step 2: Sheet**

```kotlin
package org.skepsun.kototoro.migration.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.migration.domain.MatchMode
import org.skepsun.kototoro.migration.domain.MigrationDataFlag

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MigrationConfigSheet(
    ids: LongArray,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: MigrationConfigViewModel = hiltViewModel(key = "migration-config-${ids.contentHashCode()}"),
) {
    LaunchedEffect(ids) { viewModel.load(ids) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingFamily by remember { mutableStateOf<ContentTypeFamily?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val family = editingFamily?.let { f -> state.families.firstOrNull { it.family == f } }
        if (family != null) {
            SourcePicker(
                family = family,
                onToggle = { viewModel.toggleSource(family.family, it) },
                onPreset = { viewModel.selectPreset(family.family, it) },
                onDone = { editingFamily = null },
            )
        } else {
            ConfigContent(
                state = state,
                viewModel = viewModel,
                onEdit = { editingFamily = it },
                onStart = {
                    viewModel.save()
                    onStart()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigContent(
    state: MigrationConfigState,
    viewModel: MigrationConfigViewModel,
    onEdit: (ContentTypeFamily) -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    var showMore by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(stringResource(R.string.migration_config_title, state.count), style = MaterialTheme.typography.titleLarge)
        if (state.originSourceNames.isNotEmpty()) {
            Text(
                text = state.originSourceNames.joinToString("、") { ContentSource(it).getTitle(context) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.families.forEach { family ->
            SectionLabel(stringResource(R.string.migration_config_sources)) {
                TextButton(onClick = { onEdit(family.family) }) { Text(stringResource(R.string.migration_config_edit)) }
            }
            if (family.selected.isEmpty()) {
                Text(stringResource(R.string.migration_no_sources), color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                family.selected.take(MAX_VISIBLE_SOURCES).forEachIndexed { index, name ->
                    AssistChip(
                        onClick = { onEdit(family.family) },
                        label = { Text("${index + 1}  ${ContentSource(name).getTitle(context)}") },
                    )
                }
                val rest = family.selected.size - MAX_VISIBLE_SOURCES
                if (rest > 0) AssistChip(onClick = { onEdit(family.family) }, label = { Text("+$rest") })
            }
        }
        SectionLabel(stringResource(R.string.migration_config_data))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MigrationDataFlag.entries.forEach { flag ->
                FilterChip(
                    selected = flag in state.flags,
                    enabled = flag != MigrationDataFlag.CATEGORIES,
                    onClick = { viewModel.toggleFlag(flag) },
                    label = { Text(stringResource(flag.titleRes())) },
                )
            }
        }
        SectionLabel(stringResource(R.string.migration_config_match))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MatchMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.matchMode == mode,
                    onClick = { viewModel.setMatchMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, MatchMode.entries.size),
                ) {
                    Text(
                        stringResource(
                            if (mode == MatchMode.FIRST_HIT) R.string.migration_match_first_hit else R.string.migration_match_most_chapters,
                        ),
                    )
                }
            }
        }
        TextButton(onClick = { showMore = !showMore }, modifier = Modifier.padding(top = 4.dp)) {
            Text((if (showMore) "▾ " else "▸ ") + stringResource(R.string.migration_config_more))
        }
        AnimatedVisibility(showMore) {
            Column {
                OutlinedTextField(
                    value = state.extraQuery,
                    onValueChange = viewModel::setExtraQuery,
                    singleLine = true,
                    label = { Text(stringResource(R.string.migration_extra_query)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(stringResource(R.string.migration_deep_search), state.deepSearch, viewModel::setDeepSearch,
                    stringResource(R.string.migration_deep_search_summary))
                SwitchRow(stringResource(R.string.migration_hide_unmatched), state.hideUnmatched, viewModel::setHideUnmatched)
                SwitchRow(stringResource(R.string.migration_hide_without_updates), state.hideWithoutUpdates, viewModel::setHideWithoutUpdates)
                SwitchRow(stringResource(R.string.migration_duplicate_check), state.duplicateCheck, viewModel::setDuplicateCheck)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.migration_start))
        }
    }
}

@Composable
private fun SourcePicker(
    family: FamilySources,
    onToggle: (String) -> Unit,
    onPreset: (SourcePreset) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.migration_sources_picker_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.migration_sources_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDone) { Text(stringResource(android.R.string.ok)) }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourcePreset.entries.forEach { preset ->
                AssistChip(
                    onClick = { onPreset(preset) },
                    label = {
                        Text(
                            stringResource(
                                when (preset) {
                                    SourcePreset.ALL -> R.string.migration_select_all
                                    SourcePreset.PINNED -> R.string.migration_select_pinned
                                    SourcePreset.ENABLED -> R.string.migration_select_enabled
                                    SourcePreset.NONE -> R.string.migration_select_none
                                },
                            ),
                        )
                    },
                )
            }
        }
        LazyColumn {
            items(family.available, key = { it.name }) { source ->
                val order = family.selected.indexOf(source.name)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(source.name) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = order >= 0, onCheckedChange = { onToggle(source.name) })
                    ContentSourceIcon(source = source, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(source.getTitle(context), modifier = Modifier.weight(1f))
                    if (order >= 0) Badge { Text("${order + 1}") }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun MigrationDataFlag.titleRes(): Int = when (this) {
    MigrationDataFlag.CATEGORIES -> R.string.migration_flag_categories
    MigrationDataFlag.PROGRESS -> R.string.migration_flag_progress
    MigrationDataFlag.TRACKING -> R.string.migration_flag_tracking
    MigrationDataFlag.NOTES -> R.string.migration_flag_notes
    MigrationDataFlag.STATS -> R.string.migration_flag_stats
}

private const val MAX_VISIBLE_SOURCES = 3
```

- [ ] **Step 3: 编译并提交**

Run: `./gradlew :app:compileDebugKotlin --no-daemon` → BUILD SUCCESSFUL。

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/ui/config
git commit -m "feat(migration): add migration config sheet with ordered source picker"
```

---

### Task 17: `AppRouter` 入口，多选「修复」改为「换源」

**Files:**
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt`（在 `openAlternatives` 之后）
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/KototoroSelectionTopBar.kt`
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/AppContentListRoute.kt`

- [ ] **Step 1: AppRouter**

在 `fun openAlternatives(manga: Content)` 之后加入：

```kotlin
    /** Opens the migration config sheet; starting it launches the migration list. */
    fun openMigration(ids: LongArray) {
        if (ids.isEmpty()) return
        val composeActivity = activity as? BaseComposeActivity ?: return
        val key = "migration-config"
        composeActivity.showComposeModal(key) {
            org.skepsun.kototoro.migration.ui.config.MigrationConfigSheet(
                ids = ids,
                onStart = {
                    composeActivity.dismissComposeModal(key)
                    startActivity(
                        org.skepsun.kototoro.migration.ui.list.MigrationListActivity.newIntent(composeActivity, ids),
                    )
                },
                onDismiss = { composeActivity.dismissComposeModal(key) },
            )
        }
    }

    fun openMigrationSources() =
        startActivity(org.skepsun.kototoro.migration.ui.sources.MigrationSourcesActivity::class.java)
```

`MigrationSourcesActivity` 在 Task 18 创建，所以本任务的编译放到 Task 18 结束时进行。

- [ ] **Step 2: SelectionAction 改名**

在 `KototoroSelectionTopBar.kt` 中：
- 枚举 `FIX,` 改为 `MIGRATE,`
- 第 107-108 行的 `SelectionAction.FIX` 全部改为 `SelectionAction.MIGRATE`
- 第 323 行 `SelectionAction.FIX -> Unit` 改为 `SelectionAction.MIGRATE -> Unit`
- 第 372 行改为 `SelectionAction.MIGRATE -> stringResource(fixActionTitleRes ?: R.string.migrate)`

参数名 `fixActionTitleRes` 保持不变，它也被 `TopBarOverrideState` 使用，没有调用方传入非 null 值，不值得扩大改动面。

- [ ] **Step 3: AppContentListRoute**

把 370-377 行的 `SelectionAction.FIX -> { ... }` 分支替换为：

```kotlin
                                SelectionAction.MIGRATE -> {
                                    appRouter.openMigration(currentSelectionIds.toLongArray())
                                    updateSelection(emptySet())
                                }
```

删除 `onFixSelection` 参数（第 112 行）、`var pendingFixIds`（第 187 行），以及 412 行起的 `pendingFixIds?.let { ... AlertDialog ... }` 整块，再删掉由此变成未使用的 `AutoFixService` import（第 29 行）。先确认没有调用方传入 `onFixSelection`：

Run: `grep -rn "onFixSelection" app/src/main --include=*.kt`
Expected: 修改后无结果。

- [ ] **Step 4: 提交（编译在 Task 18 结束时进行）**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose
git commit -m "feat(migration): replace fix selection action with migrate"
```

---

### Task 18: 按来源换源页

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/sources/MigrationSourcesViewModel.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/sources/MigrationSourcesActivity.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/sources/MigrationSourcesScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: ViewModel**

```kotlin
package org.skepsun.kototoro.migration.ui.sources

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.domain.SourceHealth
import org.skepsun.kototoro.migration.domain.SourceHealthUseCase
import javax.inject.Inject

data class MigrationSourcesState(
    val sources: List<SourceHealth> = emptyList(),
    val rowsById: Map<Long, LibraryRow> = emptyMap(),
    val isLoading: Boolean = true,
) {
    val attention: List<SourceHealth> get() = sources.filter { it.status.needsAttention }
    val healthy: List<SourceHealth> get() = sources.filterNot { it.status.needsAttention }
    val attentionIds: LongArray get() = attention.flatMap { it.contentIds }.toLongArray()
}

@HiltViewModel
class MigrationSourcesViewModel @Inject constructor(
    private val sourceHealthUseCase: SourceHealthUseCase,
    private val database: MangaDatabase,
) : BaseViewModel() {

    private val _state = MutableStateFlow(MigrationSourcesState())
    val state: StateFlow<MigrationSourcesState> = _state

    init {
        refresh()
    }

    fun refresh() {
        launchLoadingJob(Dispatchers.Default) {
            val rows = database.getMigrationDao().findLibraryRows().associateBy { it.id }
            _state.value = MigrationSourcesState(sourceHealthUseCase(), rows, isLoading = false)
        }
    }
}
```

- [ ] **Step 2: Activity**

```kotlin
package org.skepsun.kototoro.migration.ui.sources

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class MigrationSourcesActivity : BaseComposeActivity() {

    private val viewModel: MigrationSourcesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            MigrationSourcesScreen(
                state = state,
                onNavigateUp = ::finish,
                onMigrate = { ids -> router.openMigration(ids) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
```

- [ ] **Step 3: Screen**

```kotlin
package org.skepsun.kototoro.migration.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.migration.domain.SourceHealth
import org.skepsun.kototoro.migration.domain.SourceHealthStatus
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold

@Composable
fun MigrationSourcesScreen(
    state: MigrationSourcesState,
    onNavigateUp: () -> Unit,
    onMigrate: (LongArray) -> Unit,
) {
    var picking by remember { mutableStateOf<SourceHealth?>(null) }
    SettingsTopBarScaffold(title = stringResource(R.string.migration_sources_title), onNavigateUp = onNavigateUp) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@SettingsTopBarScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.attention.isNotEmpty()) {
                item {
                    AttentionBanner(
                        sourceCount = state.attention.size,
                        contentCount = state.attentionIds.size,
                        onMigrate = { onMigrate(state.attentionIds) },
                    )
                }
                item { GroupHeader(stringResource(R.string.migration_health_needs_attention)) }
                items(state.attention, key = { it.source.name }) { SourceRow(it) { picking = it } }
            }
            if (state.healthy.isNotEmpty()) {
                item { GroupHeader(stringResource(R.string.migration_health_ok)) }
                items(state.healthy, key = { it.source.name }) { SourceRow(it) { picking = it } }
            }
        }
    }
    picking?.let { source ->
        SourceContentPicker(
            source = source,
            state = state,
            onNext = { ids ->
                picking = null
                onMigrate(ids)
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun AttentionBanner(sourceCount: Int, contentCount: Int, onMigrate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.migration_health_banner, sourceCount, contentCount),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onMigrate,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.migration_health_banner_action, contentCount)) }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourceRow(health: SourceHealth, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContentSourceIcon(source = health.source, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(health.source.getTitle(context), fontWeight = FontWeight.SemiBold)
                StatusTag(health.status)
            }
            statusSummary(health)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("${health.favouriteCount}", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusTag(status: SourceHealthStatus) {
    val (label, bg, fg) = when (status) {
        SourceHealthStatus.UNINSTALLED -> Triple(R.string.migration_health_uninstalled, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        SourceHealthStatus.BROKEN -> Triple(R.string.migration_health_broken, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        SourceHealthStatus.FAILING -> Triple(R.string.migration_health_failing, Color(0xFFFFF1D6), Color(0xFF8A5A00))
        SourceHealthStatus.DISABLED -> Triple(R.string.migration_health_disabled, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        SourceHealthStatus.HEALTHY -> return
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(start = 6.dp)) {
        Text(
            stringResource(label),
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun statusSummary(health: SourceHealth): String? = when (health.status) {
    SourceHealthStatus.UNINSTALLED -> stringResource(R.string.migration_health_uninstalled_summary)
    SourceHealthStatus.BROKEN -> stringResource(R.string.migration_health_broken_summary)
    SourceHealthStatus.FAILING -> health.errorSummary
    SourceHealthStatus.DISABLED -> stringResource(R.string.migration_health_disabled_summary)
    SourceHealthStatus.HEALTHY -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceContentPicker(
    source: SourceHealth,
    state: MigrationSourcesState,
    onNext: (LongArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(source.source.name) { mutableStateOf(source.contentIds.toSet()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(0.85f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(source.source.getTitle(context), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Checkbox(
                checked = selected.size == source.contentIds.size,
                onCheckedChange = { selected = if (it) source.contentIds.toSet() else emptySet() },
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(source.contentIds, key = { it }) { id ->
                val row = state.rowsById[id] ?: return@items
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = if (id in selected) selected - id else selected + id }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id })
                    Text(row.title, modifier = Modifier.weight(1f))
                    Text("${row.chaptersCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(
            onClick = { onNext(selected.toLongArray()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text(stringResource(R.string.migration_next)) }
    }
}
```

- [ ] **Step 4: 注册 Activity**

```xml
		<activity
			android:name="org.skepsun.kototoro.migration.ui.sources.MigrationSourcesActivity"
			android:label="@string/migration_sources_title" />
```

- [ ] **Step 5: 编译（同时验证 Task 17）并提交**

Run: `./gradlew :app:compileDebugKotlin --no-daemon` → BUILD SUCCESSFUL。

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/ui/sources app/src/main/AndroidManifest.xml
git commit -m "feat(migration): add migrate-by-source screen with health tags"
```

---

### Task 19: 收藏页菜单与失效源浮动提示卡

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/health/SourceHealthBannerViewModel.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/health/SourceHealthBanner.kt`
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/main/ui/compose/MainShellScene.kt:1907-1912`
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/compose/FavoritesHostScreen.kt`（外层 `Box(modifier = Modifier.fillMaxSize())` 内部的末尾，大约在第 337 行附近）

- [ ] **Step 1: ViewModel**

```kotlin
package org.skepsun.kototoro.migration.ui.health

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.migration.domain.SourceHealthUseCase
import javax.inject.Inject

data class HealthBannerState(val sourceCount: Int = 0, val key: String? = null)

@HiltViewModel
class SourceHealthBannerViewModel @Inject constructor(
    private val sourceHealthUseCase: SourceHealthUseCase,
    private val settings: MigrationSettings,
) : BaseViewModel() {

    private val _state = MutableStateFlow(HealthBannerState())
    val state: StateFlow<HealthBannerState> = _state

    init {
        refresh()
    }

    fun refresh() {
        launchJob(Dispatchers.Default) {
            val attention = sourceHealthUseCase().filter { it.status.needsAttention }
            val key = attention.map { it.source.name }.sorted().joinToString(",")
            _state.value = if (attention.isEmpty() || key == settings.dismissedHealthKey) {
                HealthBannerState()
            } else {
                HealthBannerState(attention.size, key)
            }
        }
    }

    fun dismiss() {
        settings.dismissedHealthKey = _state.value.key
        _state.value = HealthBannerState()
    }
}
```

- [ ] **Step 2: Banner composable**

```kotlin
package org.skepsun.kototoro.migration.ui.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R

/** Floating, dismissible card telling the user some favourite sources look broken. */
@Composable
fun SourceHealthBanner(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourceHealthBannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = state.sourceCount > 0, modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.migration_health_hint, state.sourceCount),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen) { Text(stringResource(R.string.migration_title)) }
                IconButton(onClick = viewModel::dismiss) { Icon(Icons.Default.Close, contentDescription = null) }
            }
        }
    }
}
```

- [ ] **Step 3: 插入收藏页**

在 `FavoritesHostScreen.kt` 中，找到 `Box(modifier = Modifier.fillMaxSize()) {`（第 337 行附近），在它的闭合 `}` 之前加入：

```kotlin
        org.skepsun.kototoro.migration.ui.health.SourceHealthBanner(
            onOpen = appRouter::openMigrationSources,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
```

- [ ] **Step 4: 收藏页菜单**

在 `MainShellScene.kt` 的 `duplicates_finder` 菜单项（第 1907-1912 行）之前插入：

```kotlin
                    KototoroTopBarMenuAction(
                        org.skepsun.kototoro.R.string.migration_sources_title,
                        org.skepsun.kototoro.R.drawable.ic_swap_vert,
                    ) {
                        appRouter.openMigrationSources()
                    },
```

- [ ] **Step 5: 编译并提交**

Run: `./gradlew :app:compileDebugKotlin --no-daemon` → BUILD SUCCESSFUL。

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/ui/health app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/compose/FavoritesHostScreen.kt app/src/main/kotlin/org/skepsun/kototoro/main/ui/compose/MainShellScene.kt
git commit -m "feat(migration): surface broken sources on favourites"
```

---

### Task 20: 收藏时的重复提醒 + 详情页失效提示

**Files:**
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/duplicate/DuplicateFavouriteViewModel.kt`
- Create: `app/src/main/kotlin/org/skepsun/kototoro/migration/ui/duplicate/DuplicateFavouriteSheet.kt`
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt:873-889`（`showFavoriteDialog`）
- Modify: `app/src/main/kotlin/org/skepsun/kototoro/details/ui/compose/DetailsScreen.kt`（第 1280、1462 行的 `onFavoriteClick`，以及 snackbar）

- [ ] **Step 1: ViewModel**

```kotlin
package org.skepsun.kototoro.migration.ui.duplicate

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.alternatives.domain.MigrateUseCase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.domain.FindLibraryDuplicatesUseCase
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.migration.domain.SourceHealthStatus
import org.skepsun.kototoro.migration.domain.SourceHealthUseCase
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

data class DuplicateEntry(val row: LibraryRow, val sourceBroken: Boolean)

@HiltViewModel
class DuplicateFavouriteViewModel @Inject constructor(
    private val findDuplicates: FindLibraryDuplicatesUseCase,
    private val sourceHealthUseCase: SourceHealthUseCase,
    private val migrateUseCase: MigrateUseCase,
    private val contentDataRepository: ContentDataRepository,
    private val settings: MigrationSettings,
) : BaseViewModel() {

    /** null = not checked yet; empty = no duplicates. */
    private val _duplicates = MutableStateFlow<List<DuplicateEntry>?>(null)
    val duplicates: StateFlow<List<DuplicateEntry>?> = _duplicates

    private val _health = MutableStateFlow(SourceHealthStatus.HEALTHY)
    val health: StateFlow<SourceHealthStatus> = _health

    val onSwitched = MutableEventFlow<Unit>()

    fun check(content: Content) {
        _duplicates.value = null
        launchJob(Dispatchers.Default) {
            val rows = findDuplicates(content)
            val unhealthy = if (rows.isEmpty()) emptySet() else {
                sourceHealthUseCase().filter { it.status.needsAttention }.mapTo(HashSet()) { it.source.name }
            }
            _duplicates.value = rows.map { DuplicateEntry(it, it.source in unhealthy) }
        }
    }

    fun loadHealth(content: Content) {
        launchJob(Dispatchers.Default) {
            _health.value = sourceHealthUseCase.forSource(content.source.name)
        }
    }

    fun switchSource(old: LibraryRow, current: Content) {
        launchLoadingJob(Dispatchers.Default) {
            val oldContent = checkNotNull(contentDataRepository.findContentById(old.id, withChapters = true))
            migrateUseCase(oldContent, current, MigrationMode.REPLACE, settings.dataFlags)
            onSwitched.call(Unit)
        }
    }

    fun disableCheck() {
        settings.isDuplicateCheckEnabled = false
    }
}
```

- [ ] **Step 2: Sheet**

```kotlin
package org.skepsun.kototoro.migration.ui.duplicate

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.parsers.model.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFavouriteSheet(
    content: Content,
    duplicates: List<DuplicateEntry>,
    viewModel: DuplicateFavouriteViewModel,
    onOpen: (Long) -> Unit,
    onAddAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel.onSwitched) {
        viewModel.onSwitched.collect { event ->
            event?.consume {
                Toast.makeText(context, R.string.migration_completed, Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.duplicate_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.duplicate_subtitle, content.title, content.source.getTitle(context), content.chaptersCount()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(duplicates, key = { it.row.id }) { entry ->
                    DuplicateCard(
                        entry = entry,
                        onOpen = { onOpen(entry.row.id) },
                        onSwitch = { viewModel.switchSource(entry.row, content) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(onClick = onAddAnyway, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.duplicate_add_anyway)) }
            }
            TextButton(
                onClick = {
                    viewModel.disableCheck()
                    onAddAnyway()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.duplicate_dont_ask)) }
        }
    }
}

@Composable
private fun DuplicateCard(entry: DuplicateEntry, onOpen: () -> Unit, onSwitch: () -> Unit) {
    val context = LocalContext.current
    val row = entry.row
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.width(132.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.clickable(onClick = onOpen)) {
                AsyncImage(
                    model = row.coverUrl,
                    contentDescription = row.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(13f / 18f).clip(RoundedCornerShape(10.dp)),
                )
                if (entry.sourceBroken) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.duplicate_source_broken),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(row.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${ContentSource(row.source).getTitle(context)} · ${row.chaptersCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                row.historyChapterNumber?.let {
                    stringResource(R.string.duplicate_read_to, if (it % 1f == 0f) it.toInt().toString() else it.toString())
                } ?: stringResource(R.string.duplicate_unread),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (row.historyPercent ?: 0f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            if (entry.sourceBroken) {
                Button(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.duplicate_switch_source), style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.duplicate_switch_source), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
```

- [ ] **Step 3: 列表或单本收藏入口（AppRouter）**

把 `fun showFavoriteDialog(manga: Collection<Content>)` 的 `composeActivity.showComposeModal { ... }` 部分替换为：

```kotlin
            val mangaList = manga.toList()
            val key = "favourite-dialog"
            composeActivity.showComposeModal(key) {
                val single = mangaList.singleOrNull()
                if (single == null) {
                    FavoriteCategoryDialogRoute(
                        manga = mangaList,
                        onManageCategories = ::openFavoriteCategories,
                        onDismiss = { composeActivity.dismissComposeModal(key) },
                    )
                } else {
                    val duplicateViewModel: org.skepsun.kototoro.migration.ui.duplicate.DuplicateFavouriteViewModel =
                        androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(key = "dup-${single.id}")
                    androidx.compose.runtime.LaunchedEffect(single.id) { duplicateViewModel.check(single) }
                    val duplicates by duplicateViewModel.duplicates.collectAsStateWithLifecycle()
                    var addAnyway by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    val found = duplicates
                    when {
                        found == null -> Unit
                        found.isEmpty() || addAnyway -> FavoriteCategoryDialogRoute(
                            manga = mangaList,
                            onManageCategories = ::openFavoriteCategories,
                            onDismiss = { composeActivity.dismissComposeModal(key) },
                        )
                        else -> org.skepsun.kototoro.migration.ui.duplicate.DuplicateFavouriteSheet(
                            content = single,
                            duplicates = found,
                            viewModel = duplicateViewModel,
                            onOpen = { id ->
                                composeActivity.dismissComposeModal(key)
                                openDetails(id)
                            },
                            onAddAnyway = { addAnyway = true },
                            onDismiss = { composeActivity.dismissComposeModal(key) },
                        )
                    }
                }
            }
```

如果文件还没有导入，需要 import `androidx.compose.runtime.getValue`、`androidx.compose.runtime.setValue` 和 `androidx.lifecycle.compose.collectAsStateWithLifecycle`，放在文件顶部的 import 区。

- [ ] **Step 4: 详情页**

在 `DetailsScreen.kt` 中：

(a) 在第 534 行 `val snackbarHostState = remember { SnackbarHostState() }` 之后加入：

```kotlin
    val duplicateViewModel: org.skepsun.kototoro.migration.ui.duplicate.DuplicateFavouriteViewModel = hiltViewModel()
    val duplicateResult by duplicateViewModel.duplicates.collectAsStateWithLifecycle()
    var pendingDuplicateCheck by remember { mutableStateOf(false) }
    var skipDuplicateSheet by remember { mutableStateOf(false) }
```

(b) 把 1280、1462 两处 `onFavoriteClick = { detailsScreenState.setShowFavoriteDialog(true) },` 都改为：

```kotlin
onFavoriteClick = {
    val current = content
    if (current != null && favouriteCategories.isEmpty()) {
        skipDuplicateSheet = false
        pendingDuplicateCheck = true
        duplicateViewModel.check(current)
    } else {
        detailsScreenState.setShowFavoriteDialog(true)
    }
},
```

(c) 在 `if (showFavoriteDialog && isWorkActionEnabled && content != null) {` 块之前加入：

```kotlin
            if (pendingDuplicateCheck && content != null) {
                val found = duplicateResult
                when {
                    found == null -> Unit
                    found.isEmpty() || skipDuplicateSheet -> {
                        pendingDuplicateCheck = false
                        detailsScreenState.setShowFavoriteDialog(true)
                    }
                    else -> org.skepsun.kototoro.migration.ui.duplicate.DuplicateFavouriteSheet(
                        content = content,
                        duplicates = found,
                        viewModel = duplicateViewModel,
                        onOpen = { id ->
                            pendingDuplicateCheck = false
                            appRouter.openDetails(id)
                        },
                        onAddAnyway = { skipDuplicateSheet = true },
                        onDismiss = { pendingDuplicateCheck = false },
                    )
                }
            }
```

(d) 失效源 Snackbar：在 (a) 之后加入：

```kotlin
    val sourceHealth by duplicateViewModel.health.collectAsStateWithLifecycle()
    LaunchedEffect(content?.id, favouriteCategories.isNotEmpty()) {
        val current = content ?: return@LaunchedEffect
        if (favouriteCategories.isNotEmpty()) duplicateViewModel.loadHealth(current)
    }
    val healthLabel = when (sourceHealth) {
        org.skepsun.kototoro.migration.domain.SourceHealthStatus.UNINSTALLED -> stringResource(R.string.migration_health_uninstalled)
        org.skepsun.kototoro.migration.domain.SourceHealthStatus.BROKEN -> stringResource(R.string.migration_health_broken)
        org.skepsun.kototoro.migration.domain.SourceHealthStatus.FAILING -> stringResource(R.string.migration_health_failing)
        org.skepsun.kototoro.migration.domain.SourceHealthStatus.DISABLED -> stringResource(R.string.migration_health_disabled)
        org.skepsun.kototoro.migration.domain.SourceHealthStatus.HEALTHY -> null
    }
    val healthMessage = healthLabel?.let { stringResource(R.string.migration_health_details_warning, it) }
    val migrateLabel = stringResource(R.string.migrate)
    LaunchedEffect(healthMessage) {
        val message = healthMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = migrateLabel,
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            content?.let { appRouter.openMigration(longArrayOf(it.id)) }
        }
    }
```

`favouriteCategories`（第 289 行）和 `content`（第 356 行）都声明在第 534 行之前，可以直接使用。`appRouter` 是该 composable 的参数（第 243 行）。`hiltViewModel`、`mutableStateOf`、`LaunchedEffect`、`collectAsStateWithLifecycle` 已经 import；如果缺少 `getValue`/`setValue`，按编译错误补上。

- [ ] **Step 5: 编译并提交**

Run: `./gradlew :app:compileDebugKotlin --no-daemon` → BUILD SUCCESSFUL。

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/migration/ui/duplicate app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt app/src/main/kotlin/org/skepsun/kototoro/details/ui/compose/DetailsScreen.kt
git commit -m "feat(migration): prompt for duplicates when favouriting and warn about broken sources"
```

---

### Task 21: 全量验证与设备验收

- [ ] **Step 1: 全部单元测试**

Run: `./gradlew :app:testDebugUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL，新测试全部通过，已有测试无回归。有失败时贴出失败测试的输出，并先使用 superpowers:systematic-debugging 排查。

- [ ] **Step 2: 构建并部署**

Run: `./gradlew :app:assembleDebug --no-daemon`，然后 `android run --debug`（在设备或模拟器上）。

- [ ] **Step 3: 按验收标准手动走一遍**

1. 收藏页右上菜单 → 「按来源换源」：来源分组显示，失效来源有标签。
2. 点击一个来源 → 默认全选 → 「下一步」→ 设置面板显示目标来源芯片（带序号）→「编辑」勾选顺序决定序号 →「开始匹配」。
3. 迁移列表：进度条推进，筛选芯片计数正确；点一行打开候选面板，切换候选后章节徽标更新；⋮ →「跳过」后该行消失。
4. 「换源 N 部」→ 确认框 → 进度 → 结果；返回收藏页，新条目在原分类中，打开后阅读进度指向对应章节。
5. 在另一个来源的详情页收藏一部书架里已有（同名或别名相同）的作品 → 出现重复提醒 →「仍然收藏」进入分类选择；再次操作选择「换成新来源」→ 提示换源成功。
6. 多选收藏条目 → 菜单中是「换源」而不是「修复」。

用 `android layout --pretty` 或截图确认界面；截图必须先实际查看 PNG 再下结论。

- [ ] **Step 4: 汇总提交（如果有修正）**

```bash
git add -A app/src
git commit -m "fix(migration): address issues found during device verification"
```
