package com.dawnpatrol.game

/**
 * Sprite grids and tuning constants - lifted 1:1 from mock/artdata.py.
 *
 * This is the reverse of the usual pipeline: normally shipped code drives
 * the preview (see mock/ktparse.py). This file is the FIRST generation, so
 * it goes the other way once - transcribed by script from the sprites that
 * were already rendered, looked at, and approved as PNG frames. From here
 * on, mock/ktparse.py parses THIS file; edit sprites here, not in Python.
 */
object Art {
    // ---- logical canvas ------------------------------------------------
    const val LW = 400
    const val LH = 700
    const val PX = 4
    const val GW = 100
    const val GH = 175

    // ---- world layout (pixel-grid rows) ---------------------------------
    const val HUD_H = 20
    const val SKY_TOP = 20
    const val HORIZON = 126
    const val CEILING = 24
    const val PLAYER_X = 26

    // ---- flight model (per 60Hz frame, pixel-grid units) ----------------
    const val CLIMB_ACC = 0.055f
    const val GRAVITY = 0.048f
    const val VY_MAX_UP = 1.05f
    const val VY_MAX_DN = 1.55f
    const val SPEED_MIN = 0.95f
    const val SPEED_MAX = 1.8f

    // ---- procedural world ------------------------------------------------
    const val SLOT_W = 30
    const val TERRAIN_CELL = 8
    const val TERR_AMP = 5

    // ---- sprites: Array<String> of 'X' / '.', run-length batched into
    // canvas.drawRect per row by GameView - no image assets. -----------------
    val SPR_PLANE = arrayOf(
        ".....XXXXXXXXXX.......",
        "......X......X........",
        "......X......X........",
        ".XX.......X.XXXXXXX...",
        "XXXXXXXXXXXXXXXXXXXX.X",
        "..XXXXXXXXXXXXXXXXX...",
        ".....XXXXXXXXXX.....X.",
        "......X.....X.........",
        ".....XXX...XXX........"
    )

    val SPR_PLANE_CLIMB = arrayOf(
        ".....XXXXXXXXXX.......",
        "......X......X........",
        "......X......X.XXXX...",
        "..........X.XXXXXXXX.X",
        ".XX.XXXXXXXXXXXXXXX...",
        "XXXXXXXXXXXXXXX.....X.",
        "..XX.XXXXXXXXXX.......",
        "......X.....X.........",
        ".....XXX...XXX........"
    )

    val SPR_PLANE_DIVE = arrayOf(
        ".....XXXXXXXXXX.......",
        "......X......X........",
        ".XX...X......X........",
        "XXXX......X.XXX.......",
        "..XXXXXXXXXXXXXXXXX...",
        "....XXXXXXXXXXXXXXXX.X",
        ".....XXXXXXXXXXXXXX...",
        "......X.....X.......X.",
        ".....XXX...XXX........"
    )

    val SPR_AA_GUN = arrayOf(
        "............XXX",
        "..........XXX..",
        "........XXX....",
        "......XXX......",
        "....XXXX.......",
        "...XXXXX.......",
        "..XXXXXXX......",
        "XXXX.XXXX......",
        ".X..XXXXX......",
        "..XXXXXXXX....."
    )

    val SPR_AA_WRECK = arrayOf(
        "...............",
        "...............",
        "...............",
        "...............",
        "......X........",
        "...XX.X........",
        "..XXXX.XXX.....",
        "XXXX.XX...XX...",
        ".X..XXX........",
        "..XXX.XXXX....."
    )

    val SPR_SEARCHLIGHT = arrayOf(
        "......XXXXX.",
        ".....XXXXXXX",
        "....XXX...XX",
        "...XXX....XX",
        "...XXX...XX.",
        "....XXXXXX..",
        ".....XX.....",
        "...XXXXXX...",
        "..XXXXXXXX.."
    )

    val SPR_DEPOT = arrayOf(
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
        "XXXXXXXXXXXXXXXXXX"
    )

    val SPR_DEPOT_WRECK = arrayOf(
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
        "XXXXXXXXXXXXXXXXXX"
    )

    val SPR_BALLOON = arrayOf(
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
        ".......XX......."
    )

    val SPR_TANK = arrayOf(
        "....XXXXXXXX....",
        "..XXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXX.",
        "XXXXXXXXXXXXXXXX",
        "XXXX.XXXXXX.XXXX",
        "XXXXXXXXXXXXXXXX",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX.."
    )

    // tiny roundel for the lives row
    val SPR_LIFE = arrayOf(
        "..X..",
        "XXXXX",
        "..X.."
    )

    val SPR_BOMB = arrayOf(
        ".X.",
        "XXX",
        "XXX",
        "XXX",
        "X.X"
    )

    val SPR_HANGAR = arrayOf(
        "....XXXXXXXXXXXXXX....",
        "..XXXXXXXXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXXXXXXXX.",
        "XXXXXXXXXXXXXXXXXXXXXX",
        "XX..................XX",
        "XX....XXXXXXXXXX....XX",
        "XX....XXXXXXXXXX....XX",
        "XX....XXXXXXXXXX....XX",
        "XX....XXXXXXXXXX....XX",
        "XXXXXXXXXXXXXXXXXXXXXX"
    )

    val SPR_TENT = arrayOf(
        "......XX......",
        ".....XXXX.....",
        "....XXXXXX....",
        "...XXX..XXX...",
        "..XXX....XXX..",
        ".XXX......XXX.",
        "XXX........XXX",
        "XXXXXXXXXXXXXX"
    )

    val SPR_TREE = arrayOf(
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
        "XXXXXX"
    )

