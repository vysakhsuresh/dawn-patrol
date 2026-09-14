"""
Layered scene compositor.

VALUE SYSTEM (the thing that makes a 1-bit frame read):
    sky     - clean paper, at most a faint stipple
    ridge   - a light dithered mass, sits behind everything
    earth   - SOLID ink, one unbroken silhouette
    art     - solid ink shapes rising out of the earth into clean sky
    smoke / flak / searchlight - the ONLY mid-tones on screen, so they pop
At night the same structure is inverted: the earth is the paper (black),
and features are drawn as pale outlines.
"""
import math
import artdata as A
import world as W
from render import FB

CRUST = 1


# ---------------------------------------------------------------------
#  SKY
# ---------------------------------------------------------------------
def sky_dawn(fb, sun_x, sun_y):
    # cold band at the top fading to clean paper at the glowing horizon
    for y in range(A.HUD_H, A.HORIZON):
        t = (y - A.HUD_H) / float(A.HORIZON - A.HUD_H)
        lvl = int(round(11.0 * max(0.0, 1.0 - t) ** 1.7))
        if lvl > 0:
            for x in range(fb.w):
                fb.dpx(x, y, lvl)
    # sun disc: clean paper hole, hard rim, banded
    r = 11
    for y in range(int(sun_y - r - 1), int(sun_y + r + 2)):
        for x in range(int(sun_x - r - 1), int(sun_x + r + 2)):
            if (x - sun_x) ** 2 + (y - sun_y) ** 2 <= r * r:
                fb.set(x, y, 0)
    fb.circle(sun_x, sun_y, r)
    for yy in (sun_y - 5, sun_y - 1, sun_y + 3, sun_y + 7):
        half = int(math.sqrt(max(0, r * r - (yy - sun_y) ** 2)))
        fb.hline(sun_x - half, sun_x + half, yy, 1)
    # light spilling along the horizon
    for y in range(A.HORIZON - 14, A.HORIZON):
        d = abs(y - (A.HORIZON - 7))
        lvl = int(round(9 * (1.0 - d / 7.0)))
        for x in range(fb.w):
            if abs(x - sun_x) > 20 and lvl > 0:
                fb.dpx(x, y, lvl)


def sky_day(fb):
    for y in range(A.HUD_H, A.HORIZON):
        t = (y - A.HUD_H) / float(A.HORIZON - A.HUD_H)
        lvl = int(round(6.0 * max(0.0, 1.0 - t) ** 1.7))
        if lvl > 0:
            for x in range(fb.w):
                fb.dpx(x, y, lvl)
    # a couple of flat cloud banks - hard bottom edge, stippled body
    for (cx, cy, cw, ch, lv) in ((22, 40, 52, 11, 20), (62, 64, 44, 8, 15)):
        for y in range(cy, cy + ch):
            for x in range(cx, cx + cw):
                u = (x - cx) / float(cw)
                lobe = math.sin(u * math.pi) ** 0.6
                lobe *= 0.75 + 0.25 * math.sin(u * 11.0 + cx)
                if (y - cy) / float(ch) < lobe:
                    fb.dpx(x, y, lv)


def sky_night(fb, star_seed=1):
    for i in range(90):
        h = W.hash32(star_seed * 131 + i)
        x = (h >> 3) % fb.w
        y = A.HUD_H + ((h >> 11) % (A.HORIZON - A.HUD_H - 26))
        if (h & 7) < 5:
            fb.set(x, y, 1)
    for y in range(A.HORIZON - 20, A.HORIZON):
        t = (y - (A.HORIZON - 20)) / 20.0
        lvl = int(round(11.0 * t * t * t))
        if lvl > 0:
            for x in range(fb.w):
                fb.dpx(x, y, lvl)


# ---------------------------------------------------------------------
#  RIDGE - dithered mass behind the solid earth
# ---------------------------------------------------------------------
def ridge(fb, cam_x, para=0.30, base=None, lvl=None, period=17, ink=1, gap=2):
    """A SOLID silhouette layer.  Depth comes from a 1-2px paper gap punched
    along its own skyline, never from a dither value - dithered landmasses
    turn the middle of the frame into grey mush."""
    base = base if base is not None else A.HORIZON - 10
    prof = []
    for x in range(fb.w):
        wx = cam_x * para + x
        c = W.cell(wx, period)
        t = W.frac(wx, period, c)
        t = t * t * (3 - 2 * t)
        a = base - ((W.hash32(c * 3 + 91) >> 5) % 9)
        b = base - ((W.hash32((c + 1) * 3 + 91) >> 5) % 9)
        prof.append(int(round(a + (b - a) * t)))
    for x, y0 in enumerate(prof):
        for y in range(max(A.HUD_H, y0 - gap), y0):
            fb.set(x, y, 0)
    for x, y0 in enumerate(prof):
        for y in range(y0, fb.h):
            fb.set(x, y, ink)
    return prof


