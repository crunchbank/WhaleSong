# Copyright (c) 2026 crunchbank (WhaleSong). Licensed under the GNU General Public License,
# version 3 or (at your option) any later version.
#
# Builds the invented music library used for the store screenshots in fastlane/. Every album,
# artist and cover is made up here; the audio is near-silence. Needs Pillow, cairosvg and ffmpeg
# (on macOS cairosvg needs Homebrew's cairo: DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib).
# The whale covers use the shapes WhaleSong draws its sightings and icon with.
# Usage: python3 art/store/demo-library.py OUTDIR, then copy OUTDIR's folders into Music/ on a
# test device. The screenshots were taken on an emulator at 1080x2400 with the demo status bar.
import math
import os
import random
import subprocess
import sys

import io

from PIL import Image, ImageDraw, ImageFilter

# The humpback side-on (body units, facing +x, ~60 long) and the icon's fluke (icon units), as in
# app/src/main/java/org/oxycblt/auxio/whale/sightings/Humpback.kt.
BODY_D = (
    "M25.2,0.5 Q25.2,-0.9 23.6,-1.4 Q22.9,-1.6 22.2,-1.8 Q21.23,-1.62 20.45,-2.23 "
    "Q19.48,-2.05 18.7,-2.65 Q17.73,-2.47 16.95,-3.08 Q15.98,-2.9 15.2,-3.5 "
    "Q13.6,-4.7 11.6,-4.6 C6,-5.6 -1,-6.4 -6,-6.3 Q-8.6,-6.3 -10,-7.0 "
    "Q-11.2,-8.4 -12.4,-8.3 Q-12.7,-7.4 -13.8,-6.4 Q-14.38,-5.88 -15.14,-5.76 "
    "Q-15.72,-5.24 -16.48,-5.12 Q-17.06,-4.6 -17.82,-4.48 Q-18.4,-3.96 -19.16,-3.84 "
    "Q-19.74,-3.32 -20.5,-3.2 C-22.2,-2.4 -23.6,-1.5 -25,-1.0 C-28.5,-1.3 -32,-0.9 -35.2,0.9 "
    "Q-31.5,1.3 -25,0.9 C-18,2.0 -12,3.6 -6,5.0 C0,6.7 6.5,7.0 10.5,6.0 "
    "C15,4.9 20.5,3.2 23.6,2.2 Q25.6,1.6 25.2,0.5 Z")
FLIPPER_D = (
    "M13.6,2.6 C11,5.6 6.5,9.2 0.8,11.6 Q-0.42,11.58 -1.33,12.4 Q-2.56,12.38 -3.47,13.2 "
    "Q-4.69,13.18 -5.6,14 Q-6.6,14.3 -5.6,13.0 C-2,10.2 2.5,7.6 8.2,4.2 Z")
LINES_D = "M25.1,0.9 Q19,1.4 12.8,2.6 M22.4,2.2 Q11,6.4 -4,5.0 M20.6,3.0 Q10,6.8 -1.5,5.7"
FLUKE_D = (
    "M48.9,84 C49.94,73 51.22,62.5 51.56,56.2 C47.5,53.6 40.31,50.9 35.67,46.6 "
    "C31.73,42.9 29.64,37.6 29.52,30.2 Q31.96,33.6 34.98,35 Q36.54,35.02 37.57,36.03 "
    "Q39.13,36.05 40.16,37.07 Q41.72,37.08 42.75,38.1 Q44.31,38.12 45.34,39.13 "
    "Q46.9,39.15 47.93,40.17 Q49.49,40.18 50.52,41.2 C52.14,41.9 53.3,43 54,44.2 "
    "C54.7,43 55.86,41.9 57.48,41.2 Q58.51,40.18 60.07,40.17 Q61.1,39.15 62.66,39.13 "
    "Q63.69,38.12 65.25,38.1 Q66.28,37.08 67.84,37.07 Q68.87,36.05 70.43,36.03 "
    "Q71.46,35.02 73.02,35 Q76.04,33.6 78.48,30.2 C78.36,37.6 76.27,42.9 72.33,46.6 "
    "C67.69,50.9 60.5,53.6 56.44,56.2 C56.78,62.5 58.06,73 59.1,84 Z")


