#!/bin/bash
set -euo pipefail

task_project_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(uname -s)" != Linux ]]; then
    echo "This launcher requires Linux." >&2
    exit 1
fi
task_compatibility_root="$task_project_root/build/desktop-compatibility"
if [[ $# -gt 0 && "$1" != -* ]]; then
    task_compatibility_root="$1"
    shift
fi
if [[ ! -d "$task_compatibility_root" ]]; then
    echo "Supply the pinned compatibility directory as the first argument; see desktop-app/README.md." >&2
    exit 1
fi
task_compatibility_root="$(cd "$task_compatibility_root" && pwd)"
if [[ -z "${DISPLAY:-}" ]]; then
    echo "An X11 display is required. Use a desktop session or xvfb-run -a bash desktop-app/run-linux.sh." >&2
    exit 1
fi
cd "$task_project_root"
exec ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=$task_compatibility_root" \
    :desktop-app:run --console=plain --no-configuration-cache "$@"
