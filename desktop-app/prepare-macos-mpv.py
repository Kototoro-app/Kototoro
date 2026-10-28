#!/usr/bin/env python3
"""Prepare pinned, self-contained LGPL libmpv resources without installing system packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request


SYSTEM_PREFIXES = ("/System/Library/", "/usr/lib/")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def run(*arguments):
    result = subprocess.run(arguments, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed: {result.stderr.strip()} {result.stdout.strip()}")
    return result.stdout


def fetch(spec, pins, cache, supplied=None):
    cache.mkdir(parents=True, exist_ok=True)
    target = supplied or cache / spec["name"]
    if target.is_file() and sha256(target) == spec["sha256"]:
        return target
    if supplied:
        raise ValueError("The supplied libmpv archive does not match the pinned SHA-256")
    url = f'https://github.com/{pins["repository"]}/releases/download/{pins["tag"]}/{spec["name"]}'
    with tempfile.NamedTemporaryFile(dir=cache, prefix="mpv-", suffix=".part", delete=False) as output:
        partial = Path(output.name)
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Kototoro-macOS-build"})
            with urllib.request.urlopen(request, timeout=120) as response:
                shutil.copyfileobj(response, output)
            output.close()
            if sha256(partial) != spec["sha256"]:
                raise ValueError("Downloaded libmpv archive failed SHA-256 verification")
            os.replace(partial, target)
        finally:
            partial.unlink(missing_ok=True)
    return target


def archive_files(archive):
    """Read data rather than extracting paths; even alias targets must remain inside the archive."""
    members = {item.name.rstrip("/"): item for item in archive.getmembers()}
    roots = {PurePosixPath(name).parts[0] for name in members}
    if len(roots) != 1:
        raise ValueError("libmpv archive must have exactly one top directory")
    root = roots.pop()

    def read(name):
        seen = set()
        while True:
            if name in seen:
                raise ValueError("Archive link cycle")
            seen.add(name)
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or path.parts[0] != root:
                raise ValueError("Unsafe archive path")
            item = members[name]
            if item.issym():
                name = str(path.parent / item.linkname)
            elif item.islnk():
                name = item.linkname
            elif item.isfile():
                return archive.extractfile(item).read()
            else:
                raise ValueError("Archive entry is not a regular file")

    result = {}
    for name, item in members.items():
        relative = PurePosixPath(name).relative_to(root)
        if item.isdir() or not relative.parts:
            continue
        if relative.parts[0] in ("lib", "licenses") or str(relative) in ("build-info.json", "README-runtime.md"):
            result[str(relative)] = read(name)
    return result


def dependencies(path):
    # The first load command printed for a dylib is its own LC_ID_DYLIB, not a dependency.
    return [line.strip().split(" (compatibility version", 1)[0]
            for line in run("/usr/bin/otool", "-L", str(path)).splitlines()[2:] if line.strip()]


def rpaths(path):
    lines = run("/usr/bin/otool", "-l", str(path)).splitlines()
    result = []
    for index, line in enumerate(lines):
        if line.strip() == "cmd LC_RPATH":
            for following in lines[index + 1:index + 4]:
                if following.strip().startswith("path "):
                    result.append(following.strip()[5:].split(" (offset ", 1)[0])
    return result


def prepare(archive_path, output, pins, architecture):
    with tarfile.open(archive_path, "r:gz") as archive:
        files = archive_files(archive)
    info = json.loads(files["build-info.json"])
    if info.get("profile") != pins["profile"] or info.get("arch") != architecture:
        raise ValueError("libmpv profile or CPU does not match the selected build")
    if "licenses/mpv-LICENSE.LGPL" not in files or "licenses/ffmpeg-LICENSE.md" not in files:
        raise ValueError("libmpv archive is missing required notices")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="macos-mpv-", dir=output.parent) as temporary:
        prepared = Path(temporary) / "mpv"
        prepared.mkdir()
        pending = ["libmpv.2.dylib"]
        inventory = {}
        while pending:
            name = pending.pop()
            if name in inventory:
                continue
            source = f"lib/{name}"
            if source not in files or Path(name).name != name:
                raise ValueError(f"Dependency is missing from the pinned libmpv archive: {name}")
            library = prepared / name
            library.write_bytes(files[source])
            run("/usr/bin/lipo", "-verify_arch", architecture, str(library))
            links = dependencies(library)
            replacements = []
            for link in links:
                if link.startswith(SYSTEM_PREFIXES):
                    continue
                dependency = PurePosixPath(link).name
                if f"lib/{dependency}" not in files:
                    raise ValueError(f"Unbundled dependency: {link}")
                pending.append(dependency)
                replacements.extend(["-change", link, f"@loader_path/{dependency}"])
            arguments = ["/usr/bin/install_name_tool", "-id", f"@loader_path/{name}", *replacements]
            for rpath in dict.fromkeys(rpaths(library)):
                arguments.extend(["-delete_rpath", rpath])
            run(*arguments, str(library))
            run("/usr/bin/codesign", "--force", "--sign", "-", str(library))
            run("/usr/bin/codesign", "--verify", "--strict", str(library))
            inventory[name] = {"sha256": sha256(library), "dependencies": dependencies(library)}
        for name, data in files.items():
            if not name.startswith("licenses/") and name not in ("build-info.json", "README-runtime.md"):
                continue
            relative = PurePosixPath(name)
            if ".." in relative.parts or relative.is_absolute():
                raise ValueError("Unsafe notice path")
            target = prepared / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        for name, item in inventory.items():
            for dependency in item["dependencies"]:
                if dependency.startswith(SYSTEM_PREFIXES):
                    continue
                if not dependency.startswith("@loader_path/") or dependency[13:] not in inventory:
                    raise ValueError(f"Non-relocatable dependency in {name}: {dependency}")
        supplement = Path(__file__).parent / "distribution/licenses/lua-5.2.4-LICENSE.txt"
        shutil.copyfile(supplement, prepared / "licenses/lua-LICENSE.txt")
        manifest = {"source": pins, "architecture": architecture, "libraries": inventory}
        (prepared / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (prepared / "THIRD_PARTY_PLAYBACK.md").write_text(
            "# macOS bundled playback runtime\n\n"
            f'Build: https://github.com/{pins["repository"]}/tree/{pins["source_commit"]}\n\n'
            f'mpv source: {pins["mpv_repository"]} (ref recorded in build-info.json).\n\n'
            f'Pinned release: {pins["tag"]}; build profile: {pins["profile"]}.\n\n'
            "libmpv and its complete non-system dylib dependency closure are dynamically loaded from this directory.\n"
            "Exact component versions are in build-info.json; upstream licence texts are in licenses/.\n"
            "The library may be replaced by a compatible build. Kototoro changes only install names, rpaths and\n"
            "local ad-hoc code signatures; runtime-manifest.json lists the resulting hashes and dependencies.\n"
        )
        # Replace only this build task's generated directory, after verification has completed.
        if output.exists():
            shutil.rmtree(output)
        shutil.copytree(prepared, output)
    print(f"Prepared {len(inventory)} {architecture} libraries in {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pins", type=Path, required=True)
    parser.add_argument("--architecture", choices=("arm64", "x86_64"), required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path)
    options = parser.parse_args()
    pins = json.loads(options.pins.read_text())
    source = fetch(pins["assets"][options.architecture], pins, options.cache, options.archive)
    prepare(source, options.output, pins, options.architecture)


if __name__ == "__main__":
    main()