def haze_sprite(fb, spr, x, y_base, lvl=40, ink=1):
    """Draw a sprite at partial density so it reads as distance, with the
    paper gap that keeps it off whatever is behind."""
    gx = int(round(x - len(spr[0]) / 2.0))
    gy = int(round(y_base)) - len(spr)
    for j, row in enumerate(spr):
        for i, c in enumerate(row):
            if c == "X":
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        fb.set(gx + i + dx, gy + j + dy, 0)
    for j, row in enumerate(spr):
        for i, c in enumerate(row):
            if c == "X":
                fb.dpx(gx + i, gy + j, lvl, ink)


def far_field(fb, cam_x, trenches, para=0.35, lvl=44, period=13, ink=1,
              base_lift=3):
    """Distant skyline: shattered poplars and a ruined spire, hazed.
    Seeded from absolute parallax-world position, never from screen index."""
    k0 = W.cell(cam_x * para - 12, period)
    k1 = W.cell(cam_x * para + fb.w + 12, period)
    for k in range(k0, k1 + 1):
        h = W.hash32(k * 6271 + 17)
        wx = k * period + ((h >> 7) % period)
        x = wx - cam_x * para
        if x < -14 or x > fb.w + 14:
            continue
        y = A.HORIZON - base_lift - ((h >> 3) & 3)
        r = h & 15
        if r == 0:
            haze_sprite(fb, A.SPR_SPIRE, x, y, lvl, ink)
        elif r < 9:
            haze_sprite(fb, A.SPR_TREE, x, y, lvl, ink)
        else:
            haze_sprite(fb, A.SPR_STUMP, x, y, lvl, ink)


def ridge_haze(fb, prof, depth=5, lvl=16, ink=1):
    """Optional: thin the top of a far ridge so it reads as distance."""
    for x, y0 in enumerate(prof):
        for y in range(y0, y0 + depth):
            if (W.hash32(x * 17 + y * 7) & 63) < lvl:
                fb.set(x, y, 0)


# ---------------------------------------------------------------------
#  EARTH
# ---------------------------------------------------------------------
def surface_y(wx, trenches):
    g = W.ground_y(wx)
    for (tx, h) in trenches:
        g += W.trench_cut(wx, tx, h)
    return g


def earth(fb, cam_x, trenches, night=False):
    """Day: one solid ink mass.  Night: black mass with a pale crust."""
    for x in range(fb.w):
        wx = cam_x + x
        gy = int(round(surface_y(wx, trenches)))
        if night:
            for k in range(CRUST):
                fb.set(x, gy + k, 1)
            for y in range(gy + CRUST, fb.h):
                fb.set(x, y, 0)
        else:
            for y in range(max(A.HUD_H, gy - 2), gy):
                fb.set(x, y, 0)
            for y in range(gy, fb.h):
                fb.set(x, y, 1)


def earth_detail(fb, cam_x, trenches, night=False):
    """Paper marks knocked out of the solid earth: furrows, craters, wire."""
    v = 1 if night else 0
    for x in range(fb.w):
        wx = cam_x + x
        gy = int(round(surface_y(wx, trenches)))
        h = W.hash32(int(math.floor(wx)))
        # shell furrows - short horizontal scratches
        if (h & (63 if night else 15)) == 0:
            L = 2 + ((h >> 8) & 3)
            yy = gy + 4 + ((h >> 12) % 34)
            fb.hline(x, x + L, yy, v)
        # wire pickets standing on the parapet
        if (h & 95) == 5:
            for k in range(3):
                fb.set(x, gy - 1 - k, 1 if not night else 1)
            fb.set(x - 1, gy - 3, 1)
            fb.set(x + 1, gy - 3, 1)
        # crater rims
        if (h & (255 if night else 127)) == 9:
            cy = gy + 6 + ((h >> 16) % 16)
            fb.circle(x, cy, 3 + ((h >> 20) & 1), v)


def sandbag_lip(fb, cam_x, tx, h, trenches, ink=1):
    half = 11 + (h & 3)
    for d in range(-half, half + 1):
        wx = tx + d
        x = int(round(wx - cam_x))
        if not (0 <= x < fb.w):
            continue
        gy = int(round(surface_y(wx, trenches)))
        g = W.hash32(int(math.floor(wx)) * 31 + 7)
        bump = 1 + ((g >> 5) & 1)
        if abs(d) > half - 3:
            bump += 2
        for k in range(bump):
            fb.set(x, gy - 1 - k, ink)


# ---------------------------------------------------------------------
#  ATMOSPHERE - the only mid-tones in the frame
# ---------------------------------------------------------------------
def smoke_column(fb, cam_x, wx, h, t, n=8, ink=1, base_y=None):
    x0 = wx - cam_x
    gy = base_y if base_y is not None else W.ground_y(wx)
    for (dx, dy, r, lvl) in W.smoke_puffs(wx, h, t, n):
        fb.dcircle(x0 + dx, gy + dy, r, int(lvl * 2.8), ink)


