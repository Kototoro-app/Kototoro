package org.skepsun.kototoro.settings.sources

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getDomainTitleResId
import org.skepsun.kototoro.core.model.getEnableSourceTitleResId
import org.skepsun.kototoro.core.model.getRecommendationTermResId
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.getUnsupportedSourceTitleResId
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import org.skepsun.kototoro.core.parser.EmptyContentRepository
import org.skepsun.kototoro.core.parser.JsContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserRepository
import org.skepsun.kototoro.core.parser.legado.LegadoRepository
import org.skepsun.kototoro.core.parser.tvbox.TVBoxRepository
import org.skepsun.kototoro.core.parser.tvbox.TVBoxSupportStatusClassifier
import org.skepsun.kototoro.mihon.MihonMangaRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.SourceSettings
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.util.ext.observeChanges
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.withArgs
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SourceSettingsActionRowUiState
import org.skepsun.kototoro.settings.compose.SourceSettingsChoiceRowUiState
import org.skepsun.kototoro.settings.compose.SourceSettingsInfoRowUiState
import org.skepsun.kototoro.settings.compose.SourceSettingsRowUiState
import org.skepsun.kototoro.settings.compose.SourceSettingsScreen
import org.skepsun.kototoro.settings.compose.SourceSettingsSectionUiState
import org.skepsun.kototoro.settings.compose.SourceSettingsSwitchRowUiState
import org.skepsun.kototoro.settings.compose.SourceSettingsTextRowUiState
import org.skepsun.kototoro.settings.utils.validation.DomainValidator
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import javax.inject.Inject
import java.io.File
import java.util.regex.Pattern
import org.json.JSONObject
import org.skepsun.kototoro.aniyomi.AniyomiAnimeRepository

@AndroidEntryPoint
class SourceComposeSettingsFragment : Fragment() {

    @Inject
    lateinit var settings: AppSettings

    private lateinit var exceptionResolver: ExceptionResolver

    private val viewModel: SourceSettingsViewModel by viewModels()
    private val configKeysFlow = MutableStateFlow<List<ConfigKey<*>>>(emptyList())
    private val jsSettingsSchemaFlow = MutableStateFlow<List<JsContentRepository.JsSettingItem>>(emptyList())
    private val externalPreferenceScreenFlow = MutableStateFlow<PreferenceScreen?>(null)
    private val sourcePreferencesName: String by lazy { resolveSourcePreferencesName() }

