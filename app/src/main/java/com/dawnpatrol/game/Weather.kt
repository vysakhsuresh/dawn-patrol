package com.dawnpatrol.game

/**
 * The weather over a sector.
 *
 * Free replay variety, and the good kind: it changes HOW you fly rather
 * than what the numbers are. A headwind makes the return leg the problem.
 * A crosswind means bombs no longer go where the nose points. Fog blinds
 * the gunners and you equally. A storm will not let you hold a line.
 *
 * Chosen from the SECTOR, not rolled: the same ground has the same weather
 * every time you fly it, so "the fog over Bapaume Wood" is a place you
 * remember rather than a die roll you resent. Audit A33 proves every state
 * still leaves a clearable, survivable sortie.
 */
object Weather {
    const val CLEAR = 0
    const val HEADWIND = 1
    const val CROSSWIND = 2
    const val FOG = 3
    const val STORM = 4

    private val NAME = arrayOf("CLEAR", "HEADWIND", "CROSSWIND", "FOG", "STORM")

    fun name(w: Int) = NAME[w.coerceIn(0, NAME.size - 1)]

    /**
     * Weather for a sector. Weighted so CLEAR is the common case - every
     * sortie being eventful is the same as none of them being eventful.
     */
    fun forSector(index: Int): Int {
        val h = World.hash32(index * 7717 + 991)
        return when ((h % 16u).toInt()) {
            in 0..7 -> CLEAR
            8, 9 -> HEADWIND
            10, 11 -> CROSSWIND
            12, 13 -> FOG
            else -> STORM
        }
    }

    fun speedScale(w: Int) = if (w == HEADWIND) Tune.WIND_HEAD else 1f
    fun bombDrift(w: Int) = if (w == CROSSWIND) Tune.WIND_CROSS else 0f
    fun aimScale(w: Int) = if (w == FOG) Tune.FOG_AIM else 1f
    fun gust(w: Int, ticks: Float): Float =
        if (w != STORM) 0f
        else (kotlin.math.sin((ticks * 0.031f).toDouble()).toFloat() +
              0.6f * kotlin.math.sin((ticks * 0.077f).toDouble()).toFloat()) * Tune.GUST_V
}
