# Copyright (c) 2026 crunchbank (WhaleSong). Licensed under the GNU General Public License,
# version 3 or (at your option) any later version.
#
# Generates the WhaleSong icon: a humpback fluke rising above blue waves, a vinyl record behind it.
# Usage: python3 art/whalesong-icon.py  (from the repository root). Writes the launcher, monochrome,
# splash and notification vectors into app/src/main/res/drawable, plus art/*.svg previews.
# The legacy PNGs are rendered from art/whalesong-icon-legacy.svg (on macOS: qlmanage -t -s <px>).
import math, sys, os
OUT = sys.argv[1] if len(sys.argv) > 1 else "."
RES = sys.argv[2] if len(sys.argv) > 2 else OUT
CX, CY, R = 54.0, 51.0, 30.0
BG = "#d7e9fa"

def f(v): return ("%.2f" % v).rstrip("0").rstrip(".")

def circle(cx, cy, r):
    return "M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z" % (f(cx-r), f(cy), f(r), f(r), f(2*r), f(r), f(r), f(-2*r))

def ring(cx, cy, r, w):  # annulus as an even-odd-free path: outer CW + inner CCW
    ro, ri = r + w/2, r - w/2
    return ("M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z " % (f(cx-ro), f(cy), f(ro), f(ro), f(2*ro), f(ro), f(ro), f(-2*ro)) +
            "M%s,%s a%s,%s 0 1,1 %s,0 a%s,%s 0 1,1 %s,0 Z" % (f(cx-ri), f(cy), f(ri), f(ri), f(2*ri), f(ri), f(ri), f(-2*ri)))

def wedge(a1, a2, r):
    p = lambda a: (CX + r*math.cos(math.radians(a)), CY + r*math.sin(math.radians(a)))
    (x1, y1), (x2, y2) = p(a1), p(a2)
    return "M%s,%s L%s,%s A%s,%s 0 0,1 %s,%s Z" % (f(CX), f(CY), f(x1), f(y1), f(r), f(r), f(x2), f(y2))

# Left half of the fluke, from the stock base (under the sea) round to the notch.
# Each segment: ('C', c1, c2, end) or ('Q', c, end)
start = (49.6, 82.0)
def _serrate(p0, p1, n, bulge):
    segs = []
    for i in range(n):
        t0, t1 = i / n, (i + 1) / n
        x0, y0 = p0[0] + (p1[0]-p0[0])*t0, p0[1] + (p1[1]-p0[1])*t0
        x1, y1 = p0[0] + (p1[0]-p0[0])*t1, p0[1] + (p1[1]-p0[1])*t1
        dx, dy = x1 - x0, y1 - y0
        L = (dx*dx + dy*dy) ** 0.5
        nx, ny = dy / L, -dx / L          # left-hand normal = outward (up) for this edge
        segs.append(('Q', ((x0+x1)/2 + nx*bulge, (y0+y1)/2 + ny*bulge), (x1, y1)))
    return segs
left = [
    ('C', (50.5, 71.0), (51.6, 60.5), (51.9, 54.2)),   # tall stock, tapering upward
    ('C', (48.4, 51.6), (42.2, 48.9), (38.2, 44.6)),   # leading edge sweeping out
    ('C', (34.8, 40.9), (33.0, 35.6), (32.9, 28.2)),   # outer edge rising to the tip
    ('Q', (35.0, 31.6), (37.6, 33.0)),                 # trailing edge leaves the tip
] + _serrate((37.6, 33.0), (51.0, 39.2), 6, 0.55) + [
    ('C', (52.4, 39.9), (53.4, 41.0), (54.0, 42.2)),   # into the notch
]
K, PX, PY = 1.0, 54.0, 64.0
def sc(p): return (54.0 + (p[0]-54.0)*1.16, p[1] + 2.0)
start = sc(start)
left = [(seg[0],) + tuple(sc(p) for p in seg[1:]) for seg in left]
def mx(p): return (108 - p[0], p[1])
def fluke_path(start=start):
    pts0 = start
    d = "M%s,%s " % (f(start[0]), f(start[1]))
    for s in left:
        d += s[0] + " ".join("%s,%s" % (f(x), f(y)) for x, y in s[1:]) + " "
    # mirror: walk the left half backwards on the right side
    pts = [pts0] + [s[-1] for s in left]
    for i in range(len(left) - 1, -1, -1):
        s = left[i]
        prev = pts[i]
        if s[0] == 'C':
            d += "C%s,%s %s,%s %s,%s " % (f(mx(s[2])[0]), f(s[2][1]), f(mx(s[1])[0]), f(s[1][1]), f(mx(prev)[0]), f(prev[1]))
        else:
            d += "Q%s,%s %s,%s " % (f(mx(s[1])[0]), f(s[1][1]), f(mx(prev)[0]), f(prev[1]))
    return d + "Z"

