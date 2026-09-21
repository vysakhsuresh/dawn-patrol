package com.dawnpatrol.game

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Numeric audit of the SHIPPED code.
 *
 * This is not a port. Audit.kt compiles against the same Art/World/Tune/Sim
 * classes that go into the APK and runs them headlessly, so a result here
 * is a statement about the game, not about a Python model of it.
 *
 * Run:  tools/audit.sh
 */
object Audit {

    private val failures = ArrayList<String>()

    private fun check(name: String, ok: Boolean, detail: String = "") {
        println(String.format("%-52s %s   %s", name, if (ok) "PASS" else "FAIL", detail))
        if (!ok) failures.add(name)
    }

    private fun note(s: String) = println("    $s")

    // =====================================================================
    //  A1-A3  procedural world  (the Cat on a Fence bugs)
    // =====================================================================
    private fun a1ScrollStability(frames: Int = 24000, speed: Double = 1.37) {
        val terr = HashMap<Long, Double>()
        val slots = HashMap<Int, World.Slot>()
        var worst = 0.0
        var cam = -913.5
        var mutations = 0
        for (f in 0 until frames) {
            cam += speed
            var sx = 0
            while (sx < Art.GW) {
                val wx = cam + sx
                val key = Math.round(wx * 100.0)
                val g = World.groundY(wx)
                val prev = terr[key]
                if (prev != null) worst = max(worst, abs(prev - g)) else terr[key] = g
                sx += 3
            }
            for (k in World.visibleSlots(cam, Art.GW.toDouble())) {
                val s = World.slot(k)
                val prev = slots[k]
                if (prev != null) { if (prev != s) mutations++ } else slots[k] = s
            }
        }
        check("A1 terrain stable over $frames frames", worst == 0.0,
            "max drift ${worst}px over ${terr.size} sampled world positions")
        check("A1 features stable over $frames frames", mutations == 0,
            "${slots.size} distinct slots visited, $mutations mutations")
    }

    private fun a2SeedSource(trials: Int = 6000) {
        var bad = 0
        for (i in 0 until trials) {
            val cam = i * 7.31 - 9000.0
            val left = World.visibleSlots(cam, Art.GW.toDouble()).map { World.slot(it) }
            val k1 = World.cell(cam + Art.GW + 40, Art.SLOT_W.toDouble())
            val k0 = World.cell(cam - 40, Art.SLOT_W.toDouble())
            val right = (k1 downTo k0).map { World.slot(it) }.reversed()
            if (left != right) bad++
        }
        check("A2 visible set independent of walk direction", bad == 0,
            "${trials - bad}/$trials camera positions")

        var outOfRange = 0
        for (i in 0 until trials) {
            val wx = i * 3.77 - 7000.0
            val c = World.cell(wx, Art.TERRAIN_CELL.toDouble())
            val t = World.frac(wx, Art.TERRAIN_CELL.toDouble(), c)
            if (t < 0.0 || t >= 1.0) outOfRange++
        }
        check("A2 within-cell fraction always in [0,1)", outOfRange == 0,
            "checked $trials positions incl. negative worldX")
    }

    private fun a3WrapSafety() {
        var bad = 0
        var x = -200.0
        while (x < 200.0) {
            val c = World.cell(x, Art.TERRAIN_CELL.toDouble())
            if (!(c * Art.TERRAIN_CELL <= x && x < (c + 1) * Art.TERRAIN_CELL)) bad++
            x += 0.5
        }
        check("A3 cell() correct across worldX = 0", bad == 0, "800 samples, $bad failures")

        var naiveBad = 0
        var y = -200.0
        while (y < 0.0) {
            if ((y / Art.TERRAIN_CELL).toInt() * Art.TERRAIN_CELL > y) naiveBad++
            y += 0.5
        }
        check("A3 naive toInt() truncation would have failed", naiveBad > 0,
            "$naiveBad of 400 negative samples - why cell() uses floor()")
    }

    // =====================================================================
    //  A4  art must not lie about physics
    // =====================================================================
    private fun a4ArtWithinHitbox() {
        var ok = true
        val boxes = HashSet<Pair<Int, Int>>()
        for ((name, spr) in listOf(
            "SPR_PLANE" to Art.SPR_PLANE,
            "SPR_PLANE_CLIMB" to Art.SPR_PLANE_CLIMB,
            "SPR_PLANE_DIVE" to Art.SPR_PLANE_DIVE)) {
            val rows = spr.indices.filter { spr[it].contains('X') }
            val cols = spr.flatMap { r -> r.indices.filter { r[it] == 'X' } }
            val top = rows.min(); val bot = rows.max()
            val left = cols.min(); val right = cols.max()
            val inside = top >= 0 && bot < Tune.PLANE_H && left >= 0 && right < Tune.PLANE_W
            note(String.format("%-16s art rows %d..%-2d cols %d..%-2d  hitbox %dx%d  %s",
                name, top, bot, left, right, Tune.PLANE_W, Tune.PLANE_H,
                if (inside) "ok" else "ESCAPES"))
            ok = ok && inside
            boxes.add(spr.maxOf { it.length } to spr.size)
        }
        check("A4 no plane art outside its hitbox", ok,
            "hitbox is the exact drawn extent, not a forgiving shrink")
        check("A4 all attitudes share one hitbox size", boxes.size == 1, boxes.toString())
    }

