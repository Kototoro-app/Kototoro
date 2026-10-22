package org.skepsun.kototoro.backups.ui.periodical

import org.skepsun.kototoro.backups.webdav.WebDavResource
import org.skepsun.kototoro.backups.webdav.parseWebDavResources

/**
 * The reading itself is shared with the Windows client (`core-net`, `parseWebDavResources`); this entry stays because
 * [WebDavNetworkModule] hands the function to `WebDavBackupClient` and the Android tests keep their package-level name.
 */
internal fun parseWebDavResponse(xml: String): List<WebDavResource> = parseWebDavResources(xml)