def humpback(transform, body="#26303c", flipper="#e9eff5", lines="#141a22", rim=None):
    edge = f' stroke="{rim}" stroke-width="0.45" stroke-opacity="0.8"' if rim else ""
    return (f'<g transform="{transform}"><path d="{FLIPPER_D}" fill="{flipper}"/>'
            f'<path d="{BODY_D}" fill="{body}"{edge}/>'
            f'<path d="{LINES_D}" fill="none" stroke="{lines}" stroke-width="0.35" '
            f'stroke-linecap="round"/></g>')


def waves(y0, n, step, colour, opacity, amp=8, width=4, seed=0):
    out = []
    for k in range(n):
        y = y0 + k * step
        pts = " ".join(f"{x},{y + amp * math.sin(x / 55 + k * 1.3 + seed):.1f}"
                       for x in range(-10, S + 20, 20))
        out.append(f'<polyline points="{pts}" fill="none" stroke="{colour}" '
                   f'stroke-opacity="{opacity}" stroke-width="{width}"/>')
    return "".join(out)


def svg_cover(name, seed):
    rnd = random.Random(seed)
    if name == "fluke":
        # The tail rising at dusk, the sea closing round its stock.
        body = (
            '<defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">'
            '<stop offset="0" stop-color="#1b2347"/><stop offset="0.62" stop-color="#7c5a7a"/>'
            '<stop offset="1" stop-color="#f0956f"/></linearGradient>'
            '<linearGradient id="sea" x1="0" y1="0" x2="0" y2="1">'
            '<stop offset="0" stop-color="#2d4f7c"/><stop offset="1" stop-color="#0f2444"/></linearGradient></defs>'
            '<rect width="800" height="800" fill="url(#sky)"/>'
            '<circle cx="610" cy="560" r="70" fill="#f7b38f" fill-opacity="0.85"/>'
            f'<path d="{FLUKE_D}" transform="translate(400,300) scale(9) translate(-54,-44.2)" fill="#f2f6fa"/>'
            '<rect y="590" width="800" height="210" fill="url(#sea)"/>'
            + waves(612, 6, 32, "#ffffff", 0.18, amp=5, width=3, seed=seed)
            + '<ellipse cx="400" cy="592" rx="70" ry="9" fill="#ffffff" fill-opacity="0.7"/>'
            '<ellipse cx="400" cy="598" rx="120" ry="11" fill="none" stroke="#ffffff" stroke-opacity="0.35" stroke-width="3"/>')
    elif name == "spyhop":
        # A humpback raising its head out of the water at night, under the moon.
        stars = "".join(f'<circle cx="{rnd.uniform(0, 800):.0f}" cy="{rnd.uniform(0, 430):.0f}" '
                        f'r="{rnd.choice([1.2, 1.6, 2.2])}" fill="#ffffff" fill-opacity="0.8"/>' for _ in range(70))
        body = (
            '<defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">'
            '<stop offset="0" stop-color="#0b1026"/><stop offset="1" stop-color="#26385c"/></linearGradient>'
            '<clipPath id="above"><rect width="800" height="560"/></clipPath></defs>'
            '<rect width="800" height="800" fill="url(#sky)"/>' + stars +
            '<circle cx="590" cy="170" r="78" fill="#f2ead2"/>'
            '<rect y="555" width="800" height="245" fill="#122440"/>'
            '<path d="M590,560 L520,800 L660,800 Z" fill="#f2ead2" fill-opacity="0.10"/>'
            + waves(578, 7, 32, "#9fb8d8", 0.22, amp=5, width=3, seed=seed) +
            '<g clip-path="url(#above)">'
            + humpback("translate(250,610) rotate(-70) scale(13)", body="#2f3d50", lines="#141c27",
                       rim="#e8e0c6") +
            '</g>'
            '<ellipse cx="330" cy="560" rx="110" ry="13" fill="#dfe9f5" fill-opacity="0.4"/>')
    elif name == "bubblenet":
        # Under water: a humpback rising through the spiral of bubbles it blew to trap krill.
        bubbles = []
        for i in range(150):
            t = i / 150
            ang = t * 5.2 * math.pi
            r = 250 - 40 * t
            x = 400 + r * math.cos(ang)
            y = 760 - 640 * t + 0.32 * r * math.sin(ang)
            rr = rnd.uniform(5, 15)
            bubbles.append(f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{rr:.1f}" fill="#d6f3ff" '
                           f'fill-opacity="0.18" stroke="#e8faff" stroke-opacity="0.75" stroke-width="2"/>')
        krill = "".join(f'<circle cx="{rnd.gauss(400, 70):.0f}" cy="{rnd.gauss(120, 40):.0f}" r="2.2" '
                        f'fill="#ffb59a" fill-opacity="0.9"/>' for _ in range(120))
        rays = "".join(f'<path d="M{x},0 L{x + 60},0 L{x - 120},800 L{x - 220},800 Z" fill="#ffffff" fill-opacity="0.05"/>'
                       for x in (180, 420, 640))
        body = (
            '<defs><linearGradient id="sea" x1="0" y1="0" x2="0" y2="1">'
            '<stop offset="0" stop-color="#3aa6b9"/><stop offset="1" stop-color="#0b2f4a"/></linearGradient></defs>'
            '<rect width="800" height="800" fill="url(#sea)"/>' + rays + krill
            + "".join(bubbles[:75])
            + humpback("translate(400,470) rotate(-90) scale(9.5)", body="#1e2b3a", lines="#0d141d")
            + "".join(bubbles[75:]))
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{S}" height="{S}" viewBox="0 0 800 800">{body}</svg>'
    import cairosvg
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode()))).convert("RGB")

