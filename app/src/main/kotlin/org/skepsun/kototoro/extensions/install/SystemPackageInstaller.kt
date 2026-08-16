package org.skepsun.kototoro.extensions.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.skepsun.kototoro.BuildConfig

@Singleton
class SystemPackageInstaller @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private var activeSession: SystemPackageInstallSession? = null

	fun createSession(
		apkFile: File,
		expectedPackageName: String,
		expectedVersionCode: Long,
	): SystemPackageInstallSession {
		val completion = CompletableDeferred<Unit>()
		val installerReturned = CompletableDeferred<Unit>()
		val cleanedUp = AtomicBoolean(false)
		var receiver: BroadcastReceiver? = null
		lateinit var installSession: SystemPackageInstallSession

		fun cleanUp() {
			if (cleanedUp.compareAndSet(false, true)) {
				receiver?.let { runCatching { context.unregisterReceiver(it) } }
				apkFile.delete()
				synchronized(this@SystemPackageInstaller) {
					if (activeSession === installSession) {
						activeSession = null
					}
				}
			}
		}

		val apkUri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", apkFile)
		val installIntent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(apkUri, APK_MIME_TYPE)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
		}
		installSession = SystemPackageInstallSession(
			intent = installIntent,
			completion = completion,
			installerReturned = installerReturned,
			isExpectedVersionInstalled = {
				isInstalledVersionSatisfied(
					installedVersion = context.installedVersionCode(expectedPackageName),
					expectedVersion = expectedVersionCode,
				)
			},
			onClose = ::cleanUp,
		)
		receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				val changedPackageName = intent.data?.schemeSpecificPart ?: return
				if (changedPackageName != expectedPackageName) return
				completion.complete(Unit)
				cleanUp()
			}
		}

		try {
			ContextCompat.registerReceiver(
				context,
				requireNotNull(receiver),
				IntentFilter().apply {
					addAction(Intent.ACTION_PACKAGE_ADDED)
					addAction(Intent.ACTION_PACKAGE_REPLACED)
					addDataScheme("package")
				},
				// Package broadcasts can originate from privileged OEM package manager processes.
				ContextCompat.RECEIVER_EXPORTED,
			)
			synchronized(this) {
				activeSession?.cancel()
				activeSession = installSession
			}
			return installSession
		} catch (error: Throwable) {
			cleanUp()
			throw error
		}
	}

	@Synchronized
	fun onInstallerActivityReturned() {
		activeSession?.notifyInstallerReturned()
	}
}

class SystemPackageInstallSession internal constructor(
	private val intent: Intent,
	private val completion: CompletableDeferred<Unit>,
	private val installerReturned: CompletableDeferred<Unit>,
	private val isExpectedVersionInstalled: () -> Boolean,
	private val onClose: () -> Unit,
) {
	suspend fun awaitUserAction(): Intent? {
		return intent
	}

	suspend fun awaitCompletion() {
		try {
			withTimeout(MAX_INSTALL_DURATION_MS) {
				// Prefer package broadcasts, then verify the installed version when the installer returns.
				select<Unit> {
					completion.onAwait { Unit }
					installerReturned.onAwait {
						awaitExpectedVersionInstalled()
					}
				}
			}
		} catch (error: TimeoutCancellationException) {
			throw CancellationException("Package installation was cancelled or did not finish", error)
		} finally {
			onClose()
		}
	}

	private suspend fun awaitExpectedVersionInstalled(): Unit =
		withTimeout(INSTALLER_RETURN_GRACE_PERIOD_MS) {
			while (!isExpectedVersionInstalled()) {
				delay(INSTALLED_VERSION_POLL_INTERVAL_MS)
			}
		}

	internal fun notifyInstallerReturned() {
		installerReturned.complete(Unit)
	}

	internal fun cancel() {
		completion.completeExceptionally(CancellationException("Package installation was superseded"))
		onClose()
	}
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val INSTALLED_VERSION_POLL_INTERVAL_MS = 200L
private const val INSTALLER_RETURN_GRACE_PERIOD_MS = 10_000L
private const val MAX_INSTALL_DURATION_MS = 5 * 60_000L

internal fun isInstalledVersionSatisfied(installedVersion: Long?, expectedVersion: Long): Boolean {
	return installedVersion != null && installedVersion >= expectedVersion
}

@Suppress("DEPRECATION")
private fun Context.installedVersionCode(packageName: String): Long? {
	return runCatching {
		PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
	}.getOrNull()
}
