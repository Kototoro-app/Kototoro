# Implementation Plan

1. Normalize `GlassDropdownMenu` root overlay sizing, surface, effect order, content color, and fallback behavior against the main “More” menu.
2. Add root-overlay anchors to the main content source filter menu, details menus, and list menus; set above/below placement explicitly.
3. Replace scoped menu item/text implementations with the shared compact menu primitives while preserving icons, enabled states, selection indicators, and actions.
4. Search for remaining scoped `GlassDropdownMenu` call sites using raw Material3 menu rows and review whether they are in or out of scope.
5. Run `git diff --check` and `JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:compileDebugKotlin --no-daemon -Pksp.incremental=false`.
6. Manually review iOS Backdrop source/consumer ordering and the unchanged FAB menu path.
