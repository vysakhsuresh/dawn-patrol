"""
Turn the supplied DAWN PATROL badge artwork into an Android adaptive icon,
and PROVE what a launcher will actually show.

The trap this exists to avoid: an adaptive icon layer is 108dp, but a
launcher only ever shows the middle 72dp of it, and then cuts THAT with a
mask it chooses - circle, squircle or rounded square. So an icon that looks
perfect as a square picture can lose a third of its width before any mask
is applied, and then have its corners taken off as well.

Usage:  python3.12 mock/icon_from_png.py <source.png> <outdir>
"""
import sys, os
from PIL import Image, ImageDraw

LAYER = 432          # 108dp at 4x
VIEW = LAYER * 2 // 3  # 72dp - all a launcher ever shows


def interior(src):
    """The parchment artwork inside the badge, with its corners repaired.

    Two things have to go before this can be an icon layer:

    The gold rim is a rounded SQUARE. Left in place, a circular mask would
    slice it into four disconnected arcs, which reads as a mistake rather
    than as a frame - so the mask supplies the shape and the rim goes.

    And the parchment is itself a rounded square, so a square crop of it
    keeps four dark wedges of the page behind it, which show as dirty
    corners under a square or squircle mask. They are filled by extending
    each row outward from the first pixel actually inside the parchment -
    the sky keeps going sideways, which is what the picture would do.

    All four numbers are MEASURED off the source, not eyeballed: the badge
    runs 77..1172 x 72..1177 with a ~243px outer corner and a ~26px rim.
    """
    im = Image.open(src).convert("RGB")
    L, T, R_, B = 103, 98, 1147, 1152     # the parchment, inside the rim
    RAD = 217                              # its own corner radius
    art = im.crop((L, T, R_, B))
    W, H = art.size
    px = art.load()
    for y in range(H):
        # how far in the parchment starts on this row, from its corner arc
        dy = 0
        if y < RAD:
            dy = RAD - y
        elif y >= H - RAD:
            dy = RAD - (H - 1 - y)
        if dy <= 0:
            continue
        inset = int(round(RAD - (RAD * RAD - dy * dy) ** 0.5))
        if inset <= 0:
            continue
        inset = min(inset, W // 2 - 1)
        left, right = px[inset, y], px[W - 1 - inset, y]
        for x in range(inset):
            px[x, y] = left
            px[W - 1 - x, y] = right
    # square it up, losing a few rows top and bottom rather than distorting
    cut = (H - W) // 2
    return art.crop((0, cut, W, cut + W)) if H > W else art


def bleed(art, inner):
    """Place `art` at `inner` px inside a LAYER canvas, extending its edges.

    The 108dp layer is mostly never seen; it exists so a launcher can pan
    and zoom. Filling it by stretching the artwork's outermost rows and
    columns means the sky keeps going up and the ground keeps going down,
    which is what the picture would do anyway.
    """
    a = art.resize((inner, inner), Image.LANCZOS)
    out = Image.new("RGB", (LAYER, LAYER))
    off = (LAYER - inner) // 2
    if off > 0:
        # sides first, then top and bottom over them
        out.paste(a.crop((0, 0, 1, inner)).resize((off, inner)), (0, off))
        out.paste(a.crop((inner - 1, 0, inner, inner)).resize((off, inner)),
                  (off + inner, off))
        mid = Image.new("RGB", (LAYER, inner))
        mid.paste(out.crop((0, off, LAYER, off + inner)), (0, 0))
        mid.paste(a, (off, 0))
        out.paste(mid, (0, off))
        out.paste(mid.crop((0, 0, LAYER, 1)).resize((LAYER, off)), (0, 0))
        out.paste(mid.crop((0, inner - 1, LAYER, inner)).resize((LAYER, off)),
                  (0, off + inner))
    else:
        out.paste(a, (off, off))
    return out


def masks(size):
    """circle / squircle / rounded square, as launchers actually cut them."""
    out = {}
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size - 1, size - 1), 255)
    out["circle"] = m
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size - 1, size - 1),
                                        radius=int(size * 0.30), fill=255)
    out["squircle"] = m
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size - 1, size - 1),
                                        radius=int(size * 0.12), fill=255)
    out["square"] = m
    return out


def preview(layer, path):
    """What the three masks give, at the three sizes that matter."""
    view = layer.crop(((LAYER - VIEW) // 2, (LAYER - VIEW) // 2,
                       (LAYER + VIEW) // 2, (LAYER + VIEW) // 2))
    sizes = [192, 96, 48]
    names = ["circle", "squircle", "square"]
    pad = 12
    W = pad + sum(s + pad for s in sizes)
    H = pad + len(names) * (192 + pad)
    sheet = Image.new("RGB", (W, H), (30, 30, 34))
    y = pad
    for n in names:
        x = pad
        for s in sizes:
            v = view.resize((s, s), Image.LANCZOS)
            m = masks(s)[n]
            cell = Image.new("RGB", (s, s), (30, 30, 34))
            cell.paste(v, (0, 0), m)
            sheet.paste(cell, (x, y + (192 - s) // 2))
            x += s + pad
        y += 192 + pad
    sheet.save(path)


if __name__ == "__main__":
    src, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)
    art = interior(src)
    print("interior", art.size)
    for name, inner in [("v1_fullbleed", LAYER),
                        ("v2_fitview", VIEW),
                        ("v3_fitcircle", int(VIEW * 0.90))]:
        layer = bleed(art, inner)
        layer.save(f"{outdir}/{name}_layer.png")
        preview(layer, f"{outdir}/{name}_masks.png")
        print("wrote", name)


# --------------------------------------------------------------------------
def place(art, scale=0.92, dy=0.03):
    """The chosen placement: the badge at `scale` of the visible 72dp, nudged
    down by `dy`.

    Full size (scale 1.00) the circular mask cuts the tops off DAWN - the D
    and the N are the outermost glyphs and they sit high, so the mask reaches
    them before it reaches anything else. 0.92 with a 3 percent drop clears
    it with margin under all three masks without looking shrunken; 0.88 also
    clears but reads as an icon with a border. Rendered side by side in
    out/icon/placement.png.
    """
    layer = bleed(art, int(VIEW * scale))
    if dy:
        off = int(LAYER * dy)
        out = Image.new("RGB", (LAYER, LAYER))
        out.paste(layer, (0, off))
        out.paste(layer.crop((0, 0, LAYER, 1)).resize((LAYER, off)), (0, 0))
        layer = out
    return layer


def emit(src, res_dir, play_png):
    """Write the real Android resources."""
    layer = place(interior(src))
    for bucket, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                       ("xxhdpi", 324), ("xxxhdpi", 432)]:
        d = f"{res_dir}/mipmap-{bucket}"
        os.makedirs(d, exist_ok=True)
        img = layer.resize((px, px), Image.LANCZOS)
        # a flat illustration; a palette keeps it crisp and small
        img.convert("P", palette=Image.ADAPTIVE, colors=128).save(
            f"{d}/ic_launcher_foreground.png", optimize=True)
        print(f"  mipmap-{bucket}/ic_launcher_foreground.png  {px}px")
    # Play Store listing art keeps the badge's own frame - nothing masks it
    full = Image.open(src).convert("RGB").crop((70, 64, 1180, 1184))
    full.resize((512, 512), Image.LANCZOS).save(play_png, optimize=True)
    print(" ", play_png, "512px")
