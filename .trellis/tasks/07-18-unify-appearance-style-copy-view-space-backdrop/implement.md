# Implementation Plan

1. Inventory all appearance string keys and locale variants; update only the interface-style labels and iOS preset notes identified by repository search.
2. Add a small View-hosted Compose FAB presentation component or delegate-owned overlay following existing `ComposeView` lifecycle patterns.
3. Connect the overlay to the three immersive View hosts while preserving the XML FAB as the interaction/state anchor.
4. Verify iOS and Material 3 branches, overlay cleanup, fallback rendering, and existing show/hide transitions.
5. Run resource consistency checks and the narrowest available Android compile/test targets.

Validation commands:

- `rg -n -i "modern style|interface_style_(material3|ios)|appearance_.*ios_note" app/src/main/res`
- `./gradlew :app:compileFossDebugKotlin`
- Relevant unit tests if available for the delegate/presentation state.

Rollback points:

- Resource changes are isolated to `strings.xml` files.
- View visual integration can be reverted independently without changing space switching or navigation logic.
