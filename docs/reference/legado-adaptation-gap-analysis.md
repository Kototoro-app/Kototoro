# Legado Book Source Adaptation — Gap Analysis & Improvement Plan

This document tracks the gaps between Kototoro's Legado book source adapter and the native [legado-with-MD3](https://github.com/gedoor/legado) implementation.

> Last updated: 2026-05-26. ✅ = completed, 🟡 = in progress, ⬜ = pending.

---

## Phase 1: ContentRule Field Completions ✅ Done

**Impact:** Missing fields cause incomplete content parsing for sources that depend on them.

| Task | File | Status |
|------|------|--------|
| Add `subContent` / `imageDecode` / `callBackJs` to `ContentRule` | `core/model/jsonsource/LegadoBookSource.kt` | ✅ |
| Implement `subContent` parsing | `core/parser/legado/book/BookContent.kt` | ✅ |
| Inject `imageDecode` script as `X-Legado-ImageDecode` page header | `core/parser/legado/book/BookContent.kt` | ✅ |
| Execute `callBackJs` at end of content parsing | `core/parser/legado/book/BookContent.kt` | ✅ |

---

## Phase 2: JS Bridge Expansion ✅ Done

| Task | File | Status |
|------|------|--------|
| `t2s` / `s2t` Chinese conversion | `core/util/ChineseConverter.kt` (new) + `core/javascript/LegadoJavaAPI.kt` | ✅ |
| `connect(url, headers?, callTimeout?)` → `StrResponse` | `core/javascript/LegadoJavaAPI.kt` | ✅ |
| `webView(html, url, js, cacheFirst)` series | `core/javascript/LegadoJavaAPI.kt` | ✅ |
| `webViewGetSource(html, url, js, sourceRegex, cacheFirst, delayTime)` | `core/javascript/LegadoJavaAPI.kt` | ✅ |
| `webViewGetOverrideUrl(html, url, js, overrideUrlRegex, cacheFirst, delayTime)` | `core/javascript/LegadoJavaAPI.kt` | ✅ |
| `ajaxAll(urlList, skipRateLimit?)` → `Array<StrResponse>` | `core/javascript/LegadoJavaAPI.kt` | ✅ |
| `hexEncode(str)` — complement to existing `hexDecodeToString` | `core/javascript/LegadoJavaAPI.kt` | ✅ |
| `encodingDetect(bytes)` / `encodingDetect(str)` | `core/javascript/LegadoJavaAPI.kt` | ✅ |

---

## Phase 3: TOC Metadata Completion ✅ Done

| Task | File | Status |
|------|------|--------|
| Parse `isVolume` → chapter `volume` field | `core/parser/legado/book/BookChapterList.kt` | ✅ |
| Parse `isVip` / `isPay` → `branch` tag (`"vip"`, `"pay"`) | `core/parser/legado/book/BookChapterList.kt` | ✅ |
| Parse `updateTime` → `uploadDate` (multi-format `parseTimestamp()`) | `core/parser/legado/book/BookChapterList.kt` | ✅ |

---

## Phase 4: Source Login System ✅ Done

| Task | File | Status |
|------|------|--------|
| AES crypto utility (Legado-compatible format) | `core/parser/legado/auth/LegadoCrypto.kt` (new) | ✅ |
| Sandbox loads persisted `sourceVariable_*` / `userInfo_*` on init | `core/parser/legado/sandbox/LegadoSandbox.kt` | ✅ |
| Implement `ContentParserAuthProvider` on `LegadoRepository` | `core/parser/legado/LegadoRepository.kt` | ✅ |
| Factory passes `legado_source_store` SharedPreferences | `core/parser/JsonContentRepositoryProvider.kt` | ✅ |
| "用浏览器登录" button when `loginUrl` is present | `settings/sources/SourceComposeSettingsFragment.kt` | ✅ |
| `SourceAuthActivity` fallback to `ContentRepository.Factory` for non-Parser sources | `settings/sources/auth/SourceAuthActivity.kt` | ✅ |
| Existing `loginUi` dynamic form + `loginCheckJs` button (pre-existing) | `settings/sources/SourceComposeSettingsFragment.kt` | ✅ (已有) |

---

## Phase 5: Network Layer Enhancements ✅ Done

