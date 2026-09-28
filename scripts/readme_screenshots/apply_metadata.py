#!/usr/bin/env python3
"""Merge the demo library's display metadata into the index.json files the app generated.

The app writes an index.json into every local title folder on its first library scan (ids, chapter
table, file URLs). Writing those by hand is fragile, so this script only adds the fields the details
page shows - authors, description, tags, state, rating - and leaves the rest as the app wrote it.

    python apply_metadata.py <out>/metadata.json [--package org.skepsun.kototoro.debug]

Run it after the app has scanned the pushed library once, then pull to refresh the library.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path


def adb(*args: str, capture: bool = False) -> str:
    env = dict(os.environ, MSYS_NO_PATHCONV="1")  # keep Git Bash from rewriting device paths
    result = subprocess.run(["adb", *args], check=True, capture_output=capture, text=True, env=env)
    return result.stdout if capture else ""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--package", default="org.skepsun.kototoro.debug")
    args = parser.parse_args()

    base = f"/sdcard/Android/data/{args.package}/files"
    catalogue = json.loads(args.metadata.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as tmp:
        local = Path(tmp) / "index.json"
        for key, meta in catalogue.items():
            remote = f"{base}/{key}/index.json"
            try:
                adb("pull", remote, str(local), capture=True)
            except subprocess.CalledProcessError:
                print(f"skip {key}: no index.json yet (open the app so it scans the library)")
                continue
            index = json.loads(local.read_text(encoding="utf-8"))
            index["authors"] = meta["authors"]
            index["author"] = meta["authors"][0]
            index["description"] = meta["description"]
            index["tags"] = [{"key": tag.lower().replace(" ", "_"), "title": tag} for tag in meta["tags"]]
            index["state"] = meta["state"]
            index["rating"] = meta["rating"]
            local.write_text(json.dumps(index, indent=4, ensure_ascii=False), encoding="utf-8")
            adb("push", str(local), remote, capture=True)
            print("patched", key)


if __name__ == "__main__":
    main()
