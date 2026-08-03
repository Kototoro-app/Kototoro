package org.skepsun.kototoro.reader.ui.config

import android.graphics.Bitmap
import android.view.View
import androidx.collection.scatterSetOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderImageScalingQuality
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderOcrEngine
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.util.MediatorStateFlow
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.reader.domain.ReaderColorFilter

data class ReaderSettings(
	val zoomMode: ZoomMode,
	val background: ReaderBackground,
	val colorFilter: ReaderColorFilter?,
	val imageScalingQuality: ReaderImageScalingQuality,
	val isReaderOptimizationEnabled: Boolean,
	val isReaderPreloadReductionEnabled: Boolean,
	val bitmapConfig: Bitmap.Config,
	val isPagesNumbersEnabled: Boolean,
	val isPagesCropEnabledStandard: Boolean,
	val isPagesCropEnabledWebtoon: Boolean,
	val isReaderDoubleCoverPage: Boolean,
	val isTranslationEnabled: Boolean,
	val isTranslationShowTranslated: Boolean,
	val translationSourceLanguage: String,
	val translationTargetLanguage: String,
	val translationOcrEngine: ReaderOcrEngine,
	val translationMode: ReaderTranslationMode,
	val translationApiProviderPreset: String,
	val translationApiEndpoint: String,
	val translationApiModel: String,
	val translationBubbleGroupingTuning: String,
	val isTranslationBubbleGroupingEnabled: Boolean,
	val translationOverlayCompactness: String,
	val translationRenderStyle: String,
	val translationPaddleModelPath: String,
	val translationPaddleOfficialModelId: String,
	val translationPaddleDetModelId: String,
	val translationOcrDetectionMaxSide: Int,
	val translationOcrDetectionThreshold: Float,
	val translationOcrMinBoxSize: Int,
	val translationOcrRecognitionThreshold: Float,
	val translationOcrRecognitionMaxWidth: Int,
	val translationOcrRecognitionBatchSize: Int,
	val translationPaddleModelUrl: String,
	val translationPaddleModelVersion: String,
	val translationPaddleModelSha256: String,
	val translationPaddleDetModelUrl: String,
	val translationPaddleDetModelVersion: String,
	val translationPaddleDetModelSha256: String,
	val translationPaddleRecModelUrl: String,
	val translationPaddleRecModelVersion: String,
	val translationPaddleRecModelSha256: String,
	val translationPaddleClsModelUrl: String,
	val translationPaddleClsModelVersion: String,
	val translationPaddleClsModelSha256: String,
	val translationOnnxModelId: String,
	val isSuperResolutionEnabled: Boolean,
	val superResolutionEngine: String,
	val superResolutionAnime4kMode: String,
	val superResolutionModel: String,
	val superResolutionNoiseLevel: Int,
	val superResolutionCacheLimitMb: Int,
) {

	private constructor(settings: AppSettings, colorFilterOverride: ReaderColorFilter?) : this(
		zoomMode = settings.zoomMode,
		background = settings.readerBackground,
		colorFilter = colorFilterOverride?.takeUnless { it.isEmpty } ?: settings.readerColorFilter,
		imageScalingQuality = settings.readerImageScalingQuality,
		isReaderOptimizationEnabled = settings.isReaderOptimizationEnabled,
		isReaderPreloadReductionEnabled = settings.isReaderPreloadReductionEnabled,
		bitmapConfig = if (settings.is32BitColorsEnabled) {
			Bitmap.Config.ARGB_8888
		} else {
			Bitmap.Config.RGB_565
		},
		isPagesNumbersEnabled = settings.isPagesNumbersEnabled,
		isPagesCropEnabledStandard = settings.isPagesCropEnabled(ReaderMode.STANDARD),
		isPagesCropEnabledWebtoon = settings.isPagesCropEnabled(ReaderMode.WEBTOON),
		isReaderDoubleCoverPage = settings.isReaderDoubleCoverPage,
		isTranslationEnabled = settings.isReaderTranslationEnabled,
		isTranslationShowTranslated = settings.isReaderTranslationShowTranslated,
		translationSourceLanguage = settings.readerTranslationSourceLanguage,
		translationTargetLanguage = settings.readerTranslationTargetLanguage,
		translationOcrEngine = settings.readerTranslationOcrEngine,
		translationMode = settings.readerTranslationMode,
		translationApiProviderPreset = settings.readerTranslationApiProviderPreset,
		translationApiEndpoint = settings.readerTranslationApiEndpoint,
		translationApiModel = settings.readerTranslationApiModel,
		translationBubbleGroupingTuning = settings.readerTranslationBubbleGroupingTuning,
		isTranslationBubbleGroupingEnabled = settings.isReaderTranslationBubbleGroupingEnabled,
		translationOverlayCompactness = settings.readerTranslationOverlayCompactness,
		translationRenderStyle = settings.readerTranslationRenderStyle,
		translationPaddleModelPath = settings.readerTranslationPaddleModelPath,
		translationPaddleOfficialModelId = settings.readerTranslationPaddleOfficialModelId,
		translationPaddleDetModelId = settings.readerTranslationPaddleDetModelId,
		translationOcrDetectionMaxSide = settings.readerTranslationOcrDetectionMaxSide,
		translationOcrDetectionThreshold = settings.readerTranslationOcrDetectionThreshold,
		translationOcrMinBoxSize = settings.readerTranslationOcrMinBoxSize,
		translationOcrRecognitionThreshold = settings.readerTranslationOcrRecognitionThreshold,
		translationOcrRecognitionMaxWidth = settings.readerTranslationOcrRecognitionMaxWidth,
		translationOcrRecognitionBatchSize = settings.readerTranslationOcrRecognitionBatchSize,
		translationPaddleModelUrl = settings.readerTranslationPaddleModelUrl,
		translationPaddleModelVersion = settings.readerTranslationPaddleModelVersion,
		translationPaddleModelSha256 = settings.readerTranslationPaddleModelSha256,
		translationPaddleDetModelUrl = settings.readerTranslationPaddleDetModelUrl,
		translationPaddleDetModelVersion = settings.readerTranslationPaddleDetModelVersion,
		translationPaddleDetModelSha256 = settings.readerTranslationPaddleDetModelSha256,
		translationPaddleRecModelUrl = settings.readerTranslationPaddleRecModelUrl,
		translationPaddleRecModelVersion = settings.readerTranslationPaddleRecModelVersion,
		translationPaddleRecModelSha256 = settings.readerTranslationPaddleRecModelSha256,
		translationPaddleClsModelUrl = settings.readerTranslationPaddleClsModelUrl,
		translationPaddleClsModelVersion = settings.readerTranslationPaddleClsModelVersion,
		translationPaddleClsModelSha256 = settings.readerTranslationPaddleClsModelSha256,
		translationOnnxModelId = settings.readerTranslationOnnxModelId,
		isSuperResolutionEnabled = settings.isReaderSuperResolutionEnabled,
		superResolutionEngine = settings.readerSuperResolutionEngine,
		superResolutionAnime4kMode = settings.readerSuperResolutionAnime4kMode,
		superResolutionModel = settings.readerSuperResolutionModel,
		superResolutionNoiseLevel = settings.readerSuperResolutionNoiseLevel,
		superResolutionCacheLimitMb = settings.readerSuperResolutionCacheLimitMb,
	)

	fun applyBackground(view: View) {
		view.background = background.resolve(view.context)
		view.backgroundTintList = if (background.isLight(view.context)) {
			colorFilter?.getBackgroundTint()
		} else {
			null
		}
	}

	fun isPagesCropEnabled(isWebtoon: Boolean) = if (isWebtoon) {
		isPagesCropEnabledWebtoon
	} else {
		isPagesCropEnabledStandard
	}

	fun translationSignature(): String = buildString {
		append(isTranslationEnabled)
		append('|')
		append(isTranslationShowTranslated)
		append('|')
		append(translationSourceLanguage)
		append('|')
		append(translationTargetLanguage)
		append('|')
		append(translationOcrEngine.name)
		append('|')
		append(translationMode.name)
		append('|')
		append(translationApiProviderPreset)
		append('|')
		append(translationApiEndpoint)
		append('|')
		append(translationApiModel)
		append('|')
		append(translationBubbleGroupingTuning)
		append('|')
		append(isTranslationBubbleGroupingEnabled)
		append('|')
		append(translationOverlayCompactness)
		append('|')
		append(translationRenderStyle)
		append('|')
		append(translationPaddleModelPath)
		append('|')
		append(translationPaddleOfficialModelId)
		append('|')
		append(translationPaddleDetModelId)
		append('|')
		append(translationOcrDetectionMaxSide)
		append('|')
		append(translationOcrDetectionThreshold)
		append('|')
		append(translationOcrMinBoxSize)
		append('|')
		append(translationOcrRecognitionThreshold)
		append('|')
		append(translationOcrRecognitionMaxWidth)
		append('|')
		append(translationOcrRecognitionBatchSize)
		append('|')
		append(translationPaddleModelUrl)
		append('|')
		append(translationPaddleModelVersion)
		append('|')
		append(translationPaddleModelSha256)
		append('|')
		append(translationPaddleDetModelUrl)
		append('|')
		append(translationPaddleDetModelVersion)
		append('|')
		append(translationPaddleDetModelSha256)
		append('|')
		append(translationPaddleRecModelUrl)
		append('|')
		append(translationPaddleRecModelVersion)
		append('|')
		append(translationPaddleRecModelSha256)
		append('|')
		append(translationPaddleClsModelUrl)
		append('|')
		append(translationPaddleClsModelVersion)
		append('|')
		append(translationPaddleClsModelSha256)
	}

	fun translationDisplaySignature(): String = buildString {
		append(isTranslationEnabled)
		append('|')
		append(isTranslationShowTranslated)
	}

	fun translationContentSignature(): String = buildString {
		append(isTranslationEnabled)
		append('|')
		append(translationSourceLanguage)
		append('|')
		append(translationTargetLanguage)
		append('|')
		append(translationOcrEngine.name)
		append('|')
		append(translationMode.name)
		append('|')
		append(translationApiProviderPreset)
		append('|')
		append(translationApiEndpoint)
		append('|')
		append(translationApiModel)
		append('|')
		append(translationBubbleGroupingTuning)
		append('|')
		append(isTranslationBubbleGroupingEnabled)
		append('|')
		append(translationOverlayCompactness)
		append('|')
		append(translationRenderStyle)
		append('|')
		append(translationPaddleModelPath)
		append('|')
		append(translationPaddleOfficialModelId)
		append('|')
		append(translationPaddleDetModelId)
		append('|')
		append(translationOcrDetectionMaxSide)
		append('|')
		append(translationOcrDetectionThreshold)
		append('|')
		append(translationOcrMinBoxSize)
		append('|')
		append(translationOcrRecognitionThreshold)
		append('|')
		append(translationOcrRecognitionMaxWidth)
		append('|')
		append(translationOcrRecognitionBatchSize)
		append('|')
		append(translationPaddleModelUrl)
		append('|')
		append(translationPaddleModelVersion)
		append('|')
		append(translationPaddleModelSha256)
		append('|')
		append(translationPaddleDetModelUrl)
		append('|')
		append(translationPaddleDetModelVersion)
		append('|')
		append(translationPaddleDetModelSha256)
		append('|')
		append(translationPaddleRecModelUrl)
		append('|')
		append(translationPaddleRecModelVersion)
		append('|')
		append(translationPaddleRecModelSha256)
		append('|')
		append(translationPaddleClsModelUrl)
		append('|')
		append(translationPaddleClsModelVersion)
		append('|')
		append(translationPaddleClsModelSha256)
		append('|')
		append(translationOnnxModelId)
	}

	class Producer @AssistedInject constructor(
		@Assisted private val mangaId: Flow<Long>,
		private val settings: AppSettings,
		private val mangaDataRepository: ContentDataRepository,
	) : MediatorStateFlow<ReaderSettings>(ReaderSettings(settings, null)) {

		private val settingsKeys = scatterSetOf(
			AppSettings.KEY_ZOOM_MODE,
			AppSettings.KEY_PAGES_NUMBERS,
			AppSettings.KEY_READER_BACKGROUND,
			AppSettings.KEY_32BIT_COLOR,
			AppSettings.KEY_READER_OPTIMIZE,
			AppSettings.KEY_READER_REDUCE_PRELOAD,
			AppSettings.KEY_CF_CONTRAST,
			AppSettings.KEY_CF_BRIGHTNESS,
			AppSettings.KEY_CF_INVERTED,
			AppSettings.KEY_CF_GRAYSCALE,
			AppSettings.KEY_READER_IMAGE_SCALING_QUALITY,
			AppSettings.KEY_READER_CROP,
			AppSettings.KEY_READER_DOUBLE_COVER_PAGE,
			AppSettings.KEY_READER_TRANSLATION_ENABLED,
			AppSettings.KEY_READER_TRANSLATION_SHOW_TRANSLATED,
			AppSettings.KEY_READER_TRANSLATION_SOURCE_LANG,
			AppSettings.KEY_READER_TRANSLATION_TARGET_LANG,
			AppSettings.KEY_READER_TRANSLATION_OCR_ENGINE,
			AppSettings.KEY_READER_TRANSLATION_MODE,
			AppSettings.KEY_READER_TRANSLATION_API_PROVIDER_PRESET,
			AppSettings.KEY_READER_TRANSLATION_API_ENDPOINT,
			AppSettings.KEY_READER_TRANSLATION_API_KEY,
			AppSettings.KEY_READER_TRANSLATION_API_MODEL,
			AppSettings.KEY_READER_TRANSLATION_BUBBLE_GROUPING_TUNING,
			AppSettings.KEY_READER_TRANSLATION_OVERLAY_COMPACTNESS,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_MODEL_PATH,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_OFFICIAL_MODEL_ID,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_ID,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_MODEL_URL,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_MODEL_VERSION,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_MODEL_SHA256,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_URL,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_VERSION,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_DET_MODEL_SHA256,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_REC_MODEL_URL,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_REC_MODEL_VERSION,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_REC_MODEL_SHA256,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_URL,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_VERSION,
			AppSettings.KEY_READER_TRANSLATION_PADDLE_CLS_MODEL_SHA256,
			AppSettings.KEY_READER_SUPER_RESOLUTION_ENABLED,
			AppSettings.KEY_READER_SUPER_RESOLUTION_ENGINE,
			AppSettings.KEY_READER_SUPER_RESOLUTION_ANIME4K_MODE,
			AppSettings.KEY_READER_SUPER_RESOLUTION_MODEL,
			AppSettings.KEY_READER_SUPER_RESOLUTION_NOISE_LEVEL,
			AppSettings.KEY_READER_SUPER_RESOLUTION_CACHE_LIMIT,
		)
		private var job: Job? = null

		override fun onActive() {
			assert(job?.isActive != true)
			job?.cancel()
			job = processLifecycleScope.launch(Dispatchers.Default) {
				observeImpl()
			}
		}

		override fun onInactive() {
			job?.cancel()
			job = null
		}

		private suspend fun observeImpl() {
			combine(
				mangaId.flatMapLatest { mangaDataRepository.observeColorFilter(it) },
				settings.observeChanges().filter { x -> x == null || x in settingsKeys }.onStart { emit(null) },
			) { mangaCf, settingsKey ->
				ReaderSettings(settings, mangaCf)
			}.collect {
				publishValue(it)
			}
		}

		@AssistedFactory
		interface Factory {

			fun create(mangaId: Flow<Long>): Producer
		}
	}
}
