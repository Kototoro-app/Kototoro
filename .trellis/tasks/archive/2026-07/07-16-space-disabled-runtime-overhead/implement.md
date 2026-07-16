# Implementation Plan

1. Short-circuit `SpaceBrowseScope.observeAllowedSourceNames` before creating
   the enabled-source subscription when no Space is active.
2. Add a master-flag check around MainActivity's Space state collection and
   event wiring, while preserving default callback/state values for the
   disabled path.
3. Make the two MainActivity-started Space controllers lazy with respect to the
   master flag, and recheck on lifecycle resume for runtime re-enable.
4. Add or update focused tests for the disabled source-flow behavior and run
   Space-related tests plus the relevant compile task.
5. Inspect the final diff for accidental Space behavior changes and confirm no
   independent permanent coroutine was introduced.

## Validation

- `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.space.*"`
- `./gradlew :app:compileDebugKotlin`
- `git diff --check`
