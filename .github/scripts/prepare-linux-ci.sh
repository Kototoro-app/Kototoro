#!/usr/bin/env bash
# Linux counterpart of prepare-windows-ci.ps1: the same 29 pinned compatibility JARs and the ncnn-vulkan release
# archives the reader's upscalers need. The JARs are ordinary Java libraries, so both platform jobs document them in
# the same pin list (mihon-desktop-compat/windows-runtime-pins.properties); nothing here touches WebView2, WiX or the
# Windows-only parts of the PowerShell script.
#
# Usage:
#   prepare-linux-ci.sh --source-java-home <jdk25>        # build the JARs from the pinned Suwayomi source (CI default)
#   prepare-linux-ci.sh --compatibility-url <https zip>   # unpack them from an archive instead
#   prepare-linux-ci.sh --compatibility-archive <zip>     # ... or from a copy on disk
#   prepare-linux-ci.sh --upscalers-dir <dir>             # take the ncnn archives from disk instead of GitHub
#   prepare-linux-ci.sh --upscalers-only | --compatibility-only
#
# Outputs: build/linux-ci-compatibility (the 29 JARs) and build/linux-ci-upscalers (the two release zips).
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build"
pins="$root/mihon-desktop-compat/windows-runtime-pins.properties"
compat_dir="$build/linux-ci-compatibility"
upscale_dir="$build/linux-ci-upscalers"

source_revision="eb2dc0b19a9571b27c02bebc5c883e404b7bd7fb"
patch_revision="b86cead7dd1387b09c71e7f4b15c1e020463e422"
runtime_patch_sha256="ec18dcb53cfe7cd5c3b1bcaac665e5a8b76755a2911d9bdccf1d2197429788d0"
init_script_sha256="fe3efd267a55adb796599b49e6ea92f2efe76da960ca955af00e80873559ee02"


source_java_home=""
compatibility_url=""
compatibility_archive=""
upscalers_dir=""
want_compatibility=1
want_upscalers=1
pins_list=""
pin_count=0

usage() {
    cat <<'EOF'
Prepare the pinned build inputs of the Linux desktop job.

  --source-java-home <jdk25>        build the 29 compatibility JARs from the pinned Suwayomi source (CI default)
  --compatibility-url <https zip>   unpack them from an archive the Windows job can use as well
  --compatibility-archive <zip>     unpack them from a copy on disk
  --upscalers-dir <dir>             take the two ncnn release zips from disk instead of GitHub
  --compatibility-only | --upscalers-only
EOF
}

fail() {
    echo "prepare-linux-ci: $*" >&2
    exit 1
}

while (($# > 0)); do
    case "$1" in
        --source-java-home) source_java_home="${2:?missing value}"; shift 2 ;;
        --compatibility-url) compatibility_url="${2:?missing value}"; shift 2 ;;
        --compatibility-archive) compatibility_archive="${2:?missing value}"; shift 2 ;;
        --upscalers-dir) upscalers_dir="${2:?missing value}"; shift 2 ;;
        --compatibility-only) want_upscalers=0; shift ;;
        --upscalers-only) want_compatibility=0; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; fail "unknown argument: $1" ;;
    esac
done

