package com.dawnpatrol.game

/**
 * Every number that decides how the game FEELS, in one place.
 *
 * Units: one "row"/"unit" is one cell of the GW x GH pixel grid, and one
 * "tick" is one 60Hz frame (dt is normalised to that and clamped, so these
 * read the same on a 120Hz screen).
 *
 * ---------------------------------------------------------------------
 * IF IT FEELS FLOATY, CHANGE THESE TWO FIRST:
 *      CLIMB_ACC  - how hard the stick bites when you hold to climb
 *      GRAVITY    - how hard it drops when you let go
 * Both are accelerations in rows/tick^2. Bigger = tighter, twitchier.
 * The number that actually matters is how long a full reversal takes:
 *      full dive -> full climb = (VY_MAX_DN + VY_MAX_UP) / CLIMB_ACC ticks
 * At the values below that is ~20 ticks (~0.34s), which is about as crisp
 * as a continuous-pitch control can be without feeling snappy/digital.
 * Audit.kt prints this number so it never has to be worked out by hand.
 * ---------------------------------------------------------------------
 */
object Tune {

    // ---- flight ---------------------------------------------------------
    // TUNED AFTER PLAYTEST. The first numbers made this a flapping game:
    // gravity was strong enough that letting go for one second dropped you
    // most of the usable band, so the only way to stay up was to hammer the
    // screen. An aeroplane should GLIDE when you stop pulling, not plummet.
    //
    // What changed: gravity is now less than half the climb authority, and
    // terminal sink is gentle. Releasing costs you altitude slowly; holding
    // buys it back briskly. Full reversal is still ~0.35s, so it has not
    // gone mushy - it is responsive without being exhausting.
    const val CLIMB_ACC = 0.072f      // rows/tick^2, applied while held
    const val GRAVITY = 0.030f        // rows/tick^2, always pulling down
    const val VY_MAX_UP = 0.86f       // terminal climb rate
    const val VY_MAX_DN = 0.62f       // terminal sink rate - a glide, not a drop

    // Forward speed tracks pitch: diving trades altitude for speed. Faster
    // also means less time to react, which is the cost of going low.
    const val SPEED_MIN = 0.62f       // rows/tick at full climb  (~37/s)
    const val SPEED_MAX = 1.05f       // rows/tick at full dive   (~63/s)

    // The plane's hitbox is EXACTLY its drawn extent - see Audit A4. No
    // forgiving shrink: art must never extend past the box in the direction
    // that matters, and "generous hitbox" is the same lie wearing a hat.
    const val PLANE_W = 22
    const val PLANE_H = 9

    const val CEIL_ROW = 24f          // hard ceiling, just under the HUD

    // ---- guns -----------------------------------------------------------
    const val GUN_PERIOD = 6f         // ticks between rounds while held
    const val GUN_RANGE = 46f         // rows, muzzle to end of useful cone
    const val GUN_SPEED = 3.4f        // rows/tick
    const val GUN_DROP = 0.018f       // rows/tick^2 - tracers sag, so you
                                      // must get low for a flat shot

    // ---- bombs ----------------------------------------------------------
    const val BOMB_MAX = 6
    const val BOMB_GRAV = 0.062f      // rows/tick^2
    const val BOMB_BLAST = 9.5f       // rows, kill radius on the ground
    const val BOMB_RELOAD = 150f      // ticks to regain one bomb

    // ---- anti-aircraft --------------------------------------------------
    // The signature mechanic. Guns solve a real intercept on your CURRENT
    // velocity, so holding a steady line is what gets you killed; changing
    // vertical velocity is what saves you.
    const val SHELL_SPEED = 1.15f     // rows/tick - deliberately slow.
                                      // Flight time is what creates the dodge
                                      // window, and a slow shell buys it from a
                                      // range where the firing gun is still
                                      // on screen. A fast shell would need the
                                      // battery to open up from off-screen.
    const val FLAK_LETHAL = 4.2f      // rows, burst kill radius

    // Engagement envelope. MIN_ENGAGE is not a flavour number: it is what
    // guarantees every shell has enough flight time for a full-authority
    // pitch change to clear the burst. Audit A6 proves the inequality
    //     0.5 * CLIMB_ACC * t^2  >  FLAK_LETHAL + PLANE_H/2
    // holds for every shell the guns are allowed to fire.
    const val MIN_ENGAGE = 36f        // rows, slant range
    const val MAX_ENGAGE = 64f
    const val AA_PERIOD = 78f         // ticks between shots, per gun
    const val AA_PERIOD_JITTER = 34f  // so a battery never fires in lockstep

    // Aim error vs altitude - this IS the risk/reward dial. Low and the
    // flak is precise; high and it is guesswork. Rows of vertical error.
    const val AIM_ERR_LOW = 1.4f      // at treetop height
    const val AIM_ERR_HIGH = 9.5f     // at the ceiling
    const val AIM_ERR_LOCKED = 0.7f   // caught in a searchlight at night

