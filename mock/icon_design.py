"""
Design tool for the adaptive launcher icon's background layer: a dawn sky
+ sun + aerodrome silhouette, built from the same pixel-art vocabulary as
the game (reusing Art's actual HANGAR/TENT grids), rendered with PIL first
so it can be looked at before any VectorDrawable XML gets written.

Icon viewport is 108x108 (Android adaptive icon units). We design on a
CELL x CELL pixel-art grid (cell = one blocky "pixel"), matching the
game's own hard-edged 1-bit look rather than the smooth reference mockup.
"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artdata as A

VP = 108             # adaptive icon viewport, in dp/units
CELL = 3             # size of one "pixel" in viewport units
G = VP // CELL        # grid cells across/down (36)

SAFE_R = 33.0        # dp radius - foreground content must stay inside this
CX_CELL, CY_CELL = G / 2.0, G / 2.0

GROUND_ROW = 27       # ground silhouette starts here (bottom ~25%)
SUN_CY = 25           # sun centre row - mostly hidden by the ground line
SUN_R = 6.0
PLANE_CY_CELL = 13.0  # plane vertical centre, well clear of the ground


def circle_row_span(r, cx_cell, cy_cell, gy):
    y = gy - cy_cell + 0.5
    if abs(y) > r:
        return None
    half = math.sqrt(max(0.0, r * r - y * y))
    return (int(round(cx_cell - half)), int(round(cx_cell + half)))


def build_layers():
    """color -> list of (gx0, gx1, gy) cell-runs, in the 36x36 grid."""
    layers = {}

    def add(color, gx0, gx1, gy):
        if gx1 <= gx0:
            return
        layers.setdefault(color, []).append((gx0, gx1, gy))

    # ---- dawn sky: banded, warmest near the horizon --------------------
    bands = [
        (0, GROUND_ROW - 11, "#C97A2E"),
        (GROUND_ROW - 11, GROUND_ROW - 6, "#DB9A3C"),
        (GROUND_ROW - 6, GROUND_ROW - 2, "#EAB752"),
        (GROUND_ROW - 2, GROUND_ROW, "#F6D488"),
    ]
    for y0, y1, color in bands:
        for gy in range(max(0, y0), min(G, y1)):
            add(color, 0, G, gy)

    # ---- sun, mostly set below the horizon, off-centre so it never ----
    # competes with the plane for the safe zone
    for gy in range(0, GROUND_ROW):
        span = circle_row_span(SUN_R, G * 0.70, SUN_CY, gy)
        if span:
            add("#FBE7B8", max(0, span[0]), min(G, span[1]), gy)

    # ---- ground: dark band, full bleed to every edge -------------------
    for gy in range(GROUND_ROW, G):
        add("#1A1310", 0, G, gy)

    # ---- aerodrome silhouette sitting on the ground line ---------------
    def stamp(sprite, gx, gy_base, color="#1A1310"):
        h = len(sprite)
        for ry, row in enumerate(sprite):
            gy = gy_base - h + ry
            if not (0 <= gy < G):
                continue
            i = 0
            while i < len(row):
                if row[i] == "X":
                    j = i
                    while j < len(row) and row[j] == "X":
                        j += 1
                    add(color, gx + i, gx + j, gy)
                    i = j
                else:
                    i += 1

    stamp(A.SPR_HANGAR, -3, GROUND_ROW + 1)
    stamp(A.SPR_TENT, 20, GROUND_ROW + 1)
    stamp(A.SPR_TENT, 26, GROUND_ROW + 1)

    # windsock mast off the right edge
    mast_x = G - 3
    for gy in range(GROUND_ROW - 8, GROUND_ROW + 1):
        add("#1A1310", mast_x, mast_x + 1, gy)
    for k in range(3):
        add("#1A1310", mast_x + 1, mast_x + 2 + k, GROUND_ROW - 7 + k * 2)

    return layers


PLANE_SCALE = 2.4  # dp per plane-pixel - independent of the background's
                   # coarser CELL grid, matched to the already-verified
                   # foreground (worst corner radius 28.5 of the 35 budget)


def plane_runs_dp():
    """The plane sprite as (x0, x1, y0, y1) rects in raw dp units, sized
    and centred to sit inside the safe zone."""
    grid = A.SPR_PLANE
    cols = max(len(r) for r in grid)
    rows = len(grid)
    s = PLANE_SCALE
    ox = VP / 2.0 - cols * s / 2.0
    oy = PLANE_CY_CELL * CELL - rows * s / 2.0
    runs = []
    for ry, row in enumerate(grid):
        y0 = oy + ry * s
        y1 = y0 + s
        i = 0
        while i < len(row):
            if row[i] == "X":
                j = i
                while j < len(row) and row[j] == "X":
                    j += 1
                runs.append((ox + i * s, ox + j * s, y0, y1))
                i = j
            else:
                i += 1
    return runs


def worst_corner_radius(runs):
    """Audit: farthest any plane rect corner sits from the icon centre,
    in dp - must stay under the 35/36 safe-zone budget."""
    worst = 0.0
    for (x0, x1, y0, y1) in runs:
        for x in (x0, x1):
            for y in (y0, y1):
                worst = max(worst, math.hypot(x - VP / 2.0, y - VP / 2.0))
    return worst


def render_preview(show_guide=True):
    from PIL import Image, ImageDraw
    scale = 8
    px = VP * scale
    img = Image.new("RGB", (px, px), "#000000")
    d = ImageDraw.Draw(img)

    for color, runs in build_layers().items():
        for (gx0, gx1, gy) in runs:
            d.rectangle([gx0 * CELL * scale, gy * CELL * scale,
                         gx1 * CELL * scale, (gy + 1) * CELL * scale], fill=color)

    plane_runs = plane_runs_dp()
    for (x0, x1, y0, y1) in plane_runs:
        d.rectangle([x0 * scale, y0 * scale, x1 * scale, y1 * scale], fill="#1A160F")

    if show_guide:
        r_px = SAFE_R * scale
        d.ellipse([px / 2 - r_px, px / 2 - r_px, px / 2 + r_px, px / 2 + r_px],
                   outline="#00FF00", width=2)

    img.save("/home/user/dawn-patrol/out/icon_design.png")
    worst = worst_corner_radius(plane_runs)
    print("plane worst corner radius: %.2f  (budget 35 of 36)" % worst)
    assert worst <= 35.0
    print("saved", img.size)


if __name__ == "__main__":
    render_preview()
