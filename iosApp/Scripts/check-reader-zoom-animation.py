#!/usr/bin/env python3
"""Check a portrait recording of fixture_reader_zoom during zoom in/out.

Run LocalImportTests/testContinuousDoubleTapZoomsAtTheTap while recording with
`xcrun simctl io <device> recordVideo --codec=h264 <video>`. Pass the interval
AFTER the settings panel closes and BEFORE the app exits. The fixture's vertical
colour bands and horizontal page boundary must stay straight in every frame;
a shrunken viewport snapshot over the final layout produces a rectangular step.
Blank frames also fail the check. Use --blank-only with other coloured reader
fixtures, selecting an interval containing only the loaded reading surface.
Use --partial-black for arbitrary content: it detects temporary black rectangles
between matching frames, including a page replaced by its loading background.

Requires ffmpeg (also used by stage-picker-fixtures.py), no Python packages.
Exit 1 means a broken frame; exit 2 means insufficient fixture coverage.
"""

import argparse
from collections import deque
import subprocess
import sys


WIDTH, HEIGHT, FPS = 320, 696, 60


class PartialBlackFlashDetector:
    """Find content replaced by black between two matching rendered frames, including partial-page reloads."""

    def __init__(self):
        self.radius = FPS // 5
        self.frames = deque()

    @staticmethod
    def sample(frame):
        pixels = b"".join(frame[(y * WIDTH + x) * 3:(y * WIDTH + x) * 3 + 3]
                          for y in range(HEIGHT // 20, HEIGHT * 19 // 20, 8)
                          for x in range(4, WIDTH - 4, 8))
        black = sum(max(pixels[i:i + 3]) < 24 for i in range(0, len(pixels), 3))
        return pixels, black

    @staticmethod
    def missing(before, current, after):
        total = len(current) // 3
        stable = missing = 0
        for i in range(0, len(current), 3):
            a, c, b = before[i:i + 3], current[i:i + 3], after[i:i + 3]
            if max(abs(a[j] - b[j]) for j in range(3)) <= 24:
                stable += 1
                if max(c) < 24 and max(a) > 64 and max(b) > 64:
                    missing += 1
        return stable >= total * 0.95 and missing >= total * 0.03

    def push(self, index, frame):
        pixels, black = self.sample(frame)
        self.frames.append((index, frame, pixels, black))
        if len(self.frames) < self.radius * 2 + 1:
            return None
        center = self.frames[self.radius]
        threshold = len(pixels) // 3 * 0.03
        before = [f for f in list(self.frames)[:self.radius] if f[3] < center[3] - threshold]
        after = [f for f in list(self.frames)[self.radius + 1:] if f[3] < center[3] - threshold]
        broken = any(self.missing(a[2], center[2], b[2]) for a in reversed(before) for b in after)
        self.frames.popleft()
        return center[0], center[1], broken


def inspect(frame, blank_only=False):
    def pixel(x, y):
        offset = (y * WIDTH + x) * 3
        return frame[offset:offset + 3]

    # A fully blank frame was previously skipped along with settings/loading screens, hiding reset flashes.
    if all(max(pixel(x, y)) < 24
           for x in range(WIDTH // 4, WIDTH * 3 // 4, 16)
           for y in range(HEIGHT // 5, HEIGHT * 3 // 5, 16)):
        return True
    if blank_only:
        return False

    # Only judge other frames containing the loaded colour fixture across the reading surface.
    row = [sorted(pixel(x, int(HEIGHT * 0.4))) for x in range(8, WIDTH - 8, 3)]
    if sum(rgb[2] > 100 and rgb[2] - rgb[1] > 60 for rgb in row) < len(row) * 0.75:
        return None

    broken_columns = 0
    ends = []
    for x in range(8, WIDTH - 8, 3):
        column = [pixel(x, y) for y in range(int(HEIGHT * 0.15), int(HEIGHT * 0.6), 4)]
        if any(max(p[c] for p in column) - min(p[c] for p in column) > 50 for c in range(3)):
            broken_columns += 1
        # The second fixture page is neutral grey; its top edge cannot form a step during a camera transform.
        end = int(HEIGHT * 0.84)
        for y in range(int(HEIGHT * 0.4), end, 2):
            rgb = pixel(x, y)
            if max(rgb) - min(rgb) < 16 and 150 < sum(rgb) < 390:
                end = y
                break
        ends.append(end)
    ends.sort()
    return broken_columns > 4 or ends[len(ends) * 9 // 10] - ends[len(ends) // 10] > 6


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video")
    parser.add_argument("--start", type=float, required=True)
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--failure-frame", help="Optional first broken frame, written as a PPM image")
    parser.add_argument("--blank-only", action="store_true", help="Check only blank flashes, for other reader fixtures")
    parser.add_argument("--partial-black", action="store_true",
                        help="Detect temporary black rectangles between matching frames of arbitrary reader content")
    args = parser.parse_args()
    if args.start < 0 or args.duration <= 0:
        parser.error("start must be nonnegative and duration must be positive")
    command = ["ffmpeg", "-v", "error", "-ss", str(args.start), "-i", args.video,
               "-t", str(args.duration), "-vf", f"fps={FPS},scale={WIDTH}:{HEIGHT}",
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    checked = 0
    failures = []
    frame_index = 0
    partial = PartialBlackFlashDetector() if args.partial_black else None
    with subprocess.Popen(command, stdout=subprocess.PIPE) as process:
        while True:
            frame = process.stdout.read(WIDTH * HEIGHT * 3)
            if not frame:
                break
            if len(frame) != WIDTH * HEIGHT * 3:
                raise RuntimeError("Incomplete video frame")
            if partial:
                result = partial.push(frame_index, frame)
                frame_index += 1
                if result is None:
                    continue
                judged_index, judged_frame, broken = result
            else:
                judged_index, judged_frame = frame_index, frame
                broken = inspect(frame, blank_only=args.blank_only)
                frame_index += 1
            if broken is not None:
                checked += 1
            if broken:
                if not failures and args.failure_frame:
                    with open(args.failure_frame, "wb") as output:
                        output.write(f"P6\n{WIDTH} {HEIGHT}\n255\n".encode() + judged_frame)
                failures.append(round(args.start + judged_index / FPS, 3))
        if process.wait() != 0:
            return 2
    print(f"checked={checked}, discontinuous_frames={len(failures)}, times={failures[:12]}")
    if checked < FPS:
        print("Insufficient fixture coverage: include at least one second of loaded reader content.", file=sys.stderr)
        return 2
    return int(bool(failures))


if __name__ == "__main__":
    sys.exit(main())