OUT = sys.argv[1]
S = 800

ALBUMS = [
    ("Pelagic", "The Lantern Fish", 2024, "Electronic", "rings", ((12, 36, 74), (40, 140, 190)),
     ["Thermocline", "Bioluminescence", "Abyssal Plain", "Marine Snow", "Upwelling"]),
    ("Harbour Lights", "Mara Lindqvist", 2022, "Folk", "moon", ((25, 32, 66), (233, 120, 90)),
     ["Night Ferry", "Lighthouse Keeper", "Salt on the Window", "The Long Pier"]),
    ("Kelp Forest", "Wren Okafor", 2023, "Ambient", "stripes", ((18, 70, 52), (150, 200, 120)),
     ["Canopy", "Sunflecks", "Holdfast", "Otters at Dusk", "Drift"]),
    ("Tidal Lines", "Cold Harbour", 2021, "Indie Rock", "waves", ((230, 220, 200), (30, 80, 120)),
     ["Spring Tide", "Undertow", "Breakwater", "Low Water Mark"]),
    ("Bubble Net", "The Lunge Feeders", 2020, "Electronic", "svg:bubblenet", None,
     ["Spiral", "Krill Swarm", "Lunge", "Gulp", "Surface Feeding"]),
    ("Sandbar", "Ada Marsh", 2025, "Pop", "sun", ((246, 200, 140), (240, 110, 90)),
     ["Barefoot", "Sunscreen", "Postcards", "Rip Current", "Golden Hour"]),
    ("Deep Blue", "The Pelagic Society", 2019, "Jazz", "bubbles", ((10, 40, 90), (70, 150, 230)),
     ["Blue in Green Water", "Sounding", "Pressure", "Breath Hold"]),
    ("Saltwater Hymns", "Mara Lindqvist", 2019, "Folk", "grid", ((240, 236, 226), (60, 90, 110)),
     ["Fisherman's Psalm", "Storm Warning", "Home Port"]),
    ("Fluke", "Lumen Tide", 2026, "Classical", "svg:fluke", None,
     ["First Movement: Migration", "Second Movement: Breach", "Third Movement: Calving Grounds",
      "Fourth Movement: Return"]),
    ("Spyhop", "Cetacea Ensemble", 2024, "Ambient", "svg:spyhop", None,
     ["Moonrise", "Looking Around", "Fifty-Two Hertz", "Night Watch"]),
]


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(c1, c2, vertical=True):
    img = Image.new("RGB", (S, S))
    d = ImageDraw.Draw(img)
    for i in range(S):
        d.line([(0, i), (S, i)] if vertical else [(i, 0), (i, S)], fill=lerp(c1, c2, i / S))
    return img


