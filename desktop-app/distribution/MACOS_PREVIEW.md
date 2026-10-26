# macOS local preview

This is the same Compose Desktop application and JVM source runtime as the Windows host.
The UI, library, database, readers, networking and extension execution are shared.

This preview does not include a macOS browser bridge, embedded video rendering, native ncnn tools or an application
updater. Sources requiring browser JavaScript or interactive challenges need the browser adapter before they can work.
Video streams can be opened in an installed mpv executable. Windows native binaries are not bundled or executed.

Default persistent storage is `~/Library/Application Support/Kototoro`; `--data-dir` overrides it.
The application contains its own JVM and pinned compatibility JARs. The local preview is unsigned and is not a
public distribution or a redistribution licence approval. The compatibility runtime retains its existing provenance
and licence review requirements.
