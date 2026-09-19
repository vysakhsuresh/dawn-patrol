package com.dawnpatrol.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The whole game, as pure deterministic Kotlin. No android.* anywhere in
 * this file - that is what lets Audit.kt run thousands of simulated
 * sorties against the code that actually ships, instead of against a
 * Python re-implementation that can quietly drift from it.
 *
 * Everything is in grid rows and 60Hz ticks (see Tune.kt).
 */

/** Persistence, abstracted so Sim stays free of Android. */
interface Store {
    fun getFloat(key: String, def: Float): Float
    fun putFloat(key: String, v: Float)
    fun getString(key: String, def: String): String
    fun putString(key: String, v: String)
    fun flush()
}

class MemStore : Store {
    private val m = HashMap<String, String>()
    override fun getFloat(key: String, def: Float) = m[key]?.toFloatOrNull() ?: def
    override fun putFloat(key: String, v: Float) { m[key] = v.toString() }
    override fun getString(key: String, def: String) = m[key] ?: def
    override fun putString(key: String, v: String) { m[key] = v }
    override fun flush() {}
}

// ---- entities ---------------------------------------------------------
const val K_BULLET = 0
const val K_BOMB = 1
const val K_SHELL = 2
const val K_EBULLET = 3

class Shot {
    var alive = false
    var kind = 0
    var x = 0f; var y = 0f
    var vx = 0f; var vy = 0f
    var fuse = 0f          // shells: ticks until the burst
    var life = 0f
}

class Burst {
    var alive = false
    var x = 0f; var y = 0f
    var age = 0f
    var span = 0f          // ticks it stays on screen
    var lethal = 0f        // ticks it can still kill
    var r = 0f
}

/** An enemy scout. The answer to "nothing can reach me up high". */
class Enemy {
    var alive = false
    var x = 0f; var y = 0f
    var vx = 0f; var vy = 0f
    var fireTimer = 0f
}

/** A floating "+n" that rises off a kill - the feedback that makes a hit land. */
class Pip {
    var alive = false
    var x = 0f; var y = 0f; var age = 0f
    var text = ""
}

class Sim(private val store: Store) {

    // ---- flight ---------------------------------------------------------
    var camX = 0.0; private set
    var py = 0f; private set          // TOP row of the plane sprite
    var vy = 0f; private set
    var speed = Tune.SPEED_MIN; private set
    var climbing = false

    // ---- sortie ----------------------------------------------------------
    var distance = 0f; private set
    var crashed = false; private set
    var crashCause = ""; private set
    var crashTicks = 0f; private set
    var bombs = Tune.BOMB_MAX; private set
    private var bombReload = 0f
    private var gunTimer = 0f
    var firing = false

    // ---- greed -----------------------------------------------------------
    var mult = 1; private set
    private var lowTicks = 0f
    private var highTicks = 0f

    // ---- start gate -------------------------------------------------------
    // The sortie does not begin until the player touches. Until then the
    // aeroplane flies straight and level. Dropping someone into a falling
    // plane before they have found the controls reads as a crash, not a game.
    var started = false; private set
    var sorties = 0; private set

    // Three states, not two. The briefing card is its own step: the tap that
    // dismisses it must NOT also launch the sortie, because that tap is the
    // player saying "I have read this", not "I am ready to fly". Merging the
    // two meant the very first thing a new player ever saw was their
    // aeroplane falling out of the sky the instant they touched the screen.
    //
    //   BRIEFING  -> tap anywhere   -> READY
    //   READY     -> press LEFT     -> FLYING   (and that press is the climb)
    //
    // READY is the same straight-and-level loiter used between lives, so the
    // plane is always visibly flying before physics is handed over.
    private var briefed = false
    val showFullBriefing: Boolean get() = sorties == 0 && !briefed

    /** Put the briefing card away without launching anything. */
    fun dismissBriefing() { briefed = true }

    // ---- score --------------------------------------------------------------
    var score = 0; private set
    var bestScore = 0; private set

    // ---- lives ----------------------------------------------------------------
    var lives = Tune.LIVES; private set
    var gameOver = false; private set
    var invuln = 0f; private set
    var lifeFlash = 0f; private set      // ticks the "LIFE LOST" banner holds
    var lastLoss = ""; private set

    // ---- the line ---------------------------------------------------------
    var linePct = Tune.LINE_START; private set
    var lineDelta = 0f; private set   // this sortie only, for the end card

