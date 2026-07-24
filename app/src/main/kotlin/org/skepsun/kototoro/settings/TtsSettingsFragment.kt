package org.skepsun.kototoro.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.reader.novel.tts.LegadoTtsParser
import org.skepsun.kototoro.reader.novel.tts.engine.HttpTTSEngine
import org.skepsun.kototoro.reader.novel.tts.engine.SystemTTSEngine
import org.skepsun.kototoro.reader.novel.tts.engine.TTSEngine
import org.skepsun.kototoro.reader.novel.tts.model.Token
import org.skepsun.kototoro.reader.novel.tts.model.TokenType
import org.skepsun.kototoro.reader.novel.tts.model.TtsHttpConfig
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.TtsSettingsScreen
import org.skepsun.kototoro.settings.compose.TtsSettingsUiState
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@Composable
fun TtsSettingsRoute(
    settings: AppSettings,
    coordinator: TtsSettingsCoordinator,
    modifier: Modifier = Modifier,
) {
    val enabled = settings.observeAsState(TtsSettingsCoordinator.KEY_TTS_ENABLED) {
        prefs.getBoolean(TtsSettingsCoordinator.KEY_TTS_ENABLED, true)
    }.value
    val engineType = settings.observeAsState(TtsSettingsCoordinator.KEY_TTS_ENGINE_TYPE) {
        prefs.getString(TtsSettingsCoordinator.KEY_TTS_ENGINE_TYPE, TtsSettingsCoordinator.ENGINE_SYSTEM)
            ?: TtsSettingsCoordinator.ENGINE_SYSTEM
    }.value
    val systemVoice = settings.observeAsState(TtsSettingsCoordinator.KEY_TTS_SYSTEM_VOICE) {
        prefs.getString(
            TtsSettingsCoordinator.KEY_TTS_SYSTEM_VOICE,
            TtsSettingsCoordinator.DEFAULT_VOICE_VALUE,
        ) ?: TtsSettingsCoordinator.DEFAULT_VOICE_VALUE
    }.value
    val legadoVoice = settings.observeAsState(TtsSettingsCoordinator.KEY_TTS_LEGADO_VOICE) {
        prefs.getString(TtsSettingsCoordinator.KEY_TTS_LEGADO_VOICE, "") ?: ""
    }.value
    val systemVoiceOptions by coordinator.systemVoiceOptionsFlow.collectAsState()
    val systemVoiceSummary by coordinator.systemVoiceSummaryFlow.collectAsState()
    val legadoVoiceOptions by coordinator.legadoVoiceOptionsFlow.collectAsState()
    val legadoVoiceSummary by coordinator.legadoVoiceSummaryFlow.collectAsState()
    val legadoConfigCount by coordinator.legadoConfigCountFlow.collectAsState()
    val isTestRunning by coordinator.isTestRunningFlow.collectAsState()

    TtsSettingsScreen(
        state = TtsSettingsUiState(
            enabled = enabled,
            engineType = engineType,
            systemVoice = systemVoice,
            systemVoiceOptions = systemVoiceOptions,
            systemVoiceSummary = systemVoiceSummary,
            legadoVoice = legadoVoice,
            legadoVoiceOptions = legadoVoiceOptions,
            legadoVoiceSummary = legadoVoiceSummary,
            legadoConfigCount = legadoConfigCount,
            isTestRunning = isTestRunning,
        ),
        onEnabledChange = { checked ->
            settings.prefs.edit { putBoolean(TtsSettingsCoordinator.KEY_TTS_ENABLED, checked) }
        },
        onEngineTypeChange = { value ->
            settings.prefs.edit { putString(TtsSettingsCoordinator.KEY_TTS_ENGINE_TYPE, value) }
        },
        onSystemVoiceChange = { value ->
            settings.prefs.edit { putString(TtsSettingsCoordinator.KEY_TTS_SYSTEM_VOICE, value) }
        },
        onLegadoVoiceChange = { value ->
            settings.prefs.edit { putString(TtsSettingsCoordinator.KEY_TTS_LEGADO_VOICE, value) }
        },
        onTestClick = coordinator::testTtsVoice,
        onImportClipboardClick = coordinator::importFromClipboard,
        onImportUrlClick = coordinator::importFromUrl,
        onManageSourcesClick = coordinator::manageLegadoSources,
        modifier = modifier,
    )
}

