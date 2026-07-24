package org.skepsun.kototoro.details.ui

import androidx.compose.material3.SnackbarDuration
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.exceptions.UnsupportedSourceException
import org.skepsun.kototoro.core.exceptions.resolve.ErrorObserver
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.prefs.SourceSettings
import org.skepsun.kototoro.core.util.ext.findCloudFlareException
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.isNetworkError
import org.skepsun.kototoro.core.util.ext.isSerializable
import org.skepsun.kototoro.parsers.exception.NotFoundException
import org.skepsun.kototoro.parsers.exception.ParseException

class DetailsErrorObserver(
	override val activity: DetailsActivity,
	private val viewModel: DetailsViewModel,
	resolver: ExceptionResolver?,
) : ErrorObserver(
	activity.contentRoot, null, resolver,
	{ isResolved ->
		if (isResolved) {
			viewModel.reload()
		}
	},
) {

	override suspend fun emit(value: Throwable) {
		val cf = value.findCloudFlareException()
		if (cf is CloudFlareProtectedException && canResolve(cf)) {
			val autoDisabled = SourceSettings(host.context, cf.source).isCaptchaAutoResolveDisabled
			if (!autoDisabled) {
				val resolved = resolveNow(cf, tryAutoResolve = true)
				if (resolved) {
					viewModel.reload()
					return
				}
			}
		}
		val duration = if (value is NotFoundException || value is UnsupportedSourceException) {
			SnackbarDuration.Indefinite
		} else {
			SnackbarDuration.Short
		}
		var actionLabel: String? = null
		var action: (() -> Unit)? = null
		when {
			canResolve(value) -> {
				actionLabel = host.context.getString(getResolveStringId(value))
				action = { resolve(value) }
			}

			value is ParseException -> {
				val router = router()
				if (router != null && value.isSerializable()) {
					actionLabel = host.context.getString(R.string.details)
					action = { router.showErrorDialog(value) }
				}
			}

			value.isNetworkError() -> {
				actionLabel = host.context.getString(R.string.try_again)
				action = viewModel::reload
			}
		}
		activity.showDetailsMessage(
			message = value.getDisplayMessage(host.context.resources),
			duration = duration,
			actionLabel = actionLabel,
			onAction = action,
		)
	}
}