    val SPR_STUMP = arrayOf(
        ".XX..",
        "XXX.X",
        ".XXXX",
        ".XXX.",
        "XXXXX"
    )

    val SPR_BLAST = arrayOf(
        arrayOf(
            ".....",
            ".XXX.",
            ".XXX.",
            ".XXX.",
            "....."
        ),
        arrayOf(
            "..X.X..",
            ".XXXXX.",
            "XXXXXXX",
            ".XXXXX.",
            "..X.X.."
        ),
        arrayOf(
            "X..X..X..",
            ".XXX.XXX.",
            "XXX...XXX",
            ".XX...XX.",
            "XXX...XXX",
            ".XXX.XXX.",
            "X..X..X.."
        ),
        arrayOf(
            "X...X...X",
            "..X...X..",
            ".X..X..X.",
            "X..X.X..X",
            ".X..X..X.",
            "..X...X..",
            "X...X...X"
        )
    )

    // ---- 3x5 pixel font --------------------------------------------------
    val FONT: Map<Char, Array<String>> = mapOf(
        'A' to arrayOf("XXX", "X.X", "XXX", "X.X", "X.X"),
        'B' to arrayOf("XX.", "X.X", "XX.", "X.X", "XX."),
        'C' to arrayOf("XXX", "X..", "X..", "X..", "XXX"),
        'D' to arrayOf("XX.", "X.X", "X.X", "X.X", "XX."),
        'E' to arrayOf("XXX", "X..", "XX.", "X..", "XXX"),
        'F' to arrayOf("XXX", "X..", "XX.", "X..", "X.."),
        'G' to arrayOf("XXX", "X..", "X.X", "X.X", "XXX"),
        'H' to arrayOf("X.X", "X.X", "XXX", "X.X", "X.X"),
        'I' to arrayOf("XXX", ".X.", ".X.", ".X.", "XXX"),
        'J' to arrayOf("XXX", "..X", "..X", "X.X", "XXX"),
        'K' to arrayOf("X.X", "X.X", "XX.", "X.X", "X.X"),
        'L' to arrayOf("X..", "X..", "X..", "X..", "XXX"),
        'M' to arrayOf("X.X", "XXX", "XXX", "X.X", "X.X"),
        'N' to arrayOf("XX.", "X.X", "X.X", "X.X", "X.X"),
        'O' to arrayOf("XXX", "X.X", "X.X", "X.X", "XXX"),
        'P' to arrayOf("XXX", "X.X", "XXX", "X..", "X.."),
        'Q' to arrayOf("XXX", "X.X", "X.X", "XXX", "..X"),
        'R' to arrayOf("XXX", "X.X", "XX.", "X.X", "X.X"),
        'S' to arrayOf("XXX", "X..", "XXX", "..X", "XXX"),
        'T' to arrayOf("XXX", ".X.", ".X.", ".X.", ".X."),
        'U' to arrayOf("X.X", "X.X", "X.X", "X.X", "XXX"),
        'V' to arrayOf("X.X", "X.X", "X.X", "X.X", ".X."),
        'W' to arrayOf("X.X", "X.X", "XXX", "XXX", "X.X"),
        'X' to arrayOf("X.X", "X.X", ".X.", "X.X", "X.X"),
        'Y' to arrayOf("X.X", "X.X", ".X.", ".X.", ".X."),
        'Z' to arrayOf("XXX", "..X", ".X.", "X..", "XXX"),
        '0' to arrayOf("XXX", "X.X", "X.X", "X.X", "XXX"),
        '1' to arrayOf(".X.", "XX.", ".X.", ".X.", "XXX"),
        '2' to arrayOf("XXX", "..X", "XXX", "X..", "XXX"),
        '3' to arrayOf("XXX", "..X", "XXX", "..X", "XXX"),
        '4' to arrayOf("X.X", "X.X", "XXX", "..X", "..X"),
        '5' to arrayOf("XXX", "X..", "XXX", "..X", "XXX"),
        '6' to arrayOf("XXX", "X..", "XXX", "X.X", "XXX"),
        '7' to arrayOf("XXX", "..X", "..X", "..X", "..X"),
        '8' to arrayOf("XXX", "X.X", "XXX", "X.X", "XXX"),
        '9' to arrayOf("XXX", "X.X", "XXX", "..X", "XXX"),
        '.' to arrayOf("...", "...", "...", "...", "X.."),
        ',' to arrayOf("...", "...", "...", ".X.", "X.."),
        '-' to arrayOf("...", "...", "XXX", "...", "..."),
        '+' to arrayOf("...", ".X.", "XXX", ".X.", "..."),
        '%' to arrayOf("X.X", "..X", ".X.", "X..", "X.X"),
        ':' to arrayOf("...", ".X.", "...", ".X.", "..."),
        '!' to arrayOf(".X.", ".X.", ".X.", "...", ".X."),
        '/' to arrayOf("..X", "..X", ".X.", "X..", "X.."),
        '\'' to arrayOf(".X.", ".X.", "...", "...", "..."),
        ' ' to arrayOf("...", "...", "...", "...", "...")
    )

    // ---- 8x8 ordered dither ---------------------------------------------
    // 65 density levels. Fine enough that a 10 percent stipple reads as haze
    // and not as a checkerboard - the mistake that made the first preview
    // pass look like grey mush. Indexed [ (y and 7)*8 + (x and 7) ].
    const val BAYER_MAX = 64
    val BAYER = intArrayOf(
         0, 32,  8, 40,  2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44,  4, 36, 14, 46,  6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
         3, 35, 11, 43,  1, 33,  9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47,  7, 39, 13, 45,  5, 37,
        63, 31, 55, 23, 61, 29, 53, 21
    )
}
