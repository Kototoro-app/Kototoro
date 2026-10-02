package org.koitharu.kotatsu.parsers.config

public sealed class ConfigKey<T>(
	@JvmField public val key: String,
) {

	public abstract val defaultValue: T

	public class Domain(
		@JvmField @JvmSuppressWildcards public vararg val presetValues: String,
	) : ConfigKey<String>("domain") {

		init {
			require(presetValues.isNotEmpty()) { "You must provide at least one domain" }
		}

		override val defaultValue: String
			get() = presetValues.first()
	}

	public class ShowSuspiciousContent(
		override val defaultValue: Boolean,
	) : ConfigKey<Boolean>("show_suspicious")

	public class UserAgent(
		override val defaultValue: String,
	) : ConfigKey<String>("user_agent")

	public class SplitByTranslations(
		override val defaultValue: Boolean,
	) : ConfigKey<Boolean>("split_translations")

	public class PreferredImageServer(
		public val presetValues: Map<String?, String?>,
		override val defaultValue: String?,
	) : ConfigKey<String?>("img_server")

	/**
	 * Configuration key for disabling automatic chapter update checking.
	 *
	 * When set to true, this source will be excluded from background
	 * chapter update checks. Manual browsing and reading will still work normally.
	 *
	 * Useful for sources that:
	 * - Require CloudFlare bypass
	 * - Have rate limiting
	 * - Require authentication
	 * - Are slow to respond
	 *
	 * @param defaultValue Default state (default: false - updates enabled)
	 */
	public class DisableUpdateChecking(
		override val defaultValue: Boolean = false,
	) : ConfigKey<Boolean>("disable_updates")

	/**
	 * Configuration key for enabling CloudFlare interception and bypass.
	 *
	 * When set to true, the parser will attempt to intercept and handle
	 * CloudFlare challenges automatically using browser evaluation or other
	 * bypass mechanisms.
	 *
	 * Useful for sources that:
	 * - Are protected by CloudFlare
	 * - Require JavaScript evaluation to bypass challenges
	 * - Need browser-like behavior for access
	 *
	 * @param defaultValue Default state (default: false - no interception)
	 */
	public class InterceptCloudflare(
		override val defaultValue: Boolean = false,
	) : ConfigKey<Boolean>("intercept_cloudflare")
}
