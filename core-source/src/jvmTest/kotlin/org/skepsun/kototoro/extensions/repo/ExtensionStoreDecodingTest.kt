@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.extensions.repo

import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExtensionStoreDecodingTest {
    @Test
    fun `legacy index arrays become APK-only rows with the library taken from the version name`() {
        val data = """[{"name":"Aniyomi: AnimeGG","pkg":"eu.kanade.tachiyomi.animeextension.en.animegg",
          "apk":"aniyomi-en.animegg-v14.2.apk","lang":"en","code":2,"version":"14.2","nsfw":0,
          "sources":[{"name":"AnimeGG","lang":"en","id":"2247987965040486818","baseUrl":"https://www.animegg.org"}]},
          {"name":"Tachiyomi: Old","pkg":"eu.kanade.tachiyomi.extension.all.old","apk":"old.apk","lang":"all",
          "code":"7","version":"1.4.12","nsfw":1,"sources":[{"name":"Old","lang":"en","id":-5,"baseUrl":""}]}]"""
        val index = decodeLegacyExtensionIndex(data.toByteArray(), "github.com/owner/repo")
        assertEquals("github.com/owner/repo", index.name)
        val (anime, old) = index.extensionList!!.extensions
        assertEquals("AnimeGG", anime.name)
        assertEquals("apk/aniyomi-en.animegg-v14.2.apk", anime.resources.apkUrl)
        assertEquals("", anime.resources.jarUrl)
        assertEquals("14", anime.extensionLib)
        assertEquals(2L, anime.versionCode)
        assertEquals(2247987965040486818L, anime.sources.single().id)
        assertEquals("https://www.animegg.org", anime.sources.single().homeUrl)
        assertEquals(ExtensionStoreIndex.ContentWarning.SAFE, anime.contentWarning)
        assertEquals("1.4", old.extensionLib)
        assertEquals(7L, old.versionCode)
        assertEquals(-5L, old.sources.single().id)
        assertEquals(ExtensionStoreIndex.ContentWarning.NSFW, old.contentWarning)
    }
    @Test
    fun `protobuf preserves JVM resources and signed int64 ids`() {
        val extension = ExtensionStoreIndex.Extension("源", "fixture.extension",
            ExtensionStoreIndex.Resources("plugin.apk", "icon.png", "plugin.jar"), "1.6", Long.MAX_VALUE, "1.6.1",
            ExtensionStoreIndex.ContentWarning.SAFE, listOf(ExtensionStoreIndex.Source(Long.MIN_VALUE, "源", "zh")))
        val original = ExtensionStoreIndex("仓库", "FIX", "key", ExtensionStoreIndex.Contact("https://fixture.invalid"),
            ExtensionStoreIndex.ExtensionList(listOf(extension)))
        assertEquals(original, decodeExtensionStoreIndex(ProtoBuf.encodeToByteArray(ExtensionStoreIndex.serializer(), original), true))
    }

    @Test
    fun `protobuf JSON enums and quoted large numbers match the same contract`() {
        val data = """{"name":"仓库","badgeLabel":"FIX","signingKey":"key","contact":{"website":"https://fixture.invalid"},
          "extensionList":{"extensions":[{"name":"源","packageName":"fixture.extension","extensionLib":"1.6",
          "versionCode":"9223372036854775807","versionName":"1.6.1","contentWarning":"CONTENT_WARNING_SAFE",
          "resources":{"apkUrl":"plugin.apk","iconUrl":"icon.png","jarUrl":"plugin.jar"},
          "sources":[{"id":"-9223372036854775808","name":"源","language":"zh"}]}]},"future":true}"""
        val extension = requireNotNull(decodeExtensionStoreIndex(data.toByteArray(), false).extensionList).extensions.single()
        assertEquals(Long.MAX_VALUE, extension.versionCode)
        assertEquals(Long.MIN_VALUE, extension.sources.single().id)
        assertEquals(ExtensionStoreIndex.ContentWarning.SAFE, extension.contentWarning)
        assertEquals("plugin.jar", extension.resources.jarUrl)
    }

    @Test
    fun `external list numeric enum is decoded and malformed supported fields are rejected`() {
        val data = """{"extensions":[{"name":"源","packageName":"fixture.extension","extensionLib":"1.6",
          "versionCode":1,"versionName":"1.6.1","contentWarning":2,
          "resources":{"apkUrl":"plugin.apk","iconUrl":"icon.png"},"sources":[]}]}"""
        assertEquals(ExtensionStoreIndex.ContentWarning.MIXED,
            decodeExtensionStoreList(data.toByteArray(), false).extensions.single().contentWarning)
        assertEquals("", decodeExtensionStoreList(data.toByteArray(), false).extensions.single().resources.jarUrl)
        assertThrows(Exception::class.java) { decodeExtensionStoreList(data.replace("\"versionCode\":1", "\"versionCode\":\"bad\"").toByteArray(), false) }
    }
}
