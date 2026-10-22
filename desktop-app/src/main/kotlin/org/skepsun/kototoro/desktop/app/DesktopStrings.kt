package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * One UI language this build ships, identified by its BCP-47 [tag] (`zh-CN`), which names its catalog file.
 * [nativeName] is the name in the language itself, for the picker, and [englishName] the name in English so a
 * language stays findable from one the reader does not read.
 */
internal data class DesktopLanguage(val tag: String, val nativeName: String, val englishName: String)

/**
 * The languages the Windows UI offers.
 *
 * The build writes `desktop-languages.properties` from the catalogs in `src/main/resources/i18n`, so adding a
 * `desktop-strings_<tag>.properties` (and the matching Android `values-*` folder) is all it takes to add a language:
 * the settings page, the picker and the Android string export all follow that one list. English is the base catalog
 * (`desktop-strings.properties`) and the fallback for every string a translation does not cover.
 */
internal object DesktopLanguages {
    const val BASE_TAG = "en"

    /** Every offered language, English first. */
    val bundled: List<DesktopLanguage> by lazy {
        readIndex().distinct().ifEmpty { listOf(BASE_TAG) }.map(::languageOf)
    }

    private fun languageOf(tag: String): DesktopLanguage {
        val locale = Locale.forLanguageTag(tag)
        return DesktopLanguage(
            tag = tag,
            nativeName = locale.getDisplayName(locale).replaceFirstChar { it.titlecase() },
            englishName = locale.getDisplayName(Locale.ENGLISH).replaceFirstChar { it.titlecase() },
        )
    }

    /** The language the UI follows when the reader has not chosen one: the system's display language. */
    fun systemTag(): String = Locale.getDefault(Locale.Category.DISPLAY).toLanguageTag()

    /**
     * The offered language that best matches [requested] (a tag, or null for the system language): an exact tag
     * first, then the same language in another region (`zh-TW` reads `zh-CN`), then English.
     */
    fun best(requested: String?): String {
        val bundled = bundled.map { it.tag }
        val wanted = requested?.takeIf { it.isNotBlank() } ?: systemTag()
        val locale = runCatching { Locale.forLanguageTag(wanted) }.getOrNull()
            ?: return bundled.firstOrNull() ?: BASE_TAG
        return bundled.firstOrNull { tag -> Locale.forLanguageTag(tag).toLanguageTag().equals(locale.toLanguageTag(), true) }
            ?: bundled.firstOrNull { tag -> Locale.forLanguageTag(tag).language == locale.language }
            ?: BASE_TAG
    }

    private fun readIndex(): List<String> {
        val properties = loadProperties("desktop-languages") ?: return listOf(BASE_TAG)
        return properties.getProperty("languages").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    }
}

/**
 * The Windows UI's text, in the language the user picked (or the system's), so no screen hardcodes one language.
 *
 * Lookups are resolved per call rather than composed into a bundle: [tag] is snapshot state, so every composable that
 * shows text is invalidated when the language changes, and a language switch needs no restart. A missing key returns
 * the key itself at runtime; `:desktop-app:verifyDesktopStrings` rejects one at build time, together with a key no
 * screen uses and a translation that misses a key of the base catalog.
 */
internal object DesktopStrings {
    private val catalogs = ConcurrentHashMap<String, Properties>()

    @Volatile private var pinned = false
    private val mutableTag = mutableStateOf(resolveInitialTag())

    /** The language in effect; reading it from a composable makes that composable follow a language switch. */
    val tag: State<String> get() = mutableTag

    /** True when `--lang` or `-Dkototoro.desktop.locale` fixed the language for this run. */
    val isPinned: Boolean get() = pinned

    /** The tag in effect, for callers outside composition. */
    val currentTag: String get() = mutableTag.value

    /** The [Locale] for dates, numbers and collation, so formats follow the interface language. */
    val locale: Locale get() = Locale.forLanguageTag(mutableTag.value)

    /** Fixes the language for this run (command line or system property); the saved setting is then not applied. */
    fun pin(requested: String?) {
        pinned = true
        mutableTag.value = DesktopLanguages.best(requested)
    }

    /** Shows the UI in [requested] (null: follow the system language), ignoring unknown tags. */
    fun select(requested: String?) {
        mutableTag.value = DesktopLanguages.best(requested)
    }

    operator fun get(key: String): String {
        val tag = mutableTag.value // read through the snapshot state, so composables re-read after a switch
        // getProperty, not get: Properties is a Hashtable, whose get returns Any?.
        return catalog("desktop-strings", tag).getProperty(key)
            ?: baseCatalog("desktop-strings").getProperty(key)
            ?: key
    }

    /** The string [key] with [args] substituted, formatted in the interface language. */
    fun format(key: String, vararg args: Any?): String = String.format(locale, get(key), *args)

    /**
     * [key] in the interface language, or [fallback] when this build's catalogs do not have it, formatted with [args].
     * `desktop-runtime` reports a key plus an English fallback for its own messages (see its DesktopRuntimeStrings),
     * because it cannot read the UI's catalogs; this is the lookup it installs.
     */
    fun localized(key: String, fallback: String, args: Array<out Any?>): String {
        val value = get(key).takeIf { it != key } ?: fallback
        return if (args.isEmpty()) value else String.format(locale, value, *args)
    }

    /** The strings of one Android resource file in the interface language, falling back to Android's default. */
    internal fun android(name: String, key: String): String? {
        val tag = mutableTag.value
        return catalog(name, tag).getProperty(key) ?: baseCatalog(name).getProperty(key)
    }

    private fun catalog(name: String, tag: String): Properties = catalogs.getOrPut("$name@$tag") {
        // Android's and our own catalogs are partial translations: the base catalog fills the gaps.
        val base = baseCatalog(name)
        val translated = candidates(tag).firstNotNullOfOrNull { loadProperties("${name}_$it") }
        when {
            translated == null -> base
            else -> Properties().apply { putAll(base); putAll(translated) }
        }
    }

    private fun baseCatalog(name: String): Properties = catalogs.getOrPut("$name@base") {
        loadProperties(name) ?: Properties()
    }

    /** `zh-Hant-TW` before `zh-Hant` before `zh`: the usual locale fallback chain. */
    private fun candidates(tag: String): List<String> {
        val parts = tag.split('-').filter(String::isNotEmpty)
        return (parts.size downTo 1).map { parts.take(it).joinToString("-") }
    }

    private fun resolveInitialTag(): String {
        val requested = System.getProperty(LOCALE_PROPERTY)?.takeIf(String::isNotBlank)
        pinned = requested != null
        return DesktopLanguages.best(requested)
    }

    /** `-Dkototoro.desktop.locale=zh-CN` pins the language, as `--lang` does; the desktop test suite uses it. */
    const val LOCALE_PROPERTY = "kototoro.desktop.locale"
}

/** The text named [name] in the interface language; [args] are substituted as in [DesktopStrings.format]. */
internal fun desktopText(name: String, vararg args: Any?): String =
    if (args.isEmpty()) DesktopStrings[name] else DesktopStrings.format(name, *args)

/** Catalogs are resources under `i18n/`: the desktop's own, and Android's exported by the build. */
private fun loadProperties(baseName: String): Properties? =
    DesktopStrings::class.java.getResourceAsStream("/i18n/$baseName.properties")
        ?.reader(Charsets.UTF_8)?.use { reader -> Properties().apply { load(reader) } }