def drift_smoke(fb, cam_x, wx, y, r, lvl, t, ink=1, lobes=9):
    """A cluster of lobes, not one disc - a single big dcircle reads as a
    rectangle of stipple, which is exactly what it is."""
    sway = math.sin(t * 0.017 + (int(wx) & 15)) * 3
    cx = wx - cam_x + sway
    for i in range(lobes):
        h = W.hash32(int(wx) * 977 + i * 31)
        a = (h & 255) / 255.0 * math.tau
        d = ((h >> 8) & 255) / 255.0
        rr = r * (0.34 + 0.5 * (((h >> 16) & 255) / 255.0))
        ox = math.cos(a) * d * r * 0.95
        oy = math.sin(a) * d * r * 0.55
        lv = int(lvl * (1.0 - 0.55 * d) * (0.75 + 0.5 * (((h >> 24) & 15) / 15.0)))
        if lv >= 2:
            fb.dcircle(cx + ox, y + oy, rr, min(64, lv), ink)


def flak_burst(fb, cx, cy, age, ink=1):
    """age 0..1: white-hot core -> expanding dithered ring -> ragged smudge."""
    if age < 0.22:
        fb.dcircle(cx, cy, 1.6 + age * 7, 64, ink)
        for a in range(8):
            th = a * math.pi / 4 + 0.2
            L = 4 + age * 26
            fb.line(cx, cy, cx + math.cos(th) * L, cy + math.sin(th) * L, ink, dash=2)
    else:
        r = 2.0 + age * 9.5
        lvl = int(round(56 * (1.0 - age) ** 0.8))
        fb.dcircle(cx, cy, r, max(6, lvl), ink, hollow=0.3)
        fb.dcircle(cx + r * 0.5, cy - r * 0.45, r * 0.55, max(4, lvl - 14), ink)
        fb.dcircle(cx - r * 0.6, cy + r * 0.35, r * 0.45, max(4, lvl - 22), ink)
        # a broken rim keeps an old burst reading as a puff, not a smudge
        for a in range(0, 360, 11):
            th = math.radians(a)
            if (W.hash32(int(cx) * 31 + a) & 1) == 0:
                continue
            fb.set(int(round(cx + math.cos(th) * r)),
                   int(round(cy + math.sin(th) * r)), ink)


def searchlight(fb, sx, sy, tx, ty, spread=0.14, lvl=20, ink=1, locked=False):
    ang = math.atan2(ty - sy, tx - sx)
    reach = math.hypot(tx - sx, ty - sy) * 1.5
    for y in range(A.HUD_H, fb.h):
        for x in range(fb.w):
            dx, dy = x - sx, y - sy
            d = math.hypot(dx, dy)
            if d < 3 or d > reach:
                continue
            a = math.atan2(dy, dx)
            da = (a - ang + math.pi) % (2 * math.pi) - math.pi
            if abs(da) > spread:
                continue
            edge = (1.0 - abs(da) / spread) ** 0.6
            fall = 1.0 - (d / reach) * 0.5
            l = lvl * edge * fall * (2.4 if locked else 1.0)
            if l >= 1:
                fb.dpx(x, y, int(round(l)), ink)
    for s in (-1, 1):
        a = ang + s * spread
        fb.line(sx, sy, sx + math.cos(a) * reach, sy + math.sin(a) * reach,
                ink, dash=4)


def tracer(fb, x0, y0, x1, y1, ink=1, head=7):
    """A solid bright streak at the muzzle end, fading to a dashed track."""
    d = math.hypot(x1 - x0, y1 - y0)
    if d < 1:
        return
    ux, uy = (x1 - x0) / d, (y1 - y0) / d
    fb.line(x0, y0, x0 + ux * head, y0 + uy * head, ink)
    fb.line_duty(x0 + ux * head, y0 + uy * head, x1, y1, ink, on=1, period=5)


# ---------------------------------------------------------------------
#  TEXT + HUD - always on a knocked-out plate so it can never be lost
# ---------------------------------------------------------------------
def plate_text(fb, s, x, y, ink=1, sp=1, scale=1, pad=2):
    from render import text_width
    w = text_width(s, sp, scale)
    fb.rect(x - pad, y - pad, w + pad * 2, 5 * scale + pad * 2, 0 if ink else 1)
    fb.text(s, x, y, ink, sp, scale)
    return w


def hud(fb, alt, bombs, line_pct, ink=1):
    fb.rect(0, 0, fb.w, A.HUD_H, 0 if ink else 1)
    fb.text("ALT %d" % alt, 3, 2, ink)
    x = fb.w - 3 - bombs * 4
    for i in range(bombs):
        fb.sprite(A.SPR_BOMB, x + i * 4, 2, ink)
    bx, bw, by, bh = 4, fb.w - 8, 8, 4
    fb.frame(bx, by, bw, bh, ink)
    fill = int(round((bw - 2) * line_pct))
    for i in range(bw - 2):
        lv = 64 if i < fill else 14
        fb.dpx(bx + 1 + i, by + 1, lv, ink)
        fb.dpx(bx + 1 + i, by + 2, lv, ink)
    fb.vline(bx + 1 + fill, by - 2, by + bh + 1, ink)
    for x in range(0, fb.w, 2):
        fb.set(x, A.HUD_H - 2, ink)


def multiplier(fb, x, y, n, ink=1):
    plate_text(fb, "X%d" % n, x, y, ink, sp=2, scale=2, pad=1)
