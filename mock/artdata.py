"""
DAWN PATROL - art + tuning source of truth (pre-Kotlin).

Everything in this file is written in the exact shape it will take in
GameView.kt: sprites are Array<String> grids of 'X' and '.', constants are
scalars.  mock/ktparse.py reads GameView.kt with regex when it exists and
falls back to this module until then, so the preview renderer can never
drift from shipped code.
"""

# ---- logical canvas ---------------------------------------------------
# Portrait.  Fixed logical units, uniformly scaled: s = min(w/LW, h/LH).
LW = 400
LH = 700
PX = 4                 # logical units per sprite pixel
GW = LW // PX          # 100 pixel columns
GH = LH // PX          # 175 pixel rows

# ---- world layout (pixel-grid rows) -----------------------------------
HUD_H      = 15        # HUD strip across the top
SKY_TOP    = HUD_H
HORIZON    = 126       # nominal ground line
CEILING    = SKY_TOP + 4
PLAYER_X   = 26        # player's fixed column

# ---- flight model (per 60Hz frame, pixel-grid units) ------------------
CLIMB_ACC  = 0.055
GRAVITY    = 0.048
VY_MAX_UP  = 1.05
VY_MAX_DN  = 1.55
SPEED_MIN  = 0.95
SPEED_MAX  = 1.80

# ---- procedural world -------------------------------------------------
SLOT_W     = 30        # world units between feature slots
TERRAIN_CELL = 8       # world units per terrain control point

# ======================================================================
#  SPRITES
# ======================================================================

SPR_PLANE = [
    ".....XXXXXXXXXX.......",
    "......X......X........",
    "......X......X........",
    ".XX.......X.XXXXXXX...",
    "XXXXXXXXXXXXXXXXXXXX.X",
    "..XXXXXXXXXXXXXXXXX...",
    ".....XXXXXXXXXX.....X.",
    "......X.....X.........",
    ".....XXX...XXX........",
]

SPR_PLANE_DIVE = [
    ".....XXXXXXXXXX.......",
    "......X......X........",
    ".XX...X......X........",
    "XXXX......X.XXX.......",
    "..XXXXXXXXXXXXXXXXX...",
    "....XXXXXXXXXXXXXXXX.X",
    ".....XXXXXXXXXXXXXX...",
    "......X.....X.......X.",
    ".....XXX...XXX........",
]

SPR_PLANE_CLIMB = [
    ".....XXXXXXXXXX.......",
    "......X......X........",
    "......X......X.XXXX...",
    "..........X.XXXXXXXX.X",
    ".XX.XXXXXXXXXXXXXXX...",
    "XXXXXXXXXXXXXXX.....X.",
    "..XX.XXXXXXXXXX.......",
    "......X.....X.........",
    ".....XXX...XXX........",
]

SPR_AA_GUN = [
    "............XXX",
    "..........XXX..",
    "........XXX....",
    "......XXX......",
    "....XXXX.......",
    "...XXXXX.......",
    "..XXXXXXX......",
    "XXXX.XXXX......",
    ".X..XXXXX......",
    "..XXXXXXXX.....",
]

SPR_AA_WRECK = [
    "...............",
    "...............",
    "...............",
    "...............",
    "......X........",
    "...XX.X........",
    "..XXXX.XXX.....",
    "XXXX.XX...XX...",
    ".X..XXX........",
    "..XXX.XXXX.....",
]

SPR_SEARCHLIGHT = [
    "......XXXXX.",
    ".....XXXXXXX",
    "....XXX...XX",
    "...XXX....XX",
    "...XXX...XX.",
    "....XXXXXX..",
    ".....XX.....",
    "...XXXXXX...",
    "..XXXXXXXX..",
]

SPR_DEPOT = [
    "......XXXXXX......",
    ".....XXXXXXXX.....",
    "....XXXXXXXXXX....",
    "...XXXXXXXXXXXX...",
    "..XX..........XX..",
    "..X............X..",
    "XXXX..XXXXXX..XXXX",
    "XXXX..X....X..XXXX",
    "XXXX..X....X..XXXX",
    "XXXX..XXXXXX..XXXX",
    "XXXXXXXXXXXXXXXXXX",
]

