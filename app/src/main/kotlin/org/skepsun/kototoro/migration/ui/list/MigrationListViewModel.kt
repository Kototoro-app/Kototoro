package org.skepsun.kototoro.migration.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.skepsun.kototoro.migration.domain.assignFamilies
import org.skepsun.kototoro.migration.domain.resolveContentFamily
import org.skepsun.kototoro.core.model.ContentTypeFamily
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
    private val candidateDetailsJobs = ConcurrentHashMap<Long, Job>()
    private val families = ConcurrentHashMap<Long, ContentTypeFamily>()
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
                families[id] = resolveContentFamily(content.source.getContentType(), chaptersDao.findStoredContentType(id))
                val originChapters = maxOf(chaptersDao.countChapters(id), chaptersDao.findHistoryChaptersCount(id))
                MigrationItemState(origin = content, originChapters = originChapters)
            }
            // Same folding as the config sheet, so unknown entries search the sources picked there.
            val assigned = assignFamilies(families.toMap())
            families.putAll(assigned)
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
                val item = _state.value.items.firstOrNull { it.origin.id == originId }
                val current = item?.candidates?.firstOrNull { it.content.id == candidate.content.id } ?: candidate
                val content = item?.candidateWithDetails(current)?.content ?: current.content
                if (content.chapters == null) repositoryFactory.create(content.source).getDetails(content) else content
            }.getOrNull()
            _state.update { s ->
                s.updateItem(originId) {
                    if (details == null) {
                        it.copy(status = MigrationItemStatus.NOT_FOUND, target = null, targetChapters = null)
                    } else {
                        it.copy(status = MigrationItemStatus.MATCHED, target = details, targetChapters = details.chaptersCount())
                            .withCandidateDetails(details)
                    }
                }
            }
        }
    }

    /** Only candidates rendered on screen fetch details; previews share the matching concurrency limits. */
    fun loadCandidateDetails(originId: Long, candidateId: Long) {
        val item = _state.value.items.firstOrNull { it.origin.id == originId } ?: return
        val candidate = item.candidates.firstOrNull { it.content.id == candidateId } ?: return
        val content = item.candidateWithDetails(candidate).content
        if (content.chapters != null || candidateDetailsJobs.containsKey(candidateId)) return
        val job = launchJob(Dispatchers.Default, start = CoroutineStart.LAZY) {
            try {
                val details = runCatchingCancellable {
                    itemPermits.withPermit {
                        sourcePermits.getOrPut(content.source.name) { Semaphore(MAX_PER_SOURCE) }.withPermit {
                            repositoryFactory.create(content.source).getDetails(content)
                        }
                    }
                }.getOrNull() ?: return@launchJob
                _state.update { state ->
                    state.copy(items = state.items.map { it.withCandidateDetails(details) })
                }
            } finally {
                candidateDetailsJobs.remove(candidateId)
            }
        }
        if (candidateDetailsJobs.putIfAbsent(candidateId, job) == null) job.start() else job.cancel()
    }

    /** Manual search across the target sources; replaces the item's candidate list. */
    fun manualSearch(originId: Long, query: String) {
        val item = _state.value.items.firstOrNull { it.origin.id == originId } ?: return
        itemJobs.remove(originId)?.cancel()
        _state.update { s -> s.updateItem(originId) { it.copy(status = MigrationItemStatus.SEARCHING, candidates = emptyList()) } }
        itemJobs[originId] = launchJob(Dispatchers.Default) {
            // Results stream in per source so one slow site does not hold back the rest.
            kotlinx.coroutines.coroutineScope {
                targetSources(item.origin).map { source ->
                    async {
                        val outcome = engine.searchSource(item.origin, source, query, minScore = 0.0)
                        if (outcome.candidates.isNotEmpty()) {
                            _state.update { s ->
                                s.updateItem(originId) {
                                    it.copy(candidates = (it.candidates + outcome.candidates).sortedByDescending { c -> c.score })
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            _state.update { s ->
                s.updateItem(originId) {
                    it.copy(
                        status = if (it.target != null) MigrationItemStatus.MATCHED else MigrationItemStatus.NOT_FOUND,
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
                        val matched = it.copy(
                            status = if (best != null) MigrationItemStatus.MATCHED else MigrationItemStatus.NOT_FOUND,
                            target = best,
                            targetChapters = best?.chaptersCount(),
                            candidates = result?.candidates.orEmpty(),
                            sourceErrors = result?.errors?.map { (name, e) -> "$name: ${e.message}" }.orEmpty(),
                        )
                        if (best != null) matched.withCandidateDetails(best) else matched
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
        val family = families[origin.id] ?: origin.source.getContentType().contentFamily()
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