    // =====================================================================
    //  A5  terrain continuity
    // =====================================================================
    private fun a5Terrain(span: Int = 60000) {
        var worst = 0.0; var worstAt = 0
        var prev = World.groundY(0.0)
        for (i in 1 until span) {
            val g = World.groundY(i.toDouble())
            val d = abs(g - prev)
            if (d > worst) { worst = d; worstAt = i }
            prev = g
        }
        check("A5 max terrain step per column", worst <= 2.0,
            String.format("%.3f rows at worldX %d", worst, worstAt))

        var lo = 1e9; var hi = -1e9
        for (i in 0 until 20000) {
            val g = World.groundY(i.toDouble()); lo = min(lo, g); hi = max(hi, g)
        }
        check("A5 terrain stays inside its band",
            lo >= Art.HORIZON - Art.TERR_AMP - 1 && hi <= Art.HORIZON + Art.TERR_AMP + 1,
            String.format("%.1f .. %.1f (band %d +/- %d)", lo, hi, Art.HORIZON, Art.TERR_AMP))
    }

    // =====================================================================
    //  A6  THE DODGE WINDOW  - the brief's explicit demand
    //
    //  Every shell the guns are allowed to fire must be escapable by a
    //  full-authority pitch change made at the moment of firing, without
    //  flying into the ground or the ceiling to do it.
    // =====================================================================
    /**
     * Fly one bang-bang control input and report (separation from the aimed
     * point at detonation, survived).
     *
     * `firstUp` is the initial stick, `switchAt` the tick it reverses.
     * Single-direction inputs are just switchAt >= ticks.
     *
     * Two earlier models were both wrong and both let A6 fail states that
     * are actually flyable:
     *   - "hold full stick the whole flight" cannot dodge at all when the
     *     plane is ALREADY at max climb on the deck - no authority left one
     *     way, terrain the other. A real pilot just eases off for a moment:
     *     the aimed point keeps climbing at the old rate, so a gap opens
     *     while the plane is still climbing, only less steeply.
     *   - "pitch, then level off" was worse: stop manoeuvring and the aimed
     *     point drifts back onto you.
     */
    private fun fly(vy0: Float, y0: Float, ticks: Float, firstUp: Boolean,
                    switchAt: Float, ground: Float): Pair<Float, Boolean> {
        var v = vy0
        var y = y0
        var t = 0f
        while (t < ticks) {
            val step = min(1f, ticks - t)
            val up = if (t < switchAt) firstUp else !firstUp
            val acc = if (up) -Tune.CLIMB_ACC else Tune.GRAVITY
            v = (v + acc * step).coerceIn(-Tune.VY_MAX_UP, Tune.VY_MAX_DN)
            y += v * step
            if (y < Tune.CEIL_ROW) { y = Tune.CEIL_ROW; if (v < 0f) v = 0f }
            if (y + Tune.PLANE_H >= ground) return 0f to false
            t += step
        }
        return abs(y - (y0 + vy0 * ticks)) to true
    }

    /** Best separation any survivable control input achieves. */
    private fun bestDodge(vy0: Float, y0: Float, ticks: Float, ground: Float):
            Pair<Float, Boolean> {
        var best = 0f
        var anyAlive = false
        for (firstUp in listOf(true, false)) {
            var sw = 0f
            while (sw <= ticks + 1f) {
                val (dev, alive) = fly(vy0, y0, ticks, firstUp, sw, ground)
                if (alive) { anyAlive = true; best = max(best, dev) }
                sw += 1f
            }
        }
        return best to anyAlive
    }

    private fun a6DodgeWindow() {
        val need = Tune.FLAK_LETHAL + Tune.PLANE_H / 2f
        val ground = Art.HORIZON.toFloat()
        var worstDev = Float.MAX_VALUE
        var worstAt = ""
        var cases = 0
        var escapable = 0
        var skipped = 0

        var range = Tune.MIN_ENGAGE
        while (range <= Tune.MAX_ENGAGE) {
            val t = range / Tune.SHELL_SPEED
            var vy0 = -Tune.VY_MAX_UP
            while (vy0 <= Tune.VY_MAX_DN) {
                var alt = Tune.CEIL_ROW
                while (alt <= ground - Tune.PLANE_H - 1f) {
                    // Range and altitude are NOT independent. The gun is on
                    // the ground, so slant range can never be less than the
                    // vertical separation - a plane near the ceiling simply
                    // cannot be 36 rows from a battery. Sweeping them freely
                    // tested states the geometry forbids.
                    val gunY = ground - 8f
                    val vertSep = abs((alt + Tune.PLANE_H / 2f) - gunY)
                    if (vertSep > range) { alt += 4f; continue }

                    val (dev, alive) = bestDodge(vy0, alt, t, ground)
                    // A state no control input can keep out of the dirt is
                    // already lost to terrain; a shell did not kill it.
                    if (!alive) { skipped++; alt += 4f; continue }
                    cases++
                    if (dev > need) escapable++
                    if (dev < worstDev) {
                        worstDev = dev
                        worstAt = String.format("range %.0f, vy %.2f, alt %.0f", range, vy0, alt)
                    }
                    alt += 4f
                }
                vy0 += 0.25f
            }
            range += 4f
        }
        check("A6 every legal shell is dodgeable", escapable == cases,
            "$escapable/$cases recoverable states ($skipped already lost to terrain)")
        check("A6 worst-case clearance beats lethal radius", worstDev > need,
            String.format("%.2f rows vs %.2f needed  (%s)", worstDev, need, worstAt))
        note(String.format("flight time: %.1f..%.1f ticks (%.2f..%.2fs)",
            Tune.MIN_ENGAGE / Tune.SHELL_SPEED, Tune.MAX_ENGAGE / Tune.SHELL_SPEED,
            Tune.MIN_ENGAGE / Tune.SHELL_SPEED / 60f, Tune.MAX_ENGAGE / Tune.SHELL_SPEED / 60f))
    }