SPR_DEPOT_WRECK = [
    "..................",
    "..................",
    "..................",
    "..................",
    "..................",
    "..................",
    "..X...........X...",
    ".XXX..X....X..X...",
    ".XX...XX..XX..XXX.",
    "XXXX..XXXXXX..XXX.",
    "XXXXXXXXXXXXXXXXXX",
]

SPR_BALLOON = [
    "....XXXXXXXX....",
    "..XXXXXXXXXXXX..",
    ".XXXXXXXXXXXXXX.",
    "XXXXXXXXXXXXXXX.",
    "XXXXXXXXXXXXXXXX",
    "XXXXXXXXXXXXXXX.",
    ".XXXXXXXXXXXXXX.",
    "..XXXXXXXXXXXX..",
    "....XXXXXXXX....",
    "......X..X......",
    ".......XX.......",
]

SPR_TANK = [
    "....XXXXXXXX....",
    "..XXXXXXXXXXXX..",
    ".XXXXXXXXXXXXXX.",
    "XXXXXXXXXXXXXXXX",
    "XXXX.XXXXXX.XXXX",
    "XXXXXXXXXXXXXXXX",
    ".XXXXXXXXXXXXXX.",
    "..XXXXXXXXXXXX..",
]

SPR_BOMB = [
    ".X.",
    "XXX",
    "XXX",
    "XXX",
    "X.X",
]

SPR_HANGAR = [
    "....XXXXXXXXXXXXXX....",
    "..XXXXXXXXXXXXXXXXXX..",
    ".XXXXXXXXXXXXXXXXXXXX.",
    "XXXXXXXXXXXXXXXXXXXXXX",
    "XX..................XX",
    "XX....XXXXXXXXXX....XX",
    "XX....XXXXXXXXXX....XX",
    "XX....XXXXXXXXXX....XX",
    "XX....XXXXXXXXXX....XX",
    "XXXXXXXXXXXXXXXXXXXXXX",
]

SPR_TENT = [
    "......XX......",
    ".....XXXX.....",
    "....XXXXXX....",
    "...XXX..XXX...",
    "..XXX....XXX..",
    ".XXX......XXX.",
    "XXX........XXX",
    "XXXXXXXXXXXXXX",
]

# explosion animation, 4 keyframes
SPR_BLAST = [
    [
        ".....",
        ".XXX.",
        ".XXX.",
        ".XXX.",
        ".....",
    ],
    [
        "..X.X..",
        ".XXXXX.",
        "XXXXXXX",
        ".XXXXX.",
        "..X.X..",
    ],
    [
        "X..X..X..",
        ".XXX.XXX.",
        "XXX...XXX",
        ".XX...XX.",
        "XXX...XXX",
        ".XXX.XXX.",
        "X..X..X..",
    ],
    [
        "X...X...X",
        "..X...X..",
        ".X..X..X.",
        "X..X.X..X",
        ".X..X..X.",
        "..X...X..",
        "X...X...X",
    ],
]

