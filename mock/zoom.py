import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artdata as A
from render import FB, save

names = sys.argv[1:] or ["SPR_PLANE"]
sprs = [(n, getattr(A, n)) for n in names]
W = max(max(len(r) for r in s) for _, s in sprs) + 4
H = sum(len(s) + 4 for _, s in sprs)
fb = FB(W, H)
y = 2
for n, s in sprs:
    fb.sprite(s, 2, y)
    y += len(s) + 4
save(fb, "/home/user/dawn-patrol/out/zoom.png", (26, 22, 18), (232, 200, 150), scale=18)
print(W, H)
