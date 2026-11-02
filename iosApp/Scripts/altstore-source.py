#!/usr/bin/env python3
"""AltStore / SideStore source for the unsigned iOS releases.

  metadata <ipa> <entitlements>... -o <out.json>
      Describe one IPA for the source: the bundle's own version, build, minimum iOS and usage descriptions (AltStore
      refuses an install whose numbers differ from the source), the IPA's size and SHA-256, and the entitlements the
      app and its extensions request. ios-build.yml uploads it next to the IPA as <asset>.altstore.json.

  source --repository <owner/name> --channel <release|nightly> -o <out.json>
      Collect those descriptions from the repository's GitHub releases, newest first, into an AltStore source.
      The docs site publishes the result, so the source URL stays fixed while releases come and go.

Python standard library only; GH_TOKEN (optional) raises the GitHub API rate limit.
"""
import argparse
import hashlib
import json
import os
import plistlib
import sys
import time
import urllib.error
import urllib.request
import zipfile

METADATA_FORMAT = 1
METADATA_SUFFIX = ".altstore.json"
APP_NAME = "Kototoro"
SITE = "https://kototoro-app.github.io/Kototoro"
TINT = "0f766e"
DESCRIPTION = (
    "Kototoro brings manga, novels and video into one reader. The iOS preview runs Mihon, Tsundoku and Aniyomi "
    "extensions plus kototoro / kotatsu / UMA parser plugins in an embedded JVM, and Apple CMS / TVBox sources "
    "natively. The app ships no extensions: add repositories or import APK/JAR files from inside the app.\n\n"
    "Requires iOS 26 or later. What works and what is missing: " + SITE + "/platforms"
)
CHANNELS = {
    "release": {
        "identifier": "org.skepsun.kototoro.altstore",
        "name": "Kototoro",
        "subtitle": "Manga, novel and video reader",
    },
    "nightly": {
        "identifier": "org.skepsun.kototoro.altstore.nightly",
        "name": "Kototoro Nightly",
        "subtitle": "Daily builds of the Kototoro reader",
    },
}
MAX_VERSIONS = 10
MAX_NOTES = 4000


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def metadata(args):
    with zipfile.ZipFile(args.ipa) as ipa:
        plists = [name for name in ipa.namelist()
                  if name.startswith("Payload/") and name.endswith(".app/Info.plist") and name.count("/") == 2]
        if len(plists) != 1:
            sys.exit(f"{args.ipa}: expected one Payload/*.app/Info.plist, found {plists}")
        info = plistlib.loads(ipa.read(plists[0]))
    for key in ("CFBundleIdentifier", "CFBundleShortVersionString", "CFBundleVersion", "MinimumOSVersion"):
        if not isinstance(info.get(key), str) or not info[key]:
            sys.exit(f"{args.ipa}: Info.plist has no {key}")
    entitlements = set()
    for path in args.entitlements:
        with open(path, "rb") as stream:
            entitlements.update(plistlib.load(stream))
    privacy = {key: value for key, value in info.items() if key.startswith("NS") and key.endswith("UsageDescription")}
    result = {
        "format": METADATA_FORMAT,
        "ipa": os.path.basename(args.ipa),
        "bundleIdentifier": info["CFBundleIdentifier"],
        "version": info["CFBundleShortVersionString"],
        "buildVersion": info["CFBundleVersion"],
        "minOSVersion": info["MinimumOSVersion"],
        "size": os.path.getsize(args.ipa),
        "sha256": sha256(args.ipa),
        "entitlements": sorted(entitlements),
        "privacy": dict(sorted(privacy.items())),
    }
    with open(args.output, "w", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(f"{args.output}: {result['bundleIdentifier']} {result['version']} ({result['buildVersion']})")


def fetch(url, token, accept="application/vnd.github+json"):
    headers = {"Accept": accept, "User-Agent": "kototoro-altstore-source", "X-GitHub-Api-Version": "2022-11-28"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError) as error:
            client_error = isinstance(error, urllib.error.HTTPError) and 400 <= error.code < 500 \
                and error.code not in (403, 429)
            if client_error or attempt == 3:
                raise
            time.sleep(2 ** attempt * 5)


def source(args):
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    channel = CHANNELS[args.channel]
    suffix = f"-ios-{args.channel}-unsigned"
    versions = []
    bundle_identifier = None
    permissions = None
    page = 1
    while len(versions) < MAX_VERSIONS and page <= 5:
        releases = fetch(f"https://api.github.com/repos/{args.repository}/releases?per_page=50&page={page}", token)
        if not releases:
            break
        page += 1
        for release in releases:
            if release.get("draft") or len(versions) >= MAX_VERSIONS:
                continue
            assets = {asset["name"]: asset for asset in release.get("assets", [])}
            for name, asset in assets.items():
                if not name.endswith(suffix + METADATA_SUFFIX):
                    continue
                # The public download URL: the API asset URL redirects to storage that rejects the token header.
                described = fetch(asset["browser_download_url"], None, accept="*/*")
                ipa = assets.get(described.get("ipa"))
                if described.get("format") != METADATA_FORMAT or ipa is None or ipa["size"] != described["size"]:
                    print(f"Skipping {release['tag_name']}/{name}: metadata does not match an uploaded IPA",
                          file=sys.stderr)
                    continue
                # The newest build defines the identity and the permissions AltStore checks before installing.
                if bundle_identifier is None:
                    bundle_identifier = described["bundleIdentifier"]
                    permissions = {"entitlements": described["entitlements"], "privacy": described["privacy"]}
                elif described["bundleIdentifier"] != bundle_identifier:
                    continue
                notes = (release.get("body") or "").strip()
                if len(notes) > MAX_NOTES:
                    notes = notes[:MAX_NOTES].rsplit("\n", 1)[0] + "\n…"
                versions.append({
                    "version": described["version"],
                    "buildVersion": described["buildVersion"],
                    "date": release.get("published_at") or release["created_at"],
                    "localizedDescription": notes or release.get("name") or release["tag_name"],
                    "downloadURL": ipa["browser_download_url"],
                    "size": described["size"],
                    "sha256": described["sha256"],
                    "minOSVersion": described["minOSVersion"],
                })
    versions.sort(key=lambda version: version["date"], reverse=True)
    icon = f"{SITE}/altstore/icon.png"
    apps = []
    if versions:
        apps.append({
            "name": APP_NAME,
            "bundleIdentifier": bundle_identifier,
            "developerName": "Kototoro contributors",
            "subtitle": channel["subtitle"],
            "localizedDescription": DESCRIPTION,
            "iconURL": icon,
            "tintColor": TINT,
            "category": "entertainment",
            "versions": versions,
            "appPermissions": permissions,
        })
    result = {
        "name": channel["name"],
        "identifier": channel["identifier"],
        "subtitle": channel["subtitle"],
        "description": DESCRIPTION,
        "iconURL": icon,
        "website": SITE + "/platforms",
        "tintColor": TINT,
        "featuredApps": [bundle_identifier] if versions else [],
        "apps": apps,
        "news": [],
    }
    with open(args.output, "w", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(f"{args.output}: {len(versions)} {args.channel} version(s) from {args.repository}")
    if not versions and args.require_version:
        sys.exit(f"No iOS {args.channel} release with {METADATA_SUFFIX} metadata in {args.repository}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    describe = commands.add_parser("metadata")
    describe.add_argument("ipa")
    describe.add_argument("entitlements", nargs="*")
    describe.add_argument("-o", "--output", required=True)
    describe.set_defaults(run=metadata)
    collect = commands.add_parser("source")
    collect.add_argument("--repository", required=True)
    collect.add_argument("--channel", required=True, choices=sorted(CHANNELS))
    collect.add_argument("--require-version", action="store_true")
    collect.add_argument("-o", "--output", required=True)
    collect.set_defaults(run=source)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
