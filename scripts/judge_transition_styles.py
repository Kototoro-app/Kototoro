"""CS-2 device visual confirmation: do the transition styles actually render differently?

The closure plan's device method: capture one frame per transition style **at the same drag
position, finger still down**, then compare the frames numerically. Two metrics carry the verdict:

  - the share of moved pixels between two styles (styles that collapsed onto one rendering path
    would differ only in the noise);
  - the **inner edge slope** of the changed region: a slide keeps a vertical boundary (slope ~ 0)
    while a paper fold clips along a tilted crease, and a tilt cannot be produced by translation.

Reads the `.raw` captures (16-byte header + RGBA8888) that `scripts/capture_transition_styles.ps1`
produces, reusing the decoder in `transition_probe.py`.

Usage:
    python scripts/judge_transition_styles.py --dir E:/kototoro_demo/reader-bench/cs2-visual-20260921
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from transition_probe import load_any  # noqa: E402

STYLES = ("none", "default", "advanced", "simulation")
LABELS = {
    "none": "NONE (no animation)",
    "default": "DEFAULT (slide)",
    "advanced": "ADVANCED (cover)",
    "simulation": "SIMULATION (curl)",
}


def inner_edge_slope(a: np.ndarray, b: np.ndarray, threshold: int = 40, margin: int = 300) -> tuple[float, float, int]:
    """Slope of the changed region's inner (leading) edge, per row."""
    diff = np.abs(a.astype(np.int16) - b.astype(np.int16)).max(axis=2)
    band = diff[margin:a.shape[0] - margin]
    rows = np.where((band > threshold).any(axis=1))[0]
    if rows.size < 10:
        return 0.0, 0.0, int(rows.size)
    min_x = np.array([np.where(band[y] > threshold)[0].min() for y in rows], dtype=np.float32)
    slope, intercept = np.polyfit(rows.astype(np.float32), min_x, 1)
    predicted = slope * rows.astype(np.float32) + intercept
    ss_res = float(((min_x - predicted) ** 2).sum())
    ss_tot = float(((min_x - min_x.mean()) ** 2).sum())
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else 0.0
    return float(slope), float(r2), int(rows.size)


def moved_share(a: np.ndarray, b: np.ndarray, threshold: int = 12) -> float:
    diff = np.abs(a.astype(np.int16) - b.astype(np.int16)).max(axis=2)
    return float((diff > threshold).mean())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, required=True)
    args = parser.parse_args()

    frames: dict[str, np.ndarray] = {}
    for style in STYLES:
        path = args.dir / f"{style}-drag.raw"
        if not path.is_file():
            print(f"missing capture: {path}")
            return 1
        frames[style] = load_any(str(path))

    first = next(iter(frames.values()))
    print(f"captures: {first.shape[1]}x{first.shape[0]} per style\n")

    print("A. Do the styles render differently? (share of moved pixels, threshold 12)")
    print(f"   {'pair':38s} {'moved share':>12s}   verdict")
    pairs = [
        ("none", "default"),
        ("default", "advanced"),
        ("default", "simulation"),
        ("advanced", "simulation"),
    ]
    for left, right in pairs:
        share = moved_share(frames[left], frames[right])
        verdict = "same rendering path" if share < 0.01 else "different rendering"
        print(f"   {LABELS[left]:18s} vs {LABELS[right]:18s} {share:11.4f}   {verdict}")

    print("\nB. Is the transition a translation or a fold? (inner edge slope of the changed region)")
    print(f"   {'style':38s} {'inner slope':>12s} {'r2':>6s} {'rows':>6s}   verdict")
    rest_frames = {}
    for style in STYLES:
        rest_path = args.dir / f"{style}-rest.raw"
        if rest_path.is_file():
            rest_frames[style] = load_any(str(rest_path))
    for style in STYLES:
        reference = rest_frames.get("none")
        if reference is None:
            reference = rest_frames.get(style)
        if reference is None:
            continue
        slope, r2, rows = inner_edge_slope(reference, frames[style])
        if abs(slope) < 0.02:
            verdict = "vertical boundary -> translation"
        elif abs(slope) > 0.1:
            verdict = "tilted boundary -> fold/curl"
        else:
            verdict = "slightly tilted"
        print(f"   {LABELS[style]:38s} {slope:+12.4f} {r2:6.3f} {rows:6d}   {verdict}")

    # A single straight-line fit cannot describe a crease that is curved, so a low slope with a low
    # r2 is not evidence of a vertical boundary — and a handful of pixels of spread is not evidence
    # of a tilted one. The discriminating quantity is how far the edge travels across the rows.
    print("\nC. Spread of the changed region's inner edge across rows (the discriminating quantity)")
    print(f"   {'style':38s} {'x spread':>9s} {'x std':>7s} {'x range':>14s}   reading")
    for style in STYLES:
        reference = rest_frames.get("none")
        if reference is None:
            reference = rest_frames.get(style)
        if reference is None:
            continue
        diff = np.abs(reference.astype(np.int16) - frames[style].astype(np.int16)).max(axis=2)
        band = diff[300:diff.shape[0] - 300]
        rows = np.where((band > 40).any(axis=1))[0]
        if rows.size < 10:
            print(f"   {LABELS[style]:38s} {'-':>9s} {'-':>7s} {'-':>14s}   no change")
            continue
        min_x = np.array([np.where(band[y] > 40)[0].min() for y in rows], dtype=np.float32)
        spread = int(min_x.max() - min_x.min())
        if spread < 40:
            reading = "vertical boundary (translation)"
        else:
            reading = "edge travels with y (tilted/curved, not translation)"
        print(
            f"   {LABELS[style]:38s} {spread:9d} {min_x.std():7.1f} "
            f"{f'[{int(min_x.min())},{int(min_x.max())}]':>14s}   {reading}"
        )

    note = (
        "\nA style comparison is only meaningful between frames taken at the same drag position; "
        "the captured position is whatever the injected drag reached, so read the pairs, not the rows."
    )
    print(note)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