    // ---- tally -------------------------------------------------------------
    var killsAA = 0; private set
    var killsDepot = 0; private set
    var killsTank = 0; private set
    var killsBalloon = 0; private set
    var best = 0f; private set

    // ---- world state --------------------------------------------------------
    val destroyed = HashSet<Int>()

    // ---- pools -------------------------------------------------------------
    val shots = Array(72) { Shot() }
    val bursts = Array(24) { Burst() }
    val pips = Array(12) { Pip() }
    val enemies = Array(8) { Enemy() }
    private var enemyTimer = 0f

    // ---- AA bookkeeping ------------------------------------------------------
    private val gunTimers = HashMap<Int, Float>()
    var lockTicks = 0f; private set    // searchlight lock, night only
    var ticks = 0f; private set

    // scratch, reused so the update loop allocates nothing
    private val slotBuf = ArrayList<World.Slot>(24)
    private val trenchBuf = ArrayList<Pair<Double, UInt>>(8)

    init { load() }

    // =====================================================================
    //  lifecycle
    // =====================================================================
    private fun load() {
        linePct = store.getFloat("line", Tune.LINE_START).coerceIn(0f, 1f)
        best = store.getFloat("best", 0f)
        bestScore = store.getFloat("bestScore", 0f).toInt()
        sorties = store.getFloat("sorties", 0f).toInt()
        val d = store.getString("dead", "")
        if (d.isNotEmpty()) for (part in d.split(',')) part.toIntOrNull()?.let { destroyed.add(it) }
        reset()
    }

    private fun save() {
        store.putFloat("line", linePct)
        store.putFloat("best", best)
        store.putFloat("bestScore", bestScore.toFloat())
        store.putFloat("sorties", sorties.toFloat())
        // The world is endless, so the destroyed set cannot grow forever.
        // Keep the most recent slots - the ones the player may fly back over.
        val keep = destroyed.sortedDescending().take(512)
        store.putString("dead", keep.joinToString(","))
        store.flush()
    }

    private var introBaseY = (Art.HORIZON - 52).toFloat()

    fun reset() {
        camX = 0.0
        py = (Art.HORIZON - 52).toFloat()
        introBaseY = py
        vy = 0f
        speed = Tune.SPEED_MIN
        distance = 0f
        crashed = false
        crashCause = ""
        crashTicks = 0f
        bombs = Tune.BOMB_MAX
        bombReload = 0f
        gunTimer = 0f
        firing = false
        climbing = false
        mult = 1
        lowTicks = 0f
        highTicks = 0f
        lineDelta = 0f
        score = 0
        started = false
        lives = Tune.LIVES
        gameOver = false
        invuln = 0f
        lifeFlash = 0f
        lastLoss = ""
        enemyTimer = Tune.ENEMY_PERIOD
        for (e in enemies) e.alive = false
        killsAA = 0; killsDepot = 0; killsTank = 0; killsBalloon = 0
        lockTicks = 0f
        ticks = 0f
        for (s in shots) s.alive = false
        for (b in bursts) b.alive = false
        for (p in pips) p.alive = false
        gunTimers.clear()
    }

    /**
     * Is the end-of-run card ready to accept a tap? The lockout is what makes
     * GAME OVER actually stop the game rather than blink past.
     */
    fun canRestart(): Boolean =
        crashed && crashTicks >= Tune.GAMEOVER_LOCKOUT

    /** The LEFT press that ends the loiter and hands over to physics. */
    fun start() {
        if (started) return
        started = true
        sorties++
        vy = 0f
    }

    // =====================================================================
    //  world queries
    // =====================================================================
    fun visibleSlots(): ArrayList<World.Slot> {
        slotBuf.clear(); trenchBuf.clear()
        for (k in World.visibleSlots(camX, Art.GW.toDouble())) {
            val s = World.slot(k)
            slotBuf.add(s)
            if (s.type == World.TRENCH) trenchBuf.add(s.worldX to s.hash)
        }
        return slotBuf
    }

    fun trenches(): List<Pair<Double, UInt>> = trenchBuf

    fun groundAt(worldX: Double): Float =
        World.surfaceY(worldX, trenchBuf).toFloat()

    fun slotKeyAt(worldX: Double): Int = World.cell(worldX, Art.SLOT_W.toDouble())

    fun isDead(k: Int) = destroyed.contains(k)

