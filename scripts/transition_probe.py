"""Judge device captures without looking at them.

Decodes PNG with zlib + numpy (no PIL on this machine) and reports numbers that
separate the paged scene's transition styles:

  stats <png>...                  size, mean luminance, colour spread
  diff  <a.png> <b.png>           mean/max abs difference and the share of moved pixels
  seam  <png> y0 y1 x0 x1 [rows]  per-row strongest horizontal edge x, plus the fitted
                                  slope: a slide leaves a vertical seam (slope ~ 0) while a
                                  paper fold leaves a tilted one (|slope| >> 0)
  extent <a> <b> [thresh] [y0] [y1]
                                  per-row extent of the change between two frames; the fitted
                                  slope of the outer edge separates a slide (vertical, ~0)
                                  from a fold (tilted)
"""
from __future__ import annotations

import struct
import sys
import zlib
from dataclasses import dataclass

import numpy as np

PNG_SIG = b"\x89PNG\r\n\x1a\n"


def _paeth(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> np.ndarray:
    p = a + b - c
    pa, pb, pc = np.abs(p - a), np.abs(p - b), np.abs(p - c)
    return np.where((pa <= pb) & (pa <= pc), a, np.where(pb <= pc, b, c))


def load(path: str) -> np.ndarray:
    """Decode an 8-bit non-interlaced PNG into an (H, W, 3) uint8 array."""
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:8] != PNG_SIG:
        raise ValueError(f"{path}: not a PNG")
    pos, idat = 8, bytearray()
    width = height = colortype = depth = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            width, height, depth, colortype, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if depth != 8 or interlace != 0:
                raise ValueError(f"{path}: only 8-bit non-interlaced PNG is supported")
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break
        pos += 12 + length
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    out = np.zeros((height, stride), dtype=np.uint8)
    prev = np.zeros(stride, dtype=np.uint8)
    off = 0
    for y in range(height):
        ftype = raw[off]
        line = np.frombuffer(raw, dtype=np.uint8, count=stride, offset=off + 1).copy()
        off += stride + 1
        if ftype == 0:
            cur = line
        elif ftype == 1:
            cur = line
            for x in range(channels, stride):
                cur[x] = (int(cur[x]) + int(cur[x - channels])) & 0xFF
        elif ftype == 2:
            cur = (line.astype(np.int32) + prev.astype(np.int32)).astype(np.uint8)
        elif ftype == 3:
            cur = line
            for x in range(stride):
                left = int(cur[x - channels]) if x >= channels else 0
                cur[x] = (int(cur[x]) + ((left + int(prev[x])) >> 1)) & 0xFF
        elif ftype == 4:
            cur = line
            for x in range(stride):
                a = int(cur[x - channels]) if x >= channels else 0
                b = int(prev[x])
                c = int(prev[x - channels]) if x >= channels else 0
                cur[x] = (int(cur[x]) + int(_paeth(np.uint8(a), np.uint8(b), np.uint8(c)))) & 0xFF
        else:
            raise ValueError(f"{path}: unknown filter {ftype}")
        out[y] = cur
        prev = cur
    img = out.reshape(height, width, channels)
    if channels == 1:
        img = np.repeat(img, 3, axis=2)
    elif channels == 2:
        img = np.repeat(img[:, :, :1], 3, axis=2)
    elif channels == 4:
        img = img[:, :, :3]
    return img


def load_raw(path: str) -> np.ndarray:
    """Decode a raw `adb exec-out screencap` dump (header + RGBA_8888 pixels)."""
    with open(path, "rb") as fh:
        data = fh.read()
    if len(data) < 16:
        raise ValueError(f"{path}: too small to be a screencap dump")
    width, height, _fmt, _cs = struct.unpack("<IIII", data[:16])
    body = data[16:]
    if len(body) != width * height * 4:
        width, height, _fmt = struct.unpack("<III", data[:12])
        body = data[12:]
        if len(body) != width * height * 4:
            raise ValueError(
                f"{path}: unexpected size {len(data)} for {width}x{height} "
                f"(expected {width * height * 4} bytes of pixels)"
            )
    px = np.frombuffer(body, dtype=np.uint8).reshape(height, width, 4)
    return px[:, :, :3]


def load_any(path: str) -> np.ndarray:
    """Decode either a raw screencap dump or an 8-bit PNG."""
    with open(path, "rb") as fh:
        head = fh.read(8)
    return load(path) if head == PNG_SIG else load_raw(path)


def luma(img: np.ndarray) -> np.ndarray:
    return (0.299 * img[:, :, 0] + 0.587 * img[:, :, 1] + 0.114 * img[:, :, 2]).astype(np.float32)


@dataclass
class Seam:
    slope: float
    r2: float
    xs: list
    strength: float


