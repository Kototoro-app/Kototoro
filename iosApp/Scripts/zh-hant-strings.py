#!/usr/bin/env python3
"""Generate and verify the zh-Hant localizations of the iOS string catalogs.

The catalogs declare Simplified Chinese as their source language, so each key is
itself the Simplified text. A zh-Hant value must therefore be a real
Simplified-to-Traditional conversion; copying the key (or the en value) leaves
Traditional-Chinese devices reading Simplified, i.e. no visible change at all.

`generate` rewrites every zh-Hant value with OpenCC's s2twp profile, which
converts to Traditional with Taiwan vocabulary (视频 -> 影片, 软件 -> 軟體),
then applies the small OVERRIDES table for the few words s2twp gets unusual
(类型 -> 類型 rather than 型別). `zh-Hans` is the source language and is never
written.

`check` fails when

* a zh-Hant value still contains Simplified text: converting it with OpenCC
  `s2t` changes it, which also catches a value that just copies its key,
* a zh-Hant value is empty or not marked `translated`,
* a zh-Hant value contains a form that OVERRIDES replaces (e.g. 型別),
* the format specifiers of a zh-Hant value differ from the English one,
* a shipped catalog is missing a localization for zh-Hant,
* the Xcode project no longer falls back to English (developmentRegion must be
  `en`, so ja/de and other unshipped languages keep falling back to English).

Requires the OpenCC Python wheel: `pip install opencc-python-reimplemented`
(pure Python, ships its dictionaries, no native build). Exit 1 means the check
failed.

Usage:
    python3 iosApp/Scripts/zh-hant-strings.py check
    python3 iosApp/Scripts/zh-hant-strings.py generate
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CATALOGS = (
    REPO_ROOT / "iosApp" / "Kototoro" / "Localizable.xcstrings",
    REPO_ROOT / "iosApp" / "Kototoro" / "InfoPlist.xcstrings",
)
PROJECT_FILE = REPO_ROOT / "iosApp" / "Kototoro.xcodeproj" / "project.pbxproj"
LOCALE = "zh-Hant"
# %@ %d %lld %1$@ %.1f %% and friends; the leading % is kept so the comparison
# is order independent and format strings survive translation unchanged.
# OpenCC's s2twp output is not always the wording Traditional-Chinese UI text
# uses in practice: it prefers 型別/賬號 where 類型/帳號 is the common form. These
# corrections are applied after the conversion so `generate` stays reproducible
# and a rerun cannot drift; `check` rejects the pre-correction forms so a value
# copied in by hand cannot reintroduce them.
OVERRIDES = {
    "型別": "類型",
    "賬號": "帳號",
}
FORMAT_SPECIFIER = re.compile(r"%(?:\d+\$)?[-+ 0#]*\d*(?:\.\d+)?(?:hh|h|ll|l|q|z|t|j)?[@dDiuUxXoOfFeEgGcCsSpaA%]")


def apply_overrides(value: str) -> str:
    for source, target in OVERRIDES.items():
        value = value.replace(source, target)
    return value


def load_opencc(profile: str):
    try:
        import opencc  # type: ignore[import-not-found]
    except ImportError:
        sys.exit(
            "OpenCC is not available. Install it with\n"
            "    pip install opencc-python-reimplemented\n"
            "(`brew install opencc` only provides the CLI, this script uses the module)."
        )
    return opencc.OpenCC(profile)


def load_catalog(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def dump_catalog(path: Path, catalog: dict) -> None:
    with path.open("w", encoding="utf-8") as handle:
        json.dump(catalog, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def zh_hant_unit(entry: dict) -> dict | None:
    localizations = entry.get("localizations") or {}
    return localizations.get(LOCALE) or {}


def hant_value(entry: dict) -> str | None:
    unit = zh_hant_unit(entry).get("stringUnit") or {}
    value = unit.get("value")
    return value if isinstance(value, str) else None


def en_value(entry: dict) -> str | None:
    localizations = entry.get("localizations") or {}
    unit = (localizations.get("en") or {}).get("stringUnit") or {}
    value = unit.get("value")
    return value if isinstance(value, str) else None


def specifiers(value: str) -> list[str]:
    # English values are written with positional specifiers (%1$@) while the
    # Simplified source keys use plain ones (%@); compare the kinds and counts.
    return sorted(re.sub(r"\d+\$", "", match) for match in FORMAT_SPECIFIER.findall(value))


def generate() -> int:
    s2twp = load_opencc("s2twp")
    total = 0
    for path in CATALOGS:
        if not path.exists():
            print(f"skip {path.relative_to(REPO_ROOT)} (absent)")
            continue
        catalog = load_catalog(path)
        if catalog.get("sourceLanguage") != "zh-Hans":
            sys.exit(f"{path.relative_to(REPO_ROOT)}: sourceLanguage is not zh-Hans")
        written = 0
        for key, entry in catalog["strings"].items():
            localizations = entry.setdefault("localizations", {})
            if not localizations:
                # Keys the app resolves at runtime carry no localizations at all.
                continue
            value = apply_overrides(s2twp.convert(key))
            # Keep the existing key order inside localizations (Xcode sorts them).
            unit = localizations.get(LOCALE) or {}
            unit["stringUnit"] = {"state": "translated", "value": value}
            localizations[LOCALE] = unit
            localizations.update(sorted(localizations.items()))
            written += 1
        dump_catalog(path, catalog)
        total += written
        print(f"{path.relative_to(REPO_ROOT)}: wrote {written} zh-Hant values")
    print(f"total: {total}")
    return 0


def check() -> int:
    s2t = load_opencc("s2t")
    problems: list[str] = []
    for path in CATALOGS:
        rel = path.relative_to(REPO_ROOT)
        if not path.exists():
            problems.append(f"{rel}: missing catalog")
            continue
        catalog = load_catalog(path)
        if catalog.get("sourceLanguage") != "zh-Hans":
            problems.append(f"{rel}: sourceLanguage must stay zh-Hans")
        for key, entry in catalog["strings"].items():
            localizations = entry.get("localizations") or {}
            if not localizations:
                continue
            unit = zh_hant_unit(entry).get("stringUnit") or {}
            value = hant_value(entry)
            if value is None:
                problems.append(f"{rel}: {key!r} has no zh-Hant value")
                continue
            if not value.strip():
                problems.append(f"{rel}: {key!r} has an empty zh-Hant value")
                continue
            if unit.get("state") != "translated":
                problems.append(f"{rel}: {key!r} zh-Hant state is {unit.get('state')!r}")
            traditional = s2t.convert(value)
            if traditional != value:
                problems.append(
                    f"{rel}: {key!r} zh-Hant value still contains Simplified text "
                    f"({value!r} converts to {traditional!r})"
                )
            for source, target in OVERRIDES.items():
                if source in value:
                    problems.append(
                        f"{rel}: {key!r} zh-Hant value uses {source!r}, the catalog "
                        f"wording is {target!r} (see OVERRIDES in this script)"
                    )
            english = en_value(entry)
            if english is not None and specifiers(english) != specifiers(value):
                problems.append(
                    f"{rel}: {key!r} format specifiers differ "
                    f"(en {specifiers(english)}, zh-Hant {specifiers(value)})"
                )

    if PROJECT_FILE.exists():
        project = PROJECT_FILE.read_text(encoding="utf-8")
        match = re.search(r"developmentRegion = (\w+);", project)
        if not match:
            problems.append("project.pbxproj: developmentRegion not found")
        elif match.group(1) != "en":
            problems.append(
                f"project.pbxproj: developmentRegion is {match.group(1)!r}, "
                "unshipped languages would stop falling back to English"
            )
        if f'"{LOCALE}"' not in project:
            problems.append(f"project.pbxproj: {LOCALE} is not a known region")
    else:
        problems.append("project.pbxproj: not found")

    if problems:
        print(f"zh-Hant check failed ({len(problems)} problem(s)):", file=sys.stderr)
        for problem in problems[:40]:
            print(f"  - {problem}", file=sys.stderr)
        if len(problems) > 40:
            print(f"  ... and {len(problems) - 40} more", file=sys.stderr)
        return 1

    print("zh-Hant check passed: every zh-Hant value is Traditional, "
          "format specifiers match English, developmentRegion is en")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=("check", "generate"))
    args = parser.parse_args()
    return generate() if args.command == "generate" else check()


if __name__ == "__main__":
    sys.exit(main())
