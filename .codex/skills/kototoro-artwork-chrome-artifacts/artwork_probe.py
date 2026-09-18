"""Pixel probe for Compose visual artifacts — no imaging library required.

Decodes PNG with zlib + numpy (no PIL/scipy on this machine) and reports
background-independent numbers, so a capture can be judged instead of looked at.

    python artwork_probe.py metrics shot.png 110 258 71
    python artwork_probe.py plate   shot.png 16 170 210 350
    python artwork_probe.py bands   shot.png 20 180 200 340 110 52
    python artwork_probe.py scan    shot.png h 258 0 260 4

`metrics` is the artifact detector: the p5-p95 luminance spread inside a chrome
shape. A translucent fill that lets the surface shadow bleed through reads ~17;
a clean one reads 1-2. Decoded frames are cached next to the PNG as .npy because
pure-python unfiltering is slow.
"""
from __future__ import annotations

import os
import struct
import sys
import zlib

import numpy as np

PNG_SIG = b"\x89PNG\r\n\x1a\n"


def _paeth(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> np.ndarray:
    p = a + b - c
    pa, pb, pc = np.abs(p - a), np.abs(p - b), np.abs(p - c)
    return np.where((pa <= pb) & (pa <= pc), a, np.where(pb <= pc, b, c))


def load(path: str) -> np.ndarray:
    """Decode an 8-bit PNG to an (H, W, 4) uint8 array."""
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:8] != PNG_SIG:
        raise ValueError(f"{path}: not a PNG (a UTF-16 '>' redirect corrupts captures)")
    pos, idat = 8, bytearray()
    width = height = colortype = None
    palette = trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, depth, colortype, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if interlace or depth != 8:
                raise ValueError("only non-interlaced 8-bit PNG is supported")
        elif ctype == b"PLTE":
            palette = np.frombuffer(chunk, dtype=np.uint8).reshape(-1, 3)
        elif ctype == b"tRNS":
            trns = np.frombuffer(chunk, dtype=np.uint8)
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break

    raw = zlib.decompress(bytes(idat))
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    stride = width * channels
    arr = np.frombuffer(raw, dtype=np.uint8).reshape(height, stride + 1)
    filters = arr[:, 0]
    lines = arr[:, 1:].reshape(height, width, channels)
    # Every filter predicts from the previous pixel of the same row, so viewing the
    # row as (width, channels) lets the unfiltering vectorise across channels.
    out = np.empty((height, width, channels), dtype=np.uint8)
    prev = np.zeros((width, channels), dtype=np.int16)
    for y in range(height):
        f = int(filters[y])
        cur = lines[y].astype(np.int16)
        if f == 0:
            rec = cur
        elif f == 1:
            rec = np.empty_like(cur)
            rec[0] = cur[0]
            for x in range(1, width):
                rec[x] = (cur[x] + rec[x - 1]) & 0xFF
        elif f == 2:
            rec = (cur + prev) & 0xFF
        elif f == 3:
            rec = np.empty_like(cur)
            rec[0] = (cur[0] + (prev[0] >> 1)) & 0xFF
            for x in range(1, width):
                rec[x] = (cur[x] + ((rec[x - 1] + prev[x]) >> 1)) & 0xFF
        elif f == 4:
            rec = np.empty_like(cur)
            rec[0] = (cur[0] + _paeth(np.int16(0), prev[0], np.int16(0))) & 0xFF
            for x in range(1, width):
                rec[x] = (cur[x] + _paeth(rec[x - 1], prev[x], prev[x - 1])) & 0xFF
        else:
            raise ValueError(f"unknown PNG filter {f}")
        out[y] = rec.astype(np.uint8)
        prev = rec

    if colortype == 6:
        return out
    rgba = np.empty((height, width, 4), dtype=np.uint8)
    if colortype in (0, 4):
        rgba[..., :3] = out[..., 0:1]
    elif colortype == 2:
        rgba[..., :3] = out
    else:
        rgba[..., :3] = palette[out[..., 0]]
    rgba[..., 3] = out[..., 1] if colortype == 4 else (255 if trns is None else trns[out[..., 0]])
    return rgba


def load_cached(path: str) -> np.ndarray:
    npy = path + ".npy"
    if os.path.exists(npy):
        return np.load(npy)
    img = load(path)
    np.save(npy, img)
    return img


