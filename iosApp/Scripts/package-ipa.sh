#!/bin/bash
# Build the Release app for devices and package it as an unsigned IPA.
# Usage: iosApp/Scripts/package-ipa.sh [--jvm [runtime-dir]] [version] [build-number]
#   --jvm  embed the extension runtime (default iosApp/build/jvm-device, made by
#          build-jvm-preview.py --platform iphoneos --profile <staged profile>)
# Output: iosApp/build/release-ios/Kototoro-<version>-ios[-jvm]-unsigned.ipa and its .sha256.
# KOTOTORO_UPDATE_CHANNEL selects the embedded update channel (release by default, or nightly).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
project=iosApp/Kototoro.xcodeproj

runtime=""
if [[ "${1:-}" == "--jvm" ]]; then
    shift
    runtime="$root/iosApp/build/jvm-device"
    if [[ -n "${1:-}" && -d "$1" ]]; then
        runtime="$(cd "$1" && pwd)"
        shift
    fi
fi

version="${1:-$(xcodebuild -project "$project" -target Kototoro -configuration Release -showBuildSettings |
    awk '$1 == "MARKETING_VERSION" { print $3; exit }')}"
build_number="${2:-$(git rev-list --count HEAD)}"
update_channel="${KOTOTORO_UPDATE_CHANNEL:-release}"
case "$update_channel" in
    release|nightly) ;;
    *) echo "Invalid iOS update channel '$update_channel'." >&2; exit 1 ;;
esac
if ! [[ "$version" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] || ! [[ "$build_number" =~ ^[0-9]+$ ]]; then
    echo "Invalid version '$version' or build number '$build_number'." >&2
    exit 1
fi

# Each variant keeps its own DerivedData so JVM resources never leak into the default bundle.
if [[ -n "$runtime" ]]; then
    # LC_BUILD_VERSION platform 2 is iphoneos (7 is the simulator); no early-exiting pipe under pipefail.
    platforms="$(otool -l "$runtime/libkototoro-jvm.a" | awk '$1 == "platform" { print $2 }' | sort -u)"
    if [[ "$platforms" != "2" ]]; then
        echo "$runtime/libkototoro-jvm.a is not an iOS device (iphoneos) runtime." >&2
        exit 1
    fi
    # Release runtime: the staged profile's VM, modules and libraries, but none of its bundled sample extensions
    # or preview label; users install extensions from repositories or files.
    release_runtime="$root/iosApp/build/jvm-device-release"
    rm -rf "$release_runtime"
    mkdir -p "$release_runtime/sources"
    for item in java_bundle lib include libkototoro-jvm.a runtime-origin.json; do
        cp -cR "$runtime/$item" "$release_runtime/$item"
    done
    python3 - "$runtime/sources/config.json" "$release_runtime/sources/config.json" <<'PY'
import json, sys
config = json.load(open(sys.argv[1]))
platform_class = config.get("platformClass")
if platform_class != "org.skepsun.kototoro.ios.compat.MihonIosPlatform":
    sys.exit(f"ERROR: Cannot package Release IPA with fixture platformClass '{platform_class}'. Must use 'org.skepsun.kototoro.ios.compat.MihonIosPlatform'.")
config["jars"] = []
config["plugins"] = []
json.dump(config, open(sys.argv[2], "w"), indent=2)
PY
    variant=ios-jvm
    derived=iosApp/build/ReleaseIPA-JVM
    jvm_settings=(KOTOTORO_JVM=1 "KOTOTORO_JVM_DIRECTORY=$release_runtime"
        "KOTOTORO_JVM_LDFLAGS=$runtime/libkototoro-jvm.a -lz -liconv -framework Security -framework SystemConfiguration")
else
    variant=ios
    derived=iosApp/build/ReleaseIPA
    jvm_settings=(KOTOTORO_JVM=0)
fi

archive="iosApp/build/Kototoro-Release-$variant.xcarchive"
# The JVM resource phase merges into the existing bundle, so a previous archive's files would survive.
rm -rf "$archive" "$derived/Build/Intermediates.noindex/ArchiveIntermediates"
xcodebuild -project "$project" -scheme Kototoro \
    -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
    -derivedDataPath "$derived" -archivePath "$archive" \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
    "${jvm_settings[@]}" MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build_number" \
    KOTOTORO_UPDATE_CHANNEL="$update_channel" \
    archive

app="$archive/Products/Applications/Kototoro.app"
if [[ -n "$runtime" ]]; then
    if [[ ! -f "$app/jvm/java_bundle/lib/modules" || ! -f "$app/jvm/sources/config.json" ]]; then
        echo "JVM resources are missing from the app bundle." >&2
        exit 1
    fi
    if [[ "$(ls "$app/jvm/sources")" != "config.json" ]]; then
        echo "Release app bundles more than the extension runtime configuration:" >&2
        ls "$app/jvm/sources" >&2
        exit 1
    fi
elif [[ -e "$app/jvm" ]]; then
    echo "Default app unexpectedly contains JVM resources." >&2
    exit 1
fi

output=iosApp/build/release-ios
asset="Kototoro-$version-$variant-unsigned"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/Payload" "$output"
ditto "$app" "$stage/Payload/Kototoro.app"
rm -f "$output/$asset.ipa" "$output/$asset.sha256"
(cd "$stage" && zip -qry -X "$root/$output/$asset.ipa" Payload)
(cd "$output" && shasum -a 256 "$asset.ipa" > "$asset.sha256")
ls -l "$output/$asset.ipa" "$output/$asset.sha256"
