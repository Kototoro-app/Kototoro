package org.skepsun.kototoro.core.network

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.jupiter.api.Test

class MangasNoSekaiTest {

    @Test
    fun testMangasNoSekaiConnection() {
        val chromeTlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(
                CipherSuite.TLS_AES_128_GCM_SHA256,
                CipherSuite.TLS_AES_256_GCM_SHA384,
                CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
            )
            .build()

        val client = OkHttpClient.Builder()
            .connectionSpecs(listOf(chromeTlsSpec, ConnectionSpec.CLEARTEXT))
            .build()

        val userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
        val request = Request.Builder()
            .url("https://mangasnosekai.com")
            .header("User-Agent", userAgent)
            .header("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .build()

        println("--- START CONNECTION TEST ---")
        println("Sending request to mangasnosekai.com with aligned TLS and User-Agent...")
        try {
            client.newCall(request).execute().use { response ->
                println("Response Code: ${response.code}")
                println("Response Message: ${response.message}")
                println("Server Header: ${response.header("server")}")
                val protection = org.skepsun.kototoro.parsers.network.CloudFlareHelper.checkResponseForProtection(response)
                println("Is protected check result: $protection (0=None, 1=Captcha, 2=Blocked)")
                val bodyText = response.body?.string() ?: ""
                println("Body Length: ${bodyText.length}")
                println("Body Preview: ${bodyText.take(300).replace("\n", " ")}")
            }
        } catch (e: Exception) {
            println("Connection failed with error: ${e.message}")
            e.printStackTrace()
        }
        println("--- END CONNECTION TEST ---")
    }
}
