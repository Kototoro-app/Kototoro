#!/usr/bin/env python3
"""Check the language fallback of a built app bundle.

A device whose language the app does not translate runs in the bundle's development region, and a device that
asks for Simplified Chinese needs the table the string catalog generates from its source language. Neither is
visible in a successful compile, and both were wrong in review once (issue #677: Japanese and German devices
launched in Chinese), so the product is checked here instead of trusted.

Usage: verify-bundle-localizations.py <path to Kototoro.app>
"""
import os
import plistlib
import sys
from pathlib import Path

# The widget extension compiles the same string catalog, so it keeps both tables as well.
EXTENSIONS = ("PlugIns/KototoroWidget.appex",)
# The catalog's sourceLanguage is zh-Hans, so the generated tables are English and Simplified Chinese.
EXPECTED_REGION = "en"
EXPECTED_TABLES = ("en", "zh-Hans")


def report(message):
    """CI reads the prefix as an annotation; a local run gets the same text on stderr."""
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::error::{message}")
    else:
        print(message, file=sys.stderr)


def development_region(bundle):
    with (bundle / "Info.plist").open("rb") as handle:
        return plistlib.load(handle).get("CFBundleDevelopmentRegion", "")


def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <path to Kototoro.app>", file=sys.stderr)
        return 2
    app = Path(sys.argv[1])
    if not app.is_dir():
        print(f"No app bundle at {app}", file=sys.stderr)
        return 1

    failure = False
    for bundle in (app, *(app / extension for extension in EXTENSIONS)):
        if not bundle.is_dir():
            report(f"Missing bundle: {bundle}")
            failure = True
            continue
        region = development_region(bundle)
        tables = sorted(path.stem for path in bundle.glob("*.lproj"))
        print(f"{bundle.name}: CFBundleDevelopmentRegion={region!r} tables={tables}")
        if region != EXPECTED_REGION:
            report(
                f"{bundle.name}: CFBundleDevelopmentRegion is {region!r}, expected {EXPECTED_REGION!r}. "
                "Every language the app does not ship falls back to this region, so a zh-Hans region launches "
                "the app in Chinese for those devices (issue #677)."
            )
            failure = True
        for language in EXPECTED_TABLES:
            if not (bundle / f"{language}.lproj" / "Localizable.strings").is_file():
                report(
                    f"{bundle.name}: missing {language}.lproj/Localizable.strings. The string catalog's "
                    "sourceLanguage is zh-Hans, so both tables have to be generated; if that source language "
                    "changes, update this check."
                )
                failure = True

    if failure:
        return 1
    print("Language fallback is intact: unsupported languages resolve to English, Simplified Chinese keeps its own table.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
