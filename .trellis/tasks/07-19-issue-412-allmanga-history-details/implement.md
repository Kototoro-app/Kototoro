# Implementation Plan

## Research and Baseline

- [x] Read the current frontend/backend specs and relevant Mihon compatibility
  contracts.
- [x] Trace `DetailsOrigin.EntityGraph` from History through ViewModel state,
  chapter mapping, related-content seed, and provider creation.
- [x] Inspect the recent combined Mihon API migration and identify the exact
  AllManga-invalid input.
- [x] Audit `../extensions-source` Keiyoushi core/KeiSource and `../mihon`
  source-api/network/extension loader implementations.
- [x] Resolve the host/runtime version matrix for OkHttp, Okio, zstd, and
  shared class-loader packages.
- [x] Add focused failing regression fixtures before changing behavior where
  the existing test seams allow it.

## Implementation

- [x] Make the initial local projection the first-class details input during
  Entity Graph initialization.
- [x] Prevent synthetic Entity Graph content from reaching provider/chapter/
  related-content requests when a real projection is available.
- [x] Align the Mihon host network client with the KeiSource contract and
  correct the combined-update invocation or add a narrow compatibility
  fallback that preserves source context and snapshot caching.
- [x] Keep a separate Brotli-enabled legacy client for old Mihon sources while
  keeping Brotli out of the `KeiSource` default client.
- [x] Preserve the legacy source API while covering the current v16 combined
  API and memo contract.
- [x] Add regression coverage for first render, pull-to-refresh, and ordinary
  Mihon/local detail flows.
- [x] Add a generic KeiSource-style fixture; AllManga-only tests are not
  sufficient.

## Validation

- [x] Run targeted DetailsViewModel/details-domain tests.
- [x] Run targeted Mihon compatibility tests.
- [x] Run `:app:compileDebugKotlin` and `:app:processDebugResources`.
- [x] Run `git diff --check`.
- [x] Resolve and inspect the final OkHttp/Okio dependency graph.
- [x] Record whether an AllManga-capable device/plugin was available for manual
  validation.

Manual AllManga history/refresh validation was not available in the local
workspace; the regression is covered by the generic memo-dependent source
fixture and the audited AllManga implementation.

## Review Gates

- No synthetic entity ID is passed as a local manga ID.
- No blanket exception swallowing or global provider disablement is introduced.
- Legacy Mihon sources and the new combined API remain source-compatible.
- The host does not depend on an AllManga package/domain check.
- The exposed Mihon client passes concrete interceptor and compression checks
  performed by `KeiSource`.
