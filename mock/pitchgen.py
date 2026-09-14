"""Design tool: rotate the level plane grid to make pitch attitudes.
Output is hand-checked and pasted into artdata.py as literal grids."""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artdata as A
from render import FB, save

def rot(rows, deg, pad=2):
    w = max(len(r) for r in rows); h = len(rows)
    W, H = w, h + pad * 2
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    ocx, ocy = (W - 1) / 2.0, (H - 1) / 2.0
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    out = [["."] * W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            dx, dy = x - ocx, y - ocy
            # inverse rotate (screen y down, positive deg = nose up)
            sx = ca * dx - sa * dy + cx
            sy = sa * dx + ca * dy + cy
            ix, iy = int(round(sx)), int(round(sy))
            if 0 <= iy < h and 0 <= ix < len(rows[iy]) and rows[iy][ix] == "X":
                out[y][x] = "X"
    res = ["".join(r) for r in out]
    while res and "X" not in res[0]:
        res.pop(0)
    while res and "X" not in res[-1]:
        res.pop()
    return res

if __name__ == "__main__":
    deg = float(sys.argv[1]) if len(sys.argv) > 1 else 12
    g = rot(A.SPR_PLANE, deg)
    for r in g:
        print('    "%s",' % r)
    fb = FB(max(len(r) for r in g) + 4, len(g) + 4)
    fb.sprite(g, 2, 2)
    save(fb, "/home/user/dawn-patrol/out/zoom.png", (26, 22, 18), (232, 200, 150), scale=18)
