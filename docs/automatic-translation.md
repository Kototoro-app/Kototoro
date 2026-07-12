# Automatic Translation

Kototoro can translate manga and novel content inside the reader. Translation is an optional reader enhancement; normal reading continues when it is disabled or a page cannot be translated.

## How Translation Works

Kototoro uses a two-stage pipeline: text is detected and recognized on the page, then the recognized text is sent to the configured translator. The translated result is rendered as a layer over the original image.

The pipeline supports two translation modes:

| Mode | Actual behavior |
| --- | --- |
| `Local` | Tries the configured local ONNX translation model when available, then falls back to the on-device ML Kit translator. It does not call a remote translation API. |
| `API only` | Sends recognized text to the configured online translation service. The API endpoint must be configured before translation can be enabled. |

Older settings values may contain `LOCAL_FIRST`, but the current app normalizes that value to `Local`. It is not an automatic local-to-API fallback mode in the current build.

## Where To Configure It

Open `Settings -> AI`. The AI settings hub organizes translation features into these sections:

- **Local Model Management** — download, manage, and configure OCR detection, recognition, and super-resolution models
- **Online Translation Service** — configure the API provider, key, and model
- **Translation** — choose translation mode, OCR mode, source/target languages, and debug logs
- **Image Enhancement** — Anime4K, RealCUGAN, and Real-ESRGAN super-resolution model settings
- **TTS (Text-to-Speech)** — voice reading settings for novels
- **Video Enhancement** — Anime4K video super-resolution filter presets

### Translation Settings

Open `Settings -> AI -> Translation`. The page contains:

- **Translation mode**: `Local` or `API only`
- **OCR mode**: `Basic` or `Advanced`
- **Source and target languages**
- **Translation debug logs**

When translation mode is `API only`, the settings button opens **Online Translation Service**.

## First-Time Setup

### Local Translation

1. Open `Settings -> AI -> Translation`.
2. Choose `Local` translation mode.
3. Choose the target language. Leave source language on `Automatic` unless recognition is consistently using the wrong language.
4. Keep OCR mode on `Basic` for the quickest setup.
5. Open a manga chapter, open the reader configuration panel, and select **Set up manga translation** or **Manga translation**.

Basic OCR uses ML Kit on the device and does not require a manually managed OCR pack. Local translation can use the configured ONNX model when one is available, then uses the on-device ML Kit translation path for text that remains untranslated. Required language models may be downloaded by the underlying on-device services when first used.

### Advanced Offline OCR

Choose `Advanced` under **OCR mode** when the basic recognizer is not sufficient for your pages. Kototoro asks to download an advanced OCR pack before enabling this mode. The mode becomes available only after all five required detection and language-recognition models have downloaded and passed verification.

After the pack is ready, use the settings button on the OCR row to open **Local Model Management**. This screen lets you:

- Choose the text detection and recognition models used by advanced OCR
- Download or remove supported detector and recognizer models
- Inspect model version and download status
- Manage image super-resolution models shown on the same screen

Model downloads run in the background and report progress through Android notifications. Deleting a model that belongs to the required advanced pack switches OCR back to `Basic`.

### API-Only Translation

1. Select `API only` in `Settings -> AI -> Translation`.
2. Open **Online Translation Service** from the settings button on that row.
3. Select a provider preset, or select `Custom` and enter a compatible endpoint.
4. Enter the API key and translation model.
5. Use **Test connection and choose model** when the provider supports model discovery; otherwise enter the model name manually.
6. Enable manga translation from the reader.

The built-in presets are:

