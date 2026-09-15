"""
Render the launcher icon (background + foreground, composited exactly as
Android's adaptive-icon system does) under circle, squircle and square
masks at 192/96/48px - the check lesson #6 asks for, run before ever
shipping an icon change.
"""
import sys
import math
sys.path.insert(0, __file__.rsplit("/", 1)[0])
import icon_design as ID
from PIL import Image, ImageDraw

OUT = "/home/user/dawn-patrol/out/icon_masks.png"


def render(size):
    scale = size / ID.VP
    img = Image.new("RGB", (size, size), "#000000")
    d = ImageDraw.Draw(img)
    for color, runs in ID.build_layers().items():
        for (gx0, gx1, gy) in runs:
            d.rectangle([gx0 * ID.CELL * scale, gy * ID.CELL * scale,
                         gx1 * ID.CELL * scale, (gy + 1) * ID.CELL * scale], fill=color)
    for (x0, x1, y0, y1) in ID.plane_runs_dp():
        d.rectangle([x0 * scale, y0 * scale, x1 * scale, y1 * scale], fill="#1A160F")
    return img


def mask(shape, size):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, size, size], fill=255)
    elif shape == "square":
        d.rectangle([0, 0, size, size], fill=255)
    else:  # squircle, superellipse n=4 - roughly matches common launcher masks
        n = 4
        pts = []
        c = size / 2.0
        for a in range(0, 360, 2):
            th = math.radians(a)
            ct, st = math.cos(th), math.sin(th)
            r = c / (abs(ct) ** n + abs(st) ** n) ** (1.0 / n)
            pts.append((c + r * ct, c + r * st))
        d.polygon(pts, fill=255)
    return m


def build_sheet():
    sizes = [192, 96, 48]
    shapes = ["circle", "squircle", "square"]
    cell = max(sizes) + 20
    sheet = Image.new("RGB", (cell * len(sizes) + 20, cell * len(shapes) + 20), (30, 30, 32))
    for r, shape in enumerate(shapes):
        for c, size in enumerate(sizes):
            icon = render(size).convert("RGBA")
            m = mask(shape, size)
            out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            out.paste(icon, (0, 0), m)
            bg = Image.new("RGBA", (size, size), (60, 60, 64, 255))
            bg.alpha_composite(out)
            x = 10 + c * cell + (cell - size) // 2
            y = 10 + r * cell + (cell - size) // 2
            sheet.paste(bg.convert("RGB"), (x, y))
    return sheet


if __name__ == "__main__":
    worst = ID.worst_corner_radius(ID.plane_runs_dp())
    print("plane worst corner radius: %.2f (budget 35 of 36)" % worst)
    assert worst <= 35.0
    sheet = build_sheet()
    sheet.save(OUT)
    print("saved", OUT, sheet.size)
