package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs a real Legado catalog through the desktop runtime: search, details, table of contents and the first chapter of
 * every novel source, one line per source. Opt-in (`-PrealLegadoCatalog=<file>`), because it talks to the real sites.
 * Arguments: catalog file, search query, maximum number of sources.
 */
internal object DesktopRealLegadoProbe {
    private const val STEP_TIMEOUT_MS = 30_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val imported = requireNotNull(DesktopLegadoCatalog.read(Files.readString(Path.of(args[0])))) { "not a Legado catalog" }
        val query = args.getOrElse(1) { "斗罗" }
        val sources = imported.sources.mapNotNull(DesktopLegadoCatalog::source).take(args.getOrNull(2)?.toInt() ?: Int.MAX_VALUE)
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        val platform = DesktopLegadoScriptPlatform(
            client = client,
            root = Files.createTempDirectory("legado-real"),
            stores = MemoryPreferences.stores(),
        )
        val runtime = DesktopLegadoSourceRuntime(
            sources = { sources },
            createEngine = { org.skepsun.kototoro.core.javascript.RhinoJavaScriptEngine(platform) },
            createExecutor = { desktopLegadoExecutor(client, it.config, variables = null) },
        )
        println("LEGADO sources=${sources.size} skipped=${imported.skipped} query=$query")
        val passed = sources.count { source ->
            var step = "categories"
            val line = StringBuilder("LEGADO ${source.config.bookSourceName} | ")
            runCatching {
                runBlocking {
                    val categories = withTimeout(STEP_TIMEOUT_MS) { runtime.getFilterOptions(source.id).availableTags.size }
                    line.append("categories=$categories ")
                    step = "search"
                    val results = withTimeout(STEP_TIMEOUT_MS) { runtime.getList(source.id, 0, null, SourceFilter(query = query)) }
                    line.append("search=${results.size} ")
                    step = "details"
                    val details = withTimeout(STEP_TIMEOUT_MS) {
                        runtime.getDetails(results.first(), SourceDetailsFetchMode.FORCE_REFRESH)
                    }
                    val chapters = details.chapters.orEmpty()
                    line.append("chapters=${chapters.size} ")
                    step = "content"
                    val content = withTimeout(STEP_TIMEOUT_MS) {
                        runtime.getChapterContent(chapters.first(), chapters.getOrNull(1)?.url)
                    }
                    line.append("content=${content?.html?.length ?: 0}")
                    check(!content?.html.isNullOrBlank()) { "empty chapter" }
                }
            }.onFailure { error ->
                line.append("| FAIL at $step: ${error.javaClass.simpleName}: ${error.message?.take(160)}")
            }.also { println(line) }.isSuccess
        }
        println("LEGADO passed=$passed of ${sources.size}")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
