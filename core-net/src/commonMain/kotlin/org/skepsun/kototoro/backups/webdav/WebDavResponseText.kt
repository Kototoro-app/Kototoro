package org.skepsun.kototoro.backups.webdav

/**
 * Reads a WebDAV `PROPFIND` multistatus body into [WebDavResource]s without a DOM.
 *
 * Android originally parsed this with `javax.xml` inside `app`, which the Windows client cannot reuse for two reasons:
 * the class lives in the Android module, and a DOM reader ties the format to a JVM-only API. The body is a small,
 * regular document, so the reading lives here — in `commonMain` — and every platform supplies only its HTTP client.
 *
 * The rules are Android's, kept deliberately: a response whose `href` ends with `/` is a directory and is skipped, the
 * first `propstat` of a response is the one read (that is the successful one in every server we have seen), the file
 * name is the last `href` segment, and a missing date or length becomes `0` instead of failing the whole listing — a
 * server that omits a dead property must not hide the backups it does publish.
 *
 * Namespace prefixes are ignored (`D:`, `d:`, `lp1:`, `ns0:` all match by local name, case-insensitively), because the
 * prefix is the server's choice, not part of the format.
 *
 * A broken body throws [WebDavFormatException]: reporting "no remote backups" for a truncated response would invite an
 * upload that overwrites a good one, so the caller must see the failure instead of an empty list.
 */
fun parseWebDavResources(xml: String): List<WebDavResource> {
    validateStructure(xml)
    val resources = mutableListOf<WebDavResource>()
    for (response in elementTexts(xml, "response")) {
        val href = elementTexts(response, "href").firstOrNull()?.trim() ?: continue
        if (href.endsWith('/')) continue
        val name = href.substringAfterLast('/')
        if (name.isEmpty()) continue
        val propstat = elementTexts(response, "propstat").firstOrNull() ?: continue
        val prop = elementTexts(propstat, "prop").firstOrNull() ?: continue
        resources += WebDavResource(
            name = name,
            lastModifiedMillis = elementTexts(prop, "getlastmodified").firstOrNull()?.let(::webDavDateMillis) ?: 0L,
            size = elementTexts(prop, "getcontentlength").firstOrNull()?.trim()?.toLongOrNull() ?: 0L,
        )
    }
    return resources
}

/** The body is not a well-formed PROPFIND response; the caller reported it instead of returning an empty listing. */
class WebDavFormatException(message: String) : IllegalArgumentException(message)

/**
 * An HTTP date (`Sun, 06 Nov 1994 08:49:37 GMT`), which is what `getlastmodified` carries, in epoch milliseconds.
 *
 * A missing, empty or malformed value is `0` — the listing still shows the file, and [WebDavBackupCatalog] then orders
 * it last instead of throwing during a backup sync.
 *
 * Out-of-range fields roll over the way Android's lenient `SimpleDateFormat` did (`Thu, 32 Oct 2015` is 1 November,
 * `24:00:00` is the next midnight): the date is metadata for ordering, and a server that writes a bad day should not
 * drop a backup from the list.
 */
