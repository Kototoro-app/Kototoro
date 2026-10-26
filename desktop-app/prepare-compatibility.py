#!/usr/bin/env python3
"""Extract only the shared, pinned JVM runtime from a previously downloaded desktop ZIP."""

import argparse
import hashlib
from pathlib import Path
import re
import zipfile


def prepare(archive: Path, destination: Path) -> None:
    pins_file = Path(__file__).resolve().parent.parent / "mihon-desktop-compat/windows-runtime-pins.properties"
    pins = dict(line.split("=", 1) for line in pins_file.read_text().splitlines() if line and not line.startswith("#"))
    verified = {}
    with zipfile.ZipFile(archive) as bundle:
        for name, expected in pins.items():
            entries = [entry for entry in bundle.infolist()
                       if re.sub(r"-[0-9a-f]{16,64}(?=\.jar$)", "",
                                 re.sub(r"^\d{3}-", "", Path(entry.filename).name)) == name]
            if len(entries) != 1:
                raise ValueError(f"Archive must contain exactly one {name}")
            if entries[0].file_size > 128 * 1024 * 1024:
                raise ValueError(f"Runtime artifact exceeds size limit: {name}")
            data = bundle.read(entries[0])
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError(f"Runtime SHA-256 mismatch: {name}")
            verified[name] = data
    # Validate the entire input before writing anything; never extract archive paths or unpinned native binaries.
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in verified.items():
        target = destination / name
        if target.is_symlink():
            raise ValueError(f"Runtime destination must not be a symbolic link: {name}")
        target.write_bytes(data)
    print(f"Verified {len(verified)} shared JVM runtime JARs in {destination}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).resolve().parent.parent / "build/desktop-compatibility")
    args = parser.parse_args()
    prepare(args.archive, args.output)
