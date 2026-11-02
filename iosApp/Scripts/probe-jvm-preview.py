#!/usr/bin/env python3
"""Run the staged real-extension profile on this Mac's JDK through the JSONL host, before the simulator.

Same classpath, platform class and config as the iOS app (iosApp/build/jvm-real-api); data goes to a
fresh directory under iosApp/build. For each requested source: list -> details -> first chapter's pages / text,
plus an image or stream check. Usage: probe-jvm-preview.py [source names...] (default: one per ecosystem).
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

APP = Path(__file__).resolve().parent.parent
PROFILE = APP / "build/jvm-real-api"


class Host:
    def __init__(self):
        data = Path(tempfile.mkdtemp(prefix="jvm-probe-", dir=APP / "build"))
        config = json.loads((PROFILE / "sources/config.json").read_text())
        for item in config["jars"] + config.get("plugins", []):
            item["path"] = str(PROFILE / "sources" / item["path"])
        (data / "config.json").write_text(json.dumps(config))
        java = Path(os.environ.get("KOTOTORO_JVM_BOOT_JDK") or subprocess.check_output(
            ["/usr/libexec/java_home", "-v", "24"], text=True).strip()) / "bin/java"
        classpath = ":".join(str(p) for p in sorted((PROFILE / "lib").glob("*.jar")))
        self.process = subprocess.Popen(
            [java, "-Duser.home=" + str(data), "-Dkototoro.source.diagnostics=stderr", *os.environ.get("KOTOTORO_PROBE_JVM_OPTIONS", "").split(),
             "-cp", classpath, "org.skepsun.kototoro.source.host.SourceHostCliKt", "serve", data / "config.json"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=open(data / "host.log", "w"), text=True)
        self.data = data

    def call(self, call):
        request = {"version": 1, "requestId": str(uuid.uuid4()), "call": call}
        self.process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError(f"JVM host exited before replying; see {self.data / 'host.log'}")
        response = json.loads(line)
        if response.get("error"):
            raise RuntimeError(f"{call['operation']}: {response['error']}")
        return response["result"]

    def close(self):
        self.process.stdin.close()
        self.process.wait(timeout=30)


def probe(host, name, ref):
    out = [name, ref["contentType"]]
    items = host.call({"operation": "list", "sourceName": name, "offset": 0})["content"]
    out.append(f"list={len(items)}")
    if not items:
        return " ".join(out + ["EMPTY"])
    details = host.call({"operation": "details", "content": items[0], "fetchMode": "FORCE_REFRESH"})["content"]
    chapters = details.get("chapters") or []
    out += [f"title={details['title'][:30]!r}", f"chapters={len(chapters)}"]
    if not chapters:
        return " ".join(out + ["NO_CHAPTERS"])
    chapter = chapters[0]
    kind = ref["contentType"]
    if kind.endswith("NOVEL"):
        text = host.call({"operation": "chapterContent", "chapter": chapter})["content"]
        out.append(f"text={len((text or {}).get('html', ''))}chars")
    else:
        pages = host.call({"operation": "pages", "chapter": chapter})["pages"]
        out.append(f"pages={len(pages)}")
        if pages and kind.endswith("VIDEO"):
            out.append("stream=" + pages[0]["url"][:70])
        elif pages:
            image = host.call({"operation": "image", "page": pages[0]})["artifact"]
            out.append(f"image={image['byteSize']}B {image['contentType']}")
    return " ".join(out + ["OK"])


def main():
    global PROFILE
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument('--runtime', type=Path, default=PROFILE,
                           help='JVM resource directory, including a built app bundle\'s jvm directory')
    arguments.add_argument('--startup-only', action='store_true',
                           help='Check host startup and source enumeration without browsing online')
    arguments.add_argument('sources', nargs='*')
    options = arguments.parse_args()
    PROFILE = options.runtime.resolve()
    host = Host()
    try:
        sources = host.call({"operation": "sources"})["sources"]
        if options.startup_only:
            print('STARTUP OK', 'sources=' + str(len(sources)), flush=True)
            return
        by_prefix = {}
        for ref in sources:
            prefix = ref["name"].split("_", 1)[0] if ref["name"].split("_", 1)[0] in ("MIHON", "TSUNDOKU", "ANIYOMI") else "JAR"
            by_prefix.setdefault(prefix, []).append(ref)
        print("SOURCES", {k: len(v) for k, v in by_prefix.items()}, flush=True)
        wanted = options.sources or [v[0]["name"] for k, v in by_prefix.items() if k != "JAR"]
        refs = {ref["name"]: ref for ref in sources}
        for name in wanted:
            try:
                print("PROBE", probe(host, name, refs[name]), flush=True)
            except Exception as error:  # report and continue: sites change independently of the host
                print("PROBE", name, "FAIL", error, flush=True)
    finally:
        host.close()
        print("LOG", host.data / "host.log")


if __name__ == "__main__":
    main()
