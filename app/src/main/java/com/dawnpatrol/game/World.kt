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

    private val SLOT_TABLE = intArrayOf(
        AA_GUN, TRENCH, SANDBAG, AA_GUN, DEPOT, TRENCH, WRECK, AA_GUN,
        SANDBAG, BALLOON, TRENCH, DEPOT, TANK, SANDBAG, LIGHT, TRENCH
    )

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
        val typ = SLOT_TABLE[(h and 15u).toInt()]
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
