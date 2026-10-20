#!/usr/bin/env python3
"""Check a portrait recording of fixture_reader_zoom during zoom in/out.

Run LocalImportTests/testContinuousDoubleTapZoomsAtTheTap while recording with
`xcrun simctl io <device> recordVideo --codec=h264 <video>`. Pass the interval
AFTER the settings panel closes and BEFORE the app exits. The fixture's vertical
colour bands and horizontal page boundary must stay straight in every frame;
a shrunken viewport snapshot over the final layout produces a rectangular step.

Requires ffmpeg (also used by stage-picker-fixtures.py), no Python packages.
Exit 1 means a broken frame; exit 2 means insufficient fixture coverage.
"""

import argparse
import subprocess
import sys


WIDTH, HEIGHT, FPS = 320, 696, 60


def inspect(frame):
    def pixel(x, y):
        offset = (y * WIDTH + x) * 3
        return frame[offset:offset + 3]

    # Only judge frames containing the loaded colour fixture across the reading surface.
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
    args = parser.parse_args()
    if args.start < 0 or args.duration <= 0:
        parser.error("start must be nonnegative and duration must be positive")
    command = ["ffmpeg", "-v", "error", "-ss", str(args.start), "-i", args.video,
               "-t", str(args.duration), "-vf", f"fps={FPS},scale={WIDTH}:{HEIGHT}",
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    checked = 0
    failures = []
    frame_index = 0
    with subprocess.Popen(command, stdout=subprocess.PIPE) as process:
        while True:
            frame = process.stdout.read(WIDTH * HEIGHT * 3)
            if not frame:
                break
            if len(frame) != WIDTH * HEIGHT * 3:
                raise RuntimeError("Incomplete video frame")
            broken = inspect(frame)
            if broken is not None:
                checked += 1
            if broken:
                if not failures and args.failure_frame:
                    with open(args.failure_frame, "wb") as output:
                        output.write(f"P6\n{WIDTH} {HEIGHT}\n255\n".encode() + frame)
                failures.append(round(args.start + frame_index / FPS, 3))
            frame_index += 1
        if process.wait() != 0:
            return 2
    print(f"checked={checked}, discontinuous_frames={len(failures)}, times={failures[:12]}")
    if checked < FPS:
        print("Insufficient fixture coverage: include at least one second of loaded reader content.", file=sys.stderr)
        return 2
    return int(bool(failures))


if __name__ == "__main__":
    sys.exit(main())
