"""Render every sprite on one sheet so the art can be LOOKED at."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artdata as A
from render import FB, save

ITEMS = [
    ("PLANE", A.SPR_PLANE), ("CLIMB", A.SPR_PLANE_CLIMB), ("DIVE", A.SPR_PLANE_DIVE),
    ("AA GUN", A.SPR_AA_GUN), ("AA WRECK", A.SPR_AA_WRECK),
    ("LIGHT", A.SPR_SEARCHLIGHT), ("DEPOT", A.SPR_DEPOT),
    ("DEPOT WRECK", A.SPR_DEPOT_WRECK), ("BALLOON", A.SPR_BALLOON),
    ("TANK", A.SPR_TANK), ("BOMB", A.SPR_BOMB),
    ("HANGAR", A.SPR_HANGAR), ("TENT", A.SPR_TENT),
    ("BLAST0", A.SPR_BLAST[0]), ("BLAST1", A.SPR_BLAST[1]),
    ("BLAST2", A.SPR_BLAST[2]), ("BLAST3", A.SPR_BLAST[3]),
]

CELL_W, CELL_H, COLS = 36, 32, 4
fb = FB(COLS * CELL_W, ((len(ITEMS) + COLS - 1) // COLS) * CELL_H + 2)

for n, (name, spr) in enumerate(ITEMS):
    cx = (n % COLS) * CELL_W
    cy = (n // COLS) * CELL_H
    fb.text(name, cx + 1, cy + 1)
    w = max(len(r) for r in spr)
    fb.sprite(spr, cx + 1, cy + 8)
    # hitbox outline drawn 1px outside so it never hides art
    fb.frame(cx, cy + 7, w + 2, len(spr) + 2)

save(fb, "/home/user/dawn-patrol/out/sheet.png", (26, 22, 18), (232, 200, 150), scale=7)
print("sheet:", fb.w, "x", fb.h)
