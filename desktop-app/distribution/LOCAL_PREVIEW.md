# Local Windows preview

This image includes supplied runtime artifacts identified in runtime-artifacts.tsv and
windows-runtime-pins.properties. Those files establish artifact identity, not redistribution rights.
This local preview has not passed the S3 combined-work license/source/notice audit or signing gate.
PROJECT_LICENSE reproduces the repository root LICENSE; it does not settle all code provenance
or relicense bundled third-party artifacts.
Preserve the bundled JVM legal directory. Installed Microsoft Edge WebView2 Runtime is required.

The launcher contains no developer data/import paths. Data defaults to the current user's private
Kototoro directory; use --data-dir <directory> for an isolated preview. --check-runtime <report>
requires an explicit --data-dir and runs local database/image/browser checks without source requests.