| Task | File | Status |
|------|------|--------|
| `bodyJs` option support in `parseUrlWithOptions()` | `core/parser/legado/AnalyzeUrl.kt` | ✅ |
| `encodedQuery` option — auto URL-encode query parameters | `core/parser/legado/AnalyzeUrl.kt` | ✅ |
| Response charset auto-detection | `core/parser/legado/LegadoRepository.kt` | ✅ (已有 `EncodingDetect.getHtmlEncode()`) |

---

## Phase 6: Global Replace Rule System ✅ Done

**Impact:** Content cleansing relied solely on per-source `replaceRegex`. Now all novel content from ALL source types passes through global replace rules.

### Implementation (Simplified Architecture)

Instead of injecting at individual repositories, rules are applied at a single universal point:

| Task | File | Status |
|------|------|--------|
| Data model (`ReplaceRule` with Legado-compatible JSON serialization) | `core/replace/ReplaceRule.kt` (new) | ✅ |
| Persistence (`ReplaceRuleRepository` with import/export) | `core/replace/ReplaceRuleRepository.kt` (new) | ✅ |
| `applyReplaceRules()` at end of `htmlToPlainText()` — covers all HTML-based sources | `reader/novel/NovelContentLoader.kt` | ✅ |
| `applyReplaceRules()` on EPUB direct path | `reader/novel/NovelContentLoader.kt` | ✅ |
| Management UI (add/edit/delete/toggle/import/export) | `settings/sources/replace/ReplaceRulesFragment.kt` (new) | ✅ |

**Coverage:** Legado, Parser, Mihon, JS, LNReader, IReader, EPUB, local files — all novel content flows through `htmlToPlainText()` or the EPUB path.

---

## Phase 7: Source Type Expansion ❌ Not Planned

### 7.1 Audio Source Support (bookSourceType=1)

Kototoro 定位是漫画 + 小说阅读器，不是音频播放器。不做。


---

## Other Low-Priority Gaps ⬜

### Network / HTTP

| Gap | Legado Native | Kototoro Status | Impact | Effort |
|-----|--------------|----------------|--------|--------|
| `ajaxAll` true concurrency | `flow.mapAsync(N)` with `threadCount` config | Sequential loop | Slow for sources issuing 10+ parallel requests | 小 |
| `java.ajax(url, {method, headers, body, charset})` Rhino overload | Full options map via NativeObject/Undefined | Partial (handles `method` but not full NativeObject) | Some scripts pass JS-native objects | 小 |
| `get(url, headers, timeout)` / `head()` / `post()` (Jsoup-style) | Jsoup `Connection.Response` with header map | Not available | Rarely used by book source scripts | 小 |
| `ajaxTestAll(urlList, timeout)` | Concurrent test calls with individual timeout | Not available | Source validation/debugging only | 小 |

### File I/O

| Gap | Legado Native | Kototoro Status | Impact | Effort |
|-----|--------------|----------------|--------|--------|
| `getFile(path)` / `readTxtFile(path)` / `readFile(path)` | Read from app cache directory | Not available | Some cache-first scripts use this | 小 |
| `deleteFile(path)` | Delete from cache | Not available | Rare | 小 |
| `downloadFile(url)` / `cacheFile(url, saveTime)` | Download + cache with TTL | Not available | Rare for book sources; more common in RSS/spider | 中 |
| `getTxtInFolder(path)` | Read first .txt in directory | Not available | Rare | 小 |

### Archive Operations

| Gap | Legado Native | Kototoro Status | Impact | Effort |
|-----|--------------|----------------|--------|--------|
| `unzipFile` / `un7zFile` / `unrarFile` | Decompress archives | Not available | Rare; some sources use archived content | 中 |
| `getZipStringContent` / `getRarStringContent` / `get7zStringContent` | Read text file from inside archive | Not available | Very rare | 中 |
| `getZipByteArrayContent` etc. | Read binary from archive | Not available | Very rare | 中 |

### Crypto / Encoding

| Gap | Legado Native | Kototoro Status | Impact | Effort |
|-----|--------------|----------------|--------|--------|
| `java.createSymmetricCrypto(algorithm, key, iv)` | AES/DES/3DES via hutool-crypto | Not available | Some API sources use custom encryption | 中 |
| `hexDecodeToByteArray(hex)` | Hex → bytes | Only `hexDecodeToString` (single-byte chars) | Edge case | 小 |
| `base64DecodeToByteArray(str)` | Base64 → bytes | Only `base64Decode` (String output) | Edge case | 小 |

### Font / Typography

