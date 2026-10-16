#!/usr/bin/env python3
"""Local Apple CMS (MacCMS v10 JSON) fixture for the iOS video XCTest.

Generates two short HLS episodes and a cover with ffmpeg under ignored iosApp/build/cms-fixture, then serves the
CMS API on 127.0.0.1:8765 until interrupted. The simulator shares the host network, so the app imports
http://127.0.0.1:8765/api.php/provide/vod/ like any other site. Nothing is downloaded or written outside build/.
"""
import http.server
import json
import pathlib
import shutil
import subprocess
import sys
import urllib.parse

PORT = 8765
ROOT = pathlib.Path(__file__).resolve().parents[1] / "build" / "cms-fixture"
BASE = f"http://127.0.0.1:{PORT}"


def generate():
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        sys.exit("ffmpeg is required to generate the HLS fixture")
    for episode, tone in (("ep1", 440), ("ep2", 660)):
        target = ROOT / "media" / episode
        if (target / "index.m3u8").exists():
            continue
        target.mkdir(parents=True, exist_ok=True)
        subprocess.run([
            ffmpeg, "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", f"testsrc2=size=640x360:rate=25:duration=12",
            "-f", "lavfi", "-i", f"sine=frequency={tone}:duration=12",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "main", "-c:a", "aac", "-shortest",
            "-hls_time", "4", "-hls_playlist_type", "vod",
            "-hls_segment_filename", str(target / "seg%02d.ts"), str(target / "index.m3u8"),
        ], check=True)
    cover = ROOT / "cover.jpg"
    if not cover.exists():
        subprocess.run([ffmpeg, "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=300x450",
                        "-frames:v", "1", str(cover)], check=True)
    # Distinct site identities over the same deterministic API, for source usage and ordering tests.
    (ROOT / "usage-sites.json").write_text(json.dumps(usage_sites(), ensure_ascii=False), encoding="utf-8")


def usage_sites(tag=""):
    return {"sites": [
        {"key": f"ios_usage_a_{tag}", "name": f"使用排序{tag}甲", "type": 1, "api": f"{BASE}/api.php/provide/vod/"},
        {"key": f"ios_usage_b_{tag}", "name": f"使用排序{tag}乙", "type": 1, "api": f"{BASE}/api.php/provide/vod/"},
    ]}


def vod(vod_id, name, type_id, type_name, with_play):
    item = {"vod_id": vod_id, "vod_name": name, "type_id": type_id, "type_name": type_name,
            "vod_pic": "/cover.jpg", "vod_remarks": "全2集", "vod_content": f"<p>{name} 的<b>简介</b></p>"}
    if with_play:
        item["vod_play_from"] = "高清$$$备用"
        item["vod_play_url"] = (
            f"第1集${BASE}/media/ep1/index.m3u8#第2集${BASE}/media/ep2/index.m3u8"
            f"$$$第1集${BASE}/media/ep1/index.m3u8"
        )
    return item


CATALOG = [(101, "测试动画", 2, "动画"), (102, "测试电影", 1, "电影")]
PAGE_TWO_MODE = "off"


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        global PAGE_TWO_MODE
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/health":
            return self.reply({"ok": True})
        if url.path == "/usage-sites.json":
            tag = urllib.parse.parse_qs(url.query).get("run", [""])[0]
            return self.reply(usage_sites(tag))
        if url.path == "/fail-page-2":
            PAGE_TWO_MODE = "fail"
            return self.reply({"ok": True})
        if url.path == "/recover-page-2":
            PAGE_TWO_MODE = "ok"
            return self.reply({"ok": True})
        if url.path.rstrip("/") in ("/api.php/provide/vod", "/related/api.php/provide/vod"):
            query = urllib.parse.parse_qs(url.query)
            ids = query.get("ids", query.get("id", []))
            words = query.get("wd", [])
            category = query.get("t", [])
            items = [vod(i, n, t, tn, bool(ids)) for i, n, t, tn in CATALOG
                     if (not ids or str(i) in ids) and
                     (not words or url.path.startswith("/related/") or words[0] in n) and
                     (not category or str(t) == category[0])]
            if url.path.startswith("/related/"):
                # A shared series remark also matches the runtime's local search filter.
                for item in items:
                    item["vod_remarks"] = "测试动画系列推荐"
            page = int(query.get("pg", ["1"])[0])
            if page == 2 and PAGE_TWO_MODE == "fail":
                self.send_response(503)
                self.end_headers()
                return
            if page == 2 and PAGE_TWO_MODE == "ok":
                PAGE_TWO_MODE = "off"
                items = [vod(103, "第二页动画", 2, "动画", False)]
            elif page > 1:
                items = []
            return self.reply({"code": 1, "msg": "数据列表", "page": page, "pagecount": 1, "total": len(items),
                               "class": [{"type_id": 1, "type_name": "电影"}, {"type_id": 2, "type_name": "动画"}],
                               "list": items})
        return super().do_GET()

    def reply(self, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    generate()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"Serving Apple CMS fixture on {BASE}/api.php/provide/vod/", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