    // =====================================================================
    //  A7  never two lethal bursts inside one dodge window
    // =====================================================================
    private fun a7BurstSeparation() {
        var worstConcurrent = 0
        var totalTicks = 0
        for (seed in 0 until 12) {
            val sim = Sim(MemStore())
            sim.reset()
            sim.start()
            var t = 0
            while (t < 9000 && !sim.crashed) {
                // a greedy pilot: hug the deck, which is where the flak is worst
                val alt = sim.altitude()
                sim.climbing = alt < 16f || (seed % 3 == 0 && alt < 26f)
                sim.firing = (t / 40 + seed) % 3 != 0
                sim.update(1f)
                val lethal = sim.bursts.count { it.alive && it.lethal > 0f }
                worstConcurrent = max(worstConcurrent, lethal)
                totalTicks++
                t++
            }
        }
        check("A7 never 2 lethal bursts at once", worstConcurrent <= 1,
            "max concurrent = $worstConcurrent over $totalTicks ticks")
    }

    // =====================================================================
    //  A8  balloons are always clearable
    // =====================================================================
    private fun a8BalloonClearance() {
        // Worst case: flying as low and as fast as possible when the balloon
        // first becomes visible at the right edge, then pulling up hard.
        // Climbing bleeds speed, which is what buys the time.
        var worstMargin = Float.MAX_VALUE
        var worstAt = ""
        var cleared = 0
        var total = 0

        var topRow = Tune.BALLOON_TOP_MIN
        while (topRow <= Tune.BALLOON_TOP_MIN + Tune.BALLOON_TOP_SPAN) {
            var startAlt = Art.HORIZON - Tune.PLANE_H - 2f
            while (startAlt > Tune.CEIL_ROW) {
                var y = startAlt
                var v = Tune.VY_MAX_DN            // worst case: already diving
                // runway = screen width plus the chevron's head start
                var x = Art.GW + Tune.BALLOON_WARN
                var t = 0
                var ok = false
                while (t < 1200) {
                    v = (v - Tune.CLIMB_ACC).coerceIn(-Tune.VY_MAX_UP, Tune.VY_MAX_DN)
                    y += v
                    if (y < Tune.CEIL_ROW) y = Tune.CEIL_ROW
                    val sp = Tune.SPEED_MIN + (Tune.SPEED_MAX - Tune.SPEED_MIN) *
                        ((v + Tune.VY_MAX_UP) / (Tune.VY_MAX_UP + Tune.VY_MAX_DN)).coerceIn(0f, 1f)
                    x -= sp
                    if (y + Tune.PLANE_H <= topRow) { ok = true; break }
                    if (x <= Art.PLAYER_X + Tune.PLANE_W) break
                    t++
                }
                total++
                if (ok) cleared++
                val margin = x - (Art.PLAYER_X + Tune.PLANE_W)
                if (ok && margin < worstMargin) {
                    worstMargin = margin
                    worstAt = String.format("balloon top %.0f, start alt %.0f", topRow, startAlt)
                }
                startAlt -= 4f
            }
            topRow += 3f
        }
        check("A8 balloon clearable from any altitude", cleared == total, "$cleared/$total")
        check("A8 worst clearance margin > 0", worstMargin > 0f,
            String.format("%.1f rows of runway to spare (%s)", worstMargin, worstAt))
        note("guns are the other answer: a balloon is a target, not just a wall")
    }

    // =====================================================================
    //  A9  feel numbers
    // =====================================================================
    private fun a9Feel() {
        val reversal = (Tune.VY_MAX_DN + Tune.VY_MAX_UP) / Tune.CLIMB_ACC
        val toTerminal = Tune.VY_MAX_DN / Tune.GRAVITY
        val band = Art.HORIZON - Tune.CEIL_ROW - Tune.PLANE_H
        note(String.format("full dive -> full climb : %.1f ticks (%.2fs)", reversal, reversal / 60f))
        note(String.format("release -> terminal dive: %.1f ticks (%.2fs)", toTerminal, toTerminal / 60f))
        note(String.format("usable altitude band    : %.0f rows", band))
        note(String.format("screen crossing         : %.2fs climbing, %.2fs diving",
            Art.GW / Tune.SPEED_MIN / 60f, Art.GW / Tune.SPEED_MAX / 60f))
        check("A9 reversal is responsive (< 0.5s)", reversal / 60f < 0.5f,
            String.format("%.2fs", reversal / 60f))
        check("A9 altitude band is usable", band > 80f, String.format("%.0f rows", band))
    }

