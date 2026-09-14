"""
Procedural world for Dawn Patrol.

LESSON #1 (paid for on Cat on a Fence): every procedural element is seeded
from its ABSOLUTE world position, never from an index counted off the screen
edge, and the cell index and the within-cell offset are derived from the SAME
floor() call so they can never disagree by a rounding error.

LESSON #4: unary minus binds tighter than %.  We never use % on a value that
can be negative - `cell()` below uses an explicit floor division instead.
"""
import math
import artdata as A

MASK = 0xFFFFFFFF


def hash32(n):
    """Deterministic 32-bit hash.  Ports verbatim to Kotlin Int arithmetic."""
    h = (n * 0x27D4EB2D) & MASK
    h ^= (h >> 15)
    h = (h * 0x85EBCA6B) & MASK
    h ^= (h >> 13)
    h = (h * 0xC2B2AE35) & MASK
    h ^= (h >> 16)
    return h


def cell(wx, period):
    """floor division - correct for negative wx, unlike int() or %."""
    return int(math.floor(wx / float(period)))


def frac(wx, period, c):
    """Within-cell fraction derived from the SAME c the caller already has."""
    return (wx - c * period) / float(period)


# ---------------------------------------------------------------------
#  TERRAIN
# ---------------------------------------------------------------------
TERR_BASE = A.HORIZON
TERR_AMP = 5


def terr_point(c):
    """Height (pixel row) of terrain control point c."""
    h = hash32(c * 2 + 1)
    j = (h >> 4) & 0xFF
    return TERR_BASE + (j * (TERR_AMP * 2 + 1) // 256) - TERR_AMP


def ground_y(wx):
    """Ground row at absolute world x.  ONE floor call feeds both c and t."""
    c = cell(wx, A.TERRAIN_CELL)
    t = frac(wx, A.TERRAIN_CELL, c)
    a, b = terr_point(c), terr_point(c + 1)
    # smoothstep so the crust never shows a hard corner
    t = t * t * (3 - 2 * t)
    return a + (b - a) * t


# ---------------------------------------------------------------------
#  FEATURE SLOTS
# ---------------------------------------------------------------------
AA_GUN, DEPOT, BALLOON, LIGHT, TRENCH, WRECK, TANK, SANDBAG = range(8)

# weighted table - index by hash % 16
SLOT_TABLE = [AA_GUN, TRENCH, SANDBAG, AA_GUN, DEPOT, TRENCH, WRECK, AA_GUN,
              SANDBAG, BALLOON, TRENCH, DEPOT, TANK, SANDBAG, LIGHT, TRENCH]


def slot(k):
    """Feature in absolute slot k.  Returns (type, world_x, hash)."""
    h = hash32(k * 7919 + 13)
    typ = SLOT_TABLE[h & 15]
    off = ((h >> 6) & 0x3F) * A.SLOT_W // 64
    return typ, k * A.SLOT_W + off, h


def visible_slots(cam_x, view_w, margin=40):
    """Absolute slot indices whose feature can touch the view."""
    k0 = cell(cam_x - margin, A.SLOT_W)
    k1 = cell(cam_x + view_w + margin, A.SLOT_W)
    return range(k0, k1 + 1)


# ---------------------------------------------------------------------
#  TRENCH PROFILE - a notch cut into the terrain, also world-seeded
# ---------------------------------------------------------------------
def trench_cut(wx, tx, h):
    """Depth to subtract from ground_y near a trench at tx.  0 outside."""
    half = 11 + (h & 3)
    d = wx - tx
    if abs(d) > half:
        return 0.0
    return 4.0 + 2.0 * (1.0 - abs(d) / float(half))


# ---------------------------------------------------------------------
#  DRIFTING SMOKE - columns anchored to world x, drifting with time
# ---------------------------------------------------------------------
def smoke_puffs(wx0, h, t, n=7):
    """Yield (dx, dy, r, level) puffs for a fire at world x = wx0."""
    for i in range(n):
        g = hash32(h + i * 2654435761)
        rise = i * 4.6 + 3
        sway = math.sin((t * 0.02) + i * 0.9 + (g & 31) * 0.2) * (1.4 + i * 0.8)
        drift = i * 1.7
        r = 1.7 + i * 0.82
        lvl = max(2, 13 - i)
        yield (drift + sway, -rise, r, lvl)
