---
name: kototoro-artwork-chrome-artifacts
description: Shadow bleed and sibling Kototoro chrome artifacts that appear only with an artwork/image background — a nested pale plate, an octagon-shaped light island, or a dark rim inside top-bar pills, the search button, bottom nav and other GlassSurface panels — plus the no-rebuild loop that locates which layer draws any Compose visual artifact, through prefs-only A/B, pixel metrics and pinned-library bytecode.
---

# Kototoro Artwork Chrome Artifacts

For Kototoro-only chrome artifacts over an artwork background. The glass stack is the vendored Kyant Backdrop — module `backdrop/`, package `com.kyant.backdrop` — so load the `backdrop` skill alongside this one whenever the affected code imports it.

## The rule: shadow bleed

A Material3 `Surface` whose `color` is translucent cannot hide its own shadow. The shadow draws behind the shape, reads through the fill as a dark rim hugging the inside of the outline, and leaves a smaller, brighter plate in the middle. Kototoro makes every glass role translucent over artwork (chrome 0.65–0.92, cards 0.40–0.74), so the shadow paints the artifact and no tint or alpha tuning can remove it.

```kotlin
internal fun resolveFallbackShadowElevation(
    styleShadowElevation: Dp,
    containerAlpha: Float,
    flat: Boolean,
): Dp = if (flat || containerAlpha < 1f) 0.dp else styleShadowElevation
```

`GlassSurface`'s Material fallback routes through it, and so must any hand-rolled `Surface(color = …translucent…, shadowElevation = …)`. Over artwork the hairline border already carries the edge, so dropping the shadow costs depth and nothing else.

## Read the exemption before the symptom

"Every screen except X" names the mechanism. The details page was exempt because it is the only caller of `TopBarControlSurface(fallbackContainerColor = …)` — the one branch that forces `copy(alpha = 1f)`. Read X's code early; it usually differs in exactly the layer under suspicion.

## Diagnosis loop (no rebuild)

Style preferences alone add and remove this family of artifact, so keep the loop on prefs and pixels.

1. **Read the live config.** `adb shell run-as <pkg> cat shared_prefs/<pkg>_preferences.xml`. This family reproduces with `interface_style=MATERIAL_3_EXPRESSIVE` + `background_style=DYNAMIC_ARTWORK_GALLERY`. Done when both style values are read from the device rather than assumed from the report.

2. **Flip one variable per capture.** Each flip removes the artifact for a different reason, so the pair localises the layer:
   - `background_style=DEFAULT` makes fills opaque; artifact gone ⇒ the fill's transparency is load-bearing.
   - `interface_style=IOS` switches to the kyant `drawBackdrop` path; artifact gone ⇒ the Material fallback draws it.
   Done when each surviving candidate is eliminated by its own capture.

3. **Measure, don't look.** A rotating backdrop defeats absolute thresholds, so use *interior uniformity*: the p5–p95 luminance spread inside the shape, discarding icon pixels below 150. Artifact reads 17.0 on a 44dp capsule; clean reads 1–2. The bright region's bounding box also sizes the culprit — 99px is a 30.5dp nested plate, 135px is the whole 44dp capsule. Done when before/after numbers come from the same mask over a matching backdrop.

4. **Confirm API behaviour in the pinned library.** Wrong fixes here start from a guessed widget default. `IconButton` in this project's material3 passes `tonalElevation = 0f` / `shadowElevation = 0f` while the call site sets `containerColor = Transparent`, so it draws nothing and any fix aimed at it is wasted — as one was. Check the AAR before blaming a widget:
   `javap -p -c -classpath <extracted classes.jar> androidx.compose.material3.IconButtonKt`
   Done when the suspect's real colours and elevations are quoted from bytecode or from the call site.

## Editing prefs on device

`adb shell` re-parses your command with the device shell, which consumes `$` and `>`. Style values are ASCII enum names, so:

- Change a value: `adb shell run-as <pkg> sed -i s/OLD/NEW/ <prefs>`.
- Insert or delete a line: push a script file and run it, sidestepping inline quoting.
  `adb push x.sed /data/local/tmp/ && adb shell chmod 644 /data/local/tmp/x.sed`
  `adb shell run-as <pkg> sed -i -f /data/local/tmp/x.sed <prefs>`
- Force-stop before editing so the app cannot flush over the file; restore the reported values and a closing `</map>` when done.

## Pixel analysis without PIL

`artwork_probe.py` in this folder decodes PNG through zlib + numpy and prints the uniformity metric, the band map and the nested-plate signature — no imaging library needed on this machine. Pull captures to a file instead of redirecting `adb exec-out`: PowerShell rewrites `>` output as UTF-16 and corrupts the PNG.

## Closing the loop

- Put the policy in a pure function and pin it with unit tests, e.g. `:app:testDebugUnitTest --tests "…GlassSurfacePolicyTest"`.
- Compile and install the real build, then re-measure the same mask on the device; extract the numbers rather than describing the screenshot.
- Restore the user's original prefs and remove capture scratch before reporting.
