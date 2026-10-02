package org.skepsun.kototoro.parsers.config

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

	public class Text(
		key: String,
		@JvmField public val title: String,
		override val defaultValue: String = "",
	) : ConfigKey<String>(key)

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

	public class InterceptCloudflare(
		override val defaultValue: Boolean = false,
	) : ConfigKey<Boolean>("intercept_cloudflare")

	public class Toggle(
		key: String,
		@JvmField public val title: String,
		override val defaultValue: Boolean = false,
	) : ConfigKey<Boolean>(key)

	public class PreferredLanguage(
		@JvmField public val title: String,
		@JvmField public val presetValues: Map<String, String>,
		override val defaultValue: String = "all",
	) : ConfigKey<String>("preferred_language")
}
