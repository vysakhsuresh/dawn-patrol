package com.dawnpatrol.game

/**
 * Standing orders, and the rank ladder they feed.
 *
 * WHY THIS EXISTS. Before it, the only thing a sortie could give you was a
 * number that vanished when you died. There was no reason to start, and no
 * reason to keep going once a run had gone badly - a bad first minute made
 * the whole sortie pointless, so the right move was to crash and restart.
 * That is the opposite of what you want a player to feel.
 *
 * Orders fix that by making EVERY sortie bank something. Progress is
 * cumulative across sorties, never reset, so a terrible run still moves
 * three bars. Three orders at a time; clear all three and you are promoted
 * and given a harder three. You are always a measurable distance from the
 * next thing.
 *
 * Deliberately NOT here: daily timers, streak punishments, anything that
 * makes someone feel bad for not playing. The pull should be "I was two
 * depots away", not "I will lose my streak".
 *
 * Free of android.*, so Audit A22 can prove the ladder never stalls.
 */
object Orders {

    // ---- what an order can ask for -------------------------------------
    const val DEPOTS = 0
    const val GUNS = 1
    const val BALLOONS = 2
    const val SCOUTS = 3
    const val TANKS = 4
    const val LOW_ROWS = 5      // rows flown below Tune.LOW_ALT
    const val GRAZES = 6        // flak that burst close and missed
    const val CHAIN = 7         // best kill chain reached (a high-water mark)
    const val ROWS = 8          // rows flown
    const val SCORE = 9
    const val TYPES = 10

    /** Base target at rank 0, before the per-rank growth below. */
    //                            D  G  B  S  T  LOW  GZ  CH   ROWS   SCORE
    private val BASE = intArrayOf(3, 6, 4, 4, 3, 600, 4,  3,  6500, 11000)

    // ROWS and SCORE are the BACKSTOP slot - the one that guarantees a
    // sortie which went badly still moves a bar. At 2500 rows it filled in a
    // single sortie and then sat dead for the rest of the rank, which Audit
    // A23 caught: 2 of 12 sorties banked nothing because the backstop was
    // already full and the player's kills were not the type being asked
    // for. These targets are sized to outlast the kill orders, so the slot
    // is open for the whole rank - which is its entire job.
    /** How much each one grows per rank, as a fraction of its base. */
    private val GROWTH = floatArrayOf(
        0.55f, 0.55f, 0.55f, 0.6f, 0.5f, 0.45f, 0.4f, 0.22f, 0.25f, 0.3f)

    private val LABEL = arrayOf(
        "DEPOTS", "GUNS", "BALLOONS", "SCOUTS", "TANKS",
        "LOW ROWS", "GRAZES", "CHAIN", "ROWS", "SCORE")

    /** A high-water mark, not a tally - progress is the best ever reached. */
    fun isBest(type: Int) = type == CHAIN

    fun label(type: Int) = LABEL[type]

    val RANKS = arrayOf(
        "SERGEANT", "FLIGHT SGT", "2ND LIEUT", "LIEUTENANT",
        "CAPTAIN", "MAJOR", "ACE", "ACE OF ACES")

    fun rankName(rank: Int) = RANKS[rank.coerceIn(0, RANKS.size - 1)]

    /** The ladder has a top. Past it, orders keep growing and the name stays. */
    fun isTopRank(rank: Int) = rank >= RANKS.size - 1

    fun target(type: Int, rank: Int): Int {
        val t = BASE[type] * (1f + GROWTH[type] * rank)
        // round to something a player can hold in their head
        val step = when {
            t >= 2000 -> 500
            t >= 400 -> 100
            t >= 40 -> 5
            else -> 1
        }
        return (Math.round(t / step) * step).coerceAtLeast(step)
    }

    /**
     * The three orders for a rank, chosen deterministically from the rank
     * itself so they are the same every time the game is opened, and so the
     * audit can walk the whole ladder.
     *
     * CHAIN and SCORE are held back until rank 2: a new pilot being told to
     * "reach a 3 kill chain" before they can reliably hit anything is a wall,
     * not a goal.
     */
    fun forRank(rank: Int): IntArray {
        val out = IntArray(3)

        // ONE SLOT IS ALWAYS A SORTIE-WIDE ORDER - rows flown, or score.
        //
        // Audit A23 caught the whole point of this system failing: with
        // three kill-type orders, a sortie that went badly in the first ten
        // seconds banked literally nothing, which is precisely the "that run
        // was a waste, why did I bother" feeling orders exist to remove. A
        // rows-or-score slot means every single take-off moves a bar, even a
        // disastrous one. Below rank 2 it is always ROWS, because a new
        // pilot chasing a score target before they can reliably hit anything
        // is a wall.
        out[0] = if (rank < 2) ROWS
                 else if (World.hash32(rank * 31 + 5) % 2u == 0u) ROWS else SCORE

        val pool = ArrayList<Int>()
        for (t in 0 until TYPES) {
            if (t == ROWS || t == SCORE) continue
            if (rank < 2 && (t == CHAIN || t == GRAZES)) continue
            pool.add(t)
        }
        for (i in 1 until 3) {
            val h = World.hash32(rank * 7919 + i * 613 + 13)
            out[i] = pool.removeAt((h % pool.size.toUInt()).toInt())
        }
        out.sort()
        return out
    }
}

/** One live order: what it asks, how far along, and whether it is done. */
class Order(val type: Int, val target: Int) {
    var progress = 0
        private set

    val done: Boolean get() = progress >= target

    /** Returns true if this call completed the order. */
    fun bump(n: Int): Boolean {
        if (done || n <= 0) return false
        progress = minOf(target, progress + n)
        return done
    }

    /** For high-water-mark orders: keep the best ever seen. */
    fun reach(v: Int): Boolean {
        if (done || v <= progress) return false
        progress = minOf(target, v)
        return done
    }

    fun restore(p: Int) { progress = p.coerceIn(0, target) }

    /**
     * Thousands are abbreviated, because the line has to share its row with
     * a progress bar: "SCORE 23400/14300" ran straight through the bar at
     * the higher ranks, and a score order's exact units are not what the
     * player is reading there - the bar is.
     */
    private fun short(v: Int): String =
        if (v >= 1000) (v / 1000).toString() + "K" else v.toString()

    fun text(): String = Orders.label(type) + " " + short(progress) + "/" + short(target)
}
