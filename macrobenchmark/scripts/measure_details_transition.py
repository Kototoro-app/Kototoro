#!/usr/bin/env python3
"""Measure the list -> details -> back flight with `dumpsys gfxinfo`.

Why this exists: `androidx.benchmark` 1.5.0 cannot run on every device. On a Motorola
M332BF (Android 16) perfetto writes the trace as `root:root 0600`, the library's size
check reads an empty `ls`, and every iteration aborts with

    Cannot check size of /data/misc/perfetto-traces/trace_output.pb

`gfxinfo` reports Android's own jank classification plus the P90/P95 of the same frames,
which is what the transition issue is about, and it needs nothing but adb.

Usage:
    python3 macrobenchmark/scripts/measure_details_transition.py \
        --case phone --title "Some work in your list" --runs 5

    # the two-pane case, emulated on the same phone with wm size / wm density
    python3 macrobenchmark/scripts/measure_details_transition.py \
        --case tablet --title "Some work in your list" --runs 5

    # interleaved A/B of two builds installed side by side or by turns (see --ab)
    python3 macrobenchmark/scripts/measure_details_transition.py \
        --case tablet --title "Some work in your list" --runs 8 \
        --ab /path/to/baseline.apk /path/to/change.apk

The work is matched by its card's content description (the full title, unlike the
ellipsized visible line). The window size and density are restored on every exit path,
including Ctrl-C, and the tool prints the values it started and ended with.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time

TABLET_SIZE = "2560x1600"
TABLET_DENSITY = "320"


class Device:
    def __init__(self, package: str, activity: str, adb: str | None = None):
        self.package = package
        self.activity = activity
        self.adb = adb or (
            os.path.join(os.environ["ANDROID_HOME"], "platform-tools", "adb")
            if os.environ.get("ANDROID_HOME")
            else shutil.which("adb")
        )
        if not self.adb:
            sys.exit("adb not found: set ANDROID_HOME or put adb on PATH")

    def sh(self, *args: str, timeout: int = 600) -> str:
        return subprocess.run(
            [self.adb, *args], capture_output=True, text=True, timeout=timeout
        ).stdout

    def window(self) -> tuple[str, str]:
        return self.sh("shell", "wm", "size").strip(), self.sh("shell", "wm", "density").strip()

    def apply_tablet(self) -> None:
        self.sh("shell", "wm", "size", TABLET_SIZE)
        self.sh("shell", "wm", "density", TABLET_DENSITY)
        time.sleep(1.5)

    def reset_window(self) -> None:
        self.sh("shell", "wm", "size", "reset")
        self.sh("shell", "wm", "density", "reset")
        time.sleep(1.5)

    def temperature(self) -> float | None:
        for line in self.sh("shell", "dumpsys", "battery").splitlines():
            if "temperature" in line:
                return int(line.split(":")[1].strip()) / 10.0
        return None

    def install(self, apk: str) -> None:
        self.sh("shell", "am", "force-stop", self.package)
        self.sh("install", "-r", "-d", apk)
        # The AOT state dies with the install; recompile so both builds of an A/B start equal.
        self.sh("shell", "cmd", "package", "compile", "-m", "speed", "-f", self.package)

    def find_item(self, title: str) -> tuple[int, int] | None:
        for _ in range(12):
            self.sh("shell", "uiautomator", "dump", "/sdcard/window.xml")
            xml = self.sh("shell", "cat", "/sdcard/window.xml")
            for node in re.findall(r"<node[^>]*>", xml):
                if f'content-desc="{title}"' not in node:
                    continue
                match = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
                if not match:
                    continue
                x1, y1, x2, y2 = map(int, match.groups())
                if x2 > x1 and y2 > y1:
                    return (x1 + x2) // 2, (y1 + y2) // 2
            time.sleep(1)
            self.sh("shell", "input", "swipe", "640", "2000", "640", "900", "12")
        return None

    def flight(self, title: str) -> str:
        """One launch -> tap -> back -> settle flight; returns the gfxinfo summary text."""
        self.sh("shell", "am", "force-stop", self.package)
        time.sleep(0.5)
        # The main activity directly: the app restores whatever activity it was on last, and a
        # benchmark that silently measures a settings screen is worse than no benchmark.
        self.sh("shell", "am", "start", "-W", "-n", f"{self.package}/{self.activity}")
        position = self.find_item(title)
        if not position:
            raise SystemExit(f"title {title!r} not found in the list")
        self.sh("shell", "dumpsys", "gfxinfo", self.package, "reset")
        time.sleep(0.3)
        self.sh("shell", "input", "tap", str(position[0]), str(position[1]))
        time.sleep(0.9)     # the details page and its hero settle
        self.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.7)     # the list and its hero settle
        return self.sh("shell", "dumpsys", "gfxinfo", self.package)


def parse(summary: str) -> dict | None:
    jank = re.search(r"Janky frames:.*?(\d+)%", summary)
    p90 = re.search(r"90th percentile: (\d+)ms", summary)
    p95 = re.search(r"95th percentile: (\d+)ms", summary)
    frames = re.search(r"Total frames rendered: (\d+)", summary)
    if not (jank and p90):
        return None
    return {
        "frames": int(frames.group(1)) if frames else 0,
        "jankPct": float(jank.group(1)),
        "p90": int(p90.group(1)),
        "p95": int(p95.group(1)) if p95 else None,
    }


def median(values: list) -> float | None:
    ordered = sorted(values)
    return ordered[len(ordered) // 2] if ordered else None


def summarise(label: str, rows: list[dict]) -> None:
    if not rows:
        print(f"{label}: no usable runs")
        return
    janks = [r["jankPct"] for r in rows]
    p90s = [r["p90"] for r in rows]
    p95s = [r["p95"] for r in rows if r["p95"]]
    print(
        f"{label}: n={len(rows)} jankMedian={median(janks)} jankAll={sorted(janks)} "
        f"p90Median={median(p90s)} p90All={sorted(p90s)} "
        f"p95Median={median(p95s)} p95All={sorted(p95s)}"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", default="org.skepsun.kototoro.debug")
    parser.add_argument("--title", required=True, help="a work shown in the list the app opens on")
    parser.add_argument("--case", choices=["phone", "tablet"], default="phone")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--min-frames", type=int, default=40, help="discard runs shorter than this")
    parser.add_argument("--activity", default="org.skepsun.kototoro.main.ui.MainActivity")
    parser.add_argument("--adb", default=None)
    parser.add_argument(
        "--ab",
        nargs=2,
        metavar=("BASELINE_APK", "CHANGE_APK"),
        help="install these two builds alternately and report both; cancels thermal drift",
    )
    args = parser.parse_args()

    device = Device(args.package, args.activity, args.adb)
    before = device.window()
    collected: dict[str, list[dict]] = {}
    try:
        if args.case == "tablet":
            device.apply_tablet()
        order = [("baseline", args.ab[0]), ("change", args.ab[1])] if args.ab else [(None, None)]
        for index in range(args.runs):
            for label, apk in order:
                if apk:
                    device.install(apk)
                start = device.temperature()
                result = parse(device.flight(args.title))     # warmup, discarded
                result = parse(device.flight(args.title))
                end = device.temperature()
                name = label or "run"
                print(f"{name} {index + 1}/{args.runs} temp={start}->{end} {result}", flush=True)
                if result and (result["frames"] or 0) >= args.min_frames:
                    collected.setdefault(name, []).append(result)
                elif result:
                    print(f"  discarded: only {result['frames']} frames in the window", flush=True)
    finally:
        device.reset_window()
    after = device.window()
    print(f"window before={before} after={after}")
    for label, rows in collected.items():
        summarise(label, rows)
    if before != after:
        print("WARNING: the window size or density was not restored", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