def wave(y, amp, period, phase, bottom=112):
    d = "M%s,%s " % (f(-4 + phase), f(y))
    x = -4 + phase
    up = True
    while x < 112:
        h = period / 2
        d += "q%s,%s %s,0 " % (f(h/2), f(-amp if up else amp), f(h))
        x += h; up = not up
    d += "L%s,%s L%s,%s Z" % (f(x), f(bottom), f(-4 + phase), f(bottom))
    return d

FLUKE = fluke_path()
def wave_band(y, amp, period, thick, phase=-2):
    h = period / 2
    xs = []; x = -4 + phase
    while x < 112: xs.append(x); x += h
    xs.append(x)
    d = "M%s,%s " % (f(xs[0]), f(y))
    up = True
    for i in range(len(xs) - 1):
        d += "Q%s,%s %s,%s " % (f(xs[i] + h/2), f(y - amp if up else y + amp), f(xs[i+1]), f(y)); up = not up
    d += "L%s,%s " % (f(xs[-1]), f(y + thick))
    for i in range(len(xs) - 1, 0, -1):
        up = not up
        d += "Q%s,%s %s,%s " % (f(xs[i] - h/2), f(y + thick - amp if up else y + thick + amp), f(xs[i-1]), f(y + thick))
    return d + "Z"
MONO = [ring(CX, CY, R - 1.5, 3.0), fluke_path(sc((50.6, 70.5))), wave_band(70.0, 2.6, 18, 4.2), wave_band(78.0, 2.2, 22, 3.6, -9)]
layers_fg = [
    # (path, fill, alpha)
    (circle(CX, CY, R), "#14161b", 1),
    (wedge(200, 250, R - 1.2), "#ffffff", 0.06),
    (wedge(20, 70, R - 1.2), "#ffffff", 0.06),
] + [(ring(CX, CY, r, 0.55), "#2c313a", 1) for r in (27.5, 25.0, 22.5, 20.0, 17.5, 15.0)] + [
    (circle(CX, CY, 10.5), "#f0795a", 1),
    (circle(CX, CY, 7.0), "#f59a7f", 1),
    (FLUKE, "#0f3d6e", 1),                         # outline (drawn fat underneath)
]
# shrink-free outline trick: draw the fluke twice; the dark copy is stroked in SVG/vector
fg_after = [
    (wave(70.0, 2.6, 18, -2), "#5aa7ef", 1),
    (wave(74.5, 2.4, 22, -9), "#2a7fd4", 1),
    (wave(80.0, 2.0, 26, -3), "#1a5fae", 1),
]

def svg_paths(items):
    out = []
    for d, c, a in items:
        out.append('<path d="%s" fill="%s"%s/>' % (d, c, '' if a == 1 else ' fill-opacity="%s"' % a))
    return "\n".join(out)

FLUKE_SVG = ('<path d="%s" fill="#0f3d6e" stroke="#0f3d6e" stroke-width="2.4" stroke-linejoin="round"/>\n'
             '<path d="%s" fill="#f7fbff"/>') % (FLUKE, FLUKE)

def body():
    return svg_paths(layers_fg[:-1]) + "\n" + FLUKE_SVG + "\n" + svg_paths(fg_after)

