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
// ---- the three states of a sortie ------------------------------------
const val LEG_OUT = 0
const val LEG_HOME = 1
const val LEG_DOWN = 2

// ---- the five things that can break ----------------------------------
const val PART_ENGINE = 0
const val PART_FUEL = 1
const val PART_ELEVATOR = 2
const val PART_WING = 3
const val PART_GUNS = 4
val PART_NAME = arrayOf("ENGINE", "FUEL", "ELEVATOR", "WING", "GUNS")

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
    var grazed = false     // already paid out as a near miss
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
    private var lowRows = 0f      // fractional carry for the LOW ROWS order
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

    // ---- pause ------------------------------------------------------------
    // Leaving the app must freeze the sortie, not feed it to the flak. This
    // lives in Sim rather than in the Android layer so the audit can prove
    // that a paused world really is stopped - every single thing about it,
    // not just the aeroplane.
    var paused = false; private set
    var resumeCount = 0f; private set

    /** Freeze the sortie. Only meaningful once one is actually in progress. */
    fun pause() { if (started && !crashed) paused = true }

    /** Start the count-in. The world stays frozen until it runs out. */
    fun requestResume() {
        if (!paused) return
        paused = false
        resumeCount = Tune.RESUME_COUNT
    }

    /** 3, 2, 1 - what to print during the count-in, or 0 for "not counting". */
    fun resumeDigit(): Int =
        if (resumeCount <= 0f) 0 else ((resumeCount / 60f).toInt() + 1).coerceAtMost(3)

    /** True whenever the world is deliberately stopped. */
    fun frozen(): Boolean = paused || resumeCount > 0f

    // ---- sound ------------------------------------------------------------
    // Kept here, not in the Android layer, so it persists with everything
    // else the player has earned and the renderer can draw the right icon.
    var muted = false; private set

    fun toggleMute() {
        muted = !muted
        store.putFloat("muted", if (muted) 1f else 0f)
        store.flush()
    }

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

    /**
     * Where the next sortie takes off from. THE fix for the worst thing
     * about this game: every run used to start at worldX 0, so every run
     * flew the identical ground, and since destroyed slots persist the
     * reward for identical play decayed by 71% over four sorties and then
     * flatlined. The front is a real place now - win ground and you get
     * fresh country, lose it and you fly back over your own wrecks.
     */
    var frontX = 0.0; private set

    /** Rows of new ground the last sortie won (or gave up, if negative). */
    var frontMoved = 0f; private set
    /** Sectors taken this sortie - the thing worth bragging about. */
    var sectors = 0; private set

    // ---- the sortie arc ------------------------------------------------
    // TAKE OFF -> OBJECTIVE -> RETURN TO BASE, and the score is only yours
    // if you land. `pending` is everything earned this sortie and it is at
    // risk the whole way home; `score` is what has actually been banked.
    var leg = LEG_OUT; private set
    var landed = false; private set
    var objectiveX = 0.0; private set
    var baseX = 0.0; private set
    var pending = 0; private set
    var turnFlash = 0f; private set
    /** Times the strip was missed and flown round again. */
    var goArounds = 0; private set
    var landFlash = 0f; private set
    /** Endless mode: no objective, no landing, fly until you are shot down. */
    var endless = false

    // ---- damage ---------------------------------------------------------
    // One flag per part. `damaged.size` is also the length of the smoke
    // trail, so the readout and the state cannot drift apart.
    val damaged = HashSet<Int>()
    var fuel = Tune.FUEL_FULL; private set
    private var lagBuf = FloatArray(16)
    private var lagHead = 0

    fun isDamaged(part: Int) = damaged.contains(part)
    fun damageCount() = damaged.size

    /** Climb authority, after whatever is broken. */
    fun climbAcc(): Float =
        Tune.CLIMB_ACC * (if (isDamaged(PART_ENGINE)) Tune.DMG_CLIMB else 1f)

    fun speedMax(): Float =
        Tune.SPEED_MAX * (if (isDamaged(PART_ENGINE)) Tune.DMG_SPEED else 1f)

    fun gunPeriod(): Float =
        Tune.GUN_PERIOD * (if (isDamaged(PART_GUNS)) Tune.DMG_GUN_MULT else 1f)

    /** Ticks of fuel left, as a fraction. Only meaningful on the way home. */
    fun fuelFrac(): Float = (fuel / Tune.FUEL_FULL).coerceIn(0f, 1f)
    var sectorFlash = 0f; private set

    // ---- orders and rank ----------------------------------------------------
    var rank = 0; private set
    var orders: Array<Order> = Orders.forRank(0).map {
        Order(it, Orders.target(it, 0)) }.toTypedArray()
    var promoted = false; private set       // this sortie earned a promotion
    var ordersDone = 0; private set         // orders cleared this sortie

    // ---- chain and graze -----------------------------------------------------
    var chain = 0; private set
    var bestChain = 0; private set
    private var chainTimer = 0f
    var chainFlash = 0f; private set
    var grazes = 0; private set
    var grazeFlash = 0f; private set

    // ---- tally -------------------------------------------------------------
    var killsAA = 0; private set
    var killsDepot = 0; private set
    var killsTank = 0; private set
    var killsBalloon = 0; private set
    var best = 0f; private set
    /** The best BEFORE this run, so the card can say how close it came. */
    var lastBest = 0f; private set
    /** Points that went down with the aeroplane. The sting on the card. */
    var lostScore = 0; private set
    /** Rows from base at the moment it ended - the "so near" number. */
    var rowsFromBase = 0f; private set

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
        muted = store.getFloat("muted", 0f) > 0.5f
        frontX = store.getFloat("front", 0f).toDouble()
        rank = store.getFloat("rank", 0f).toInt().coerceAtLeast(0)
        makeOrders()
        // progress is stored as three plain numbers; a set that no longer
        // matches the rank (because the ladder was retuned) just starts over
        // rather than restoring nonsense
        val saved = store.getString("orders", "")
        val parts = saved.split(',')
        if (parts.size == orders.size) {
            for (i in orders.indices) parts[i].toIntOrNull()?.let { orders[i].restore(it) }
        }
        val d = store.getString("dead", "")
        if (d.isNotEmpty()) for (part in d.split(',')) part.toIntOrNull()?.let { destroyed.add(it) }
        reset()
    }

    private fun save() {
        store.putFloat("line", linePct)
        store.putFloat("best", best)
        store.putFloat("bestScore", bestScore.toFloat())
        store.putFloat("sorties", sorties.toFloat())
        store.putFloat("front", frontX.toFloat())
        store.putFloat("rank", rank.toFloat())
        store.putString("orders", orders.joinToString(",") { it.progress.toString() })
        // The world is endless, so the destroyed set cannot grow forever.
        // Keep the most recent slots - the ones the player may fly back over.
        val keep = destroyed.sortedDescending().take(512)
        store.putString("dead", keep.joinToString(","))
        store.flush()
    }

    private var introBaseY = (Art.HORIZON - 52).toFloat()

    private fun makeOrders() {
        orders = Orders.forRank(rank).map { Order(it, Orders.target(it, rank)) }
            .toTypedArray()
    }

    /**
     * Bank what the sortie achieved, then move the front.
     *
     * Called once when a run ends, never per-frame, so the front can only
     * move by whole sorties and a player can always point at the thing that
     * moved it.
     */
    /**
     * The meter is full: the sector is taken.
     *
     * This is the payoff the game was missing. It can fire mid-sortie,
     * which is the point - it is a thing that HAPPENS to you while you are
     * flying, not a number you read on a card afterwards. The front jumps a
     * sector, the meter resets to face the next one, and the next sortie
     * launches over ground nobody has flown.
     */
    /**
     * The sortie arc: out to the objective, turn, home, land.
     *
     * The turn is automatic at the objective rather than a button, because
     * the decision the game wants from the player is "how much more do I
     * squeeze on the way back", not "may I turn round now".
     */
    private fun stepLeg() {
        if (endless) return
        when (leg) {
            LEG_OUT -> if (goArounds > 0) {
                // a go-around is a circuit, not a second sortie: turn back
                // in as soon as there is room to line up again
                if (noseWorldX() >= baseX + Tune.LAND_ZONE + 120f) { dir = -1; leg = LEG_HOME }
            } else if (noseWorldX() >= objectiveX) {
                leg = LEG_HOME
                dir = -1
                turnFlash = Tune.TURN_FLASH
                // the turn itself is worth something: you got there
                pending += Tune.PTS_SECTOR / 3 * mult
            }
            LEG_HOME -> {
                // over the strip: any point along it will do
                if (noseWorldX() <= baseX + Tune.LAND_ZONE) tryLand()
                // ...and if it sails past the west end, go round again
                if (!landed && noseWorldX() < baseX - Tune.GOAROUND_OVERSHOOT) {
                    dir = 1
                    leg = LEG_OUT
                    goArounds++
                    turnFlash = Tune.TURN_FLASH * 0.6f
                }
            }
            else -> {}
        }
    }

    /**
     * Touchdown. You have to be low AND not falling out of the sky: an
     * aeroplane arriving over the aerodrome at terminal sink has crashed,
     * it has not landed, and letting that count would make the whole
     * return leg free.
     */
    private fun tryLand() {
        if (altitude() > Tune.LAND_ALT || vy > Tune.LAND_SPEED) {
            // Too high is simply not landed yet - keep flying down the
            // strip. Too LOW and still sinking hard is an arrival, not a
            // landing, and the ground collision will say so.
            return
        }
        leg = LEG_DOWN
        landed = true
        landFlash = Tune.LIFE_FLASH
        pending += Tune.PTS_LANDED * mult
        bankPending()
        crashed = true          // the sortie is over, in the good way
        gameOver = true
        crashCause = "LANDED"
        crashTicks = 0f
        lastBest = best
        if (distance > best) best = distance
        if (score > bestScore) bestScore = score
        endSortie()
    }

    /**
     * Earn points. They are PENDING - at risk until the wheels are down.
     * In endless mode there is no landing, so they bank as they are made.
     */
    private fun earn(n: Int) {
        pending += n
        if (endless) bankPending()
    }

    /** Score as the player sees it: banked plus whatever is still at risk. */
    fun shownScore(): Int = score + pending

    /** Move everything earned this sortie into the score that is kept. */
    private fun bankPending() {
        score += pending
        pending = 0
    }

    /** Spend the greed meter on getting home instead of on more greed. */
    fun repairOne(): Boolean {
        if (mult < Tune.COST_REPAIR || damaged.isEmpty() || crashed) return false
        val part = damaged.min()
        damaged.remove(part)
        mult = 1; lowTicks = 0f
        freePip()?.let {
            it.alive = true; it.x = planeWorldX().toFloat(); it.y = py - 10f
            it.age = 0f; it.text = PART_NAME[part] + " FIXED"
        }
        return true
    }

    fun rearm(): Boolean {
        if (mult < Tune.COST_REARM || bombs >= Tune.BOMB_MAX || crashed) return false
        bombs = Tune.BOMB_MAX
        mult = 1; lowTicks = 0f
        freePip()?.let {
            it.alive = true; it.x = planeWorldX().toFloat(); it.y = py - 10f
            it.age = 0f; it.text = "REARMED"
        }
        return true
    }

    /** What a tap on the spend button would do right now, for the HUD. */
    fun spendLabel(): String = when {
        damaged.isNotEmpty() && mult >= Tune.COST_REPAIR -> "FIX"
        bombs < Tune.BOMB_MAX && mult >= Tune.COST_REARM -> "ARM"
        else -> ""
    }

    fun spend(): Boolean = repairOne() || rearm()

    private fun captureSector() {
        frontX += Tune.SECTOR_ROWS
        sectors++
        sectorFlash = Tune.LIFE_FLASH
        linePct = Tune.LINE_START
        // taken ground cannot be lost on the way home, so this banks now
        score += Tune.PTS_SECTOR * mult
        save()
    }

    private fun endSortie() {
        // YOU HOLD THE GROUND YOU FLEW OVER, AS FAR AS THE LINE SAYS YOU DO.
        //
        // One sentence, and the meter in the HUD now means something exact:
        // it is the fraction of today's ground you keep. Fly 2000 rows with
        // the line at 0.8 and the next sortie starts 1600 rows further on,
        // over country nobody has seen. Fly the same 2000 rows achieving
        // nothing and you keep almost none of it and have to do it again.
        //
        // Captures (filling the meter) have already banked a whole sector
        // the moment they happened, so a capture counts even if the sortie
        // ends badly ten seconds later.
        // Ground is only consolidated by a machine that came home. Shot
        // down, you hold half of what you fought over - the rest is
        // retaken while the squadron is short an aeroplane.
        frontMoved = distance * linePct * (if (landed) 1f else 0.5f)
        frontX += frontMoved
        // orders that measure a whole sortie are settled here
        bumpOrder(Orders.ROWS, distance.toInt())
        // Credit what the sortie EARNED, not only what it banked. Points
        // that went down with the aeroplane were still shot for, and A23
        // caught 6 of 12 sorties banking nothing the moment score became
        // conditional on landing.
        bumpOrder(Orders.SCORE, score + lostScore)
        reachOrder(Orders.CHAIN, bestChain)
        if (orders.all { it.done } && !Orders.isTopRank(rank)) {
            rank++
            promoted = true
            makeOrders()
        } else if (orders.all { it.done }) {
            // at the top of the ladder the orders keep coming, harder
            promoted = true
            makeOrders()
        }
        save()
    }

    private fun bumpOrder(type: Int, n: Int) {
        for (o in orders) if (o.type == type && o.bump(n)) ordersDone++
    }

    private fun reachOrder(type: Int, v: Int) {
        for (o in orders) if (o.type == type && o.reach(v)) ordersDone++
    }

    fun reset() {
        // Launch from the front line, not from worldX 0. This one line is
        // what makes a second sortie a different game from the first.
        camX = frontX
        dir = 1
        leg = LEG_OUT
        landed = false
        lostScore = 0
        rowsFromBase = 0f
        baseX = frontX
        objectiveX = frontX + Tune.SORTIE_LEN
        pending = 0
        turnFlash = 0f; landFlash = 0f
        goArounds = 0
        damaged.clear()
        fuel = Tune.FUEL_FULL
        lagBuf = FloatArray(16); lagHead = 0
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
        paused = false
        resumeCount = 0f
        chain = 0; bestChain = 0; chainTimer = 0f; chainFlash = 0f
        grazes = 0; grazeFlash = 0f
        promoted = false; ordersDone = 0
        frontMoved = 0f; sectors = 0; sectorFlash = 0f
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
    /**
     * The features on screen, with the ground around your own aerodrome
     * made friendly.
     *
     * The world generator seeds from absolute position and knows nothing
     * about where your base is, so it will happily put a barrage balloon
     * on your landing strip - and a balloon cable is lethal all the way to
     * the ground, which makes the approach not hard but IMPOSSIBLE. Audit
     * A26 could not land, and the cause looked like pilot error for three
     * rounds. Inside HOME_SAFE the hostile furniture becomes sandbags:
     * your own aerodrome has hangars, not enemy flak.
     *
     * World itself is untouched, so A1/A2 stability still holds - this is
     * a view of the world, not a change to it.
     */
    fun visibleSlots(): ArrayList<World.Slot> {
        slotBuf.clear(); trenchBuf.clear()
        for (k in World.visibleSlots(camX, Art.GW.toDouble())) {
            var s = World.slot(k)
            if (!endless && kotlin.math.abs(s.worldX - baseX) < Tune.HOME_SAFE &&
                s.type != World.TRENCH && s.type != World.WRECK) {
                s = World.Slot(World.SANDBAG, s.worldX, s.hash)
            }
            slotBuf.add(s)
            if (s.type == World.TRENCH) trenchBuf.add(s.worldX to s.hash)
        }
        return slotBuf
    }

    fun trenches(): List<Pair<Double, UInt>> = trenchBuf

    /**
     * The surface, with the aerodrome levelled.
     *
     * The generator rolls the ground +/-5 rows, and a landing that needs
     * altitude <= LAND_ALT and a gentle sink at the same moment is luck on
     * terrain like that - the frame dumper kept writing LANDED=false,
     * CAUSE=GROUND. Real aerodromes are flat, so the strip is flat, with a
     * 40-row blend at each end so A5's "no step over 2 rows per column"
     * still holds.
     */
    fun groundAt(worldX: Double): Float {
        val raw = World.surfaceY(worldX, trenchBuf).toFloat()
        if (endless) return raw
        val lo = baseX - Tune.GOAROUND_OVERSHOOT - Tune.STRIP_BLEND
        val hi = baseX + Tune.LAND_ZONE + Tune.STRIP_BLEND
        if (worldX < lo || worldX > hi) return raw
        val flat = World.surfaceY(baseX, trenchBuf).toFloat()
        // full flat over the strip, easing out across the blend
        val d = when {
            worldX < baseX - Tune.GOAROUND_OVERSHOOT ->
                ((baseX - Tune.GOAROUND_OVERSHOOT) - worldX).toFloat() / Tune.STRIP_BLEND
            worldX > baseX + Tune.LAND_ZONE ->
                (worldX - (baseX + Tune.LAND_ZONE)).toFloat() / Tune.STRIP_BLEND
            else -> 0f
        }.coerceIn(0f, 1f)
        return flat + (raw - flat) * d
    }

    fun slotKeyAt(worldX: Double): Int = World.cell(worldX, Art.SLOT_W.toDouble())

    fun isDead(k: Int) = destroyed.contains(k)

    /**
     * The attitude actually drawn. Collision reads the SAME sprite, so the
     * shape you can see is the shape that can hit things.
     */
    private val mirrored = HashMap<Array<String>, Array<String>>()

    /** Flip a sprite left-to-right, cached, so homebound art is free. */
    private fun mirror(spr: Array<String>): Array<String> =
        mirrored.getOrPut(spr) { Array(spr.size) { spr[it].reversed() } }

    fun planeSprite(): Array<String> {
        val base = when {
            vy < -0.15f -> Art.SPR_PLANE_CLIMB
            vy > 0.15f -> Art.SPR_PLANE_DIVE
            else -> Art.SPR_PLANE
        }
        // The homebound aeroplane is the same shape pointing the other way,
        // and collision reads THIS function too - so the mirrored machine
        // cannot collide with the unmirrored one's wheels.
        return if (dir > 0) base else mirror(base)
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

    // =====================================================================
    //  where the aeroplane is
    // =====================================================================
    /**
     * +1 outbound, -1 coming home. Everything that cares which way the
     * aeroplane is pointing reads this, so the return leg is the same game
     * played in mirror rather than a second code path to keep in step.
     */
    var dir = 1; private set

    /** Signed forward velocity - the AA intercept solves against this. */
    fun velX(): Float = speed * dir

    /** The column the aeroplane is drawn in; it swaps ends on the way home. */
    fun planeX(): Int =
        if (dir > 0) Art.PLAYER_X else Art.GW - Art.PLAYER_X - Tune.PLANE_W

    /** Its left edge in world coordinates. */
    fun planeWorldX(): Double = camX + planeX()

    /** Its nose, which is whichever end it is pointing. */
    fun noseWorldX(): Double =
        planeWorldX() + (if (dir > 0) Tune.PLANE_W.toDouble() else 0.0)

    /** Altitude above the ground directly below, in rows. */
    fun altitude(): Float = groundAt(planeWorldX()) - (py + Tune.PLANE_H)

    /** 0 at the ground, 1 at the ceiling - the greed axis. */
    fun altFrac(): Float {
        val g = groundAt(planeWorldX())
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

        // A pause stops EVERYTHING: the aeroplane, the scroll, the shells
        // already in the air, the searchlights, the reload timers. `ticks`
        // keeps running so the card's prompt can still blink.
        if (paused) return
        // The count-in is frozen for every one of its ticks; the first tick
        // that flies is the one after it. Letting the last tick fall through
        // into live physics would hand the world back half a frame early,
        // which is exactly the kind of off-by-one nobody ever sees but the
        // audit has to be able to state plainly.
        if (resumeCount > 0f) {
            resumeCount = max(0f, resumeCount - dt)
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
        // A broken elevator does not weaken the stick, it DELAYS it: the
        // input goes into a ring buffer and comes out DMG_LAG ticks later,
        // which is a far nastier and more readable failure than a smaller
        // number would be.
        val stick: Boolean
        if (isDamaged(PART_ELEVATOR)) {
            lagBuf[lagHead] = if (climbing) 1f else 0f
            lagHead = (lagHead + 1) % lagBuf.size
            val back = (lagHead + lagBuf.size -
                Tune.DMG_LAG.toInt().coerceIn(1, lagBuf.size - 1)) % lagBuf.size
            stick = lagBuf[back] > 0.5f
        } else stick = climbing

        vy += if (stick) -climbAcc() * dt else Tune.GRAVITY * dt
        // a holed wing sinks whatever you do; you have to fly it off
        if (isDamaged(PART_WING)) vy += Tune.DMG_WING_VY * dt
        vy = vy.coerceIn(-Tune.VY_MAX_UP, Tune.VY_MAX_DN)
        py += vy * dt
        if (py < Tune.CEIL_ROW) { py = Tune.CEIL_ROW; if (vy < 0f) vy = 0f }

        val t = ((vy + Tune.VY_MAX_UP) / (Tune.VY_MAX_UP + Tune.VY_MAX_DN)).coerceIn(0f, 1f)
        speed = Tune.SPEED_MIN + (speedMax() - Tune.SPEED_MIN) * t
        camX += speed * dir * dt
        distance += speed * dt

        // fuel burns on the clock, faster through a holed tank
        fuel -= dt * (if (isDamaged(PART_FUEL)) Tune.FUEL_LEAK else 1f)
        if (fuel <= 0f) { fuel = 0f; if (!crashed) hit("OUT OF FUEL") }

        stepLeg()

        // the line creeps back while you are just sightseeing
        linePct = (linePct - Tune.LINE_CREEP * speed * dt).coerceIn(0f, 1f)

        if (chainTimer > 0f) {
            chainTimer -= dt
            if (chainTimer <= 0f) { chainTimer = 0f; chain = 0 }
        }
        if (chainFlash > 0f) chainFlash = max(0f, chainFlash - dt)
        if (sectorFlash > 0f) sectorFlash = max(0f, sectorFlash - dt)
        if (grazeFlash > 0f) grazeFlash = max(0f, grazeFlash - dt)
        // These two shipped without decay for a while: the TURN FOR HOME
        // banner would have sat on the screen for the rest of the sortie.
        // The frame dumper found it by never matching "banner is partway
        // through fading", which is a better bug report than a screenshot.
        if (turnFlash > 0f) turnFlash = max(0f, turnFlash - dt)
        if (landFlash > 0f) landFlash = max(0f, landFlash - dt)

        // ---- greed meter --------------------------------------------------
        if (altitude() < Tune.LOW_ALT) {
            lowTicks += dt; highTicks = 0f
            lowRows += speed * dt
            while (lowRows >= 1f) { lowRows -= 1f; bumpOrder(Orders.LOW_ROWS, 1) }
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
            gunTimer = gunPeriod()
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
        s.x = noseWorldX().toFloat()
        s.y = py + 4f
        s.vx = (Tune.GUN_SPEED + speed) * dir
        s.vy = 0.35f
        s.life = Tune.GUN_RANGE / Tune.GUN_SPEED
    }

    fun dropBomb(): Boolean {
        if (bombs <= 0) return false
        val s = freeShot() ?: return false
        bombs--
        s.alive = true; s.kind = K_BOMB
        s.x = (planeWorldX() + 11).toFloat()
        s.y = py + Tune.PLANE_H
        s.vx = velX()
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
                earn(earned)
                bumpOrder(Orders.SCOUTS, 1)
                val bonus = registerKill()
                detonate(e.x + 8f, e.y + 4f, 8f, false)
                freePip()?.let {
                    it.alive = true; it.x = e.x; it.y = e.y - 8f; it.age = 0f
                    it.text = if (bonus > 0) "CHAIN X" + chain else "+" + earned
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

    /**
     * Register a kill and return the chain bonus it earned.
     *
     * Kills inside CHAIN_WINDOW stack. This is what turns a strafing run
     * into a plan - lining three targets up on one pass is worth noticeably
     * more than potting one and climbing away, which is exactly the
     * behaviour the game wants to reward and previously did not.
     */
    private fun registerKill(): Int {
        chain = if (chainTimer > 0f) minOf(Tune.CHAIN_MAX, chain + 1) else 1
        chainTimer = Tune.CHAIN_WINDOW
        if (chain > bestChain) bestChain = chain
        if (chain < 2) return 0
        chainFlash = 44f
        val bonus = Tune.PTS_CHAIN * chain * mult
        earn(bonus)
        return bonus
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
        earn(earned)
        linePct += gain
        lineDelta += gain
        if (linePct >= 1f) captureSector()
        linePct = linePct.coerceIn(0f, 1f)
        when (type) {
            World.DEPOT -> bumpOrder(Orders.DEPOTS, 1)
            World.AA_GUN, World.LIGHT -> bumpOrder(Orders.GUNS, 1)
            World.BALLOON -> bumpOrder(Orders.BALLOONS, 1)
            World.TANK -> bumpOrder(Orders.TANKS, 1)
        }
        val bonus = registerKill()
        detonate(x, y - 4f, 7f, false)
        freePip()?.let {
            it.alive = true; it.x = x; it.y = y - 14f; it.age = 0f
            it.text = if (bonus > 0) "CHAIN X" + chain else "+" + earned
        }
        gunTimers.remove(k)
    }

    private fun detonate(x: Float, y: Float, r: Float, lethal: Boolean) {
        val b = freeBurst() ?: return
        b.alive = true; b.x = x; b.y = y; b.age = 0f
        b.span = 26f; b.r = r
        b.grazed = false
        b.lethal = if (lethal) 5f else 0f
    }

    private fun dustPuff(x: Float, y: Float) {
        val b = freeBurst() ?: return
        b.alive = true; b.x = x; b.y = y - 1f; b.age = 0f
        b.span = 9f; b.r = 3f; b.lethal = 0f; b.grazed = false
    }

    private fun stepBursts(dt: Float) {
        for (b in bursts) {
            if (!b.alive) continue
            b.age += dt
            if (b.lethal > 0f) {
                b.lethal -= dt
                if (!crashed && invuln <= 0f && burstHitsPlane(b)) hit("FLAK")
                // A near miss pays. Without this the brave line and the safe
                // line scored exactly the same once you survived, so the
                // game's whole "accuracy demands altitude" bargain had no
                // upside you could feel. Scored once per burst, and only for
                // a burst that was genuinely trying to kill you.
                else if (!crashed && !b.grazed && invuln <= 0f &&
                         distToPlane(b.x, b.y) <= Tune.GRAZE_R) {
                    b.grazed = true
                    grazes++
                    grazeFlash = 36f
                    val earned = Tune.PTS_GRAZE * mult
                    earn(earned)
                    bumpOrder(Orders.GRAZES, 1)
                    freePip()?.let {
                        it.alive = true; it.x = b.x; it.y = py - 6f; it.age = 0f
                        it.text = "GRAZED +" + earned
                    }
                }
            }
            if (b.age >= b.span) b.alive = false
        }
    }

    /** Distance from a point to the plane's box - 0 when it is inside. */
    private fun distToPlane(bx: Float, by: Float): Float {
        val x0 = planeWorldX().toFloat()
        val dx = bx - bx.coerceIn(x0, x0 + Tune.PLANE_W)
        val dy = by - by.coerceIn(py, py + Tune.PLANE_H)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun burstHitsPlane(b: Burst): Boolean {
        // plane box vs burst circle
        val x0 = planeWorldX().toFloat()
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
        val px = (planeWorldX() + Tune.PLANE_W / 2f).toFloat()
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

            val overHome = kotlin.math.abs(sl.worldX - baseX) < Tune.HOME_SAFE
            if (!overHome && timer <= 0f &&
                range >= Tune.MIN_ENGAGE && range <= Tune.MAX_ENGAGE) {
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
        val vx = velX()
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
        val pxc = (planeWorldX() + Tune.PLANE_W / 2f).toFloat()
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
            val lead = (e.x - pxc) * dir
            if (e.fireTimer <= 0f && lead > 16f && lead < 74f) {
                e.fireTimer = Tune.ENEMY_FIRE_PERIOD
                fireEnemyBullet(e, pxc, pyc)
            }

            val gone = if (dir > 0) e.x < camX - 24 else e.x > camX + Art.GW + 24
            if (gone) { e.alive = false; continue }
            if (invuln <= 0f && !crashed && boxHitsPlane(e.x, e.y, Tune.PLANE_W.toFloat(),
                    Tune.PLANE_H.toFloat())) {
                e.alive = false
                detonate(e.x, e.y, 9f, false)
                hit("COLLISION")
            }
        }
    }

    private fun spawnEnemy() {
        if (kotlin.math.abs(planeWorldX() - baseX) < Tune.HOME_SAFE) return
        val e = enemies.firstOrNull { !it.alive } ?: return
        e.alive = true
        e.x = (camX + (if (dir > 0) Art.GW + 14 else -14)).toFloat()
        // enter near the player's band so it is visible on approach
        val h = World.hash32(ticks.toInt() * 7919 + 3)
        val off = (((h shr 5) and 0x3Fu).toInt() - 32) * 0.8f
        e.y = (py + off).coerceIn(Tune.CEIL_ROW + 2f,
            groundAt(e.x.toDouble()) - Tune.PLANE_H - 6f)
        e.vx = -Tune.ENEMY_SPEED * dir
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
        val ax0 = planeWorldX().toFloat(); val ax1 = ax0 + Tune.PLANE_W
        val ay0 = py; val ay1 = py + Tune.PLANE_H
        return x < ax1 && x + w > ax0 && y < ay1 && y + h > ay0
    }

    // ---- searchlights -----------------------------------------------------------
    private fun stepSearchlight(dt: Float) {
        if (!isNight()) { lockTicks = max(0f, lockTicks - dt); return }
        var lit = false
        val px = (planeWorldX() + Tune.PLANE_W / 2f).toFloat()
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
        val x0 = planeWorldX()
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
        detonate((planeWorldX() + 11).toFloat(), py + 4f, 11f, false)
        lives--
        // Break something, and say WHICH. "I lost a life" is a counter;
        // "my elevator is gone and I am forty rows up" is a situation. The
        // damage is kept through the respawn on purpose - you go on flying
        // the machine you wrecked.
        val whole = (0 until Tune.PARTS).filter { !damaged.contains(it) }
        if (whole.isNotEmpty()) {
            val pick = whole[(World.hash32(ticks.toInt() * 2654 + lives)
                % whole.size.toUInt()).toInt()]
            damaged.add(pick)
            lastLoss = cause + " - " + PART_NAME[pick]
        } else lastLoss = cause
        lifeFlash = Tune.LIFE_FLASH
        if (lives <= 0) {
            lives = 0
            crashed = true
            gameOver = true
            crashCause = cause
            crashTicks = 0f
            // F3: death must be legible. Not just WHAT killed you but HOW
            // CLOSE you were - "400 rows from base" is the sentence that
            // makes a thumb tap again.
            lostScore = pending
            pending = 0
            rowsFromBase = kotlin.math.abs(planeWorldX() - baseX).toFloat()
            lastBest = best
            if (distance > best) best = distance
            if (score > bestScore) bestScore = score
            endSortie()      // banks the orders, moves the front, saves
        } else {
            respawn()
        }
    }

    private fun respawn() {
        py = (groundAt(planeWorldX()) - Tune.RESPAWN_ALT)
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