class TtsSettingsCoordinator(
    private val context: Context,
    private val appSettings: AppSettings,
) {

    val systemVoiceOptionsFlow = MutableStateFlow<List<SettingsChoiceOption<String>>>(emptyList())
    val systemVoiceSummaryFlow = MutableStateFlow<String?>(null)
    val legadoVoiceOptionsFlow = MutableStateFlow<List<SettingsChoiceOption<String>>>(emptyList())
    val legadoVoiceSummaryFlow = MutableStateFlow<String?>(null)
    val legadoConfigCountFlow = MutableStateFlow(0)
    val isTestRunningFlow = MutableStateFlow(false)

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var localTts: TextToSpeech? = null
    private var testMediaPlayer: MediaPlayer? = null
    private var isStarted = false

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_LEGADO_TTS_CONFIGS) {
            updateLegadoVoiceOptions()
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        appSettings.prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        updateLegadoVoiceOptions()
        initializeSystemVoices()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        runCatching {
            appSettings.prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        }
        localTts?.shutdown()
        localTts = null
        testMediaPlayer?.release()
        testMediaPlayer = null
        coroutineScope.cancel()
    }

    fun testTtsVoice() {
        Toast.makeText(context, R.string.tts_test_generating, Toast.LENGTH_SHORT).show()
        isTestRunningFlow.value = true

        coroutineScope.launch(Dispatchers.IO) {
            var engine: TTSEngine? = null
            var shouldResetState = true
            try {
                val prefs = appSettings.prefs
                val engineId = prefs.getString(KEY_TTS_ENGINE_TYPE, ENGINE_SYSTEM) ?: ENGINE_SYSTEM
                engine = if (engineId == ENGINE_LEGADO) {
                    val url = prefs.getString(KEY_TTS_LEGADO_VOICE, "") ?: ""
                    val config = parseLegadoConfigs().find { it.url == url }
                        ?: error(string(R.string.tts_legado_voice_unavailable))
                    HttpTTSEngine(
                        client = OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .build(),
                        config = config,
                        context = context,
                    )
                } else {
                    SystemTTSEngine(context)
                }

                val testText = string(R.string.tts_test_phrase)
                val testToken = Token(
                    id = System.currentTimeMillis(),
                    text = testText,
                    type = TokenType.NARRATION,
                    range = testText.indices,
                )
                val result = engine.synthesize(testToken)

                withContext(Dispatchers.Main) {
                    val audioData = result.getOrNull()
                        ?: error(result.exceptionOrNull()?.message ?: string(R.string.reader_translation_task_state_failed))
                    testMediaPlayer?.release()
                    val player = MediaPlayer.create(context, audioData.uri)
                        ?: error(string(R.string.reader_translation_task_state_failed))
                    testMediaPlayer = player
                    player.setOnCompletionListener {
                        it.release()
                        testMediaPlayer = null
                        isTestRunningFlow.value = false
                    }
                    player.setOnErrorListener { mediaPlayer, _, _ ->
                        mediaPlayer.release()
                        testMediaPlayer = null
                        isTestRunningFlow.value = false
                        true
                    }
                    player.start()
                    shouldResetState = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        string(R.string.tts_test_failed, e.message ?: ""),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                engine?.release()
                if (shouldResetState) {
                    withContext(Dispatchers.Main) {
                        isTestRunningFlow.value = false
                    }
                }
            }
        }
    }

    fun manageLegadoSources() {
        val configs = parseLegadoConfigs().toMutableList()
        if (configs.isEmpty()) {
            Toast.makeText(context, R.string.tts_legado_sources_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val names = configs.map { it.name }.toTypedArray()
        val checkedItems = BooleanArray(configs.size)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.tts_legado_manage_delete_title)
            .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(R.string.tts_legado_manage_delete_action) { _, _ ->
                val remaining = configs.filterIndexed { index, _ -> !checkedItems[index] }
                if (remaining.size == configs.size) return@setPositiveButton

                appSettings.prefs.edit {
                    putString(KEY_LEGADO_TTS_CONFIGS, Gson().toJson(remaining))
                    val currentVoice = appSettings.prefs.getString(KEY_TTS_LEGADO_VOICE, "")
                    if (currentVoice != null && remaining.none { it.url == currentVoice }) {
                        putString(KEY_TTS_LEGADO_VOICE, "")
                    }
                }
                Toast.makeText(
                    context,
                    string(R.string.tts_legado_sources_deleted, configs.size - remaining.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun importFromUrl() {
        val input = EditText(context).apply {
            hint = URL_HINT
            isSingleLine = true
        }

        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        params.setMargins(margin, 0, margin, 0)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.tts_legado_import_dialog_title)
            .setMessage(R.string.tts_legado_import_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.tts_legado_import_url) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    downloadAndImportUrl(url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(context, R.string.tts_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val configs = LegadoTtsParser.parseList(text)
                withContext(Dispatchers.Main) {
                    if (configs.isNotEmpty()) {
                        saveLegadoConfigs(configs)
                        Toast.makeText(
                            context,
                            string(R.string.tts_legado_import_success, configs.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(context, R.string.tts_legado_import_parse_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.tts_legado_import_parse_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initializeSystemVoices() {
        systemVoiceOptionsFlow.value = defaultSystemVoiceOptions()
        systemVoiceSummaryFlow.value = string(R.string.loading_)

        localTts?.shutdown()
        localTts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                systemVoiceSummaryFlow.value = string(R.string.tts_system_voice_unavailable)
                return@TextToSpeech
            }

            val voices = try {
                localTts?.voices?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            if (voices.isNotEmpty()) {
                val options = voices
                    .sortedBy { it.locale.displayName }
                    .map { voice ->
                        SettingsChoiceOption(
                            value = voice.name,
                            label = "${voice.locale.displayName} (${voice.name})",
                        )
                    }
                systemVoiceOptionsFlow.value = mergeCurrentSelection(
                    currentValue = appSettings.prefs.getString(KEY_TTS_SYSTEM_VOICE, DEFAULT_VOICE_VALUE),
                    options = options,
                    defaultOption = SettingsChoiceOption(
                        value = DEFAULT_VOICE_VALUE,
                        label = string(R.string.tts_system_voice_default),
                    ),
                )
                systemVoiceSummaryFlow.value = null
                return@TextToSpeech
            }

            val locales = try {
                localTts?.availableLanguages?.toList().orEmpty().sortedBy { it.displayName }
            } catch (_: Exception) {
                emptyList()
            }

            if (locales.isNotEmpty()) {
                val options = locales.map { locale ->
                    SettingsChoiceOption(
                        value = locale.toLanguageTag(),
                        label = locale.displayName,
                    )
                }
                systemVoiceOptionsFlow.value = mergeCurrentSelection(
                    currentValue = appSettings.prefs.getString(KEY_TTS_SYSTEM_VOICE, DEFAULT_VOICE_VALUE),
                    options = options,
                    defaultOption = SettingsChoiceOption(
                        value = DEFAULT_VOICE_VALUE,
                        label = string(R.string.tts_system_voice_default),
                    ),
                )
                systemVoiceSummaryFlow.value = string(R.string.tts_system_voice_fallback)
            } else {
                systemVoiceOptionsFlow.value = defaultSystemVoiceOptions()
                systemVoiceSummaryFlow.value = string(R.string.tts_system_voice_unavailable)
            }
        }
    }

    private fun updateLegadoVoiceOptions() {
        val configs = parseLegadoConfigs()
        legadoConfigCountFlow.value = configs.size

        if (configs.isEmpty()) {
            legadoVoiceOptionsFlow.value = emptyList()
            legadoVoiceSummaryFlow.value = string(R.string.tts_legado_voice_unavailable)
            if (!appSettings.prefs.getString(KEY_TTS_LEGADO_VOICE, "").isNullOrEmpty()) {
                appSettings.prefs.edit { putString(KEY_TTS_LEGADO_VOICE, "") }
            }
            return
        }

        legadoVoiceOptionsFlow.value = mergeCurrentSelection(
            currentValue = appSettings.prefs.getString(KEY_TTS_LEGADO_VOICE, ""),
            options = configs.map { config ->
                SettingsChoiceOption(
                    value = config.url,
                    label = config.name.take(30) + if (config.name.length > 30) "..." else "",
                )
            },
        )
        legadoVoiceSummaryFlow.value = null
    }

    private fun saveLegadoConfigs(newConfigs: List<TtsHttpConfig>) {
        val existingConfigs = parseLegadoConfigs().toMutableList()
        val urls = existingConfigs.map { it.url }.toSet()
        existingConfigs += newConfigs.filter { it.url !in urls }
        appSettings.prefs.edit {
            putString(KEY_LEGADO_TTS_CONFIGS, Gson().toJson(existingConfigs))
        }
    }

    private fun downloadAndImportUrl(url: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    check(body.isNotBlank()) { "Empty response body" }

                    val configs = LegadoTtsParser.parseList(body)
                    withContext(Dispatchers.Main) {
                        if (configs.isNotEmpty()) {
                            saveLegadoConfigs(configs)
                            Toast.makeText(
                                context,
                                string(R.string.tts_legado_import_success, configs.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(context, R.string.tts_legado_import_empty, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        string(R.string.tts_legado_import_download_failed, e.message ?: ""),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun parseLegadoConfigs(): List<TtsHttpConfig> {
        val currentJson = appSettings.prefs.getString(KEY_LEGADO_TTS_CONFIGS, "[]") ?: "[]"
        val type = object : TypeToken<List<TtsHttpConfig>>() {}.type
        return try {
            Gson().fromJson<List<TtsHttpConfig>>(currentJson, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun defaultSystemVoiceOptions(): List<SettingsChoiceOption<String>> {
        return listOf(
            SettingsChoiceOption(
                value = DEFAULT_VOICE_VALUE,
                label = string(R.string.tts_system_voice_default),
            ),
        )
    }

    private fun mergeCurrentSelection(
        currentValue: String?,
        options: List<SettingsChoiceOption<String>>,
        defaultOption: SettingsChoiceOption<String>? = null,
    ): List<SettingsChoiceOption<String>> {
        return buildList {
            defaultOption?.let(::add)
            if (!currentValue.isNullOrBlank() && options.none { it.value == currentValue } && currentValue != defaultOption?.value) {
                add(SettingsChoiceOption(currentValue, currentValue))
            }
            addAll(options)
        }.distinctBy { it.value }
    }

    private fun string(@StringRes resId: Int, vararg args: Any): String {
        return context.getString(resId, *args)
    }

    companion object {
        const val KEY_TTS_ENABLED = "tts_enabled"
        const val KEY_TTS_ENGINE_TYPE = "tts_engine_type"
        const val KEY_TTS_SYSTEM_VOICE = "tts_system_voice"
        const val KEY_TTS_LEGADO_VOICE = "tts_legado_voice"
        const val KEY_LEGADO_TTS_CONFIGS = "legado_tts_configs"
        const val ENGINE_SYSTEM = "SYSTEM"
        const val ENGINE_LEGADO = "LEGADO"
        const val DEFAULT_VOICE_VALUE = "default"
        const val URL_HINT = "https://..."
    }
}