def seam(img: np.ndarray, y0: int, y1: int, x0: int, x1: int, rows: int = 60) -> Seam:
    """Fit the strongest horizontal edge position per row and report its slope."""
    band = luma(img[y0:y1, x0:x1])
    grad = np.abs(np.diff(band, axis=1))
    # Smooth along x so that stripe texture inside a page does not win over the page boundary.
    kernel = np.ones(9, dtype=np.float32) / 9.0
    smoothed = np.apply_along_axis(lambda r: np.convolve(r, kernel, mode="same"), 1, grad)
    ys = np.linspace(0, smoothed.shape[0] - 1, num=min(rows, smoothed.shape[0])).astype(int)
    xs, strengths = [], []
    for y in ys:
        row = smoothed[y]
        idx = int(np.argmax(row))
        xs.append(idx + x0)
        strengths.append(float(row[idx]))
    ys_f = ys.astype(np.float32)
    xs_f = np.array(xs, dtype=np.float32)
    if xs_f.std() < 1e-6 or ys_f.std() < 1e-6:
        return Seam(0.0, 0.0, xs, float(np.mean(strengths)))
    slope, intercept = np.polyfit(ys_f, xs_f, 1)
    pred = slope * ys_f + intercept
    ss_res = float(((xs_f - pred) ** 2).sum())
    ss_tot = float(((xs_f - xs_f.mean()) ** 2).sum())
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else 0.0
    return Seam(float(slope), float(r2), xs, float(np.mean(strengths)))


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    if cmd == "stats":
        for path in sys.argv[2:]:
            img = load_any(path)
            lum = luma(img)
            print(f"{path}: {img.shape[1]}x{img.shape[0]} mean_luma={lum.mean():.1f} "
                  f"std={lum.std():.1f} p5={np.percentile(lum, 5):.1f} p95={np.percentile(lum, 95):.1f}")
        return 0
    if cmd == "diff":
        a, b = load_any(sys.argv[2]).astype(np.int16), load_any(sys.argv[3]).astype(np.int16)
        if a.shape != b.shape:
            print(f"shape mismatch: {a.shape} vs {b.shape}")
            return 1
        d = np.abs(a - b).max(axis=2)
        print(f"mean_abs_diff={d.mean():.2f} max={d.max()} moved_share(>12)={float((d > 12).mean()):.4f} "
              f"moved_share(>40)={float((d > 40).mean()):.4f}")
        return 0
    if cmd == "seam":
        path = sys.argv[2]
        y0, y1, x0, x1 = (int(v) for v in sys.argv[3:7])
        rows = int(sys.argv[7]) if len(sys.argv) > 7 else 60
        result = seam(load_any(path), y0, y1, x0, x1, rows)
        head = result.xs[:3]
        print(f"seam slope={result.slope:+.4f} r2={result.r2:.3f} strength={result.strength:.1f} "
              f"first_xs={[int(v) for v in head]}")
        return 0
    if cmd == "extent":
        # Per-row extent of the change between two frames. A pure slide moves content
        # horizontally, so the changed region's outer edge is the same x on every row; a paper
        # fold clips along a tilted line, so that edge drifts with y.
        a, b = load_any(sys.argv[2]).astype(np.int16), load_any(sys.argv[3]).astype(np.int16)
        thresh = int(sys.argv[4]) if len(sys.argv) > 4 else 40
        y0 = int(sys.argv[5]) if len(sys.argv) > 5 else 300
        y1 = int(sys.argv[6]) if len(sys.argv) > 6 else a.shape[0] - 300
        d = np.abs(a - b).max(axis=2)[y0:y1]
        rows = np.where((d > thresh).any(axis=1))[0]
        if rows.size < 10:
            print(f"changed rows: {rows.size} (too few to fit)")
            return 0
        max_x = np.array([np.where(d[y] > thresh)[0].max() for y in rows], dtype=np.float32)
        min_x = np.array([np.where(d[y] > thresh)[0].min() for y in rows], dtype=np.float32)
        slope, intercept = np.polyfit(rows.astype(np.float32), max_x, 1)
        pred = slope * rows.astype(np.float32) + intercept
        ss_res = float(((max_x - pred) ** 2).sum())
        ss_tot = float(((max_x - max_x.mean()) ** 2).sum())
        r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else 0.0
        # The inner edge is the discriminating one: a slide starts changing content on a fixed
        # vertical boundary, a fold starts on a tilted crease, so its inner edge drifts with y.
        in_slope, in_intercept = np.polyfit(rows.astype(np.float32), min_x, 1)
        in_pred = in_slope * rows.astype(np.float32) + in_intercept
        in_ss_res = float(((min_x - in_pred) ** 2).sum())
        in_ss_tot = float(((min_x - min_x.mean()) ** 2).sum())
        in_r2 = 1.0 - in_ss_res / in_ss_tot if in_ss_tot > 0 else 0.0
        print(f"changed_rows={rows.size} outer_slope={slope:+.4f} outer_r2={r2:.3f} "
              f"outer_x_std={max_x.std():.1f} outer_x_range=[{int(max_x.min())},{int(max_x.max())}]")
        print(f"   inner_slope={in_slope:+.4f} inner_r2={in_r2:.3f} inner_x_std={min_x.std():.1f} "
              f"inner_x_range=[{int(min_x.min())},{int(min_x.max())}]")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