    // ---- barrage balloons -------------------------------------------------
    // The only safe path is OVER the envelope - the cable below it is lethal
    // all the way down. So the band is set by what a pilot caught on the deck
    // can physically climb over in the runway they get once it comes into
    // view, which Audit A8 proves by simulation. Raising these traps people.
    const val BALLOON_TOP_MIN = 56f
    const val BALLOON_TOP_SPAN = 15f
    // Balloons are announced by a HUD chevron before the envelope itself
    // scrolls on. Without that lead a pilot on the deck cannot physically
    // climb over one - Audit A8 proves the margin with this included.
    const val BALLOON_WARN = 34f

    // ---- searchlights ---------------------------------------------------
    const val BEAM_SWEEP = 0.0065f    // radians/tick
    const val BEAM_SPREAD = 0.10f     // half-angle, radians
    const val BEAM_RANGE = 150f
    const val LOCK_GRACE = 42f        // ticks a lock persists after you leave

    // ---- greed / multiplier ---------------------------------------------
    // The multiplier is the greed meter: it only climbs while you are low.
    // Climbing to safety is what spends it.
    const val LOW_ALT = 34f           // rows above ground that counts as "low"
    const val MULT_STEP = 84f         // ticks low per multiplier step
    const val MULT_MAX = 9
    const val MULT_DECAY = 26f        // ticks high before it starts dropping

    // ---- the front line --------------------------------------------------
    // Not a distance score. Destroying things pushes the line your way;
    // flying past live targets lets it creep back.
    const val LINE_DEPOT = 0.028f
    const val LINE_AA = 0.011f
    const val LINE_TANK = 0.014f
    const val LINE_BALLOON = 0.017f
    const val LINE_CREEP = 0.0000145f // per row flown, back toward the enemy
    const val LINE_START = 0.42f

    // ---- day / night ------------------------------------------------------
    // Five phases: dawn, day, dusk, deep dusk, night. At 2600 rows a phase
    // the full cycle took over three minutes of unbroken flight, so in
    // practice nobody would ever have seen the night pass - the whole
    // inverted palette would have been dead content. 900 puts a full cycle
    // at roughly 80 seconds, so a decent run actually flies into darkness.
    const val PHASE_LEN = 900f        // rows of world per phase

    // ---- lives ---------------------------------------------------------
    // One hit ending the whole run gave no room to learn and no moment to
    // register WHY you died. Three lives, a visible loss, and a respawn in
    // place - you keep the world and the score you earned, you just lose a
    // machine. Only running out ends the run.
    const val LIVES = 3
    const val INVULN = 110f           // ticks of grace after a respawn
    const val LIFE_FLASH = 70f        // ticks the "LIFE LOST" banner holds
    const val RESPAWN_ALT = 46f       // rows above ground to put you back

    // ---- enemy scouts ----------------------------------------------------
    // The fix for "nothing can touch me up high". Flak is a ground weapon
    // and physically cannot reach the ceiling, which made cruising at the
    // top a safe, scoreless, boring optimum. Scouts come to YOU: they drift
    // toward your altitude, so there is no longer anywhere to hide.
    const val ENEMY_SPEED = 0.30f     // rows/tick, closing on top of your own
    const val ENEMY_VY = 0.30f        // how fast they match your altitude
    const val ENEMY_TRACK = 0.010f    // how hard they steer - gentle on purpose
    const val ENEMY_PERIOD = 460f     // ticks between scouts at zero threat
    const val ENEMY_PERIOD_MIN = 170f // ...and at full threat
    const val ENEMY_FIRE_PERIOD = 66f
    const val EBULLET_SPEED = 1.35f   // slow enough to read and dodge
    const val PTS_ENEMY = 220

    // ---- difficulty ramp ---------------------------------------------------
    // Flat difficulty means the first minute and the tenth feel identical.
    const val RAMP_ROWS = 2600f       // rows over which threat goes 0 -> 1

    // ---- scoring -----------------------------------------------------------
    // Shown to the player on the briefing card, so these ARE the contract.
    const val PTS_DEPOT = 280
    const val PTS_BALLOON = 170
    const val PTS_TANK = 140
    const val PTS_LIGHT = 130
    const val PTS_AA = 110

    // ---- pacing -----------------------------------------------------------
    const val WARMUP_ROWS = 260f      // quiet run-in before the guns wake up

    // The aircraft flies straight and level until the player's first touch.
    // Opening on a plane already falling out of the sky reads as a bug and
    // panics people before they have found the controls.
    const val INTRO_DRIFT = 0.34f     // rows/tick of world scroll while waiting
    const val INTRO_BOB = 1.6f        // rows of gentle float, so it looks alive
}
