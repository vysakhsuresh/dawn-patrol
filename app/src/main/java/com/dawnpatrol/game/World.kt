package com.dawnpatrol.game

import kotlin.math.floor

/**
 * Procedural world - ported 1:1 from mock/world.py, verified there by
 * mock/audit.py (scroll stability, seed-source independence, floor()
 * correctness across worldX = 0, terrain continuity: 11/11 checks passing
 * over 24,000 simulated frames).
 *
 * LESSON #1 (paid for on Cat on a Fence): every procedural element is
 * seeded from its ABSOLUTE world position, never from an index counted off
 * the screen edge, and the cell index and the within-cell offset are
 * derived from the SAME floor() call so they can never disagree by a
 * rounding error.
 *
 * LESSON #4: unary minus binds tighter than '%'. We never use '%' on a
 * value that can be negative - [cell] uses floor() instead, and hash32
 * uses UInt throughout so its internal shifts are logical (unsigned), the
 * same as Python's shifts on a value already masked non-negative. A plain
 * Kotlin `Int shr` here would sign-extend and silently diverge from the
 * audited Python behaviour whenever a hash's top bit is set - exactly the
 * class of bug lesson #4 is about.
 */
object World {

    // ---- feature types, matching mock/world.py's SLOT_TABLE order ----
    const val AA_GUN = 0
    const val DEPOT = 1
    const val BALLOON = 2
    const val LIGHT = 3
    const val TRENCH = 4
    const val WRECK = 5
    const val TANK = 6
    const val SANDBAG = 7

    // ---- authored set pieces ---------------------------------------------
    // SLOT_TABLE alone is texture, not encounters: sixteen entries
    // shuffled give every sortie the same rhythm. These are hand-authored
    // runs of ground that the generator SEQUENCES - twelve of them
    // shuffled is hundreds of distinct sorties, where pure procedural
    // goes samey and pure handmade runs out.
    //
    // Each chunk is a list of slot types laid down consecutively. They
    // are deliberately short: a chunk is a bar of music, not a level.
    private val CHUNKS: Array<IntArray> = arrayOf(
        // flak gauntlet - three guns, nowhere to hide
        intArrayOf(AA_GUN, SANDBAG, AA_GUN, SANDBAG, AA_GUN),
        // balloon line - the only way is over
        intArrayOf(BALLOON, TRENCH, BALLOON, TRENCH, BALLOON),
        // supply dump - bombs pay here
        intArrayOf(DEPOT, SANDBAG, DEPOT, TRENCH, DEPOT),
        // searchlight alley - murder at night, harmless by day
        intArrayOf(LIGHT, TRENCH, LIGHT, TRENCH, LIGHT),
        // armour in the open
        intArrayOf(TANK, TANK, SANDBAG, TANK),
        // the quiet stretch, so the loud ones land
        intArrayOf(TRENCH, WRECK, TRENCH, SANDBAG, TRENCH, WRECK),
        // a gun dug in behind its own balloon
        intArrayOf(BALLOON, AA_GUN, SANDBAG, AA_GUN, BALLOON),
        // railhead: depots behind armour
        intArrayOf(TANK, DEPOT, DEPOT, TANK),
        // the battery - four guns and a light
        intArrayOf(AA_GUN, AA_GUN, LIGHT, AA_GUN, AA_GUN),
        // picket line
        intArrayOf(SANDBAG, TRENCH, SANDBAG, TRENCH, SANDBAG, TRENCH),
        // one fat target, well defended
        intArrayOf(AA_GUN, DEPOT, AA_GUN),
        // a wrecked sector somebody already fought over
        intArrayOf(WRECK, WRECK, TRENCH, WRECK, SANDBAG)
    )

    /**
     * The chunk covering absolute slot k, and how far into it we are.
     *
     * Seeded from the absolute slot index exactly the way everything else
     * here is (lesson #1), so a chunk does not shift when you fly back
     * over it - Audit A1/A37 hold across chunk boundaries.
     */
    private fun chunkFor(k: Int): Pair<IntArray, Int> {
        // walk a deterministic chain of chunks from a coarse anchor, so
        // the chunk a slot belongs to never depends on the camera
        var start = floorDiv(k, 64) * 64
        var idx = 0
        var guard = 0
        while (guard < 64) {
            val c = CHUNKS[(hash32(start * 131 + 7) % CHUNKS.size.toUInt()).toInt()]
            if (k < start + c.size) return c to (k - start)
            start += c.size
            idx++
            guard++
        }
        return CHUNKS[0] to 0
    }

    private fun floorDiv(a: Int, b: Int): Int =
        if (a >= 0) a / b else -(((-a) + b - 1) / b)