download() { # url destination [sha256]
    local url="$1" destination="$2" sha256="${3:-}"
    [[ $url == https://* ]] || fail "build input downloads require HTTPS: $url"
    if [[ -n $sha256 && -f $destination ]] && checksum_matches "$destination" "$sha256"; then
        return
    fi
    # Anonymous API calls share the runner IP's 60/hour limit and fail with 403; the job token lifts it.
    local auth=()
    if [[ $url == https://api.github.com/* && -n ${GITHUB_TOKEN:-} ]]; then
        auth=(--header "Authorization: Bearer $GITHUB_TOKEN")
    fi
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 900 --silent --show-error \
        "${auth[@]}" --output "$destination.part" "$url" || fail "download failed: ${url%%\?*}"
    mv -f "$destination.part" "$destination"
    if [[ -n $sha256 ]]; then
        checksum_matches "$destination" "$sha256" || fail "SHA-256 mismatch: $(basename "$destination")"
    fi
}

checksum_matches() { # file sha256
    local actual
    actual="$(sha256sum "$1" | cut -d' ' -f1)"
    [[ ${actual,,} == "${2,,}" ]]
}

verify() { # file sha256 label
    checksum_matches "$1" "$2" || fail "$3 identity mismatch: $(basename "$1")"
}

read_pins() { # name sha256 per line, in pin-list order
    local line
    [[ -f $pins ]] || fail "missing pin list: mihon-desktop-compat/windows-runtime-pins.properties"
    while IFS= read -r line || [[ -n $line ]]; do
        [[ $line =~ ^([^#=[:space:]]+\.jar)=([0-9a-fA-F]{64})$ ]] || continue
        printf '%s %s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2],,}"
    done <"$pins"
}

# Copies the pinned JARs out of a directory that holds them by basename (Source mode) and checks every hash.
install_from_directory() { # directory
    local from="$1" name sha256
    rm -rf "$compat_dir"
    mkdir -p "$compat_dir"
    while read -r name sha256; do
        [[ -f $from/$name ]] || fail "runtime output is missing $name"
        cp -f "$from/$name" "$compat_dir/$name"
        verify "$compat_dir/$name" "$sha256" "compatibility JAR"
    done <<<"$pins_list"
    echo "Prepared $pin_count compatibility JARs from $(basename "$from")."
}

# Copies the pinned JARs out of a ZIP: exactly one entry may carry each pinned basename, and nothing else is unpacked.
install_from_zip() { # zip
    local zip="$1" entries name sha256 entry match count
    command -v unzip >/dev/null 2>&1 || fail "unzip is required"
    [[ -f $zip ]] || fail "no such archive: $zip"
    mapfile -t entries < <(unzip -Z1 "$zip")
    rm -rf "$compat_dir"
    mkdir -p "$compat_dir"
    while read -r name sha256; do
        count=0
        match=""
        for entry in "${entries[@]}"; do
            if [[ ${entry##*/} == "$name" ]]; then
                count=$((count + 1))
                match="$entry"
            fi
        done
        ((count == 1)) || fail "the archive must contain exactly one $name, found $count"
        unzip -p "$zip" "$match" >"$compat_dir/$name" || fail "cannot read $name out of $(basename "$zip")"
        verify "$compat_dir/$name" "$sha256" "compatibility JAR"
    done <<<"$pins_list"
    echo "Prepared $pin_count compatibility JARs from $(basename "$zip")."
}

github_file() { # api path sha256 destination
    local resource="$1" sha256="$2" destination="$3"
    if [[ -f $destination ]] && checksum_matches "$destination" "$sha256"; then
        return
    fi
    download "https://api.github.com/repos/az4521/TachiyomiAzIOS/contents/Scripts/${resource}?ref=$patch_revision" \
        "$destination.json"
    python3 - "$destination.json" <<'PY' >"$destination"
import base64, json, sys
payload = json.load(open(sys.argv[1], encoding='utf-8'))
sys.stdout.write(base64.b64decode(payload['content']).decode('utf-8'))
PY
    rm -f "$destination.json"
    verify "$destination" "$sha256" "pinned upstream resource"
}

# Rebuilds the pinned runtime the way prepare-windows-ci.ps1 does: shallow-fetch the pinned Suwayomi source, apply the
# pinned patch, freeze the timestamp the original artifact was built with, and copy the JARs out with JDK 25.
build_compatibility_from_source() { # jdk25 home
    local java_home="$1" downloads work timestamp_expression
    [[ -n $java_home ]] || fail "Source mode needs JDK 25: pass --source-java-home <jdk 25> or set JAVA_HOME_25_X64"
    [[ -x $java_home/bin/java ]] || fail "Source mode needs JDK 25: $java_home/bin/java is missing"
    [[ -n ${ANDROID_HOME:-} || -n ${ANDROID_SDK_ROOT:-} ]] || fail "Source mode needs the Android SDK"
    downloads="$build/linux-ci-downloads"
    mkdir -p "$downloads"
    github_file "patches/suwayomi-ios-runtime.patch" "$runtime_patch_sha256" \
        "$downloads/suwayomi-ios-runtime.patch"
    github_file "gradle/copy-suwayomi-runtime.init.gradle" "$init_script_sha256" \
        "$downloads/copy-suwayomi-runtime.init.gradle"
    mkdir -p "$build"
    work="$(mktemp -d "$build/linux-ci-suwayomi-XXXXXXXX")"
    git init --quiet "$work" || fail "cannot initialize the runtime source checkout"
    git -C "$work" -c core.autocrlf=false fetch --quiet --depth=1 \
        https://github.com/Suwayomi/Suwayomi-Server.git "$source_revision" \
        || fail "cannot fetch pinned Suwayomi source"
    git -C "$work" -c core.autocrlf=false checkout --quiet --detach FETCH_HEAD \
        || fail "cannot check out Suwayomi source"
    git -C "$work" apply "$downloads/suwayomi-ios-runtime.patch" || fail "cannot apply the pinned runtime patch"
    # The original artifact was built at one fixed second; freezing it keeps the existing SHA-256 pins reproducible.
    timestamp_expression='Instant.now().epochSecond.toString()'
    python3 - "$work/server/build.gradle.kts" "$timestamp_expression" <<'PY'
import sys
path, expression = sys.argv[1], sys.argv[2]
with open(path, encoding='utf-8') as source:
    text = source.read()
if text.count(expression) != 1:
    sys.exit(f"prepare-linux-ci: upstream build timestamp expression changed in {path}")
with open(path, 'w', encoding='utf-8', newline='\n') as target:
    target.write(text.replace(expression, '"1790898154"'))
PY
    (
        cd "$work"
        JAVA_HOME="$java_home" ProductBuildType=Preview ./gradlew :server:copyTachiazRuntime --no-daemon \
            --max-workers=2 --console=plain "-Dorg.gradle.jvmargs=-Xmx4g" "-Pkotlin.daemon.jvmargs=-Xmx4g" \
            --init-script "$downloads/copy-suwayomi-runtime.init.gradle" "-PtachiazRuntimeOutput=$work/runtime-output" \
            || fail "the pinned compatibility runtime build failed"
    )
    install_from_directory "$work/runtime-output"
}

install_upscaler() { # name url sha256
    local name="$1" url="$2" sha256="$3"
    if [[ -n $upscalers_dir ]]; then
        [[ -f $upscalers_dir/$name ]] || fail "no $name in $upscalers_dir"
        cp -f "$upscalers_dir/$name" "$upscale_dir/$name"
        verify "$upscale_dir/$name" "$sha256" "upscaler archive"
    else
        download "$url" "$upscale_dir/$name" "$sha256"
    fi
}

# Keep these two archives in sync with DesktopUpscaleTool.assets in DesktopSuperResolution.kt. A stale entry cannot
# pass silently: DesktopSuperResolutionTest installs them through the same SHA-256 check the app uses.
install_upscalers() {
    rm -rf "$upscale_dir"
    mkdir -p "$upscale_dir"
    install_upscaler "realcugan-ncnn-vulkan-20220728-ubuntu.zip" \
        "https://github.com/nihui/realcugan-ncnn-vulkan/releases/download/20220728/realcugan-ncnn-vulkan-20220728-ubuntu.zip" \
        "d745174bd04c0232c89d935b74799311008fda06bea4195f61be5f0f3cc087cb"
    install_upscaler "realesrgan-ncnn-vulkan-20220424-ubuntu.zip" \
        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-ubuntu.zip" \
        "e5aa6eb131234b87c0c51f82b89390f5e3e642b7b70f2b9bbe95b6a285a40c96"
    echo "Prepared 2 upscaler archives in ${upscale_dir#"$root"/}."
}

if ((want_compatibility)); then
    pins_list="$(read_pins)"
    [[ -n $pins_list ]] || fail "the pin list holds no JAR: mihon-desktop-compat/windows-runtime-pins.properties"
    pin_count="$(grep -c '' <<<"$pins_list")"
    if [[ -n $compatibility_archive ]]; then
        install_from_zip "$compatibility_archive"
    elif [[ -n $compatibility_url ]]; then
        mkdir -p "$build/linux-ci-downloads"
        compatibility_archive="$build/linux-ci-downloads/compatibility.zip"
        download "$compatibility_url" "$compatibility_archive"
        install_from_zip "$compatibility_archive"
    else
        build_compatibility_from_source "${source_java_home:-${JAVA_HOME_25_X64:-}}"
    fi
fi

if ((want_upscalers)); then
    install_upscalers
fi
