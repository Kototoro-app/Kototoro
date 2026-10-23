# Shared tablet UI

Android/JVM Compose Multiplatform presentation extracted from the Android application. Both `app` and
`desktop-app` depend on this module; it has no dependency on either application or their runtime services.

- `adaptive`: Android tablet navigation and equally sized details panes, width classes and overlay sizing.
- `preview`: the complete tablet preview content and primary-branch chapter grouping. Display models contain
  resolved text; hosts provide painters, cover rendering and callbacks. Short-window scrolling preserves access
  to all actions. The Material preview surface is shared as well; Android supplies its glass alternative.
- `theme`: existing Android typography, interface geometry tokens and shape mapping. Font loading, preferences,
  palette resolution and expressive motion configuration remain platform adapters.
- `text`: the wording Android's catalogs own, addressed by resource name (`content_type_manga`) so a shared screen
  carries no copy of it. `UiStrings` + `LocalUiStrings` + `uiString`/`uiStringArray`; each host injects its own
  catalog at its theme root — Android answers `R.string`, Windows answers the catalog `prepareDesktopStrings` exports
  from the same files, so one translation serves both. Content-type titles derive their name from the type itself
  (`source/contentTypeTitle`), and `:core-ui:verifyUiStrings` fails the build when a shared key, or a content type,
  has no Android string.
- `compose` / `list.ui.compose`: Android cover geometry, poster frame, spine and compact title overlay. Images,
  selection, badges and progress are slots; Android retains Coil caching, shared transitions and TV focus.
- `source` / `explore.ui.compose`: Android source grid tile and its scale-dependent geometry. Hosts resolve
  localized titles/icons and supply selection, availability, pin badges and long-press actions.
- `home`: Android's home page layout, lazy scrolling, centred width limit, pane-based breakpoints and expanded
  highlights/quick-access columns. Both hosts also call the same hero pager, card surface and standard/details
  poster/text layout. Android supplies system insets, scroll interop, TV focus and shared-transition callbacks;
  Windows supplies its existing source data and image loader. Section data binding remains in the hosts.
- `gestures`: primary-mouse grab scrolling on shared lists and pagers. Axis locking and slop preserve card taps
  and nested vertical/horizontal scrolling; owned scroll mutations cancel earlier flings and release on detach.
  Fling and pager snapping use Compose's own behavior. Touch and wheel input retain their existing handlers.
- `chapters`: Android's list/grid directory layout, filter rail, section headers and chapter cards. Both hosts
  render the same current/read/selected/bookmarked/downloaded markers and grid geometry. Models, resolved labels,
  Android haptics/TV focus/nested scroll and platform scrollbars are adapters; search and read-state rules are shared.
- `glass`: Android liquid-glass draw implementation, tuning models and safe lens parameters. Android retains
  Hilt/settings/AMOLED and fallback policy; desktop resolves its own interface-style preference and canvas backdrop.
  Both use the vendored Backdrop 2.0.0 KMP module, including the original Skiko backend and existing local fixes.

Android keeps the existing theme entry, settings and Hilt. Desktop provides a Material3 theme and temporarily
maps the same roles into Material 2 for screens not yet migrated. iOS UI targets are intentionally not added.

Validate with `:core-ui:jvmTest`, `:core-ui:verifyUiStrings`, `:app:compileDebugKotlin` and the optional
`:desktop-app:test` task. The desktop
tests exercise shared home layout/hero scrolling, previews, source actions, poster title bounds and actual Skiko blur in both themes, plus
real source/library/reader flows. The five tablet screenshots supplied by the user on 2026-10-04 supersede
old repository screenshots; palettes still follow theme settings. Home section assembly, the subscription page
container, inner details content and remaining desktop Material 2 screens still need migration. Shared reader
chrome, settings panels, novel directory/bookmark cards and page scenes already live in this module; their
platform playback, image loading, window and storage services stay in the hosts.

## Presentation boundary

Continue extracting Android presentation into `commonMain` and have **both hosts call that implementation**.
Desktop adapters resolve models, labels, artwork and service callbacks. Keep layout, Material 3 controls and
presentation state in shared code, using slots only for platform services or existing feature content. A desktop
Compose screen that reproduces an Android screen is still a second implementation, even if its appearance matches.
