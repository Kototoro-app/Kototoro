package org.skepsun.kototoro.cloudstream.runtime

/**
 * A plugin linked against NewPipeExtractor classes the host cannot provide. YouTube plugins rely on the copy the
 * official Cloudstream app bundles: Android bundles the same release, so there this means the plugin was built
 * against a different one; the Windows host has none. Either way the plugin fails with [NoClassDefFoundError] as
 * soon as it extracts a link, and hosts show this instead of a generic playback error.
 */
class CloudstreamMissingNewPipeException(cause: Throwable) : UnsupportedOperationException(
    "This extension needs NewPipe classes this host does not provide, so it cannot resolve videos",
    cause,
)

/** True when [this] or one of its causes is a class of NewPipeExtractor failing to resolve. */
internal fun Throwable.isMissingNewPipe(): Boolean = generateSequence(this) { it.cause }
    .take(MAX_CAUSE_DEPTH)
    .any { error ->
        (error is NoClassDefFoundError || error is ClassNotFoundException) &&
            error.message.orEmpty().replace('/', '.').contains(NEWPIPE_PACKAGE)
    }

private const val NEWPIPE_PACKAGE = "org.schabi.newpipe"
private const val MAX_CAUSE_DEPTH = 8
