# Troubleshooting

This page collects the most common setup and integration problems.

## Sources Do Not Appear

### Mihon or Aniyomi sources are missing

1. Confirm the extension APK is actually installed on the device.
2. If you use in-app repositories, confirm the repository synced and the extension finished installing.
3. Reopen Kototoro and refresh the `Settings -> Content Sources -> Extensions` screen.
4. Check whether the source appears in `Browse -> Content Sources`.
5. If the source is still missing, check compatibility notes for that ecosystem.

### IReader sources are missing

1. Confirm the IReader extension APK is installed on the device.
2. Reopen Kototoro — IReader extensions are auto-detected on startup.
3. Check whether the source appears in `Browse -> Content Sources`.
4. If still missing, ensure the extension APK is compatible (some older versions may not be detected).

### Legado or TVBox sources are missing

1. Confirm the JSON file or JSON URL is valid and reachable.
2. Re-import it from `Settings -> Content Sources -> Import JSON Sources`.
3. Open `Settings -> Content Sources -> JSON Sources Directory` and confirm the imported source is listed and enabled.
4. Check whether it appears in `Browse -> Content Sources`.
5. Test with one source first before importing many.

### TVBox sources import but do not load content

1. Check whether the source is direct media, playlist, CMS, JavaScript, ordinary JAR, or Guard-native JAR.
2. Direct media, playlists, and simpler CMS-style sources should be tested first because they are the most reliable.
3. If a `type = 4` source fails, it may need a JavaScript bridge feature that is not implemented yet.
4. If an ordinary `type = 3` / `csp_*` JAR source fails, capture logs for missing classes, missing methods, initialization failures, proxy failures, or response parsing failures.
5. If a Guard-native JAR source fails, do not treat it as the same problem as an ordinary JAR spider. Guard-native sources can depend on native/JNI behavior that is not reliably supported locally.
6. See [TVBox Runtime Compatibility](./reference/tvbox-runtime.md) for the support matrix and diagnostic categories.

### Kotatsu-Redo sources do not load content

1. The source may be Cloudflare-protected. Check if a browser challenge prompt appears.
2. Complete the Cloudflare challenge when prompted.
3. If the challenge does not appear, try refreshing the page.
4. Some sources may require a working network proxy if their upstream site is region-restricted.

## Automatic Translation Is Not Ready

### The translation feature is enabled but nothing happens

1. Check that the work language and target language differ; matching languages are intentionally skipped.
2. Recheck source and target languages, using an explicit source language if automatic detection is wrong.
3. In `API only` mode, confirm the endpoint, key, and model are configured.
4. In advanced OCR mode, wait for the full OCR pack to download and verify.
5. Test on a page with clearly visible text.

### Model downloads fail

1. Check network connectivity.
2. Check device storage.
3. Reopen the model management screen and retry.

### Remote API translation fails

1. Recheck endpoint, API key, and model name.
2. Use **Test connection and choose model**; enter the model name manually if discovery fails.
3. Switch to `Local` only if you want on-device translation. The current app does not automatically fall back from local translation to an API.

## WebDAV Sync Problems

### Devices do not show the same data

1. Pick the device with the newest state.
2. Create a manual backup there.
3. Restore that backup on the other device.
4. Resume normal sync only after both devices match.

### Backup or restore fails

1. Recheck the full WebDAV endpoint path.
2. Verify credentials.
3. Test with a dedicated backup directory.

## Video Player Problems

### DLNA casting does not find any devices

1. Confirm the target device is on the same local network.
2. Confirm the target device supports DLNA/UPnP playback.
3. Wait a few seconds for SSDP discovery to complete.
4. Check if your Wi-Fi network allows multicast traffic (some public networks block it).

### Subtitles do not appear

1. Confirm the source provides subtitle tracks.
2. Open the player menu and check the subtitle track selection.
3. Some sources do not embed subtitles in their streams.

### Anime4K filters have no visible effect

1. Confirm super-resolution is enabled in player settings.
2. Anime4K is most effective on lower-resolution sources (480p/720p upscaled to display resolution).
3. On high-resolution sources, the visual difference may be subtle.

## External Integrations Are Unstable

- Upstream websites change frequently.
- Extension compatibility can differ by version.
- A source working in a companion app does not always guarantee identical behavior in Kototoro.

## More References

- [Documentation Hub](./README.md)
- [Getting Started](./getting-started.md)
- [Automatic Translation](./automatic-translation.md)
- [Source Integrations](./source-integrations.md)
- [WebDAV Sync](./webdav-sync.md)
- [FAQ](./faq.md)
- [Mihon Integration Reference](./reference/mihon-integration.md)
