package org.skepsun.kototoro.tracker.work

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

/**
 * One work that had new chapters in a check; a single webhook request carries at most
 * [TrackerWebhookPayloads.MAX_ITEMS] of them, the rest only shows up in the totals.
 */
@Serializable
data class TrackerWebhookItem(
    val title: String,
    val source: String,
    val url: String,
    val newChaptersCount: Int,
    val latestChapterTitle: String? = null,
)

/**
 * Body of a tracker webhook request. [content] is a plain-text summary so a Discord webhook
 * endpoint renders something readable without any extra mapping; other endpoints can ignore it
 * and read the structured fields.
 */
@Serializable
data class TrackerWebhookPayload(
    val event: String,
    val timestamp: Long,
    val totalWorks: Int,
    val totalChapters: Int,
    val items: List<TrackerWebhookItem>,
    val content: String,
)

object TrackerWebhookPayloads {

    const val EVENT_CHAPTERS_UPDATED = "chapters_updated"
    const val EVENT_TEST = "test"

    /** Works listed in one request; the totals always count every work of the batch. */
    const val MAX_ITEMS = 20

    /**
     * Builds the body of one merged request for [items]. Totals cover the full batch, [items] is
     * cut down to [maxItems] so a large library cannot produce a request a webhook would reject.
     */
    fun buildBatch(
        items: List<TrackerWebhookItem>,
        timestamp: Long,
        maxItems: Int = MAX_ITEMS,
    ): TrackerWebhookPayload {
        val totalWorks = items.size
        val totalChapters = items.sumOf { it.newChaptersCount }
        val shown = if (items.size > maxItems) items.take(maxItems) else items
        return TrackerWebhookPayload(
            event = EVENT_CHAPTERS_UPDATED,
            timestamp = timestamp,
            totalWorks = totalWorks,
            totalChapters = totalChapters,
            items = shown,
            content = buildSummary(EVENT_CHAPTERS_UPDATED, shown, totalWorks, totalChapters),
        )
    }

    /** Builds the body of a "send test" request; it never lists any work. */
    fun buildTest(timestamp: Long): TrackerWebhookPayload = TrackerWebhookPayload(
        event = EVENT_TEST,
        timestamp = timestamp,
        totalWorks = 0,
        totalChapters = 0,
        items = emptyList(),
        content = "Kototoro webhook test\nNew-chapter notifications are set up correctly.",
    )

    private fun buildSummary(
        event: String,
        shown: List<TrackerWebhookItem>,
        totalWorks: Int,
        totalChapters: Int,
    ): String = buildString {
        append(if (event == EVENT_TEST) "Kototoro webhook test" else "Kototoro: new chapters")
        append('\n')
        append(totalWorks)
        append(if (totalWorks == 1) " work, " else " works, ")
        append(totalChapters)
        append(if (totalChapters == 1) " new chapter" else " new chapters")
        if (shown.size < totalWorks) {
            append(" (showing ")
            append(shown.size)
            append(')')
        }
        for (item in shown) {
            append('\n')
            append("• ")
            append(item.title)
            append(" — ")
            append(item.newChaptersCount)
            if (item.newChaptersCount == 1) append(" new chapter") else append(" new chapters")
        }
    }

    /**
     * Builds the payload item of one checked work, or `null` when the work must stay out of the
     * request. The NSFW rule is the one the new-chapter notification applies
     * (`TrackerNotificationHelper.createNotification`): a work the tracker hides from notifications
     * does not reach the webhook either, so a webhook never leaks titles the user asked to hide.
     *
     * @param content the preferred local representation of the work (title/url/source come from it)
     * @param excludeNsfw `settings.isTrackerNsfwDisabled || settings.isNsfwContentDisabled`
     */
    fun buildItem(
        content: Content,
        newChapters: List<ContentChapter>,
        excludeNsfw: Boolean,
    ): TrackerWebhookItem? {
        if (newChapters.isEmpty()) {
            return null
        }
        if (excludeNsfw && content.isNsfw()) {
            return null
        }
        return TrackerWebhookItem(
            title = content.title,
            source = content.source.name,
            url = content.publicUrl.ifBlank { content.url },
            newChaptersCount = newChapters.size,
            latestChapterTitle = newChapters.lastOrNull()?.title?.takeUnless { it.isBlank() },
        )
    }
}

/**
 * Validation of the user-supplied webhook endpoint: only a well-formed `https://` URL is accepted,
 * anything empty, malformed or plain `http://` is rejected (the payload may contain private titles).
 */
object TrackerWebhookUrl {

    /** Returns the normalized URL, or `null` when [raw] is not an acceptable webhook endpoint. */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) {
            return null
        }
        return url.toString()
    }

    fun isValid(raw: String?): Boolean = normalize(raw) != null
}

/**
 * Minimum interval between two tracker webhook requests. A run that finds nothing new sends
 * nothing at all, so this only guards two checks finishing back to back.
 */
object TrackerWebhookCooldown {

    const val MIN_INTERVAL_MS = 60_000L

    /** `true` while a request sent at [lastSentAt] (0 = never) is still too recent at [now]. */
    fun isActive(
        lastSentAt: Long,
        now: Long,
        intervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean = lastSentAt > 0L && now - lastSentAt < intervalMs
}