def cover(style, c1, c2, seed):
    rnd = random.Random(seed)
    img = gradient(c1, c2)
    d = ImageDraw.Draw(img, "RGBA")
    if style == "rings":
        for r in range(40, 600, 36):
            d.ellipse([S / 2 - r, S * 0.62 - r, S / 2 + r, S * 0.62 + r], outline=(255, 255, 255, 60), width=3)
        for _ in range(60):
            x, y, r = rnd.uniform(0, S), rnd.uniform(0, S), rnd.uniform(2, 6)
            d.ellipse([x - r, y - r, x + r, y + r], fill=(180, 255, 250, 160))
    elif style == "moon":
        d.ellipse([480, 120, 680, 320], fill=(233, 120, 90))
        for i in range(12):
            y = 520 + i * 22
            d.line([(0, y), (S, y)], fill=(233, 120, 90, 90 - i * 6), width=4)
        d.polygon([(110, 520), (130, 300), (150, 520)], fill=(240, 230, 210))
        d.rectangle([124, 260, 136, 300], fill=(255, 220, 140))
    elif style == "stripes":
        for i in range(14):
            x = rnd.uniform(0, S)
            w = rnd.uniform(10, 40)
            pts = [(x + 30 * math.sin(y / 90 + i), y) for y in range(0, S + 20, 20)]
            d.line(pts, fill=(30 + i * 8, 110 + i * 6, 60, 200), width=int(w))
    elif style == "waves":
        for k in range(10):
            pts = [(x, 160 + k * 60 + 22 * math.sin(x / 70 + k * 0.7)) for x in range(0, S + 10, 10)]
            d.line(pts, fill=c2 + (255,), width=8)
    elif style == "sun":
        d.ellipse([250, 170, 550, 470], fill=(255, 236, 170))
        d.rectangle([0, 470, S, S], fill=(60, 150, 170))
        for i in range(8):
            y = 490 + i * 30
            d.line([(220 + i * 8, y), (580 - i * 8, y)], fill=(255, 236, 170, 200 - i * 22), width=6)
        d.rectangle([0, 700, S, S], fill=(240, 214, 160))
    elif style == "bubbles":
        for _ in range(45):
            x, y, r = rnd.uniform(0, S), rnd.uniform(0, S), rnd.uniform(8, 70)
            d.ellipse([x - r, y - r, x + r, y + r], outline=(200, 235, 255, 150), width=4)
    elif style == "grid":
        for i in range(0, S, 80):
            d.line([(i, 0), (i, S)], fill=(60, 90, 110, 60), width=2)
            d.line([(0, i), (S, i)], fill=(60, 90, 110, 60), width=2)
        d.polygon([(200, 540), (600, 540), (540, 620), (260, 620)], fill=(60, 90, 110))
        d.line([(400, 540), (400, 230)], fill=(60, 90, 110), width=8)
        d.polygon([(410, 250), (410, 520), (560, 520)], fill=(200, 110, 80))
    return img.filter(ImageFilter.SMOOTH)


def main():
    os.makedirs(OUT, exist_ok=True)
    for n, (album, artist, year, genre, style, colours, tracks) in enumerate(ALBUMS):
        folder = os.path.join(OUT, f"{artist} - {album}")
        os.makedirs(folder, exist_ok=True)
        art = os.path.join(folder, "cover.jpg")
        if style.startswith("svg:"):
            svg_cover(style[4:], n).save(art, quality=88)
        else:
            cover(style, *colours, n).save(art, quality=88)
        for t, title in enumerate(tracks, 1):
            dur = 150 + (hash((album, title)) % 140)
            path = os.path.join(folder, f"{t:02d} {title}.mp3".replace(":", " -"))  # Android forbids ":"
            subprocess.run(
                ["ffmpeg", "-loglevel", "error", "-y",
                 "-f", "lavfi", "-i", f"anoisesrc=d={dur}:a=0.0005:r=22050",
                 "-i", art, "-map", "0:a", "-map", "1:v",
                 "-ac", "1", "-b:a", "32k", "-c:v", "copy", "-id3v2_version", "3",
                 "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)",
                 "-metadata", f"title={title}", "-metadata", f"artist={artist}",
                 "-metadata", f"album_artist={artist}", "-metadata", f"album={album}",
                 "-metadata", f"date={year}", "-metadata", f"genre={genre}",
                 "-metadata", f"track={t}/{len(tracks)}", path],
                check=True)
        print(folder)


main()
