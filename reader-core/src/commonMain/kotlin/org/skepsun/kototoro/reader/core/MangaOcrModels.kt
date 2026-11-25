package org.skepsun.kototoro.reader.core

import kotlin.math.exp
import kotlin.math.ln

object MangaOcrModels {
    data class File(val name: String, val sha256: String) {
        val url: String get() = "https://huggingface.co/Skepsun/manga-translator-ui-onnx/resolve/main/$name"
    }

    val detector = File("detect-20241225.onnx", "0875cca4fda3d3c29dbc9b8d22b1230a9240f2ee187c83cc5068ee2006332f97")
    val recognizer = File("ocr-48px-ctc.onnx", "da8d4b2c3ea236ad0c741677da29b77360230aee3380675bc95d4c15dc452497")
    val alphabet = File("alphabet-all-v5.txt", "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33")
}

data class MangaCtcResult(val text: String, val confidence: Float)

fun decodeManga48pxCtc(
    values: FloatArray,
    offset: Int,
    timeSteps: Int,
    classes: Int,
    dictionary: List<String>,
): MangaCtcResult {
    val text = StringBuilder()
    var previousIndex = 0
    var emittedLogProbabilitySum = 0f
    var emittedCount = 0
    for (timeStep in 0 until timeSteps) {
        val timeStepOffset = offset + timeStep * classes
        var bestIndex = 0
        var bestLogit = Float.NEGATIVE_INFINITY
        for (classIndex in 0 until classes) {
            val logit = values[timeStepOffset + classIndex]
            if (logit > bestLogit) {
                bestIndex = classIndex
                bestLogit = logit
            }
        }
        if (bestIndex != 0 && bestIndex != previousIndex) {
            var expSum = 0.0
            for (classIndex in 0 until classes) {
                expSum += exp((values[timeStepOffset + classIndex] - bestLogit).toDouble())
            }
            emittedLogProbabilitySum += -ln(expSum).toFloat()
            emittedCount++
            when (val character = dictionary.getOrNull(bestIndex)) {
                "<SP>" -> text.append(' ')
                null -> Unit
                else -> text.append(character)
            }
        }
        previousIndex = bestIndex
    }
    return MangaCtcResult(
        text = text.toString(),
        confidence = if (emittedCount == 0) 0f else exp(emittedLogProbabilitySum / emittedCount),
    )
}