def luminance(img: np.ndarray) -> np.ndarray:
    rgb = img[..., :3].astype(np.float32)
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def metrics(path: str, cx: int, cy: int, r: int, inner: float = 0.82) -> None:
    img = load_cached(path)
    rad = r * inner
    ys, xs = np.mgrid[0:img.shape[0], 0:img.shape[1]]
    inside = ((xs - cx) ** 2 + (ys - cy) ** 2) <= rad * rad
    body = luminance(img)[inside]
    body = body[body > 150]  # drop icon strokes, which are dark by design
    p5, p95 = np.percentile(body, 5), np.percentile(body, 95)
    verdict = "ARTIFACT (nested plate + rim)" if p95 - p5 > 8 else "uniform"
    print(
        f"{path}\n  n={body.size} mean={body.mean():.1f} std={body.std():.2f}\n"
        f"  p5={p5:.1f} p95={p95:.1f} spread={p95 - p5:.1f}   -> {verdict}"
    )


def plate(path: str, box, thr: float = 243.0) -> None:
    """Locate the brightest nested region: its bbox sizes the culprit."""
    img = load_cached(path)
    x0, y0, x1, y1 = box
    lum = luminance(img[y0:y1, x0:x1])
    mask = lum > thr
    if not mask.any():
        print(f"{path}: no region above {thr} in {box}")
        return
    ys, xs = np.nonzero(mask)
    rows = []
    for i in range(int(ys.min()), int(ys.max()) + 1, max(1, (int(ys.max()) - int(ys.min())) // 10)):
        r = np.flatnonzero(mask[i])
        if r.size:
            rows.append(f"y={y0 + i}: {x0 + int(r[0])}-{x0 + int(r[-1])} (w={r.size})")
    print(
        f"{path}  (context: 520dpi device = 3.25 px/dp, so 44dp = 143px)\n"
        f"  thr>{thr}: area={int(mask.sum())} "
        f"bbox=x[{x0 + int(xs.min())},{x0 + int(xs.max())}] y[{y0 + int(ys.min())},{y0 + int(ys.max())}] "
        f"size={int(xs.max() - xs.min()) + 1}x{int(ys.max() - ys.min()) + 1}\n"
        "  profile: " + "; ".join(rows)
    )


def bands(path: str, box, cols: int, rows: int) -> None:
    """Coarse symbolic map, enough to read nested shapes as text."""
    img = load_cached(path)
    x0, y0, x1, y1 = box
    lum = luminance(img[y0:y1, x0:x1])
    h, w = lum.shape
    ys = np.linspace(0, h, rows + 1).astype(int)
    xs = np.linspace(0, w, cols + 1).astype(int)

    def ch(v: float) -> str:
        return "X" if v < 120 else "." if v < 210 else "-" if v < 240 else "+" if v < 246 else "#"

    print(f"# bands box={box}  '#'>=246  '+'240-246  '-'210-240  '.'<210  'X'icon")
    for i in range(rows):
        line = "".join(
            ch(float(np.mean(lum[ys[i]:max(ys[i + 1], ys[i] + 1), xs[j]:max(xs[j + 1], xs[j] + 1)])))
            for j in range(cols)
        )
        print(f"{y0 + ys[i]:5d} {line}")


def scan(path: str, axis: str, fixed: int, start: int, end: int, step: int = 4) -> None:
    img = load_cached(path)
    print(f"# scan {axis} {fixed} from {start} to {end} step {step}")
    prev = None
    for v in range(start, end, step):
        p = img[fixed, v, :3] if axis == "h" else img[v, fixed, :3]
        lum = float(luminance(p.reshape(1, 1, 3))[0, 0])
        if prev is None or int(np.abs(p.astype(int) - prev).max()) > 3:
            print(f"  {'x' if axis == 'h' else 'y'}={v:5d} rgb=({p[0]:3d},{p[1]:3d},{p[2]:3d}) lum={lum:6.1f}  <-- change")
        prev = p.astype(int)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        raise SystemExit(2)
    mode, path, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
    if mode == "metrics":
        metrics(path, int(rest[0]), int(rest[1]), int(rest[2]))
    elif mode == "plate":
        plate(path, tuple(int(v) for v in rest[:4]), float(rest[4]) if len(rest) > 4 else 243.0)
    elif mode == "bands":
        bands(path, tuple(int(v) for v in rest[:4]), int(rest[4]), int(rest[5]))
    elif mode == "scan":
        scan(path, rest[0], int(rest[1]), int(rest[2]), int(rest[3]), int(rest[4]) if len(rest) > 4 else 4)
    else:
        print(f"unknown mode {mode}")
        raise SystemExit(2)
