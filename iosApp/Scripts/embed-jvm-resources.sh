#!/bin/bash
set -euo pipefail

if [[ "${KOTOTORO_JVM:-0}" != "1" ]]; then
    if [[ -d "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/jvm" ]]; then
        echo "This build directory contains optional JVM resources; use a separate DerivedData directory for the default app." >&2
        exit 1
    fi
    exit 0
fi
if [[ "${PLATFORM_NAME:-}" != "iphonesimulator" && "${PLATFORM_NAME:-}" != "iphoneos" ]] ||
   [[ "${CURRENT_ARCH:-arm64}" == "x86_64" ]]; then
    echo "The local JVM preview supports only arm64 iOS devices and simulators." >&2
    exit 1
fi

runtime="${KOTOTORO_JVM_DIRECTORY:-$SRCROOT/build/jvm}"
if [[ -f "$runtime/boot-shims.jar" ]]; then
    echo "Legacy boot-shims.jar profile detected; regenerate the runtime with Kototoro's own shims." >&2
    exit 1
fi
for required in java_bundle/lib/modules libkototoro-jvm.a include/jni.h lib sources/config.json; do
    if [[ ! -e "$runtime/$required" ]]; then
        echo "Missing JVM preview artifact: $runtime/$required" >&2
        exit 1
    fi
done

# A staged VM can outlive multiple source fixes. Rebuild its adapter and host together before embedding them.
# The independent fixture profile has no parser host and keeps its own isolated ABI.
if [[ -f "$runtime/lib/10-parser-host.jar" ]]; then
    repository="$(cd "$SRCROOT/.." && pwd)"
    (
        cd "$repository"
        JAVA_HOME="$(/usr/libexec/java_home -v '17+')" ./gradlew :mihon-ios-compat:stageIosHost \
            -PwithMihonIosCompat \
            "-PmihonIosCompatibilityDirectory=${KOTOTORO_IOS_COMPATIBILITY_DIRECTORY:-$SRCROOT/build/mihon-ios-api}" \
            --console=plain
    )
fi
destination="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/jvm"
mkdir -p "$destination"
for directory in java_bundle lib sources; do
    ditto "$runtime/$directory" "$destination/$directory"
done
if [[ -f "$runtime/lib/10-parser-host.jar" ]]; then
    ditto "$repository/mihon-ios-compat/build/ios-host" "$destination/lib"
fi
