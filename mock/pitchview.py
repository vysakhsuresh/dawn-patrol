import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artdata as A
from render import FB, save, sprite_rot

fb = FB(30, 5 * 16 + 4)
y = 3
for deg in (24, 12, 0, -12, -24):
    sprite_rot(fb, A.SPR_PLANE, 3, y, deg)
    y += 16
save(fb, "/home/user/dawn-patrol/out/zoom.png", (26, 22, 18), (232, 200, 150), scale=13)
print("ok")