    /**
     * The attitude actually drawn. Collision reads the SAME sprite, so the
     * shape you can see is the shape that can hit things.
     */
    fun planeSprite(): Array<String> = when {
        vy < -0.15f -> Art.SPR_PLANE_CLIMB
        vy > 0.15f -> Art.SPR_PLANE_DIVE
        else -> Art.SPR_PLANE
    }

    /**
     * Lowest drawn pixel in each column of a sprite, or -1 for an empty
     * column.
     *
     * The old ground test used one flat bottom edge across the full 22-column
     * box. But the bottom row of the plane is only the WHEELS - at the nose
     * and tail the lowest drawn pixel is three rows higher. So the box hung
     * below the artwork at both ends and you died with visible daylight under
     * the aeroplane. Per-column is the honest shape.
     */
    private val bottomProfiles = HashMap<Array<String>, IntArray>()

    fun bottomProfile(spr: Array<String>): IntArray = bottomProfiles.getOrPut(spr) {
        val w = spr.maxOf { it.length }
        IntArray(w) { col ->
            var lowest = -1
            for (r in spr.indices) if (col < spr[r].length && spr[r][col] == 'X') lowest = r
            lowest
        }
    }

    /** Altitude above the ground directly below, in rows. */
    fun altitude(): Float = groundAt(camX + Art.PLAYER_X) - (py + Tune.PLANE_H)

    /** 0 at the ground, 1 at the ceiling - the greed axis. */
    fun altFrac(): Float {
        val g = groundAt(camX + Art.PLAYER_X)
        val band = (g - Tune.PLANE_H - Tune.CEIL_ROW).coerceAtLeast(1f)
        return ((g - Tune.PLANE_H - py) / band).coerceIn(0f, 1f)
    }

    /** Which of the five day/night keyframes we are in, and how far through. */
    fun phase(): Float = ((camX / Tune.PHASE_LEN) % 5.0).toFloat().let {
        if (it < 0f) it + 5f else it
    }

    fun isNight(): Boolean = phase() >= 4f

    /** 0 at the start of a sortie, 1 once the front is fully awake. */
    fun threat(): Float =
        ((distance - Tune.WARMUP_ROWS) / Tune.RAMP_ROWS).coerceIn(0f, 1f)

    // =====================================================================
    //  update
    // =====================================================================
    fun update(dt: Float) {
        ticks += dt
        if (crashed) {
            crashTicks += dt
            stepShots(dt); stepBursts(dt); stepPips(dt)
            return
        }

        if (!started) {
            // Loiter: straight and level, world drifting past so the scene
            // reads as alive rather than frozen. No gravity, no guns, no flak.
            camX += Tune.INTRO_DRIFT * dt
            vy = 0f
            py = introBaseY + kotlin.math.sin((ticks * 0.035f).toDouble()).toFloat() *
                Tune.INTRO_BOB
            visibleSlots()
            return
        }

        visibleSlots()

        // ---- flight ------------------------------------------------------
        vy += if (climbing) -Tune.CLIMB_ACC * dt else Tune.GRAVITY * dt
        vy = vy.coerceIn(-Tune.VY_MAX_UP, Tune.VY_MAX_DN)
        py += vy * dt
        if (py < Tune.CEIL_ROW) { py = Tune.CEIL_ROW; if (vy < 0f) vy = 0f }

        val t = ((vy + Tune.VY_MAX_UP) / (Tune.VY_MAX_UP + Tune.VY_MAX_DN)).coerceIn(0f, 1f)
        speed = Tune.SPEED_MIN + (Tune.SPEED_MAX - Tune.SPEED_MIN) * t
        camX += speed * dt
        distance += speed * dt

        // the line creeps back while you are just sightseeing
        linePct = (linePct - Tune.LINE_CREEP * speed * dt).coerceIn(0f, 1f)

        // ---- greed meter --------------------------------------------------
        if (altitude() < Tune.LOW_ALT) {
            lowTicks += dt; highTicks = 0f
            val step = 1 + (lowTicks / Tune.MULT_STEP).toInt()
            mult = min(Tune.MULT_MAX, step)
        } else {
            highTicks += dt
            if (highTicks > Tune.MULT_DECAY) { lowTicks = 0f; mult = 1 }
        }

        // ---- weapons -------------------------------------------------------
        if (bombs < Tune.BOMB_MAX) {
            bombReload += dt
            if (bombReload >= Tune.BOMB_RELOAD) { bombReload = 0f; bombs++ }
        }
        gunTimer -= dt
        if (firing && gunTimer <= 0f) {
            gunTimer = Tune.GUN_PERIOD
            fireGun()
        }

        if (invuln > 0f) invuln -= dt
        if (lifeFlash > 0f) lifeFlash -= dt

        stepShots(dt); stepBursts(dt); stepPips(dt)
        stepAA(dt)
        stepEnemies(dt)
        stepSearchlight(dt)
        checkTerrain()
    }

