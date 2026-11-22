# Copyright (c) 2026 crunchbank (WhaleSong). Licensed under the GNU General Public License,
# version 3 or (at your option) any later version.
#
# Mockup of the whale-sightings humpback, before it goes into WhaleSightings.kt.
# Draws each pose of the routine over a mock playback slider, current whale beside the proposed
# one, at roughly phone size and enlarged. Units are dp, as in WhaleSightings.
#
#   python3 art/whale-sighting-mockup.py <fluke-source-svg> <out.html>
import math, re, sys

src, out = sys.argv[1], sys.argv[2]
ICON_FLUKE = re.search(r'd="([^"]*)" fill="#f7fbff"', open(src).read()).group(1)

def f(v): return ("%.2f" % v).rstrip("0").rstrip(".")

def bumps(p0, p1, n, h):
    """n small arcs from p0 to p1 bulging up by h: tubercles, knuckles, scalloped edges."""
    s = ""
    for i in range(n):
        a = (p0[0] + (p1[0]-p0[0])*i/n, p0[1] + (p1[1]-p0[1])*i/n)
        b = (p0[0] + (p1[0]-p0[0])*(i+1)/n, p0[1] + (p1[1]-p0[1])*(i+1)/n)
        dx, dy = b[0]-a[0], b[1]-a[1]; L = math.hypot(dx, dy)
        nx, ny = dy/L, -dx/L
        s += "Q%s,%s %s,%s " % (f((a[0]+b[0])/2 + nx*h), f((a[1]+b[1])/2 + ny*h), f(b[0]), f(b[1]))
    return s

# --- The proposed humpback, side on, facing +x, about 57 units nose to fluke tip. -------------
# Snout at +24.5, tail stock at -24. Flat knobbly head, the splashguard ahead of the blowhole,
# a long low back, the small dorsal fin sat on its hump, knuckled tail stock, a fluke turned
# slightly towards us, then the pleated throat bulging under the jaw.
BODY = ("M25.2,0.5 Q25.2,-0.9 23.6,-1.4 Q22.9,-1.6 22.2,-1.8 " + bumps((22.2, -1.8), (15.2, -3.5), 4, 0.4) +
        "Q13.6,-4.7 11.6,-4.6 "                 # splashguard, blowhole just behind it
        "C6,-5.6 -1,-6.4 -6,-6.3 "              # the long back
        "Q-8.6,-6.3 -10,-7.0 "                  # rising onto the hump
        "Q-11.2,-8.4 -12.4,-8.3 "               # small stubby dorsal fin on top of it
        "Q-12.7,-7.4 -13.8,-6.4 " +
        bumps((-13.8, -6.4), (-20.5, -3.2), 5, 0.22) +   # knuckles along the tail stock
        "C-22.2,-2.4 -23.6,-1.5 -25,-1.0 "
        # Fluke seen edge-on: one flat horizontal blade, tip drooping, never a fish's tail.
        "C-28.5,-1.3 -32,-0.9 -35.2,0.9 "
        "Q-31.5,1.3 -25,0.9 "
        "C-18,2.0 -12,3.6 -6,5.0 "                # belly to the navel
        "C0,6.7 6.5,7.0 10.5,6.0 "              # pleated throat
        "C15,4.9 20.5,3.2 23.6,2.2 Q25.6,1.6 25.2,0.5 Z")
# The long white pectoral flipper, a third of the body, scalloped leading edge.
FLIPPER = ("M13.6,2.6 C11,5.6 6.5,9.2 0.8,11.6 " + bumps((0.8, 11.6), (-5.6, 14.0), 3, -0.45) +
           "Q-6.6,14.3 -5.6,13.0 C-2,10.2 2.5,7.6 8.2,4.2 Z")
DETAIL = ('<path d="M25.1,0.9 Q19,1.4 12.8,2.6" />'                        # mouth line
          '<g opacity="0.55"><path d="M22.4,2.2 Q11,6.4 -4,5.0" />'           # throat pleats
          '<path d="M20.6,3.0 Q10,6.8 -1.5,5.7" /><path d="M18.6,3.8 Q9,7.1 1.5,6.3" /></g>')
EYE = (13.6, 1.3)

