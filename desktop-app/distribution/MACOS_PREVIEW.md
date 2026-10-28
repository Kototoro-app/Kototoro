# macOS local preview

This is the same Compose Desktop application and JVM source runtime as the Windows host.
The UI, library, database, readers, networking and extension execution are shared.

This preview includes a native WKWebView bridge using the shared browser protocol and SDK cookie store.
It supports HTML/JavaScript, HTTP requests, interactive browser windows and cookie synchronization. Each process
uses an isolated browser store; the SDK cookie store remains the persistent authority.

Embedded video uses libmpv with a dedicated CGL/OpenGL render context, shared controls and progress persistence.
Anime4K and FSR video enhancements execute on this GPU path. Frames are read back for Swing presentation; this is
not a zero-copy native surface. The package includes libmpv and its non-system dylib dependencies; Homebrew is not required.
Pinned build sources, versions, hashes and licenses are in resources/mpv/.
The JVM option -Dkototoro.mpv.renderer=software selects the diagnostic software path, without GLSL enhancements.
External mpv remains available when libmpv is missing.

RealCUGAN and Real-ESRGAN use pinned, SHA-256-verified official macOS archives downloaded on demand from the reader.
The application updater selects this CPU and channel, verifies SHA-256, stages a read-only DMG and validates the
bundle identity, CPU and version. Developer ID installations also require the same Apple signing team. Replacement
waits for the running app to exit, retains the previous bundle, and rolls back if swapping or dispatching restart
fails. A read-only DMG cannot update itself; copy the app to a writable application directory first.
Updates require the matching DMG and .sha256 assets to be published. Windows native binaries are not executed.

Default persistent storage is `~/Library/Application Support/Kototoro`; `--data-dir` overrides it.
The application contains its own JVM and pinned compatibility JARs. The local preview has no Developer ID signature
or notarization and is not a
public distribution or a redistribution licence approval. The compatibility runtime retains its existing provenance
and licence review requirements.
