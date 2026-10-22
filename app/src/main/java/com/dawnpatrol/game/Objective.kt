package com.dawnpatrol.game

/**
 * What this particular sortie is FOR.
 *
 * Standing orders are a cumulative tally of what you happened to do.
 * An objective is a thing the squadron wants from THIS take-off, named on
 * the briefing before you start, which changes how you fly the next two
 * minutes. Chosen from the sector so the same ground always asks the same
 * thing of you.
 */
object Objective {
    const val SWEEP = 0      // no special orders: shoot what you like
    const val STRIKE = 1     // destroy N depots
    const val PHOTO = 2      // fly low and level over the objective, guns cold
    const val CULL = 3       // down N scouts
    const val SILENCE = 4    // destroy N guns
    const val COUNT = 5

    private val NAME = arrayOf("SWEEP", "STRIKE", "PHOTO RUN", "CULL", "SILENCE")

    fun name(o: Int) = NAME[o.coerceIn(0, NAME.size - 1)]

    fun target(o: Int, rank: Int): Int = when (o) {
        STRIKE -> 2 + rank / 3
        CULL -> 2 + rank / 3
        SILENCE -> 3 + rank / 2
        PHOTO -> 220                 // rows flown low, level and cold
        else -> 0
    }

    fun brief(o: Int, n: Int): String = when (o) {
        STRIKE -> "BOMB $n DEPOTS"
        PHOTO -> "$n LOW - GUNS COLD"
        CULL -> "DOWN $n SCOUTS"
        SILENCE -> "SILENCE $n GUNS"
        else -> "SWEEP THE SECTOR"
    }

    /** Weighted so a plain sweep is common - every sortie being special
     *  is the same as none of them being special. */
    fun forSector(index: Int): Int {
        val h = World.hash32(index * 3607 + 541)
        return when ((h % 12u).toInt()) {
            in 0..4 -> SWEEP
            5, 6 -> STRIKE
            7, 8 -> SILENCE
            9, 10 -> CULL
            else -> PHOTO
        }
    }
}