def humpback(fg, flip, line, knock, sw):
    """The proposed whale in its own units: knock-out outline, body, pleats, flipper, eye."""
    return ('<g stroke-linejoin="round">'
            '<path d="%s" fill="%s" stroke="%s" stroke-width="%s"/>'
            '<path d="%s" fill="%s"/>'
            '<g fill="none" stroke="%s" stroke-width="0.45" stroke-linecap="round" opacity="0.7">%s</g>'
            '<path d="%s" fill="%s" stroke="%s" stroke-width="%s"/>'
            '<circle cx="%s" cy="%s" r="0.5" fill="%s"/></g>' %
            (BODY, knock, knock, sw, BODY, fg, line, DETAIL, FLIPPER, flip, knock, f(sw * 0.25),
             f(EYE[0]), f(EYE[1]), line))

# --- The current whale, lifted from WhaleSightings.kt for comparison. ---------------------------
OLD_BACK = ("M-22,6 Q-21,-1 -13,-3.2 Q-8,-4.2 -6,-5 Q-4.6,-7.6 -2.6,-7.2 Q-3.6,-6.2 -2.4,-5.4 "
            "Q5,-6.2 11,-5 Q17,-3.6 20,-0.5 Q20.5,4 14,6 Z")
OLD_FLUKE = ("M-1.6,4 L-1.2,-6 C-4,-6.6 -8.5,-8 -11.5,-10.8 C-8.2,-11.6 -4.2,-11.2 -0.8,-9.2 "
             "L0,-8.4 L0.8,-9.2 C4.2,-11.2 8.2,-11.6 11.5,-10.8 C8.5,-8 4,-6.6 1.2,-6 L1.6,4 Z")
OLD_BREACH = ("M19,0 Q14,-4.2 6,-4.6 Q-4,-4.8 -12,-2.6 Q-16,-1.4 -18.5,-0.6 L-23,-4 "
              "Q-21.5,-0.4 -21,0 Q-21.5,0.4 -23,4 L-18.5,0.6 Q-10,4.2 2,4.6 Q13,4.2 19,0 Z")
OLD_FLIP = "M7,2.5 Q3,9 -6,12.5 Q-1.5,7 3.5,2.2 Z"

# --- Themes: the wave is the slider's active colour. --------------------------------------------
THEMES = {
    "dark":  dict(bg="#111318", track="#a8c7fa", inactive="#44474e", flip="#e3ecff",
                  line="#0b2a57", thumb="#a8c7fa", text="#c4c6d0"),
    "light": dict(bg="#f8f9ff", track="#2f5ea8", inactive="#d7dbe6", flip="#cfe0ff",
                  line="#f8f9ff", thumb="#2f5ea8", text="#43474e"),
}

W, H, CY = 200, 46, 30          # panel in dp; slider centre line
LEFT, THUMB = 8, 150             # played part ends at the thumb
GAP = 6                         # M3 thumb-to-track gap
WATER = CY - 2                  # the bar is flat: the sea is the top of the track

def swell(px, wx, rise, amp=2.2):
    d = px - wx
    return amp * 0.75 * math.exp(-d*d/700) + rise * 2.2 * math.exp(-d*d/260)

