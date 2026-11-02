package org.skepsun.kototoro.core.util

import java.io.File

/**
 * Where an archive entry called [entryName] lands under [destDir], or a [SecurityException] when it would land
 * outside it. The comparison is by path component on canonical paths, so `../model-evil/x` does not pass for
 * `model` the way a string prefix check lets it, and a symbolic link inside [destDir] is followed before judging.
 */
fun archiveEntryFile(destDir: File, entryName: String): File {
    val base = destDir.canonicalFile
    val target = File(base, entryName.replace('\\', '/').trimStart('/')).canonicalFile
    if (!target.toPath().startsWith(base.toPath())) {
        throw SecurityException("Archive entry escapes the destination directory: $entryName")
    }
    return target
}
