# Linux local preview

This is the same JVM desktop application as the Windows and macOS hosts. The UI, controllers, library, database,
readers, networking and extension execution remain shared; Android/desktop presentation is in core-ui/commonMain.

The image includes its own JVM and the pinned compatibility JARs. It needs an X11 display (XWayland or Xvfb can
provide one), system fonts and the native libraries required by AWT/Skiko. A headless runtime check does not prove
GPU rendering, video playback or compatibility with every Linux distribution.

This preview does not bundle a browser bridge, libmpv, ncnn tools or an application updater. Sources requiring
browser JavaScript or interactive challenges are unavailable. An installed mpv executable can open video streams;
embedded playback needs a separately supplied libmpv and platform verification. Windows native binaries are not bundled.

Default persistent storage remains ~/.kototoro; --data-dir overrides it. The runtime check requires an explicit
data directory. This local preview retains the existing compatibility runtime provenance and licence requirements.