# ---- 3x5 pixel font ---------------------------------------------------
FONT = {
    "A": ["XXX", "X.X", "XXX", "X.X", "X.X"],
    "B": ["XX.", "X.X", "XX.", "X.X", "XX."],
    "C": ["XXX", "X..", "X..", "X..", "XXX"],
    "D": ["XX.", "X.X", "X.X", "X.X", "XX."],
    "E": ["XXX", "X..", "XX.", "X..", "XXX"],
    "F": ["XXX", "X..", "XX.", "X..", "X.."],
    "G": ["XXX", "X..", "X.X", "X.X", "XXX"],
    "H": ["X.X", "X.X", "XXX", "X.X", "X.X"],
    "I": ["XXX", ".X.", ".X.", ".X.", "XXX"],
    "J": ["XXX", "..X", "..X", "X.X", "XXX"],
    "K": ["X.X", "X.X", "XX.", "X.X", "X.X"],
    "L": ["X..", "X..", "X..", "X..", "XXX"],
    "M": ["X.X", "XXX", "XXX", "X.X", "X.X"],
    "N": ["XX.", "X.X", "X.X", "X.X", "X.X"],
    "O": ["XXX", "X.X", "X.X", "X.X", "XXX"],
    "P": ["XXX", "X.X", "XXX", "X..", "X.."],
    "Q": ["XXX", "X.X", "X.X", "XXX", "..X"],
    "R": ["XXX", "X.X", "XX.", "X.X", "X.X"],
    "S": ["XXX", "X..", "XXX", "..X", "XXX"],
    "T": ["XXX", ".X.", ".X.", ".X.", ".X."],
    "U": ["X.X", "X.X", "X.X", "X.X", "XXX"],
    "V": ["X.X", "X.X", "X.X", "X.X", ".X."],
    "W": ["X.X", "X.X", "XXX", "XXX", "X.X"],
    "X": ["X.X", "X.X", ".X.", "X.X", "X.X"],
    "Y": ["X.X", "X.X", ".X.", ".X.", ".X."],
    "Z": ["XXX", "..X", ".X.", "X..", "XXX"],
    "0": ["XXX", "X.X", "X.X", "X.X", "XXX"],
    "1": [".X.", "XX.", ".X.", ".X.", "XXX"],
    "2": ["XXX", "..X", "XXX", "X..", "XXX"],
    "3": ["XXX", "..X", "XXX", "..X", "XXX"],
    "4": ["X.X", "X.X", "XXX", "..X", "..X"],
    "5": ["XXX", "X..", "XXX", "..X", "XXX"],
    "6": ["XXX", "X..", "XXX", "X.X", "XXX"],
    "7": ["XXX", "..X", "..X", "..X", "..X"],
    "8": ["XXX", "X.X", "XXX", "X.X", "XXX"],
    "9": ["XXX", "X.X", "XXX", "..X", "XXX"],
    ".": ["...", "...", "...", "...", "X.."],
    ",": ["...", "...", "...", ".X.", "X.."],
    "-": ["...", "...", "XXX", "...", "..."],
    "+": ["...", ".X.", "XXX", ".X.", "..."],
    "%": ["X.X", "..X", ".X.", "X..", "X.X"],
    ":": ["...", ".X.", "...", ".X.", "..."],
    "!": [".X.", ".X.", ".X.", "...", ".X."],
    "/": ["..X", "..X", ".X.", "X..", "X.."],
    "'": [".X.", ".X.", "...", "...", "..."],
    " ": ["...", "...", "...", "...", "..."],
}

# distant, hazed skyline furniture
SPR_TREE = [
    "..XX..",
    "..XX..",
    ".XXX..",
    "..XXX.",
    "..XX..",
    ".XXX..",
    "..XX..",
    "..XX..",
    "..XXX.",
    "..XX..",
    ".XXX..",
    "..XX..",
    "..XX..",
    ".XXXX.",
    "XXXXXX",
]

SPR_SPIRE = [
    "...X.....",
    "..XX.....",
    "..XXX....",
    ".XXXXX...",
    ".XX.XX...",
    ".XX.XX...",
    ".XXXXX...",
    ".XX.XX...",
    ".XXXXX...",
    "XXXXXXX..",
    "XXX.XXX..",
    "XXXXXXX..",
    "XXX.XXX..",
    "XXXXXXX..",
    "XXXXXXXX.",
    "XXXXXXXX.",
    "XXXXXXXX.",
    "XXXXXXXXX",
    "XXXXXXXXX",
]

SPR_STUMP = [
    ".XX..",
    "XXX.X",
    ".XXXX",
    ".XXX.",
    "XXXXX",
]


# ---------------------------------------------------------------------
# If a Kotlin source exists, it wins: the shipped .kt is parsed and the
# values above are replaced.  The preview can therefore never drift from
# the game.  See mock/ktparse.py.
# ---------------------------------------------------------------------
def _sync_from_kotlin():
    try:
        import ktparse
    except ImportError:
        return None
    import sys
    return ktparse.apply_to(sys.modules[__name__])


KT_SYNC = _sync_from_kotlin()
