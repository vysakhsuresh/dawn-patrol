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
    // Scaled x3 from the original values, measured: at the old rates a
    // strong sortie moved the meter +0.18, so from a 0.42 start it took
    // three good sorties to fill - too far away to be a goal you play
    // toward. At these rates a strong sortie takes a sector and a poor one
    // banks visible progress toward the next.
    const val LINE_DEPOT = 0.085f
    const val LINE_AA = 0.034f
    const val LINE_TANK = 0.042f
    const val LINE_BALLOON = 0.052f
    const val LINE_CREEP = 0.0000435f // per row flown, back toward the enemy
    const val LINE_START = 0.42f

    // ---- the sortie arc -----------------------------------------------------
    // Before this the only way a sortie ended was running out of machines:
    // no climax, no shape, and nothing you could lose by pressing on. Now a
    // sortie is TAKE OFF -> OBJECTIVE -> RETURN TO BASE, and the score is
    // only yours IF YOU LAND. Loss aversion beats accumulation: "did I make
    // it back" is a story, "I reached 4200" is a number.
    // MEASURED: a competent test pilot covers ~2100 rows before it is shot
    // down, so the round trip has to fit inside that. At 1500 out (3000
    // round trip) Audit A26 could not get a single sortie home.
    // SWEPT, not guessed. Against a 400-row friendly bubble, lengths of
    // 900/1000/1100/1200 all land 20/20 for a pilot that shoots back, and
    // the front advance and the earnings both rise with length:
    //     900 -> +956/sortie   1000 -> +1272   1100 -> +1356   1200 -> +1513
    // 1200 it is. (At 620 of bubble and 1000 out, 62% of the leg had no
    // targets at all and the front stopped advancing - Audit A22.)
    const val SORTIE_LEN = 1200f      // rows out to the objective
    const val LAND_SPEED = 0.52f      // rows/tick, max sink on touchdown
    const val LAND_ALT = 10f          // rows above ground that counts as down
    const val STRIP_BLEND = 40f       // rows of easing either side of the flat
    // Base is a STRIP, not a point. As a point, a pilot arriving high or
    // fast sailed over it and flew west forever with no way back - Audit
    // A26 landed 0 of 10. A strip you can line up on, and a go-around if
    // you muff it, is both the real thing and the fair thing.
    const val LAND_ZONE = 260f        // rows of strip, approached from the east
    const val GOAROUND_OVERSHOOT = 40f // rows past the strip before you must
                                       // climb out and come round again
    const val PTS_LANDED = 800        // the bonus for bringing it home
    // FRIENDLY AIRSPACE. The aerodrome is behind your own lines, so no
    // enemy battery sits on your runway and no scout patrols your circuit.
    // Without this the approach was a gauntlet flown at landing altitude
    // into AIM_ERR_LOW accuracy, and Audit A26 landed 0 of 10 - the pilot
    // kept being shot down on its own doorstep. The drama of the return
    // leg belongs in the middle of it, not over the hangars.
    // Sized from the descent, not picked: dropping from cruise (~90 rows
    // up) at terminal sink takes ~145 ticks, about 120 rows, and the strip
    // is 260 more. 400 covers both with margin and no more.
    const val HOME_SAFE = 400f        // rows around base with no enemy fire
    const val TURN_FLASH = 100f       // ticks the TURN FOR HOME banner holds

    // ---- shake --------------------------------------------------------
    // Rows of camera offset. Small: at 100x175 a two-row shake is already
    // violent, and anything that moves the whole world has to stay under
    // the threshold where the player loses track of the aeroplane.
    const val SHAKE_HIT = 2.4f
    const val SHAKE_SECTOR = 1.6f

    // ---- hit-stop -------------------------------------------------------
    // Freeze the world for a few ticks on impact. The cheapest juice that
    // exists: the eye reads the frozen frame as weight. Kept tiny - at 60Hz
    // even four ticks is noticeable and eight feels broken.
    const val STOP_KILL = 2f
    const val STOP_HIT = 4f

    // ---- weather ---------------------------------------------------------
    // Chosen per sortie from the sector, so it is a property of WHERE you
    // are flying rather than a die roll, and the briefing can name it
    // before you take off.
    const val WIND_HEAD = 0.84f       // multiplier on both speeds
    const val WIND_CROSS = 0.055f     // rows/tick of lateral drift on bombs
    const val FOG_AIM = 3.1f          // multiplier on the gun's aim error
    const val FOG_VEIL = 26           // dither density of the murk
    const val GUST_V = 0.016f         // rows/tick^2 of vertical gusting

    // ---- damage ---------------------------------------------------------
    // A hit damages a PART, not an abstract life. Each part has one
    // readable consequence, and the smoke trail is the readout so you can
    // fly by the picture instead of the HUD.
    const val PARTS = 5               // engine, fuel, elevator, wing, guns
    const val DMG_SPEED = 0.70f       // engine: multiplier on SPEED_MAX
    const val DMG_CLIMB = 0.72f       // engine: multiplier on CLIMB_ACC
    const val DMG_LAG = 9f            // elevator: ticks of control lag
    // A holed wing must SINK you, not GROUND you. At 0.085 it out-pulled
    // the whole engine - climb authority is only 0.072 - so engine+wing
    // together could not climb at all and 16 of the 32 damage states were
    // unflyable. Audit A27 now proves every combination still climbs:
    //     CLIMB_ACC * DMG_CLIMB - GRAVITY - DMG_WING_VY  >  0
    //     0.072     * 0.72      - 0.030   - 0.010        =  +0.0118
    const val DMG_WING_VY = 0.010f    // wing: constant sink you must hold off
    const val DMG_GUN_MULT = 2.2f     // guns: multiplier on GUN_PERIOD
    const val FUEL_FULL = 6000f       // ticks of fuel at full tanks
    // A leak is a clock, not a death sentence: holed at the turn, the tank
    // must still cover most of the way home so the drama is "will I make
    // it" rather than "I was dead when it happened". A26 proves it.
    const val FUEL_LEAK = 1.1f        // extra ticks burned per tick when holed

    // ---- spending the multiplier -------------------------------------------
    // The greed meter was a number that went up and down. Now it BUYS
    // things, which turns a passive readout into a dilemma re-offered every
    // few seconds: push for x9, or cash out and get home.
    const val COST_REPAIR = 3         // multiplier needed to patch one part
    const val COST_REARM = 2          // ...and to refill the bomb bay

    // ---- the advancing front ----------------------------------------------
    // MEASURED, not guessed. Before this, every sortie started at worldX 0,
    // so every sortie flew the IDENTICAL ground - same terrain, same
    // targets, forever - and because destroyed slots persist, the score for
    // playing exactly as well decayed 2260 -> 900 -> 770 -> 660 and then
    // flatlined. The game punished you for playing it.
    //
    // Now a sortie launches from the front line itself. Push the line and
    // your next sortie starts over fresh country; lose it and you are sent
    // back over your own burning wrecks, which is both the honest outcome
    // and the game's own stated fantasy.
    //
    // The scale comes from the test pilot: a strong sortie moves the line
    // +0.18 and covers ~2075 rows. 11000 makes that worth almost exactly one
    // sortie of new ground, so playing well buys a brand new map and playing
    // badly does not.
    // Filling the meter TAKES the sector: the front jumps forward by this
    // much, the meter resets, and the next sortie launches over ground
    // nobody has flown. One mechanism, one payoff, and a meter that can
    // never saturate and sit dead at 1.00 the way a one-way ratchet does.
    const val SECTOR_ROWS = 2400f
    const val PTS_SECTOR = 1500       // the capture bonus
    const val FRONT_MAX_BACK = 900f   // rows the front can give up at once

    // ---- grazing ------------------------------------------------------------
    // The whole game says "accuracy demands altitude", but surviving close
    // flak paid nothing, so the safe play and the brave play scored the same
    // once you lived. A burst that misses by a hair now pays - which is the
    // moment the player's pulse actually moves.
    // MEASURED: at 9.0 a campaign averaged 0.1 grazes a sortie, because at
    // low level the gun's aim error is only 1.4 rows, so a shell that gets
    // that close mostly just kills you - the near-miss band was a sliver
    // exactly where the player is bravest. 14 rows makes "that was close"
    // something you feel a few times a sortie, which is the whole point.
    const val GRAZE_R = 14.0f         // rows; outside FLAK_LETHAL, inside this
    const val PTS_GRAZE = 60

    // ---- kill chains ---------------------------------------------------------
    // Kills inside the window stack. Rewards a planned strafing run over
    // potting one target and climbing away.
    const val CHAIN_WINDOW = 110f     // ticks since the last kill
    const val PTS_CHAIN = 45          // x the chain length
    const val CHAIN_MAX = 12

    // ---- day / night ------------------------------------------------------
    // Five phases: dawn, day, dusk, deep dusk, night. MEASURED, not guessed:
    // tools/FrameDump.kt flies a competent test pilot (dodges committed
    // shells, breaks off scouts, cruises above the flak envelope) and it dies
    // at about 1970 rows with all three machines spent. At the original 2600
    // rows a phase, night began at 10400 rows; at 900 it began at 3600. Both
    // numbers are past the end of any real sortie, so the entire inverted
    // night palette - the best-looking thing in the game - was content no
    // player would ever see.
    //
    // 460 puts night at 1840 rows and a full cycle at 2300, so a good run
    // flies dawn -> day -> dusk -> night and back out the other side. The
    // palette crossfades continuously rather than stepping, so a shorter
    // phase reads as a faster drift, not as flicker.
    const val PHASE_LEN = 460f        // rows of world per phase

    // ---- lives ---------------------------------------------------------
    // One hit ending the whole run gave no room to learn and no moment to
    // register WHY you died. Three lives, a visible loss, and a respawn in
    // place - you keep the world and the score you earned, you just lose a
    // machine. Only running out ends the run.
    const val LIVES = 3
    const val INVULN = 110f           // ticks of grace after a respawn
    const val LIFE_FLASH = 70f        // ticks the "LIFE LOST" banner holds
    const val RESPAWN_ALT = 46f       // rows above ground to put you back

    // GAME OVER has to actually stop. Without a lockout the card is gone
    // before it is read: you die mid-tap, your very next finger movement -
    // which during play is constant - is taken as "start again".
    // `crashed` means the RUN is over - losing a life with machines left
    // respawns instead - so there is one lockout, not two. A second constant
    // for "between lives" would be a branch that can never run.
    const val GAMEOVER_LOCKOUT = 80f  // ticks before a tap is accepted

    // ---- pause -----------------------------------------------------------
    // Coming back from a pause straight into live flak is not a pause, it is
    // an ambush - you may have walked away mid-dive with a shell in the air.
    // A count-in gives the screen back before the world starts moving. It is
    // a COUNT-IN and not a moment of invulnerability on purpose: a pause
    // must not be a way to shrug off a shell that was already committed.
    const val RESUME_COUNT = 180f     // ticks, i.e. 3 - 2 - 1

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

    // How close to your own best counts as "so close". The end card says it
    // out loud, because "180 rows short" is what makes someone tap again.
    const val SO_CLOSE = 0.12f        // fraction of best

    // The aircraft flies straight and level until the player's first touch.
    // Opening on a plane already falling out of the sky reads as a bug and
    // panics people before they have found the controls.
    const val INTRO_DRIFT = 0.34f     // rows/tick of world scroll while waiting
    const val INTRO_BOB = 1.6f        // rows of gentle float, so it looks alive
}
