package com.dawnpatrol.game

/**
 * The aeroplanes you can fly, and what makes each one a different game.
 *
 * WHY THIS EXISTS. You earned eight ranks and flew the identical machine
 * at all of them - the reward for promotion was a word. These are not stat
 * bumps: each airframe changes which VERB the sortie is built around.
 *
 *   SCOUT     nimble, quick to reverse, two bombs      - a dodging game
 *   TRIPLANE  slow over the ground, superb climb       - a vertical game
 *   BOMBER    heavy, poor climb, twelve bombs          - a committing game
 *
 * Every one of them has to pass the same fairness proofs the scout does:
 * Audit A32 re-runs the dodge window, the balloon clearance, the control
 * reversal and the scout escape PER AIRFRAME. A fast aeroplane that cannot
 * dodge, or a heavy one that cannot clear a balloon, is a trap sold as a
 * reward.
 */
class Plane(
    val name: String,
    val sprite: Array<String>,
    val climbAcc: Float,
    val vyUp: Float,
    val vyDown: Float,
    val speedMin: Float,
    val speedMax: Float,
    val bombs: Int,
    val unlockRank: Int,
    val blurb: String
) {
    val climb: Array<String> = Art.shear(sprite, 1)
    val dive: Array<String> = Art.shear(sprite, -1)
}

object Planes {

    val ALL: Array<Plane> = arrayOf(
        Plane(
            name = "SCOUT",
            sprite = Art.SPR_PLANE,
            climbAcc = Tune.CLIMB_ACC,
            vyUp = Tune.VY_MAX_UP, vyDown = Tune.VY_MAX_DN,
            speedMin = Tune.SPEED_MIN, speedMax = Tune.SPEED_MAX,
            bombs = Tune.BOMB_MAX,
            unlockRank = 0,
            blurb = "NIMBLE - 6 BOMBS"
        ),
        Plane(
            name = "TRIPLANE",
            sprite = Art.SPR_TRIPLANE,
            climbAcc = Tune.CLIMB_ACC * 1.34f,
            vyUp = Tune.VY_MAX_UP * 1.22f, vyDown = Tune.VY_MAX_DN * 0.92f,
            speedMin = Tune.SPEED_MIN * 0.88f, speedMax = Tune.SPEED_MAX * 0.86f,
            bombs = 4,
            unlockRank = 2,
            blurb = "CLIMBS - SLOW - 4 BOMBS"
        ),
        Plane(
            name = "BOMBER",
            sprite = Art.SPR_BOMBER,
            climbAcc = Tune.CLIMB_ACC * 0.80f,
            vyUp = Tune.VY_MAX_UP * 0.86f, vyDown = Tune.VY_MAX_DN * 1.10f,
            speedMin = Tune.SPEED_MIN * 1.05f, speedMax = Tune.SPEED_MAX * 1.08f,
            bombs = 12,
            unlockRank = 4,
            blurb = "HEAVY - 12 BOMBS"
        )
    )

    fun unlockedAt(rank: Int): List<Plane> = ALL.filter { it.unlockRank <= rank }

    fun byName(n: String): Plane = ALL.firstOrNull { it.name == n } ?: ALL[0]
}
