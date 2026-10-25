#!/usr/bin/env python3
"""A minimal WebDAV server for the iOS WebDAV backup UI test: PROPFIND (depth 0/1), PUT, GET and DELETE over one
temporary directory on 127.0.0.1:8766 (the simulator shares the host network). Basic auth user "kototoro" with
password "secret" is required, so the test also proves the credentials are sent.

Usage: serve-webdav-fixture.py [directory]   (GET /__files lists the stored names, for checks from the host)
"""
import base64
import email.utils
import http.server
import os
import sys
import tempfile
import urllib.parse

ROOT = sys.argv[1] if len(sys.argv) > 1 else tempfile.mkdtemp(prefix="webdav-fixture-")
AUTH = "Basic " + base64.b64encode(b"kototoro:secret").decode()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _path(self):
        relative = urllib.parse.unquote(urllib.parse.urlparse(self.path).path).lstrip("/")
        return os.path.normpath(os.path.join(ROOT, relative))

    def _authorized(self):
        if self.headers.get("Authorization") == AUTH:
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="fixture"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def _empty(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if self.path == "/__files":
            names = "\n".join(sorted(f for f in os.listdir(ROOT) if os.path.isfile(os.path.join(ROOT, f))))
            data = names.encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if not self._authorized():
            return
        path = self._path()
        if not os.path.isfile(path):
            return self._empty(404)
        with open(path, "rb") as file:
            data = file.read()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_PUT(self):
        if not self._authorized():
            return
        length = int(self.headers.get("Content-Length", "0"))
        path = self._path()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as file:
            remaining = length
            while remaining > 0:
                chunk = self.rfile.read(min(65536, remaining))
                if not chunk:
                    break
                file.write(chunk)
                remaining -= len(chunk)
        self._empty(201)

    def do_DELETE(self):
        if not self._authorized():
            return
        path = self._path()
        if os.path.isfile(path):
            os.remove(path)
            return self._empty(204)
        self._empty(404)

    def do_MKCOL(self):
        if not self._authorized():
            return
        os.makedirs(self._path(), exist_ok=True)
        self._empty(201)

    def do_PROPFIND(self):
        if not self._authorized():
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length:
            self.rfile.read(length)
        path = self._path()
        os.makedirs(path, exist_ok=True) if not os.path.exists(path) else None
        href = urllib.parse.urlparse(self.path).path
        if not href.endswith("/"):
            href += "/"
        entries = [(href, None)]
        if self.headers.get("Depth", "1") != "0" and os.path.isdir(path):
            for name in sorted(os.listdir(path)):
                full = os.path.join(path, name)
                if os.path.isfile(full):
                    entries.append((href + urllib.parse.quote(name), full))
        parts = ['<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">']
        for link, full in entries:
            if full is None:
                props = "<D:resourcetype><D:collection/></D:resourcetype><D:displayname>fixture</D:displayname>"
            else:
                stat = os.stat(full)
                modified = email.utils.formatdate(stat.st_mtime, usegmt=True)
                props = (f"<D:resourcetype/><D:getcontentlength>{stat.st_size}</D:getcontentlength>"
                         f"<D:getlastmodified>{modified}</D:getlastmodified>")
            parts.append(f"<D:response><D:href>{link}</D:href><D:propstat><D:prop>{props}</D:prop>"
                         "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        parts.append("</D:multistatus>")
        data = "".join(parts).encode()
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    print(ROOT, flush=True)
    http.server.ThreadingHTTPServer(("127.0.0.1", 8766), Handler).serve_forever()
