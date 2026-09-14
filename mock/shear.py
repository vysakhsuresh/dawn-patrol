"""Design tool: per-column vertical shift (pure shift, never a union)."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artdata as A
from render import FB, save

def shear(rows, offs):
    """offs: list of (col_start, dy) breakpoints. dy>0 = that column moves DOWN."""
    w = max(len(r) for r in rows); h = len(rows)
    def dy_at(x):
        d = 0
        for c, v in offs:
            if x >= c:
                d = v
        return d
    lo = min(dy_at(x) for x in range(w)); hi = max(dy_at(x) for x in range(w))
    H = h + (hi - lo)
    out = [["."] * w for _ in range(H)]
    for y, r in enumerate(rows):
        for x, c in enumerate(r):
            if c == "X":
                out[y + dy_at(x) - lo][x] = "X"
    return ["".join(r) for r in out]

CLIMB = shear(A.SPR_PLANE, [(0, 3), (5, 2), (10, 1), (16, 0)])
DIVE  = shear(A.SPR_PLANE, [(0, 0), (5, 1), (10, 2), (16, 3)])

if __name__ == "__main__":
    print("CLIMB"); [print('    "%s",' % r) for r in CLIMB]
    print("DIVE");  [print('    "%s",' % r) for r in DIVE]
    fb = FB(28, 3 * 16 + 4)
    y = 2
    for g in (CLIMB, A.SPR_PLANE, DIVE):
        fb.sprite(g, 3, y); y += 16
    save(fb, "/home/user/dawn-patrol/out/zoom.png", (26, 22, 18), (232, 200, 150), scale=13)