    // ---- player weapons ---------------------------------------------------
    private fun fireGun() {
        val s = freeShot() ?: return
        s.alive = true; s.kind = K_BULLET
        s.x = (camX + Art.PLAYER_X + Tune.PLANE_W).toFloat()
        s.y = py + 4f
        s.vx = Tune.GUN_SPEED + speed
        s.vy = 0.35f
        s.life = Tune.GUN_RANGE / Tune.GUN_SPEED
    }

    fun dropBomb(): Boolean {
        if (bombs <= 0) return false
        val s = freeShot() ?: return false
        bombs--
        s.alive = true; s.kind = K_BOMB
        s.x = (camX + Art.PLAYER_X + 11).toFloat()
        s.y = py + Tune.PLANE_H
        s.vx = speed
        s.vy = max(0f, vy)
        s.life = 400f
        return true
    }

    private fun freeShot(): Shot? = shots.firstOrNull { !it.alive }
    private fun freeBurst(): Burst? = bursts.firstOrNull { !it.alive }
    private fun freePip(): Pip? = pips.firstOrNull { !it.alive }

    // ---- projectiles --------------------------------------------------------
    private fun stepShots(dt: Float) {
        for (s in shots) {
            if (!s.alive) continue
            when (s.kind) {
                K_BULLET -> s.vy += Tune.GUN_DROP * dt
                K_EBULLET -> { }
                K_BOMB -> s.vy += Tune.BOMB_GRAV * dt
                K_SHELL -> {
                    s.fuse -= dt
                    if (s.fuse <= 0f) { detonate(s.x, s.y, Tune.FLAK_LETHAL, true); s.alive = false; continue }
                }
            }
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.life -= dt
            if (s.life <= 0f) { s.alive = false; continue }
            if (s.x < camX - 40 || s.x > camX + Art.GW + 60) { s.alive = false; continue }

            if (s.kind == K_EBULLET) {
                if (invuln <= 0f && !crashed &&
                    boxHitsPlane(s.x - 1f, s.y - 1f, 2f, 2f)) {
                    s.alive = false
                    hit("SCOUT")
                    continue
                }
                if (s.y >= groundAt(s.x.toDouble())) s.alive = false
                continue
            }

            if (s.kind == K_BULLET && hitEnemy(s)) { s.alive = false; continue }

            if (s.kind == K_BULLET || s.kind == K_BOMB) {
                if (hitSomething(s)) { s.alive = false; continue }
                val g = groundAt(s.x.toDouble())
                if (s.y >= g) {
                    if (s.kind == K_BOMB) {
                        detonate(s.x, g - 3f, Tune.BOMB_BLAST, false)
                        damageGround(s.x, Tune.BOMB_BLAST)
                    } else {
                        dustPuff(s.x, g)
                    }
                    s.alive = false
                }
            }
        }
    }

    private fun hitEnemy(s: Shot): Boolean {
        for (e in enemies) {
            if (!e.alive) continue
            if (s.x > e.x - 2f && s.x < e.x + Tune.PLANE_W &&
                s.y > e.y - 2f && s.y < e.y + Tune.PLANE_H + 2f) {
                e.alive = false
                val earned = Tune.PTS_ENEMY * mult
                score += earned
                detonate(e.x + 8f, e.y + 4f, 8f, false)
                freePip()?.let {
                    it.alive = true; it.x = e.x; it.y = e.y - 8f; it.age = 0f
                    it.text = "+" + earned
                }
                return true
            }
        }
        return false
    }

