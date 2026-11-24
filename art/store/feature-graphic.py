# Copyright (c) 2026 crunchbank (WhaleSong). Licensed under the GNU General Public License,
# version 3 or (at your option) any later version.
#
# WhaleSong's store feature graphic (1024x500): the icon over a night sea, with the name.
# Usage: python3 art/store/feature-graphic.py fastlane/metadata/android/en-US/images/icon.png \
#     app/src/main/res/font fastlane/metadata/android/en-US/images/featureGraphic.png  (needs Pillow)
import math
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

icon_path, font_dir, out = sys.argv[1:4]
W, H, SS = 1024, 500, 2  # drawn at 2x, then downsampled for smooth edges
w, h = W * SS, H * SS

img = Image.new("RGB", (w, h))
d = ImageDraw.Draw(img)
top, bottom = (16, 19, 26), (30, 36, 48)
for y in range(h):
    t = y / h
    d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))

# Faint record grooves centred behind the icon, and stars.
cx, cy = 290 * SS, 230 * SS
for r in range(260 * SS, 700 * SS, 14 * SS):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(40, 45, 56), width=SS)
rnd = random.Random(7)
for _ in range(70):
    x, y = rnd.uniform(0, w), rnd.uniform(0, h * 0.62)
    r = rnd.choice([1, 1, 1.5, 2]) * SS
    d.ellipse([x - r, y - r, x + r, y + r], fill=(235, 240, 250))


def sparkle(x, y, s):
    d.polygon([(x, y - s), (x + s * 0.22, y - s * 0.22), (x + s, y), (x + s * 0.22, y + s * 0.22),
               (x, y + s), (x - s * 0.22, y + s * 0.22), (x - s, y), (x - s * 0.22, y - s * 0.22)],
              fill=(240, 244, 252))


for x, y, s in [(560, 70, 14), (930, 120, 10), (700, 40, 8), (990, 60, 9)]:
    sparkle(x * SS, y * SS, s * SS)

# Waves along the bottom, in the icon's three blues.
for k, (col, base, amp, ph) in enumerate([((86, 160, 238), 420, 12, 0.0),
                                           ((44, 120, 210), 445, 10, 1.3),
                                           ((30, 90, 170), 470, 8, 2.1)]):
    pts = [(x, (base + amp * math.sin(x / (SS * 60) + ph)) * SS) for x in range(0, w + SS * 10, SS * 10)]
    d.polygon(pts + [(w, h), (0, h)], fill=col)

icon = Image.open(icon_path).convert("RGBA").resize((380 * SS, 380 * SS), Image.LANCZOS)
mask = Image.new("L", icon.size, 0)
ImageDraw.Draw(mask).ellipse([3 * SS, 3 * SS, icon.width - 3 * SS, icon.height - 3 * SS], fill=255)
icon.putalpha(mask)
shadow = Image.new("RGBA", icon.size, (0, 0, 0, 0))
shadow.putalpha(icon.getchannel("A").point(lambda a: a * 0.5))
shadow = shadow.filter(ImageFilter.GaussianBlur(12 * SS))
img.paste(shadow, (cx - icon.width // 2, cy - icon.height // 2 + 10 * SS), shadow)
img.paste(icon, (cx - icon.width // 2, cy - icon.height // 2), icon)

title = ImageFont.truetype(f"{font_dir}/inter_semibold.otf", 78 * SS)
sub = ImageFont.truetype(f"{font_dir}/inter_regular.otf", 31 * SS)
d = ImageDraw.Draw(img)
d.text((520 * SS, 175 * SS), "WhaleSong", font=title, fill=(246, 248, 252))
d.text((524 * SS, 280 * SS), "A clean, simple music player", font=sub, fill=(200, 210, 226))
d.text((524 * SS, 324 * SS), "for the music on your phone", font=sub, fill=(200, 210, 226))

img.resize((W, H), Image.LANCZOS).save(out)
