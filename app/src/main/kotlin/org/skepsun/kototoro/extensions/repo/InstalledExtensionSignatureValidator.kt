package org.skepsun.kototoro.extensions.repo

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport

@Singleton
class InstalledExtensionSignatureValidator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cache = ConcurrentHashMap<String, Set<String>>()

    fun isTrusted(packageName: String, expectedFingerprint: String): Boolean {
        return ExtensionFingerprintTrust.isTrusted(expectedFingerprint, getFingerprints(packageName))
    }

    private fun getFingerprints(packageName: String): Set<String> {
        return cache.getOrPut(packageName) {
            runCatching {
                val packageInfo = context.packageManager.getPackageInfoCompat(packageName)
                    ?: getManagedPackageInfo(packageName)
                    ?: return@getOrPut emptySet()
                packageInfo.getSignaturesCompat()
                    .mapTo(LinkedHashSet()) { signature ->
                        MessageDigest.getInstance("SHA-256")
                            .digest(signature.toByteArray())
                            .joinToString("") { byte -> "%02x".format(byte) }
                    }
            }.getOrDefault(emptySet())
        }
    }

    private fun getManagedPackageInfo(packageName: String): PackageInfo? {
        return listOf("mihon", "aniyomi", "ireader").firstNotNullOfOrNull { ecosystem ->
            LocalApkExtensionSupport.getLocalArchivePackageInfoOrNull(
                context = context,
                pkgManager = context.packageManager,
                ecosystem = ecosystem,
                packageName = packageName,
            )
        }
    }

    private fun PackageInfo.getSignaturesCompat(): Array<Signature> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            val signingInfo = signingInfo ?: return emptyArray()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        }

        else -> {
            @Suppress("DEPRECATION")
            signatures ?: emptyArray()
        }
    }

    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }.getOrNull()
        }
    }
}
