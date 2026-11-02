# Third-party build inputs

Permanent copies of pinned third-party binaries whose upstream releases are rolling and get deleted. The build
scripts verify each file by SHA-256 before use. This tag points at an orphan commit on purpose, so it never becomes
the "previous tag" of a release changelog.

- `libmpv-2-20261003-git-413ff0b1cd.dll`: libmpv-2.dll (mpv v0.41.0-1095-g413ff0b1c, LGPL build) from
  `mpv-dev-lgpl-x86_64-20261003-git-413ff0b1cd.7z` of https://github.com/zhongfly/mpv-winbuild release
  `2026-10-03-413ff0b1cd` (archive SHA-256 12a9966bad239672c97276f01a9e625504e0b1d1fdcff95256066eeb8ffb1f21),
  as bundled in Kototoro 2.3.4 for Windows. Source: https://github.com/mpv-player/mpv
