package org.skepsun.kototoro.backups.external

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `app_id` recognition (#743). Usagi writes `app_id = BuildConfig.APPLICATION_ID`
 * (`UsagiBackup/BackupIndex.kt`), and its `app/build.gradle` carries
 * `applicationIdSuffix = ".debug"`, so a debug build exports `org.draken.usagi.debug`.
 * A fork that renames the application id exports yet another value, so equality alone
 * rejects valid archives; the rule is the dotted prefix instead.
 */
class UsagiBackupAppIdTest {

    /** Same Json settings as `UsagiBackupExportRepository`. */
    private val exportJson = Json {
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun indexJson(appId: String): String = exportJson.encodeToString(
        ListSerializer(UsagiBackupIndex.serializer()),
        listOf(UsagiBackupIndex(appId = appId, createdAt = 1_700_000_000_000L)),
    )

    private fun sections(appId: String): Map<String, String> = mapOf(
        "index" to indexJson(appId),
        "categories" to exportJson.encodeToString(
            ListSerializer(UsagiCategoryBackup.serializer()),
            listOf(UsagiCategoryBackup(categoryId = 11, createdAt = 10L, sortKey = 0, title = "Reading")),
        ),
        "favourites" to exportJson.encodeToString(
            ListSerializer(UsagiFavouriteBackup.serializer()),
            listOf(
                UsagiFavouriteBackup(
                    mangaId = 1L,
                    categoryId = 11L,
                    createdAt = 1_700_000_100_000L,
                    manga = UsagiMangaBackup(
                        id = 1L,
                        title = "Favourite",
                        url = "/manga/1",
                        publicUrl = "https://example.org/manga/1",
                        coverUrl = "https://example.org/cover/1.jpg",
                        source = "MANGA_DEX",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `usagi app ids with a dotted suffix are accepted`() {
        listOf(
            // The release build: unchanged, still the only value we export ourselves.
            "org.draken.usagi",
            // Usagi's own debug build (`applicationIdSuffix = ".debug"`): the reason for this PR.
            "org.draken.usagi.debug",
            // Any other dotted suffix a fork might add.
            "org.draken.usagi.beta",
            // Accepted on purpose: the rule is the literal dotted prefix, not a valid package name.
            "org.draken.usagi.",
        ).forEach { appId -> assertTrue(isUsagiBackupAppId(appId), appId) }
    }

    @Test
    fun `look-alike app ids are rejected`() {
        listOf(
            // No dot after the constant: the case a loose `startsWith` would wrongly accept.
            "org.draken.usagix",
            "usagi",
            "org.draken.usa",
            "org.draken.usagi2",
            // Extra text before the constant is not a dotted suffix of it.
            "com.example.org.draken.usagi",
            "",
        ).forEach { appId -> assertFalse(isUsagiBackupAppId(appId), appId) }
    }

    @Test
    fun `a missing app id is rejected`() {
        assertFalse(isUsagiBackupAppId(null))
    }

    @Test
    fun `a debug build's backup decodes end to end`() {
        val payload = decodeUsagiBackupSections(sections("org.draken.usagi.debug"))

        assertEquals(listOf("Reading"), payload.favoriteCategories.map { it.name })
        assertEquals(listOf("Favourite"), payload.records.map { it.title })
        assertEquals(listOf(11L), payload.records.single().favoriteCategoryOrders)
    }

    @Test
    fun `a look-alike app id is rejected with the actual value in the message`() {
        val exception = assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupSections(sections("org.draken.usagix"))
        }
        assertTrue(exception.message!!.contains("org.draken.usagix"), exception.message)
    }

    @Test
    fun `an index without an app id names it as missing`() {
        val exception = assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupSections(mapOf("index" to "[{\"format\":2,\"createdAt\":1}]"))
        }
        assertTrue(exception.message!!.contains("missing"), exception.message)
    }

    @Test
    fun `a JSON null app id is rejected`() {
        // `JsonNull` is itself a `JsonPrimitive`, so `(index["app_id"] as? JsonPrimitive)?.content` yields the
        // string "null" today (not null, and not "missing"); it is not a Usagi id either way. Pinned here
        // so the behaviour is explicit and does not drift.
        val exception = assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupSections(mapOf("index" to "[{\"app_id\":null}]"))
        }
        assertEquals("Not a Usagi backup: index app_id is null", exception.message)
    }

    @Test
    fun `a non-string app id keeps today's behaviour`() {
        // A number is still a `JsonPrimitive`; its `content` is "5", which is not a Usagi id.
        assertFalse(isUsagiBackupAppId("5"))
        val exception = assertThrows(UnsupportedExternalBackupException::class.java) {
            decodeUsagiBackupSections(mapOf("index" to "[{\"app_id\":5}]"))
        }
        assertTrue(exception.message!!.contains("5"), exception.message)
    }

    @Test
    fun `the lenient index parser reads the app id and gives up on anything else`() {
        assertEquals(USAGI_BACKUP_APP_ID, parseUsagiBackupAppId(indexJson(USAGI_BACKUP_APP_ID)))
        assertEquals("org.draken.usagi.debug", parseUsagiBackupAppId(indexJson("org.draken.usagi.debug")))
        // Not an array / empty array / no app_id / not JSON at all: the iOS pre-check treats these as "not Usagi".
        assertNull(parseUsagiBackupAppId("{}"))
        assertNull(parseUsagiBackupAppId("[]"))
        assertNull(parseUsagiBackupAppId("[\"org.draken.usagi\"]"))
        assertNull(parseUsagiBackupAppId("[{\"format\":2}]"))
        assertNull(parseUsagiBackupAppId("[{\"app_id\":"))
        assertNull(parseUsagiBackupAppId("not json"))
    }
}
