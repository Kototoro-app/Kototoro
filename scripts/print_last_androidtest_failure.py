"""Print the failing assertion of the newest connected androidTest result, if any."""

from __future__ import annotations

import pathlib
import re
import sys

result = pathlib.Path(
    sys.argv[1] if len(sys.argv) > 1
    else r"app/build/outputs/androidTest-results/connected/debug/TEST-M332BF - 17-_app-.xml"
)
if not result.is_file():
    print(f"no result file at {result}")
    raise SystemExit(1)

text = result.read_text(encoding="utf-8", errors="replace")
names = re.findall(r'<testcase name="([^"]+)"', text)
failures = re.findall(r'<failure[^>]*>(.*?)</failure>', text, re.S)
print(f"cases={len(names)} failures={len(failures)}")
for index, body in enumerate(failures):
    for line in body.splitlines():
        if "Error" in line or "error" in line:
            print(f"  [{index}] {line.strip()[:300]}")
            break
    else:
        print(f"  [{index}] {body.strip()[:300]}")
