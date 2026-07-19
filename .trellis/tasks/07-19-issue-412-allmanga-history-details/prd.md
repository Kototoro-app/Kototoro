# Issue 412: Support Keiyoushi Mihon Extensions

## Goal

Make Kototoro a compatible host for both legacy Mihon extensions and the new
Keiyoushi extension architecture. Sources migrated to `KeiSource` must be
loadable and able to browse, refresh details/chapters, resolve related content,
and read pages while existing Mihon 1.4 extensions keep their legacy request
and parser behavior.

The reported AllManga history failure is one regression scenario, not the
compatibility boundary. Keiyoushi and the official Mihon host currently share
OkHttp 5.4.0 and the 1.6 combined source API; Kototoro must provide the same
runtime contracts while retaining its existing Cloudflare, caching, and
content repository integration.

## Requirements

- Preserve the projection ID passed by `resolveDetailsOrigin` from History
  through `DetailsOrigin.EntityGraph` and the initial details load.
- Do not use the temporary synthetic `Entity Graph` Content as a provider,
  chapter, or related-content request once a local projection is available.
- Keep the Mihon combined detail/chapter API compatible with sources that use
  the new API, including AllManga; do not invoke it with an invalid empty
  snapshot when the source requires initialized manga/chapter state.
- Refresh failures must retain the real projection context and expose a useful
  error path rather than replacing the source with `EmptyContentRepository`.
- Keep behavior for ordinary local sources, non-history details, legacy Mihon
  sources, and other extension providers unchanged.
- Match the OkHttp/Okio runtime ABI used by current Keiyoushi and Mihon
  extensions, including `okhttp-zstd` and its transitive zstd implementation.
- Expose the concrete default-client interceptor contract required by
  `KeiSource`, while keeping legacy extension clients and request helpers
  working.
- Preserve the source API surface needed by both the official Mihon 1.4/1.6
  hosts and the Keiyoushi v16 extension library. Do not special-case a source
  by package, class name, or domain.
- Keep extension class loading deterministic: shared API packages use the
  host's classes, and host versions must match the extensions' compile
  contract.
- Add regression tests for the entity-origin projection handoff and Mihon
  detail refresh input contract.

## Acceptance Criteria

- [ ] History opens AllManga with the original projection ID and reading source
  available on the first render after cached projection data is loaded.
- [ ] Initial chapter/related-content requests do not use synthetic
  `Entity Graph` content when a real projection exists.
- [ ] Pull-to-refresh no longer reproduces the reported AllManga NPE; the
  adapter either completes the combined update or follows a valid compatibility
  fallback.
- [ ] Existing Mihon API compatibility tests and ordinary local detail flows
  remain green.
- [ ] Debug compilation, relevant JVM tests, and `git diff --check` pass.

## Audited baselines

- `../extensions-source` (`keiyoushi/extensions-source`) at HEAD
  `e7c25abbe1`, with `KeiSource` introduced by commit `5699e8a80`.
- `../mihon` at HEAD `5ce7d00eb`.
- Current Keiyoushi and official Mihon builds use OkHttp `5.4.0` and Okio
  `3.17.0`; Kototoro now aligns its resolved runtime to those versions.
- Keiyoushi's v16 artifact is
  `com.github.keiyoushi:extensions-lib:6e0c96cea8`; its source API exposes
  the combined `getMangaUpdate` contract and memo fields.

## Notes

- The log shows `mangaId=4515734976139131316` resolving to `entityId=1612`,
  followed by synthetic `Entity Graph` provider fallback and an AllManga
  `getMangaUpdate` NPE.
- Do not hide the problem with a blanket `catch (Throwable)` or by disabling
  the related-content feature globally.
- Do not solve the network mismatch by allowing each extension to load a
  private OkHttp copy: Kototoro's host API and request tags must remain shared
  with the app.