    // =====================================================================
    //  A10  soak: many sorties, assert nothing impossible happens
    // =====================================================================
    private fun a10Soak() {
        var sorties = 0
        var instantDeaths = 0
        var nanSeen = 0
        var maxDist = 0f
        val causes = HashMap<String, Int>()
        var totalKills = 0

        for (seed in 0 until 40) {
            val sim = Sim(MemStore())
            sim.reset()
            sim.start()
            var t = 0
            var committedTo: Shot? = null
            var commitClimb = false
            while (t < 6000 && !sim.crashed) {
                val alt = sim.altitude()
                // a competent pilot: hold a band, dodge when a shell is close
                val target = when (seed % 4) {
                    0 -> 22f; 1 -> 40f; 2 -> 60f; else -> 30f
                }
                // A competent pilot: pick a break direction ONCE when a
                // shell appears and hold it. Re-deciding every tick makes
                // the pilot chatter around the very point the shell is
                // fuzed for, which is the opposite of dodging.
                val threat = sim.shots.filter { it.alive && it.kind == K_SHELL }
                    .minByOrNull { it.fuse }
                if (threat !== committedTo) {
                    committedTo = threat
                    commitClimb = if (threat == null) false else {
                        // break toward whichever side has more room
                        val room = sim.altitude()
                        if (room < 30f) true else sim.vy < 0f
                    }
                }
                sim.climbing = if (threat != null && threat.fuse < 34f) commitClimb
                               else alt < target
                sim.firing = (t % 7) < 4
                if (t % 90 == 0) sim.dropBomb()
                sim.update(1f)
                if (sim.py.isNaN() || sim.camX.isNaN() || sim.linePct.isNaN()) nanSeen++
                t++
            }
            sorties++
            if (sim.crashed && sim.distance < 40f) instantDeaths++
            maxDist = max(maxDist, sim.distance)
            if (sim.crashed) causes[sim.crashCause] = (causes[sim.crashCause] ?: 0) + 1
            totalKills += sim.killsAA + sim.killsDepot + sim.killsTank + sim.killsBalloon
        }
        check("A10 no NaN in $sorties sorties", nanSeen == 0, "$nanSeen samples")
        check("A10 nobody dies on the runway", instantDeaths == 0,
            "$instantDeaths/$sorties died inside 40 rows")
        check("A10 targets are actually killable", totalKills > 0,
            "$totalKills kills across $sorties sorties")
        note("death causes: " + (causes.entries.joinToString { "${it.key}=${it.value}" }
            .ifEmpty { "none - all survived the time limit" }))
        note(String.format("furthest sortie: %.0f rows", maxDist))
    }

    // =====================================================================
    //  A11  the warm-up really is quiet
    // =====================================================================
    private fun a11Warmup() {
        var shellsEarly = 0
        for (seed in 0 until 8) {
            val sim = Sim(MemStore())
            sim.reset()
            sim.start()
            var t = 0
            while (t < 4000 && sim.distance < Tune.WARMUP_ROWS) {
                sim.climbing = sim.altitude() < 30f
                sim.update(1f)
                shellsEarly += sim.shots.count { it.alive && it.kind == K_SHELL }
                t++
            }
        }
        check("A11 no flak before the warm-up ends", shellsEarly == 0,
            "${Tune.WARMUP_ROWS.toInt()} rows of quiet run-in")
    }

    // =====================================================================
    //  A12  lives actually work
    // =====================================================================
    private fun a12Lives() {
        val sim = Sim(MemStore())
        sim.reset(); sim.start()
        var t = 0
        var seenLives = sim.lives
        var respawnedSafely = true
        var everInvuln = false
        // suicidal pilot: never climbs, so it hits the deck repeatedly
        while (t < 30000 && !sim.gameOver) {
            sim.climbing = false
            sim.update(1f)
            if (sim.lives < seenLives) {
                seenLives = sim.lives
                // The last life has no respawn to be safe about - only the
                // ones that put you back in the air need grace and altitude.
                if (!sim.gameOver) {
                    if (sim.invuln <= 0f) respawnedSafely = false
                    if (sim.altitude() < 10f) respawnedSafely = false
                }
            }
            if (sim.invuln > 0f) everInvuln = true
            t++
        }
        check("A12 losing a life does not end the run", sim.lives == 0 && sim.gameOver,
            "burned through ${Tune.LIVES} machines then game over")
        check("A12 respawn is safe and has grace", respawnedSafely && everInvuln,
            "put back at altitude with ${Tune.INVULN.toInt()} ticks of invulnerability")
    }

    // =====================================================================
    //  A13  the sky is no longer a safe hiding place
    // =====================================================================
    private fun a13CeilingIsNotSafe() {
        // A pilot pinned to the ceiling used to be untouchable: flak is a
        // ground weapon and cannot reach. Scouts must be able to.
        var threatened = 0
        for (seed in 0 until 6) {
            val sim = Sim(MemStore())
            sim.reset(); sim.start()
            var t = 0
            var sawScoutClose = false
            while (t < 24000 && !sim.gameOver) {
                sim.climbing = sim.py > Tune.CEIL_ROW + 2f   // hug the ceiling
                sim.update(1f)
                for (e in sim.enemies) {
                    if (!e.alive) continue
                    val dx = abs(e.x - (sim.camX + Art.PLAYER_X).toFloat())
                    val dy = abs(e.y - sim.py)
                    if (dx < 40f && dy < 16f) sawScoutClose = true
                }
                t++
            }
            if (sawScoutClose) threatened++
        }
        check("A13 scouts reach a ceiling-hugging pilot", threatened >= 5,
            "$threatened/6 ceiling runs were engaged")
    }