    /** Bullets/bombs vs standing targets (AA, tanks, searchlights, balloons). */
    private fun hitSomething(s: Shot): Boolean {
        for (sl in slotBuf) {
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            if (destroyed.contains(k)) continue
            val sx = sl.worldX.toFloat()
            when (sl.type) {
                World.AA_GUN, World.LIGHT, World.TANK -> {
                    val g = groundAt(sl.worldX)
                    if (abs(s.x - sx) < 8f && s.y > g - 11f && s.y < g + 1f) {
                        killSlot(k, sl.type, sx, g); return true
                    }
                }
                World.BALLOON -> {
                    val top = balloonTop(sl)
                    val bw = Art.SPR_BALLOON[0].length
                    val bh = Art.SPR_BALLOON.size
                    if (abs(s.x - sx) < bw / 2f && s.y > top && s.y < top + bh) {
                        killSlot(k, sl.type, sx, top + bh / 2f); return true
                    }
                }
                World.DEPOT -> {
                    if (s.kind != K_BOMB) continue   // depots need a bomb
                    val g = groundAt(sl.worldX)
                    if (abs(s.x - sx) < 11f && s.y > g - 12f) {
                        killSlot(k, sl.type, sx, g); return true
                    }
                }
                else -> {}
            }
        }
        return false
    }

    /** A bomb blast also takes out anything within its radius. */
    private fun damageGround(bx: Float, r: Float) {
        for (sl in slotBuf) {
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            if (destroyed.contains(k)) continue
            if (sl.type == World.BALLOON) continue
            if (abs(sl.worldX.toFloat() - bx) <= r) {
                killSlot(k, sl.type, sl.worldX.toFloat(), groundAt(sl.worldX))
            }
        }
    }

    private fun killSlot(k: Int, type: Int, x: Float, y: Float) {
        if (!destroyed.add(k)) return
        var pts = 0
        val gain = when (type) {
            World.DEPOT -> { killsDepot++; pts = Tune.PTS_DEPOT; Tune.LINE_DEPOT }
            World.AA_GUN -> { killsAA++; pts = Tune.PTS_AA; Tune.LINE_AA }
            World.TANK -> { killsTank++; pts = Tune.PTS_TANK; Tune.LINE_TANK }
            World.BALLOON -> { killsBalloon++; pts = Tune.PTS_BALLOON; Tune.LINE_BALLOON }
            World.LIGHT -> { killsAA++; pts = Tune.PTS_LIGHT; Tune.LINE_AA }
            else -> 0f
        } * mult
        val earned = pts * mult
        score += earned
        linePct = (linePct + gain).coerceIn(0f, 1f)
        lineDelta += gain
        detonate(x, y - 4f, 7f, false)
        freePip()?.let {
            it.alive = true; it.x = x; it.y = y - 14f; it.age = 0f
            it.text = "+" + earned
        }
        gunTimers.remove(k)
    }

    private fun detonate(x: Float, y: Float, r: Float, lethal: Boolean) {
        val b = freeBurst() ?: return
        b.alive = true; b.x = x; b.y = y; b.age = 0f
        b.span = 26f; b.r = r
        b.lethal = if (lethal) 5f else 0f
    }

    private fun dustPuff(x: Float, y: Float) {
        val b = freeBurst() ?: return
        b.alive = true; b.x = x; b.y = y - 1f; b.age = 0f
        b.span = 9f; b.r = 3f; b.lethal = 0f
    }

    private fun stepBursts(dt: Float) {
        for (b in bursts) {
            if (!b.alive) continue
            b.age += dt
            if (b.lethal > 0f) {
                b.lethal -= dt
                if (!crashed && invuln <= 0f && burstHitsPlane(b)) hit("FLAK")
            }
            if (b.age >= b.span) b.alive = false
        }
    }

    private fun burstHitsPlane(b: Burst): Boolean {
        // plane box vs burst circle
        val x0 = (camX + Art.PLAYER_X).toFloat()
        val x1 = x0 + Tune.PLANE_W
        val y0 = py
        val y1 = py + Tune.PLANE_H
        val cx = b.x.coerceIn(x0, x1)
        val cy = b.y.coerceIn(y0, y1)
        val dx = b.x - cx; val dy = b.y - cy
        return dx * dx + dy * dy <= b.r * b.r
    }

    private fun stepPips(dt: Float) {
        for (p in pips) {
            if (!p.alive) continue
            p.age += dt
            p.y -= 0.12f * dt
            if (p.age > 46f) p.alive = false
        }
    }

