#!/usr/bin/env python3
"""Verify the Debian payload against the tested image, then run its native launcher without installing it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def verify(package: Path, image: Path, icon: Path, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kototoro-deb-") as temporary:
        root = Path(temporary)
        subprocess.run(["dpkg-deb", "-x", str(package), str(root / "extracted")], check=True)
        installed = root / "extracted/opt/kototoro"
        checked = 0
        for source in image.rglob("*"):
            relative = source.relative_to(image)
            # jpackage removes its build-time state when turning an app image into an installer.
            if relative == Path("lib/app/.jpackage.xml"):
                continue
            target = installed / relative
            if source.is_symlink():
                if not target.is_symlink() or source.readlink() != target.readlink():
                    raise ValueError(f"Packaged symlink changed: {relative}")
            elif source.is_file():
                if not target.is_file() or target.is_symlink():
                    raise ValueError(f"Packaged file missing: {relative}")
                if hashlib.sha256(source.read_bytes()).digest() != hashlib.sha256(target.read_bytes()).digest():
                    raise ValueError(f"Packaged bytes changed: {relative}")
                if source.stat().st_mode & 0o111 != target.stat().st_mode & 0o111:
                    raise ValueError(f"Packaged executable permissions changed: {relative}")
                checked += 1
        desktop = (installed / "lib/kototoro-Kototoro.desktop").read_text()
        for entry in ("Exec=/opt/kototoro/bin/Kototoro", "Icon=/opt/kototoro/lib/Kototoro.png", "Type=Application"):
            if entry not in desktop.splitlines():
                raise ValueError(f"Invalid desktop entry: {entry}")
        packaged_icon = installed / "lib/Kototoro.png"
        if hashlib.sha256(icon.read_bytes()).digest() != hashlib.sha256(packaged_icon.read_bytes()).digest():
            raise ValueError(f"Packaged icon is not Android's app icon: {packaged_icon}")
        environment = dict(os.environ)
        for key in ("JAVA_HOME", "CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
            environment.pop(key, None)
        environment["PATH"] = "/usr/bin:/bin"
        report = output.resolve() / "runtime-check.properties"
        with (output / "runtime-check.log").open("w") as log:
            subprocess.run([str(installed / "bin/Kototoro"), "--data-dir", str(root / "data"),
                            "--check-runtime", str(report)], env=environment, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=60)
        (output / "contents.json").write_text(json.dumps({
            "package": str(package), "files_checked": checked, "content": "identical",
            "executable_modes": "preserved", "icon": "android", "runtime_exit": 0,
        }, indent=2) + "\n")
        print(f"Verified {checked} files and the extracted Debian launcher: {package}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", required=True, type=Path)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--icon", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    verify(args.package.resolve(), args.image.resolve(), args.icon.resolve(), args.output.resolve())