    // =====================================================================
    //  A14  a scout is always avoidable
    // =====================================================================
    private fun a14ScoutsAreDodgeable() {
        // A scout closes at its own speed plus yours. From the moment it
        // appears at the screen edge, a full-authority pitch change must
        // clear its box before it arrives.
        val closing = Tune.ENEMY_SPEED + Tune.SPEED_MAX
        val runway = (Art.GW - Art.PLAYER_X - Tune.PLANE_W).toFloat()
        val ticks = runway / closing
        // vertical separation reachable in that time, worst case from a
        // standing start, against a scout that is also steering toward you
        var v = 0f; var y = 0f; var t = 0f
        while (t < ticks) {
            v = (v - Tune.CLIMB_ACC).coerceIn(-Tune.VY_MAX_UP, Tune.VY_MAX_DN)
            y += v; t += 1f
        }
        val reach = abs(y) - Tune.ENEMY_VY * ticks   // scout closes some of it
        val need = Tune.PLANE_H.toFloat()
        note(String.format("scout warning: %.0f rows of runway, %.1f ticks (%.2fs)",
            runway, ticks, ticks / 60f))
        check("A14 a scout can always be out-climbed", reach > need,
            String.format("%.1f rows of separation vs %d needed", reach, Tune.PLANE_H))
    }

    // =====================================================================
    //  A15  the ground kills you when it TOUCHES you, and the run stops
    // =====================================================================
    private fun a15GroundHonesty() {
        val sim = Sim(MemStore())

        // ---- the shape that collides is the shape that is drawn ----------
        // The plane's bottom ROW is only the wheels; at the nose and tail the
        // lowest drawn pixel is three rows higher. A flat box across all 22
        // columns therefore hangs below the artwork at both ends, and on
        // rising ground the empty nose columns reach the mud first - you die
        // with daylight under the aeroplane, which is exactly what "feels
        // odd" feels like.
        var worstEarly = 0f; var worstAt = 0.0
        var honestIsLate = 0
        for (i in 0 until 6000) {
            val x0 = i * 7.0 + 0.5
            for (spr in listOf(Art.SPR_PLANE, Art.SPR_PLANE_CLIMB, Art.SPR_PLANE_DIVE)) {
                val prof = sim.bottomProfile(spr)
                // the lowest py at which each model first reports a touch
                var pyHonest = Float.MAX_VALUE
                var pyBox = Float.MAX_VALUE
                for (col in prof.indices) {
                    val gy = sim.groundAt(x0 + col)
                    if (prof[col] >= 0) pyHonest = min(pyHonest, gy - prof[col])
                    pyBox = min(pyBox, gy - Tune.PLANE_H)
                }
                val early = pyHonest - pyBox
                if (early > worstEarly) { worstEarly = early; worstAt = x0 }
                if (pyHonest < pyBox) honestIsLate++

                // and the honest test must be exact: one notch higher, no
                // drawn pixel is in the earth at all.
                val py = pyHonest - 0.02f
                var overlap = false
                for (col in prof.indices) {
                    if (prof[col] < 0) continue
                    if (py + prof[col] >= sim.groundAt(x0 + col)) overlap = true
                }
                if (overlap) honestIsLate++
            }
        }
        note(String.format(
            "flat-box model killed up to %.2f rows early (worst at worldX %.0f)",
            worstEarly, worstAt))
        check("A15 old flat-box model really was killing early", worstEarly > 1f,
            String.format("%.2f rows of visible daylight", worstEarly))
        check("A15 collision fires on drawn-pixel contact, not before",
            honestIsLate == 0, "$honestIsLate of 18000 poses")

        // ---- the wreck comes to rest ON the surface, not in it ------------
        var rested = false; var clr = -1f
        for (seed in 0 until 40) {
            val s = Sim(MemStore()); s.start()
            var t = 0
            while (t < 40000 && !s.gameOver) {
                s.climbing = t % 400 < (seed % 7)   // mostly nose-down
                s.update(1f); t++
            }
            if (s.crashCause != "GROUND") continue
            val prof = s.bottomProfile(s.planeSprite())
            val x0 = s.camX + Art.PLAYER_X
            var minGap = Float.MAX_VALUE
            for (col in prof.indices) {
                if (prof[col] < 0) continue
                minGap = min(minGap, s.groundAt(x0 + col) - (s.py + prof[col]))
            }
            rested = true; clr = minGap; break
        }
        note(String.format("wreck settles %.2f rows clear of the surface", clr))
        check("A15 a ground kill settles the wreck on the surface",
            rested && clr >= 0f && clr <= 1.01f,
            if (rested) String.format("%.2f rows", clr) else "no GROUND death sampled")

        // ---- GAME OVER actually stops the game ----------------------------
        val s = Sim(MemStore()); s.start()
        var t = 0
        while (t < 40000 && !s.gameOver) { s.climbing = false; s.update(1f); t++ }
        check("A15 running out of lives ends the run", s.gameOver && s.crashed,
            "lives=${s.lives}")
        val camAtDeath = s.camX
        // `wait` is the FIRST tick on which a tap would be taken, so this
        // one number proves nothing was accepted before it.
        var wait = 0
        while (!s.canRestart() && wait < 600) { s.update(1f); wait++ }
        note("tap accepted after $wait ticks (" +
            String.format("%.2f", wait / 60f) + "s)")
        check("A15 no tap is accepted before the card can be read",
            wait >= Tune.GAMEOVER_LOCKOUT.toInt(), "$wait ticks")
        check("A15 the world is frozen while GAME OVER holds",
            s.camX == camAtDeath, "camX drifted ${s.camX - camAtDeath}")
        // and it does eventually accept - a lockout that never lifts is worse
        check("A15 the lockout does lift", s.canRestart(), "after $wait ticks")
    }

