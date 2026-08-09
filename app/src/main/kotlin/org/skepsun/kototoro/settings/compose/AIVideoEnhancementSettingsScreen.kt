package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.VideoRendererMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionShader
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.video.player.MpvShaderManager

@Composable
fun AIVideoEnhancementSettingsScreen(
    settings: AppSettings,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val mode by settings.observeAsState(AppSettings.KEY_VIDEO_SUPER_RES_MODE) { videoSuperResolutionMode }
    val shaderFiles = remember(context) {
        MpvShaderManager.ensureShadersCopied(context)
            .listFiles { _, name -> name.endsWith(".glsl", ignoreCase = true) }
            .orEmpty()
            .map { it.name }
            .sorted()
    }
    var customShaders by remember(settings) {
        mutableStateOf(settings.videoSuperResolutionCustomShaders.toShaderSet())
    }
    val modeEntries = VideoSuperResolutionMode.entries.map {
        SettingsChoiceOption(it.name, it.name)
    }
    val shaderEntries = VideoSuperResolutionShader.entries.map {
        SettingsChoiceOption(it.name, it.name)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
            ) {
                SettingsPreferenceSection(
                    title = stringResource(R.string.ai_video_enhancement_settings),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SettingsChoicePreference(
                        title = stringResource(R.string.video_super_resolution_mode),
                        value = mode.name,
                        options = modeEntries,
                        onValueChange = { value ->
                            val selectedMode = VideoSuperResolutionMode.valueOf(value)
                            val disablesGpuNext = selectedMode != VideoSuperResolutionMode.OFF &&
                                settings.videoRendererMode == VideoRendererMode.GPU_NEXT
                            settings.videoSuperResolutionMode = selectedMode
                            if (disablesGpuNext) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.video_super_resolution_disabled_gpu_next),
                                    )
                                }
                            }
                        },
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.video_super_resolution_submode_quality),
                        value = settings.prefs.getString(
                            AppSettings.KEY_VIDEO_SUPER_RES_QUALITY_SHADER,
                            VideoSuperResolutionShader.MODE_A.name,
                        ) ?: VideoSuperResolutionShader.MODE_A.name,
                        options = shaderEntries,
                        onValueChange = { value ->
                            settings.prefs.edit()
                                .putString(AppSettings.KEY_VIDEO_SUPER_RES_QUALITY_SHADER, value)
                                .apply()
                        },
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.video_super_resolution_submode_balanced),
                        value = settings.prefs.getString(
                            AppSettings.KEY_VIDEO_SUPER_RES_BALANCED_SHADER,
                            VideoSuperResolutionShader.MODE_B.name,
                        ) ?: VideoSuperResolutionShader.MODE_B.name,
                        options = shaderEntries,
                        onValueChange = { value ->
                            settings.prefs.edit()
                                .putString(AppSettings.KEY_VIDEO_SUPER_RES_BALANCED_SHADER, value)
                                .apply()
                        },
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.video_super_resolution_submode_performance),
                        value = settings.prefs.getString(
                            AppSettings.KEY_VIDEO_SUPER_RES_PERFORMANCE_SHADER,
                            VideoSuperResolutionShader.MODE_C.name,
                        ) ?: VideoSuperResolutionShader.MODE_C.name,
                        options = shaderEntries,
                        onValueChange = { value ->
                            settings.prefs.edit()
                                .putString(AppSettings.KEY_VIDEO_SUPER_RES_PERFORMANCE_SHADER, value)
                                .apply()
                        },
                    )
                    SettingsSectionDivider()
                    shaderFiles.forEach { fileName ->
                        SettingsSwitchPreference(
                            title = fileName,
                            summary = shaderDescription(fileName),
                            checked = fileName in customShaders,
                            onCheckedChange = { checked ->
                                customShaders = customShaders.toMutableSet().apply {
                                    if (checked) add(fileName) else remove(fileName)
                                }
                                settings.videoSuperResolutionCustomShaders = customShaders.joinToString(",")
                                settings.videoSuperResolutionShader = VideoSuperResolutionShader.CUSTOM
                            },
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private fun String.toShaderSet(): Set<String> = split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toSet()

@Composable
private fun shaderDescription(fileName: String): String? {
    val resources = LocalContext.current.resources
    val resourceName = "video_super_resolution_shader_desc_${fileName.substringBeforeLast('.').lowercase()}"
    val resourceId = resources.getIdentifier(resourceName, "string", LocalContext.current.packageName)
    return resourceId.takeIf { it != 0 }?.let(resources::getString)
}
