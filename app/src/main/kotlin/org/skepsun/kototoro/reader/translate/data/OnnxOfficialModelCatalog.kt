package org.skepsun.kototoro.reader.translate.data

data class OnnxOfficialModel(
	val id: String,
	val title: String,
	val version: String,
	val category: OnnxModelCategory = OnnxModelCategory.CLASSIC_TRANSLATION,
	val archiveUrl: String? = null,
	val sha256: String? = null,
	val files: List<OnnxModelFile> = emptyList(),
	val description: String,
)

enum class OnnxModelCategory {
	CLASSIC_TRANSLATION,
	BUBBLE_DETECTION,
	OCR_DETECTOR,
	OCR_RECOGNIZER,
	IMAGE_SUPER_RESOLUTION,
}

data class OnnxModelFile(
	val fileName: String,
	val downloadUrl: String,
	val sha256: String? = null,
)

object OnnxOfficialModelCatalog {
	const val source = "https://github.com/niedev/OnnxModelsEnhancer/releases, https://github.com/niedev/RTranslator/releases, https://www.modelscope.cn/models/PaddlePaddle/PP-OCRv6_medium_rec_onnx, https://www.modelscope.cn/models/hgmzhn/manga-translator-ui, https://huggingface.co/Skepsun/manga-translator-ui-onnx, https://huggingface.co/justinchuby/Hy-MT2-1.8B-ONNX, https://huggingface.co/ilaylow/PP_OCRv5_mobile_onnx, https://huggingface.co/l0wgear/manga-ocr-2025-onnx, https://huggingface.co/collections/PaddlePaddle/pp-ocrv6"