    // =====================================================================
    //  A16  the day/night cycle must be content, not decoration
    // =====================================================================
    private fun a16CycleIsReachable() {
        // The night palette inverts the whole scene - solid earth becomes
        // pale, features are outlined, the plane reads as a hole in the
        // light. It is the best-looking thing in the game, and for two
        // revisions it was unreachable: at PHASE_LEN 2600 night began at
        // 10400 rows, at 900 it began at 3600, and nobody flies that far.
        // Dead content is invisible in playtesting - you simply never hear
        // about the thing nobody saw. So it gets a number.
        val reaches = ArrayList<Int>()
        var sawNight = 0; var sawDusk = 0
        val biases = listOf(0f, 3f, 6f, 9f, 12f, 16f, 20f, 26f)
        for (b in biases) {
            val s = TestPilot.sortie(b)
            reaches.add(s.distance.toInt())
            if (s.camX >= Tune.PHASE_LEN * 4) sawNight++
            if (s.camX >= Tune.PHASE_LEN * 2) sawDusk++
        }
        reaches.sort()
        val best = reaches.last()
        val median = reaches[reaches.size / 2]
        note("competent pilot reaches " +
            "${reaches.first()}..$best rows (median $median)")
        note(String.format("night begins at %.0f rows, a full cycle is %.0f",
            Tune.PHASE_LEN * 4, Tune.PHASE_LEN * 5))
        check("A16 a competent sortie sees dusk", sawDusk == biases.size,
            "$sawDusk/${biases.size} sorties")
        check("A16 a competent sortie reaches night", sawNight * 2 >= biases.size,
            "$sawNight/${biases.size} sorties")
        check("A16 the whole cycle fits inside a good run",
            Tune.PHASE_LEN * 5 <= best, "cycle ${(Tune.PHASE_LEN * 5).toInt()} rows " +
            "vs best sortie $best")
        // ...and not so short that the palette strobes: a phase must last
        // at least a few seconds at full dive speed.
        val phaseSecs = Tune.PHASE_LEN / (Tune.SPEED_MAX * 60f)
        note(String.format("shortest a phase can pass: %.1fs at full dive", phaseSecs))
        check("A16 a phase is not a flicker", phaseSecs >= 5f,
            String.format("%.1fs", phaseSecs))
    }

    // =====================================================================
    //  A17  the start gate: nobody is ever dropped into a falling aeroplane
    // =====================================================================
    private fun a17StartGate() {
        // The first thing a new player does is dismiss the briefing. For one
        // revision that tap ALSO launched the sortie, so their first ever
        // action dropped the aeroplane out of the sky. The card, the loiter
        // and the launch are three separate states and this pins them.
        val sim = Sim(MemStore())
        check("A17 a fresh install opens on the briefing",
            sim.showFullBriefing && !sim.started, "")

        // dismissing the card must not launch anything
        sim.dismissBriefing()
        check("A17 dismissing the briefing does not launch the sortie",
            !sim.showFullBriefing && !sim.started, "")

        // and in that loiter the plane must not sink. Not "sink slowly" -
        // not at all: it bobs about a fixed line and holds its altitude.
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        var vyWorst = 0f
        for (i in 0 until 3000) {
            sim.update(1f)
            lo = min(lo, sim.py); hi = max(hi, sim.py)
            vyWorst = max(vyWorst, abs(sim.vy))
        }
        note(String.format("loiter holds %.2f rows of bob over 50s (limit %.1f)",
            hi - lo, Tune.INTRO_BOB * 2f))
        check("A17 the aeroplane does not sink while waiting",
            hi - lo <= Tune.INTRO_BOB * 2f + 0.01f && vyWorst == 0f,
            String.format("%.2f rows, worst |vy| %.3f", hi - lo, vyWorst))
        check("A17 the world still moves while waiting", sim.camX > 0.0,
            String.format("%.0f rows of drift", sim.camX))

        // ...and the moment it IS launched, physics takes over
        val before = sim.py
        sim.start()
        for (i in 0 until 40) { sim.climbing = false; sim.update(1f) }
        check("A17 launching hands over to physics", sim.py > before,
            String.format("%.2f -> %.2f rows", before, sim.py))

        // after a run ends, the next one loiters again - you are never put
        // straight back into a falling aeroplane either
        while (!sim.gameOver) { sim.climbing = false; sim.update(1f) }
        sim.reset()
        check("A17 a restart loiters, it does not resume mid-fall",
            !sim.started && !sim.crashed, "")
        check("A17 the briefing does not come back mid-session",
            !sim.showFullBriefing, "sorties=${sim.sorties}")
    }