    // ---- anti-aircraft --------------------------------------------------------
    private fun stepAA(dt: Float) {
        if (distance < Tune.WARMUP_ROWS) return
        val px = (camX + Art.PLAYER_X + Tune.PLANE_W / 2f).toFloat()
        val pyC = py + Tune.PLANE_H / 2f

        for (sl in slotBuf) {
            if (sl.type != World.AA_GUN) continue
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            if (destroyed.contains(k)) continue

            val gx = sl.worldX.toFloat()
            val gy = groundAt(sl.worldX) - 8f
            val dx = px - gx; val dy = pyC - gy
            val range = sqrt(dx * dx + dy * dy)

            var timer = gunTimers[k] ?: run {
                // stagger first shots so a battery never fires in lockstep
                val j = ((World.hash32(k * 31 + 5) and 0xFFFFu).toFloat() / 65535f)
                val v = Tune.AA_PERIOD * 0.4f + j * Tune.AA_PERIOD_JITTER
                gunTimers[k] = v; v
            }
            timer -= dt

            if (timer <= 0f && range >= Tune.MIN_ENGAGE && range <= Tune.MAX_ENGAGE) {
                if (fireShell(gx, gy, px, pyC, k)) {
                    val j = ((World.hash32(k * 977 + ticks.toInt()) and 0xFFFFu).toFloat() / 65535f)
                    val period = Tune.AA_PERIOD * (1f - 0.42f * threat())
                    timer = period + j * Tune.AA_PERIOD_JITTER
                } else {
                    timer = 8f   // blocked by the fuse-separation rule; retry soon
                }
            }
            gunTimers[k] = timer
        }
    }

    /**
     * Solve a real intercept on the player's CURRENT velocity and fire a
     * time-fuzed shell at it. Holding a steady line is what kills you.
     *
     * Returns false if firing now would put two lethal bursts inside one
     * dodge window - see FUSE_SEP. That rule is what makes "always
     * dodgeable" true for the whole battery, not just one gun.
     */
    private fun fireShell(gx: Float, gy: Float, px: Float, pyC: Float, k: Int): Boolean {
        val vx = speed
        val vyy = vy
        val dx = px - gx; val dy = pyC - gy
        val s2 = Tune.SHELL_SPEED * Tune.SHELL_SPEED
        val a = vx * vx + vyy * vyy - s2
        val b = 2f * (dx * vx + dy * vyy)
        val c = dx * dx + dy * dy

        val t: Float = if (abs(a) < 1e-4f) {
            if (abs(b) < 1e-6f) return false else -c / b
        } else {
            val disc = b * b - 4f * a * c
            if (disc < 0f) return false
            val sq = sqrt(disc)
            val t1 = (-b + sq) / (2f * a)
            val t2 = (-b - sq) / (2f * a)
            val cand = listOf(t1, t2).filter { it > 0f }
            if (cand.isEmpty()) return false else cand.min()
        }
        if (t <= 0f || t > 400f) return false

        // the guarantee: never two lethal bursts inside one dodge window
        for (o in shots) {
            if (o.alive && o.kind == K_SHELL && abs(o.fuse - t) < FUSE_SEP) return false
        }
        if (shots.count { it.alive && it.kind == K_SHELL } >= MAX_SHELLS) return false

        // aim error: precise down low, guesswork up high - the greed dial
        val err = if (lockTicks > 0f) Tune.AIM_ERR_LOCKED
        else Tune.AIM_ERR_LOW + (Tune.AIM_ERR_HIGH - Tune.AIM_ERR_LOW) * altFrac()
        val sign = if ((World.hash32(k * 7919 + ticks.toInt()) and 1u) == 0u) 1f else -1f
        val jit = ((World.hash32(k * 104729 + ticks.toInt() * 7) and 0xFFFFu).toFloat() / 65535f)

        val aimX = px + vx * t
        val aimY = pyC + vyy * t + sign * jit * err

        val sh = freeShot() ?: return false
        val ddx = aimX - gx; val ddy = aimY - gy
        val d = sqrt(ddx * ddx + ddy * ddy)
        if (d < 1e-3f) return false
        sh.alive = true; sh.kind = K_SHELL
        sh.x = gx; sh.y = gy
        sh.vx = ddx / d * Tune.SHELL_SPEED
        sh.vy = ddy / d * Tune.SHELL_SPEED
        sh.fuse = t
        sh.life = t + 6f
        return true
    }

