package org.skepsun.kototoro.extensions.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class ExternalExtensionManagerRuntime<ResultT, SuccessT, ErrorT, SourceT, WrappedSourceT>(
	private val context: Context,
	private val scope: CoroutineScope,
) {

	private val _installedExtensions = MutableStateFlow<List<SuccessT>>(emptyList())
	val installedExtensions: StateFlow<List<SuccessT>> = _installedExtensions.asStateFlow()

	private val _failedExtensions = MutableStateFlow<List<ErrorT>>(emptyList())
	val failedExtensions: StateFlow<List<ErrorT>> = _failedExtensions.asStateFlow()

	private val _isLoading = MutableStateFlow(false)
	val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

	private val _changes = MutableStateFlow(0)
	val changes: StateFlow<Int> = _changes.asStateFlow()

	private val loadMutex = Mutex()

	@Volatile
	private var sourceSnapshot = SourceSnapshot<SourceT, WrappedSourceT>()

	@Volatile
	private var isPackageObserverRegistered = false

	fun initialize(loadAction: suspend () -> Unit) {
		registerPackageObserver(loadAction)
		scope.launchInRuntime(loadAction)
	}

	suspend fun loadExtensions(
		loadResults: suspend (Context) -> List<ResultT>,
		processResults: (List<ResultT>) -> ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT>,
	) {
		if (!loadMutex.tryLock()) return

		_isLoading.value = true
		try {
			val processed = processResults(loadResults(context))
			sourceSnapshot = SourceSnapshot(
				sourceById = processed.sourceById,
				wrappedSourceById = processed.wrappedSourceById,
			)
			_installedExtensions.value = processed.successful
			_failedExtensions.value = processed.failed
			_changes.value++
		} finally {
			_isLoading.value = false
			loadMutex.unlock()
		}
	}

	fun getInstalledExtensions(): List<SuccessT> = installedExtensions.value

	fun getSourceById(sourceId: Long): SourceT? = sourceSnapshot.sourceById[sourceId]

	fun getWrappedSourceById(sourceId: Long): WrappedSourceT? = sourceSnapshot.wrappedSourceById[sourceId]

	fun getWrappedSources(): List<WrappedSourceT> = sourceSnapshot.wrappedSourceById.values.toList()

	fun getSourceCount(): Int = sourceSnapshot.sourceById.size

	fun hasExtensions(): Boolean = installedExtensions.value.isNotEmpty()

	private fun registerPackageObserver(loadAction: suspend () -> Unit) {
		if (isPackageObserverRegistered) return
		registerExternalExtensionPackageObserver(context) {
			loadAction()
		}
		isPackageObserverRegistered = true
	}

	private fun CoroutineScope.launchInRuntime(loadAction: suspend () -> Unit) {
		launch {
			loadAction()
		}
	}

	private data class SourceSnapshot<SourceT, WrappedSourceT>(
		val sourceById: Map<Long, SourceT> = emptyMap(),
		val wrappedSourceById: Map<Long, WrappedSourceT> = emptyMap(),
	)
}
