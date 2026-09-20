"""CS-2 follow-up: if the changed regions match, are COVER and CURL the same rendering?

Section A of `judge_transition_styles.py` says the two frames differ on ~49% of their pixels, while
the inner-edge extent of both lands on the same range. Both cannot describe the same thing, so this
characterises the edge itself — its distribution across rows and its sign of travel — instead of
reducing it to two numbers.

Usage:
    python scripts/characterize_transition_edge.py --dir E:/kototoro_demo/reader-bench/cs2-visual-20260921
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
    "none": "NONE",
    "default": "DEFAULT (slide)",
    "advanced": "ADVANCED (cover)",
    "simulation": "SIMULATION (curl)",
}


def edge_per_row(a: np.ndarray, b: np.ndarray, threshold: int = 40) -> tuple[np.ndarray, np.ndarray]:
    diff = np.abs(a.astype(np.int16) - b.astype(np.int16)).max(axis=2)
    band = diff[300:diff.shape[0] - 300]
    rows = np.where((band > 40).any(axis=1))[0]
    if rows.size == 0:
        return np.array([]), np.array([])
    left = np.array([np.where(band[y] > threshold)[0].min() for y in rows], dtype=np.float32)
    right = np.array([np.where(band[y] > threshold)[0].max() for y in rows], dtype=np.float32)
    return left, right


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, required=True)
    args = parser.parse_args()

    reference = load_any(str(args.dir / "none-rest.raw"))
    print("Inner (leading) edge of the changed region, per row\n")
    print(f"   {'style':24s} {'rows':>5s} {'min x':>6s} {'max x':>6s} {'row share at each x':>0s}")
    for style in STYLES:
        left, _ = edge_per_row(reference, load_any(str(args.dir / f"{style}-drag.raw")))
        if left.size == 0:
            print(f"   {LABELS[style]:24s} {0:5d}      -      -")
            continue
        counts = np.bincount(left.astype(int))
        top = np.argsort(counts)[::-1][:4]
        top_str = ", ".join(f"x={int(x)}:{counts[x] / left.size:.0%}" for x in top if counts[x] > 0)
        print(
            f"   {LABELS[style]:24s} {left.size:5d} {int(left.min()):6d} {int(left.max()):6d}   {top_str}"
        )

    print("\nRow-by-row comparison of the leading edge (does the edge follow the same path?)")
    headers = {style: edge_per_row(reference, load_any(str(args.dir / f"{style}-drag.raw")))[0] for style in STYLES}
    pairs = [("default", "advanced"), ("default", "simulation"), ("advanced", "simulation")]
    for left_style, right_style in pairs:
        left, right = headers[left_style], headers[right_style]
        if left.size == 0 or right.size == 0 or left.size != right.size:
            print(f"   {LABELS[left_style]} vs {LABELS[right_style]}: not comparable ({left.size}/{right.size} rows)")
            continue
        same = int((left == right).sum())
        print(
            f"   {LABELS[left_style]:18s} vs {LABELS[right_style]:18s} rows={left.size} "
            f"identical_edge={same} ({same / left.size:.1%}) mean_abs_diff={np.abs(left - right).mean():.1f}px"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
