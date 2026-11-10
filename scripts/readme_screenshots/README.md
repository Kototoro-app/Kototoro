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

The iOS and macOS shots live in `docs/public/screenshots/{ios,macos}/` and show the same library; see
[iOS](#ios) and [macOS](#macos) below.

## Scripts

- `make_demo_library.py <out>` generates the library into `<out>/{manga,novel,video}` plus
  `<out>/metadata.json`. Needs Pillow and ffmpeg (on `PATH`, or `pip install imageio-ffmpeg`).
- `apply_metadata.py <out>/metadata.json` merges authors, description, tags, state and rating into
  the `index.json` files the app writes on its first scan.
- `demo_mode.sh` puts the status bar in demo mode: 12:00, full battery and Wi-Fi, no notification
  icons.
- `package_for_import.py <out> <files>` packages the library as files the iOS app imports: a CBZ per manga
  (chapter folders plus `ComicInfo.xml`), an EPUB for the novel and a folder of episodes per video.
- `desktop_source/build.py <out> <compatibility-dir> <demo.jar>` builds the manga into a Mihon extension JAR for
  the desktop app; pages and catalog are resources inside the JAR, so the source never touches the network.

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

## iOS

`docs/public/screenshots/ios/1-5.png`: library, details page, manga reader, novel reader, video player
(landscape), on an iPhone simulator with the app in English.

1. Package the library and copy it to the simulator's "On My iPhone" storage (open the Files app once so the
   storage exists), then fix the status bar:

   ```bash
   python scripts/readme_screenshots/package_for_import.py /tmp/kototoro-demo /tmp/kototoro-import
   D=$(ls -d ~/Library/Developer/CoreSimulator/Devices/<UDID>/data/Containers/Shared/AppGroup/*/"File Provider Storage")
   mkdir -p "$D/Demo" && cp -R /tmp/kototoro-import/. "$D/Demo/"
   xcrun simctl status_bar <UDID> override --time "9:41" --batteryState charged --batteryLevel 100 \
       --wifiBars 3 --cellularBars 4 --dataNetwork wifi
   ```

2. Build and run the app (see `iosApp/README.md`) with `-AppleLanguages "(en)" -AppleLocale en_US`.
3. **Browse -> Local manga -> +**: in the picker open *On My iPhone/Demo*, tap one file, *Select All*, *Open*.
   The EPUB lands in *Local novels*. In **Local videos** use the folder button and open *Demo/Orbit Cafe*.
4. In *Local manga* long-press a cover, *Select multiple*, *Select all*, the heart, and create the category
   "Read later". Relaunch the app so the "back to Files" breadcrumb leaves the status bar.
5. Capture with `xcrun simctl io <UDID> screenshot shot.png`, in the same order as on Android. The manga reader
   pages right to left, so swipe left-to-right for page 2. Read Sky Archive last so the continue bar shows it.
6. Scale the shots to two thirds (804 px wide). Do not quantise these: 256 colours wash out the tinted glass.

## macOS

`docs/public/screenshots/macos/1-3.png`: favourites shelf, details page, manga reader, from the real desktop
window. The desktop app has no local import, so the library comes from the demo extension.

1. Build the extension and start the app on an empty data directory (compatibility JARs as in
   `desktop-app/README.md`):

   ```bash
   python scripts/readme_screenshots/desktop_source/build.py /tmp/kototoro-demo build/desktop-compatibility /tmp/demo-library.jar
   ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=$PWD/build/desktop-compatibility" :desktop-app:run \
       --args="--data-dir /tmp/kototoro-mac --import /tmp/demo-library.jar --lang en"
   ```

2. **Browse -> Demo Library**: click each cover and the heart in its preview panel. The default category is
   created as "收藏" whatever the language; rename it to "Read later" from the Favourites menu.
3. Open Sky Archive from **Favourites**: *Read*, right arrow for page 2 (reader shot); back, *Details* (details
   shot); close it for the shelf shot. Move the pointer off rows and buttons before capturing.
4. Capture the window alone, without its shadow: `screencapture -x -o -l <window id> shot.png`.

## Library layout notes

- A manga title folder must contain only chapter folders: a loose image at its root becomes an extra
  chapter. The cover is therefore page 1 of chapter 1. Novel and video folders only treat their own
  media as chapters, so their `cover.png` sits beside them.
- The novel is one TXT file per chapter, paragraphs separated by blank lines.
- After replacing a title's files its saved chapter no longer exists: *Read* then reports the missing
  chapter and offers *Start reading*.