    // ---- enemy scouts ------------------------------------------------------------
    private fun stepEnemies(dt: Float) {
        if (distance > Tune.WARMUP_ROWS) {
            enemyTimer -= dt
            if (enemyTimer <= 0f) {
                val period = Tune.ENEMY_PERIOD +
                    (Tune.ENEMY_PERIOD_MIN - Tune.ENEMY_PERIOD) * threat()
                val j = ((World.hash32(ticks.toInt() * 31 + 7) and 0xFFFFu).toFloat() / 65535f)
                enemyTimer = period * (0.7f + 0.6f * j)
                spawnEnemy()
            }
        }
        val pxc = (camX + Art.PLAYER_X + Tune.PLANE_W / 2f).toFloat()
        val pyc = py + Tune.PLANE_H / 2f
        for (e in enemies) {
            if (!e.alive) continue
            // steer gently toward the player's altitude: there is no longer
            // an altitude that nothing can reach
            val want = ((pyc - e.y) * Tune.ENEMY_TRACK)
                .coerceIn(-Tune.ENEMY_VY, Tune.ENEMY_VY)
            e.vy += (want - e.vy) * 0.08f * dt
            e.x += e.vx * dt
            e.y += e.vy * dt
            e.y = e.y.coerceIn(Tune.CEIL_ROW, groundAt(e.x.toDouble()) - Tune.PLANE_H - 2f)

            e.fireTimer -= dt
            val lead = e.x - pxc
            if (e.fireTimer <= 0f && lead > 16f && lead < 74f) {
                e.fireTimer = Tune.ENEMY_FIRE_PERIOD
                fireEnemyBullet(e, pxc, pyc)
            }

            if (e.x < camX - 24) { e.alive = false; continue }
            if (invuln <= 0f && !crashed && boxHitsPlane(e.x, e.y, Tune.PLANE_W.toFloat(),
                    Tune.PLANE_H.toFloat())) {
                e.alive = false
                detonate(e.x, e.y, 9f, false)
                hit("COLLISION")
            }
        }
    }

    private fun spawnEnemy() {
        val e = enemies.firstOrNull { !it.alive } ?: return
        e.alive = true
        e.x = (camX + Art.GW + 14).toFloat()
        // enter near the player's band so it is visible on approach
        val h = World.hash32(ticks.toInt() * 7919 + 3)
        val off = (((h shr 5) and 0x3Fu).toInt() - 32) * 0.8f
        e.y = (py + off).coerceIn(Tune.CEIL_ROW + 2f,
            groundAt(e.x.toDouble()) - Tune.PLANE_H - 6f)
        e.vx = -Tune.ENEMY_SPEED
        e.vy = 0f
        e.fireTimer = Tune.ENEMY_FIRE_PERIOD * 0.6f
    }

    private fun fireEnemyBullet(e: Enemy, pxc: Float, pyc: Float) {
        val s = freeShot() ?: return
        val dx = pxc - e.x; val dy = pyc - e.y
        val d = sqrt(dx * dx + dy * dy)
        if (d < 1f) return
        s.alive = true; s.kind = K_EBULLET
        s.x = e.x; s.y = e.y + 4f
        s.vx = dx / d * Tune.EBULLET_SPEED - speed * 0.25f
        s.vy = dy / d * Tune.EBULLET_SPEED
        s.life = 150f
    }

    private fun boxHitsPlane(x: Float, y: Float, w: Float, h: Float): Boolean {
        val ax0 = (camX + Art.PLAYER_X).toFloat(); val ax1 = ax0 + Tune.PLANE_W
        val ay0 = py; val ay1 = py + Tune.PLANE_H
        return x < ax1 && x + w > ax0 && y < ay1 && y + h > ay0
    }

    // ---- searchlights -----------------------------------------------------------
    private fun stepSearchlight(dt: Float) {
        if (!isNight()) { lockTicks = max(0f, lockTicks - dt); return }
        var lit = false
        val px = (camX + Art.PLAYER_X + Tune.PLANE_W / 2f).toFloat()
        val pyC = py + Tune.PLANE_H / 2f
        for (sl in slotBuf) {
            if (sl.type != World.LIGHT) continue
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            if (destroyed.contains(k)) continue
            val bx = sl.worldX.toFloat()
            val by = groundAt(sl.worldX) - 7f
            val ang = beamAngle(k)
            val dx = px - bx; val dy = pyC - by
            val d = sqrt(dx * dx + dy * dy)
            if (d > Tune.BEAM_RANGE) continue
            val a = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
            var da = a - ang
            while (da > Math.PI.toFloat()) da -= 2f * Math.PI.toFloat()
            while (da < -Math.PI.toFloat()) da += 2f * Math.PI.toFloat()
            if (abs(da) <= Tune.BEAM_SPREAD) { lit = true; break }
        }
        lockTicks = if (lit) Tune.LOCK_GRACE else max(0f, lockTicks - dt)
    }

