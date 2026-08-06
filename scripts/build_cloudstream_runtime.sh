#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly KOTOTORO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly DEFAULT_SOURCE_REPO="$KOTOTORO_ROOT/../cloudstream"
readonly DEFAULT_COMMIT="caf0db3dc13bd75a496d4a94152bb8f22f8fdb1e"
readonly DEFAULT_SHA256="0a281a34bc4335f024b9bf0ac55ba341d7ae99f5b8918c692c669d7d0bed600e"

source_repo="${CLOUDSTREAM_SOURCE_REPO:-$DEFAULT_SOURCE_REPO}"
commit="${CLOUDSTREAM_COMMIT:-$DEFAULT_COMMIT}"
output_dir="${CLOUDSTREAM_OUTPUT_DIR:-$KOTOTORO_ROOT/build/cloudstream-runtime}"

if ! git -C "$source_repo" rev-parse --git-dir >/dev/null 2>&1; then
	printf 'Cloudstream Git repository not found: %s\n' "$source_repo" >&2
	exit 1
fi
if ! git -C "$source_repo" cat-file -e "$commit^{commit}" 2>/dev/null; then
	printf 'Cloudstream commit is unavailable locally: %s\n' "$commit" >&2
	exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/kototoro-cloudstream.XXXXXX")"
cleanup() {
	rm -rf -- "$work_dir"
}
trap cleanup EXIT

printf 'Exporting Cloudstream commit %s\n' "$commit"
git -C "$source_repo" archive "$commit" | tar -x -C "$work_dir"

printf 'Building :library:jvmJar\n'
(
	cd "$work_dir"
	env -u MDL_API_KEY ./gradlew :library:jvmJar --no-daemon
)

shopt -s nullglob
built_jars=("$work_dir"/library/build/libs/library-jvm-*.jar)
shopt -u nullglob
if [[ ${#built_jars[@]} -ne 1 ]]; then
	printf 'Expected one JVM library JAR, found %d\n' "${#built_jars[@]}" >&2
	exit 1
fi
built_jar="${built_jars[0]}"

required_entries=(
	'com/lagradost/cloudstream3/plugins/BasePlugin.class'
	'com/lagradost/cloudstream3/utils/ExtractorApi.class'
	'com/lagradost/cloudstream3/utils/JsInterpreterKt.class'
)
jar_entries="$(jar tf "$built_jar")"
for entry in "${required_entries[@]}"; do
	if ! grep -Fqx "$entry" <<<"$jar_entries"; then
		printf 'Required Cloudstream class is missing: %s\n' "$entry" >&2
		exit 1
	fi
done

forbidden_pattern='^(androidx/|coil3/|kotlinx/coroutines/|com/google/android/material/|com/lagradost/cloudstream3/(ui|services|receivers|databinding)/)'
if grep -Eq "$forbidden_pattern" <<<"$jar_entries"; then
	printf 'JVM library unexpectedly contains Android app/runtime classes\n' >&2
	grep -E "$forbidden_pattern" <<<"$jar_entries" | head -n 10 >&2
	exit 1
fi

load_response_abi="$(
	javap -classpath "$built_jar" -p 'com.lagradost.cloudstream3.LoadResponse$Companion'
)"
if ! grep -Fq 'addTrailer$default' <<<"$load_response_abi"; then
	printf 'Cloudstream ABI is missing LoadResponse.Companion.addTrailer$default\n' >&2
	exit 1
fi
m3u8_abi="$(
	javap -classpath "$built_jar" -p 'com.lagradost.cloudstream3.utils.M3u8Helper$Companion'
)"
if ! grep -Fq 'generateM3u8$default' <<<"$m3u8_abi"; then
	printf 'Cloudstream ABI is missing M3u8Helper.Companion.generateM3u8$default\n' >&2
	exit 1
fi

actual_sha256="$(sha256sum "$built_jar" | awk '{print $1}')"
if [[ "$commit" == "$DEFAULT_COMMIT" && "$actual_sha256" != "$DEFAULT_SHA256" ]]; then
	printf 'Unexpected SHA-256 for the pinned Cloudstream commit\n' >&2
	printf 'Expected: %s\nActual:   %s\n' "$DEFAULT_SHA256" "$actual_sha256" >&2
	exit 1
fi

mkdir -p "$output_dir"
output_jar="$output_dir/$(basename "$built_jar")"
install -m 0644 "$built_jar" "$output_jar"

printf '\nCloudstream runtime built successfully\n'
printf 'Commit:  %s\n' "$commit"
printf 'SHA-256: %s\n' "$actual_sha256"
printf 'Output:  %s\n' "$output_jar"
