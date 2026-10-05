# Shared tablet UI

Android/JVM Compose Multiplatform presentation extracted from the Android application. Both `app` and
`desktop-app` depend on this module; it has no dependency on either application or their runtime services.

- `adaptive`: Android tablet navigation and equally sized details panes, width classes and overlay sizing.
- `preview`: the complete tablet preview content and primary-branch chapter grouping. Display models contain
  resolved text; hosts provide painters, cover rendering and callbacks. Short-window scrolling preserves access
  to all actions. The Material preview surface is shared as well; Android supplies its glass alternative.
- `theme`: existing Android typography, interface geometry tokens and shape mapping. Font loading, preferences,
  palette resolution and expressive motion configuration remain platform adapters.
- `compose` / `list.ui.compose`: Android cover geometry, poster frame, spine and compact title overlay. Images,
  selection, badges and progress are slots; Android retains Coil caching, shared transitions and TV focus.
- `source` / `explore.ui.compose`: Android source grid tile and its scale-dependent geometry. Hosts resolve
  localized titles/icons and supply selection, availability, pin badges and long-press actions.
- `glass`: Android liquid-glass draw implementation, tuning models and safe lens parameters. Android retains
  Hilt/settings/AMOLED and fallback policy; desktop resolves its own interface-style preference and canvas backdrop.
  Both use the vendored Backdrop 2.0.0 KMP module, including the original Skiko backend and existing local fixes.

Android keeps the existing theme entry, settings and Hilt. Desktop provides a Material3 theme and temporarily
maps the same roles into Material 2 for screens not yet migrated. iOS UI targets are intentionally not added.

Validate with `:core-ui:jvmTest`, `:app:compileDebugKotlin` and the optional `:desktop-app:test` task. The desktop
tests exercise shared previews, source actions, poster title bounds and actual Skiko blur in both themes, plus
real source/library/reader flows. The five tablet screenshots supplied by the user on 2026-10-04 supersede
old repository screenshots; palettes still follow theme settings. Whole home/subscription screens, inner details
content, top-bar controls and reader UI are not yet shared.
