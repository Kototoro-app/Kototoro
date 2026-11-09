package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.library.SourceStateEdits
import platform.Foundation.NSDictionary
import platform.Foundation.NSMutableDictionary
import platform.Foundation.dictionaryWithContentsOfFile
import platform.Foundation.setValue
import platform.Foundation.writeToFile

/** Source edit times in a property list beside the database, so they follow it (and its tests) around. */
internal fun iosSourceStateEdits(databasePath: String): SourceStateEdits {
    val path = "$databasePath.source-states.plist"
    return SourceStateEdits(
        load = {
            NSDictionary.dictionaryWithContentsOfFile(path).orEmpty().entries.mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }.toMap()
        },
        save = { entries ->
            val dictionary = NSMutableDictionary()
            entries.forEach { (key, value) -> dictionary.setValue(value, forKey = key) }
            check(dictionary.writeToFile(path, atomically = true)) { "Cannot save source edit times" }
        },
    )
}
