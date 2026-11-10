#!/usr/bin/env python3
"""Packages the generated demo library as files the iOS app imports.

    package_for_import.py <library> <out>

`<library>` is the output of make_demo_library.py. `<out>` receives one CBZ per manga (chapter folders plus a
ComicInfo.xml carrying the metadata), one EPUB for the novel and a folder of episodes per video.
"""
import html
import json
import pathlib
import shutil
import sys
import zipfile

STATUS = {"ONGOING": "Ongoing", "FINISHED": "Completed"}


def comic_info(title, meta):
    fields = {
        "Series": title,
        "Summary": meta.get("description", ""),
        "Writer": ", ".join(meta.get("authors", [])),
        "Genre": ", ".join(meta.get("tags", [])),
        "LanguageISO": "en",
    }
    body = "".join(f"  <{name}>{html.escape(value)}</{name}>\n" for name, value in fields.items() if value)
    status = STATUS.get(meta.get("state"))
    if status:
        body += f'  <ty:PublishingStatusTachiyomi xmlns:ty="http://www.w3.org/2001/XMLSchema">{status}</ty:PublishingStatusTachiyomi>\n'
    return f'<?xml version="1.0" encoding="utf-8"?>\n<ComicInfo>\n{body}</ComicInfo>\n'


def manga(folder, meta, out):
    with zipfile.ZipFile(out / f"{folder.name}.cbz", "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("ComicInfo.xml", comic_info(folder.name, meta))
        for page in sorted(folder.glob("*/*.png")):
            archive.write(page, f"{page.parent.name}/{page.name}")


def novel(folder, meta, out):
    chapters = sorted(folder.glob("*.txt"))
    title = folder.name
    items, refs, links = [], [], []
    with zipfile.ZipFile(out / f"{title}.epub", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("mimetype", "application/epub+zip", zipfile.ZIP_STORED)
        archive.writestr("META-INF/container.xml",
            '<?xml version="1.0"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
            "</rootfiles></container>\n")
        archive.write(folder / "cover.png", "OEBPS/cover.png")
        for index, chapter in enumerate(chapters, 1):
            # "Chapter 1 - The Lamp That Would Not Go Out.txt"
            name = html.escape(chapter.stem)
            paragraphs = [p.strip() for p in chapter.read_text(encoding="utf-8").split("\n\n") if p.strip()]
            body = "\n".join(f"<p>{html.escape(p)}</p>" for p in paragraphs)
            archive.writestr(f"OEBPS/chapter{index}.xhtml",
                '<?xml version="1.0" encoding="utf-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml"><head>'
                f"<title>{name}</title></head><body>\n<h2>{name}</h2>\n{body}\n</body></html>\n")
            items.append(f'<item id="c{index}" href="chapter{index}.xhtml" media-type="application/xhtml+xml"/>')
            refs.append(f'<itemref idref="c{index}"/>')
            links.append(f'<li><a href="chapter{index}.xhtml">{name}</a></li>')
        archive.writestr("OEBPS/nav.xhtml",
            '<?xml version="1.0" encoding="utf-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml" '
            'xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>'
            f'<nav epub:type="toc"><ol>{"".join(links)}</ol></nav></body></html>\n')
        subjects = "".join(f"<dc:subject>{html.escape(tag)}</dc:subject>" for tag in meta.get("tags", []))
        creators = "".join(f"<dc:creator>{html.escape(author)}</dc:creator>" for author in meta.get("authors", []))
        archive.writestr("OEBPS/content.opf",
            '<?xml version="1.0" encoding="utf-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" '
            'unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
            f'<dc:identifier id="id">kototoro-demo-{html.escape(title)}</dc:identifier>'
            f"<dc:title>{html.escape(title)}</dc:title><dc:language>en</dc:language>{creators}{subjects}"
            f'<dc:description>{html.escape(meta.get("description", ""))}</dc:description></metadata><manifest>'
            '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
            '<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>'
            f'{"".join(items)}</manifest><spine>{"".join(refs)}</spine></package>\n')


def video(folder, out):
    target = out / folder.name
    target.mkdir(exist_ok=True)
    for episode in sorted(folder.glob("*.mp4")):
        shutil.copyfile(episode, target / episode.name)


def main():
    library, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    metadata = json.loads((library / "metadata.json").read_text(encoding="utf-8"))
    for folder in sorted((library / "manga").iterdir()):
        manga(folder, metadata.get(f"manga/{folder.name}", {}), out)
    for folder in sorted((library / "novel").iterdir()):
        novel(folder, metadata.get(f"novel/{folder.name}", {}), out)
    for folder in sorted((library / "video").iterdir()):
        video(folder, out)
    print(f"{len(list(out.iterdir()))} items in {out}")


if __name__ == "__main__":
    main()
