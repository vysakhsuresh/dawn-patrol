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


# --------------------------------------------------------------------------
def verify_shipped(res_dir, out_png):
    """Mask the files that actually go in the APK, per density bucket.

    Verifying the intermediate would only prove the generator works. This
    opens the PNGs the build will package, takes the middle 72dp a launcher
    shows, and cuts it with each mask - at the pixel size that bucket
    renders at on a real screen.
    """
    from PIL import Image, ImageDraw
    import os
    buckets = [("mdpi", 108, 48), ("hdpi", 162, 72), ("xhdpi", 216, 96),
               ("xxhdpi", 324, 144), ("xxxhdpi", 432, 192)]
    shapes = ["circle", "squircle", "square"]
    pad, lab = 10, 14
    W = pad + sum(s + pad for _, _, s in buckets)
    H = lab + len(shapes) * (192 + pad) + pad
    sheet = Image.new("RGB", (W, H), (28, 28, 32))
    d = ImageDraw.Draw(sheet)
    y = lab
    for shape in shapes:
        x = pad
        for name, layer_px, screen_px in buckets:
            p = f"{res_dir}/mipmap-{name}/ic_launcher_foreground.png"
            im = Image.open(p).convert("RGB")
            assert im.size == (layer_px, layer_px), (p, im.size)
            v = layer_px * 2 // 3
            o = (layer_px - v) // 2
            view = im.crop((o, o, o + v, o + v)).resize(
                (screen_px, screen_px), Image.LANCZOS)
            m = Image.new("L", (screen_px, screen_px), 0)
            dr = ImageDraw.Draw(m)
            e = screen_px - 1
            if shape == "circle":
                dr.ellipse((0, 0, e, e), 255)
            else:
                r = int(screen_px * (0.30 if shape == "squircle" else 0.12))
                dr.rounded_rectangle((0, 0, e, e), radius=r, fill=255)
            cell = Image.new("RGB", (screen_px, screen_px), (28, 28, 32))
            cell.paste(view, (0, 0), m)
            sheet.paste(cell, (x, y + (192 - screen_px) // 2))
            if shape == shapes[0]:
                d.text((x, 2), f"{name} {screen_px}px", fill=(230, 220, 200))
            x += screen_px + pad
        y += 192 + pad
    os.makedirs(os.path.dirname(out_png), exist_ok=True)
    sheet.save(out_png)
    print("wrote", out_png)