def panel(theme, pose, new, scale_px):
    t = THEMES[theme]
    # Today's code puts the sea on the wave's crest, 3 dp above the flat bar; the proposal on the bar.
    WATER = globals()["WATER"] - (0 if new else 3)
    wx = 92                       # whale position
    rise = 1.0
    calf = pose == "cruise"
    if pose == "fluke": rise = 0.5
    surf = lambda px: WATER - swell(px, wx, rise) - (1.2*math.exp(-((px-(wx-26))**2)/90) if calf else 0)
    uid = "%s%s%s%d" % (theme, pose, "n" if new else "o", scale_px)
    s = ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">' %
         (W*scale_px, H*scale_px, W, H))
    s += '<rect width="%d" height="%d" fill="%s"/>' % (W, H, t["bg"])
    # Above-water clip.
    pts = " ".join("%s,%s" % (f(x), f(surf(x) + 0.5)) for x in [LEFT - 8 + i*0.5 for i in range(int((THUMB-LEFT+8)/0.5)+1)])
    s += '<clipPath id="c%s"><polygon points="%s,0 %s %s,0"/></clipPath>' % (uid, LEFT-8, pts, THUMB)
    # Slider: wavy played part, flat rest, thumb.
    # The bar as it is: flat 4 dp tracks, fully rounded, a 4 x 24 dp thumb with a gap each side.
    s += '<rect x="%s" y="%s" width="%s" height="4" rx="2" fill="%s"/>' % (LEFT, CY-2, THUMB-2-GAP-LEFT, t["track"])
    s += '<rect x="%s" y="%s" width="%s" height="4" rx="2" fill="%s"/>' % (THUMB+2+GAP, CY-2, W-8-(THUMB+2+GAP), t["inactive"])
    s += '<rect x="%s" y="%s" width="4" height="24" rx="2" fill="%s"/>' % (THUMB-2, CY-12, t["thumb"])
    s += '<text x="%s" y="%s" font-size="6" fill="%s" font-family="sans-serif">1:24</text>' % (LEFT, H-2, t["text"])
    s += '<text x="%s" y="%s" font-size="6" fill="%s" font-family="sans-serif" text-anchor="end">3:58</text>' % (W-8, H-2, t["text"])

    whale = ""
    if new:
        knock, sw = t["bg"], 1.6
        g = lambda tr, k=1.0: '<g transform="%s">%s</g>' % (tr, humpback(t["track"], t["flip"], t["line"], knock, sw/k))
        if pose == "blow":     # head and splashguard up first, blowing
            whale = g("translate(%s,%s) rotate(-9) scale(1.25)" % (wx-8, WATER+1.0))
        elif pose == "cruise":  # long low back, hump and fin, with a calf alongside
            whale = g("translate(%s,%s) scale(1.25)" % (wx, WATER+1.6))
            whale += g("translate(%s,%s) scale(0.7)" % (wx-26, WATER+1.4), 0.56)
        elif pose == "arch":   # the hump that names them: back rolls up, nose down
            whale = g("translate(%s,%s) rotate(5) scale(1.25)" % (wx+4, WATER+3.4))
        elif pose == "fluke":  # tail up, underside towards us: the logo's fluke
            whale = ('<g transform="translate(%s,%s) scale(0.5) translate(-54,-84)">'
                     '<path d="%s" fill="%s" stroke="%s" stroke-width="3.6" stroke-linejoin="round"/>'
                     '<path d="%s" fill="%s"/></g>' % (wx, WATER+9, ICON_FLUKE, t["bg"], t["bg"], ICON_FLUKE, t["track"]))
        elif pose == "breach":  # whole whale out, nose up, flipper flung wide
            whale = g("translate(%s,%s) rotate(-32) scale(1.25)" % (wx, WATER-8))
        swell_alpha = 0.4
    else:
        fill = t["track"]
        if pose in ("blow", "cruise", "arch"):
            whale = '<g transform="translate(%s,%s) scale(1.2)"><path d="%s" fill="%s"/></g>' % (wx, WATER+1.2, OLD_BACK, fill)
            if calf:
                whale += '<g transform="translate(%s,%s) scale(0.66)"><path d="%s" fill="%s"/></g>' % (wx-20, WATER+0.7, OLD_BACK, fill)
        elif pose == "fluke":
            whale = '<g transform="translate(%s,%s) scale(1.2)"><path d="%s" fill="%s"/></g>' % (wx, WATER+1.2, OLD_FLUKE, fill)
        elif pose == "breach":
            whale = ('<g transform="translate(%s,%s) rotate(-32) scale(1.2)"><path d="%s" fill="%s"/><path d="%s" fill="%s"/>'
                     '<circle cx="15.5" cy="-2.6" r="0.9" fill="%s"/></g>' % (wx, WATER-7, OLD_BREACH, fill, OLD_FLIP, fill, fill))
        swell_alpha = 1.0
    # Proposed draw order: swell behind (lighter), whale in front with its knock-out outline.
    sw_pts = " ".join("%s,%s" % (f(x), f(CY - (CY - surf(x)) * min(1, (WATER - surf(x) + 0.0001) / 1.5)))
                      for x in [LEFT + i*1.0 for i in range(THUMB-2-GAP-LEFT+1)])
    swell_path = '<polygon points="%s,%s %s %s,%s" fill="%s" opacity="%s"/>' % (LEFT, CY, sw_pts, THUMB-2-GAP, CY, t["track"], swell_alpha)
    whale = '<g clip-path="url(#c%s)">%s</g>' % (uid, whale)
    s += (swell_path + whale) if new else (whale + swell_path)
    if pose == "blow":
        bx = wx + 6 if new else wx + 10
        by = WATER - (6 if new else 3)
        s += '<g fill="%s" opacity="0.8">' % t["track"]
        if new:   # a humpback's blow: one bushy column, wider at the top
            for i, (dx, dy, r) in enumerate([(0, -3, 1.2), (0.6, -6, 1.6), (-0.4, -9, 2.0), (1.2, -11.5, 2.3),
                                              (-1.6, -12.5, 2.0), (0.2, -14, 2.5), (2.6, -13.6, 1.6), (-2.8, -14.6, 1.4)]):
                s += '<circle cx="%s" cy="%s" r="%s" opacity="%s"/>' % (f(bx+dx), f(by+dy), f(r), f(1 - i*0.07))
        else:
            for dx, dy, r in [(0, -4, 1), (-1, -8, 1.4), (1.5, -10, 1.6), (-2.5, -12, 1.2), (3, -13, 1)]:
                s += '<circle cx="%s" cy="%s" r="%s"/>' % (f(bx+dx), f(by+dy), f(r))
        s += '</g>'
    return s + "</svg>"

