#!/bin/bash
set -euo pipefail

task_project_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(uname -s)" != Linux ]]; then
    echo "This smoke test requires Linux." >&2
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
cd "$task_project_root"
task_display_runner=()
if [[ -z "${DISPLAY:-}" ]]; then
    if ! command -v xvfb-run >/dev/null; then
        echo "No X11 display. Install Xvfb or add its bin directory to PATH before running this test." >&2
        exit 1
    fi
    task_display_runner=(xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp")
fi
exec "${task_display_runner[@]}" ./gradlew -PwithDesktopApp \
    "-PmihonCompatibilityDirectory=$task_compatibility_root" \
    :desktop-app:test \
    --tests '*DesktopAppTest.*rendered source details reader settings*' \
    --tests '*DesktopAppTest.*production*window closes*' \
    --console=plain --no-configuration-cache "$@"
