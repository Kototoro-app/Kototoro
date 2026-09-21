"""Add or remove the one `chapters` row that lets the production reader open an injected local fixture.

Why a row is needed at all
--------------------------
The app never stores chapters for a purely local manga: `LocalContentIndex` persists only `manga`
plus `local_index (manga_id -> path)`, and chapters normally travel to the reader inside the
Intent's `ParcelableContent` when a user taps a chapter on the details screen. A cold launch of
`ReaderActivity` by manga id - the only route `am start` can take - resolves chapters from the
database, finds none, logs "ChaptersMapper: returning empty" and navigates back up.

The chapter id is not invented: it is the key the app's own `index.json` wrote for the directory,
which `LocalMangaParser.createUniFileChapter` computed as `"#<directory uri>".longHashCode()`.
This script re-derives it with the same algorithm and refuses to write unless it matches.

Usage (against a *copy* of the database; the caller pushes it back with the app stopped):
    python scripts/real_entry_db_row.py inject --db <copy> --index-json <pulled index.json>
    python scripts/real_entry_db_row.py remove --db <copy> --dir-name bench_large_paged
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path

LONG_HASH_SEED = 1125899906842597


def long_hash_code(value: str) -> int:
    h = LONG_HASH_SEED
    for char in value:
        h = (31 * h + ord(char)) & 0xFFFFFFFFFFFFFFFF
    return h - (1 << 64) if h >= (1 << 63) else h


def connect(db_path: Path) -> sqlite3.Connection:
    con = sqlite3.connect(str(db_path))
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    return con


def inject(args: argparse.Namespace) -> int:
    index = json.loads(Path(args.index_json).read_text(encoding="utf-8"))
    manga_id = int(index["id"])
    dir_uri = index["url"]
    title = index.get("title") or args.dir_name
    chapters = index.get("chapters") or {}
    if len(chapters) != 1:
        raise SystemExit(f"expected exactly one chapter in the index, found {len(chapters)}")
    chapter_id = int(next(iter(chapters)))

    derived = long_hash_code("#" + dir_uri)
    print(f"index chapter id = {chapter_id}; derived from '#{dir_uri}' = {derived}")
    if derived != chapter_id:
        raise SystemExit("refusing to write: the derived chapter id does not match the index")

    con = connect(Path(args.db))
    cur = con.cursor()
    cur.execute("select manga_id, title, source, url from manga where manga_id = ?", (manga_id,))
    row = cur.fetchone()
    if row is None:
        raise SystemExit(f"manga {manga_id} is not in the database - run the app once so the local index is built")
    print(f"manga row: {row}")
    if row[3] != dir_uri:
        raise SystemExit(f"manga url {row[3]!r} does not match the index url {dir_uri!r}")

    cur.execute("delete from chapters where manga_id = ?", (manga_id,))
    cur.execute(
        """insert into chapters
             (chapter_id, manga_id, name, number, volume, url, scanlator, upload_date, branch, source,
              "index", source_data)
           values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (chapter_id, manga_id, title, 1.0, 0, dir_uri, None, 0, None, "LOCAL", 0, None),
    )
    con.commit()
    cur.execute('select chapter_id, manga_id, name, url, source, "index" from chapters where manga_id = ?', (manga_id,))
    print(f"chapter row: {cur.fetchone()}")
    con.close()
    print(f"ok manga_id={manga_id} chapter_id={chapter_id}")
    return 0


def remove(args: argparse.Namespace) -> int:
    con = connect(Path(args.db))
    cur = con.cursor()
    # Resolve the ids first: the `manga` row has to be deleted last, otherwise the subquery that
    # finds the dependent rows returns nothing and they are silently left behind.
    cur.execute("select manga_id from manga where url like ?", (f"%{args.dir_name}%",))
    manga_ids = [row[0] for row in cur.fetchall()]
    if not manga_ids:
        print("  no manga row references the fixture directory")
        con.close()
        return 0
    print(f"  fixture manga ids: {manga_ids}")

    cur.execute("select name from sqlite_master where type='table'")
    tables = [row[0] for row in cur.fetchall()]
    placeholders = ",".join("?" for _ in manga_ids)
    removed: dict[str, int] = {}
    for table in tables:
        cur.execute(f"pragma table_info({table})")
        columns = {row[1] for row in cur.fetchall()}
        key = "manga_id" if "manga_id" in columns else ("mangaId" if "mangaId" in columns else None)
        if key is None:
            continue
        cur.execute(f'select count(*) from "{table}" where "{key}" in ({placeholders})', manga_ids)
        count = cur.fetchone()[0]
        if not count:
            continue
        cur.execute(f'delete from "{table}" where "{key}" in ({placeholders})', manga_ids)
        removed[table] = count
    con.commit()
    if removed:
        for table, count in sorted(removed.items()):
            print(f"  deleted {count} row(s) from {table}")
    else:
        print("  no dependent rows found")
    con.close()
    print("ok")
    return 0