| Gap | Legado Native | Kototoro Status | Impact | Effort |
|-----|--------------|----------------|--------|--------|
| `queryTTF(data)` | Extract TTF font properties from bytes | Not available | Font-related source rules extremely rare | 极小 |
| `replaceFont(data, ttfData)` | Replace font in rendered chapter | Not available | Extremely rare | 中 |

### Utilities

| Gap | Legado Native | Kototoro Status | Impact | Effort |
|-----|--------------|----------------|--------|--------|
| `sleep(ms)` | Block JS execution for N ms | Not available | Rate-limiting scripts need this | 极小 |
| `getVerificationCode(imageUrl)` | OCR captcha image | Not available | Captcha-required sources are rare | 大 |
| `importScript(path)` | Dynamic JS import from cache path | Not available | Very rare | 小 |
| `toNumChapter(s)` | Normalize chapter number string | Not available | Edge case for TOC sorting | 小 |
| `openUrl(url)` | Open URL in external intent | Not available | `startBrowser` already covers most cases | 小 |

### Reader Config Access

| Gap | Legado Native | Kototoro Status | Impact | Effort |
|-----|--------------|----------------|--------|--------|
| `getReadBookConfig()` / `getReadBookConfigMap()` | Expose reader theme/layout to JS | Not available | Some sources adapt content based on reader prefs | 极小 |
| `getThemeConfig()` / `getThemeConfigMap()` | Expose app theme to JS | Not available | Extremely rare | 极小 |



---

## Related Files

| Component | Path |
|-----------|------|
| Rule engine | `core/parser/legado/AnalyzeRule.kt` |
| URL builder | `core/parser/legado/AnalyzeUrl.kt` |
| JS sandbox | `core/parser/legado/sandbox/LegadoSandbox.kt` |
| JS API bridge | `core/javascript/LegadoJavaAPI.kt` |
| JS cookie API | `core/javascript/LegadoCookieAPI.kt` |
| Chinese converter | `core/util/ChineseConverter.kt` |
| HTTP client | `core/network/jsonsource/LegadoHttpClient.kt` |
| Source model | `core/model/jsonsource/LegadoBookSource.kt` |
| Repository | `core/parser/legado/LegadoRepository.kt` |
| Content parser | `core/parser/legado/book/BookContent.kt` |
| TOC parser | `core/parser/legado/book/BookChapterList.kt` |
| Info parser | `core/parser/legado/book/BookInfo.kt` |
| List parser | `core/parser/legado/book/BookList.kt` |
| Crypto | `core/parser/legado/auth/LegadoCrypto.kt` |
| Runtime bridge | `core/parser/legado/bridge/KototoroLegadoRuntimeBridge.kt` |
| HTTP executor | `core/parser/legado/bridge/KototoroLegadoHttpExecutor.kt` |
| Variable store | `core/parser/legado/bridge/KototoroLegadoVariableStore.kt` |
| Cookie store | `core/parser/legado/bridge/KototoroLegadoCookieStore.kt` |
| Sandbox JS evaluator | `core/parser/legado/bridge/LegadoSandboxJsEvaluator.kt` |
| Sandbox rule context | `core/parser/legado/bridge/LegadoSandboxRuleRuntimeContext.kt` |
| Standalone rule context | `core/parser/legado/bridge/StandaloneLegadoRuleRuntimeContext.kt` |
| Standalone runtime state | `core/parser/legado/runtime/StandaloneLegadoRuntimeState.kt` |
| Standalone list runtime | `core/parser/legado/runtime/StandaloneLegadoListRuntime.kt` |
| Request plan builder | `core/parser/legado/LegadoRequestPlanBuilder.kt` |
| URL template evaluator | `core/parser/legado/LegadoUrlTemplateEvaluator.kt` |
| Source rule classifier | `core/parser/legado/LegadoSourceRuleClassifier.kt` |
| Rule template resolver | `core/parser/legado/LegadoRuleTemplateResolver.kt` |
| Replace rules model | `core/replace/ReplaceRule.kt` |
| Replace rules repo | `core/replace/ReplaceRuleRepository.kt` |
| Replace rules UI | `settings/sources/replace/ReplaceRulesFragment.kt` |
| Login UI | `settings/sources/SourceComposeSettingsFragment.kt` |
| Auth activity | `settings/sources/auth/SourceAuthActivity.kt` |
| Factory | `core/parser/JsonContentRepositoryProvider.kt` |
| Legado native ref | [legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) |

---