fun webDavDateMillis(text: String): Long {
    val parts = text.trim().split(' ', ',', '\t').filter { it.isNotEmpty() }
    // "06 Nov 1994 08:49:37 GMT" is the shortest supported shape; the weekday is optional.
    if (parts.size < 5) return 0L
    // "Sun, 06 Nov 1994 08:49:37 GMT": a weekday may come first, the day is the first all-digit part.
    val dayIndex = parts.indexOfFirst { part -> part.length <= 2 && part.all(Char::isDigit) }
    if (dayIndex < 0 || parts.size <= dayIndex + 3) return 0L
    val day = parts[dayIndex].toIntOrNull() ?: return 0L
    val month = MONTHS.indexOf(parts[dayIndex + 1].lowercase()) + 1
    if (month == 0) return 0L
    val year = parts[dayIndex + 2].toIntOrNull() ?: return 0L
    val clock = parts[dayIndex + 3].split(':')
    if (clock.size != 3) return 0L
    val hour = clock[0].toIntOrNull() ?: return 0L
    val minute = clock[1].toIntOrNull() ?: return 0L
    val second = clock[2].toIntOrNull() ?: return 0L
    // Every field is added rather than range-checked, so an out-of-range value rolls into the next unit.
    val seconds = daysFromCivil(year, month, 1) * 86_400L + (day - 1) * 86_400L +
        hour * 3_600L + minute * 60L + second
    // Servers normally answer GMT; an explicit offset ("+0200" or "+02:00") is applied when present.
    val zone = parts[dayIndex + 4]
    val offsetMinutes = if (zone.startsWith("+") || zone.startsWith("-")) {
        val digits = zone.drop(1).replace(":", "")
        val signed = digits.take(4).toIntOrNull() ?: return 0L
        (if (zone.startsWith("-")) -1 else 1) * ((signed / 100) * 60 + signed % 100)
    } else 0
    return (seconds - offsetMinutes * 60L) * 1_000L
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's `days_from_civil`). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val shifted = year - if (month <= 2) 1 else 0
    val era = (if (shifted >= 0) shifted else shifted - 399) / 400
    val yearOfEra = shifted - era * 400
    val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

/**
 * The inner text of every element with [localName], in document order.
 *
 * A stack-free scan is enough: the bodies we read nest `<response>` inside `<multistatus>` and `<prop>` inside
 * `<propstat>`, and collecting the inner text of the outermost match of a name is exactly what the DOM reader did.
 */
private fun elementTexts(xml: String, localName: String): List<String> {
    val texts = mutableListOf<String>()
    var index = 0
    while (true) {
        val open = nextTag(xml, localName, index, closing = false) ?: return texts
        if (open.selfClosing) {
            texts += ""
            index = open.end
            continue
        }
        var depth = 1
        var cursor = open.end
        while (depth > 0) {
            val next = nextTag(xml, localName, cursor, null) ?: return texts
            if (next.selfClosing) {
                cursor = next.end
                continue
            }
            depth += if (next.closing) -1 else 1
            cursor = next.end
            if (depth == 0) {
                texts += xml.substring(open.end, next.start)
                index = next.end
            }
        }
    }
}

private class Tag(val name: String, val start: Int, val end: Int, val closing: Boolean, val selfClosing: Boolean)

/** Rejects a body whose tags do not balance, so a truncated response is never read as "no backups". */
private fun validateStructure(xml: String) {
    val open = ArrayDeque<String>()
    for (tag in allTags(xml)) {
        if (tag.selfClosing) continue
        if (tag.closing) {
            val top = open.removeLastOrNull() ?: throw WebDavFormatException("unexpected closing tag at ${tag.start}")
            if (top != tag.name) {
                throw WebDavFormatException("closing ${tag.name} does not match open $top at ${tag.start}")
            }
        } else {
            open.addLast(tag.name)
        }
    }
    if (open.isNotEmpty()) throw WebDavFormatException("unclosed ${open.last()} element")
}

/** Every tag in the body, with the namespace prefix stripped from [Tag.name]. */
private fun allTags(xml: String): List<Tag> {
    val tags = mutableListOf<Tag>()
    var index = 0
    while (true) {
        val start = xml.indexOf('<', index)
        if (start < 0) return tags
        if (xml.startsWith("<?", start) || xml.startsWith("<!", start)) {
            val skip = xml.indexOf('>', start)
            if (skip < 0) return tags
            index = skip + 1
            continue
        }
        val end = xml.indexOf('>', start)
        if (end < 0) return tags
        val raw = xml.substring(start + 1, end)
        val isClosing = raw.startsWith("/")
        val selfClosing = raw.trimEnd().endsWith("/")
        val name = raw.trimStart('/').trim().substringBefore(' ').removeSuffix("/").substringAfterLast(':')
        tags += Tag(name.lowercase(), start, end + 1, isClosing, selfClosing)
        index = end + 1
    }
}

/** The next `<[prefix:]localName ...>`-like tag at or after [from]; [closing] filters start/end tags when given. */
private fun nextTag(xml: String, localName: String, from: Int, closing: Boolean?): Tag? {
    var index = from
    while (true) {
        val start = xml.indexOf('<', index)
        if (start < 0) return null
        if (xml.startsWith("<?", start) || xml.startsWith("<!", start)) {
            val skip = xml.indexOf('>', start)
            if (skip < 0) return null
            index = skip + 1
            continue
        }
        val end = xml.indexOf('>', start)
        if (end < 0) return null
        val raw = xml.substring(start + 1, end)
        val isClosing = raw.startsWith("/")
        val name = raw.trimStart('/').trim().substringBefore(' ').removeSuffix("/").substringAfterLast(':')
        if (name.equals(localName, ignoreCase = true) && (closing == null || closing == isClosing)) {
            return Tag(name.lowercase(), start, end + 1, isClosing, raw.trimEnd().endsWith("/"))
        }
        index = end + 1
    }
}