    // =====================================================================
    //  A18  every character the game draws must have a glyph
    // =====================================================================
    private fun a18NoMissingGlyphs() {
        // A font miss is silent: the character reserves its width and draws
        // nothing, so "RIGHT = BOMB + GUNS" reads "RIGHT   BOMB + GUNS" and
        // looks like a spacing choice rather than a bug. Rather than try to
        // enumerate the strings - half of them are built from scores and
        // causes at runtime - drive the renderer through every state it has
        // and see what it asks for.
        Fb.missing.clear()
        val r = Renderer()
        var frames = 0

        // the briefing, the loiter after it, and the READY prompt
        run {
            val s = Sim(MemStore())
            for (i in 0 until 120) { s.update(1f); r.render(s); frames++ }
            s.dismissBriefing()
            for (i in 0 until 120) { s.update(1f); r.render(s); frames++ }
        }
        // full sorties: flight, flak, bombs, scouts, the life-lost banner,
        // the balloon chevron, every palette phase, and the end card
        for (bias in listOf(0f, 8f, 20f)) {
            val s = Sim(MemStore())
            s.dismissBriefing(); s.start()
            var i = 0
            while (i < 30000 && !(s.gameOver && s.crashTicks > Tune.GAMEOVER_LOCKOUT + 20f)) {
                TestPilot.cruise(s, bias)
                s.firing = true
                if (i % 150 == 0) s.dropBomb()
                s.update(1f)
                if (i % 3 == 0) { r.render(s); frames++ }
                i++
            }
            r.render(s); frames++
        }
        // and a deliberately greedy low run, for the high multiplier text
        run {
            val s = Sim(MemStore())
            s.dismissBriefing(); s.start()
            var i = 0
            while (i < 30000 && !s.gameOver) {
                s.climbing = s.altitude() < 18f
                s.firing = true
                s.update(1f)
                if (i % 3 == 0) { r.render(s); frames++ }
                i++
            }
        }
        note("$frames frames rendered across every screen state")
        check("A18 no character is drawn as a silent gap", Fb.missing.isEmpty(),
            "missing glyphs: " + Fb.missing.joinToString("") { "'" + it + "'" })
    }

    // =====================================================================
    //  A19  the cold-start warm-up is safe and bounded
    // =====================================================================
    private fun a19Warmup() {
        // This runs on a background thread beside the live game on a device,
        // so the two things that matter are that it cannot throw and that it
        // cannot run away. It is in Warmup rather than GameView precisely so
        // this check can execute the shipped code.
        var frames = 0
        var thrown: Throwable? = null
        val t0 = System.nanoTime()
        try { frames = Warmup.run(4_000_000_000L) } catch (e: Throwable) { thrown = e }
        val secs = (System.nanoTime() - t0) / 1e9
        note(String.format("warm-up: %d frames in %.2fs", frames, secs))
        check("A19 the warm-up never throws", thrown == null, thrown?.toString() ?: "")
        check("A19 the warm-up respects its budget", secs <= 4.6,
            String.format("%.2fs against a 4.0s budget", secs))

        // It must cover the paths it claims to: the point is to compile the
        // gun, bomb, flak and end-card code before the player meets them.
        // A fake clock that expires instantly proves the budget is checked
        // rather than merely present.
        val none = Warmup.run(0L, now = { 0L })
        check("A19 an expired budget stops it dead", none == 0, "$none frames")

        // and it must actually do enough work to matter - a warm-up that
        // runs 30 frames warms nothing
        check("A19 the warm-up does enough to warm anything", frames >= 1500,
            "$frames frames")
    }

    // =====================================================================
    //  A20  a pause stops the world, and is not a way out of a shell
    // =====================================================================

    /** Everything about the run that a frozen world must not change. */
    private fun snapshot(s: Sim): String {
        val b = StringBuilder()
        b.append(s.camX).append('|').append(s.py).append('|').append(s.vy)
            .append('|').append(s.distance).append('|').append(s.score)
            .append('|').append(s.lives).append('|').append(s.mult)
            .append('|').append(s.bombs).append('|').append(s.invuln)
            .append('|').append(s.lifeFlash).append('|').append(s.lockTicks)
            .append('|').append(s.linePct).append('|').append(s.speed).append('#')
        for (t in s.shots) b.append(t.alive).append(',').append(t.x).append(',')
            .append(t.y).append(',').append(t.fuse).append(',').append(t.life).append(';')
        b.append('#')
        for (t in s.bursts) b.append(t.alive).append(',').append(t.x).append(',')
            .append(t.y).append(',').append(t.age).append(',').append(t.lethal).append(';')
        b.append('#')
        for (t in s.enemies) b.append(t.alive).append(',').append(t.x).append(',')
            .append(t.y).append(',').append(t.fireTimer).append(';')
        return b.toString()
    }

