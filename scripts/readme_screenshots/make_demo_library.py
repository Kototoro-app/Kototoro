#!/usr/bin/env python3
"""Generate the mock local library used for the README screenshots.

Everything here is original and procedurally drawn: the titles, covers, manga pages, novel text and
video clips are made up for the screenshots, so no third-party source or copyrighted artwork ever
appears in the repository's store listing.

Output layout (push each top-level folder into the app's external files dir of the same name):

    <out>/manga/<Title>/Chapter NN/NNN.png   one folder per chapter; chapter 1 opens on the colour cover
    <out>/novel/<Title>/Chapter N - <Name>.txt   one TXT per chapter, cover.png beside them
    <out>/video/<Title>/Episode NN.mp4       720p clip with a soft subtitle track, cover.png beside it

The local library takes the first image it finds as a title's cover. A manga title root must hold
only chapter folders (a loose image there becomes a bogus chapter), so the cover is page 1 of
chapter 1; novel and video folders only treat their own media as chapters, so cover.png sits beside
them.

Requirements: Pillow, and ffmpeg on PATH or the `imageio-ffmpeg` package.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

# ---------------------------------------------------------------------------------------------
# Fonts

FONT_CANDIDATES = {
    "display": ["bahnschrift.ttf", "impact.ttf", "DejaVuSans-Bold.ttf", "Arial Bold.ttf"],
    "serif": ["georgiab.ttf", "cambriab.ttf", "DejaVuSerif-Bold.ttf", "Georgia Bold.ttf"],
    "sans": ["segoeui.ttf", "arial.ttf", "DejaVuSans.ttf", "Arial.ttf"],
    "sans_bold": ["segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf", "Arial Bold.ttf"],
    "comic": ["comicbd.ttf", "comic.ttf", "DejaVuSans-Bold.ttf", "Arial Bold.ttf"],
}
FONT_DIRS = [
    Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts",
    Path("/usr/share/fonts"),
    Path("/Library/Fonts"),
    Path("/System/Library/Fonts/Supplemental"),
]
_font_cache: dict[tuple[str, int], ImageFont.FreeTypeFont] = {}


def font(kind: str, size: int) -> ImageFont.FreeTypeFont:
    key = (kind, size)
    if key in _font_cache:
        return _font_cache[key]
    for name in FONT_CANDIDATES[kind]:
        for base in FONT_DIRS:
            hits = [base / name] if (base / name).is_file() else list(base.rglob(name)) if base.is_dir() and base.name == "fonts" else []
            for hit in hits:
                try:
                    _font_cache[key] = ImageFont.truetype(str(hit), size)
                    return _font_cache[key]
                except OSError:
                    continue
    _font_cache[key] = ImageFont.load_default(size)
    return _font_cache[key]


# ---------------------------------------------------------------------------------------------
# Catalogue


@dataclass
class Title:
    name: str
    author: str
    palette: tuple[str, str, str]  # sky top, sky bottom, accent
    motif: str
    seed: int
    lines: list[str] = field(default_factory=list)
    description: str = ""
    tags: tuple[str, ...] = ()
    state: str = "ONGOING"
    rating: float = 0.8


MANGA = [
    Title(
        "Sky Archive", "Mira Okonkwo", ("#1d2b64", "#f8a978", "#ffe29a"), "peaks", 11,
        ["The archive only opens at dawn.", "Then we climb before the sun does!", "Hold on to the map!",
         "Is that... a library in the clouds?", "Every book here fell from the sky.", "WHOOSH"],
        "A cartographer's apprentice chases a library that drifts above the mountains and only opens at dawn, "
        "where every book is something that once fell from the sky.",
        ("Adventure", "Fantasy", "Mystery"), "ONGOING", 0.86,
    ),
    Title(
        "Glass Garden", "Ren Halvorsen", ("#0f3443", "#34e89e", "#fbe8a6"), "garden", 23,
        ["Don't touch the glass roses.", "They remember every hand.", "Then why are they singing?",
         "Because you're finally listening.", "Water them with moonlight only.", "RING"],
        "In a greenhouse of singing glass roses, a new gardener learns that the flowers remember every hand "
        "that has ever touched them.",
        ("Slice of Life", "Fantasy", "Drama"), "ONGOING", 0.82,
    ),
    Title(
        "Neon Monks", "Asha Varga", ("#12002b", "#c33764", "#00f5d4"), "city", 37,
        ["The temple moved downtown.", "Meditation, but make it overtime.", "Did the sign just blink at me?",
         "It blinks at everyone. Breathe.", "Floor 88. The quiet floor.", "BZZT"],
        "An ancient temple relocates to floor 88 of a downtown tower, and its monks discover that meditation "
        "in the city comes with overtime.",
        ("Comedy", "Urban Fantasy", "Seinen"), "ONGOING", 0.78,
    ),
    Title(
        "Hollow Star", "Teo Lindqvist", ("#000428", "#004e92", "#f9d423"), "planet", 41,
        ["Signal from the hollow star again.", "It's counting down.", "Counting down to what?",
         "To us answering.", "Engines ready. Hearts... mostly ready.", "VRRM"],
        "A patched-up freighter crew answers a countdown broadcast from a star that should have gone dark "
        "centuries ago.",
        ("Sci-Fi", "Space", "Action"), "ONGOING", 0.84,
    ),
    Title(
        "Tidebreaker", "Noa Castellanos", ("#0b486b", "#3b8d99", "#f5f7fa"), "waves", 53,
        ["The sea is holding its breath.", "Then we sail before it exhales!", "Brace the mast!",
         "That wave has eyes!", "Captain, it's smiling.", "SPLASH"],
        "A young captain sails ahead of a sea that holds its breath, racing a wave that seems to smile.",
        ("Adventure", "Action", "Fantasy"), "FINISHED", 0.88,
    ),
    Title(
        "Paper Moon Express", "Iris Takeda", ("#2c3e50", "#fd746c", "#fff1c1"), "moon", 67,
        ["Last train to the paper moon.", "Tickets are folded from wishes.", "I only have a receipt.",
         "Close enough. Hop on!", "Next stop: tomorrow.", "CHOO"],
        "The last train to the paper moon takes tickets folded from wishes, and one passenger boards with only "
        "a receipt.",
        ("Fantasy", "Romance", "Slice of Life"), "ONGOING", 0.80,
    ),
    Title(
        "Cloud Kitchen", "Priya Sandoval", ("#355c7d", "#f67280", "#ffe0ac"), "peaks", 101,
        ["Order up! One cumulus souffle.", "It's floating off the plate!", "That means it's done.",
         "Can clouds be overcooked?", "Only if you stir them twice.", "SIZZLE"],
        "A cook on a mountaintop inn bakes with weather: souffles of cumulus, soups of fog, and a storm "
        "that must never be served cold.",
        ("Cooking", "Comedy", "Fantasy"), "ONGOING", 0.83,
    ),
    Title(
        "Moth & Meteor", "Juno Adeyemi", ("#0f0c29", "#302b63", "#ff9a8b"), "planet", 113,
        ["A meteor landed in the moth garden.", "It's glowing. Should we name it?", "Names make things stay.",
         "Then let's call it Tomorrow.", "The moths already love it.", "FWOOM"],
        "A shy moth keeper and a talkative fallen meteor spend one summer deciding whether it should go home.",
        ("Romance", "Sci-Fi", "Slice of Life"), "FINISHED", 0.87,
    ),
    Title(
        "Harbor of Bells", "Lukas Brandt", ("#1a2980", "#26d0ce", "#fdfc47"), "waves", 127,
        ["Every ship here carries a bell.", "Mine never rings.", "Then it's waiting for its tune.",
         "What if I never find it?", "The harbor will hum it for you.", "DONG"],
        "In a port where every ship rings its own bell, a silent one arrives with no crew and a sealed letter.",
        ("Mystery", "Drama", "Adventure"), "ONGOING", 0.79,
    ),
    Title(
        "Static Bloom", "Kira Moreau", ("#200122", "#6f0000", "#f5af19"), "city", 131,
        ["The radio towers are flowering.", "Petals made of static?", "Listen. They're broadcasting.",
         "It sounds like my grandmother.", "Then turn the volume up.", "KRZZT"],
        "When a city's radio towers start to bloom, each flower plays a voice nobody has heard in decades.",
        ("Urban Fantasy", "Drama", "Mystery"), "ONGOING", 0.81,
    ),
    Title(
        "Northbound Owl", "Signe Aalto", ("#16222a", "#3a6073", "#e8f1f5"), "moon", 149,
        ["The owl only flies north.", "Then we follow it north.", "Past the last station?",
         "Past the last map.", "Pack extra socks.", "HOOT"],
        "Two siblings follow a mail-carrying owl on a winter rail line that runs off the edge of every map.",
        ("Adventure", "Family", "Fantasy"), "ONGOING", 0.85,
    ),
    Title(
        "Lantern Festival Nine", "Mei Castellan", ("#2b1055", "#d53369", "#ffd26f"), "garden", 163,
        ["Nine nights, nine lanterns.", "And nine wishes?", "Only if you light them in order.",
         "I lit number seven first...", "Oh. Oh no.", "POP"],
        "A festival of nine lanterns goes gently wrong when the youngest lamplighter lights them out of order.",
        ("Comedy", "Slice of Life", "Fantasy"), "FINISHED", 0.82,
    ),
]

NOVEL = Title(
    "The Lantern Keeper of Harrow Bay", "Elodie Marsh", ("#232526", "#b06ab3", "#ffd89b"), "lighthouse", 79, [],
    "A lighthouse keeper, a lamp that refuses to go out, and a visitor made of fog who comes to breakfast with "
    "a warning: the bay is about to ask for its light back.",
    ("Fantasy", "Mystery", "Cozy"), "ONGOING", 0.90,
)

VIDEO = Title(
    "Orbit Cafe", "Studio Parhelion", ("#141e30", "#ef629f", "#ffe3a3"), "planet", 97, [],
    "A tiny cafe on a space station serves moon lattes between sunrises, forty minutes apart. Short episodes "
    "about regulars, comets and very small orbits.",
    ("Slice of Life", "Sci-Fi", "Comedy"), "ONGOING", 0.85,
)

NOVEL_CHAPTERS = [
    ("The Lamp That Would Not Go Out", [
        "Harrow Bay kept one lighthouse and one keeper, and for eleven winters the keeper had been Wren Alder. She knew the stairs by the sound they made under wet boots: the ninth step groaned, the thirty-second sighed, and the last one, just before the lamp room, said nothing at all, as if it were listening.",
        "On the night the storm came in sideways, the lamp refused to dim. Wren had turned the wick down, closed the vents and drawn the brass shutters, yet the light went on sweeping the water in its slow, patient circle. It did not flicker. It did not smoke. It simply shone, bright enough to silver the rain.",
        "She climbed down to fetch the logbook, because that was what keepers did when something strange happened: they wrote it down, plainly, so that the next keeper would know they had not imagined it. Her pencil hovered over the page for a long time before she wrote, in small careful letters, 'The lamp is awake.'",
        "Below the cliff, the sea answered with a long, low note, like a ship's horn heard through fog. Wren counted the seconds. Seven. Then the note came again, and again, always seven seconds apart, exactly matching the turn of the light.",
        "She should have been afraid. Instead she felt the way she had as a girl, waiting on the quay for her father's boat: a tight, bright hope that something far away was finally coming home.",
    ]),
    ("A Visitor Made of Fog", [
        "The visitor arrived at breakfast. Wren had just set the kettle on the stove when the fog outside the kitchen window thickened, folded, and stepped through the glass as easily as a cat through a curtain.",
        "It had the shape of a man in an oilskin coat, though the coat dripped mist instead of water and the face beneath the hood was only a suggestion, like a drawing someone had begun and then set aside. It sat down at the table and waited, politely, for tea.",
        "'You have been keeping my light,' it said. Its voice was the sound of a buoy bell far out in the channel. 'Eleven winters. That is a long time to keep something that is not yours.'",
        "Wren poured two cups, because her mother had taught her that manners were for everyone, even for weather. 'The light belongs to the bay,' she said. 'I only look after it.'",
        "The visitor considered this. Steam rose from its cup and joined its sleeve. 'Then you will want to know,' it said at last, 'that the bay is about to ask for it back.'",
    ]),
    ("The Map Beneath the Floorboards", [
        "Behind the loose board under the logbook shelf, Wren found a map she had never seen, drawn in the same careful hand as the oldest entries in the book. It showed Harrow Bay as it must have looked two hundred years ago, before the harbour wall, before the town had crept down the hill to meet the water.",
        "At the centre of the bay, where today there was only grey water and the occasional seal, the map showed an island shaped like a closed eye. Beside it, in faded ink, someone had written a single instruction: 'When the lamp wakes, row out and knock.'",
        "She laughed, once, because the idea was absurd. Then she looked out of the window at the steady, sweeping beam, and at the fog visitor waiting on the jetty beside her little blue rowing boat, and she stopped laughing.",
        "The oars were where she had left them. The tide was turning. Somewhere out in the channel the seven-second note sounded again, softer now, almost encouraging.",
        "Wren buttoned her coat, tucked the map inside it, and went down the stairs, past the step that said nothing at all, to find out who was knocking on the other side.",
    ]),
]

VIDEO_SUBTITLES = [
    ["Welcome to Orbit Cafe.", "We're open until the next sunrise.", "Which, up here, is in forty minutes."],
    ["Two moon lattes, extra stardust.", "Careful, it's still orbiting.", "Did my cup just go around the table?"],
    ["The comet's early today.", "Quick, the tip jar!", "See you next revolution."],
]


# ---------------------------------------------------------------------------------------------
# Drawing primitives


def hex_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(size: tuple[int, int], top: str, bottom: str) -> Image.Image:
    w, h = size
    img = Image.new("RGB", size)
    draw = ImageDraw.Draw(img)
    a, b = hex_rgb(top), hex_rgb(bottom)
    for y in range(h):
        draw.line([(0, y), (w, y)], fill=lerp(a, b, y / max(1, h - 1)))
    return img


def ridge(rng: random.Random, w: int, base: float, amp: float, steps: int = 14) -> list[tuple[float, float]]:
    pts = [(0, base)]
    for i in range(1, steps + 1):
        pts.append((w * i / steps, base - rng.uniform(0.2, 1.0) * amp))
    return pts


def scene(size: tuple[int, int], t: Title, variant: int = 0) -> Image.Image:
    """A colour illustration for the title's motif; `variant` reframes it for successive panels."""
    rng = random.Random(t.seed * 100 + variant)
    w, h = size
    img = gradient(size, t.palette[0], t.palette[1])
    draw = ImageDraw.Draw(img, "RGBA")
    accent = hex_rgb(t.palette[2])
    dark = lerp(hex_rgb(t.palette[0]), (0, 0, 0), 0.55)
    # Stars for the night-ish palettes.
    for _ in range(int(w * h / 9000)):
        x, y, r = rng.uniform(0, w), rng.uniform(0, h * 0.6), rng.uniform(0.6, 2.2)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, rng.randint(60, 200)))
    # Variant 0 is the cover, whose title block covers the top third: keep the sky body below it.
    cx, cy = w * rng.uniform(0.25, 0.75), h * (rng.uniform(0.4, 0.5) if variant == 0 else rng.uniform(0.18, 0.4))
    if t.motif in ("peaks", "moon", "lighthouse", "waves"):
        r = min(w, h) * rng.uniform(0.11, 0.18)
        for k in range(6, 0, -1):
            draw.ellipse([cx - r - k * 10, cy - r - k * 10, cx + r + k * 10, cy + r + k * 10], fill=accent + (18,))
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=accent + (255,))
    if t.motif == "planet":
        r = min(w, h) * 0.2
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=accent + (255,))
        draw.ellipse([cx - r * 0.8, cy - r * 0.9, cx + r * 0.3, cy - r * 0.1], fill=(255, 255, 255, 60))
        draw.ellipse([cx - r * 1.9, cy - r * 0.35, cx + r * 1.9, cy + r * 0.35], outline=(255, 255, 255, 190), width=max(3, int(r * 0.07)))
    if t.motif == "peaks":
        for layer in range(4):
            col = lerp(hex_rgb(t.palette[1]), dark, 0.35 + layer * 0.2)
            pts = ridge(rng, w, h * (0.62 + layer * 0.1), h * (0.28 - layer * 0.04))
            draw.polygon(pts + [(w, h), (0, h)], fill=col)
        for _ in range(3):
            x, y = rng.uniform(0.1, 0.9) * w, rng.uniform(0.15, 0.45) * h
            draw.rounded_rectangle([x, y, x + w * 0.16, y + h * 0.05], radius=int(h * 0.025), fill=(255, 255, 255, 170))
    elif t.motif == "garden":
        draw.rectangle([0, h * 0.7, w, h], fill=dark)
        for _ in range(26):
            x, y = rng.uniform(0, w), rng.uniform(h * 0.45, h * 0.95)
            stem = h * rng.uniform(0.08, 0.18)
            draw.line([(x, y), (x, y + stem)], fill=(40, 120, 90, 255), width=4)
            r = rng.uniform(0.02, 0.05) * w
            petal = lerp(accent, (255, 255, 255), rng.random() * 0.4)
            for a in range(6):
                ang = a * math.pi / 3
                px, py = x + math.cos(ang) * r * 0.8, y + math.sin(ang) * r * 0.8
                draw.ellipse([px - r * 0.6, py - r * 0.6, px + r * 0.6, py + r * 0.6], fill=petal + (200,))
            draw.ellipse([x - r * 0.35, y - r * 0.35, x + r * 0.35, y + r * 0.35], fill=(255, 255, 255, 230))
        # Greenhouse arches.
        for k in range(3):
            draw.arc([w * (0.05 + k * 0.3), h * 0.25, w * (0.35 + k * 0.3), h * 0.85], 180, 360, fill=(255, 255, 255, 120), width=5)
    elif t.motif == "city":
        x = 0
        while x < w:
            bw, bh = rng.uniform(0.07, 0.16) * w, rng.uniform(0.25, 0.7) * h
            draw.rectangle([x, h - bh, x + bw, h], fill=dark)
            for wy in range(int(h - bh + 12), h - 10, 22):
                for wx in range(int(x + 8), int(x + bw - 8), 16):
                    if rng.random() < 0.35:
                        draw.rectangle([wx, wy, wx + 7, wy + 10], fill=accent + (rng.randint(120, 255),))
            x += bw + rng.uniform(2, 10)
        for _ in range(3):
            sx, sy = rng.uniform(0.1, 0.7) * w, rng.uniform(0.3, 0.6) * h
            draw.rounded_rectangle([sx, sy, sx + w * 0.22, sy + h * 0.06], radius=10, outline=accent + (255,), width=4)
    elif t.motif == "planet":
        draw.polygon(ridge(rng, w, h * 0.86, h * 0.1) + [(w, h), (0, h)], fill=dark)
    elif t.motif == "waves":
        for layer in range(6):
            base = h * (0.55 + layer * 0.08)
            col = lerp(hex_rgb(t.palette[1]), dark, 0.2 + layer * 0.13)
            pts = [(x, base + math.sin(x / w * math.pi * (3 + layer) + layer) * h * 0.025) for x in range(0, w + 8, 8)]
            draw.polygon(pts + [(w, h), (0, h)], fill=col)
            draw.line(pts, fill=(255, 255, 255, 110), width=3)
        mx = w * 0.3
        draw.polygon([(mx, h * 0.58), (mx + w * 0.3, h * 0.58), (mx + w * 0.24, h * 0.64), (mx + w * 0.05, h * 0.64)], fill=(30, 20, 20, 255))
        draw.line([(mx + w * 0.15, h * 0.58), (mx + w * 0.15, h * 0.36)], fill=(30, 20, 20, 255), width=6)
        draw.polygon([(mx + w * 0.15, h * 0.37), (mx + w * 0.27, h * 0.52), (mx + w * 0.15, h * 0.52)], fill=(245, 240, 230, 255))
    elif t.motif == "moon":
        pts = ridge(rng, w, h * 0.8, h * 0.12)
        draw.polygon(pts + [(w, h), (0, h)], fill=dark)
        ty = h * 0.72
        draw.line([(0, ty + 26), (w, ty + 26)], fill=(20, 20, 30, 255), width=6)
        for k in range(4):
            x0 = w * 0.1 + k * w * 0.19
            draw.rounded_rectangle([x0, ty - 34, x0 + w * 0.17, ty + 20], radius=8, fill=(20, 20, 30, 255))
            for j in range(3):
                draw.rectangle([x0 + 10 + j * w * 0.05, ty - 24, x0 + 10 + j * w * 0.05 + w * 0.03, ty - 8], fill=accent + (255,))
    elif t.motif == "lighthouse":
        draw.polygon(ridge(rng, w, h * 0.78, h * 0.08) + [(w, h), (0, h)], fill=dark)
        lx = w * 0.62
        draw.polygon([(lx - w * 0.05, h * 0.78), (lx + w * 0.05, h * 0.78), (lx + w * 0.03, h * 0.42), (lx - w * 0.03, h * 0.42)], fill=(235, 230, 220, 255))
        for k in range(3):
            y = h * (0.5 + k * 0.09)
            draw.rectangle([lx - w * 0.045, y, lx + w * 0.045, y + h * 0.03], fill=(170, 50, 60, 255))
        draw.polygon([(lx, h * 0.4), (0, h * 0.25), (0, h * 0.4)], fill=accent + (70,))
        draw.ellipse([lx - w * 0.03, h * 0.38, lx + w * 0.03, h * 0.42], fill=accent + (255,))
    return img


