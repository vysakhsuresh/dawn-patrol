package com.dawnpatrol.game

import java.io.File

/**
 * A bench for aeroplane silhouettes.
 *
 * Candidates are drawn through the SHIPPED Fb, at the size they are actually
 * played at, against the sky AND against the solid-ink earth with the same
 * 1px paper halo the renderer uses - because a 1-bit shape that reads
 * beautifully on paper can vanish completely over ink, and a shape that
 * reads at 6x can be mush at 1x. Nothing here ships; the winner gets copied
 * into Art.kt and the audit takes over.
 *
 * Run: tools/planelab.sh
 */
object PlaneLab {

    // ---- candidates, all exactly 22 x 9 --------------------------------
    // nose to the RIGHT, col 21 is the propeller disc

    val CURRENT = arrayOf(
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

    /** B: a Camel - shorter wings, a deeper gap, a rounded cowl. */
    val CAMEL = arrayOf(
        "....XXXXXXXXXXXX......",
        ".......X......X.......",
        ".......X......X.......",
        "XXX........XXXXXXXX...",
        ".XXXXXXXXXXXXXXXXXXX.X",
        "..XXXXXXXXXXXXXXXXXX..",
        "....XXXXXXXXXXXX....X.",
        "......X......X........",
        ".....XXX...XXX........"
    )

    /** C: a Fokker Dr.I - three wings, the most unmistakable WW1 shape. */
    val TRIPLANE = arrayOf(
        "...XXXXXXXXXXXX.......",
        "......X.....X.........",
        "..XXXXXXXXXXXXXX......",
        "XXX...X.....X.XXXXX...",
        ".XXXXXXXXXXXXXXXXXXX.X",
        "...XXXXXXXXXXXXXXXX...",
        "..XXXXXXXXXXXXXX....X.",
        "......X.....X.........",
        ".....XXX...XXX........"
    )

    /** D: a parasol monoplane - one wing, floating clear of the fuselage. */
    val PARASOL = arrayOf(
        "..XXXXXXXXXXXXXX......",
        "......X.....X.........",
        "......X.....X.........",
        "XXX.........XXXXXX....",
        ".XXXXXXXXXXXXXXXXXXX.X",
        "..XXXXXXXXXXXXXXXXX...",
        "....X.........X.....X.",
        "...X...........X......",
        "..XXX.........XXX....."
    )

    /** E: a two-seater - two cockpits, a heavier tail. It carries bombs. */
    val TWOSEAT = arrayOf(
        "...XXXXXXXXXXXXX......",
        "......X.......X.......",
        "......X.......X.......",
        "XXXX.......XXXXXXXX...",
        ".XXXXXXXXXXXXXXXXXXX.X",
        "..XX.XX.XXXXXXXXXXXX..",
        "...XXXXXXXXXXXXX....X.",
        "......X.......X.......",
        ".....XXX....XXX......."
    )

    /**
     * C2: the triplane again, with the fuselage cut to ONE row so the lower
     * wing is its own line instead of a thickening of the belly. Every
     * candidate above shares the same flaw - fuselage and lower wing on
     * adjacent filled rows merge into a slab at 1x - and the triplane only
     * survived it because the two wings above were still clean.
     */
    val TRIPLANE2 = arrayOf(
        "...XXXXXXXXXXXX.......",
        "......X.....X.........",
        "..XXXXXXXXXXXXXX......",
        "XXX...X.....X.XXXXX...",
        ".XXXXXXXXXXXXXXXXXXX.X",
        "..XXXXXXXXXXXXXX....X.",
        "......X.....X.........",
        "......X.....X.........",
        ".....XXX...XXX........"
    )

    /** D2: the parasol with a taller, tidier undercarriage. */
    val PARASOL2 = arrayOf(
        "..XXXXXXXXXXXXXX......",
        "......X.....X.........",
        "......X.....X.........",
        "XXX.........XXXXXX....",
        ".XXXXXXXXXXXXXXXXXXX.X",
        "..XXXXXXXXXXXXXXXX..X.",
        "....X.......X.........",
        "....X.......X.........",
        "...XXX.....XXX........"
    )

    val CANDIDATES = listOf(
        "CURRENT" to CURRENT,
        "CAMEL" to CAMEL,
        "TRIPLANE" to TRIPLANE,
        "PARASOL" to PARASOL,
        "TWOSEAT" to TWOSEAT,
        "TRIPLANE2" to TRIPLANE2,
        "PARASOL2" to PARASOL2
    )

    // ---- attitudes -------------------------------------------------------
    /**
     * Pitch by shearing COLUMNS, never by rotating.
     *
     * Rotation on a 1-bit grid closes the 1px gaps that make a biplane read
     * as a biplane - the wings merge into a blob. A column shear keeps every
     * vertical relationship intact and just tilts the whole machine, which
     * is also honest: the wings and the undercarriage pitch with the
     * fuselage, they do not stay level while the body bends.
     */
    fun shear(spr: Array<String>, dir: Int): Array<String> {
        val w = spr.maxOf { it.length }
        val h = spr.size
        val out = Array(h) { CharArray(w) { '.' } }
        for (r in 0 until h) for (c in 0 until w) {
            if (c >= spr[r].length || spr[r][c] != 'X') continue
            // nose end lifts (climb) or drops (dive) by one row
            val lift = if (c >= w * 2 / 3) 1 else if (c < w / 3) -1 else 0
            val rr = (r - dir * lift).coerceIn(0, h - 1)
            out[rr][c] = 'X'
        }
        return Array(h) { String(out[it]) }
    }

    // ---- sheet -----------------------------------------------------------
    private fun cell(fb: Fb, spr: Array<String>, x: Int, y: Int, onInk: Boolean) {
        if (onInk) fb.rect(x - 2, y - 2, 26, 13, 1)
        fb.spriteClear(spr, x, y, 1, if (onInk) 1 else 0)
        fb.sprite(spr, x, y, if (onInk) 0 else 1)
    }

    private fun zoom(fb: Fb, spr: Array<String>, x: Int, y: Int, z: Int) {
        for (r in spr.indices) for (c in spr[r].indices)
            if (spr[r][c] == 'X') fb.rect(x + c * z, y + r * z, z, z, 1)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = if (args.isNotEmpty()) args[0] else "out/kt"
        File(dir).mkdirs()

        val rowH = 78
        val fb = Fb(360, 24 + CANDIDATES.size * rowH)
        fb.clear(0)

        var y = 16
        for ((name, spr) in CANDIDATES) {
            // integrity first - the audit's rule, applied before I look
            val w = spr.maxOf { it.length }
            val rows = spr.indices.filter { spr[it].contains('X') }
            val ok = w == Tune.PLANE_W && spr.size == Tune.PLANE_H &&
                rows.min() == 0 && rows.max() == Tune.PLANE_H - 1
            val prof = spr.indices.let { _ ->
                (0 until w).map { c -> spr.indices.lastOrNull { r ->
                    c < spr[r].length && spr[r][c] == 'X' } ?: -1 }
            }
            println(String.format("%-9s %dx%d %s  wheels at rows %s",
                name, w, spr.size, if (ok) "fits" else "DOES NOT FIT 22x9",
                prof.filter { it >= 0 }.max()))

            fb.text(name, 4, y - 10, 1, 1, 1)

            // 6x, so the drawing itself can be criticised
            zoom(fb, spr, 4, y, 6)

            // ...and at 1x, which is the only size that matters, on paper
            // and on the ink earth, level / climb / dive
            var x = 150
            for ((label, s) in listOf(
                "LVL" to spr, "UP" to shear(spr, 1), "DN" to shear(spr, -1))) {
                fb.text(label, x, y - 8, 1, 1, 1)
                cell(fb, s, x, y + 2, false)
                cell(fb, s, x, y + 16, true)
                // 3x, the middle ground
                zoom(fb, s, x, y + 32, 3)
                x += 50
            }
            // the same shape mirrored, which is how a scout is drawn: if
            // the player and the enemy share one silhouette, a head-on pass
            // is two identical smudges closing on each other
            fb.text("VS", x, y - 8, 1, 1, 1)
            val mir = Array(spr.size) { r -> spr[r].reversed() }
            cell(fb, spr, x, y + 2, false)
            cell(fb, mir, x + 26, y + 2, false)
            zoom(fb, mir, x, y + 16, 3)
            y += rowH
        }

        val out = StringBuilder()
        out.append("P3\n${fb.w} ${fb.h}\n255\n")
        for (yy in 0 until fb.h) {
            for (xx in 0 until fb.w) {
                val c = if (fb.p[yy * fb.w + xx].toInt() != 0) 0x1A1410 else 0xE8D9B5
                out.append((c shr 16) and 0xFF).append(' ')
                    .append((c shr 8) and 0xFF).append(' ')
                    .append(c and 0xFF).append(' ')
            }
            out.append('\n')
        }
        File("$dir/planes.ppm").writeText(out.toString())
        println("wrote $dir/planes.ppm")
    }
}