## Detailed Alignment Gaps — AnalyzeRule.kt

Systematic comparison of `core/parser/legado/AnalyzeRule.kt` vs `legado-with-MD3/.../model/analyzeRule/AnalyzeRule.kt`. Found 22 behavioral differences; key gaps listed below.

### CRITICAL (#6) — List Item-by-Item vs Batch Processing

| | Kototoro | Legado |
|---|---------|--------|
| When `result is List<*>` | Iterates each item, applies rule individually | Passes the ENTIRE result to the analyzer |
| Impact | CSS selectors scope limited to each item | CSS can see the full collection |
| Fix | Remove item iteration; let analyzers handle lists internally |

### CRITICAL (#18) — @put/@get Variable Storage Layers

| | Kototoro | Legado |
|---|---------|--------|
| `put(key, value)` | sandbox-ruleData only (in-memory) | chapter → book → ruleData → source (SharedPrefs) |
| `get(key)` | sandbox-ruleData only | 4-layer priority chain + special keys (bookName, title) |
| Impact | `@put:{foo: bar}` in BookInfo won't be visible in TOC parsing |
| Fix | Route `put/get` through `source.put/get` (SourceWrapper → SharedPrefs) |

### HIGH (#4) — @get/&#123;&#123;&#125;&#125; Skipped in JS Mode

| | Kototoro | Legado |
|---|---------|--------|
| SourceRule init for JS mode | Entire `@get`/`&#123;&#123;&#125;&#125;` block skipped (`if (mode != Mode.Js)`) | Parsed for ALL modes |
| Impact | `@get:{...}` inside `<js>` blocks silently ignored |
| Fix | Remove the mode guard; let `makeUpRule` handle substitution for JS mode |

### HIGH (#12+#13) — isRule() / makeUpRule &#123;&#123;&#125;&#125; Routing

| | Kototoro | Legado |
|---|---------|--------|
| `isRule()` | Extremely broad (`.` `$` `//` `@` `##` `&#123;&#123;` `children` etc.) | Narrow: `@` `$.` `$[` `//` only |
| `makeUpRule` &#123;&#123;&#125;&#125; routing | Checks `$.`/`.`/`/`/`@` prefix + dotted JSONPath heuristic | Uses narrow `isRule()` |
| Impact | `&#123;&#123;data.name&#125;&#125;` routed to JsonPath rule eval instead of JS eval |
| Fix | Narrow `isRule()` to match Legado; align `makeUpRule` route logic |

### HIGH (#10b) — HTML Unescape Default

| | Kototoro | Legado |
|---|---------|--------|
| Default value | `false` | `true` |
| First element | NOT unescaped (only items from index 1+) | All elements unescaped |
| Impact | `&#21407;` etc. not decoded by default |
| Fix | Default to `true`; unescape all elements including first |

### MEDIUM (#2) — Json Mode Detection ($ vs $.)

| | Kototoro | Legado |
|---|---------|--------|
| Rule `$xxx` (bare `$`, no dot/bracket) | → Mode.Json | → falls through to Default/Jsoup |
| Impact | `$var` used as variable name would be treated as JSONPath |
| Fix | Require `$.` or `$[` prefix for Json mode |

### MEDIUM (#10a) — getString NativeObject Missing

Legado's `getString` has a fast path: when `result is NativeObject`, skip the full rule loop and directly access `result[rule]`. Kototoro feeds NativeObject through the full analyzer pipeline. Fix: add the `NativeObject` shortcut.

### LOW (#15) — replaceRegex replaceFirst Failure Return

Legado: `replaceFirst` mode returns `replacement` string when regex fails. Kototoro: returns original `result`. Fixed in Phase 5 bodyJs alignment but `replaceFirst` branch needs verification.

### Verified OK

| # | Claim | Status |
|---|-------|--------|
| 17 | Missing JS bindings (`java`/`source`/`book`/etc.) | ❌ False alarm — `RhinoJavaScriptEngine` provides all bindings |
| 8 | NativeObject early-exit | ⬜ investigation differed |
| 10c | First element not unescaped | ⬜ investigation differed |
| 11 | URL resolution base (baseUrl vs redirectUrl) | Low impact |
| 16 | Regex cache | Performance only |
| 19 | getElements blank rule skip | Defensive, no impact |
| 20 | Missing getElement() | No callers in parser path |
| 21 | Undefined handling | Safety enhancement |
| 22 | Private getStringList less Map-aware | Minor |