def verify(args: argparse.Namespace) -> int:
    con = connect(Path(args.db))
    cur = con.cursor()
    cur.execute("select manga_id, title, source, url from manga where url like ?", (f"%{args.dir_name}%",))
    rows = cur.fetchall()
    if not rows:
        print("  no fixture manga row")
    for manga_id, title, source, url in rows:
        cur.execute('select chapter_id, name, url, source, "index" from chapters where manga_id = ?', (manga_id,))
        chapters = cur.fetchall()
        cur.execute("select count(*) from local_index where manga_id = ?", (manga_id,))
        indexed = cur.fetchone()[0]
        print(f"  manga_id={manga_id} title={title!r} source={source} local_index_rows={indexed}")
        print(f"    url={url}")
        for chapter in chapters:
            print(f"    chapter: {chapter}")
        if not chapters:
            print("    chapter: NONE - the reader will resolve no chapters and navigate back up")
    con.close()
    return 0


def count(args: argparse.Namespace) -> int:
    con = connect(Path(args.db))
    cur = con.cursor()
    cur.execute("select count(*) from manga where url like ?", (f"%{args.dir_name}%",))
    print(cur.fetchone()[0])
    con.close()
    return 0


def position(args: argparse.Namespace) -> int:
    con = connect(Path(args.db))
    cur = con.cursor()
    cur.execute("select manga_id from manga where url like ?", (f"%{args.dir_name}%",))
    ids = [row[0] for row in cur.fetchall()]
    if not ids:
        print("  no fixture manga row")
        con.close()
        return 0
    for manga_id in ids:
        cur.execute("select page, scroll, percent, chapter_id from work_history where anchor_manga_id = ?", (manga_id,))
        rows = cur.fetchall()
        if not rows:
            print(f"  manga {manga_id}: no reading position recorded (reader starts at the beginning)")
        for page, scroll, percent, chapter_id in rows:
            print(f"  manga {manga_id}: page={page} scroll={scroll} percent={percent} chapter={chapter_id}")
    con.close()
    return 0


def reset_position(args: argparse.Namespace) -> int:
    # The reader resumes the saved position, so a repeatable run has to start from the same one.
    # Deleting the row is what "no history yet" means to the reader: it opens at the beginning.
    con = connect(Path(args.db))
    cur = con.cursor()
    cur.execute("select manga_id from manga where url like ?", (f"%{args.dir_name}%",))
    ids = [row[0] for row in cur.fetchall()]
    if not ids:
        print("  no fixture manga row")
        con.close()
        return 0
    placeholders = ",".join("?" for _ in ids)
    cur.execute(f"delete from work_history where anchor_manga_id in ({placeholders})", ids)
    print(f"  cleared reading position for {ids} ({cur.rowcount} row(s))")
    con.commit()
    con.close()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    inject_parser = sub.add_parser("inject", help="add the fixture chapter row")
    inject_parser.add_argument("--db", required=True)
    inject_parser.add_argument("--index-json", required=True)
    inject_parser.add_argument("--dir-name", default="bench_large_paged")
    inject_parser.set_defaults(func=inject)

    remove_parser = sub.add_parser("remove", help="remove every row that references the fixture directory")
    remove_parser.add_argument("--db", required=True)
    remove_parser.add_argument("--dir-name", default="bench_large_paged")
    remove_parser.set_defaults(func=remove)

    verify_parser = sub.add_parser("verify", help="print the fixture manga/chapter rows")
    verify_parser.add_argument("--db", required=True)
    verify_parser.add_argument("--dir-name", default="bench_large_paged")
    verify_parser.set_defaults(func=verify)

    count_parser = sub.add_parser("count", help="print how many manga rows reference the fixture directory")
    count_parser.add_argument("--db", required=True)
    count_parser.add_argument("--dir-name", default="bench_large_paged")
    count_parser.set_defaults(func=count)

    position_parser = sub.add_parser("position", help="print the recorded reading position")
    position_parser.add_argument("--db", required=True)
    position_parser.add_argument("--dir-name", default="bench_large_paged")
    position_parser.set_defaults(func=position)

    reset_parser = sub.add_parser("reset-position", help="clear the recorded reading position (reader starts at the beginning)")
    reset_parser.add_argument("--db", required=True)
    reset_parser.add_argument("--dir-name", default="bench_large_paged")
    reset_parser.set_defaults(func=reset_position)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
