package org.skepsun.kototoro.extensions.repo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.ResponseBody.Companion.toResponseBody
import io.mockk.mockk
import org.skepsun.kototoro.core.prefs.AppSettings
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ExtensionRepoServiceTest : FunSpec({

	lateinit var server: MockWebServer
	lateinit var service: ExtensionRepoService

	beforeTest {
		server = MockWebServer()
		server.start()
		service = ExtensionRepoService(
			httpClient = OkHttpClient(),
			json = Json {
				ignoreUnknownKeys = true
				isLenient = true
				encodeDefaults = true
				prettyPrint = true
				coerceInputValues = true
			},
			settings = mockk<AppSettings>(relaxed = true),
		)
	}

	afterTest {
		server.shutdown()
	}

	test("normalizeIndexUrl appends index path and strips query and fragment") {
		service.normalizeIndexUrl(" https://example.org/extensions/?foo=1#bar ") shouldBe
			"https://example.org/extensions/index.min.json"
	}

	test("baseUrlFromIndexUrl strips standard index file for non cloudstream repositories") {
		service.baseUrlFromIndexUrl("https://example.org/extensions/index.min.json") shouldBe
			"https://example.org/extensions"
	}

	test("normalizeIndexUrl preserves protobuf index url") {
		service.normalizeIndexUrl(" https://example.org/extensions/index.pb?foo=1#bar ") shouldBe
			"https://example.org/extensions/index.pb"
	}

	test("baseUrlFromIndexUrl preserves protobuf index url") {
		service.baseUrlFromIndexUrl("https://example.org/extensions/index.pb") shouldBe
			"https://example.org/extensions/index.pb"
	}

	test("normalizeIndexUrl keeps cloudstream root urls when type is cloudstream") {
		service.normalizeIndexUrl(
			" https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/main#frag ",
			ExternalExtensionType.CLOUDSTREAM,
		) shouldBe "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/main"
	}

	test("normalizeIndexUrl rejects non-https urls") {
		service.normalizeIndexUrl("http://example.org/extensions").shouldBeNull()
	}

	test("fetchRepoDetails parses repository metadata") {
		runBlocking {
			server.enqueue(
				MockResponse().setBody(
					"""
					{
					  "meta": {
					    "name": "Keiyoushi",
					    "shortName": "Kei",
					    "website": "https://keiyoushi.example",
					    "signingKeyFingerprint": "AA:BB:CC"
					  }
					}
					""".trimIndent(),
				),
			)

			val repo = service.fetchRepoDetails(
				baseUrl = server.url("/mihon").toString().removeSuffix("/"),
				type = ExternalExtensionType.MIHON,
			)

			repo.shouldNotBeNull()
			repo.type shouldBe ExternalExtensionType.MIHON
			repo.baseUrl shouldBe server.url("/mihon").toString().removeSuffix("/")
			repo.name shouldBe "Keiyoushi"
			repo.shortName shouldBe "Kei"
			repo.website shouldBe "https://keiyoushi.example"
			repo.signingKeyFingerprint shouldBe "AA:BB:CC"
		}
	}

	test("fetchRepoDetails falls back to repo endpoint for cloudstream repositories") {
		runBlocking {
			server.enqueue(MockResponse().setResponseCode(404))
			server.enqueue(
				MockResponse().setBody(
					"""
					{
					  "name": "3rabi عربي",
					  "manifestVersion": 1,
					  "repositoryUrl": "https://github.com/Abodabodd/re-3arabi",
					  "pluginLists": ["plugins.json"]
					}
					""".trimIndent(),
				),
			)

			val repo = service.fetchRepoDetails(
				baseUrl = server.url("/cloudstream").toString().removeSuffix("/"),
				type = ExternalExtensionType.CLOUDSTREAM,
			)

			repo.shouldNotBeNull()
			repo.type shouldBe ExternalExtensionType.CLOUDSTREAM
			repo.baseUrl shouldBe server.url("/cloudstream/repo").toString()
			repo.name shouldBe "3rabi عربي"
			repo.website shouldBe "https://github.com/Abodabodd/re-3arabi"

			server.takeRequest().path shouldBe "/cloudstream/repo.json"
			server.takeRequest().path shouldBe "/cloudstream/repo"
		}
	}

	test("fetchAvailableExtensions parses catalog entries and compatibility") {
		runBlocking {
			server.enqueue(
				MockResponse().setBody(
					"""
					[
					  {
					    "name": "Tachiyomi: Asura Scans",
					    "pkg": "ext.asura",
					    "apk": "asura.apk",
					    "lang": "en",
					    "code": 2,
					    "version": "1.9.0",
					    "nsfw": 1,
					    "sources": [{"name": "Asura Scans"}]
					  },
					  {
					    "name": "Tachiyomi: Legacy",
					    "pkg": "ext.legacy",
					    "apk": "legacy.apk",
					    "lang": "en",
					    "code": 1,
					    "version": "1.1.0",
					    "nsfw": 0,
					    "sources": [{"name": "Legacy"}]
					  },
					  {
					    "name": "Tachiyomi: Broken",
					    "pkg": "ext.broken",
					    "apk": "broken.apk",
					    "lang": "en",
					    "code": 1,
					    "version": "broken",
					    "nsfw": 0,
					    "sources": [{"name": "Broken"}]
					  }
					]
					""".trimIndent(),
				),
			)

			val repo = ExternalExtensionRepo(
				type = ExternalExtensionType.MIHON,
				baseUrl = server.url("/mihon").toString().removeSuffix("/"),
				name = "Keiyoushi",
				shortName = "Kei",
				website = "https://keiyoushi.example",
				signingKeyFingerprint = "AA:BB:CC",
				createdAt = 1L,
				updatedAt = 1L,
				lastSuccessAt = 1L,
				lastError = null,
			)

			val extensions = service.fetchAvailableExtensions(repo)

			extensions shouldHaveSize 2
			extensions[0].name shouldBe "Asura Scans"
			extensions[0].isCompatible.shouldBeTrue()
			extensions[0].isNsfw.shouldBeTrue()
			extensions[0].sourceNames shouldBe listOf("Asura Scans")
			extensions[0].iconUrl shouldBe "${repo.baseUrl}/icon/ext.asura.png"
			extensions[0].signatureHash shouldBe "AA:BB:CC"
			extensions[0].repoName shouldBe "Kei"

			extensions[1].name shouldBe "Legacy"
			extensions[1].isCompatible.shouldBeFalse()
		}
	}

	test("fetchRepoDetails follows legacy index_v2 protobuf url") {
		runBlocking {
			val index = MihonExtensionStoreIndex(
				name = "Keiyoushi",
				badgeLabel = "KEI",
				signingKey = "AA:BB:CC",
				contact = MihonExtensionStoreIndex.Contact(
					website = "https://keiyoushi.github.io",
				),
				extensionList = MihonExtensionStoreIndex.ExtensionList(emptyList()),
			)
			val encoded = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(
				MihonExtensionStoreIndex.serializer(),
				index,
			).gzip()
			val indexUrl = server.url("/mihon/index.pb").toString()
			server.enqueue(
				MockResponse().setBody(
					"""
					{
					  "index_v2": "$indexUrl",
					  "meta": {
					    "name": "Legacy",
					    "website": "https://legacy.example",
					    "signingKeyFingerprint": "legacy"
					  }
					}
					""".trimIndent(),
				),
			)
			server.enqueue(MockResponse().setBody(okio.Buffer().write(encoded)))

			val repo = service.fetchRepoDetails(
				baseUrl = server.url("/mihon").toString().removeSuffix("/"),
				type = ExternalExtensionType.MIHON,
			)

			repo.baseUrl shouldBe indexUrl
			repo.name shouldBe "Keiyoushi"
			repo.shortName shouldBe "KEI"
			server.takeRequest().path shouldBe "/mihon/repo.json"
			server.takeRequest().path shouldBe "/mihon/index.pb"
		}
	}

	test("fetchRepoDetails and extensions parse gzipped protobuf index") {
		runBlocking {
			val index = MihonExtensionStoreIndex(
				name = "Keiyoushi",
				badgeLabel = "KEI",
				signingKey = "AA:BB:CC",
				contact = MihonExtensionStoreIndex.Contact(
					website = "https://keiyoushi.github.io",
					discord = "https://discord.example",
				),
				extensionList = MihonExtensionStoreIndex.ExtensionList(
					extensions = listOf(
						MihonExtensionStoreIndex.Extension(
							name = "Asura Scans",
							packageName = "ext.asura",
							resources = MihonExtensionStoreIndex.Resources(
								apkUrl = "https://cdn.example/asura.apk",
								iconUrl = "https://cdn.example/asura.png",
							),
							extensionLib = "1.9",
							versionCode = 2,
							versionName = "1.9.0",
							contentWarning = MihonExtensionStoreIndex.ContentWarning.MIXED,
							sources = listOf(
								MihonExtensionStoreIndex.Source(
									id = 1,
									name = "Asura Scans",
									language = "en",
									homeUrl = "https://asura.example",
								),
							),
						),
					),
				),
			)
			val encoded = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(
				MihonExtensionStoreIndex.serializer(),
				index,
			).gzip()
			server.enqueue(MockResponse().setBody(okio.Buffer().write(encoded)))
			server.enqueue(MockResponse().setBody(okio.Buffer().write(encoded)))
			val indexUrl = server.url("/mihon/index.pb").toString()

			val repo = service.fetchRepoDetails(indexUrl, ExternalExtensionType.MIHON)
			val extensions = service.fetchAvailableExtensionsOrThrow(repo)

			repo.baseUrl shouldBe indexUrl
			repo.name shouldBe "Keiyoushi"
			repo.shortName shouldBe "KEI"
			repo.website shouldBe "https://keiyoushi.github.io"
			repo.signingKeyFingerprint shouldBe "AA:BB:CC"
			extensions shouldHaveSize 1
			extensions.first().name shouldBe "Asura Scans"
			extensions.first().archiveName shouldBe "asura.apk"
			extensions.first().archiveUrl shouldBe "https://cdn.example/asura.apk"
			extensions.first().iconUrl shouldBe "https://cdn.example/asura.png"
			extensions.first().isNsfw.shouldBeTrue()
			extensions.first().sourceNames shouldBe listOf("Asura Scans")
			server.takeRequest().path shouldBe "/mihon/index.pb"
			server.takeRequest().path shouldBe "/mihon/index.pb"
		}
	}

	test("fetchAvailableExtensions falls back from legacy json to protobuf index") {
		runBlocking {
			val index = MihonExtensionStoreIndex(
				name = "Keiyoushi",
				badgeLabel = "KEI",
				signingKey = "AA:BB:CC",
				contact = MihonExtensionStoreIndex.Contact("https://keiyoushi.github.io"),
				extensionList = MihonExtensionStoreIndex.ExtensionList(
					listOf(
						MihonExtensionStoreIndex.Extension(
							name = "Asura Scans",
							packageName = "ext.asura",
							resources = MihonExtensionStoreIndex.Resources(
								apkUrl = "https://cdn.example/asura.apk",
								iconUrl = "https://cdn.example/asura.png",
							),
							extensionLib = "1.9",
							versionCode = 2,
							versionName = "1.9.0",
							contentWarning = MihonExtensionStoreIndex.ContentWarning.SAFE,
							sources = listOf(
								MihonExtensionStoreIndex.Source(1, "Asura Scans", "en"),
							),
						),
					),
				),
			)
			val encoded = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(
				MihonExtensionStoreIndex.serializer(),
				index,
			).gzip()
			server.enqueue(MockResponse().setResponseCode(404))
			server.enqueue(MockResponse().setBody(okio.Buffer().write(encoded)))
			val baseUrl = server.url("/mihon").toString().removeSuffix("/")
			val repo = ExternalExtensionRepo(
				type = ExternalExtensionType.MIHON,
				baseUrl = baseUrl,
				name = "Keiyoushi",
				shortName = "KEI",
				website = "https://keiyoushi.github.io",
				signingKeyFingerprint = "AA:BB:CC",
				createdAt = 1,
				updatedAt = 1,
				lastSuccessAt = 1,
				lastError = null,
			)

			val extensions = service.fetchAvailableExtensionsOrThrow(repo)

			extensions shouldHaveSize 1
			extensions.first().archiveUrl shouldBe "https://cdn.example/asura.apk"
			extensions.first().repoUrl shouldBe baseUrl
			server.takeRequest().path shouldBe "/mihon/index.min.json"
			server.takeRequest().path shouldBe "/mihon/index.pb"
		}
	}

	test("fetchAvailableExtensions falls back to repo endpoint for cloudstream repositories") {
		runBlocking {
			server.enqueue(
				MockResponse().setBody(
					"""
					{
					  "name": "3rabi عربي",
					  "manifestVersion": 1,
					  "pluginLists": ["plugins.json"]
					}
					""".trimIndent(),
				),
			)
			server.enqueue(
				MockResponse().setBody(
					"""
					[
					  {
					    "url": "build/Aflaam.cs3",
					    "version": 7,
					    "apiVersion": 1,
					    "name": "Aflaam",
					    "internalName": "Aflaam",
					    "language": "ar",
					    "iconUrl": "https://example.org/aflaam.png"
					  }
					]
					""".trimIndent(),
				),
			)

			val repo = ExternalExtensionRepo(
				type = ExternalExtensionType.CLOUDSTREAM,
				baseUrl = server.url("/cloudstream/repo").toString(),
				name = "3rabi عربي",
				shortName = "3rabi عربي",
				website = "https://github.com/Abodabodd/re-3arabi",
				signingKeyFingerprint = "cloudstream",
				createdAt = 1L,
				updatedAt = 1L,
				lastSuccessAt = 1L,
				lastError = null,
			)

			val extensions = service.fetchAvailableExtensionsOrThrow(repo)

			extensions shouldHaveSize 1
			extensions.first().archiveUrl shouldBe server.url("/cloudstream/build/Aflaam.cs3").toString()
			extensions.first().iconUrl shouldBe "https://example.org/aflaam.png"

			server.takeRequest().path shouldBe "/cloudstream/repo"
			server.takeRequest().path shouldBe "/cloudstream/plugins.json"
		}
	}

	test("fetchRepoDetails keeps exact cloudstream json metadata url") {
		runBlocking {
			server.enqueue(
				MockResponse().setBody(
					"""
					{
					  "name": "CXXX",
					  "manifestVersion": 1,
					  "pluginLists": ["plugins.json"]
					}
					""".trimIndent(),
				),
			)

			val metadataUrl = server.url("/builds/CXXX.json").toString()
			val repo = service.fetchRepoDetails(
				baseUrl = metadataUrl,
				type = ExternalExtensionType.CLOUDSTREAM,
			)

			repo.baseUrl shouldBe metadataUrl
			server.takeRequest().path shouldBe "/builds/CXXX.json"
		}
	}

	test("fetchAvailableExtensions resolves cloudstream plugin urls relative to plugin list file") {
		runBlocking {
			server.enqueue(
				MockResponse().setBody(
					"""
					{
					  "name": "CXXX",
					  "manifestVersion": 1,
					  "pluginLists": ["plugins.json"]
					}
					""".trimIndent(),
				),
			)
			server.enqueue(
				MockResponse().setBody(
					"""
					[
					  {
					    "url": "plugins/test.cs3",
					    "version": 3,
					    "apiVersion": 1,
					    "name": "CXXX Test",
					    "internalName": "cxxx_test",
					    "language": "en",
					    "iconUrl": "icons/test.png"
					  }
					]
					""".trimIndent(),
				),
			)

			val metadataUrl = server.url("/builds/CXXX.json").toString()
			val repo = ExternalExtensionRepo(
				type = ExternalExtensionType.CLOUDSTREAM,
				baseUrl = metadataUrl,
				name = "CXXX",
				shortName = "CXXX",
				website = "https://github.com/phisher98/CXXX",
				signingKeyFingerprint = "cxxx",
				createdAt = 1L,
				updatedAt = 1L,
				lastSuccessAt = 1L,
				lastError = null,
			)

			val extensions = service.fetchAvailableExtensionsOrThrow(repo)

			extensions shouldHaveSize 1
			extensions.first().archiveUrl shouldBe server.url("/builds/plugins/test.cs3").toString()
			extensions.first().iconUrl shouldBe server.url("/builds/icons/test.png").toString()
			server.takeRequest().path shouldBe "/builds/CXXX.json"
			server.takeRequest().path shouldBe "/builds/plugins.json"
		}
	}

	test("fetchRepoDetails discovers custom cloudstream metadata file from github contents api") {
		runBlocking {
			val baseUrl = "https://raw.githubusercontent.com/example/CXXX/builds"
			val apiUrl = "https://api.github.com/repos/example/CXXX/contents?ref=builds"
			val client = OkHttpClient.Builder()
				.addInterceptor { chain ->
					val url = chain.request().url.toString()
					when (url) {
						"$baseUrl/repo.json" -> okhttp3.Response.Builder()
							.request(chain.request())
							.protocol(okhttp3.Protocol.HTTP_1_1)
							.code(404)
							.message("Not Found")
							.body("404".toResponseBody("text/plain".toMediaType()))
							.build()
						"$baseUrl/repo" -> okhttp3.Response.Builder()
							.request(chain.request())
							.protocol(okhttp3.Protocol.HTTP_1_1)
							.code(404)
							.message("Not Found")
							.body("404".toResponseBody("text/plain".toMediaType()))
							.build()
						apiUrl -> okhttp3.Response.Builder()
							.request(chain.request())
							.protocol(okhttp3.Protocol.HTTP_1_1)
							.code(200)
							.message("OK")
							.body(
								"""
								[
								  {"name":"plugins.json","type":"file","download_url":"$baseUrl/plugins.json"},
								  {"name":"CXXX.json","type":"file","download_url":"$baseUrl/CXXX.json"}
								]
								""".trimIndent().toResponseBody("application/json".toMediaType())
							)
							.build()
						"$baseUrl/CXXX.json" -> okhttp3.Response.Builder()
							.request(chain.request())
							.protocol(okhttp3.Protocol.HTTP_1_1)
							.code(200)
							.message("OK")
							.body(
								"""
								{
								  "name": "CXXX",
								  "manifestVersion": 1,
								  "pluginLists": ["plugins.json"]
								}
								""".trimIndent().toResponseBody("application/json".toMediaType())
							)
							.build()
						else -> error("Unexpected URL: $url")
					}
				}
				.build()
			val discoveryService = ExtensionRepoService(
				httpClient = client,
				json = Json {
					ignoreUnknownKeys = true
					isLenient = true
					encodeDefaults = true
					prettyPrint = true
					coerceInputValues = true
				},
				settings = mockk<AppSettings>(relaxed = true),
			)

			val repo = discoveryService.fetchRepoDetails(
				baseUrl = baseUrl,
				type = ExternalExtensionType.CLOUDSTREAM,
			)

			repo.baseUrl shouldBe "$baseUrl/CXXX.json"
			repo.name shouldBe "CXXX"
		}
	}
})

private fun ByteArray.gzip(): ByteArray {
	return ByteArrayOutputStream().use { output ->
		GZIPOutputStream(output).use { gzip -> gzip.write(this) }
		output.toByteArray()
	}
}
