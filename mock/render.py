"""1-bit framebuffer + drawing primitives for the Dawn Patrol preview."""
from PIL import Image
import artdata as A

# 8x8 ordered dither: 65 density levels, fine enough that a 10%
# stipple reads as haze instead of as a checkerboard.
BAYER = [
    [ 0, 32,  8, 40,  2, 34, 10, 42],
    [48, 16, 56, 24, 50, 18, 58, 26],
    [12, 44,  4, 36, 14, 46,  6, 38],
    [60, 28, 52, 20, 62, 30, 54, 22],
    [ 3, 35, 11, 43,  1, 33,  9, 41],
    [51, 19, 59, 27, 49, 17, 57, 25],
    [15, 47,  7, 39, 13, 45,  5, 37],
    [63, 31, 55, 23, 61, 29, 53, 21],
]
BAYER_N = 8
BAYER_MAX = 64


class FB:
    """Strict 1-bit framebuffer.  0 = paper, 1 = ink."""

    def __init__(self, w=A.GW, h=A.GH, fill=0):
        self.w, self.h = w, h
        self.p = [bytearray([fill]) * w for _ in range(h)]

    def set(self, x, y, v=1):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.p[y][x] = v

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.p[y][x]
        return 0

    def rect(self, x, y, w, h, v=1):
        for j in range(int(y), int(y + h)):
            for i in range(int(x), int(x + w)):
                self.set(i, j, v)

    def frame(self, x, y, w, h, v=1):
        self.hline(x, x + w - 1, y, v)
        self.hline(x, x + w - 1, y + h - 1, v)
        self.vline(x, y, y + h - 1, v)
        self.vline(x + w - 1, y, y + h - 1, v)

    def hline(self, x0, x1, y, v=1):
        if x1 < x0:
            x0, x1 = x1, x0
        for x in range(int(x0), int(x1) + 1):
            self.set(x, int(y), v)

    def vline(self, x, y0, y1, v=1):
        if y1 < y0:
            y0, y1 = y1, y0
        for y in range(int(y0), int(y1) + 1):
            self.set(int(x), y, v)

    def line(self, x0, y0, x1, y1, v=1, dash=0):
        x0, y0, x1, y1 = int(x0), int(y0), int(x1), int(y1)
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        n = 0
        while True:
            if dash == 0 or (n % (dash * 2)) < dash:
                self.set(x0, y0, v)
            n += 1
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def line_duty(self, x0, y0, x1, y1, v=1, on=1, period=5):
        """Sparse dotted line - `on` pixels lit out of every `period`."""
        x0, y0, x1, y1 = int(x0), int(y0), int(x1), int(y1)
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err, n = dx + dy, 0
        while True:
            if (n % period) < on:
                self.set(x0, y0, v)
            n += 1
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy; x0 += sx
            if e2 <= dx:
                err += dx; y0 += sy

    # ---- dithering ----------------------------------------------------
    def dpx(self, x, y, level, v=1):
        """Plot (x,y) only if the ordered-dither threshold passes.
        level 0..64 -> 0% .. 100% coverage."""
        if BAYER[int(y) % BAYER_N][int(x) % BAYER_N] < level:
            self.set(int(x), int(y), v)

    def dcircle(self, cx, cy, r, level, v=1, hollow=0.0):
        r2, h2 = r * r, (r * hollow) ** 2
        for y in range(int(cy - r), int(cy + r) + 1):
            for x in range(int(cx - r), int(cx + r) + 1):
                d = (x - cx) ** 2 + (y - cy) ** 2
                if h2 <= d <= r2:
                    self.dpx(x, y, level, v)

    def circle(self, cx, cy, r, v=1):
        """Midpoint circle outline."""
        x, y, d = int(r), 0, 1 - int(r)
        while x >= y:
            for sx, sy in ((x, y), (y, x), (-x, y), (-y, x),
                           (x, -y), (y, -x), (-x, -y), (-y, -x)):
                self.set(int(cx) + sx, int(cy) + sy, v)
            y += 1
            if d < 0:
                d += 2 * y + 1
            else:
                x -= 1
                d += 2 * (y - x) + 1

    # ---- sprites ------------------------------------------------------
    def sprite(self, grid, x, y, v=1, flipx=False, mask_only=False):
        for j, row in enumerate(grid):
            cols = range(len(row) - 1, -1, -1) if flipx else range(len(row))
            for k, i in enumerate(cols):
                if row[i] == "X":
                    self.set(int(x) + k, int(y) + j, v)

    def sprite_clear(self, grid, x, y, pad=0):
        """Knock a sprite-shaped hole in whatever is underneath (paper)."""
        for j, row in enumerate(grid):
            for i, c in enumerate(row):
                if c == "X":
                    for dy in range(-pad, pad + 1):
                        for dx in range(-pad, pad + 1):
                            self.set(int(x) + i + dx, int(y) + j + dy, 0)

    # ---- text ---------------------------------------------------------
    def text(self, s, x, y, v=1, sp=1, scale=1):
        cx = int(x)
        for ch in s.upper():
            g = A.FONT.get(ch, A.FONT[" "])
            for j, row in enumerate(g):
                for i, c in enumerate(row):
                    if c == "X":
                        for sy in range(scale):
                            for sx in range(scale):
                                self.set(cx + i * scale + sx,
                                         int(y) + j * scale + sy, v)
            cx += (3 * scale) + sp
        return cx

    def text_w(self, s, sp=1, scale=1):
        return len(s) * (3 * scale + sp) - sp


