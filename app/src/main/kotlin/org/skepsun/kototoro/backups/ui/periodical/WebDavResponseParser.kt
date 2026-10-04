package org.skepsun.kototoro.backups.ui.periodical

import org.skepsun.kototoro.backups.webdav.WebDavResource
import org.w3c.dom.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

internal fun parseWebDavResponse(xml: String): List<WebDavResource> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    val builder = factory.newDocumentBuilder()
    val document = builder.parse(xml.byteInputStream())

    val responses = document.getElementsByTagNameNS("DAV:", "response")
    val backupFiles = mutableListOf<WebDavResource>()

    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue

        // Skip directory entries
        if (href.endsWith("/")) continue

        val fileName = href.substringAfterLast("/")
        if (fileName.isEmpty()) continue

        val propstat = response.getElementsByTagNameNS("DAV:", "propstat").item(0) as? Element ?: continue
        val prop = propstat.getElementsByTagNameNS("DAV:", "prop").item(0) as? Element ?: continue

        val lastModifiedStr = prop.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent
        val sizeStr = prop.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent

        val lastModified = lastModifiedStr?.let { parseWebDavDate(it) } ?: Date(0)
        val size = sizeStr?.toLongOrNull() ?: 0L

        backupFiles.add(
            WebDavResource(
                name = fileName,
                lastModifiedMillis = lastModified.time,
                size = size,
            ),
        )
    }

    return backupFiles
}

private fun parseWebDavDate(dateStr: String): Date {
    return try {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
        format.parse(dateStr) ?: Date(0)
    } catch (e: Exception) {
        Date(0)
    }
}