| Provider | Endpoint | Default model |
| --- | --- | --- |
| OpenAI | `api.openai.com/v1/chat/completions` | gpt-5.4-mini |
| DeepSeek | `api.deepseek.com/chat/completions` | deepseek-v4-flash |
| Zhipu | `open.bigmodel.cn/api/paas/v4/chat/completions` | glm-5-turbo |
| Alibaba | `dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | qwen3.6-flash |
| Moonshot | `api.moonshot.ai/v1/chat/completions` | kimi-k2.5 |
| Anthropic | `api.anthropic.com/v1/chat/completions` | claude-sonnet-5 |
| Gemini | `generativelanguage.googleapis.com/v1beta/openai/chat/completions` | gemini-3.5-flash |
| OpenRouter | `openrouter.ai/api/v1/chat/completions` | openai/gpt-5.4-mini |

Provider presets supply the endpoint and default model. `Custom` exposes the endpoint and JSON custom-header fields.

## Using Translation In The Reader

### Manga Translation

The reader's translation shortcut is hidden when the work language already matches the configured target language. In that case, Kototoro skips translation and explains why.

For a work that needs translation:

1. Open the reader configuration panel.
2. Select **Manga translation**. If no usable translation engine is configured, the app opens translation settings instead.
3. After it is enabled, use the reader translation control to switch between the translated rendering and the original image.
4. Open the configuration panel again for **Retranslate** and the **Translation task panel**.

Retranslate supports the current page, failed pages in the current chapter, and the full current chapter. The task panel lists current-chapter pages with ready, running, and failed states, lets you filter the list, retry failed pages, and inspect page logs and chapter timing summaries.

### Novel Translation

Novel translation is available when reading novel content. The processor reuses the same translation engine as manga translation but handles text differently:

- Chapters are split into paragraphs
- Paragraphs are batched and sent to the translation coordinator
- Results are emitted progressively via Flow for streaming rendering
- A text cache avoids re-translating duplicate text

**Display modes:**

| Mode | Behavior |
| --- | --- |
| `Translation only` | Replaces the original text with the translated text |
| `Bilingual` | Shows the original text in gray above the translated text |

To use novel translation:

1. Open a novel chapter in the reader.
2. Open the reader configuration panel.
3. Enable **Novel translation**.
4. Choose the display mode: `Translation only` or `Bilingual`.
5. The translated text streams in progressively as the engine processes paragraphs.

Novel translation uses the same translation engine and API configuration as manga translation.

## Languages And Results

`Automatic` source language first follows the current work/source language where that information is available. For remaining text, the translation coordinator performs language detection before translation. Select an explicit source language when the detected language is wrong or when a mixed-language page gives inconsistent results.

The rendered result is a translated layer over the original image. Kototoro keeps the original page available, so a failed page or an unsatisfactory overlay does not prevent reading.

Text regions may still be difficult to render well when the source has dense mixed layouts, decorative text, fragmented speech bubbles, or incorrect OCR grouping. Problems in those cases can originate in detection or region grouping rather than in the translation itself.

## Debugging And Privacy

Enable **Translation debug logs** only while investigating a problem. It writes concise OCR and translation diagnostics to Logcat.

`Local` mode keeps text translation on-device after any required model downloads. `API only` sends recognized text to the endpoint configured in the app; review that provider's privacy and retention policy before using it for material you do not want to share remotely.

## Architecture Overview

```
Page image
  → Bubble detection (OnnxBubbleDetectorEngine / BubbleReaderTextDetector)
  → Text recognition (ML Kit / PaddleOCR / MangaOCR)
  → Text grouping and merging (ReaderBubbleGroupingCoordinator / ReaderTextMergeCoordinator)
  → Translation (ONNX local / ML Kit local / API provider)
  → Render overlay (ReaderBubbleRenderCoordinator / ReaderPageTranslationProcessor)
```

### Key Components

| Component | Path |
| --- | --- |
| Translation coordinator | `reader/translate/domain/ReaderTranslationCoordinator.kt` |
| Page translation processor | `reader/translate/domain/ReaderPageTranslationProcessor.kt` |
| ONNX translation engine | `reader/translate/domain/OnnxReaderTranslationEngine.kt` |
| Bubble detector (ONNX) | `reader/translate/domain/OnnxBubbleDetectorEngine.kt` |
| Bubble text detector | `reader/translate/domain/BubbleReaderTextDetector.kt` |
| ML Kit OCR engine | `reader/translate/domain/MlKitReaderOcrEngine.kt` |
| PaddleOCR engine | `reader/translate/domain/PaddleReaderOcrEngine.kt` |
| MangaOCR recognizer | `reader/translate/domain/MangaOcrReaderTextRecognizer.kt` |
| ONNX model manager | `reader/translate/data/OnnxModelManager.kt` |
| Paddle model manager | `reader/translate/data/PaddleModelManager.kt` |
| API provider catalog | `reader/translate/domain/TranslationApiProviderCatalog.kt` |
| Novel translation processor | `reader/novel/NovelTranslationProcessor.kt` |
| Translation task panel UI | `reader/ui/TranslationTaskPanelSheet.kt` |
| Translation settings screen | `settings/TranslationSettingsFragment.kt` |
| API settings screen | `settings/TranslationApiSettingsFragment.kt` |
| OCR models screen | `settings/OcrModelsFragment.kt` |
| AI settings hub | `settings/compose/AISettingsScreen.kt` |

## Common Problems

### Translation does not start

- Check that the work language and target language are different.
- In `API only` mode, configure the endpoint, key, and model before enabling translation.
- For advanced OCR, wait for the complete model pack to download and verify.
- Try a page with clear, readable text first.

### Local translation is incomplete

- Confirm the source and target languages are supported by the device-side translator.
- Try an explicit source language instead of `Automatic`.
- Use advanced OCR if basic OCR is not recognizing the page correctly.
- If using local ONNX translation, verify the model is downloaded and compatible.
- Use `API only` only when you have configured and intend to use a remote provider; it is a separate mode, not a fallback for local translation.

### API translation fails

- Confirm the provider preset or custom endpoint is correct.
- Recheck the API key and model name.
- Use **Test connection and choose model** when available.
- If model discovery is unavailable, enter the provider's model identifier manually.

### The translated layer is clipped or uneven

- Compare with the original image to determine whether the text was recognized correctly.
- Try a simpler page to distinguish layout issues from OCR or translation failures.
- Enable debug logs and inspect the page task log before reporting an issue.

### Novel translation is slow or incomplete

- Novel translation processes paragraphs in batches; larger chapters take longer.
- The text cache avoids re-translating duplicate text, but first-time translation of long chapters is inherently slow.
- Try reducing batch size through the API concurrency settings.

See also: [Troubleshooting](./troubleshooting.md)

## Related Documents

- [Documentation Hub](./README.md)
- [Getting Started](./getting-started.md)
- [Reader Features](./reader-features.md)
- [FAQ](./faq.md)
- [Troubleshooting](./troubleshooting.md)