    private val sourcePrefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences(
            sourcePreferencesName,
            Context.MODE_PRIVATE,
        )
    }

    private val sourceSettings: SourceSettings by lazy {
        SourceSettings(requireContext(), viewModel.source)
    }
    private val legadoJson by lazy {
        Json { ignoreUnknownKeys = true; isLenient = true; allowTrailingComma = true }
    }
    private val legadoSourcePrefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences(LEGADO_SOURCE_PREFS, Context.MODE_PRIVATE)
    }
    private val legadoBookPrefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences(LEGADO_BOOK_PREFS, Context.MODE_PRIVATE)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(context)
        exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.onError.observeEvent(
            viewLifecycleOwner,
            SnackbarErrorObserver(view, this, exceptionResolver) { viewModel.onResume() },
        )
        viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(view))

        viewLifecycleOwner.lifecycleScope.launch {
            when (val repository = viewModel.repository) {
                is ParserContentRepository -> {
                    configKeysFlow.value = repository.getConfigKeys()
                    jsSettingsSchemaFlow.value = emptyList()
                }

                is KotatsuParserRepository -> {
                    configKeysFlow.value = repository.getConfigKeys()
                    jsSettingsSchemaFlow.value = emptyList()
                }

                is JsContentRepository -> {
                    configKeysFlow.value = emptyList()
                    jsSettingsSchemaFlow.value = runCatching {
                        repository.fetchSettingsSchema()
                    }.getOrDefault(emptyList())
                }

                is MihonMangaRepository -> {
                    configKeysFlow.value = emptyList()
                    jsSettingsSchemaFlow.value = emptyList()
                    externalPreferenceScreenFlow.value = buildExternalPreferenceScreen(repository)
                }

                is AniyomiAnimeRepository -> {
                    configKeysFlow.value = emptyList()
                    jsSettingsSchemaFlow.value = emptyList()
                    externalPreferenceScreenFlow.value = buildExternalPreferenceScreen(repository)
                }

                else -> {
                    configKeysFlow.value = emptyList()
                    jsSettingsSchemaFlow.value = emptyList()
                }
            }
        }

        (view as ComposeView).setContent {
            sourcePrefs.observeChanges()
                .map { Any() }
                .collectAsStateWithLifecycle(initialValue = Any())
                .value
            legadoSourcePrefs.observeChanges()
                .map { Any() }
                .collectAsStateWithLifecycle(initialValue = Any())
                .value
            legadoBookPrefs.observeChanges()
                .map { Any() }
                .collectAsStateWithLifecycle(initialValue = Any())
                .value
            val configKeys = configKeysFlow.asStateFlow().collectAsStateWithLifecycle().value
            val jsSettingsSchema = jsSettingsSchemaFlow.asStateFlow().collectAsStateWithLifecycle().value
            val externalPreferenceScreen = externalPreferenceScreenFlow.asStateFlow().collectAsStateWithLifecycle().value
            val isEnabled = viewModel.isEnabled.collectAsStateWithLifecycle(initialValue = false).value
            val browserUrl = viewModel.browserUrl.collectAsStateWithLifecycle(initialValue = null).value
            val username = viewModel.username.collectAsStateWithLifecycle(initialValue = null).value
            val isAuthorized = viewModel.isAuthorized.collectAsStateWithLifecycle(initialValue = null).value
            val isLoading = viewModel.isLoading.collectAsStateWithLifecycle(initialValue = false).value

            KototoroTheme {
                SourceSettingsScreen(
                    sections = buildSections(
                        configKeys = configKeys,
                        jsSettingsSchema = jsSettingsSchema,
                        externalPreferenceScreen = externalPreferenceScreen,
                        isEnabled = isEnabled,
                        browserUrl = browserUrl,
                        username = username,
                        isAuthorized = isAuthorized,
                        isLoading = isLoading,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(viewModel.source.getTitle(requireContext()))
        viewModel.onResume()
    }

    private fun buildSections(
        configKeys: List<ConfigKey<*>>,
        jsSettingsSchema: List<JsContentRepository.JsSettingItem>,
        externalPreferenceScreen: PreferenceScreen?,
        isEnabled: Boolean,
        browserUrl: String?,
        username: String?,
        isAuthorized: Boolean?,
        isLoading: Boolean,
    ): List<SourceSettingsSectionUiState> {
        val repository = viewModel.repository
        val sections = mutableListOf<SourceSettingsSectionUiState>()
        val contentType = viewModel.source.getContentType()
        val isValidSource = repository !is EmptyContentRepository

        buildGeneralRows(contentType, isValidSource, isEnabled).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += SourceSettingsSectionUiState(
                id = "general",
                title = getString(R.string.reader_translation_section_general),
                rows = rows,
            )
        }

        buildConfigRows(contentType, configKeys).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += SourceSettingsSectionUiState(
                id = "source_config",
                title = getString(R.string.settings),
                rows = rows,
            )
        }

        sections += buildExternalPreferenceSections(externalPreferenceScreen)

        buildTvBoxRows(repository).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += SourceSettingsSectionUiState(
                id = "tvbox_info",
                title = getString(R.string.source_type_tvbox),
                rows = rows,
            )
        }

        buildLegadoVariableRows(repository).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += SourceSettingsSectionUiState(
                id = "legado_variables",
                title = "Legado 变量",
                rows = rows,
            )
        }

        buildLegadoAuthRows(repository).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += SourceSettingsSectionUiState(
                id = "legado_auth",
                title = "登录（Legado）",
                rows = rows,
            )
        }

        buildLegadoRuntimeRows(repository).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += SourceSettingsSectionUiState(
                id = "legado_runtime",
                title = "运行时（Legado）",
                rows = rows,
            )
        }

        buildAuthRows(
            repository = repository,
            browserUrl = browserUrl,
            username = username,
            isAuthorized = isAuthorized,
            isLoading = isLoading,
        ).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += SourceSettingsSectionUiState(
                id = "auth",
                title = getString(R.string.auth_title),
                rows = rows,
            )
        }

        buildJsSettingsRows(repository, jsSettingsSchema).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += SourceSettingsSectionUiState(
                id = "js_settings",
                title = getString(R.string.settings),
                rows = rows,
            )
        }

        if (repository is EmptyContentRepository) {
            sections += SourceSettingsSectionUiState(
                id = "unsupported",
                title = getString(R.string.settings),
                rows = listOf(
                    SourceSettingsInfoRowUiState(
                        id = "unsupported_info",
                        title = viewModel.source.getTitle(requireContext()),
                        summary = getString(contentType.getUnsupportedSourceTitleResId()),
                    ),
                ),
            )
        }

        return sections
    }

    private fun resolveSourcePreferencesName(): String {
        return when (val repository = viewModel.repository) {
            is MihonMangaRepository -> "source_${repository.mihonSource.id}"
            is AniyomiAnimeRepository -> "source_${repository.aniyomiSource.id}"
            else -> viewModel.source.name.replace(File.separatorChar, '$')
        }
    }

    private fun buildExternalPreferenceSections(
        screen: PreferenceScreen?,
    ): List<SourceSettingsSectionUiState> {
        val preferenceScreen = screen ?: return emptyList()
        var hasUnsupportedPreference = false
        val sections = ComposePreferenceAdapter(
            context = requireContext(),
            sharedPreferencesName = sourcePreferencesName,
        ).buildSections(preferenceScreen) {
            hasUnsupportedPreference = true
        }
        if (!hasUnsupportedPreference) {
            return sections
        }
        return sections + SourceSettingsSectionUiState(
            id = "external_preference_compat",
            title = getString(R.string.settings),
            rows = listOf(
                SourceSettingsInfoRowUiState(
                    id = "external_preference_compat_info",
                    title = "兼容性提示",
                    summary = "该扩展包含暂未 Compose 化的自定义设置项，当前仅显示可安全映射的常规项。",
                ),
            ),
        )
    }

    private fun buildExternalPreferenceScreen(repository: MihonMangaRepository): PreferenceScreen? {
        val mihonSource = repository.mihonSource as? eu.kanade.tachiyomi.source.ConfigurableSource ?: return null
        val adapter = ComposePreferenceAdapter(
            context = requireContext(),
            sharedPreferencesName = sourcePreferencesName,
        )
        return runCatching {
            adapter.createScreen().also(mihonSource::setupPreferenceScreen)
        }.onFailure {
            android.util.Log.e("SourceComposeSettings", "Failed to setup Mihon preferences", it)
        }.getOrNull()
    }

    private fun buildExternalPreferenceScreen(repository: AniyomiAnimeRepository): PreferenceScreen? {
        val aniyomiSource =
            repository.aniyomiSource as? eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource ?: return null
        val adapter = ComposePreferenceAdapter(
            context = requireContext(),
            sharedPreferencesName = sourcePreferencesName,
        )
        return runCatching {
            adapter.createScreen().also(aniyomiSource::setupPreferenceScreen)
        }.onFailure {
            android.util.Log.e("SourceComposeSettings", "Failed to setup Aniyomi preferences", it)
        }.getOrNull()
    }

    private fun buildGeneralRows(
        contentType: ContentType,
        isValidSource: Boolean,
        isEnabled: Boolean,
    ): List<SourceSettingsRowUiState> {
        val rows = mutableListOf<SourceSettingsRowUiState>()
        if (isValidSource && !settings.isAllSourcesEnabled) {
            rows += SourceSettingsSwitchRowUiState(
                id = "enable",
                title = getString(contentType.getEnableSourceTitleResId()),
                checked = isEnabled,
                onCheckedChange = viewModel::setEnabled,
            )
        }
        if (isValidSource) {
            rows += SourceSettingsSwitchRowUiState(
                id = SourceSettings.KEY_NO_CAPTCHA,
                title = getString(R.string.disable_captcha_notifications),
                checked = sourcePrefs.getBoolean(SourceSettings.KEY_NO_CAPTCHA, false),
                summary = getString(
                    R.string.disable_captcha_notifications_summary,
                    getString(contentType.getRecommendationTermResId()),
                ),
                onCheckedChange = { checked ->
                    sourcePrefs.edit { putBoolean(SourceSettings.KEY_NO_CAPTCHA, checked) }
                },
            )
            rows += SourceSettingsSwitchRowUiState(
                id = SourceSettings.KEY_NO_AUTO_CAPTCHA,
                title = getString(R.string.disable_captcha_auto_solve),
                checked = sourcePrefs.getBoolean(SourceSettings.KEY_NO_AUTO_CAPTCHA, false),
                summary = getString(R.string.disable_captcha_auto_solve_summary),
                onCheckedChange = { checked ->
                    sourcePrefs.edit { putBoolean(SourceSettings.KEY_NO_AUTO_CAPTCHA, checked) }
                },
            )
            rows += SourceSettingsSwitchRowUiState(
                id = SourceSettings.KEY_SLOWDOWN,
                title = getString(R.string.download_slowdown),
                checked = sourcePrefs.getBoolean(SourceSettings.KEY_SLOWDOWN, false),
                summary = getString(R.string.download_slowdown_summary),
                onCheckedChange = { checked ->
                    sourcePrefs.edit { putBoolean(SourceSettings.KEY_SLOWDOWN, checked) }
                },
            )
        }
        return rows
    }

    private fun buildConfigRows(
        contentType: ContentType,
        configKeys: List<ConfigKey<*>>,
    ): List<SourceSettingsRowUiState> {
        return buildList {
            configKeys.forEach { key ->
                when (key) {
                    is ConfigKey.Domain -> add(buildDomainRow(contentType, key))

                    is ConfigKey.Text -> add(
                        SourceSettingsTextRowUiState(
                            id = key.key,
                            title = key.title,
                            value = sourceSettings[key],
                            placeholder = key.defaultValue,
                            onValueChange = { value ->
                                sourceSettings[key] = value
                            },
                        ),
                    )

                    is ConfigKey.UserAgent -> {
                        add(
                            SourceSettingsTextRowUiState(
                                id = key.key,
                                title = getString(R.string.user_agent),
                                value = sourceSettings[key],
                                placeholder = key.defaultValue,
                                suggestions = buildUserAgentPresetOptions(),
                                onValueChange = onUserAgentValueChange@{ value ->
                                    if (value.isNotBlank() && !isValidHeaderValue(value.trim())) {
                                        showToast(R.string.invalid_value_message)
                                        return@onUserAgentValueChange
                                    }
                                    sourceSettings[key] = value
                                },
                            ),
                        )
                    }

                    is ConfigKey.ShowSuspiciousContent -> add(
                        SourceSettingsSwitchRowUiState(
                            id = key.key,
                            title = getString(R.string.show_suspicious_content),
                            checked = sourceSettings[key],
                            onCheckedChange = { checked ->
                                sourceSettings[key] = checked
                            },
                        ),
                    )

                    is ConfigKey.InterceptCloudflare -> Unit

                    is ConfigKey.Toggle -> add(
                        SourceSettingsSwitchRowUiState(
                            id = key.key,
                            title = key.title,
                            checked = sourceSettings[key],
                            onCheckedChange = { checked ->
                                sourceSettings[key] = checked
                            },
                        ),
                    )

                    is ConfigKey.SplitByTranslations -> add(
                        SourceSettingsSwitchRowUiState(
                            id = key.key,
                            title = getString(R.string.split_by_translations),
                            checked = sourceSettings[key],
                            summary = getString(R.string.split_by_translations_summary),
                            onCheckedChange = { checked ->
                                sourceSettings[key] = checked
                            },
                        ),
                    )

                    is ConfigKey.PreferredImageServer -> add(
                        SourceSettingsChoiceRowUiState(
                            id = key.key,
                            title = getString(R.string.image_server),
                            value = sourceSettings[key].orEmpty(),
                            options = key.presetValues.map { entry ->
                                SettingsChoiceOption(
                                    entry.key.orEmpty(),
                                    entry.value ?: getString(R.string.automatic),
                                )
                            },
                            onValueChange = { value ->
                                sourceSettings[key] = value.ifEmpty { null }
                            },
                        ),
                    )

                    is ConfigKey.PreferredLanguage -> add(
                        SourceSettingsChoiceRowUiState(
                            id = key.key,
                            title = key.title,
                            value = sourceSettings[key],
                            options = key.presetValues.map { entry ->
                                SettingsChoiceOption(entry.key, entry.value)
                            },
                            onValueChange = { value ->
                                sourceSettings[key] = value
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun buildDomainRow(
        contentType: ContentType,
        key: ConfigKey.Domain,
    ): SourceSettingsRowUiState {
        return SourceSettingsTextRowUiState(
            id = key.key,
            title = getString(contentType.getDomainTitleResId()),
            value = sourceSettings[key],
            placeholder = key.defaultValue,
            suggestions = buildDomainPresetOptions(key),
            onValueChange = { value -> setDomainValue(key, value) },
        )
    }

    private fun buildDomainPresetOptions(
        key: ConfigKey.Domain,
    ): List<SettingsChoiceOption<String>> {
        return key.presetValues
            .distinct()
            .map { domain -> SettingsChoiceOption(domain, domain) }
    }

    private fun setDomainValue(
        key: ConfigKey.Domain,
        value: String,
    ) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && !DomainValidator.isValidDomain(trimmed)) {
            showToast(R.string.invalid_domain_message)
            return
        }
        sourceSettings[key] = trimmed
    }

    private fun buildUserAgentPresetOptions(): List<SettingsChoiceOption<String>> {
        return userAgentPresets().map { preset ->
            SettingsChoiceOption(
                value = preset.value,
                label = preset.label,
            )
        }
    }

    private fun buildAuthRows(
        repository: org.skepsun.kototoro.core.parser.ContentRepository,
        browserUrl: String?,
        username: String?,
        isAuthorized: Boolean?,
        isLoading: Boolean,
    ): List<SourceSettingsRowUiState> {
        if (repository is JsContentRepository) {
            return buildJsAuthRows(
                repository = repository,
                browserUrl = browserUrl,
                isLoading = isLoading,
            )
        }
        val parserRepository = repository as? ParserContentRepository ?: return buildBrowserOnlyRows(browserUrl)
        val rows = mutableListOf<SourceSettingsRowUiState>()
        val authProvider = parserRepository.getAuthProvider()
        val credentialsProvider = authProvider as? ContentParserCredentialsAuthProvider

        if (credentialsProvider != null) {
            rows += SourceSettingsInfoRowUiState(
                id = "auth_status",
                title = getString(R.string.sign_in),
                summary = username?.let { getString(R.string.logged_in_as, it) }
                    ?: getString(R.string.auth_required),
            )
            rows += SourceSettingsTextRowUiState(
                id = "auth_username",
                title = getString(R.string.enter_name),
                value = sourcePrefs.getString(KEY_AUTH_USERNAME, "").orEmpty(),
                enabled = !isLoading,
                onValueChange = { value ->
                    sourcePrefs.edit { putString(KEY_AUTH_USERNAME, value) }
                },
            )
            rows += SourceSettingsTextRowUiState(
                id = "auth_password",
                title = getString(R.string.enter_password),
                value = sourcePrefs.getString(KEY_AUTH_PASSWORD, "").orEmpty(),
                isPassword = true,
                enabled = !isLoading,
                onValueChange = { value ->
                    sourcePrefs.edit { putString(KEY_AUTH_PASSWORD, value) }
                },
            )
            rows += SourceSettingsActionRowUiState(
                id = "auth_login",
                title = getString(R.string.sign_in),
                enabled = !isLoading,
                showChevron = false,
                onClick = {
                    viewModel.loginByCredentials(
                        sourcePrefs.getString(KEY_AUTH_USERNAME, "").orEmpty().trim(),
                        sourcePrefs.getString(KEY_AUTH_PASSWORD, "").orEmpty().trim(),
                    )
                },
            )
        } else if (authProvider != null) {
            rows += SourceSettingsActionRowUiState(
                id = "auth_browser",
                title = getString(R.string.sign_in),
                summary = username?.let { getString(R.string.logged_in_as, it) }
                    ?: getString(R.string.auth_required),
                enabled = !isLoading && isAuthorized != true,
                showChevron = false,
                onClick = {
                    router.openSourceAuth(viewModel.source)
                },
            )
        }

        browserUrl?.let { url ->
            rows += SourceSettingsActionRowUiState(
                id = AppSettings.KEY_OPEN_BROWSER,
                title = getString(R.string.open_in_browser),
                summary = url,
                onClick = {
                    router.openBrowser(
                        url = url,
                        source = viewModel.source,
                        title = viewModel.source.getTitle(requireContext()),
                    )
                },
            )
        }

        rows += SourceSettingsActionRowUiState(
            id = AppSettings.KEY_COOKIES_CLEAR,
            title = getString(R.string.clear_cookies),
            summary = getString(R.string.clear_source_cookies_summary),
            showChevron = false,
            onClick = viewModel::clearCookies,
        )

        return rows
    }

    private fun buildJsAuthRows(
        repository: JsContentRepository,
        browserUrl: String?,
        isLoading: Boolean,
    ): List<SourceSettingsRowUiState> {
        val rows = mutableListOf<SourceSettingsRowUiState>()
        val meta = viewModel.jsAccountMeta.value

        if (meta?.hasLogin == true) {
            rows += SourceSettingsTextRowUiState(
                id = KEY_JS_USERNAME,
                title = getString(R.string.username),
                value = sourcePrefs.getString(KEY_JS_USERNAME, "").orEmpty(),
                enabled = !isLoading,
                onValueChange = { value ->
                    sourcePrefs.edit { putString(KEY_JS_USERNAME, value) }
                },
            )
            rows += SourceSettingsTextRowUiState(
                id = KEY_JS_PASSWORD,
                title = getString(R.string.password),
                value = sourcePrefs.getString(KEY_JS_PASSWORD, "").orEmpty(),
                isPassword = true,
                enabled = !isLoading,
                onValueChange = { value ->
                    sourcePrefs.edit { putString(KEY_JS_PASSWORD, value) }
                },
            )
            rows += SourceSettingsActionRowUiState(
                id = KEY_JS_LOGIN,
                title = getString(R.string.sign_in),
                enabled = !isLoading,
                showChevron = false,
                onClick = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.loginJs(
                            sourcePrefs.getString(KEY_JS_USERNAME, "").orEmpty().trim(),
                            sourcePrefs.getString(KEY_JS_PASSWORD, "").orEmpty().trim(),
                        )
                    }
                },
            )
            rows += SourceSettingsActionRowUiState(
                id = KEY_JS_LOGOUT,
                title = getString(R.string.logout),
                enabled = !isLoading,
                showChevron = false,
                onClick = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.logoutJs()
                    }
                },
            )
        }

        if (meta?.hasWebLogin == true && !meta.webLoginUrl.isNullOrBlank()) {
            rows += SourceSettingsActionRowUiState(
                id = KEY_JS_WEB_LOGIN,
                title = getString(R.string.login_with_browser),
                summary = meta.webLoginUrl,
                enabled = !isLoading,
                showChevron = false,
                onClick = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.loginJsWithWebview()
                    }
                },
            )
        }

        if (!meta?.cookieFields.isNullOrEmpty()) {
            meta?.cookieFields?.forEachIndexed { index, field ->
                rows += SourceSettingsTextRowUiState(
                    id = "$KEY_JS_COOKIE_PREFIX$index",
                    title = field,
                    value = sourcePrefs.getString("$KEY_JS_COOKIE_PREFIX$index", "").orEmpty(),
                    enabled = !isLoading,
                    onValueChange = { value ->
                        sourcePrefs.edit { putString("$KEY_JS_COOKIE_PREFIX$index", value) }
                    },
                )
            }
            rows += SourceSettingsActionRowUiState(
                id = KEY_JS_COOKIE_SUBMIT,
                title = getString(R.string.save),
                enabled = !isLoading,
                showChevron = false,
                onClick = {
                    val values = meta.cookieFields.mapIndexedNotNull { index, field ->
                        val value = sourcePrefs.getString("$KEY_JS_COOKIE_PREFIX$index", "").orEmpty()
                            .takeIf { it.isNotBlank() }
                        value?.let { field to it }
                    }.toMap()
                    val ok = values.isNotEmpty() && viewModel.setJsCookies(values)
                    Toast.makeText(
                        requireContext(),
                        if (ok) R.string.cookies_saved else R.string.cookies_cleared,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }

        rows += buildBrowserOnlyRows(browserUrl)
        if (meta != null) {
            return rows
        }
        return rows.takeIf { it.isNotEmpty() } ?: buildBrowserOnlyRows(browserUrl)
    }

    private fun buildJsSettingsRows(
        repository: org.skepsun.kototoro.core.parser.ContentRepository,
        schema: List<JsContentRepository.JsSettingItem>,
    ): List<SourceSettingsRowUiState> {
        val jsRepository = repository as? JsContentRepository ?: return emptyList()
        if (schema.isEmpty()) {
            return emptyList()
        }
        return buildList {
            schema.forEach { item ->
                when (item.type.lowercase()) {
                    "select" -> add(
                        SourceSettingsChoiceRowUiState(
                            id = "js_${item.key}",
                            title = item.title,
                            value = (jsRepository.getJsSettingValue(item.key) as? String)
                                ?: item.defaultValue
                                ?: item.options.firstOrNull()?.value
                                .orEmpty(),
                            options = item.options.map { option ->
                                SettingsChoiceOption(
                                    value = option.value,
                                    label = option.text,
                                )
                            },
                            onValueChange = { value ->
                                jsRepository.saveJsSettingValue(item.key, value)
                            },
                        ),
                    )

                    "callback" -> add(
                        SourceSettingsActionRowUiState(
                            id = "js_${item.key}",
                            title = item.title,
                            summary = item.buttonText,
                            showChevron = false,
                            onClick = {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    jsRepository.executeSettingCallback(item.key)
                                }
                            },
                        ),
                    )

                    else -> add(
                        SourceSettingsTextRowUiState(
                            id = "js_${item.key}",
                            title = item.title,
                            value = (jsRepository.getJsSettingValue(item.key) as? String)
                                ?: item.defaultValue
                                .orEmpty(),
                            placeholder = item.defaultValue,
                            onValueChange = onValueChange@{ value ->
                                val pattern = item.validator
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(Pattern::compile)
                                if (pattern != null && !pattern.matcher(value).matches()) {
                                    showToast(R.string.invalid_value_message)
                                    return@onValueChange
                                }
                                jsRepository.saveJsSettingValue(item.key, value)
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun buildTvBoxRows(
        repository: org.skepsun.kototoro.core.parser.ContentRepository,
    ): List<SourceSettingsRowUiState> {
        val (repo, config) = getTvBoxRepoAndConfigOrNull(repository) ?: return emptyList()
        val candidates = buildTvBoxResourceCandidates(config)
        val runtimeSummary = buildTvBoxRuntimeSummary(repo, config)
        return buildList {
            addInfoRow(KEY_TVBOX_SITE_KEY, getString(R.string.tvbox_site_key_title), config.site.key.ifBlank {
                getString(R.string.not_specified)
            })
            addInfoRow(KEY_TVBOX_SITE_API, getString(R.string.tvbox_site_api_title), config.site.api.ifBlank {
                getString(R.string.not_specified)
            })
            addInfoRow(KEY_TVBOX_SITE_TYPE, getString(R.string.tvbox_site_type_title), getTvBoxTypeSummary(config))
            config.root.spider?.takeIf { it.isNotBlank() }?.let { spider ->
                addInfoRow(KEY_TVBOX_ROOT_SPIDER, getString(R.string.tvbox_root_spider_title), spider)
            }
            config.site.jar?.takeIf { it.isNotBlank() }?.let { jar ->
                addInfoRow(KEY_TVBOX_SITE_JAR, getString(R.string.tvbox_site_jar_title), jar)
            }
            config.meta.sourceLocator?.takeIf { it.isNotBlank() }?.let { locator ->
                addInfoRow(KEY_TVBOX_SOURCE_LOCATOR, getString(R.string.tvbox_source_locator_title), locator)
            }
            runtimeSummary?.let { summary ->
                addInfoRow(KEY_TVBOX_RUNTIME_STRATEGY, getString(R.string.tvbox_runtime_strategy_title), summary)
            }
            if (candidates.isNotEmpty()) {
                addInfoRow(
                    KEY_TVBOX_RUNTIME_CANDIDATES,
                    getString(R.string.tvbox_runtime_candidates_title),
                    candidates.joinToString(separator = "\n"),
                )
            }
            addInfoRow(
                KEY_TVBOX_STATUS,
                getString(R.string.tvbox_support_status_title),
                getTvBoxSupportStatusSummary(config, candidates),
            )
        }
    }

    private fun buildLegadoVariableRows(
        repository: org.skepsun.kototoro.core.parser.ContentRepository,
    ): List<SourceSettingsRowUiState> {
        val (repo, config) = getLegadoRepoAndConfigOrNull(repository) ?: return emptyList()
        val sourceKey = config.bookSourceUrl.trim().ifBlank { return emptyList() }
        return listOf(
            SourceSettingsTextRowUiState(
                id = KEY_LEGADO_SOURCE_VARIABLE,
                title = "源变量",
                value = legadoSourcePrefs.getString(sourceVariableKey(sourceKey), "").orEmpty(),
                summary = "用于控制书籍列表加载数量（脚本通常读取末尾数字）。留空表示不设置。",
                placeholder = "",
                onValueChange = onValueChange@{ value ->
                    val trimmed = value.trim()
                    if (!isSignedIntOrBlank(trimmed)) {
                        Toast.makeText(requireContext(), "请输入整数（可为空）", Toast.LENGTH_SHORT).show()
                        return@onValueChange
                    }
                    legadoSourcePrefs.edit {
                        if (trimmed.isBlank()) {
                            remove(sourceVariableKey(sourceKey))
                        } else {
                            putString(sourceVariableKey(sourceKey), trimmed)
                        }
                    }
                    repo.invalidateCache()
                },
            ),
            SourceSettingsTextRowUiState(
                id = KEY_LEGADO_BOOK_DEFAULT_CUSTOM,
                title = "书籍变量（custom）默认值",
                value = legadoBookPrefs.getString(bookDefaultKey(sourceKey, "custom"), "").orEmpty(),
                summary = "用于限制章节加载上限（聚合源常用）。-1 表示不限制；留空表示不设置。",
                placeholder = "-1",
                onValueChange = onValueChange@{ value ->
                    val trimmed = value.trim()
                    if (!isSignedIntOrBlank(trimmed)) {
                        Toast.makeText(requireContext(), "请输入整数（可为空）", Toast.LENGTH_SHORT).show()
                        return@onValueChange
                    }
                    legadoBookPrefs.edit {
                        if (trimmed.isBlank()) {
                            remove(bookDefaultKey(sourceKey, "custom"))
                        } else {
                            putString(bookDefaultKey(sourceKey, "custom"), trimmed)
                        }
                    }
                    repo.invalidateCache()
                },
            ),
        )
    }

    private fun buildLegadoAuthRows(
        repository: org.skepsun.kototoro.core.parser.ContentRepository,
    ): List<SourceSettingsRowUiState> {
        val (repo, config) = getLegadoRepoAndConfigOrNull(repository) ?: return emptyList()
        val sourceKey = config.bookSourceUrl.trim().ifBlank { return emptyList() }
        val rawLoginUi = config.loginUi?.trim().orEmpty()
        val hasAnyLogin = rawLoginUi.isNotBlank() ||
            config.loginUrl?.isNotBlank() == true ||
            config.loginCheckJs?.isNotBlank() == true
        if (!hasAnyLogin) return emptyList()

        val rows = mutableListOf<SourceSettingsRowUiState>()
        parseLegadoLoginUiItems(repo, rawLoginUi).forEachIndexed { index, item ->
            when (item.type.lowercase()) {
                "text", "password" -> {
                    val storeKey = sourceKvKey(sourceKey, item.name)
                    rows += SourceSettingsTextRowUiState(
                        id = "$KEY_LEGADO_LOGIN_FIELD_PREFIX$index",
                        title = item.name,
                        value = legadoSourcePrefs.getString(storeKey, "").orEmpty(),
                        isPassword = item.type.equals("password", ignoreCase = true),
                        onValueChange = { value ->
                            legadoSourcePrefs.edit { putString(storeKey, value) }
                            repo.invalidateCache()
                        },
                    )
                }

                "button" -> {
                    val action = item.action?.trim().orEmpty()
                    if (action.isNotBlank()) {
                        rows += SourceSettingsActionRowUiState(
                            id = "$KEY_LEGADO_LOGIN_BUTTON_PREFIX$index",
                            title = item.name,
                            showChevron = false,
                            onClick = {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val message = withContext(Dispatchers.IO) {
                                        runCatching { repo.runUserScript(action) }
                                            .getOrNull()
                                            ?.toString()
                                            ?.take(200)
                                            .orEmpty()
                                            .ifBlank { "执行完成" }
                                    }
                                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
        }

        val checkJs = config.loginCheckJs?.trim().orEmpty()
        if (checkJs.isNotBlank()) {
            rows += SourceSettingsActionRowUiState(
                id = KEY_LEGADO_LOGIN_CHECK,
                title = "检测登录状态",
                showChevron = false,
                onClick = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val message = withContext(Dispatchers.IO) {
                            runCatching { repo.evalUserExpression(checkJs) }
                                .getOrNull()
                                ?.toString()
                                ?.take(200)
                                ?: "执行失败"
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        rows += SourceSettingsActionRowUiState(
            id = KEY_LEGADO_LOGIN_CLEAR,
            title = "清理登录信息",
            summary = "清空该源的 sourceVariable/loginInfo/登录表单缓存",
            showChevron = false,
            onClick = {
                legadoSourcePrefs.edit {
                    remove(sourceVariableKey(sourceKey))
                    remove(loginInfoKey(sourceKey))
                    val prefix = sourceKvPrefix(sourceKey)
                    legadoSourcePrefs.all.keys
                        .filter { it.startsWith(prefix) }
                        .forEach(::remove)
                }
                repo.invalidateCache()
                Toast.makeText(requireContext(), "已清理", Toast.LENGTH_SHORT).show()
            },
        )

        return rows
    }

    private fun buildLegadoRuntimeRows(
        repository: org.skepsun.kototoro.core.parser.ContentRepository,
    ): List<SourceSettingsRowUiState> {
        if (repository !is LegadoRepository) return emptyList()
        return listOf(
            SourceSettingsSwitchRowUiState(
                id = KEY_LEGADO_STANDALONE_RUNTIME,
                title = "使用 runtime 目录解析",
                summary = "切换 Legado 目录/详情链路的 standalone runtime 调试开关。",
                checked = legadoSourcePrefs.getBoolean(KEY_LEGADO_STANDALONE_RUNTIME, false),
                onCheckedChange = { checked ->
                    legadoSourcePrefs.edit { putBoolean(KEY_LEGADO_STANDALONE_RUNTIME, checked) }
                    repository.invalidateCache()
                },
            ),
        )
    }

    private fun buildBrowserOnlyRows(browserUrl: String?): List<SourceSettingsRowUiState> {
        if (browserUrl == null) {
            return emptyList()
        }
        return listOf(
            SourceSettingsActionRowUiState(
                id = AppSettings.KEY_OPEN_BROWSER,
                title = getString(R.string.open_in_browser),
                summary = browserUrl,
                onClick = {
                    router.openBrowser(
                        url = browserUrl,
                        source = viewModel.source,
                        title = viewModel.source.getTitle(requireContext()),
                    )
                },
            ),
        )
    }

    private fun getLegadoRepoAndConfigOrNull(
        repository: org.skepsun.kototoro.core.parser.ContentRepository,
    ): Pair<LegadoRepository, LegadoBookSource>? {
        val repo = repository as? LegadoRepository ?: return null
        val jsonSource = repo.source as? JsonContentSource ?: return null
        val config = runCatching {
            legadoJson.decodeFromString<LegadoBookSource>(jsonSource.entity.config)
        }.getOrNull() ?: return null
        return repo to config
    }

    private fun getTvBoxRepoAndConfigOrNull(
        repository: org.skepsun.kototoro.core.parser.ContentRepository,
    ): Pair<TVBoxRepository, TVBoxStoredConfig>? {
        val repo = repository as? TVBoxRepository ?: return null
        val jsonSource = repo.source as? JsonContentSource ?: return null
        val config = runCatching { TVBoxStoredConfig.parse(jsonSource.entity.config) }.getOrNull() ?: return null
        return repo to config
    }

    private fun parseLegadoLoginUiItems(
        repo: LegadoRepository,
        rawLoginUi: String,
    ): List<LegadoLoginUiItem> {
        val resolved = resolveLegadoMaybeJs(repo, rawLoginUi).trim()
        if (resolved.isBlank()) return emptyList()

        val asJson = runCatching { legadoJson.parseToJsonElement(resolved) }.getOrNull()
            ?: runCatching {
                val js = "var __ui = $resolved; JSON.stringify(__ui);"
                val encoded = repo.runUserScript(js)?.toString().orEmpty()
                legadoJson.parseToJsonElement(encoded)
            }.getOrNull()
            ?: return emptyList()

        val array = asJson as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val action = obj["action"]?.jsonPrimitive?.contentOrNull
            if (name.isBlank() || type.isBlank()) return@mapNotNull null
            LegadoLoginUiItem(name = name, type = type, action = action)
        }
    }

    private fun resolveLegadoMaybeJs(
        repo: LegadoRepository,
        text: String,
    ): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("@js:", ignoreCase = true)) {
            return repo.runUserScript(trimmed.removePrefix("@js:"))?.toString().orEmpty()
        }
        if (trimmed.startsWith("<js>", ignoreCase = true) && trimmed.contains("</js>", ignoreCase = true)) {
            val script = trimmed.substringAfter("<js>", "").substringBeforeLast("</js>", "")
            return repo.runUserScript(script)?.toString().orEmpty()
        }
        return trimmed
    }

    private fun buildTvBoxRuntimeSummary(repo: TVBoxRepository, config: TVBoxStoredConfig): String? {
        val capability = repo.getRuntimeCapabilitySummary()
        val note = repo.getRuntimeUnavailabilitySummary()
        if (capability == null && note == null) {
            return if (hasTvBoxSpiderArtifacts(config)) {
                getString(R.string.tvbox_runtime_strategy_none)
            } else {
                null
            }
        }
        return buildString {
            capability?.let(::append)
            note?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
        }.ifBlank { null }
    }

    private fun buildTvBoxResourceCandidates(config: TVBoxStoredConfig): List<String> {
        val dedup = linkedSetOf<String>()

        fun add(rawValue: String?) {
            resolveTvBoxCandidateUrl(config, rawValue)?.let(dedup::add)
        }

        add(config.site.api)
        add(config.site.playUrl)
        when (val ext = config.site.ext) {
            is String -> add(ext)
            is JSONObject -> {
                listOf("url", "api", "playUrl", "link", "file", "m3u", "m3u8")
                    .forEach { key -> add(ext.optString(key).trim().ifBlank { null }) }
            }
        }
        return dedup.toList()
    }

    private fun resolveTvBoxCandidateUrl(config: TVBoxStoredConfig, rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty()
        if (value.isBlank()) return null
        if (
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("content://", ignoreCase = true) ||
            value.startsWith("file://", ignoreCase = true)
        ) {
            return value
        }
        if (value.startsWith("//")) {
            return "https:$value"
        }
        val baseHttpUrl = config.meta.sourceLocator?.toHttpUrlOrNull() ?: return null
        return baseHttpUrl.resolve(value)?.toString()
    }

    private fun hasTvBoxSpiderArtifacts(config: TVBoxStoredConfig): Boolean {
        return TVBoxSupportStatusClassifier.hasSpiderArtifacts(config)
    }

    private fun getTvBoxSupportStatusSummary(config: TVBoxStoredConfig, candidates: List<String>): String {
        return when (TVBoxSupportStatusClassifier.classify(config, candidates)) {
            org.skepsun.kototoro.core.parser.tvbox.TVBoxSupportStatus.DIRECT -> getString(R.string.tvbox_support_status_direct)
            org.skepsun.kototoro.core.parser.tvbox.TVBoxSupportStatus.PARTIAL_RUNTIME -> getString(R.string.tvbox_support_status_partial_runtime)
            org.skepsun.kototoro.core.parser.tvbox.TVBoxSupportStatus.QUICKJS_PARTIAL -> getString(R.string.tvbox_support_status_quickjs_partial)
            org.skepsun.kototoro.core.parser.tvbox.TVBoxSupportStatus.BRIDGEABLE -> getString(R.string.tvbox_support_status_bridgeable)
            org.skepsun.kototoro.core.parser.tvbox.TVBoxSupportStatus.SPIDER_BRIDGE -> getString(R.string.tvbox_support_status_spider_bridge)
            org.skepsun.kototoro.core.parser.tvbox.TVBoxSupportStatus.ORDINARY_JAR -> getString(R.string.tvbox_support_status_ordinary_jar)
            org.skepsun.kototoro.core.parser.tvbox.TVBoxSupportStatus.GUARD_NATIVE -> getString(R.string.tvbox_support_status_guard_native)
        }
    }

    private fun getTvBoxTypeSummary(config: TVBoxStoredConfig): String {
        val typeLabel = when (config.site.type) {
            0 -> "XML"
            1 -> "JSON"
            3 -> "Spider"
            4 -> "JS"
            else -> getString(R.string.not_specified)
        }
        return "$typeLabel (${config.site.type})"
    }

    private fun MutableList<SourceSettingsRowUiState>.addInfoRow(
        id: String,
        title: String,
        summary: String,
    ) {
        add(SourceSettingsInfoRowUiState(id = id, title = title, summary = summary))
    }

    private fun sourceVariableKey(sourceKey: String): String = "sourceVariable_$sourceKey"

    private fun loginInfoKey(sourceKey: String): String = "userInfo_$sourceKey"

    private fun sourceKvPrefix(sourceKey: String): String = "v_${sourceKey}_"

    private fun sourceKvKey(sourceKey: String, key: String): String = "${sourceKvPrefix(sourceKey)}$key"

    private fun bookDefaultKey(sourceKey: String, key: String): String {
        val sourceHash = sourceKey.hashCode().toString(16)
        return "bookVar_default_${sourceHash}_$key"
    }

    private fun isSignedIntOrBlank(text: String): Boolean {
        if (text.isBlank()) return true
        return SIGNED_INT_PATTERN.matcher(text).matches()
    }

    private fun isValidHeaderValue(value: String): Boolean {
        return runCatching {
            Headers.Builder()[CommonHeaders.USER_AGENT] = value
        }.isSuccess
    }

    private fun showToast(stringRes: Int) {
        Toast.makeText(requireContext(), stringRes, Toast.LENGTH_SHORT).show()
    }

    private data class LegadoLoginUiItem(
        val name: String,
        val type: String,
        val action: String?,
    )

    companion object {

        private const val KEY_AUTH_USERNAME = "auth_username"
        private const val KEY_AUTH_PASSWORD = "auth_password"
        private const val KEY_JS_USERNAME = "js_username"
        private const val KEY_JS_PASSWORD = "js_password"
        private const val KEY_JS_LOGIN = "js_login"
        private const val KEY_JS_LOGOUT = "js_logout"
        private const val KEY_JS_COOKIE_PREFIX = "js_cookie_"
        private const val KEY_JS_COOKIE_SUBMIT = "js_cookie_submit"
        private const val KEY_JS_WEB_LOGIN = "js_web_login"
        private const val KEY_TVBOX_SITE_KEY = "tvbox_site_key"
        private const val KEY_TVBOX_SITE_API = "tvbox_site_api"
        private const val KEY_TVBOX_SITE_TYPE = "tvbox_site_type"
        private const val KEY_TVBOX_ROOT_SPIDER = "tvbox_root_spider"
        private const val KEY_TVBOX_SITE_JAR = "tvbox_site_jar"
        private const val KEY_TVBOX_SOURCE_LOCATOR = "tvbox_source_locator"
        private const val KEY_TVBOX_RUNTIME_STRATEGY = "tvbox_runtime_strategy"
        private const val KEY_TVBOX_RUNTIME_CANDIDATES = "tvbox_runtime_candidates"
        private const val KEY_TVBOX_STATUS = "tvbox_support_status"
        private const val KEY_LEGADO_SOURCE_VARIABLE = "legado_source_variable"
        private const val KEY_LEGADO_BOOK_DEFAULT_CUSTOM = "legado_book_default_custom"
        private const val KEY_LEGADO_LOGIN_FIELD_PREFIX = "legado_login_field_"
        private const val KEY_LEGADO_LOGIN_BUTTON_PREFIX = "legado_login_btn_"
        private const val KEY_LEGADO_LOGIN_CHECK = "legado_login_check"
        private const val KEY_LEGADO_LOGIN_CLEAR = "legado_login_clear"
        private const val KEY_LEGADO_STANDALONE_RUNTIME = "debug_standalone_legado_list_runtime"
        private const val LEGADO_SOURCE_PREFS = "legado_source_store"
        private const val LEGADO_BOOK_PREFS = "legado_book_store"
        private val SIGNED_INT_PATTERN = Pattern.compile("^-?\\d+$")

        fun newInstance(source: org.skepsun.kototoro.parsers.model.ContentSource) =
            SourceComposeSettingsFragment().withArgs(1) {
                putString(AppRouter.KEY_SOURCE, source.name)
            }
    }
}
