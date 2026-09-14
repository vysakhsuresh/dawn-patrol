"""Compose the four Dawn Patrol preview frames."""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artdata as A
import world as W
import scene as S
from render import FB, save, text_width
from shear import shear

OUT = "/home/user/dawn-patrol/out"

DAWN = ((38, 25, 17), (233, 176, 104))
DAY = ((25, 31, 27), (183, 194, 168))
NIGHT = ((150, 183, 205), (8, 12, 19))
FIRE = ((32, 14, 8), (232, 108, 44))


def gather(cam_x, w):
    """Visible slots and the trench list the terrain needs BEFORE drawing."""
    feats, trenches = [], []
    for k in W.visible_slots(cam_x, w):
        typ, wx, h = W.slot(k)
        feats.append((k, typ, wx, h))
        if typ == W.TRENCH:
            trenches.append((wx, h))
    return feats, trenches


def outline(fb, spr, x, y, ink=1):
    """Draw only a sprite's border - the night silhouette treatment."""
    h = len(spr)
    for j, row in enumerate(spr):
        for i, c in enumerate(row):
            if c != "X":
                continue
            for (dx, dy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                yy, xx = j + dy, i + dx
                if 0 <= yy < h and 0 <= xx < len(spr[yy]) and spr[yy][xx] == "X":
                    continue
                fb.set(x + i, y + j, ink)
                break


def put(fb, spr, x, y_base, ink=1, night=False, halo=True):
    """Stand a sprite on y_base.  By day: paper halo + solid ink, so the
    silhouette separates from the solid earth.  At night: pale outline."""
    gx = int(round(x - len(spr[0]) / 2.0))
    gy = int(round(y_base)) - len(spr)
    if night:
        for j, row in enumerate(spr):
            for i, c in enumerate(row):
                if c == "X":
                    fb.set(gx + i, gy + j, 0)
        for j, row in enumerate(spr):
            for i, c in enumerate(row):
                if c == "X":
                    fb.dpx(gx + i, gy + j, 16, ink)
        outline(fb, spr, gx, gy, ink)
    else:
        if halo:
            fb.sprite_clear(spr, gx, gy, pad=1)
        fb.sprite(spr, gx, gy, ink)
    return gx, gy


def plane(fb, spr, x, y, ink=1, halo=True):
    gx, gy = int(round(x)), int(round(y))
    if halo:
        fb.sprite_clear(spr, gx, gy, pad=1)
    fb.sprite(spr, gx, gy, ink)
    return gx, gy


def features(fb, cam_x, feats, trenches, dead, smoking, t, ink=1, night=False):
    for (k, typ, wx, h) in feats:
        x = wx - cam_x
        if x < -34 or x > fb.w + 34:
            continue
        gy = S.surface_y(wx, trenches)
        if typ == W.TRENCH:
            S.sandbag_lip(fb, cam_x, wx, h, trenches, ink)
        elif typ == W.SANDBAG:
            for d in range(-7, 8):
                xx = int(round(x + d))
                gg = int(round(S.surface_y(cam_x + xx, trenches)))
                b = 1 + ((W.hash32(int(wx) + d) >> 6) & 1) + (2 if abs(d) < 4 else 0)
                for kk in range(b):
                    fb.set(xx, gg - 1 - kk, ink)
        elif typ == W.AA_GUN:
            put(fb, A.SPR_AA_WRECK if k in dead else A.SPR_AA_GUN,
                x, gy + 1, ink, night)
        elif typ == W.DEPOT:
            put(fb, A.SPR_DEPOT_WRECK if k in dead else A.SPR_DEPOT,
                x, gy + 1, ink, night)
        elif typ == W.TANK:
            put(fb, A.SPR_TANK, x, gy + 1, ink, night)
        elif typ == W.LIGHT:
            put(fb, A.SPR_SEARCHLIGHT, x, gy + 1, ink, night)
        elif typ == W.WRECK:
            put(fb, A.SPR_AA_WRECK, x, gy + 1, ink, night)
        elif typ == W.BALLOON:
            top = A.SKY_TOP + 16 + ((h >> 3) & 15)
            fb.line(x, top + len(A.SPR_BALLOON), x, gy, ink, dash=4)
            bx = int(round(x - len(A.SPR_BALLOON[0]) / 2.0))
            if night:
                for j, row in enumerate(A.SPR_BALLOON):
                    for i, c in enumerate(row):
                        if c == "X":
                            fb.set(bx + i, top + j, 0)
                outline(fb, A.SPR_BALLOON, bx, top, ink)
            else:
                fb.sprite_clear(A.SPR_BALLOON, bx, top, pad=1)
                fb.sprite(A.SPR_BALLOON, bx, top, ink)
    for (wx, h) in smoking:
        S.smoke_column(fb, cam_x, wx, h, t, 7, ink)


# =====================================================================
#  1 - DAWN TAKE-OFF / START
# =====================================================================
def frame1():
    fb = FB()
    cam_x = 0
    S.sky_dawn(fb, 76, A.HORIZON - 9)
    trenches = []
    S.far_field(fb, cam_x, trenches, 0.35, 34, 15)
    S.earth(fb, cam_x, trenches)
    S.earth_detail(fb, cam_x, trenches)

    # home aerodrome: canvas hangar, bell tents, windsock
    put(fb, A.SPR_HANGAR, 18, W.ground_y(18) + 1)
    put(fb, A.SPR_TENT, 40, W.ground_y(40) + 1)
    put(fb, A.SPR_TENT, 54, W.ground_y(54) + 1)
    mx = 94
    gy = int(W.ground_y(mx))
    fb.sprite_clear(["X"] * 1, mx, gy - 18, pad=1)
    for y in range(gy - 18, gy):
        fb.set(mx - 1, y, 0)
        fb.set(mx + 1, y, 0)
    fb.vline(mx, gy - 18, gy)
    for i in range(7):
        for k in range(1 + i // 2):
            fb.set(mx + 1 + i, gy - 18 + k, 0)
        fb.set(mx + 1 + i, gy - 18 + 1 + i // 2)
        fb.set(mx + 1 + i, gy - 18)
    # wheel ruts across the field
    for x in range(0, fb.w, 3):
        fb.set(x, int(W.ground_y(x)) + 3, 0)

    # just unstuck, climbing away with the tail still low
    plane(fb, A.SPR_PLANE_CLIMB, 20, 84)
    for i in range(6):
        fb.set(17 - i * 3, 89 + i // 2)
        fb.set(16 - i * 3, 90 + i // 2)

    S.plate_text(fb, "DAWN PATROL",
                 (fb.w - text_width("DAWN PATROL", 2, 2)) // 2, 38, 1, 2, 2, 3)
    for s, y in (("HOLD LEFT TO CLIMB", 56), ("HOLD RIGHT TO FIRE", 64)):
        S.plate_text(fb, s, (fb.w - text_width(s)) // 2, y)
    s = "LAST SORTIE  LINE 41%"
    S.plate_text(fb, s, (fb.w - text_width(s)) // 2, 160)
    S.hud(fb, 12, 6, 0.41)
    return fb, DAWN


# =====================================================================
#  2 - LOW STRAFING RUN, FLAK UP, MULTIPLIER UP
# =====================================================================
def frame2():
    fb = FB()
    cam_x = 3051
    t = 300.0
    S.sky_day(fb)
    feats, trenches = gather(cam_x, fb.w)
    S.far_field(fb, cam_x, trenches, 0.35, 38, 13)
    S.earth(fb, cam_x, trenches)
    S.earth_detail(fb, cam_x, trenches)

    guns = [(k, wx, h) for (k, typ, wx, h) in feats if typ == W.AA_GUN]
    depots = [(k, wx, h) for (k, typ, wx, h) in feats if typ == W.DEPOT]
    # the depot on the right is already burning - that is what moved the meter
    dead = {depots[0][0]} if depots else set()
    smoking = [(depots[0][1], depots[0][2])] if depots else []
    features(fb, cam_x, feats, trenches, dead, smoking, t)

    px, py = A.PLAYER_X, 88          # LOW - the greedy altitude
    # live guns lead the aircraft: short bright burst at the muzzle,
    # then a dashed shell track running out ahead of the nose
    for (k, wx, h) in guns:
        if k in dead:
            continue
        x = wx - cam_x
        if not (-6 <= x <= fb.w + 6):
            continue
        # muzzle sits at the barrel tip, not on the carriage
        mx = x - len(A.SPR_AA_GUN[0]) / 2.0 + 13.5
        my = S.surface_y(wx, trenches) + 1 - len(A.SPR_AA_GUN)
        lead = 11 + (h & 7)
        fb.dcircle(mx, my, 2.6, 64)
        fb.dcircle(mx + 2, my - 2, 1.6, 64)
        S.tracer(fb, mx, my, px + lead, py + 4)

    # flak at several ages - the gaps between them are the dodge window
    for (bx, by, age) in ((63, 66, 0.12), (84, 84, 0.40), (44, 52, 0.66),
                          (20, 76, 0.88)):
        S.flak_burst(fb, bx, by, age)

    plane(fb, A.SPR_PLANE_DIVE, px, py)
    # guns firing forward-down along the run
    for i in range(3):
        S.tracer(fb, px + 23, py + 5 + i * 2, px + 44 + i * 6, py + 15 + i * 5)
    # bombs away, and the one before it going off
    fb.sprite_clear(A.SPR_BOMB, px + 13, py + 14, pad=1)
    fb.sprite(A.SPR_BOMB, px + 13, py + 14)
    bx2 = px + 26
    S.flak_burst(fb, bx2, S.surface_y(cam_x + bx2, trenches) - 5, 0.16)

    # drifting smoke that genuinely blocks the view
    S.drift_smoke(fb, cam_x, cam_x + 4, 100, 13, 40, t)
    S.drift_smoke(fb, cam_x, cam_x + 92, 74, 11, 34, t + 90)

    S.multiplier(fb, px + 2, py - 20, 5)
    S.hud(fb, 18, 4, 0.63)
    return fb, DAY


# =====================================================================
#  3 - NIGHT PASS, SEARCHLIGHT LOCKED
# =====================================================================
def frame3():
    fb = FB()
    cam_x = 1445
    t = 900.0
    S.sky_night(fb, 7)
    
    feats, trenches = gather(cam_x, fb.w)

    px, py = A.PLAYER_X, 58
    pcx, pcy = px + 11, py + 4
    S.searchlight(fb, 80, A.HORIZON - 6, pcx, pcy, 0.085, 16, 1, locked=True)
    S.searchlight(fb, 8, A.HORIZON - 3, 66, A.HUD_H + 2, 0.11, 11, 1)

    # the earth is the black mass: punch every beam back out of it
    for x in range(fb.w):
        gy = int(round(S.surface_y(cam_x + x, trenches)))
        for y in range(gy + 1, fb.h):
            fb.set(x, y, 0)
        fb.set(x, gy, 1)
    S.earth_detail(fb, cam_x, trenches, night=True)
    features(fb, cam_x, feats, trenches, set(), [], t, night=True)

    for (ax, lead) in ((86, 9), (13, 15)):
        mx = ax - len(A.SPR_AA_GUN[0]) / 2.0 + 13.5
        my = S.surface_y(cam_x + ax, trenches) + 1 - len(A.SPR_AA_GUN)
        fb.dcircle(mx, my, 2.6, 64)
        S.tracer(fb, mx, my, px + lead, py + 3)
    for (bxx, byy, age) in ((48, 48, 0.22), (79, 36, 0.58)):
        S.flak_burst(fb, bxx, byy, age)

    # the lock itself: a bright pool of light on the aircraft, so its
    # silhouette reads as a hole rather than as an outline
    for y in range(py - 9, py + len(A.SPR_PLANE) + 9):
        for x in range(px - 11, px + 33):
            d = ((x - pcx) / 21.0) ** 2 + ((y - pcy) / 12.0) ** 2
            if d <= 1.0:
                fb.dpx(x, y, int(round(58 * (1.0 - d) ** 0.45)))

    # caught: the aircraft is a hole punched in the light, rimmed in pale
    spr = A.SPR_PLANE
    for j, row in enumerate(spr):
        for i, c in enumerate(row):
            if c != "X":
                continue
            for (dx, dy) in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1),
                             (-1, -1), (1, -1), (-1, 1)):
                yy, xx = j + dy, i + dx
                if 0 <= yy < len(spr) and 0 <= xx < len(spr[yy]) \
                        and spr[yy][xx] == "X":
                    continue
                fb.set(px + xx, py + yy, 1)
    fb.sprite(spr, px, py, 0)

    S.hud(fb, 58, 2, 0.71)
    S.plate_text(fb, "LOCKED", px + 32, py - 14, 1)
    return fb, NIGHT


# =====================================================================
#  4 - THE CRASH
# =====================================================================
def frame4():
    fb = FB()
    cam_x = 629
    t = 1400.0
    S.sky_day(fb)
    feats, trenches = gather(cam_x, fb.w)
    S.far_field(fb, cam_x, trenches, 0.35, 40, 13)
    S.earth(fb, cam_x, trenches)
    S.earth_detail(fb, cam_x, trenches)
    features(fb, cam_x, feats, trenches, set(), [], t)

    px, py = 46, 92
    # the arc it fell along, sweeping in from off the top-left corner
    for i in range(14):
        u = i / 13.0
        fx = px - 12 - u * 68.0
        fy = py - 4 - u * u * 56.0 - u * 14.0
        if fy < A.HUD_H - 6:
            break
        # oldest smoke (far up the arc) is the widest and thinnest
        fb.dcircle(fx, fy, 2.0 + u * 7.0, int(max(5, 46 - u * 33)))

    # nose-down, burning, a moment off the mud.  The shear is one step
    # steeper than a normal dive - any more and the silhouette dissolves.
    crashing = shear(A.SPR_PLANE, [(0, 0), (5, 1), (10, 3), (16, 4)])
    crashing = [r for r in crashing if "X" in r]
    crashing = crashing[2:]                      # upper wing torn away
    fb.sprite_clear(crashing, px, py, pad=2)
    fb.sprite(crashing, px, py)

    # that upper wing, tumbling clear
    wing = ["XXXXXXXXX.", ".X......X.", "..XXXXXX.."]
    fb.sprite_clear(wing, px - 24, 74, pad=2)
    fb.sprite(wing, px - 24, 74)

    # impact starting under the nose
    ix = px + 21
    gyi = S.surface_y(cam_x + ix, trenches)
    S.flak_burst(fb, ix, gyi - 6, 0.13)
    for (dx, dy) in ((7, -16), (12, -10), (4, -21), (15, -19), (10, -25)):
        fb.set(int(ix + dx), int(gyi + dy))
        fb.set(int(ix + dx) + 1, int(gyi + dy))
    # fire streaming back off the engine
    for i in range(7):
        fb.dcircle(px + 19 - i * 2.6, py + 9 + i * 1.2, 1.5 + i * 0.9,
                   max(6, 60 - i * 8))

    S.plate_text(fb, "SHOT DOWN",
                 (fb.w - text_width("SHOT DOWN", 2, 2)) // 2, 34, 1, 2, 2, 3)
    for line, y in (("LINE PUSHED  12%", 52), ("GUNS KILLED   7", 60),
                    ("DEPOTS         3", 68)):
        S.plate_text(fb, line, (fb.w - text_width(line)) // 2, y)
    s2 = "TAP TO SCRAMBLE"
    S.plate_text(fb, s2, (fb.w - text_width(s2)) // 2, 160)
    S.hud(fb, 4, 1, 0.53)
    return fb, FIRE


if __name__ == "__main__":
    for i, fn in enumerate((frame1, frame2, frame3, frame4), 1):
        fb, (ink, paper) = fn()
        save(fb, "%s/frame%d.png" % (OUT, i), ink, paper, scale=5)
        print("frame%d" % i)
