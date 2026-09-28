# README screenshots

The screenshots in the project README (`metadata/en-US/images/phoneScreenshots/1-5.png`, also used
as the F-Droid phone listing) are taken on an emulator from a mock local library. Every title, cover,
manga page, novel chapter and video clip in it is made up and procedurally generated, so no
third-party source or copyrighted artwork ever appears in the repository.

| File | Screen |
| --- | --- |
| `1.png` | Favorites shelf (12 manga) |
| `2.png` | Details page (Sky Archive, favourited, reading) |
| `3.png` | Manga reader with its controls |
| `4.png` | Novel reader, controls hidden |
| `5.png` | Video player with subtitles, landscape |

## Scripts

- `make_demo_library.py <out>` generates the library into `<out>/{manga,novel,video}` plus
  `<out>/metadata.json`. Needs Pillow and ffmpeg (on `PATH`, or `pip install imageio-ffmpeg`).
- `apply_metadata.py <out>/metadata.json` merges authors, description, tags, state and rating into
  the `index.json` files the app writes on its first scan.
- `demo_mode.sh` puts the status bar in demo mode: 12:00, full battery and Wi-Fi, no notification
  icons.

## Procedure

1. Start the phone AVD **headless**, and give its display a cutout:

   ```bash
   emulator -avd <phone-avd> -no-window -gpu host -no-snapshot-save
   adb shell cmd overlay enable --user 0 com.android.internal.display.cutout.emulation.hole
   ```

   With the Qt window, emulator 37.1 crashed (exit 139) as soon as the details page or a dialog was
   drawn, whatever the `-gpu` mode; headless it is stable. Headless, the status bar is also sized
   without a cutout and clips its own icons, which the cutout overlay fixes.

2. Install a fresh build and finish the setup wizard with its defaults:

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r -t app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
   adb shell pm clear org.skepsun.kototoro.debug
   ```

3. Generate and push the library, then open **Home -> Local storage** once so the app scans it:

   ```bash
   python scripts/readme_screenshots/make_demo_library.py /tmp/kototoro-demo
   B=/sdcard/Android/data/org.skepsun.kototoro.debug/files
   for k in manga novel video; do adb push /tmp/kototoro-demo/$k/. $B/$k/; done
   ```

4. Add the display metadata, clear the image cache and restart the app:

   ```bash
   python scripts/readme_screenshots/apply_metadata.py /tmp/kototoro-demo/metadata.json
   adb shell "run-as org.skepsun.kototoro.debug sh -c 'rm -rf cache/*'"
   adb shell am force-stop org.skepsun.kototoro.debug
   ```

5. Favourite the 12 manga (heart on each details page, category "Read later"), then run
   `demo_mode.sh` and capture with `adb exec-out screencap -p > shot.png`:
   - **Favorites** tab.
   - **Sky Archive**: open it, *Read*, swipe to page 2, tap the centre for the controls. Going back
     gives the details page with progress and the filled heart.
   - **The Lantern Keeper of Harrow Bay**: open chapter 1 from the chapter list, tap the centre to
     hide the controls.
   - **Orbit Cafe**: *Play*, tap once for the controls.

   Demo mode resets whenever SystemUI restarts (for example after changing an overlay); run
   `demo_mode.sh` again.

6. Shrink the shots before committing; 256-colour quantisation keeps the blurred backgrounds clean:

   ```python
   from PIL import Image
   Image.open("shot.png").convert("RGB").quantize(256, dither=Image.Dither.FLOYDSTEINBERG).save("1.png", optimize=True)
   ```

## Library layout notes

- A manga title folder must contain only chapter folders: a loose image at its root becomes an extra
  chapter. The cover is therefore page 1 of chapter 1. Novel and video folders only treat their own
  media as chapters, so their `cover.png` sits beside them.
- The novel is one TXT file per chapter, paragraphs separated by blank lines.
- After replacing a title's files its saved chapter no longer exists: *Read* then reports the missing
  chapter and offers *Start reading*.
