#!/usr/bin/env python3
"""Builds the demo library into a Mihon extension JAR for the desktop app.

    build.py <library> <compatibility-dir> <out.jar>

`<library>` is the output of make_demo_library.py; `<compatibility-dir>` holds the pinned desktop compatibility JARs
(build/desktop-compatibility, see desktop-app/README.md). The manga pages and a catalog are packed into the JAR as
resources. Needs a JDK 11+ on JAVA_HOME or PATH, and a kotlin-stdlib JAR to compile against: the newest one in the
Gradle cache is used unless KOTLIN_STDLIB names another.
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = pathlib.Path(__file__).resolve().parent


def kotlin_stdlib():
    if os.environ.get("KOTLIN_STDLIB"):
        return os.environ["KOTLIN_STDLIB"]
    cache = pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib"
    found = sorted(cache.glob("2.*/*/kotlin-stdlib-2.*[0-9].jar"))
    if not found:
        sys.exit("No kotlin-stdlib JAR in the Gradle cache; build the project once or set KOTLIN_STDLIB.")
    return str(found[-1])


def main():
    library, compat, out = (pathlib.Path(arg).resolve() for arg in sys.argv[1:4])
    metadata = json.loads((library / "metadata.json").read_text(encoding="utf-8"))
    javac = shutil.which("javac", path=os.path.join(os.environ.get("JAVA_HOME", ""), "bin")) or shutil.which("javac")
    with tempfile.TemporaryDirectory() as work:
        classes = pathlib.Path(work)
        subprocess.run([javac, "--release", "11", "-encoding", "UTF-8", "-nowarn", "-d", str(classes),
                        "-cp", os.pathsep.join([str(jar) for jar in sorted(compat.glob("*.jar"))] + [kotlin_stdlib()]),
                        str(HERE / "DemoSource.java")], check=True)
        catalog = []
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as jar:
            jar.write(HERE / "AndroidManifest.xml", "AndroidManifest.xml")
            for compiled in sorted(classes.rglob("*.class")):
                jar.write(compiled, compiled.relative_to(classes).as_posix())
            # metadata.json keeps the shelf order of the Android screenshots.
            for key, meta in metadata.items():
                kind, title = key.split("/", 1)
                if kind != "manga":
                    continue
                catalog.append("\t".join(["M", title, ", ".join(meta.get("authors", [])), meta.get("state", ""),
                                          ", ".join(meta.get("tags", [])), meta.get("description", "")]))
                for chapter in sorted((library / "manga" / title).iterdir()):
                    pages = sorted(chapter.glob("*.png"))
                    catalog.append(f"C\t{chapter.name}\t{len(pages)}")
                    for page in pages:
                        jar.write(page, f"demo/manga/{title}/{chapter.name}/{page.name}", zipfile.ZIP_STORED)
            jar.writestr("demo/catalog.tsv", "\n".join(catalog) + "\n")
    print(out)


if __name__ == "__main__":
    main()
