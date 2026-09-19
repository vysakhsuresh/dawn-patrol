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
        println("=".repeat(78))
        println("${failures.size} checks failed")
        if (failures.isNotEmpty()) {
            failures.forEach { println("  - $it") }
            System.exit(1)
        }
    }
}
