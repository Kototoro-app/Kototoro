package org.skepsun.kototoro.core.os

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppValidator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @SuppressLint("InlinedApi")
    val isOriginalApp = suspendLazy(Dispatchers.Default) {
        val certificates = mapOf(CERT_SHA256.hexToByteArray() to PackageManager.CERT_INPUT_SHA256)
        PackageInfoCompat.hasSignatures(context.packageManager, context.packageName, certificates, false)
    }

    private companion object {
        // 你的签名证书 SHA256 (从 keystore 提取)
        private const val CERT_SHA256 = "572e4a38c90c34a985f09e9003db3166836b037314407fce81770131a10027f3"
    }
}