    private fun a20Pause() {
        // ---- the buttons are reachable and cannot be hit by accident ------
        var overlap = false
        for (gx in 0 until Art.GW) {
            val f = gx.toFloat() + 0.5f
            if (Art.hitPause(f, 4f) && Art.hitMute(f, 4f)) overlap = true
        }
        val pw = (0 until Art.GW).count { Art.hitPause(it + 0.5f, 4f) }
        val mw = (0 until Art.GW).count { Art.hitMute(it + 0.5f, 4f) }
        note("touch targets: pause $pw game px wide, sound $mw " +
            "(~${pw * 108 / 10} physical px on a 1080-wide phone)")
        check("A20 the two HUD buttons cannot both be hit", !overlap, "")
        check("A20 the HUD buttons are big enough to hit", pw >= 12 && mw >= 12,
            "$pw and $mw game px")
        check("A20 the buttons stay inside the HUD strip",
            !Art.hitPause(Art.PAUSE_X + 3f, Art.HUD_H.toFloat()) &&
                !Art.hitMute(Art.MUTE_X + 3f, Art.HUD_H.toFloat()), "")
        // ...and clear of the readouts either side of them
        val altRight = 3 + Fb.textW("ALT 999")
        val scoreLeft = Art.GW - 3 - Fb.textW("999999")
        check("A20 the buttons do not sit on the altimeter or the score",
            Art.PAUSE_X > altRight && Art.MUTE_X + Art.BTN_W < scoreLeft,
            "ALT ends $altRight, buttons ${Art.PAUSE_X}..${Art.MUTE_X + Art.BTN_W}, " +
                "score starts $scoreLeft")

        // ---- a pause before the off, or after the end, does nothing -------
        val fresh = Sim(MemStore())
        fresh.pause()
        check("A20 there is nothing to pause before the sortie starts",
            !fresh.paused, "")

        // ---- the world really does stop -----------------------------------
        val s = Sim(MemStore())
        s.dismissBriefing(); s.start()
        var i = 0
        // fly until there is plenty in the air to freeze
        while (i < 40000 && !(s.shots.count { it.alive && it.kind == K_SHELL } >= 1 &&
                s.shots.count { it.alive } >= 3 &&
                s.enemies.any { it.alive } && !s.gameOver)) {
            // LOW, deliberately: TestPilot cruises above the flak envelope,
            // and a state with no shell in the air proves nothing here.
            s.climbing = s.altitude() < 26f
            s.firing = true
            if (i % 150 == 0) s.dropBomb()
            s.update(1f); i++
        }
        check("A20 reached a busy state to freeze",
            !s.gameOver && s.shots.any { it.alive && it.kind == K_SHELL },
            "${s.shots.count { it.alive }} shots in the air " +
                "(${s.shots.count { it.alive && it.kind == K_SHELL }} of them flak), " +
                "${s.enemies.count { it.alive }} scouts")

        val before = snapshot(s)
        val shellFuses = s.shots.filter { it.alive && it.kind == K_SHELL }.map { it.fuse }
        s.pause()
        check("A20 a sortie in progress can be paused", s.paused, "")
        // hold it frozen for ten seconds of wall clock, with the stick hard
        // over and the trigger down - none of it may reach the world
        for (j in 0 until 600) { s.climbing = true; s.firing = true; s.update(1f) }
        check("A20 a pause stops the world completely", snapshot(s) == before,
            "600 ticks with the stick hard over and the trigger down")

        // ---- the count-in gives the screen back, not immunity -------------
        s.requestResume()
        check("A20 resuming starts a count-in", s.frozen() && !s.paused,
            "resumeCount=${s.resumeCount}")
        val digits = HashSet<Int>()
        var counted = 0
        while (s.frozen()) {
            digits.add(s.resumeDigit())
            s.climbing = true
            s.update(1f); counted++
            if (counted > 1000) break
        }
        check("A20 the count-in is the length it says it is",
            counted == Tune.RESUME_COUNT.toInt(), "$counted ticks")
        check("A20 the count-in counts 3 - 2 - 1", digits == setOf(3, 2, 1),
            digits.sorted().toString())
        check("A20 the world is still frozen through the count-in",
            snapshot(s) == before, "every tick of the count-in, not all but one")
        // the shell that was committed before the pause is still committed
        val after = s.shots.filter { it.alive && it.kind == K_SHELL }.map { it.fuse }
        check("A20 a pause is not a way out of a committed shell",
            after.containsAll(shellFuses),
            "fuses before $shellFuses, after $after")

        // ---- and then it flies again --------------------------------------
        val atResume = s.py
        for (j in 0 until 30) { s.climbing = true; s.update(1f) }
        check("A20 the world moves again afterwards", s.py < atResume,
            String.format("%.2f -> %.2f", atResume, s.py))

        // ---- mute is remembered across a launch ---------------------------
        val store = MemStore()
        val a = Sim(store)
        check("A20 sound is on by default", !a.muted, "")
        a.toggleMute()
        check("A20 mute survives a relaunch", Sim(store).muted, "")
    }

    // =====================================================================
    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(78))
        println("DAWN PATROL - audit of the shipped Kotlin")
        println("=".repeat(78))
        a1ScrollStability()
        a2SeedSource()
        a3WrapSafety()
        a4ArtWithinHitbox()
        a5Terrain()
        a6DodgeWindow()
        a7BurstSeparation()
        a8BalloonClearance()
        a9Feel()
        a10Soak()
        a11Warmup()
        a12Lives()
        a13CeilingIsNotSafe()
        a14ScoutsAreDodgeable()
        a15GroundHonesty()
        a16CycleIsReachable()
        a17StartGate()
        a18NoMissingGlyphs()
        a19Warmup()
        a20Pause()
        println("=".repeat(78))
        println("${failures.size} checks failed")
        if (failures.isNotEmpty()) {
            failures.forEach { println("  - $it") }
            System.exit(1)
        }
    }
}
