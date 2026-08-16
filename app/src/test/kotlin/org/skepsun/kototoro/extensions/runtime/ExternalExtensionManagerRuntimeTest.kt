package org.skepsun.kototoro.extensions.runtime

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalExtensionManagerRuntimeTest {

	@Test
	fun `loadExtensions updates state and caches processed results`() = runBlocking {
		val source = FakeSource(1L)
		val wrapped = FakeWrappedSource(1L)
		val runtime = ExternalExtensionManagerRuntime<FakeResult, FakeSuccess, FakeError, FakeSource, FakeWrappedSource>(
			context = mockk<Context>(relaxed = true),
			scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
		)

		assertFalse(runtime.isLoading.value)
		assertTrue(runtime.installedExtensions.value.isEmpty())
		assertTrue(runtime.failedExtensions.value.isEmpty())

		runtime.loadExtensions(
			loadResults = { listOf(FakeResult.Ok) },
			processResults = {
				assertEquals(listOf(FakeResult.Ok), it)
				ProcessedExternalExtensions(
					successful = listOf(FakeSuccess("pkg.ok")),
					failed = listOf(FakeError("pkg.err")),
					sourceById = mapOf(source.id to source),
					wrappedSourceById = mapOf(wrapped.id to wrapped),
					untrustedPackages = listOf("pkg.untrusted"),
				)
			},
		)

		assertFalse(runtime.isLoading.value)
		assertEquals(listOf(FakeSuccess("pkg.ok")), runtime.installedExtensions.value)
		assertEquals(listOf(FakeError("pkg.err")), runtime.failedExtensions.value)
		assertSame(source, runtime.getSourceById(1L))
		assertSame(wrapped, runtime.getWrappedSourceById(1L))
		assertEquals(listOf(wrapped), runtime.getWrappedSources())
		assertEquals(1, runtime.getSourceCount())
		assertTrue(runtime.hasExtensions())
		assertNull(runtime.getSourceById(99L))
	}

	@Test
	fun `loadExtensions queues refresh requested while another load is running`() = runBlocking {
		val runtime = ExternalExtensionManagerRuntime<FakeResult, FakeSuccess, FakeError, FakeSource, FakeWrappedSource>(
			context = mockk<Context>(relaxed = true),
			scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
		)
		val firstLoadStarted = CompletableDeferred<Unit>()
		val releaseFirstLoad = CompletableDeferred<Unit>()

		val firstLoad = async {
			runtime.loadExtensions(
				loadResults = {
					firstLoadStarted.complete(Unit)
					releaseFirstLoad.await()
					listOf(FakeResult.Ok)
				},
				processResults = { processedExtensions("pkg.old") },
			)
		}
		firstLoadStarted.await()
		val refreshLoad = async {
			runtime.loadExtensions(
				loadResults = { listOf(FakeResult.Ok) },
				processResults = { processedExtensions("pkg.new") },
			)
		}

		releaseFirstLoad.complete(Unit)
		firstLoad.await()
		refreshLoad.await()

		assertEquals(listOf(FakeSuccess("pkg.new")), runtime.installedExtensions.value)
		assertEquals(2, runtime.changes.value)
	}

	private fun processedExtensions(packageName: String) = ProcessedExternalExtensions(
		successful = listOf(FakeSuccess(packageName)),
		failed = emptyList<FakeError>(),
		sourceById = emptyMap<Long, FakeSource>(),
		wrappedSourceById = emptyMap<Long, FakeWrappedSource>(),
		untrustedPackages = emptyList(),
	)

	private sealed interface FakeResult {
		data object Ok : FakeResult
	}

	private data class FakeSuccess(
		val pkgName: String,
	)

	private data class FakeError(
		val pkgName: String,
	)

	private data class FakeSource(
		val id: Long,
	)

	private data class FakeWrappedSource(
		val id: Long,
	)
}
