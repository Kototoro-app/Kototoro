package org.skepsun.kototoro.core.ui.text

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's answer to [UiStrings]: the name is one of its own resource names, so a screen both hosts share reads the
 * Webtranslate-managed catalog instead of a second copy of the text.
 *
 * Names are resolved to ids once and cached: composition asks for the same handful of names on every recomposition,
 * while `getIdentifier` walks the resource table. A name missing from `res/values` answers itself, as Android's own
 * `getString` would fail rather than show something wrong.
 */
class AndroidUiStrings(private val context: Context) : UiStrings {

    private val stringIds = ConcurrentHashMap<String, Int>()
    private val arrayIds = ConcurrentHashMap<String, Int>()

    override fun string(name: String): String = id(stringIds, name, "string")
        ?.let { id -> runCatching { context.getString(id) }.getOrNull() }
        ?: name

    override fun format(name: String, vararg args: Any?): String = id(stringIds, name, "string")
        ?.let { id -> runCatching { context.getString(id, *args) }.getOrNull() }
        ?: name

    override fun array(name: String): List<String> = id(arrayIds, name, "array")
        ?.let { id -> runCatching { context.resources.getStringArray(id).toList() }.getOrNull() }
        .orEmpty()

    /** The id of [name] in [cache], or null when no resource of [type] carries it. */
    @Suppress("DiscouragedApi")
    private fun id(cache: ConcurrentHashMap<String, Int>, name: String, type: String): Int? {
        val id = cache.getOrPut(name) {
            // The application id carries the build suffix; the namespace is what the resources were compiled under.
            context.resources.getIdentifier(name, type, context.packageName)
                .takeIf { it != 0 }
                ?: context.resources.getIdentifier(name, type, NAMESPACE)
        }
        return id.takeIf { it != 0 }
    }

    private companion object {
        /** `org.skepsun.kototoro`'s resources, for a context whose package differs from it. */
        const val NAMESPACE = "org.skepsun.kototoro"
    }
}

/** The catalog shared screens read on Android, resolved once for this composition tree. */
@Composable
fun rememberAndroidUiStrings(): UiStrings {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidUiStrings(context) }
}
