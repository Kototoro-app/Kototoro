package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.reader.translate.data.OnnxModelCategory
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModel
import org.skepsun.kototoro.reader.translate.data.OnnxOfficialModelCatalog
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class OnnxBubbleDetectorEngine @Inject constructor(
	private val settings: AppSettings,
	private val onnxModelManager: OnnxModelManager,
) {

	enum class AttemptStatus {
		NO_MODEL_DOWNLOADED,
		RUNTIME_UNAVAILABLE,
		NO_BOXES,
		SUCCESS,
	}

	data class DetectedBox(
		val rect: Rect,
		val classId: Int,
		val score: Float,
	)

	data class DetectionResult(
		val boxes: List<DetectedBox>,
		val modelId: String,
		val backend: String,
		val parser: String,
		val rawBoxCount: Int,
		val decodedBoxCount: Int,
		val finalBoxCount: Int,
		val totalMs: Long,
	)

	data class DetectionAttempt(
		val status: AttemptStatus,
		val result: DetectionResult? = null,
		val modelId: String = "",
		val backend: String = "",
		val parser: String = "",
		val stage: String = "",
		val inputName: String = "",
		val inputShape: String = "",
		val outputNames: String = "",
		val error: String = "",
	)

	private data class RuntimeAttempt(
		val runtime: Runtime?,
		val backend: String = "",
		val parser: String = "",
		val stage: String = "",
		val inputName: String = "",
		val inputShape: String = "",
		val outputNames: String = "",
		val error: String = "",
	)

	private data class Runtime(
		val modelId: String,
		val modelFileName: String,
		val backend: String,
		val parser: ParserKind,
		val inputName: String,
		val inputWidth: Int?,
		val inputHeight: Int?,
		val inputShape: String,
		val outputName: String,
		val session: OrtSession,
	) {
		fun close() {
			runCatching { session.close() }
		}
	}

	private data class LetterboxBitmap(
		val bitmap: Bitmap,
		val inputWidth: Int,
		val inputHeight: Int,
		val scale: Float,
		val padX: Float,
		val padY: Float,
	)

	private data class ResizedBitmap(
		val bitmap: Bitmap,
		val inputWidth: Int,
		val inputHeight: Int,
	)

	private data class ScoredBox(
		val rect: Rect,
		val classId: Int,
		val score: Float,
	)

	private data class DecodedDetections(
		val boxes: List<DetectedBox>,
		val parser: String,
		val rawBoxCount: Int,
		val decodedBoxCount: Int,
	)

	private enum class ParserKind(val wireName: String) {
		GENERIC_YOLO("generic_yolo"),
		YSG_YOLO_OBB("ysg_yolo_obb"),
		YOLO26_E2E("yolo26_e2e"),
		RT_DETR("rt_detr"),
		AUTO_RT_DETR("auto_rt_detr"),
	}

	private val runtimeLock = Mutex()
	@Volatile
	private var runtime: Runtime? = null

	suspend fun detect(bitmap: Bitmap): DetectionResult? {
		return detectAttempt(bitmap).result
	}

	suspend fun detectAttempt(bitmap: Bitmap): DetectionAttempt {
		return detectAttempt(
			bitmap = bitmap,
			preferredModelId = settings.readerTranslationBubbleDetectorModelId,
			allowDownloadedFallback = true,
		)
	}

	suspend fun detectAttempt(bitmap: Bitmap, preferredModelId: String): DetectionAttempt {
		return detectAttempt(
			bitmap = bitmap,
			preferredModelId = preferredModelId,
			allowDownloadedFallback = false,
		)
	}

	private suspend fun detectAttempt(
		bitmap: Bitmap,
		preferredModelId: String,
		allowDownloadedFallback: Boolean,
	): DetectionAttempt {
		val model = resolveActiveModel(preferredModelId, allowDownloadedFallback) ?: return DetectionAttempt(
			status = AttemptStatus.NO_MODEL_DOWNLOADED,
			modelId = preferredModelId,
			stage = "resolve_model",
		)
		if (!allowDownloadedFallback) {
			runCatching {
				onnxModelManager.ensureModelReady(model)
			}.onFailure {
				return DetectionAttempt(
					status = AttemptStatus.RUNTIME_UNAVAILABLE,
					modelId = model.id,
					stage = "model_prepare_failed",
					error = it.message.orEmpty(),
				)
			}
		}
		val runtimeAttempt = ensureRuntime(model.id)
		val runtime = runtimeAttempt.runtime ?: return DetectionAttempt(
			status = AttemptStatus.RUNTIME_UNAVAILABLE,
			modelId = model.id,
			backend = runtimeAttempt.backend,
			parser = runtimeAttempt.parser,
			stage = runtimeAttempt.stage,
			inputName = runtimeAttempt.inputName,
			inputShape = runtimeAttempt.inputShape,
			outputNames = runtimeAttempt.outputNames,
			error = runtimeAttempt.error,
		)
		val startMs = System.currentTimeMillis()
		return runCatching {
			runInterruptible(Dispatchers.Default) {
				val decoded = detectInternal(bitmap, runtime)
				val result = DetectionResult(
					boxes = decoded.boxes,
					modelId = runtime.modelId,
					backend = runtime.backend,
					parser = decoded.parser,
					rawBoxCount = decoded.rawBoxCount,
					decodedBoxCount = decoded.decodedBoxCount,
					finalBoxCount = decoded.boxes.size,
					totalMs = System.currentTimeMillis() - startMs,
				)
				if (result.boxes.isEmpty()) {
					DetectionAttempt(
						status = AttemptStatus.NO_BOXES,
						result = result,
						modelId = result.modelId,
						backend = result.backend,
						parser = result.parser,
						stage = "decode_output",
						inputName = runtime.inputName,
						inputShape = runtime.inputShape,
						outputNames = runtime.outputName,
					)
				} else {
					DetectionAttempt(
						status = AttemptStatus.SUCCESS,
						result = result,
						modelId = result.modelId,
						backend = result.backend,
						parser = result.parser,
						stage = "success",
						inputName = runtime.inputName,
						inputShape = runtime.inputShape,
						outputNames = runtime.outputName,
					)
				}
			}
		}.onFailure {
			it.printStackTraceDebug()
			log { "onnx bubble detect failed model=${runtime.modelId} err=${it.message.orEmpty()}" }
		}.getOrElse {
			DetectionAttempt(
				status = AttemptStatus.RUNTIME_UNAVAILABLE,
				modelId = runtime.modelId,
				backend = runtime.backend,
				parser = runtime.parser.wireName,
				stage = "detect_internal",
				inputName = runtime.inputName,
				inputShape = runtime.inputShape,
				outputNames = runtime.outputName,
				error = it.message.orEmpty(),
			)
		}
	}

	private fun resolveActiveModel(
		preferredId: String,
		allowDownloadedFallback: Boolean,
	): OnnxOfficialModel? {
		if (!allowDownloadedFallback) {
			return OnnxOfficialModelCatalog.findById(preferredId)
				?.takeIf { it.category == OnnxModelCategory.BUBBLE_DETECTION }
		}
		val downloaded = OnnxOfficialModelCatalog.models.filter {
			it.category == OnnxModelCategory.BUBBLE_DETECTION && onnxModelManager.isModelDownloaded(it.id)
		}
		if (downloaded.isEmpty()) return null
		return downloaded.firstOrNull { it.id == preferredId }
			?: downloaded.first().takeIf { allowDownloadedFallback }
	}

	private suspend fun ensureRuntime(modelId: String): RuntimeAttempt {
		val current = runtime
		if (current != null && current.modelId == modelId) {
			return RuntimeAttempt(
				runtime = current,
				backend = current.backend,
				parser = current.parser.wireName,
				stage = "cache_hit",
				inputName = current.inputName,
				inputShape = current.inputShape,
				outputNames = current.outputName,
			)
		}
		return runtimeLock.withLock {
			val again = runtime
			if (again != null && again.modelId == modelId) {
				return@withLock RuntimeAttempt(
					runtime = again,
					backend = again.backend,
					parser = again.parser.wireName,
					stage = "cache_hit",
					inputName = again.inputName,
					inputShape = again.inputShape,
					outputNames = again.outputName,
				)
			}
			runtime?.close()
			runtime = null
			val modelDir = onnxModelManager.getModelDir(modelId)
			val attempt = createRuntimeWithFallback(modelId, modelDir)
			runtime = attempt.runtime
			attempt
		}
	}

	private fun createRuntimeWithFallback(modelId: String, modelDir: File): RuntimeAttempt {
		val modelPath = modelDir.walkTopDown().firstOrNull { file ->
			file.isFile && file.extension.equals("onnx", ignoreCase = true)
		} ?: return RuntimeAttempt(
			runtime = null,
			parser = resolveParserKind(modelId, "").wireName,
			stage = "model_file_missing",
			error = modelDir.absolutePath,
		)
		val parserKind = resolveParserKind(modelId, modelPath.name)
		var lastFailure = RuntimeAttempt(
			runtime = null,
			parser = parserKind.wireName,
			stage = "session_not_attempted",
			error = modelPath.absolutePath,
		)
		for (useGpu in listOf(true, false)) {
			val backend = if (useGpu) "NNAPI" else "CPU"
			val options = createSessionOptions(useGpu)
			try {
				val env = OrtEnvironment.getEnvironment()
				val session = env.createSession(modelPath.absolutePath, options)
				val inputEntry = session.inputInfo.entries.firstOrNull { it.key == "images" || it.key == "input" }
					?: session.inputInfo.entries.firstOrNull { (it.value.info as? TensorInfo)?.shape?.size == 4 }
					?: session.inputInfo.entries.firstOrNull()
				if (inputEntry == null) {
					lastFailure = RuntimeAttempt(
						runtime = null,
						backend = backend,
						parser = parserKind.wireName,
						stage = "missing_input",
						outputNames = session.outputNames.joinToString(","),
						error = modelPath.name,
					)
					session.close()
					continue
				}
				val outputName = session.outputNames.firstOrNull()
				if (outputName == null) {
					lastFailure = RuntimeAttempt(
						runtime = null,
						backend = backend,
						parser = parserKind.wireName,
						stage = "missing_output",
						inputName = inputEntry.key,
						inputShape = (inputEntry.value.info as? TensorInfo)?.shape?.joinToString("x").orEmpty(),
						error = modelPath.name,
					)
					session.close()
					continue
				}
				val inputInfo = inputEntry.value.info as? TensorInfo
				if (inputInfo == null) {
					lastFailure = RuntimeAttempt(
						runtime = null,
						backend = backend,
						parser = parserKind.wireName,
						stage = "invalid_input_info",
						inputName = inputEntry.key,
						outputNames = session.outputNames.joinToString(","),
						error = inputEntry.value.info.toString(),
					)
					session.close()
					continue
				}
				val shape = inputInfo.shape
				val shapeText = shape.joinToString("x")
				if (shape.size < 4) {
					lastFailure = RuntimeAttempt(
						runtime = null,
						backend = backend,
						parser = parserKind.wireName,
						stage = "invalid_input_shape_rank",
						inputName = inputEntry.key,
						inputShape = shapeText,
						outputNames = session.outputNames.joinToString(","),
						error = modelPath.name,
					)
					session.close()
					continue
				}
				val inputHeight = resolveDim(shape[shape.size - 2])
				val inputWidth = resolveDim(shape[shape.size - 1])
				val dynamicSpatialInput = inputHeight == null || inputWidth == null
				log {
					"onnx bubble runtime ready model=$modelId file=${modelPath.name} backend=$backend " +
						"parser=${parserKind.wireName} inputShape=$shapeText " +
						if (dynamicSpatialInput) "dynamicSpatial=true" else "input=${inputWidth}x$inputHeight"
				}
				return RuntimeAttempt(
					runtime = Runtime(
						modelId = modelId,
						modelFileName = modelPath.name,
						backend = backend,
						parser = parserKind,
						inputName = inputEntry.key,
						inputWidth = inputWidth,
						inputHeight = inputHeight,
						inputShape = shapeText,
						outputName = outputName,
						session = session,
					),
					backend = backend,
					parser = parserKind.wireName,
					stage = if (dynamicSpatialInput) "ready_dynamic_input" else "ready",
					inputName = inputEntry.key,
					inputShape = shapeText,
					outputNames = session.outputNames.joinToString(","),
				)
			} catch (e: Throwable) {
				e.printStackTraceDebug()
				lastFailure = RuntimeAttempt(
					runtime = null,
					backend = backend,
					parser = parserKind.wireName,
					stage = "session_create_failed",
					error = e.message.orEmpty(),
				)
				log { "onnx bubble runtime init failed model=$modelId backend=$backend err=${e.message.orEmpty()}" }
			} finally {
				runCatching { options.close() }
			}
		}
		return lastFailure
	}

	private fun createSessionOptions(useGpu: Boolean): OrtSession.SessionOptions {
		val options = OrtSession.SessionOptions()
		options.setMemoryPatternOptimization(false)
		options.setCPUArenaAllocator(false)
		options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
		if (useGpu) {
			runCatching { options.addNnapi() }
		}
		return options
	}

	private fun detectInternal(source: Bitmap, runtime: Runtime): DecodedDetections {
		val readableSource = ensureReadableBitmap(source)
		val isDetr = runtime.parser == ParserKind.RT_DETR || runtime.parser == ParserKind.AUTO_RT_DETR
		val isResizeOnly = isDetr || runtime.parser == ParserKind.GENERIC_YOLO
		val letterboxed = if (isResizeOnly) null else createLetterboxBitmap(
			source = readableSource,
			targetWidth = resolveDynamicInputWidth(readableSource, runtime),
			targetHeight = resolveDynamicInputHeight(readableSource, runtime),
		)
		val resized = if (isResizeOnly) createResizedBitmap(
			source = readableSource,
			targetWidth = resolveDynamicInputWidth(readableSource, runtime),
			targetHeight = resolveDynamicInputHeight(readableSource, runtime),
		) else null
		val preparedBitmap = resized?.bitmap ?: letterboxed!!.bitmap
		val preparedWidth = resized?.inputWidth ?: letterboxed!!.inputWidth
		val preparedHeight = resized?.inputHeight ?: letterboxed!!.inputHeight
		var sizesTensor: OnnxTensor? = null
		val inputTensor = createInputTensor(preparedBitmap, preparedWidth, preparedHeight)
		var sessionResult: OrtSession.Result? = null
		try {
			val inputs = mutableMapOf<String, OnnxTensor>()
			inputs[runtime.inputName] = inputTensor
			if ("orig_target_sizes" in runtime.session.inputNames) {
				val env = OrtEnvironment.getEnvironment()
				val targetSizes = if (isDetr) {
					longArrayOf(readableSource.height.toLong(), readableSource.width.toLong())
				} else {
					longArrayOf(preparedHeight.toLong(), preparedWidth.toLong())
				}
				sizesTensor = OnnxTensor.createTensor(
					env,
					java.nio.LongBuffer.wrap(targetSizes),
					longArrayOf(1, 2)
				)
				inputs["orig_target_sizes"] = sizesTensor
			}
			sessionResult = runtime.session.run(inputs)

			val nmsThreshold = settings.getBubbleDetectorNms(runtime.modelId, isDetr)
			
			if (isDetr) {
				return decodeRtDetrOutput(
					sessionResult = sessionResult,
					parser = runtime.parser,
					sourceWidth = readableSource.width,
					sourceHeight = readableSource.height,
					inputWidth = preparedWidth,
					inputHeight = preparedHeight,
					nmsThreshold = nmsThreshold,
				)
			}

			val outputTensor = sessionResult.get(runtime.outputName).orElse(null) as? OnnxTensor
				?: return DecodedDetections(
					boxes = emptyList(),
					parser = runtime.parser.wireName,
					rawBoxCount = 0,
					decodedBoxCount = 0,
				)
			return decodeOutputTensor(
				parser = runtime.parser,
				tensor = outputTensor,
				sourceWidth = readableSource.width,
				sourceHeight = readableSource.height,
				inputWidth = preparedWidth,
				inputHeight = preparedHeight,
				scaleX = if (isResizeOnly) preparedWidth.toFloat() / readableSource.width else letterboxed!!.scale,
				scaleY = if (isResizeOnly) preparedHeight.toFloat() / readableSource.height else letterboxed!!.scale,
				padX = if (isResizeOnly) 0f else letterboxed!!.padX,
				padY = if (isResizeOnly) 0f else letterboxed!!.padY,
				nmsThreshold = nmsThreshold,
			)
		} finally {
			runCatching { sizesTensor?.close() }
			runCatching { inputTensor.close() }
			runCatching { sessionResult?.close() }
			if (preparedBitmap !== readableSource) {
				preparedBitmap.recycle()
			}
			if (readableSource !== source) {
				readableSource.recycle()
			}
		}
	}

	private fun resolveDynamicInputWidth(source: Bitmap, runtime: Runtime): Int {
		runtime.inputWidth?.let { return it }
		if (runtime.modelId == COMIC_TEXT_AND_BUBBLE_DETECTOR_ID &&
			(runtime.parser == ParserKind.RT_DETR || runtime.parser == ParserKind.AUTO_RT_DETR)
		) {
			return COMIC_TEXT_AND_BUBBLE_DETECTOR_INPUT_SIZE
		}
		return chooseDynamicInputSize(source.width, source.height)
	}

	private fun resolveDynamicInputHeight(source: Bitmap, runtime: Runtime): Int {
		runtime.inputHeight?.let { return it }
		if (runtime.modelId == COMIC_TEXT_AND_BUBBLE_DETECTOR_ID &&
			(runtime.parser == ParserKind.RT_DETR || runtime.parser == ParserKind.AUTO_RT_DETR)
		) {
			return COMIC_TEXT_AND_BUBBLE_DETECTOR_INPUT_SIZE
		}
		return chooseDynamicInputSize(source.height, source.width)
	}

	private fun ensureReadableBitmap(bitmap: Bitmap): Bitmap {
		if (bitmap.config != Bitmap.Config.HARDWARE) {
			return bitmap
		}
		return bitmap.copy(Bitmap.Config.ARGB_8888, false)
	}

	private fun createLetterboxBitmap(source: Bitmap, targetWidth: Int?, targetHeight: Int?): LetterboxBitmap {
		val resolvedWidth = targetWidth ?: chooseDynamicInputSize(source.width, source.height)
		val resolvedHeight = targetHeight ?: chooseDynamicInputSize(source.height, source.width)
		val scale = min(
			resolvedWidth / source.width.toFloat(),
			resolvedHeight / source.height.toFloat(),
		)
		val scaledWidth = max(1, (source.width * scale).roundToInt())
		val scaledHeight = max(1, (source.height * scale).roundToInt())
		val padX = ((resolvedWidth - scaledWidth) / 2f).coerceAtLeast(0f)
		val padY = ((resolvedHeight - scaledHeight) / 2f).coerceAtLeast(0f)
		val output = Bitmap.createBitmap(resolvedWidth, resolvedHeight, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(output)
		canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
		canvas.drawBitmap(
			source,
			null,
			android.graphics.Rect(
				padX.roundToInt(),
				padY.roundToInt(),
				(padX + scaledWidth).roundToInt(),
				(padY + scaledHeight).roundToInt(),
			),
			paint,
		)
		return LetterboxBitmap(
			bitmap = output,
			inputWidth = resolvedWidth,
			inputHeight = resolvedHeight,
			scale = scale,
			padX = padX,
			padY = padY,
		)
	}

	private fun createResizedBitmap(source: Bitmap, targetWidth: Int?, targetHeight: Int?): ResizedBitmap {
		val resolvedWidth = targetWidth ?: chooseDynamicInputSize(source.width, source.height)
		val resolvedHeight = targetHeight ?: chooseDynamicInputSize(source.height, source.width)
		val output = Bitmap.createBitmap(resolvedWidth, resolvedHeight, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(output)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
		canvas.drawBitmap(
			source,
			null,
			android.graphics.Rect(0, 0, resolvedWidth, resolvedHeight),
			paint,
		)
		return ResizedBitmap(
			bitmap = output,
			inputWidth = resolvedWidth,
			inputHeight = resolvedHeight,
		)
	}

	private fun chooseDynamicInputSize(primary: Int, secondary: Int): Int {
		val longest = max(primary, secondary).coerceAtLeast(MIN_DYNAMIC_INPUT_SIZE)
		val capped = longest.coerceAtMost(MAX_DYNAMIC_INPUT_SIZE)
		val aligned = ((capped + MODEL_STRIDE - 1) / MODEL_STRIDE) * MODEL_STRIDE
		return aligned.coerceIn(MIN_DYNAMIC_INPUT_SIZE, MAX_DYNAMIC_INPUT_SIZE)
	}

	private fun createInputTensor(bitmap: Bitmap, width: Int, height: Int): OnnxTensor {
		val pixels = IntArray(width * height)
		bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
		val channels = FloatArray(width * height * 3)
		val planeSize = width * height
		for (index in pixels.indices) {
			val pixel = pixels[index]
			channels[index] = ((pixel shr 16) and 0xFF) / 255f
			channels[planeSize + index] = ((pixel shr 8) and 0xFF) / 255f
			channels[planeSize * 2 + index] = (pixel and 0xFF) / 255f
		}
		return OnnxTensor.createTensor(
			OrtEnvironment.getEnvironment(),
			FloatBuffer.wrap(channels),
			longArrayOf(1L, 3L, height.toLong(), width.toLong()),
		)
	}

	private fun decodeOutputTensor(
		parser: ParserKind,
		tensor: OnnxTensor,
		sourceWidth: Int,
		sourceHeight: Int,
		inputWidth: Int,
		inputHeight: Int,
		scaleX: Float,
		scaleY: Float,
		padX: Float,
		padY: Float,
		nmsThreshold: Float,
	): DecodedDetections {
		val info = tensor.info as? TensorInfo ?: return DecodedDetections(
			boxes = emptyList(),
			parser = parser.wireName,
			rawBoxCount = 0,
			decodedBoxCount = 0,
		)
		val shape = info.shape
		val flat = ArrayList<Float>(estimateElementCount(shape))
		flattenNumericTensor(tensor.value, flat)
		if (flat.isEmpty()) {
			return DecodedDetections(
				boxes = emptyList(),
				parser = parser.wireName,
				rawBoxCount = 0,
				decodedBoxCount = 0,
			)
		}
		val layout = resolveOutputLayout(shape) ?: return DecodedDetections(
			boxes = emptyList(),
			parser = parser.wireName,
			rawBoxCount = 0,
			decodedBoxCount = 0,
		)
		val scored = when (parser) {
			ParserKind.YOLO26_E2E -> decodeYolo26Boxes(
				layout = layout,
				flat = flat,
				sourceWidth = sourceWidth,
				sourceHeight = sourceHeight,
				inputWidth = inputWidth,
				inputHeight = inputHeight,
				scaleX = scaleX,
				scaleY = scaleY,
				padX = padX,
				padY = padY,
				nmsThreshold = nmsThreshold,
			)
			ParserKind.GENERIC_YOLO -> decodeGenericYoloBoxes(
				layout = layout,
				flat = flat,
				sourceWidth = sourceWidth,
				sourceHeight = sourceHeight,
				inputWidth = inputWidth,
				inputHeight = inputHeight,
				scaleX = scaleX,
				scaleY = scaleY,
				padX = padX,
				padY = padY,
				nmsThreshold = nmsThreshold,
				isObb = false,
			)
			ParserKind.YSG_YOLO_OBB -> decodeGenericYoloBoxes(
				layout = layout,
				flat = flat,
				sourceWidth = sourceWidth,
				sourceHeight = sourceHeight,
				inputWidth = inputWidth,
				inputHeight = inputHeight,
				scaleX = scaleX,
				scaleY = scaleY,
				padX = padX,
				padY = padY,
				nmsThreshold = nmsThreshold,
				isObb = true,
			)
			ParserKind.RT_DETR, ParserKind.AUTO_RT_DETR -> error("RT_DETR is handled externally")
		}
		val finalBoxes = when (parser) {
			ParserKind.YOLO26_E2E -> scored.sortedByDescending { it.score }.take(MAX_OUTPUT_BOXES)
			ParserKind.YSG_YOLO_OBB -> applyNms(scored, kotlin.math.max(nmsThreshold, 0.85f), MAX_OUTPUT_BOXES_OBB)
			ParserKind.GENERIC_YOLO -> applyNms(scored, nmsThreshold)
			ParserKind.RT_DETR, ParserKind.AUTO_RT_DETR -> error("RT_DETR is handled externally")
		}
			return DecodedDetections(
				boxes = finalBoxes.map { DetectedBox(rect = it.rect, classId = it.classId, score = it.score) },
				parser = parser.wireName,
				rawBoxCount = layout.count,
				decodedBoxCount = scored.size,
			)
		}

	private fun decodeRtDetrOutput(
		sessionResult: OrtSession.Result,
		parser: ParserKind,
		sourceWidth: Int,
		sourceHeight: Int,
		inputWidth: Int,
		inputHeight: Int,
		nmsThreshold: Float,
	): DecodedDetections {
		val labelsTensor = sessionResult.get("labels").orElse(null) as? OnnxTensor
		val boxesTensor = sessionResult.get("boxes").orElse(null) as? OnnxTensor
		val scoresTensor = sessionResult.get("scores").orElse(null) as? OnnxTensor

		if (labelsTensor == null || boxesTensor == null || scoresTensor == null) {
			return DecodedDetections(
				boxes = emptyList(),
				parser = parser.wireName,
				rawBoxCount = 0,
				decodedBoxCount = 0,
			)
		}

		val labelsShape = (labelsTensor?.info as? TensorInfo)?.shape ?: longArrayOf()
		val boxesShape = (boxesTensor?.info as? TensorInfo)?.shape ?: longArrayOf()
		val scoresShape = (scoresTensor?.info as? TensorInfo)?.shape ?: longArrayOf()

		val labelsFlat = mutableListOf<Float>()
		flattenNumericTensor(labelsTensor.value, labelsFlat)

		val boxesFlat = mutableListOf<Float>()
		flattenNumericTensor(boxesTensor.value, boxesFlat)

		val scoresFlat = mutableListOf<Float>()
		flattenNumericTensor(scoresTensor.value, scoresFlat)

		val numQueries = boxesShape.getOrNull(1)?.toInt() ?: 300
		val isScores3D = scoresShape.size == 3
		val numClasses = if (isScores3D) scoresShape.getOrNull(2)?.toInt() ?: 1 else 1
		val count = if (isScores3D) numQueries else scoresFlat.size

		var isCxCyWh = false
		var isNormalized = true
		var maxCoord = -1f
		for (i in 0 until (boxesFlat.size / 4)) {
			val x1 = boxesFlat.getOrNull(i * 4) ?: 0f
			val x2 = boxesFlat.getOrNull(i * 4 + 2) ?: 0f
			if (x2 < x1) isCxCyWh = true
			maxCoord = maxOf(maxCoord, x1, boxesFlat.getOrNull(i * 4 + 1) ?: 0f, x2, boxesFlat.getOrNull(i * 4 + 3) ?: 0f)
		}
		if (maxCoord > 2.5f) isNormalized = false

		var isLogits = false
		for (i in 0 until min(100, scoresFlat.size)) {
			val s = scoresFlat[i]
			if (s < 0f || s > 1.05f) {
				isLogits = true
				break
			}
		}

		val scored = ArrayList<ScoredBox>(count)
		for (index in 0 until count) {
			val rawScore: Float
			val label: Int

			if (isScores3D) {
				var maxScore = -Float.MAX_VALUE
				var bestClass = 0
				// Skip the last class (usually background no_object in DETR models)
				val trueClasses = if (numClasses > 1) numClasses - 1 else numClasses
				for (c in 0 until trueClasses) {
					val s = scoresFlat.getOrNull(index * numClasses + c) ?: -Float.MAX_VALUE
					if (s > maxScore) {
						maxScore = s
						bestClass = c
					}
				}
				rawScore = maxScore
				label = bestClass
			} else {
				rawScore = scoresFlat.getOrNull(index) ?: 0f
				label = labelsFlat.getOrNull(index)?.toInt() ?: 0
			}

			val score = if (isLogits) (1f / (1f + kotlin.math.exp(-rawScore))) else rawScore
			
			// DETR predictions are often lower confidence initially but highly accurate spatially.
			if (score < 0.15f) continue

			val bx = index * 4
			val c1 = boxesFlat.getOrNull(bx) ?: 0f
			val c2 = boxesFlat.getOrNull(bx + 1) ?: 0f
			val c3 = boxesFlat.getOrNull(bx + 2) ?: 0f
			val c4 = boxesFlat.getOrNull(bx + 3) ?: 0f

			val scaleX = if (isNormalized) inputWidth.toFloat() else 1f
			val scaleY = if (isNormalized) inputHeight.toFloat() else 1f

			val rX1: Float
			val rX2: Float
			val rY1: Float
			val rY2: Float

			if (isCxCyWh) {
				val cx = c1 * scaleX
				val cy = c2 * scaleY
				val w = c3 * scaleX
				val h = c4 * scaleY
				rX1 = cx - w / 2f
				rY1 = cy - h / 2f
				rX2 = cx + w / 2f
				rY2 = cy + h / 2f
			} else {
				rX1 = c1 * scaleX
				rY1 = c2 * scaleY
				rX2 = c3 * scaleX
				rY2 = c4 * scaleY
			}

			val left = if (isNormalized) {
				min(rX1, rX2) * sourceWidth
			} else {
				(min(rX1, rX2) / inputWidth) * sourceWidth
			}
			val top = if (isNormalized) {
				min(rY1, rY2) * sourceHeight
			} else {
				(min(rY1, rY2) / inputHeight) * sourceHeight
			}
			val right = if (isNormalized) {
				max(rX1, rX2) * sourceWidth
			} else {
				(max(rX1, rX2) / inputWidth) * sourceWidth
			}
			val bottom = if (isNormalized) {
				max(rY1, rY2) * sourceHeight
			} else {
				(max(rY1, rY2) / inputHeight) * sourceHeight
			}

			val rect = Rect(
				left.roundToInt().coerceIn(0, sourceWidth - 1),
				top.roundToInt().coerceIn(0, sourceHeight - 1),
				right.roundToInt().coerceIn(1, sourceWidth),
				bottom.roundToInt().coerceIn(1, sourceHeight),
			)
			if (rect.width() < MIN_BOX_SIDE || rect.height() < MIN_BOX_SIDE) continue
			val areaRatio = rect.width().toFloat() * rect.height().toFloat() / (sourceWidth * sourceHeight).toFloat().coerceAtLeast(1f)
			if (areaRatio !in MIN_AREA_RATIO..MAX_AREA_RATIO) continue

			scored += ScoredBox(rect = rect, classId = label, score = score)
		}

		// DETR natively produces discrete predictions and does not require NMS. 
		// Applying aggressive IoU=0.45 NMS destroys naturally connecting bubbles or two-part dialogue balloons!
		// We use the dynamic NMS threshold matching the default of 0.85 (unless configured by user).
		val finalBoxes = applyNms(scored, nmsThreshold)

		return DecodedDetections(
			boxes = finalBoxes.map { DetectedBox(rect = it.rect, classId = it.classId, score = it.score) },
			parser = parser.wireName,
			rawBoxCount = count,
			decodedBoxCount = finalBoxes.size,
		)
	}

	private fun decodeGenericYoloBoxes(
		layout: OutputLayout,
		flat: List<Float>,
		sourceWidth: Int,
		sourceHeight: Int,
		inputWidth: Int,
		inputHeight: Int,
		scaleX: Float,
		scaleY: Float,
		padX: Float,
		padY: Float,
		nmsThreshold: Float,
		isObb: Boolean = false,
	): List<ScoredBox> {
		val excludeLastN = if (isObb) 1 else 0
		val scored = ArrayList<ScoredBox>(layout.count)
		for (index in 0 until layout.count) {
			val cxRaw = layout.read(flat, index, 0)
			val cyRaw = layout.read(flat, index, 1)
			val wRaw = layout.read(flat, index, 2)
			val hRaw = layout.read(flat, index, 3)
			val (score, classId) = layout.readConfidence(flat, index, excludeLastN)
			if (score < MIN_SCORE_THRESHOLD) continue
			val normalized = max(maxOf(cxRaw, cyRaw), maxOf(wRaw, hRaw)) <= 2.5f
			val cx = if (normalized) cxRaw * inputWidth else cxRaw
			val cy = if (normalized) cyRaw * inputHeight else cyRaw
			val width = if (normalized) wRaw * inputWidth else wRaw
			val height = if (normalized) hRaw * inputHeight else hRaw
			if (width <= 1f || height <= 1f) continue
			
			val finalWidth: Float
			val finalHeight: Float
			if (isObb) {
				val angleRad = layout.read(flat, index, layout.attributes - 1)
				val cosA = kotlin.math.abs(kotlin.math.cos(angleRad))
				val sinA = kotlin.math.abs(kotlin.math.sin(angleRad))
				finalWidth = width * cosA + height * sinA
				finalHeight = width * sinA + height * cosA
			} else {
				finalWidth = width
				finalHeight = height
			}

			val left = ((cx - finalWidth / 2f) - padX) / scaleX
			val top = ((cy - finalHeight / 2f) - padY) / scaleY
			val right = ((cx + finalWidth / 2f) - padX) / scaleX
			val bottom = ((cy + finalHeight / 2f) - padY) / scaleY
			val rect = Rect(
				left.roundToInt().coerceIn(0, sourceWidth - 1),
				top.roundToInt().coerceIn(0, sourceHeight - 1),
				right.roundToInt().coerceIn(1, sourceWidth),
				bottom.roundToInt().coerceIn(1, sourceHeight),
			)
			val minSide = if (isObb) MIN_BOX_SIDE_OBB else MIN_BOX_SIDE
			if (rect.width() < minSide || rect.height() < minSide) continue
			val areaRatio = rect.width().toFloat() * rect.height().toFloat() / (sourceWidth * sourceHeight).toFloat().coerceAtLeast(1f)
			if (areaRatio !in MIN_AREA_RATIO..MAX_AREA_RATIO) continue
			scored += ScoredBox(rect = rect, classId = classId, score = score)
		}
		return scored
	}

	private fun decodeYolo26Boxes(
		layout: OutputLayout,
		flat: List<Float>,
		sourceWidth: Int,
		sourceHeight: Int,
		inputWidth: Int,
		inputHeight: Int,
		scaleX: Float,
		scaleY: Float,
		padX: Float,
		padY: Float,
		nmsThreshold: Float,
	): List<ScoredBox> {
		val scored = ArrayList<ScoredBox>(layout.count)
		for (index in 0 until layout.count) {
			val x1Raw = layout.read(flat, index, 0)
			val y1Raw = layout.read(flat, index, 1)
			val x2Raw = layout.read(flat, index, 2)
			val y2Raw = layout.read(flat, index, 3)
			val score = layout.read(flat, index, 4)
			if (score < MIN_SCORE_THRESHOLD) continue
			val normalized = max(maxOf(x1Raw, y1Raw), maxOf(x2Raw, y2Raw)) <= 2.5f
			val x1 = if (normalized) x1Raw * inputWidth else x1Raw
			val y1 = if (normalized) y1Raw * inputHeight else y1Raw
			val x2 = if (normalized) x2Raw * inputWidth else x2Raw
			val y2 = if (normalized) y2Raw * inputHeight else y2Raw
			val left = ((min(x1, x2)) - padX) / scaleX
			val top = ((min(y1, y2)) - padY) / scaleY
			val right = ((max(x1, x2)) - padX) / scaleX
			val bottom = ((max(y1, y2)) - padY) / scaleY
			val rect = Rect(
				left.roundToInt().coerceIn(0, sourceWidth - 1),
				top.roundToInt().coerceIn(0, sourceHeight - 1),
				right.roundToInt().coerceIn(1, sourceWidth),
				bottom.roundToInt().coerceIn(1, sourceHeight),
			)
			if (rect.width() < MIN_BOX_SIDE || rect.height() < MIN_BOX_SIDE) continue
			val areaRatio = rect.width().toFloat() * rect.height().toFloat() / (sourceWidth * sourceHeight).toFloat().coerceAtLeast(1f)
			if (areaRatio !in MIN_AREA_RATIO..MAX_AREA_RATIO) continue
			scored += ScoredBox(rect = rect, classId = 0, score = score)
		}
		return scored
	}

	private fun applyNms(boxes: List<ScoredBox>, threshold: Float, maxBoxes: Int = MAX_OUTPUT_BOXES): List<ScoredBox> {
		if (boxes.isEmpty()) return emptyList()
		val sorted = boxes.sortedByDescending { it.score }.toMutableList()
		val selected = mutableListOf<ScoredBox>()
		while (sorted.isNotEmpty() && selected.size < maxBoxes) {
			val head = sorted.removeAt(0)
			selected += head
			sorted.removeAll { candidate ->
				computeIoU(head.rect, candidate.rect) >= threshold
			}
		}
		return selected
	}

	private fun computeIoU(a: Rect, b: Rect): Float {
		val left = max(a.left, b.left)
		val top = max(a.top, b.top)
		val right = min(a.right, b.right)
		val bottom = min(a.bottom, b.bottom)
		val width = (right - left).coerceAtLeast(0)
		val height = (bottom - top).coerceAtLeast(0)
		val intersection = width * height
		if (intersection <= 0) return 0f
		val union = a.width() * a.height() + b.width() * b.height() - intersection
		if (union <= 0) return 0f
		return intersection.toFloat() / union.toFloat()
	}

	private fun flattenNumericTensor(value: Any?, out: MutableList<Float>) {
		when (value) {
			null -> Unit
			is Number -> out += value.toFloat()
			is FloatArray -> value.forEach { out += it }
			is DoubleArray -> value.forEach { out += it.toFloat() }
			is IntArray -> value.forEach { out += it.toFloat() }
			is LongArray -> value.forEach { out += it.toFloat() }
			is Array<*> -> value.forEach { flattenNumericTensor(it, out) }
		}
	}

	private fun estimateElementCount(shape: LongArray): Int {
		var total = 1L
		for (dim in shape) {
			if (dim <= 0L) continue
			total *= dim
			if (total >= 1_000_000L) break
		}
		return total.coerceAtMost(1_000_000L).toInt()
	}

	private fun resolveDim(value: Long): Int? {
		if (value <= 0L || value > 4096L) return null
		return value.toInt()
	}

	private fun resolveOutputLayout(shape: LongArray): OutputLayout? {
		if (shape.size == 3 && shape[0] == 1L) {
			val d1 = shape[1].toInt()
			val d2 = shape[2].toInt()
			if (d1 > 4 && d2 > 0) {
				return OutputLayout(count = d2, attributes = d1, transposed = true)
			}
			if (d2 > 4 && d1 > 0) {
				return OutputLayout(count = d1, attributes = d2, transposed = false)
			}
		}
		if (shape.size == 2) {
			val d0 = shape[0].toInt()
			val d1 = shape[1].toInt()
			if (d1 > 4 && d0 > 0) {
				return OutputLayout(count = d0, attributes = d1, transposed = false)
			}
			if (d0 > 4 && d1 > 0) {
				return OutputLayout(count = d1, attributes = d0, transposed = true)
			}
		}
		return null
	}

	private fun resolveParserKind(modelId: String, modelFileName: String): ParserKind {
		val fingerprint = "$modelId $modelFileName".lowercase()
		return if ("yolo26" in fingerprint) ParserKind.YOLO26_E2E
		else if ("ysgyolo" in fingerprint || "ysg_yolo" in fingerprint) ParserKind.YSG_YOLO_OBB
		else if ("detr" in fingerprint) ParserKind.RT_DETR
		else ParserKind.GENERIC_YOLO
	}

	private data class OutputLayout(
		val count: Int,
		val attributes: Int,
		val transposed: Boolean,
	) {
		fun read(flat: List<Float>, index: Int, attribute: Int): Float {
			val flatIndex = if (transposed) {
				attribute * count + index
			} else {
				index * attributes + attribute
			}
			return flat.getOrElse(flatIndex) { 0f }
		}

		fun readConfidence(flat: List<Float>, index: Int, excludeLastN: Int = 0): Pair<Float, Int> {
			val limit = (attributes - excludeLastN).coerceAtLeast(4)
			if (limit <= 4) return 0f to 0
			if (limit == 5) return read(flat, index, 4) to 0
			var bestScore = 0f
			var bestClass = 0
			for (attribute in 4 until limit) {
				val score = read(flat, index, attribute)
				if (score > bestScore) {
					bestScore = score
					bestClass = attribute - 4
				}
			}
			return bestScore to bestClass
		}
	}

	private inline fun log(message: () -> String) {
		if (settings.isReaderTranslationDebugLogsEnabled) {
			android.util.Log.d(LOG_TAG, message())
		}
	}

	private companion object {
		const val LOG_TAG = "ReaderTranslate"
		const val MODEL_STRIDE = 32
		const val MIN_DYNAMIC_INPUT_SIZE = 640
		const val MAX_DYNAMIC_INPUT_SIZE = 1280
		const val COMIC_TEXT_AND_BUBBLE_DETECTOR_ID = "comic_text_and_bubble_detector_detr"
		const val COMIC_TEXT_AND_BUBBLE_DETECTOR_INPUT_SIZE = 640
		const val MIN_BOX_SIDE = 24
		const val MIN_BOX_SIDE_OBB = 12
		const val MAX_OUTPUT_BOXES = 24
		const val MAX_OUTPUT_BOXES_OBB = 64
		const val MIN_SCORE_THRESHOLD = 0.20f
		const val MIN_AREA_RATIO = 0.0008f
		const val MAX_AREA_RATIO = 0.45f
		const val NMS_IOU_THRESHOLD = 0.45f
	}
}
