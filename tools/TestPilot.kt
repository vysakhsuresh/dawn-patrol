package com.dawnpatrol.game

/**
 * A competent test pilot, shared by the audit and the frame dumper.
 *
 * NOTHING IN THE APP USES THIS. It exists so that questions of the form
 * "would a player ever actually see X?" can be answered with a number
 * instead of a hunch. The earlier tools flew a constant dive or a fixed
 * altitude and were shot down inside ~1300 rows, which made every such
 * answer pessimistic - and that pessimism was invisible, because a crude
 * pilot dying early looks exactly like content being hard to reach.
 *
 * It does the three things that keep you alive:
 *   - cruise above the flak envelope, where a ground gun cannot reach
 *   - break VERTICALLY when a shell is committed (a time-fuzed intercept
 *     is solved against your velocity, so changing it is the whole dodge)
 *   - go under a scout rather than trade head-on
 * ...and it never trades a dodge for the ground or the ceiling.
 */
object TestPilot {

    /** @param bias rows of cruise-altitude offset, to vary otherwise
     *              identical sorties through the same deterministic world. */
    fun cruise(s: Sim, bias: Float = 0f) {
        val alt = s.altitude()
        var want = s.py > Tune.CEIL_ROW + 4f + bias

        val shell = s.shots.filter { it.alive && it.kind == K_SHELL }
            .minByOrNull { it.fuse }
        if (shell != null && shell.fuse < 40f) want = alt > 60f

        val scout = s.enemies.firstOrNull {
            it.alive && it.x - s.camX > Art.PLAYER_X && it.x - s.camX < Art.GW + 30
        }
        if (scout != null && kotlin.math.abs(scout.y - s.py) < 16f)
            want = scout.y > s.py + 4f

        if (alt < 22f) want = true
        if (s.py < Tune.CEIL_ROW + 2f) want = false
        s.climbing = want
    }

    /** Fly one sortie to its end and return how many rows it covered. */
    fun sortie(bias: Float, maxTicks: Int = 200000): Sim {
        val s = Sim(MemStore())
        s.start()
        var t = 0
        while (t < maxTicks && !s.gameOver) { cruise(s, bias); s.firing = true; s.update(1f); t++ }
        return s
    }
}
