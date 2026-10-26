#!/bin/bash
set -euo pipefail

task_project_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(uname -s)" != Darwin ]]; then
    echo "This launcher requires macOS." >&2
    exit 1
fi
task_compatibility_root="${1:-$task_project_root/build/desktop-compatibility}"
if [[ ! -d "$task_compatibility_root" ]]; then
    echo "Supply the pinned compatibility directory as the first argument; see desktop-app/README.md." >&2
    exit 1
fi
if [[ $# -gt 0 ]]; then shift; fi
cd "$task_project_root"
exec ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=$task_compatibility_root" \
    :desktop-app:run --console=plain "$@"