def to_screentone(img: Image.Image) -> Image.Image:
    """Grayscale manga look: posterised values plus a dot screen on the mid tones."""
    g = img.convert("L")
    g = g.point(lambda v: 255 if v > 200 else 150 if v > 120 else 80 if v > 60 else 20)
    w, h = g.size
    dots = Image.new("L", g.size, 255)
    dd = ImageDraw.Draw(dots)
    for y in range(0, h, 7):
        for x in range((y // 7 % 2) * 3, w, 7):
            dd.ellipse([x, y, x + 3, y + 3], fill=0)
    mid = g.point(lambda v: 255 if v == 150 else 0)
    toned = Image.composite(dots, g, mid)
    return ImageChops.darker(toned, g.point(lambda v: 255 if v >= 150 else v)).convert("RGB")


def wrap(draw: ImageDraw.ImageDraw, text: str, fnt, width: int) -> list[str]:
    words, lines, line = text.split(), [], ""
    for word in words:
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=fnt) <= width:
            line = trial
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def bubble(img: Image.Image, box: tuple[int, int, int, int], text: str, rng: random.Random) -> None:
    draw = ImageDraw.Draw(img)
    x0, y0, x1, y1 = box
    pw = x1 - x0
    bw = min(pw - 28, max(280, int(pw * rng.uniform(0.42, 0.55))))
    fnt = font("comic", max(28, min(44, bw // 9)))
    lines = wrap(draw, text, fnt, int(bw * 0.78))
    lh = fnt.size + 6
    bh = lh * len(lines) + 40
    bx = rng.randint(x0 + 14, max(x0 + 15, x1 - bw - 14))
    by = rng.randint(y0 + 14, max(y0 + 15, y0 + (y1 - y0) // 3))
    tail_x = bx + bw * rng.uniform(0.3, 0.7)
    draw.polygon([(tail_x - 16, by + bh - 6), (tail_x + 16, by + bh - 6), (tail_x + rng.choice([-30, 30]), by + bh + 36)],
                 fill="white", outline="black")
    draw.ellipse([bx, by, bx + bw, by + bh], fill="white", outline="black", width=4)
    for i, line in enumerate(lines):
        tw = draw.textlength(line, font=fnt)
        draw.text((bx + (bw - tw) / 2, by + 20 + i * lh), line, font=fnt, fill="black")


def sfx(img: Image.Image, box, text: str, rng: random.Random) -> None:
    x0, y0, x1, y1 = box
    size = int(min((y1 - y0) * 0.28, (x1 - x0) * 1.5 / max(1, len(text))))
    fnt = font("display", size)
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    tw = d.textlength(text, font=fnt)
    x, y = x0 + (x1 - x0 - tw) / 2, y0 + (y1 - y0 - size) / 2
    d.text((x, y), text, font=fnt, fill="white", stroke_width=8, stroke_fill="black")
    layer = layer.rotate(rng.uniform(-10, 10), center=(x + tw / 2, y + size / 2))
    img.paste(layer, (0, 0), layer)


# ---------------------------------------------------------------------------------------------
# Covers and pages


def cover(t: Title, size=(720, 1080), kind: str = "MANGA") -> Image.Image:
    """Title block at the top: the app overlays its own badges on the bottom corners of a cover."""
    w, h = size
    k = w / 720  # every size below is laid out for a 720 px wide cover
    img = scene(size, t, variant=0)
    draw = ImageDraw.Draw(img, "RGBA")
    fnt = font("display", int(92 * k))
    lines = wrap(draw, t.name.upper(), fnt, int(w * 0.86))[:3]
    author = font("sans", int(34 * k))
    band = 40 * k + fnt.size * 1.02 * len(lines) + author.size + 44 * k
    top = Image.new("RGBA", (w, int(band)), (0, 0, 0, 0))
    td = ImageDraw.Draw(top)
    for y in range(int(band)):  # fade the band out towards the artwork
        td.line([(0, y), (w, y)], fill=(0, 0, 0, int(150 * (1 - (y / band) ** 2))))
    img.paste(top, (0, 0), top)
    y = 40 * k
    for line in lines:
        draw.text((w * 0.07, y), line, font=fnt, fill="white", stroke_width=int(3 * k), stroke_fill=(0, 0, 0, 180))
        y += fnt.size * 1.02
    draw.text((w * 0.07, y + 6 * k), t.author, font=author, fill=(255, 255, 255, 230))
    tag = font("sans_bold", int(26 * k))
    tw = draw.textlength(kind, font=tag)
    x1, y1 = w - 40 * k, h - 40 * k
    draw.rounded_rectangle([x1 - tw - 36 * k, y1 - 48 * k, x1, y1], radius=int(24 * k), fill=hex_rgb(t.palette[2]) + (235,))
    draw.text((x1 - tw - 18 * k, y1 - 40 * k), kind, font=tag, fill=(20, 20, 30))
    return img


PAGE_LAYOUTS = [
    [(0, 0, 1, 0.42), (0, 0.42, 0.55, 0.72), (0.55, 0.42, 1, 0.72), (0, 0.72, 1, 1)],
    [(0, 0, 0.6, 0.5), (0.6, 0, 1, 0.5), (0, 0.5, 1, 1)],
    [(0, 0, 1, 0.3), (0, 0.3, 1, 0.62), (0, 0.62, 0.45, 1), (0.45, 0.62, 1, 1)],
]


def manga_page(t: Title, chapter: int, page: int, size=(1080, 1560)) -> Image.Image:
    rng = random.Random(t.seed * 10_000 + chapter * 100 + page)
    w, h = size
    img = Image.new("RGB", size, "white")
    margin, gutter = 46, 18
    layout = PAGE_LAYOUTS[(chapter + page) % len(PAGE_LAYOUTS)]
    lines = t.lines
    for i, (fx0, fy0, fx1, fy1) in enumerate(layout):
        box = (int(margin + fx0 * (w - 2 * margin) + (gutter if fx0 > 0 else 0)),
               int(margin + fy0 * (h - 2 * margin) + (gutter if fy0 > 0 else 0)),
               int(margin + fx1 * (w - 2 * margin)),
               int(margin + fy1 * (h - 2 * margin)))
        pw, ph = box[2] - box[0], box[3] - box[1]
        art = to_screentone(scene((pw, ph), t, variant=chapter * 50 + page * 7 + i))
        img.paste(art, box[:2])
        ImageDraw.Draw(img).rectangle(box, outline="black", width=6)
        if i == len(layout) - 1 and rng.random() < 0.5:
            sfx(img, box, lines[-1], rng)
        else:
            bubble(img, box, lines[(page + i) % (len(lines) - 1)], rng)
    return img


def title_page(t: Title, chapter: int, size=(1080, 1560)) -> Image.Image:
    img = scene(size, t, variant=chapter * 50)
    draw = ImageDraw.Draw(img, "RGBA")
    w, h = size
    draw.rectangle([0, h * 0.08, w, h * 0.3], fill=(0, 0, 0, 110))
    draw.text((70, h * 0.1), f"CHAPTER {chapter}", font=font("sans_bold", 44), fill=hex_rgb(t.palette[2]))
    draw.text((70, h * 0.16), t.name, font=font("display", 96), fill="white", stroke_width=3, stroke_fill="black")
    return img


# ---------------------------------------------------------------------------------------------
# Novel


def write_novel(root: Path) -> None:
    """One TXT file per chapter, paragraphs separated by blank lines: the folder layout the local
    import guide documents for novels."""
    for i, (heading, paragraphs) in enumerate(NOVEL_CHAPTERS, start=1):
        (root / f"Chapter {i} - {heading}.txt").write_text("\n\n".join(paragraphs) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------------------------
# Video


def ffmpeg_exe() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:
        import imageio_ffmpeg  # type: ignore
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        sys.exit("ffmpeg not found: install ffmpeg or `pip install imageio-ffmpeg`")


def srt_time(seconds: float) -> str:
    ms = int(round(seconds * 1000))
    return f"{ms // 3_600_000:02d}:{ms // 60_000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"


def write_episode(path: Path, t: Title, episode: int, seconds: int = 24) -> None:
    ff = ffmpeg_exe()
    with tempfile.TemporaryDirectory() as tmp:
        still = Path(tmp) / "still.png"
        scene((2560, 1440), t, variant=episode * 13).save(still)
        srt = Path(tmp) / "subs.srt"
        cues = VIDEO_SUBTITLES[(episode - 1) % len(VIDEO_SUBTITLES)]
        span = seconds / len(cues)
        srt.write_text("".join(
            f"{i + 1}\n{srt_time(i * span + 0.3)} --> {srt_time((i + 1) * span - 0.3)}\n{cue}\n\n" for i, cue in enumerate(cues)),
            encoding="utf-8")
        frames = seconds * 30
        zoom = f"zoompan=z='min(1.0+on/{frames}*0.25,1.25)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d={frames}:s=1280x720:fps=30"
        subprocess.run([
            ff, "-y", "-loglevel", "error", "-loop", "1", "-i", str(still), "-i", str(srt),
            "-f", "lavfi", "-i", f"sine=frequency={220 + episode * 55}:sample_rate=44100:duration={seconds}",
            "-filter_complex", f"[0:v]{zoom},format=yuv420p[v];[2:a]volume=0.05[a]",
            "-map", "[v]", "-map", "[a]", "-map", "1:s", "-t", str(seconds),
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-c:a", "aac", "-b:a", "64k",
            "-c:s", "mov_text", "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=English",
            str(path),
        ], check=True)


# ---------------------------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out", type=Path, help="output directory (recreated)")
    parser.add_argument("--chapters", type=int, default=3)
    parser.add_argument("--pages", type=int, default=8)
    parser.add_argument("--episodes", type=int, default=3)
    args = parser.parse_args()

    if args.out.exists():
        shutil.rmtree(args.out)
    for t in MANGA:
        root = args.out / "manga" / t.name
        root.mkdir(parents=True)
        for c in range(1, args.chapters + 1):
            chapter_dir = root / f"Chapter {c:02d}"
            chapter_dir.mkdir()
            (cover(t, size=(1080, 1560)) if c == 1 else title_page(t, c)).save(chapter_dir / "001.png")
            for p in range(2, args.pages + 1):
                manga_page(t, c, p).save(chapter_dir / f"{p:03d}.png")
        print("manga", t.name)
    novel_root = args.out / "novel" / NOVEL.name
    novel_root.mkdir(parents=True)
    cover(NOVEL, kind="NOVEL").save(novel_root / "cover.png")
    write_novel(novel_root)
    print("novel", NOVEL.name)
    video_root = args.out / "video" / VIDEO.name
    video_root.mkdir(parents=True)
    cover(VIDEO, kind="ANIME").save(video_root / "cover.png")
    for e in range(1, args.episodes + 1):
        write_episode(video_root / f"Episode {e:02d}.mp4", VIDEO, e)
    print("video", VIDEO.name)
    # Display metadata for apply_metadata.py, merged into the index.json files the app generates.
    catalogue = [("manga", t) for t in MANGA] + [("novel", NOVEL), ("video", VIDEO)]
    (args.out / "metadata.json").write_text(json.dumps({
        f"{kind}/{t.name}": {
            "authors": [t.author], "description": t.description, "tags": list(t.tags),
            "state": t.state, "rating": t.rating,
        } for kind, t in catalogue
    }, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
