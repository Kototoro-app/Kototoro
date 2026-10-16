#!/usr/bin/env python3
"""Puts document-picker fixtures into the simulator's "On My iPhone" storage:
  * fixture_mihon.tachibk — a small Mihon backup (gzip + protobuf), for BackupFlowTests;
  * fixture_comic.cbz / fixture_novel.txt — a two-page comic and a GB18030 novel, for LocalImportTests.
  * fixture_reader_long.cbz / fixture_reader_error.cbz — long-page restore and neighbour-failure regressions.
  * fixture_reader_chapters.cbz / fixture_reader_download.cbz — automatic chapter turns and offline repair.
  * fixture_reader_directory.cbz — scroll-to-panel handoff through a long chapter directory.
  * fixture_reader_horizontal.cbz — horizontal reading direction, zoom and anchored restore.
  * fixture_reader_pull_long.cbz — explicit previous-chapter last-page restore.
  * fixture_reader_window.cbz — bounded paging windows across three chapters.
  * fixture_reader_paged.cbz — longer chapters for page-by-page drags across a chapter boundary.
  * fixture_reader_zoom.cbz — red/green/blue thirds, to see where a paged double tap zooms.
  * fixture_storage_comic.cbz / fixture_storage_tasks.cbz — cache cleanup and offline file ownership.
  * fixture_novel_controls.txt — long chapters for novel controls, paging and floating action preferences.

Usage: stage-picker-fixtures.py <simulator id> (ffmpeg is required for the local video fixture)
"""
import glob
import gzip
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def field(number, value):
    if isinstance(value, int):
        return varint(number << 3) + varint(value)
    data = value.encode() if isinstance(value, str) else value
    return varint(number << 3 | 2) + varint(len(data)) + data


def manga(source, url, title, category):
    return (field(1, source) + field(2, url) + field(3, title) + field(13, 1_700_000_000_000)
            + field(17, category) + field(100, 1))


backup = (
    field(1, manga(4_242_424_242, "/manga/ios-fixture", "iOS 导入测试作品", 1))
    + field(2, field(1, "外部导入") + field(2, 1))
    + field(101, field(1, "Fixture Source") + field(2, 4_242_424_242))
)

device = sys.argv[1]
root = os.path.expanduser(f"~/Library/Developer/CoreSimulator/Devices/{device}/data/Containers/Shared/AppGroup")
storages = glob.glob(os.path.join(root, "*", "File Provider Storage"))
if not storages:
    sys.exit("No 'On My iPhone' storage yet: open the Files app (or save a file from the app) once first.")


def png(width, height, rgb, columns=None):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    row = b"".join(bytes(columns[x * len(columns) // width]) for x in range(width)) if columns else bytes(rgb) * width
    raw = b"".join(b"\x00" + row for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


novel = "简介：本地导入测试用的小说，内容很短但足够测试。\n第一章 本地开端\n　　这是第一章的正文。\n第二章 本地结尾\n　　这是第二章的正文。\n"
video = pathlib.Path(__file__).resolve().parents[1] / "build" / "local-video-fixture" / "fixture_video.mp4"
if not video.exists():
    video.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        shutil.which("ffmpeg") or "ffmpeg", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24:duration=30",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=30",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
        "-movflags", "+faststart", str(video),
    ], check=True)
for storage in storages:
    shutil.copyfile(video, os.path.join(storage, video.name))
    with open(os.path.join(storage, "fixture_broken_video.mp4"), "wb") as file:
        file.write(b"not a video")
    path = os.path.join(storage, "fixture_mihon.tachibk")
    with open(path, "wb") as file:
        file.write(gzip.compress(backup))
    with zipfile.ZipFile(os.path.join(storage, "fixture_comic.cbz"), "w") as archive:
        archive.writestr("001.png", png(300, 420, (200, 60, 60)))
        archive.writestr("002.png", png(300, 420, (60, 60, 200)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_long.cbz"), "w") as archive:
        archive.writestr("001.png", png(800, 12000, (200, 60, 60)))
        archive.writestr("002.png", png(800, 12000, (60, 60, 200)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_error.cbz"), "w") as archive:
        archive.writestr("001.png", png(300, 420, (200, 60, 60)))
        archive.writestr("002.png", b"\x89PNG\r\n\x1a\ninvalid image")
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_chapters.cbz"), "w") as archive:
        archive.writestr("01/001.png", png(300, 200, (200, 60, 60)))
        archive.writestr("02/001.png", png(300, 200, (60, 60, 200)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_directory.cbz"), "w") as archive:
        for chapter in range(1, 61):
            archive.writestr(f"{chapter:02d}/001.png", png(300, 200, (60, 120, 180)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_download.cbz"), "w") as archive:
        archive.writestr("001.png", png(300, 420, (200, 60, 60)))
        archive.writestr("002.png", png(300, 420, (60, 60, 200)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_storage_comic.cbz"), "w") as archive:
        archive.writestr("01/001.png", png(600, 900, (210, 70, 40)))
        archive.writestr("01/002.png", png(600, 900, (40, 90, 210)))
        archive.writestr("02/001.png", png(600, 900, (50, 180, 90)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_storage_tasks.cbz"), "w") as archive:
        archive.writestr("01/001.png", png(600, 900, (160, 80, 170)))
        archive.writestr("01/002.png", png(600, 900, (70, 140, 150)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_horizontal.cbz"), "w") as archive:
        archive.writestr("001.png", png(1800, 600, (200, 60, 60)))
        archive.writestr("002.png", png(900, 600, (60, 60, 200)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_pull_long.cbz"), "w") as archive:
        archive.writestr("01/001.png", png(600, 4000, (200, 60, 60)))
        archive.writestr("01/002.png", png(600, 4000, (160, 40, 40)))
        archive.writestr("02/001.png", png(600, 800, (60, 60, 200)))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_window.cbz"), "w") as archive:
        for chapter, color in [("01", (200, 60, 60)), ("02", (60, 60, 200)), ("03", (60, 200, 60))]:
            for page in range(1, 4):
                archive.writestr(f"{chapter}/{page:03d}.png", png(600, 800, color))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_paged.cbz"), "w") as archive:
        for chapter, color in [("01", (200, 60, 60)), ("02", (60, 60, 200)), ("03", (60, 200, 60))]:
            for page in range(1, 9):
                shade = tuple(max(0, value - page * 12) for value in color)
                archive.writestr(f"{chapter}/{page:03d}.png", png(600, 900, shade))
    with zipfile.ZipFile(os.path.join(storage, "fixture_reader_zoom.cbz"), "w") as archive:
        archive.writestr("001.png", png(600, 900, None, [(200, 40, 40), (40, 200, 40), (40, 40, 200)]))
        archive.writestr("002.png", png(600, 900, (90, 90, 90)))
    with open(os.path.join(storage, "fixture_novel.txt"), "wb") as file:
        file.write(novel.encode("gb18030"))
    with open(os.path.join(storage, "fixture_novel_controls.txt"), "w", encoding="utf-8") as file:
        for chapter in range(1, 3):
            file.write(f"第{chapter}章 阅读控件\n")
            for paragraph in range(1, 41):
                file.write(f"第{paragraph}段，晨光落在书页上，读者沿着文字继续前行。" * 5 + "\n\n")
    print(storage)
