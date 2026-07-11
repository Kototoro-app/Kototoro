# Automatic Translation

Kototoro can translate manga pages inside the reader. The standard workflow is a two-stage pipeline: the app detects and recognizes text, translates the recognized text, then draws a translated layer over the original page. Translation is an optional reader enhancement; normal reading continues when it is disabled or a page cannot be translated.

## Where To Configure It

Open `Settings -> AI -> Translation`.

The page contains the settings that affect page translation:

- Translation mode: `Local` or `API only`
- OCR mode: `Basic` or `Advanced`
- Source and target languages
- Translation debug logs

Select `API only` and use the settings button on that row to open **Online Translation Service**. The service screen provides provider presets, API key, model selection, a connection/model-list check, and custom endpoint or headers for the `Custom` preset.

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

The built-in presets cover OpenAI-compatible services including OpenAI, DeepSeek, Zhipu, Alibaba, Moonshot, Anthropic, Gemini, and OpenRouter. Provider presets supply the endpoint and default model. `Custom` exposes the endpoint and JSON custom-header fields.

## Translation Modes

| Mode | Actual behavior |
| --- | --- |
| `Local` | Tries the selected local ONNX translation model when configured, then the on-device ML Kit translator. It does not call a remote translation API. |
| `API only` | Sends recognized text to the configured online translation service. The API endpoint must be configured before translation can be enabled. |

Older settings values may contain `LOCAL_FIRST`, but the current app normalizes that value to `Local`. It is not an automatic local-to-API fallback mode in the current build.

## Using Translation In The Reader

The reader's translation shortcut is hidden when the work language already matches the configured target language. In that case, Kototoro skips translation and explains why.

For a work that needs translation:

1. Open the reader configuration panel.
2. Select **Manga translation**. If no usable translation engine is configured, the app opens translation settings instead.
3. After it is enabled, use the reader translation control to switch between the translated rendering and the original image.
4. Open the configuration panel again for **Retranslate** and the **Translation task panel**.

Retranslate supports the current page, failed pages in the current chapter, and the full current chapter. The task panel lists current-chapter pages with ready, running, and failed states, lets you filter the list, retry failed pages, and inspect page logs and chapter timing summaries.

## Languages And Results

`Automatic` source language first follows the current work/source language where that information is available. For remaining text, the translation coordinator performs language detection before translation. Select an explicit source language when the detected language is wrong or when a mixed-language page gives inconsistent results.

The rendered result is a translated layer over the original image. Kototoro keeps the original page available, so a failed page or an unsatisfactory overlay does not prevent reading.

Text regions may still be difficult to render well when the source has dense mixed layouts, decorative text, fragmented speech bubbles, or incorrect OCR grouping. Problems in those cases can originate in detection or region grouping rather than in the translation itself.

## Debugging And Privacy

Enable **Translation debug logs** only while investigating a problem. It writes concise OCR and translation diagnostics to Logcat.

`Local` mode keeps text translation on-device after any required model downloads. `API only` sends recognized text to the endpoint configured in the app; review that provider's privacy and retention policy before using it for material you do not want to share remotely.

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

See also: [Troubleshooting](./troubleshooting.md)

## Related Documents

- [Documentation Hub](./README.md)
- [Getting Started](./getting-started.md)
- [Reader Features](./reader-features.md)
- [FAQ](./faq.md)
- [Troubleshooting](./troubleshooting.md)
