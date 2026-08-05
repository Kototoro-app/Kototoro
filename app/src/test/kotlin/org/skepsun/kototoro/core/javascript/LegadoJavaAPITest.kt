package org.skepsun.kototoro.core.javascript

import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldEndWith
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.skepsun.kototoro.browser.OpenUrlConfirmActivity
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.CookieManager
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourcesActivity

/**
 * 测试 LegadoJavaAPI 类
 * 
 * 验证所有 Legado API 方法的正确性
 */
class LegadoJavaAPITest : FunSpec({
    
    lateinit var httpClient: LegadoHttpClient
    lateinit var cookieManager: CookieManager
    lateinit var context: Context
    lateinit var api: LegadoJavaAPI
    lateinit var defaultPrefs: SharedPreferences
    lateinit var defaultPrefsEditor: SharedPreferences.Editor
    lateinit var readerPrefs: SharedPreferences
    lateinit var readerPrefsEditor: SharedPreferences.Editor
    lateinit var connectivityManager: ConnectivityManager
    lateinit var defaultPrefStore: MutableMap<String, Any?>
    lateinit var readerPrefStore: MutableMap<String, Any?>
    
    beforeTest {
        httpClient = mockk(relaxed = true)
        cookieManager = mockk()
        context = mockk<AppCompatActivity>(relaxed = true)
        defaultPrefs = mockk(relaxed = true)
        defaultPrefsEditor = mockk(relaxed = true)
        readerPrefs = mockk(relaxed = true)
        readerPrefsEditor = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        defaultPrefStore = linkedMapOf()
        readerPrefStore = linkedMapOf()
        every { context.packageName } returns "org.skepsun.kototoro.test"
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { context.getSharedPreferences("novel_reader_settings", Context.MODE_PRIVATE) } returns readerPrefs

        mockkStatic(PreferenceManager::class)
        every { PreferenceManager.getDefaultSharedPreferences(context) } returns defaultPrefs

        every { defaultPrefs.all } answers { HashMap(defaultPrefStore) }
        every { defaultPrefs.getString(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] as? String ?: secondArg<String?>()
        }
        every { defaultPrefs.getBoolean(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] as? Boolean ?: secondArg<Boolean>()
        }
        every { defaultPrefs.getInt(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] as? Int ?: secondArg<Int>()
        }
        every { defaultPrefs.getLong(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] as? Long ?: secondArg<Long>()
        }
        every { defaultPrefs.getFloat(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] as? Float ?: secondArg<Float>()
        }
        every { defaultPrefs.edit() } returns defaultPrefsEditor
        every { defaultPrefsEditor.putString(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] = secondArg<String?>()
            defaultPrefsEditor
        }
        every { defaultPrefsEditor.putBoolean(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] = secondArg<Boolean>()
            defaultPrefsEditor
        }
        every { defaultPrefsEditor.putInt(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] = secondArg<Int>()
            defaultPrefsEditor
        }
        every { defaultPrefsEditor.putLong(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] = secondArg<Long>()
            defaultPrefsEditor
        }
        every { defaultPrefsEditor.putFloat(any(), any()) } answers {
            defaultPrefStore[firstArg<String>()] = secondArg<Float>()
            defaultPrefsEditor
        }
        every { defaultPrefsEditor.remove(any()) } answers {
            defaultPrefStore.remove(firstArg<String>())
            defaultPrefsEditor
        }
        every { defaultPrefsEditor.apply() } answers { Unit }

        every { readerPrefs.all } answers { HashMap(readerPrefStore) }
        every { readerPrefs.getString(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] as? String ?: secondArg<String?>()
        }
        every { readerPrefs.getBoolean(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] as? Boolean ?: secondArg<Boolean>()
        }
        every { readerPrefs.getInt(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] as? Int ?: secondArg<Int>()
        }
        every { readerPrefs.getLong(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] as? Long ?: secondArg<Long>()
        }
        every { readerPrefs.getFloat(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] as? Float ?: secondArg<Float>()
        }
        every { readerPrefs.edit() } returns readerPrefsEditor
        every { readerPrefsEditor.putString(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] = secondArg<String?>()
            readerPrefsEditor
        }
        every { readerPrefsEditor.putBoolean(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] = secondArg<Boolean>()
            readerPrefsEditor
        }
        every { readerPrefsEditor.putInt(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] = secondArg<Int>()
            readerPrefsEditor
        }
        every { readerPrefsEditor.putLong(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] = secondArg<Long>()
            readerPrefsEditor
        }
        every { readerPrefsEditor.putFloat(any(), any()) } answers {
            readerPrefStore[firstArg<String>()] = secondArg<Float>()
            readerPrefsEditor
        }
        every { readerPrefsEditor.remove(any()) } answers {
            readerPrefStore.remove(firstArg<String>())
            readerPrefsEditor
        }
        every { readerPrefsEditor.apply() } answers { Unit }
        
        // Mock Settings.Secure for androidId()
        mockkStatic(Settings.Secure::class)
        every { 
            Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) 
        } returns "test_android_id"
        
        api = LegadoJavaAPI(httpClient, cookieManager, context)
    }
    
    test("ajax GET request") {
        // Given
        val url = "https://example.com/test"
        val responseBody = "Test response"
        val mockResponse = createMockResponse(url, responseBody)

        coEvery { httpClient.get(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockResponse
        
        // When
        val result = api.ajax(url)
        
        // Then
        result shouldBe responseBody
        coVerify(exactly = 1) {
            httpClient.get(
                url = url,
                headers = any(),
                source = isNull(),
                proxy = isNull(),
                dnsIp = isNull(),
                enableCookieJar = true,
                readTimeoutMs = isNull(),
                callTimeoutMs = isNull(),
            )
        }
    }
    
    test("ajax POST request with options") {
        // Given
        val url = "https://example.com/test"
        val responseBody = "Post response"
        val mockResponse = createMockResponse(url, responseBody)
        val options = mapOf(
            "method" to "POST",
            "body" to "key1=value1&key2=value2",
            "headers" to mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )
        
        coEvery { httpClient.post(any(), any<RequestBody>(), any(), any(), any(), any(), any(), any(), any()) } returns mockResponse
        
        // When
        val result = api.ajax(url, options)
        
        // Then
        result shouldBe responseBody
        coVerify(exactly = 1) {
            httpClient.post(
                url = url,
                body = any<RequestBody>(),
                headers = any(),
                source = isNull(),
                proxy = isNull(),
                dnsIp = isNull(),
                enableCookieJar = true,
                readTimeoutMs = isNull(),
                callTimeoutMs = isNull(),
            )
        }
    }

    test("connect 应把 callTimeout 传递到底层请求执行") {
        val url = "https://example.com/test"
        val responseBody = "Timeout response"
        val mockResponse = createMockResponse(url, responseBody)

        coEvery { httpClient.get(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockResponse

        val result = api.connect(url, null, 1234L)

        result.body() shouldBe responseBody
        coVerify(exactly = 1) {
            httpClient.get(
                url = url,
                headers = any(),
                source = isNull(),
                proxy = isNull(),
                dnsIp = isNull(),
                enableCookieJar = true,
                readTimeoutMs = isNull(),
                callTimeoutMs = 1234L,
            )
        }
    }

    test("connect 的 headers 参数应作为默认请求头传递") {
        val url = "https://example.com/test"
        val responseBody = "Header response"
        val mockResponse = createMockResponse(url, responseBody)
        val headers = """{"X-Test":"1","Referer":"https://ref.example.com/"}"""

        coEvery { httpClient.get(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockResponse

        val result = api.connect(url, headers, 2345L)

        result.body() shouldBe responseBody
        coVerify(exactly = 1) {
            httpClient.get(
                url = url,
                headers = match {
                    it["X-Test"] == "1" &&
                        it["Referer"] == "https://ref.example.com/"
                },
                source = isNull(),
                proxy = isNull(),
                dnsIp = isNull(),
                enableCookieJar = true,
                readTimeoutMs = isNull(),
                callTimeoutMs = 2345L,
            )
        }
    }

    test("StrResponse.headers 应返回与 MD3 对齐的 okhttp Headers") {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .headers(
                Headers.Builder()
                    .add("Content-Type", "text/html")
                    .add("Set-Cookie", "a=1")
                    .build(),
            )
            .body("ok".toResponseBody("text/plain".toMediaType()))
            .build()

        val strResponse = StrResponse(response, "ok")

        strResponse.headers().get("Content-Type") shouldBe "text/html"
        strResponse.headers().get("Set-Cookie") shouldBe "a=1"
    }

    test("StrResponse 应同时暴露 MD3 风格属性与方法") {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("ok".toResponseBody("text/plain".toMediaType()))
            .build()

        val strResponse = StrResponse(response, "payload")
        strResponse.putCallTime(123)

        strResponse.body shouldBe "payload"
        strResponse.body() shouldBe "payload"
        strResponse.url shouldBe "https://example.com/test"
        strResponse.url() shouldBe "https://example.com/test"
        strResponse.raw shouldBe response
        strResponse.raw() shouldBe response
        strResponse.callTime shouldBe 123
        strResponse.callTime() shouldBe 123
    }

    test("ajaxAll 应并发执行多个请求以对齐 MD3") {
        val activeRequests = AtomicInteger(0)
        val peakConcurrency = AtomicInteger(0)

        coEvery { httpClient.get(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val current = activeRequests.incrementAndGet()
            while (true) {
                val peak = peakConcurrency.get()
                if (current <= peak || peakConcurrency.compareAndSet(peak, current)) {
                    break
                }
            }
            delay(120)
            activeRequests.decrementAndGet()
            createMockResponse(firstArg(), "ok:${firstArg<String>()}")
        }

        val result = api.ajaxAll(
            arrayOf(
                "https://example.com/a",
                "https://example.com/b",
                "https://example.com/c",
            ),
        )

        result.map { it.body() } shouldBe listOf(
            "ok:https://example.com/a",
            "ok:https://example.com/b",
            "ok:https://example.com/c",
        )
        peakConcurrency.get() shouldBe 3
    }

    test("ajaxTestAll 应并发执行并把 timeout 传到底层请求") {
        val activeRequests = AtomicInteger(0)
        val peakConcurrency = AtomicInteger(0)

        coEvery { httpClient.get(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val current = activeRequests.incrementAndGet()
            while (true) {
                val peak = peakConcurrency.get()
                if (current <= peak || peakConcurrency.compareAndSet(peak, current)) {
                    break
                }
            }
            delay(120)
            activeRequests.decrementAndGet()
            createMockResponse(firstArg(), "timeout:${arg<Long?>(7)}")
        }

        val result = api.ajaxTestAll(
            arrayOf(
                "https://example.com/a",
                "https://example.com/b",
            ),
            3456,
        )

        result.map { it.body() } shouldBe listOf(
            "timeout:3456",
            "timeout:3456",
        )
        peakConcurrency.get() shouldBe 2
        coVerify(exactly = 2) {
            httpClient.get(
                url = any(),
                headers = any(),
                source = isNull(),
                proxy = isNull(),
                dnsIp = isNull(),
                enableCookieJar = true,
                readTimeoutMs = isNull(),
                callTimeoutMs = 3456L,
            )
        }
    }

    test("ajaxAll 的 skipRateLimit=false 应遵守源并发限制，而 true 应绕过") {
        val source = LegadoBookSource(
            bookSourceName = "Rate Limited Source",
            bookSourceUrl = "https://example.com/source",
            concurrentRate = "1000",
        )
        api.jsContext = JavaScriptContext(
            source = source,
            baseUrl = source.bookSourceUrl,
        )

        coEvery { httpClient.get(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(120)
            createMockResponse(firstArg(), "ok:${firstArg<String>()}")
        }

        val limitedElapsed = measureTimeMillis {
            api.ajaxAll(
                arrayOf(
                    "https://example.com/a",
                    "https://example.com/b",
                ),
                false,
            )
        }
        val skippedElapsed = measureTimeMillis {
            api.ajaxAll(
                arrayOf(
                    "https://example.com/c",
                    "https://example.com/d",
                ),
                true,
            )
        }

        limitedElapsed.shouldBeGreaterThanOrEqual(950L)
        skippedElapsed.shouldBeLessThan(900L)
    }
    
    test("setContent and getElement") {
        // Given
        val html = """
            <html>
                <body>
                    <div class="title">Test Title</div>
                    <div class="content">Test Content</div>
                </body>
            </html>
        """.trimIndent()
        
        // When
        api.setContent(html)
        val elements = api.getElement(".title")
        
        // Then
        elements.size shouldBe 1
        elements.first()?.text() shouldBe "Test Title"
    }
    
    test("base64Encode") {
        // Given
        val input = "Hello, World!"
        
        // When
        val result = api.base64Encode(input)
        
        // Then
        result shouldBe "SGVsbG8sIFdvcmxkIQ=="
    }
    
    test("base64Decode") {
        // Given
        val input = "SGVsbG8sIFdvcmxkIQ=="
        
        // When
        val result = api.base64Decode(input)
        
        // Then
        result shouldBe "Hello, World!"
    }
    
    test("hexDecodeToString") {
        // Given
        val hex = "48656c6c6f" // "Hello" in hex
        
        // When
        val result = api.hexDecodeToString(hex)
        
        // Then
        result shouldBe "Hello"
    }

    test("createSymmetricCrypto 应支持 AES 基本加解密往返") {
        val crypto = api.createSymmetricCrypto("AES/ECB/PKCS5Padding", "1234567890abcdef")

        val encryptedBase64 = crypto.encryptBase64("hello-md3")
        val decrypted = crypto.decryptStr(encryptedBase64)

        decrypted shouldBe "hello-md3"
    }

    test("createSymmetricCrypto 应兼容 PKCS7Padding 写法") {
        val crypto = api.createSymmetricCrypto("AES/CBC/PKCS7Padding", "1234567890abcdef", "abcdef1234567890")

        val encryptedBase64 = crypto.encryptBase64("cbc-mode")
        val decrypted = crypto.decryptStr(encryptedBase64)

        decrypted shouldBe "cbc-mode"
    }

    test("createSymmetricCrypto.decrypt 应同时支持 Base64 和 Hex 密文输入") {
        val crypto = api.createSymmetricCrypto("AES/ECB/PKCS5Padding", "1234567890abcdef")

        val encryptedBase64 = crypto.encryptBase64("hex-or-base64")
        val encryptedHex = crypto.encryptHex("hex-or-base64")

        crypto.decryptStr(encryptedBase64) shouldBe "hex-or-base64"
        crypto.decryptStr(encryptedHex) shouldBe "hex-or-base64"
    }

    test("digestHex 应与 MD3 一致返回十六进制摘要") {
        api.digestHex("abc", "MD5") shouldBe "900150983cd24fb0d6963f7d28e17f72"
        api.digestHex("abc", "SHA-256") shouldBe
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    test("digestBase64Str 应与 MD3 一致返回 Base64 摘要") {
        api.digestBase64Str("abc", "MD5") shouldBe "kAFQmDzST7DWlj99KOF/cg=="
        api.digestBase64Str("abc", "SHA-256") shouldBe "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="
    }

    test("HMacHex 与 HMacBase64 应与 MD3 一致") {
        api.HMacHex("abc", "HmacSHA256", "key") shouldBe
            "9c196e32dc0175f86f4b1cb89289d6619de6bee699e4c378e68309ed97a1a6ab"
        api.HMacBase64("abc", "HmacSHA256", "key") shouldBe "nBluMtwBdfhvSxy4konWYZ3mvuaZ5MN45oMJ7Zehpqs="
    }

    test("createAsymmetricCrypto 应支持 RSA 公钥加密和私钥解密") {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val crypto = api.createAsymmetricCrypto("RSA/ECB/PKCS1Padding")
            .setPublicKey(keyPair.public.encoded)
            .setPrivateKey(keyPair.private.encoded)

        val encryptedBase64 = crypto.encryptBase64("rsa-md3", true)
        val decrypted = crypto.decryptStr(encryptedBase64, false)

        decrypted shouldBe "rsa-md3"
    }

    test("createSign 应支持 RSA 签名并可被 JCA 验证") {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val sign = api.createSign("SHA256withRSA")
            .setPublicKey(keyPair.public.encoded)
            .setPrivateKey(keyPair.private.encoded)

        val signatureBytes = sign.sign("sign-md3")
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update("sign-md3".toByteArray())
        }

        verifier.verify(signatureBytes) shouldBe true
        sign.signHex("sign-md3").length shouldBe signatureBytes.size * 2
    }

    test("旧式 AES Base64 参数包装函数应与 createSymmetricCrypto 对齐") {
        val keyBytes = "1234567890abcdef".toByteArray()
        val ivBytes = "abcdef1234567890".toByteArray()
        val crypto = api.createSymmetricCrypto("AES/CBC/PKCS5Padding", keyBytes, ivBytes)
        val encrypted = crypto.encryptBase64("legacy-aes")

        val decrypted = api.aesDecodeArgsBase64Str(
            data = encrypted,
            key = java.util.Base64.getEncoder().encodeToString(keyBytes),
            mode = "CBC",
            padding = "PKCS5Padding",
            iv = java.util.Base64.getEncoder().encodeToString(ivBytes),
        )

        decrypted shouldBe "legacy-aes"
        api.aesEncodeToBase64String("legacy-aes", "1234567890abcdef", "AES/CBC/PKCS5Padding", "abcdef1234567890")
            ?.isNotBlank() shouldBe true
    }

    test("旧式 3DES 包装函数应与 createSymmetricCrypto 对齐") {
        val key = "123456789012345678901234"
        val iv = "12345678"
        val encoded = api.tripleDESEncodeBase64Str(
            data = "legacy-3des",
            key = key,
            mode = "CBC",
            padding = "PKCS5Padding",
            iv = iv,
        )

        encoded shouldBe api.createSymmetricCrypto("DESede/CBC/PKCS5Padding", key, iv).encryptBase64("legacy-3des")
        api.tripleDESDecodeStr(
            data = encoded.orEmpty(),
            key = key,
            mode = "CBC",
            padding = "PKCS5Padding",
            iv = iv,
        ) shouldBe "legacy-3des"
    }
    
    test("timeFormat") {
        // Given
        val timestamp = 1609459200000L // 2021-01-01 00:00:00 UTC
        val format = "yyyy-MM-dd"
        
        // When
        val result = api.timeFormat(timestamp, format, "UTC")
        
        // Then
        result shouldBe "2021-01-01"
    }
    
    test("timeFormatUTC with positive offset") {
        // Given
        val timestamp = 1609459200000L // 2021-01-01 00:00:00 UTC
        val format = "yyyy-MM-dd HH:mm:ss"
        val offset = 8 // GMT+8
        
        // When
        val result = api.timeFormatUTC(timestamp, format, offset)
        
        // Then
        result shouldStartWith "2021-01-01"
    }
    
    test("timeFormatUTC with negative offset") {
        // Given
        val timestamp = 1609459200000L // 2021-01-01 00:00:00 UTC
        val format = "yyyy-MM-dd HH:mm:ss"
        val offset = -5 // GMT-5
        
        // When
        val result = api.timeFormatUTC(timestamp, format, offset)
        
        // Then
        result shouldStartWith "2020-12-31"
    }
    
    test("androidId") {
        // When
        val result = api.androidId()
        
        // Then
        result shouldBe "test_android_id"
    }

    test("toURL 应返回与 MD3 对齐的 JsURL 对象字段") {
        val parsed = api.toURL("https://example.com:8443/book/detail?id=1&name=%E4%B9%A6")

        parsed.href shouldBe "https://example.com:8443/book/detail?id=1&name=%E4%B9%A6"
        parsed.host shouldBe "example.com"
        parsed.origin shouldBe "https://example.com:8443"
        parsed.pathname shouldBe "/book/detail"
        parsed.search shouldBe "?id=1&name=%E4%B9%A6"
        parsed.searchParams shouldBe mapOf(
            "id" to "1",
            "name" to "书",
        )
        parsed.toString() shouldBe parsed.href
    }

    test("toURL 应支持基于 baseUrl 解析相对地址") {
        val parsed = api.toURL("../chapter/2.html?from=list", "https://example.com/book/1/index.html")

        parsed.href shouldBe "https://example.com/book/chapter/2.html?from=list"
        parsed.origin shouldBe "https://example.com"
        parsed.pathname shouldBe "/book/chapter/2.html"
        parsed.searchParams shouldBe mapOf("from" to "list")
    }

    test("webViewGetSource 应通过 WebView 嗅探接口返回匹配资源 URL") {
        val source = LegadoBookSource(
            bookSourceName = "WebView Source",
            bookSourceUrl = "https://source.example.com",
            header = """{"User-Agent":"Custom-UA","Referer":"https://ref.example.com/"}""",
        )
        val apiWithSource = LegadoJavaAPI(httpClient, cookieManager, context).apply {
            jsContext = JavaScriptContext(source = source)
        }
        coEvery {
            httpClient.getWithWebView(
                url = "https://example.com/page",
                headers = match {
                    it["User-Agent"] == "Custom-UA" &&
                        it["Referer"] == "https://ref.example.com/"
                },
                delayMs = 1000L,
                webJs = "document.querySelector('button')?.click()",
                sourceRegex = ".*audio\\.mp3",
                blockImages = true,
            )
        } returns LegadoHttpResponse(
            url = "https://example.com/page",
            body = "https://cdn.example.com/audio.mp3",
            code = 200,
        )

        val result = apiWithSource.webViewGetSource(
            html = null,
            url = "https://example.com/page",
            js = "document.querySelector('button')?.click()",
            sourceRegex = ".*audio\\.mp3",
            cacheFirst = false,
            delayTime = 1000L,
        )

        result shouldBe "https://cdn.example.com/audio.mp3"
    }

    test("webView 应在 HTML 模式下沿用 source headers 中的 User-Agent") {
        val source = LegadoBookSource(
            bookSourceName = "WebView Source",
            bookSourceUrl = "https://source.example.com",
            header = """{"User-Agent":"Custom-UA","Referer":"https://ref.example.com/"}""",
        )
        val apiWithSource = LegadoJavaAPI(httpClient, cookieManager, context).apply {
            jsContext = JavaScriptContext(source = source)
        }
        coEvery {
            httpClient.loadHtmlWithWebView(
                html = "<html></html>",
                baseUrl = "https://example.com/page",
                delayMs = 3000L,
                webJs = "document.title",
                userAgent = "Custom-UA",
            )
        } returns "ok"

        val result = apiWithSource.webView(
            html = "<html></html>",
            url = "https://example.com/page",
            js = "document.title",
            cacheFirst = false,
        )

        result shouldBe "ok"
    }

    test("webViewGetOverrideUrl 应通过 WebView 跳转拦截接口返回匹配 URL") {
        val source = LegadoBookSource(
            bookSourceName = "WebView Source",
            bookSourceUrl = "https://source.example.com",
            header = """{"User-Agent":"Custom-UA","Referer":"https://ref.example.com/"}""",
        )
        val apiWithSource = LegadoJavaAPI(httpClient, cookieManager, context).apply {
            jsContext = JavaScriptContext(source = source)
        }
        coEvery {
            httpClient.getWebViewOverrideUrl(
                url = "https://example.com/page",
                headers = match {
                    it["User-Agent"] == "Custom-UA" &&
                        it["Referer"] == "https://ref.example.com/"
                },
                delayMs = 1000L,
                webJs = "document.querySelector('a')?.click()",
                overrideUrlRegex = ".*token=.*",
                blockImages = true,
            )
        } returns LegadoHttpResponse(
            url = "https://example.com/page",
            body = "https://example.com/callback?token=abc",
            code = 200,
        )

        val result = apiWithSource.webViewGetOverrideUrl(
            html = null,
            url = "https://example.com/page",
            js = "document.querySelector('a')?.click()",
            overrideUrlRegex = ".*token=.*",
            cacheFirst = false,
            delayTime = 1000L,
        )

        result shouldBe "https://example.com/callback?token=abc"
    }

    test("downloadFile 应优先使用 Legado url option 中的 type 作为扩展名") {
        val url = """https://example.com/download,{"type":"mp3"}"""
        val responseBody = "AUDIO-DATA"
        val mockResponse = createMockResponse("https://example.com/download", responseBody)

        coEvery { httpClient.get(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockResponse

        val relativePath = api.downloadFile(url)

        relativePath.shouldEndWith(".mp3")
    }

    test("downloadFile(content, url) 在缺失 type 时应与 MD3 一致返回空串") {
        val result = api.downloadFile("48656c6c6f", "https://example.com/download.bin")

        result shouldBe ""
    }

    test("getZipStringContent 应支持 data URI 输入以对齐 MD3 AnalyzeUrl 字节语义") {
        val zipBytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("folder/test.txt"))
                zip.write("Hello Zip".toByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }
        val dataUri = "data:application/zip;base64," + Base64.getEncoder().encodeToString(zipBytes)

        val content = api.getZipStringContent(dataUri, "folder/test.txt")

        content shouldBe "Hello Zip"
    }

    test("downloadFile 应支持 data URI 输入以对齐 MD3 AnalyzeUrl.getInputStream 语义") {
        every { context.externalCacheDir } returns File(System.getProperty("java.io.tmpdir"), "legado-javaapi-test")
        val dataUri = "data:text/plain;base64," + Base64.getEncoder().encodeToString("hello".toByteArray())

        val relativePath = api.downloadFile(dataUri)
        val savedFile = File((context.externalCacheDir ?: context.cacheDir), relativePath.removePrefix(File.separator))

        savedFile.exists() shouldBe true
        savedFile.readText() shouldBe "hello"
    }

    test("cacheFile 首次应按字节写入并二次命中本地缓存") {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "legado-javaapi-cache-test").apply {
            deleteRecursively()
            mkdirs()
        }
        every { context.externalCacheDir } returns cacheDir
        val responseBody = "缓存内容"
        val mockResponse = createMockResponse("https://example.com/script.js", responseBody)
        coEvery { httpClient.get(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockResponse

        val first = api.cacheFile("https://example.com/script.js", 3600)
        val second = api.cacheFile("https://example.com/script.js", 3600)

        first shouldBe responseBody
        second shouldBe responseBody
        coVerify(exactly = 1) {
            httpClient.get(
                url = "https://example.com/script.js",
                headers = any(),
                source = isNull(),
                proxy = isNull(),
                dnsIp = isNull(),
                enableCookieJar = true,
                readTimeoutMs = isNull(),
                callTimeoutMs = isNull(),
            )
        }
    }

    test("getTxtInFolder 应拼接目录内文本并在读取后删除目录") {
        val root = File(System.getProperty("java.io.tmpdir"), "legado-javaapi-folder-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val folder = File(root, "book").apply { mkdirs() }
        File(folder, "1.txt").writeText("Alpha", Charsets.UTF_8)
        File(folder, "2.txt").writeText("Beta", Charsets.UTF_8)
        every { context.externalCacheDir } returns root

        val content = api.getTxtInFolder("book")

        content shouldBe "Alpha\nBeta"
        folder.exists() shouldBe false
    }

    test("deleteFile 应递归删除目录以对齐 MD3") {
        val root = File(System.getProperty("java.io.tmpdir"), "legado-javaapi-delete-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val folder = File(root, "nested").apply { mkdirs() }
        File(folder, "a.txt").writeText("A", Charsets.UTF_8)
        File(folder, "b.txt").writeText("B", Charsets.UTF_8)
        every { context.externalCacheDir } returns root

        api.deleteFile("nested") shouldBe true
        folder.exists() shouldBe false
    }

    test("queryTTF 应支持本地相对文件路径以对齐 MD3") {
        val sourceFont = File("app/build/intermediates/assets/debug/mergeDebugAssets/subfont.ttf")
        if (!sourceFont.exists()) return@test
        val root = File(System.getProperty("java.io.tmpdir"), "legado-javaapi-font-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val targetFont = File(root, "font.ttf")
        targetFont.writeBytes(sourceFont.readBytes())
        every { context.externalCacheDir } returns root

        api.queryTTF("font.ttf") shouldNotBe null
    }

    test("get 在启用 cookieJar 时应自动注入持久化 Cookie 头") {
        val cookieJar = mockk<org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar>()
        every { cookieJar.getCookieHeader("https://example.com/data") } returns "sid=1; uid=2"
        mockkStatic(Jsoup::class)
        val connection = mockk<Connection>(relaxed = true)
        val response = mockk<Connection.Response>()
        val headerSlot = slot<Map<String, String>>()
        every { Jsoup.connect("https://example.com/data") } returns connection
        every { connection.timeout(any()) } returns connection
        every { connection.ignoreContentType(true) } returns connection
        every { connection.followRedirects(false) } returns connection
        every { connection.headers(capture(headerSlot)) } returns connection
        every { connection.method(Connection.Method.GET) } returns connection
        every { connection.execute() } returns response

        val apiWithJar = LegadoJavaAPI(httpClient, cookieManager, context, cookieJar).apply {
            jsContext = JavaScriptContext(
                source = LegadoBookSource(
                    bookSourceName = "Cookie Source",
                    bookSourceUrl = "https://example.com",
                    enabledCookieJar = true,
                ),
            )
        }

        apiWithJar.get("https://example.com/data", emptyMap())

        headerSlot.captured["Cookie"] shouldBe "sid=1; uid=2"
        verify(exactly = 1) { connection.execute() }
    }

    test("get 在显式传入 Cookie 头时不应重复拼接持久化 Cookie") {
        val cookieJar = mockk<org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar>()
        every { cookieJar.getCookieHeader("https://example.com/data") } returns "sid=1; uid=2"
        mockkStatic(Jsoup::class)
        val connection = mockk<Connection>(relaxed = true)
        val response = mockk<Connection.Response>()
        val headerSlot = slot<Map<String, String>>()
        every { Jsoup.connect("https://example.com/data") } returns connection
        every { connection.timeout(any()) } returns connection
        every { connection.ignoreContentType(true) } returns connection
        every { connection.followRedirects(false) } returns connection
        every { connection.headers(capture(headerSlot)) } returns connection
        every { connection.method(Connection.Method.GET) } returns connection
        every { connection.execute() } returns response

        val apiWithJar = LegadoJavaAPI(httpClient, cookieManager, context, cookieJar).apply {
            jsContext = JavaScriptContext(
                source = LegadoBookSource(
                    bookSourceName = "Cookie Source",
                    bookSourceUrl = "https://example.com",
                    enabledCookieJar = true,
                ),
            )
        }

        apiWithJar.get("https://example.com/data", mapOf("Cookie" to "manual=1"))

        headerSlot.captured["Cookie"] shouldBe "manual=1"
        verify(exactly = 1) { connection.execute() }
    }

    test("putCookie 应对齐 MD3 暴露替换式 Cookie 写入口") {
        val cookieJar = mockk<org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar>(relaxed = true)
        every { cookieJar.getCookieHeader("https://example.com/data") } returns "session=old"
        val apiWithJar = LegadoJavaAPI(httpClient, cookieManager, context, cookieJar)

        apiWithJar.putCookie("https://example.com/data", "session=new; token=xyz")

        verify(exactly = 1) {
            cookieJar.setCookies(
                eq("https://example.com/data"),
                match { cookies ->
                    cookies.any { it.name == "session" && it.value == "new" } &&
                        cookies.any { it.name == "token" && it.value == "xyz" }
                },
            )
        }
    }

    test("removeCookie 应对齐 MD3 暴露 Cookie 清理入口") {
        val cookieJar = mockk<org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar>(relaxed = true)
        val apiWithJar = LegadoJavaAPI(httpClient, cookieManager, context, cookieJar)

        apiWithJar.removeCookie("https://example.com/data")

        verify(exactly = 1) { cookieJar.removeCookies("https://example.com/data") }
    }

    test("head 在启用 cookieJar 时应自动注入持久化 Cookie 并使用 HEAD 方法") {
        val cookieJar = mockk<org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar>()
        every { cookieJar.getCookieHeader("https://example.com/head") } returns "sid=3"
        mockkStatic(Jsoup::class)
        val connection = mockk<Connection>(relaxed = true)
        val response = mockk<Connection.Response>()
        val headerSlot = slot<Map<String, String>>()
        every { Jsoup.connect("https://example.com/head") } returns connection
        every { connection.timeout(any()) } returns connection
        every { connection.ignoreContentType(true) } returns connection
        every { connection.followRedirects(false) } returns connection
        every { connection.headers(capture(headerSlot)) } returns connection
        every { connection.method(Connection.Method.HEAD) } returns connection
        every { connection.execute() } returns response

        val apiWithJar = LegadoJavaAPI(httpClient, cookieManager, context, cookieJar).apply {
            jsContext = JavaScriptContext(
                source = LegadoBookSource(
                    bookSourceName = "Cookie Source",
                    bookSourceUrl = "https://example.com",
                    enabledCookieJar = true,
                ),
            )
        }

        apiWithJar.head("https://example.com/head", emptyMap())

        headerSlot.captured["Cookie"] shouldBe "sid=3"
        verify(exactly = 1) { connection.method(Connection.Method.HEAD) }
        verify(exactly = 1) { connection.execute() }
    }

    test("post 在显式传入 Cookie 头时不应重复拼接持久化 Cookie 且应设置 requestBody") {
        val cookieJar = mockk<org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar>()
        every { cookieJar.getCookieHeader("https://example.com/post") } returns "sid=4"
        mockkStatic(Jsoup::class)
        val connection = mockk<Connection>(relaxed = true)
        val response = mockk<Connection.Response>()
        val headerSlot = slot<Map<String, String>>()
        val bodySlot = slot<String>()
        every { Jsoup.connect("https://example.com/post") } returns connection
        every { connection.timeout(any()) } returns connection
        every { connection.ignoreContentType(true) } returns connection
        every { connection.followRedirects(false) } returns connection
        every { connection.headers(capture(headerSlot)) } returns connection
        every { connection.method(Connection.Method.POST) } returns connection
        every { connection.requestBody(capture(bodySlot)) } returns connection
        every { connection.execute() } returns response

        val apiWithJar = LegadoJavaAPI(httpClient, cookieManager, context, cookieJar).apply {
            jsContext = JavaScriptContext(
                source = LegadoBookSource(
                    bookSourceName = "Cookie Source",
                    bookSourceUrl = "https://example.com",
                    enabledCookieJar = true,
                ),
            )
        }

        apiWithJar.post(
            "https://example.com/post",
            "k=v",
            mapOf("Cookie" to "manual=1", "Content-Type" to "application/x-www-form-urlencoded"),
        )

        headerSlot.captured["Cookie"] shouldBe "manual=1"
        bodySlot.captured shouldBe "k=v"
        verify(exactly = 1) { connection.method(Connection.Method.POST) }
        verify(exactly = 1) { connection.execute() }
    }

    test("openUrl 在特殊 legado 导入链接时应跳转到统一源导入页") {
        mockkStatic(Uri::class)
        mockkObject(UnifiedSourcesActivity.Companion)
        val importUri = mockk<Uri>()
        val intentSlot = slot<Intent>()
        every { Uri.parse("legado://import/bookSource?src=https%3A%2F%2Fexample.com%2Flegado.json") } returns importUri
        every { importUri.scheme } returns "legado"
        every { importUri.getQueryParameter("src") } returns "https://example.com/legado.json"
        every {
            UnifiedSourcesActivity.Companion.newIntent(
                context = any(),
                initialRepositoryKind = UnifiedSourceKind.LEGADO,
                initialRepositoryUrl = "https://example.com/legado.json",
            )
        } answers { Intent() }
        every { context.startActivity(capture(intentSlot)) } returns Unit

        api.openUrl("legado://import/bookSource?src=https%3A%2F%2Fexample.com%2Flegado.json")

        verify(exactly = 1) { context.startActivity(any()) }
        unmockkObject(UnifiedSourcesActivity.Companion)
    }

    test("openUrl 在普通链接缺少 source 上下文时应与 MD3 一致直接报错") {
        mockkStatic(Uri::class)
        val httpUri = mockk<Uri>()
        every { Uri.parse("https://example.com/open") } returns httpUri
        every { httpUri.scheme } returns "https"
        val error = shouldThrow<IllegalArgumentException> {
            api.openUrl("https://example.com/open")
        }

        error.message shouldBe "openUrl source cannot be null"
    }

    test("openUrl 在 http 链接且存在 source 时应跳转到 MD3 风格确认页并携带来源信息") {
        mockkObject(OpenUrlConfirmActivity.Companion)
        val intentSlot = slot<Intent>()
        val apiWithSource = LegadoJavaAPI(httpClient, cookieManager, context).apply {
            jsContext = JavaScriptContext(
                source = LegadoBookSource(
                    bookSourceName = "Test Source",
                    bookSourceUrl = "https://source.example.com",
                ),
                sourceName = "JSON_LEGADO_TEST_SOURCE",
            )
        }
        every {
            OpenUrlConfirmActivity.Companion.newIntent(
                activity = any(),
                url = "https://example.com/open",
                mimeType = null,
                sourceOrigin = "JSON_LEGADO_TEST_SOURCE",
                sourceName = "Test Source",
                sourceType = 0,
            )
        } answers { Intent() }
        every { context.startActivity(capture(intentSlot)) } returns Unit

        apiWithSource.openUrl("https://example.com/open")

        verify(exactly = 1) { context.startActivity(any()) }
        unmockkObject(OpenUrlConfirmActivity.Companion)
    }

    test("openUrl 在带 mimeType 的普通链接时也应走确认页并保留 mimeType") {
        mockkObject(OpenUrlConfirmActivity.Companion)
        val intentSlot = slot<Intent>()
        val apiWithSource = LegadoJavaAPI(httpClient, cookieManager, context).apply {
            jsContext = JavaScriptContext(
                source = LegadoBookSource(
                    bookSourceName = "Pdf Source",
                    bookSourceUrl = "https://pdf-source.example.com",
                ),
                sourceName = "JSON_LEGADO_PDF_SOURCE",
            )
        }
        every {
            OpenUrlConfirmActivity.Companion.newIntent(
                activity = any(),
                url = "https://example.com/file.pdf",
                mimeType = "application/pdf",
                sourceOrigin = "JSON_LEGADO_PDF_SOURCE",
                sourceName = "Pdf Source",
                sourceType = 0,
            )
        } answers { Intent() }
        every { context.startActivity(capture(intentSlot)) } returns Unit

        apiWithSource.openUrl("https://example.com/file.pdf", "application/pdf")

        verify(exactly = 1) { context.startActivity(any()) }
        unmockkObject(OpenUrlConfirmActivity.Companion)
    }

    test("getThemeConfig 应返回 MD3 风格主题配置键面") {
        val config = JSONObject(api.getThemeConfig())
        val keys = buildSet {
            val iterator = config.keys()
            while (iterator.hasNext()) add(iterator.next())
        }

        keys.shouldContainAll(
            "themeName",
            "isNightTheme",
            "primaryColor",
            "accentColor",
            "backgroundColor",
            "bottomBackground",
            "backgroundImgPath",
            "backgroundImgBlur",
        )
    }

    test("getReadBookConfig 应返回 MD3 风格阅读配置高频键面") {
        val config = JSONObject(api.getReadBookConfig())
        val keys = buildSet {
            val iterator = config.keys()
            while (iterator.hasNext()) add(iterator.next())
        }

        keys.shouldContainAll(
            "name",
            "bgStr",
            "bgStrNight",
            "bgStrEInk",
            "bgAlpha",
            "bgType",
            "bgTypeNight",
            "bgTypeEInk",
            "darkStatusIcon",
            "darkStatusIconNight",
            "darkStatusIconEInk",
            "textColor",
            "textColorNight",
            "textColorEInk",
            "textAccentColor",
            "textAccentColorNight",
            "textAccentColorEInk",
            "pageAnim",
            "pageAnimEInk",
            "textFont",
            "titleFont",
            "headerFont",
            "footerFont",
            "headerFontSize",
            "footerFontSize",
            "textBold",
            "textSize",
            "letterSpacing",
            "lineSpacingExtra",
            "paragraphSpacing",
            "paragraphIndent",
            "paddingBottom",
            "paddingLeft",
            "paddingRight",
            "paddingTop",
            "headerPaddingBottom",
            "headerPaddingLeft",
            "headerPaddingRight",
            "headerPaddingTop",
            "footerPaddingBottom",
            "footerPaddingLeft",
            "footerPaddingRight",
            "footerPaddingTop",
            "showHeaderLine",
            "showFooterLine",
            "tipHeaderLeft",
            "tipHeaderMiddle",
            "tipHeaderRight",
            "tipFooterLeft",
            "tipFooterMiddle",
            "tipFooterRight",
            "tipHeaderColor",
            "tipFooterColor",
            "tipDividerColor",
            "headerMode",
            "footerMode",
            "regexColorRules",
        )
    }

    test("startBrowserAwait 应保留浏览器最终跳转 URL，贴近 MD3 语义") {
        val launcher = mockk<BrowserLauncher>()
        val sourceSlot = slot<org.skepsun.kototoro.parsers.model.ContentSource>()
        every {
            launcher.launchAndWait(
                url = "https://example.com/cf",
                title = "验证",
                source = capture(sourceSlot),
                refetchAfterSuccess = true,
                html = null,
            )
        } returns BrowserLauncher.BrowserWaitResult(
            url = "https://example.com/final",
            html = "<html>ok</html>",
        )

        val source = LegadoBookSource(
            bookSourceName = "Test Source",
            bookSourceUrl = "https://source.example.com",
        )
        val apiWithSource = LegadoJavaAPI(
            httpClient = httpClient,
            cookieManager = cookieManager,
            context = context,
            browserLauncherFactory = { _, _ -> launcher },
        ).apply {
            jsContext = JavaScriptContext(
                source = source,
                sourceName = "JSON_LEGADO_TEST_SOURCE",
            )
        }

        val result = apiWithSource.startBrowserAwait("https://example.com/cf", "验证")

        result.url() shouldBe "https://example.com/final"
        result.body() shouldBe "<html>ok</html>"
        sourceSlot.captured.name shouldBe "JSON_LEGADO_TEST_SOURCE"
    }

    test("t2s returns original text") {
        // Given
        val input = "繁體中文"
        
        // When
        val result = api.t2s(input)
        
        // Then
        result shouldBe "繁体中文"
    }
    
    test("s2t converts simplified Chinese to traditional Chinese") {
        // Given
        val input = "简体中文"
        
        // When
        val result = api.s2t(input)
        
        // Then
        result shouldBe "簡體中文"
    }
})

/**
 * Helper function to create a mock HTTP response
 */
private fun createMockResponse(url: String, body: String): Response {
    return Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/html".toMediaType()))
        .build()
}