def write_svg(name, size, vb, clip):
    s = '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="%s">\n' % (size, size, vb)
    if clip:
        s += '<defs><clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath></defs><g clip-path="url(#c)">\n'
    s += '<rect x="-10" y="-10" width="128" height="128" fill="%s"/>\n' % BG + body() + ("\n</g>" if clip else "") + "\n</svg>\n"
    open(os.path.join(OUT, name), "w").write(s)

write_svg("whalesong-icon.svg", 432, "0 0 108 108", False)
write_svg("whalesong-icon-legacy.svg", 512, "18 18 72 72", True)

# Android vectors
HDR = '''<?xml version="1.0" encoding="utf-8"?>
<!--
    Copyright (c) 2026 crunchbank (WhaleSong). Licensed under the GNU General Public License,
    version 3 or (at your option) any later version.
    WhaleSong icon: a humpback fluke rising above blue waves, a vinyl record behind it.
    Generated by art/whalesong-icon.py; edit that, not this file.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="%(w)sdp"
    android:height="%(w)sdp"
    android:viewportWidth="108"
    android:viewportHeight="108">
'''
def vpath(d, c, a=1, stroke=None, sw=0):
    s = '    <path\n        android:fillColor="%s"\n' % c
    if a != 1: s += '        android:fillAlpha="%s"\n' % a
    if stroke: s += '        android:strokeColor="%s"\n        android:strokeWidth="%s"\n        android:strokeLineJoin="round"\n' % (stroke, sw)
    return s + '        android:pathData="%s" />\n' % d
def vector_fg(w, group=None):
    s = HDR % {"w": w}
    ind = ""
    if group: s += '  <group %s>\n' % group
    for d, c, a in layers_fg[:-1]: s += vpath(d, c, a)
    s += vpath(FLUKE, "#0f3d6e", 1, "#0f3d6e", 2.4)
    s += vpath(FLUKE, "#f7fbff")
    for d, c, a in fg_after: s += vpath(d, c, a)
    if group: s += '  </group>\n'
    return s + "</vector>\n"
open(os.path.join(RES, "ic_launcher_foreground.xml"), "w").write(vector_fg(108))
bg = (HDR % {"w": 108}) + vpath("M0,0h108v108H0z", BG) + "</vector>\n"
open(os.path.join(RES, "ic_launcher_background.xml"), "w").write(bg)
mono = (HDR % {"w": 108}) + "".join(vpath(d, "#000000") for d in MONO) + "</vector>\n"
open(os.path.join(RES, "ic_launcher_monochrome.xml"), "w").write(mono)
# monochrome preview svg
open(os.path.join(OUT, "whalesong-icon-monochrome.svg"), "w").write('<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 108 108"><rect width="108" height="108" fill="#ddd"/>'
    + "".join('<path d="%s" fill="#333"/>' % d for d in MONO) + '</svg>')

# Splash (Android 12+): same 108 geometry as the adaptive icon; the system masks it to a circle.
splash = vector_fg(108).replace("<vector", "<vector", 1)
splash = splash.replace('android:viewportHeight="108">\n', 'android:viewportHeight="108">\n' + vpath("M0,0h108v108H0z", BG), 1)
open(os.path.join(RES, "ic_splash_whale.xml"), "w").write(splash)
# Notification / widget glyph: fluke and one wave, tinted white by the system.
small = (HDR % {"w": 24}).replace('android:viewportWidth="108"\n    android:viewportHeight="108">',
    'android:tint="@android:color/white"\n    android:viewportWidth="76"\n    android:viewportHeight="76">')
small += '  <group android:translateX="-16" android:translateY="-15.5">\n'
small += vpath(fluke_path(sc((50.6, 70.5))), "@android:color/white") + vpath(wave_band(70.0, 2.6, 18, 4.6), "@android:color/white")
small += "  </group>\n</vector>\n"
open(os.path.join(RES, "ic_auxio_24.xml"), "w").write(small)
