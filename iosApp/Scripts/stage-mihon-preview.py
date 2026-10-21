#!/usr/bin/env python3
"""Stage the real-extension simulator profile using the pinned Mobile APIs and the shared converter.

Artifacts come from repositories listed on the Kototoro repo hub, pinned by SHA-256 and converted on this Mac with
:dex-convert (devices receive the converted JARs; on-device conversion is a later step):

  * Mihon     再漫画, Comix (keiyoushi)     -> MIHON_<id>, manga (comix.to sits behind Cloudflare)
  * Tsundoku  AllNovelFull (NovelSourcery)  -> TSUNDOKU_<id>, novels
  * Aniyomi   AnimeGG, Anime1.me (yuzono)   -> ANIYOMI_<id>, video
  * JAR       kototoro-parsers plugin 137   -> parser enum names, served by :parser-host

Everything is written under ignored iosApp/build; nothing enters the default distribution.
--runtime-only stages the same runtime without the samples, which Release IPAs drop anyway; CI uses it so the
build does not depend on third-party extension releases that may be removed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
from importlib.util import module_from_spec, spec_from_file_location

APP = Path(__file__).resolve().parent.parent
ROOT = APP.parent
SOURCE = APP / "build/jvm"
TARGET = APP / "build/jvm-real-api"
WORK = APP / "build/jvm-work"

ARTIFACTS = [
    ("zaimanhua.apk",
     "https://github.com/keiyoushi/extensions/releases/download/24bc6c2/tachiyomi-zh.zaimanhua-v1.6.0.apk",
     "f6605e36a7d1453b60814bb6f7340a8f724183b11ee3f95e02d9542753ba81fe"),
    # Cloudflare-protected site (comix.to): the automatic hidden-browser pass and the manual verification.
    ("tachiyomi-en.comix-v1.6.46.apk",
     "https://github.com/keiyoushi/extensions/releases/download/b5d2cd5/tachiyomi-en.comix-v1.6.46.apk",
     "3166a50b565a5bc92c8ad8e4fb64a916695c2c4201e2d7ac465a333da461e3a0"),
    ("tachiyomi-en.allnovelfull-v1.6.19.apk",
     "https://github.com/NovelSourcery/extensions/releases/download/ba92ea4/tachiyomi-en.allnovelfull-v1.6.19.apk",
     "3f1d1e774069974e85007e09c487f0d9c5cc3928126d47c660222ea7d82c1c8b"),
    ("aniyomi-en.animegg-v14.2.apk",
     "https://raw.githubusercontent.com/yuzono/anime-repo/repo/apk/aniyomi-en.animegg-v14.2.apk",
     "b177df7d0cffec82df81ee56b568393103d0bf28688c370657a8663f8c8c39ad"),
    ("aniyomi-zh.anime1-v14.4.apk",
     "https://raw.githubusercontent.com/yuzono/anime-repo/repo/apk/aniyomi-zh.anime1-v14.4.apk",
     "fa5bda416aa643af5ec98ddbc4273b4d4b54f62f443b6aa8de79cc1cc131b50c"),
    ("kototoro-parsers-plugin-137.jar",
     "https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/apk/plugin.jar",
     "0826c8c91d671fa2a2fc961e274ce2542f2a124c8b2944b690cb3b336b4b0422"),
]
# Same id as the repository's package, so installing it from skepsun/kototoro-parsers replaces the bundled copy
# instead of loading the same ~1,000 parsers twice.
PLUGIN_IDS = {"kototoro-parsers-plugin-137.jar": "org.skepsun.kototoro.parsers"}

# Our Kotlin runtime wins over the Mobile API copies (as before); for every other artifact both provide, the Mobile
# API's copy is the single one on the classpath so OkHttp/okio/jsoup are shared by all ecosystems.
OURS = ("kotlin-stdlib", "kotlinx-coroutines-core-jvm", "kotlinx-serialization-core-jvm",
        "kotlinx-serialization-json-jvm")
MOBILE_CORE = ("000-", "AndroidCompat", "server-", "android-jar")


def base_name(jar):
    """Artifact name without version: okhttp-jvm-5.4.0.jar -> okhttp-jvm."""
    return re.sub(r"-\d[\w.\-]*$", "", jar.stem)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runtime-only", action="store_true", help="Skip the sample extensions")
    artifacts = [] if parser.parse_args().runtime_only else ARTIFACTS
    if not (SOURCE / "libkototoro-jvm.a").is_file():
        raise RuntimeError("Run build-jvm-preview.py first")
    if not (SOURCE / "runtime-origin.json").is_file():
        raise RuntimeError("Rebuild the JVM with Kototoro's own OpenJDK adaptations first")
    spec = spec_from_file_location("jvm_preview", APP / "Scripts/build-jvm-preview.py")
    builder = module_from_spec(spec)
    spec.loader.exec_module(builder)
    compatibility = APP / "build/mihon-ios-api"
    compatibility.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    if TARGET.exists():
        shutil.rmtree(TARGET)
    TARGET.mkdir(parents=True)
    with zipfile.ZipFile(builder.download("runtime.ipa")) as archive:
        for name in archive.namelist():
            if "/tachiaz-compat/" in name and name.endswith(".jar") and not name.rsplit("/", 1)[1].startswith("000-"):
                (compatibility / name.rsplit("/", 1)[1]).write_bytes(archive.read(name))

    sources = TARGET / "sources"
    sources.mkdir()
    pairs = []
    for name, url, expected in artifacts:
        local = WORK / name
        if not local.exists():
            builder.run(["curl", "--fail", "--location", "--retry", "3", "--output", local, url])
        if sha256(local) != expected:
            raise RuntimeError(f"SHA-256 mismatch for {name}")
        pairs += [str(local), str(sources / (Path(name).stem + "-converted.jar"))]

    env = dict(os.environ)
    env["JAVA_HOME"] = os.environ.get("KOTOTORO_JVM_BOOT_JDK") or subprocess.check_output(
        ["/usr/libexec/java_home", "-v", "24"], text=True).strip()
    conversion = [":dex-convert:convertApk", "-PconvertPairs=" + "|".join(pairs)] if pairs else []
    output = subprocess.run(
        [ROOT / "gradlew", ":mihon-ios-compat:stagePreview", *conversion, "--console=plain",
         "-PwithMihonIosCompat", "-PmihonIosCompatibilityDirectory=" + str(compatibility), "--no-daemon"],
        cwd=ROOT, env=env, check=True, text=True, stdout=subprocess.PIPE).stdout
    converted = [json.loads(line.split(" ", 1)[1]) for line in output.splitlines() if line.startswith("CONVERTED ")]
    if len(converted) != len(artifacts):
        raise RuntimeError("Converter did not report every artifact:\n" + output)

    for name in ["java_bundle", "include"]:
        shutil.copytree(SOURCE / name, TARGET / name, dirs_exist_ok=True)
    shutil.copy2(SOURCE / "libkototoro-jvm.a", TARGET / "libkototoro-jvm.a")
    shutil.copy2(SOURCE / "runtime-origin.json", TARGET / "runtime-origin.json")
    lib = TARGET / "lib"
    lib.mkdir()
    for index, name in enumerate(["AndroidCompat-1.0.jar", "server-1.0.jar",
                                  "android-jar-1.0.0.jar"]):
        shutil.copy2(compatibility / name, lib / (str(index + 1).zfill(2) + "-" + name))
    adapter = SOURCE / "ios-adapter"
    shutil.copy2(adapter / "deps/mihon-ios-shims.jar", lib / "00-kototoro-ios-shims.jar")
    shutil.copy2(adapter / "mihon-ios-compat.jar", lib / "05-mihon-ios-compat.jar")
    mobile = {base_name(item): item for item in compatibility.glob("*.jar") if not item.name.startswith(MOBILE_CORE)}
    for item in sorted((adapter / "deps").glob("*.jar")):
        if item.name == "mihon-ios-shims.jar":
            continue
        base = base_name(item)
        if base in mobile and not base.startswith(OURS):
            continue  # the Mobile API provides it
        shutil.copy2(item, lib / ("10-" + item.name))
    for base, item in mobile.items():
        if not base.startswith(OURS):
            shutil.copy2(item, lib / ("20-" + item.name))

    jars, plugins = [], []
    for (name, _, _), info in zip(artifacts, converted):
        relative = Path(info["output"]).name
        if info["kind"] == "apk":
            jars.append({"path": relative, "identity": {
                "packageName": info["packageName"], "versionCode": info["versionCode"], "sha256": info["sha256"]}})
        else:
            plugins.append({"path": relative, "id": PLUGIN_IDS[name], "sha256": info["sha256"]})
    config = {
        "platformClass": "org.skepsun.kototoro.ios.compat.MihonIosPlatform",
        "jars": jars, "plugins": plugins,
        "imageDirectory": "images", "preferenceDirectory": "prefs",
    }
    (sources / "config.json").write_text(json.dumps(config, indent=2))
    (sources / "preview.json").write_text(json.dumps({"label": "真实扩展兼容性验证（Mihon/Tsundoku/Aniyomi/JAR）"},
                                                     ensure_ascii=False))
    # Fail closed if a stale build or dependency reintroduces TachiaZ shim classes.
    for item in lib.glob("*.jar"):
        with zipfile.ZipFile(item) as archive:
            if any(name.startswith("app/tachiaz/") for name in archive.namelist()):
                raise RuntimeError("Third-party TachiaZ shim remains in classpath: " + str(item))
    # Inspectable digest inventory of the generated preview; not a production distribution manifest.
    inventory = {str(item.relative_to(TARGET)): sha256(item)
                 for item in TARGET.rglob("*") if item.is_file() and item.name != "inventory.json"}
    (TARGET / "inventory.json").write_text(json.dumps(inventory, indent=4))
    print("Real extension simulator profile ready:", TARGET)
    for item in converted:
        print("  ", item.get("packageName", item["kind"]), Path(item["output"]).name, item["classes"], "classes")


if __name__ == "__main__":
    main()
