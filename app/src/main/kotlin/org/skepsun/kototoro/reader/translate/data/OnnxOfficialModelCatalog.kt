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
	const val source = "https://github.com/niedev/OnnxModelsEnhancer/releases, https://github.com/niedev/RTranslator/releases, https://huggingface.co/ilaylow/PP_OCRv5_mobile_onnx, https://huggingface.co/monkt/paddleocr-onnx, https://huggingface.co/l0wgear/manga-ocr-2025-onnx, https://huggingface.co/collections/PaddlePaddle/pp-ocrv6"

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
			title = "HY-MT 1.5 (OnnxModelsEnhancer)",
			version = "v1.0.0-beta",
			archiveUrl = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/HY-MT.zip",
			sha256 = "52109bad6bb72a5a0c1cf66a62547495f024b795864f8d380bcf46be7977d517",
			description = "Decoder-only large local translation model optimized for ONNX Runtime.",
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
			title = "PP-OCRv6 Medium Recognizer",
			version = "hf-main",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "inference.onnx",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx",
				),
				OnnxModelFile(
					fileName = "inference.yml",
					downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml",
				),
			),
			description = "Official PP-OCRv6 medium ONNX recognizer. The character dictionary is loaded from inference.yml.",
		),
		OnnxOfficialModel(
			id = "en_ppocrv5_mobile_rec_onnx",
			title = "PP-OCRv5 Mobile Recognizer (English)",
			version = "hf-main",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "ppocrv5_rec_en.onnx",
					downloadUrl = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/english/rec.onnx",
				),
				OnnxModelFile(
					fileName = "en_dict.txt",
					downloadUrl = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/english/dict.txt",
				),
			),
			description = "PaddleOCR PP-OCRv5 mobile recognizer optimized for English text.",
		),
		OnnxOfficialModel(
			id = "korean_ppocrv3_mobile_rec_onnx",
			title = "PP-OCRv5 Mobile Recognizer (Korean)",
			version = "hf-main",
			category = OnnxModelCategory.OCR_RECOGNIZER,
			files = listOf(
				OnnxModelFile(
					fileName = "ppocrv3_rec_ko.onnx",
					downloadUrl = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/korean/rec.onnx",
				),
				OnnxModelFile(
					fileName = "korean_dict.txt",
					downloadUrl = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/korean/dict.txt",
				),
			),
			description = "PaddleOCR PP-OCRv5 mobile recognizer optimized for Korean text.",
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