	val models = listOf(
		OnnxOfficialModel(
			id = "nllb_rtranslator_2_0_0",
			title = "NLLB (RTranslator v2.0.0)",
			version = "2.0.0",
			files = listOf(
				OnnxModelFile(
					fileName = "NLLB_cache_initializer.onnx",
					downloadUrl = "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_cache_initializer.onnx",
				),
				OnnxModelFile(
					fileName = "NLLB_decoder.onnx",
					downloadUrl = "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_decoder.onnx",
				),
				OnnxModelFile(
					fileName = "NLLB_embed_and_lm_head.onnx",
					downloadUrl = "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_embed_and_lm_head.onnx",
				),
				OnnxModelFile(
					fileName = "NLLB_encoder.onnx",
					downloadUrl = "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_encoder.onnx",
				),
				OnnxModelFile(
					fileName = "sentencepiece_bpe.model",
					downloadUrl = "https://raw.githubusercontent.com/niedev/RTranslator/v2.00/app/src/main/assets/sentencepiece_bpe.model",
				),
			),
			description = "RTranslator-compatible NLLB cache architecture (encoder + decoder + cache init + embed/lm).",
		),
		OnnxOfficialModel(
			id = "hy_mt_v1_0_0_beta",
			title = "Hy-MT2 1.8B (Q4 ONNX)",
			version = "hf-2026-07-01-q4-kquant-tie-cpu",
			files = listOf(
				OnnxModelFile(
					fileName = "model.onnx",
					downloadUrl = "https://huggingface.co/justinchuby/Hy-MT2-1.8B-ONNX/resolve/main/Q4_KQuant_tie/cpu/model.onnx",
				),
				OnnxModelFile(
					fileName = "model.onnx.data",
					downloadUrl = "https://huggingface.co/justinchuby/Hy-MT2-1.8B-ONNX/resolve/main/Q4_KQuant_tie/cpu/model.onnx.data",
				),
				OnnxModelFile(
					fileName = "tokenizer.json",
					downloadUrl = "https://huggingface.co/justinchuby/Hy-MT2-1.8B-ONNX/resolve/main/Q4_KQuant_tie/cpu/tokenizer.json",
				),
				OnnxModelFile(
					fileName = "tokenizer_config.json",
					downloadUrl = "https://huggingface.co/justinchuby/Hy-MT2-1.8B-ONNX/resolve/main/Q4_KQuant_tie/cpu/tokenizer_config.json",
				),
				OnnxModelFile(
					fileName = "genai_config.json",
					downloadUrl = "https://huggingface.co/justinchuby/Hy-MT2-1.8B-ONNX/resolve/main/Q4_KQuant_tie/cpu/genai_config.json",
				),
				OnnxModelFile(
					fileName = "chat_template.jinja",
					downloadUrl = "https://huggingface.co/justinchuby/Hy-MT2-1.8B-ONNX/resolve/main/Q4_KQuant_tie/cpu/chat_template.jinja",
				),
			),
			description = "Hy-MT2 1.8B multilingual translation model converted to ONNX Runtime GenAI format.",
		),
		OnnxOfficialModel(
			id = "madlad_v1_0_0_beta",
			title = "Madlad 400 (OnnxModelsEnhancer)",
			version = "v1.0.0-beta",
			archiveUrl = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Madlad.zip",
			sha256 = "ec3183e6106182938fe095d8e48057e2412ded278b560c2dc818c72cfedd682d",
			description = "Large multilingual translation model with optimized cache architecture.",
		),
		OnnxOfficialModel(
			id = "manga_bubble_yolo_hf_main",
			title = "Content Bubble YOLO",
			version = "hf-main",
			category = OnnxModelCategory.BUBBLE_DETECTION,
			files = listOf(
				OnnxModelFile(
					fileName = "yolo26s.onnx",
					downloadUrl = "https://huggingface.co/Kiuyha/Manga-Bubble-YOLO/resolve/main/onnx/yolo26s.onnx",
				),
			),
			description = "YOLO-based manga bubble/text region detector for ROI OCR pipeline.",
		),
		OnnxOfficialModel(
			id = "ysgyolo_obb",
			title = "YSG YOLO v11 OBB (1024)",
			version = "1.2_OS1.0",
			category = OnnxModelCategory.BUBBLE_DETECTION,
			files = listOf(
				OnnxModelFile(
					fileName = "ysgyolo_1.2_OS1.0.onnx",
					downloadUrl = "https://huggingface.co/Skepsun/ysg_1.2_onnx/resolve/main/ysgyolo_1.2_OS1.0.onnx",
				),
			),
			description = "YOLO11 OBB model for robust manga text detection.",
		),
		OnnxOfficialModel(
			id = "comic_text_and_bubble_detector_detr",
			title = "Comic Text & Bubble Detector (RT-DETR)",
			version = "hf-main",
			category = OnnxModelCategory.BUBBLE_DETECTION,
			files = listOf(
				OnnxModelFile(
					fileName = "detector.onnx",
					downloadUrl = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main/detector.onnx",
				),
			),
			description = "RT-DETR-v2 model fine-tuned on 11k manga/comics. Differentiates between text bubbles and free text.",
		),
		OnnxOfficialModel(
			id = "comic_text_detector_onnx",
			title = "Comic Text Detector ONNX",
			version = "hf-main",
			category = OnnxModelCategory.OCR_DETECTOR,
			files = listOf(
				OnnxModelFile(
					fileName = "comic-text-detector.onnx",
					downloadUrl = "https://huggingface.co/mayocream/comic-text-detector-onnx/resolve/main/comic-text-detector.onnx",
				),
			),
			description = "Comic text detector trained on Manga109-s for text-region detection.",
		),
		OnnxOfficialModel(
			id = "manga_default_det_20241225_onnx",
			title = "Manga Text Detector 2024-12-25",
			version = "2024-12-25",
			category = OnnxModelCategory.OCR_DETECTOR,
			files = listOf(
				OnnxModelFile(
					fileName = "detect-20241225.onnx",
					downloadUrl = "https://huggingface.co/Skepsun/manga-translator-ui-onnx/resolve/main/detect-20241225.onnx",
					sha256 = "0875cca4fda3d3c29dbc9b8d22b1230a9240f2ee187c83cc5068ee2006332f97",
				),
			),
			description = "DBNet ResNet34 detector used by manga-image-translator's default OCR pipeline.",
		),
		OnnxOfficialModel(
			id = "ppocrv5_mobile_det_onnx",
			title = "PP-OCRv5 Mobile Detector",
			version = "hf-main",
			category = OnnxModelCategory.OCR_DETECTOR,
			files = listOf(
				OnnxModelFile(
					fileName = "ppocrv5_det.onnx",
					downloadUrl = "https://huggingface.co/ilaylow/PP_OCRv5_mobile_onnx/resolve/main/ppocrv5_det.onnx",
				),
			),
			description = "PaddleOCR PP-OCRv5 mobile text detector converted to ONNX Runtime.",
		),
		OnnxOfficialModel(
			id = "ppocrv6_tiny_det_onnx",
			title = "PP-OCRv6 Tiny Detector",
			version = "hf-main",
			category = OnnxModelCategory.OCR_DETECTOR,
			files = listOf(
				OnnxModelFile(
					fileName = "inference.onnx",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx",
				),
				OnnxModelFile(
					fileName = "inference.yml",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.yml",
				),
			),
			description = "Official PP-OCRv6 tiny ONNX text detector for edge/mobile deployments.",
		),
		OnnxOfficialModel(
			id = "ppocrv6_small_det_onnx",
			title = "PP-OCRv6 Small Detector",
			version = "hf-main",
			category = OnnxModelCategory.OCR_DETECTOR,
			files = listOf(
				OnnxModelFile(
					fileName = "inference.onnx",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
				),
				OnnxModelFile(
					fileName = "inference.yml",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.yml",
				),
			),
			description = "Official PP-OCRv6 small ONNX text detector balancing speed and accuracy.",
		),
		OnnxOfficialModel(
			id = "ppocrv6_medium_det_onnx",
			title = "PP-OCRv6 Medium Detector",
			version = "hf-main",
			category = OnnxModelCategory.OCR_DETECTOR,
			files = listOf(
				OnnxModelFile(
					fileName = "inference.onnx",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx",
				),
				OnnxModelFile(
					fileName = "inference.yml",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.yml",
				),
			),
			description = "Official PP-OCRv6 medium ONNX text detector with higher accuracy for larger devices.",
		),
		OnnxOfficialModel(
			id = "ppocrv5_mobile_rec_onnx",
			title = "PP-OCRv5 Mobile Recognizer",
			version = "hf-main",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "ppocrv5_rec.onnx",
					downloadUrl = "https://huggingface.co/ilaylow/PP_OCRv5_mobile_onnx/resolve/main/ppocrv5_rec.onnx",
				),
				OnnxModelFile(
					fileName = "ppocrv5_dict.txt",
					downloadUrl = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/dict/ppocrv5_dict.txt",
				),
			),
			description = "PaddleOCR PP-OCRv5 mobile recognizer converted to ONNX Runtime.",
		),
		OnnxOfficialModel(
			id = "ppocrv5_server_rec_onnx",
			title = "PP-OCRv5 Server Recognizer",
			version = "v1.7.1",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "ppocrv5_rec.onnx",
					downloadUrl = "https://github.com/hgmzhn/manga-translator-ui/releases/download/v1.7.1/ch_PP-OCRv5_rec_server_infer.onnx",
				),
				OnnxModelFile(
					fileName = "ppocrv5_dict.txt",
					downloadUrl = "https://github.com/hgmzhn/manga-translator-ui/releases/download/v1.7.1/ppocrv5_dict.txt",
				),
			),
			description = "PaddleOCR PP-OCRv5 server recognizer. Larger model optimized for Japanese and Chinese text.",
		),
		OnnxOfficialModel(
			id = "ppocrv6_tiny_rec_onnx",
			title = "PP-OCRv6 Tiny Recognizer",
			version = "hf-main",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "inference.onnx",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx",
				),
				OnnxModelFile(
					fileName = "inference.yml",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml",
				),
			),
			description = "Official PP-OCRv6 tiny ONNX recognizer. The character dictionary is loaded from inference.yml.",
		),
		OnnxOfficialModel(
			id = "ppocrv6_small_rec_onnx",
			title = "PP-OCRv6 Small Recognizer",
			version = "hf-main",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "inference.onnx",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx",
				),
				OnnxModelFile(
					fileName = "inference.yml",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml",
				),
			),
			description = "Official PP-OCRv6 small ONNX recognizer. The character dictionary is loaded from inference.yml.",
		),
		OnnxOfficialModel(
			id = "ppocrv6_medium_rec_onnx",
			title = "PP-OCRv6 Medium Recognizer (Multilingual)",
			version = "modelscope-master",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "inference.onnx",
					downloadUrl = "https://www.modelscope.cn/models/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/master/inference.onnx",
					sha256 = "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
				),
				OnnxModelFile(
					fileName = "inference.yml",
					downloadUrl = "https://www.modelscope.cn/models/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/master/inference.yml",
					sha256 = "991b700facf5b50a7de193468207d5f4255b538dde0d312ae3b7c7a9b6873129",
				),
			),
			description = "Official PP-OCRv6 medium multilingual ONNX recognizer. Used by automatic selection for languages without a specialized PP-OCRv5 model.",
		),
		OnnxOfficialModel(
			id = "latin_ppocrv5_mobile_rec_onnx",
			title = "PP-OCRv5 Mobile Recognizer (Latin)",
			version = "manga-translator-ui-v1.8.0",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "latin_PP-OCRv5_rec_mobile_infer.onnx",
					downloadUrl = "https://www.modelscope.cn/models/hgmzhn/manga-translator-ui/resolve/master/latin_PP-OCRv5_rec_mobile_infer.onnx",
					sha256 = "614ffc2d6d3902d360fad7f1b0dd455ee45e877069d14c4e51a99dc4ef144409",
				),
				OnnxModelFile(
					fileName = "ppocrv5_latin_dict.txt",
					downloadUrl = "https://www.modelscope.cn/models/hgmzhn/manga-translator-ui/resolve/master/ppocrv5_latin_dict.txt",
					sha256 = "3c0a8a79b612653c25f765271714f71281e4e955962c153e272b7b8c1d2b13ff",
				),
			),
			description = "Manga Translator PP-OCRv5 mobile recognizer for English and other Latin-script languages.",
		),
		OnnxOfficialModel(
			id = "korean_ppocrv5_mobile_rec_onnx",
			title = "PP-OCRv5 Mobile Recognizer (Korean)",
			version = "manga-translator-ui-v1.7.1",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "korean_PP-OCRv5_rec_mobile_infer.onnx",
					downloadUrl = "https://www.modelscope.cn/models/hgmzhn/manga-translator-ui/resolve/master/korean_PP-OCRv5_rec_mobile_infer.onnx",
					sha256 = "cd6e2ea50f6943ca7271eb8c56a877a5a90720b7047fe9c41a2e541a25773c9b",
				),
				OnnxModelFile(
					fileName = "ppocrv5_korean_dict.txt",
					downloadUrl = "https://www.modelscope.cn/models/hgmzhn/manga-translator-ui/resolve/master/ppocrv5_korean_dict.txt",
					sha256 = "a88071c68c01707489baa79ebe0405b7beb5cca229f4fc94cc3ef992328802d7",
				),
			),
			description = "Manga Translator PP-OCRv5 mobile recognizer optimized for Korean text.",
		),
		OnnxOfficialModel(
			id = "thai_ppocrv5_mobile_rec_onnx",
			title = "PP-OCRv5 Mobile Recognizer (Thai)",
			version = "manga-translator-ui-master",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "thai_PP-OCRv5_rec_mobile_infer.onnx",
					downloadUrl = "https://www.modelscope.cn/models/hgmzhn/manga-translator-ui/resolve/master/thai_PP-OCRv5_rec_mobile_infer.onnx",
					sha256 = "2b6e56b1872200349e227574c25aeb0e0f9af9b8356e9ff5f75ac543a535669a",
				),
				OnnxModelFile(
					fileName = "ppocrv5_thai_dict.txt",
					downloadUrl = "https://www.modelscope.cn/models/hgmzhn/manga-translator-ui/resolve/master/ppocrv5_thai_dict.txt",
					sha256 = "57f5406f94bb6688fb7077f7be65f08bbd71cecf48c01ea26c522cb5c4836b7a",
				),
			),
			description = "Manga Translator PP-OCRv5 mobile recognizer optimized for Thai text.",
		),
		OnnxOfficialModel(
			id = "manga_48px_ctc_onnx",
			title = "Manga Translator 48px CTC",
			version = "hf-e241e93",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "ocr-48px-ctc.onnx",
					downloadUrl = "https://huggingface.co/Skepsun/manga-translator-ui-onnx/resolve/main/ocr-48px-ctc.onnx",
					sha256 = "da8d4b2c3ea236ad0c741677da29b77360230aee3380675bc95d4c15dc452497",
				),
				OnnxModelFile(
					fileName = "alphabet-all-v5.txt",
					downloadUrl = "https://huggingface.co/Skepsun/manga-translator-ui-onnx/resolve/main/alphabet-all-v5.txt",
					sha256 = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33",
				),
			),
			description = "Manga Translator 48px CTC recognizer for multilingual manga text.",
		),
		OnnxOfficialModel(
			id = "mangaocr_2025_onnx",
			title = "MangaOCR 2025 ONNX",
			version = "hf-main",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "encoder_model.onnx",
					downloadUrl = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/encoder_model.onnx",
				),
				OnnxModelFile(
					fileName = "decoder_model.onnx",
					downloadUrl = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/decoder_model.onnx",
				),
				OnnxModelFile(
					fileName = "generation_config.json",
					downloadUrl = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/generation_config.json",
				),
				OnnxModelFile(
					fileName = "preprocessor_config.json",
					downloadUrl = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/preprocessor_config.json",
				),
				OnnxModelFile(
					fileName = "special_tokens_map.json",
					downloadUrl = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/special_tokens_map.json",
				),
				OnnxModelFile(
					fileName = "tokenizer.json",
					downloadUrl = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/tokenizer.json",
				),
			),
			description = "MangaOCR encoder-decoder recognizer optimized for Japanese manga text.",
		),

		OnnxOfficialModel(
			id = "realesrgan_ncnn_x4plus_anime",
			title = "RealESRGAN 4x Anime (NCNN Native)",
			version = "v0.2.5.0",
			category = OnnxModelCategory.IMAGE_SUPER_RESOLUTION,
			archiveUrl = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip",
			files = emptyList(),
			description = "Official RealESRGAN anime-optimized super-resolution model for NCNN GPU acceleration (Includes realesrgan-x4plus-anime param and bin).",
		),
		OnnxOfficialModel(
			id = "realcugan_ncnn_2x_conservative",
			title = "RealCUGAN 2x Conservative (NCNN Native)",
			version = "master",
			category = OnnxModelCategory.IMAGE_SUPER_RESOLUTION,
			files = listOf(
				OnnxModelFile(
					fileName = "up2x-conservative.param",
					downloadUrl = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/master/models/models-se/up2x-conservative.param",
				),
				OnnxModelFile(
					fileName = "up2x-conservative.bin",
					downloadUrl = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/master/models/models-se/up2x-conservative.bin",
				)
			),
			description = "Official RealCUGAN 2x conservative super-resolution model for native NCNN GPU acceleration.",
		),
	)

	fun findById(id: String?): OnnxOfficialModel? {
		return models.firstOrNull { it.id == id }
	}
}