POSES = [("blow", "Surface and blow", "Head first: flat knobbly snout, the splashguard, one bushy blow."),
         ("cruise", "Cruise, with calf", "Long low back, the small fin sat on its hump. Calf at 55 %."),
         ("arch", "Arch before the dive", "The hump that names them: back rolls up, fin and knuckled tail stock high."),
         ("fluke", "Fluke up", "The logo's fluke, underside towards us: serrated edge, upturned tips."),
         ("breach", "Breach", "The whole animal: pleated throat, white flipper a third of its length.")]

html = ["""<!doctype html><html><head><meta charset="utf-8"><title>Whale Sighting Mockup</title>
<meta name="viewport" content="width=device-width,initial-scale=1"><style>
:root{--bg:#f4f5f8;--fg:#1b1c20;--mute:#5d606a;--card:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#0c0d10;--fg:#e4e5ea;--mute:#9a9ca6;--card:#17181c}}
body{background:var(--bg);color:var(--fg);font:15px/1.45 -apple-system,system-ui,sans-serif;margin:0;padding:24px 16px}
main{max-width:1100px;margin:auto}h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 6px}
p{color:var(--mute);margin:4px 0 12px;max-width:760px}
.row{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,500px),1fr));gap:12px}
.card{background:var(--card);border-radius:12px;padding:12px;overflow:hidden}
.card b{display:block;font-size:13px;margin-bottom:6px;color:var(--mute);font-weight:600}
svg{display:block;max-width:100%;height:auto;border-radius:6px}
.small{display:flex;gap:12px;flex-wrap:wrap}.small svg{max-width:none}
</style></head><body><main>
<h1>Whale sightings: a proper humpback</h1>
<p>Left of each pair is today's whale, right is the proposal. One side-on humpback is drawn once and
every pose is that same animal posed and dipped under the water, so the bit you see is always real
anatomy. It gets a thin outline in the background colour so it no longer melts into the swell, and the
swell sits behind it at lower opacity. The progress bar is drawn as it really is: flat, with the whale in
the played part, left of the thumb. The flipper is lighter, as a humpback's is.</p>"""]

html.append('<h2>Roughly phone size</h2><p>About how big it is on the playback screen (1 dp per px). Dark theme.</p><div class="small">')
for pose, title, _ in POSES:
    html.append('<div class="card"><b>%s</b>%s<div style="height:6px"></div>%s</div>' % (title, panel("dark", pose, False, 1), panel("dark", pose, True, 1)))
html.append("</div>")

for theme in ("dark", "light"):
    html.append('<h2>Enlarged, %s theme</h2>' % theme)
    for pose, title, note in POSES:
        html.append('<p><b style="color:var(--fg)">%s.</b> %s</p><div class="row"><div class="card"><b>Current</b>%s</div>'
                    '<div class="card"><b>Proposed</b>%s</div></div>' %
                    (title, note, panel(theme, pose, False, 5), panel(theme, pose, True, 5)))
html.append("</main></body></html>")
open(out, "w").write("\n".join(html))