    private val SLOT_TABLE = intArrayOf(
        AA_GUN, TRENCH, SANDBAG, AA_GUN, DEPOT, TRENCH, WRECK, AA_GUN,
        SANDBAG, BALLOON, TRENCH, DEPOT, TANK, SANDBAG, LIGHT, TRENCH
    )

    // ---- sector names ----------------------------------------------------
    // "SECTOR 7" reads like a spreadsheet cell. A name costs nothing,
    // is deterministic from the index, and turns the front from a number
    // into a place you have been.
    private val PLACE = arrayOf(
        "VERDUN", "BAPAUME", "ARRAS", "YPRES", "SOMME", "AMIENS", "CAMBRAI",
        "MESSINES", "LOOS", "ALBERT", "PERONNE", "LENS", "VIMY", "MONS",
        "SEDAN", "MARNE", "AISNE", "OISE", "SCARPE", "ANCRE")
    private val FEATURE = arrayOf(
        "RIDGE", "WOOD", "SALIENT", "CROSSING", "FARM", "QUARRY", "CUTTING",
        "MARSH", "SPUR", "COPSE", "REDOUBT", "HOLLOW")

    fun sectorName(index: Int): String {
        val h = hash32(index * 2654435761.toInt() + 17)
        val a = PLACE[(h % PLACE.size.toUInt()).toInt()]
        val b = FEATURE[((h shr 11) % FEATURE.size.toUInt()).toInt()]
        return a + " " + b
    }

    /** Deterministic 32-bit hash. UInt so every shift below is logical. */
    fun hash32(n: Int): UInt {
        var h = n.toUInt() * 0x27D4EB2Du
        h = h xor (h shr 15)
        h *= 0x85EBCA6Bu
        h = h xor (h shr 13)
        h *= 0xC2B2AE35u
        h = h xor (h shr 16)
        return h
    }

    /** floor division - correct for negative wx, unlike (wx / period).toInt(). */
    fun cell(wx: Double, period: Double): Int = floor(wx / period).toInt()

    /** Within-cell fraction derived from the SAME c the caller already has. */
    fun frac(wx: Double, period: Double, c: Int): Double = (wx - c * period) / period

    // ---- terrain -----------------------------------------------------
    private val TERR_BASE = Art.HORIZON.toDouble()
    private val TERR_AMP = Art.TERR_AMP.toDouble()

    private fun terrPoint(c: Int): Double {
        val h = hash32(c * 2 + 1)
        val j = ((h shr 4) and 0xFFu).toInt()
        // integer division on purpose - matches mock/world.py's `//`, which
        // is what the A5 audit's "terrain stays inside its band" checks
        return TERR_BASE + (j * (Art.TERR_AMP * 2 + 1) / 256) - TERR_AMP
    }

    /** Ground row at absolute world x. ONE floor() call feeds both c and t. */
    fun groundY(wx: Double): Double {
        val period = Art.TERRAIN_CELL.toDouble()
        val c = cell(wx, period)
        var t = frac(wx, period, c)
        val a = terrPoint(c)
        val b = terrPoint(c + 1)
        t = t * t * (3 - 2 * t) // smoothstep - no hard corner at the cell edge
        return a + (b - a) * t
    }

    // ---- feature slots -------------------------------------------------
    data class Slot(val type: Int, val worldX: Double, val hash: UInt)

    /** Feature in absolute slot k. */
    fun slot(k: Int): Slot {
        val h = hash32(k * 7919 + 13)
        // The authored chunk decides WHAT is here; the hash still decides
        // exactly where in the cell it stands, so the texture that made
        // the old generator feel hand-placed survives.
        val (chunk, i) = chunkFor(k)
        val typ = chunk[i.coerceIn(0, chunk.size - 1)]
        val off = ((h shr 6) and 0x3Fu).toInt() * Art.SLOT_W / 64
        return Slot(typ, k.toDouble() * Art.SLOT_W + off, h)
    }

    /** Absolute slot indices whose feature can touch the view. */
    fun visibleSlots(camX: Double, viewW: Double, margin: Double = 40.0): IntRange {
        val period = Art.SLOT_W.toDouble()
        val k0 = cell(camX - margin, period)
        val k1 = cell(camX + viewW + margin, period)
        return k0..k1
    }

    /** Depth to subtract from groundY near a trench at tx. 0 outside. */
    fun trenchCut(wx: Double, tx: Double, h: UInt): Double {
        val half = 11.0 + (h and 3u).toDouble()
        val d = wx - tx
        if (kotlin.math.abs(d) > half) return 0.0
        return 4.0 + 2.0 * (1.0 - kotlin.math.abs(d) / half)
    }

    /** Ground row including any trench cuts that overlap wx. */
    fun surfaceY(wx: Double, trenches: List<Pair<Double, UInt>>): Double {
        var g = groundY(wx)
        for ((tx, h) in trenches) g += trenchCut(wx, tx, h)
        return g
    }
}