def text_width(s, sp=1, scale=1):
    return len(s) * (3 * scale + sp) - sp


def save(fb, path, ink, paper, scale=6, border=0):
    w, h = fb.w * scale, fb.h * scale
    img = Image.new("RGB", (w + border * 2, h + border * 2), paper)
    px = img.load()
    for y in range(fb.h):
        row = fb.p[y]
        for x in range(fb.w):
            if row[x]:
                for sy in range(scale):
                    for sx in range(scale):
                        px[border + x * scale + sx, border + y * scale + sy] = ink
    img.save(path)
    return img


def save_row(fbs, path, ink, paper, scale=6, gap=8):
    """Contact sheet: several framebuffers side by side."""
    ws = [f.w * scale for f in fbs]
    h = max(f.h for f in fbs) * scale
    img = Image.new("RGB", (sum(ws) + gap * (len(fbs) + 1), h + gap * 2), (24, 24, 26))
    x0 = gap
    for fb, w in zip(fbs, ws):
        sub = Image.new("RGB", (fb.w * scale, fb.h * scale), paper)
        p = sub.load()
        for y in range(fb.h):
            for x in range(fb.w):
                if fb.p[y][x]:
                    for sy in range(scale):
                        for sx in range(scale):
                            p[x * scale + sx, y * scale + sy] = ink
        img.paste(sub, (x0, gap))
        x0 += w + gap
    img.save(path)
    return img


# ---------------------------------------------------------------------
#  Rotated sprite blit.
#  Mirrors Android: canvas.rotate(deg, cx, cy) then run-length drawRect
#  per sprite row.  Each run becomes a rotated quad, filled by coverage.
# ---------------------------------------------------------------------
import math


def _runs(row):
    out, i, n = [], 0, len(row)
    while i < n:
        if row[i] == "X":
            j = i
            while j < n and row[j] == "X":
                j += 1
            out.append((i, j))
            i = j
        else:
            i += 1
    return out


def sprite_rot(fb, grid, ox, oy, deg, v=1, pivot=None, cover=0.5, sub=3):
    """Blit `grid` with its top-left at (ox,oy), rotated `deg` about `pivot`
    (default: sprite centre).  Positive deg = nose up (screen y is down)."""
    h = len(grid)
    w = max(len(r) for r in grid)
    pcx, pcy = pivot if pivot else (w / 2.0, h / 2.0)
    a = math.radians(-deg)
    ca, sa = math.cos(a), math.sin(a)

    quads = []
    for j, row in enumerate(grid):
        for (i0, i1) in _runs(row):
            quads.append((i0, j, i1, j + 1))
    if not quads:
        return

    # world-space bounds
    pts = []
    for (x0, y0, x1, y1) in quads:
        for (px, py) in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
            dx, dy = px - pcx, py - pcy
            pts.append((ox + pcx + ca * dx - sa * dy,
                        oy + pcy + sa * dx + ca * dy))
    minx = int(math.floor(min(p[0] for p in pts)))
    maxx = int(math.ceil(max(p[0] for p in pts)))
    miny = int(math.floor(min(p[1] for p in pts)))
    maxy = int(math.ceil(max(p[1] for p in pts)))

    ica, isa = math.cos(-a), math.sin(-a)
    step = 1.0 / sub
    need = cover * sub * sub
    for py in range(miny, maxy + 1):
        for px in range(minx, maxx + 1):
            hits = 0
            for sy in range(sub):
                for sx in range(sub):
                    wx = px + (sx + 0.5) * step - (ox + pcx)
                    wy = py + (sy + 0.5) * step - (oy + pcy)
                    lx = ica * wx - isa * wy + pcx
                    ly = isa * wx + ica * wy + pcy
                    for (x0, y0, x1, y1) in quads:
                        if x0 <= lx < x1 and y0 <= ly < y1:
                            hits += 1
                            break
            if hits >= need:
                fb.set(px, py, v)
    return (minx, miny, maxx, maxy)


def sprite_rot_bounds(grid, ox, oy, deg, pivot=None):
    """Axis-aligned bounds of the rotated art - the hitbox must contain this."""
    h = len(grid)
    w = max(len(r) for r in grid)
    pcx, pcy = pivot if pivot else (w / 2.0, h / 2.0)
    a = math.radians(-deg)
    ca, sa = math.cos(a), math.sin(a)
    xs, ys = [], []
    for j, row in enumerate(grid):
        for i, c in enumerate(row):
            if c != "X":
                continue
            for (px, py) in ((i, j), (i + 1, j), (i, j + 1), (i + 1, j + 1)):
                dx, dy = px - pcx, py - pcy
                xs.append(ox + pcx + ca * dx - sa * dy)
                ys.append(oy + pcy + sa * dx + ca * dy)
    return (min(xs), min(ys), max(xs), max(ys))