    /** Sweep angle for a searchlight, derived from world position + time. */
    fun beamAngle(k: Int): Float {
        val phase = ((World.hash32(k * 15485863 + 3) and 0xFFFFu).toFloat() / 65535f) * 6.2832f
        val sweep = kotlin.math.sin((ticks * Tune.BEAM_SWEEP + phase).toDouble()).toFloat()
        return (-Math.PI.toFloat() / 2f) + sweep * 0.72f
    }

    /**
     * Balloons sit mid-band, not up near the ceiling. Higher balloons look
     * more dramatic but the only safe path is OVER the envelope (the cable
     * below it is lethal all the way to the ground), and Audit A8 showed a
     * pilot caught low could not physically climb over one in the runway
     * they get after it comes into view. Lower balloon = bigger gap above.
     */
    fun balloonTop(sl: World.Slot): Float =
        Tune.BALLOON_TOP_MIN + ((sl.hash shr 3) and 15u).toInt() *
            (Tune.BALLOON_TOP_SPAN / 15f)

    // ---- terrain & obstacles ------------------------------------------------------
    private fun checkTerrain() {
        val x0 = (camX + Art.PLAYER_X)
        val x1 = x0 + Tune.PLANE_W
        val bottom = py + Tune.PLANE_H

        // Column by column against the sprite's real underside, so the wheels
        // touch the mud at the moment the wheels touch the mud.
        val spr = planeSprite()
        val prof = bottomProfile(spr)
        var touched = false
        var rest = Float.MAX_VALUE
        for (col in prof.indices) {
            val b = prof[col]
            if (b < 0) continue
            val gy = groundAt(x0 + col)
            if (py + b >= gy) touched = true
            rest = min(rest, gy - b - 1f)
        }
        if (touched) {
            // settle the wreck onto the surface instead of leaving it buried
            if (rest < Float.MAX_VALUE) py = min(py, rest)
            die("GROUND")
            return
        }

        // balloon envelopes and their cables
        for (sl in slotBuf) {
            if (sl.type != World.BALLOON) continue
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            if (destroyed.contains(k)) continue
            val bx = sl.worldX.toFloat()
            if (bx < x0 - 12 || bx > x1 + 12) continue
            val top = balloonTop(sl)
            val bh = Art.SPR_BALLOON.size
            val bw = Art.SPR_BALLOON[0].length
            val g = groundAt(sl.worldX)
            val overlapX = bx + bw / 2f >= x0 && bx - bw / 2f <= x1
            val cableX = bx >= x0 && bx <= x1
            if (overlapX && bottom >= top && py <= top + bh) { die("BALLOON"); return }
            if (cableX && bottom >= top + bh && py <= g) { die("CABLE"); return }
        }
    }

    /**
     * Take a lethal hit. Costs a life, not automatically the run.
     *
     * Losing a life keeps the world, the score and the wrecks you have made
     * - you lose a machine and get put back in the air with a moment of
     * grace. Only running out of machines ends the sortie.
     */
    private fun hit(cause: String) {
        if (crashed || invuln > 0f) return
        detonate((camX + Art.PLAYER_X + 11).toFloat(), py + 4f, 11f, false)
        lives--
        lastLoss = cause
        lifeFlash = Tune.LIFE_FLASH
        if (lives <= 0) {
            lives = 0
            crashed = true
            gameOver = true
            crashCause = cause
            crashTicks = 0f
            if (distance > best) best = distance
            if (score > bestScore) bestScore = score
            save()
        } else {
            respawn()
        }
    }

    private fun respawn() {
        py = (groundAt(camX + Art.PLAYER_X) - Tune.RESPAWN_ALT)
            .coerceAtLeast(Tune.CEIL_ROW + 6f)
        vy = 0f
        invuln = Tune.INVULN
        mult = 1
        lowTicks = 0f
        // do not put the player back into the middle of an attack they
        // cannot see the start of
        for (s in shots) if (s.kind == K_SHELL || s.kind == K_EBULLET) s.alive = false
        for (b in bursts) b.lethal = 0f
        for (e in enemies) if (e.x - camX < Art.GW) e.alive = false
    }

    private fun die(cause: String) = hit(cause)

    companion object {
        const val FUSE_SEP = 15f    // ticks: minimum gap between lethal bursts
        const val MAX_SHELLS = 3
    }
}
