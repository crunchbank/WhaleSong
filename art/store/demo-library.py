# Copyright (c) 2026 crunchbank (WhaleSong). Licensed under the GNU General Public License,
# version 3 or (at your option) any later version.
#
# Builds the invented music library used for the store screenshots in fastlane/. Every album,
# artist and cover is made up here; the audio is near-silence. Needs Pillow and ffmpeg.
# Usage: python3 art/store/demo-library.py OUTDIR, then copy OUTDIR's folders into Music/ on a
# test device. The screenshots were taken on an emulator at 1080x2400 with the demo status bar.
import math
import os
import random
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter

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
    ("Northern Lights", "Aurora Field Recordings", 2020, "Ambient", "aurora", ((8, 14, 30), (90, 230, 170)),
     ["Magnetosphere", "Solar Wind", "Corona", "Green Curtain"]),
    ("Sandbar", "Ada Marsh", 2025, "Pop", "sun", ((246, 200, 140), (240, 110, 90)),
     ["Barefoot", "Sunscreen", "Postcards", "Rip Current", "Golden Hour"]),
    ("Deep Blue", "The Pelagic Society", 2019, "Jazz", "bubbles", ((10, 40, 90), (70, 150, 230)),
     ["Blue in Green Water", "Sounding", "Pressure", "Breath Hold"]),
    ("Saltwater Hymns", "Mara Lindqvist", 2019, "Folk", "grid", ((240, 236, 226), (60, 90, 110)),
     ["Fisherman's Psalm", "Storm Warning", "Home Port"]),
    ("Song of the Humpback", "Lumen Tide", 2026, "Classical", "fluke", ((20, 50, 100), (120, 190, 230)),
     ["First Movement: Migration", "Second Movement: Breach", "Third Movement: Calving Grounds",
      "Fourth Movement: Return"]),
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
    elif style == "aurora":
        aur = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        ad = ImageDraw.Draw(aur)
        for i in range(70):
            x = i * 12
            top = 180 + 90 * math.sin(i / 9)
            ad.line([(x, top), (x, top + 260)], fill=(90, 230, 170, 120), width=14)
        img.paste(aur.filter(ImageFilter.GaussianBlur(18)), (0, 0), aur.filter(ImageFilter.GaussianBlur(18)))
        d = ImageDraw.Draw(img, "RGBA")
        for _ in range(120):
            x, y = rnd.uniform(0, S), rnd.uniform(0, S * 0.6)
            d.point((x, y), fill=(255, 255, 255, 220))
        d.polygon([(0, S), (0, 640), (200, 560), (380, 650), (560, 580), (S, 660), (S, S)], fill=(4, 8, 16))
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
    elif style == "fluke":
        for k in range(6):
            pts = [(x, 600 + k * 34 + 10 * math.sin(x / 50 + k)) for x in range(0, S + 10, 10)]
            d.line(pts, fill=(255, 255, 255, 80), width=5)
        d.polygon([(390, 600), (410, 600), (420, 440), (380, 440)], fill=(240, 246, 250))
        d.polygon([(400, 460), (170, 330), (230, 300), (400, 420), (570, 300), (630, 330)], fill=(240, 246, 250))
    return img.filter(ImageFilter.SMOOTH)


def main():
    os.makedirs(OUT, exist_ok=True)
    for n, (album, artist, year, genre, style, (c1, c2), tracks) in enumerate(ALBUMS):
        folder = os.path.join(OUT, f"{artist} - {album}")
        os.makedirs(folder, exist_ok=True)
        art = os.path.join(folder, "cover.jpg")
        cover(style, c1, c2, n).save(art, quality=88)
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
