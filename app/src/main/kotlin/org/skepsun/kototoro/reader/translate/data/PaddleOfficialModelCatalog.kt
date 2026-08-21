package org.skepsun.kototoro.reader.translate.data

data class PaddleOfficialModel(
    val id: String,
    val title: String,
    val version: String,
    val downloadUrl: String,
)

object PaddleOfficialModelCatalog {

    const val source = "https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/model_list.md"

    private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/equationl/paddleocr4android/master/app/src/main/assets/models/"

    val models: List<PaddleOfficialModel> = listOf(
        // PP-OCRv4 (Recommended for Detection)
        PaddleOfficialModel(
            id = "ch_PP-OCRv4_det",
            title = "PP-OCRv4 Det (Default)",
            version = "v4.0.0",
            downloadUrl = GITHUB_RAW_BASE + "ch_PP-OCRv4/det.nb",
        ),
        PaddleOfficialModel(
            id = "ch_PP-OCRv4_rec",
            title = "PP-OCRv4 Rec (CN/EN)",
            version = "v4.0.0",
            downloadUrl = GITHUB_RAW_BASE + "ch_PP-OCRv4/rec.nb",
        ),
        PaddleOfficialModel(
            id = "ch_PP-OCRv4_cls",
            title = "PP-OCRv4 Cls (Orientation)",
            version = "v4.0.0",
            downloadUrl = GITHUB_RAW_BASE + "ch_PP-OCRv4/cls.nb",
        ),
        // PP-OCRv2
        PaddleOfficialModel(
            id = "ch_PP-OCRv2_det",
            title = "PP-OCRv2 Det (Legacy)",
            version = "v2.0.0",
            downloadUrl = GITHUB_RAW_BASE + "ch_PP-OCRv2/det_db.nb",
        ),
        PaddleOfficialModel(
            id = "ch_PP-OCRv2_rec",
            title = "PP-OCRv2 Rec (Legacy)",
            version = "v2.0.0",
            downloadUrl = GITHUB_RAW_BASE + "ch_PP-OCRv2/rec_crnn.nb",
        ),
        PaddleOfficialModel(
            id = "ch_PP-OCRv2_cls",
            title = "PP-OCRv2 Cls (Legacy)",
            version = "v2.0.0",
            downloadUrl = GITHUB_RAW_BASE + "ch_PP-OCRv2/cls.nb",
        ),
    )

    val ocrModels: List<PaddleOfficialModel> = models

    fun findById(id: String?): PaddleOfficialModel? {
        return models.firstOrNull { it.id == id }
    }
}
