package com.dawnpatrol.game

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

/**
 * The Android layer, and ONLY the Android layer: input, the frame clock,
 * and one bitmap blit. Everything that decides what the game does or what
 * it looks like lives in Sim and Renderer, which are free of android.* so
 * the audit and the frame dumper can run them for real.
 *
 * Controls (one thumb, no menus):
 *   left half  - hold to climb, release to dive. Gravity is always on.
 *   right half - press drops a bomb and opens up with the guns;
 *                hold keeps the guns firing.
 */
class GameView(context: Context) : View(context), Choreographer.FrameCallback {

    private val store = object : Store {
        private val p = context.getSharedPreferences("dawn_patrol", Context.MODE_PRIVATE)
        private var ed: android.content.SharedPreferences.Editor? = null
        private fun editor(): android.content.SharedPreferences.Editor {
            val e = ed ?: p.edit()
            ed = e
            return e
        }
        override fun getFloat(key: String, def: Float) = p.getFloat(key, def)
        override fun putFloat(key: String, v: Float) { editor().putFloat(key, v) }
        override fun getString(key: String, def: String): String = p.getString(key, def) ?: def
        override fun putString(key: String, v: String) { editor().putString(key, v) }
        override fun flush() { ed?.apply(); ed = null }
    }

    private val sim = Sim(store)
    private val renderer = Renderer()
    private val sound = Sound()

    // ---- blit ------------------------------------------------------------
    private val bmp: Bitmap = Bitmap.createBitmap(Art.GW, Art.GH, Bitmap.Config.ARGB_8888)
    private val px = IntArray(Art.GW * Art.GH)
    private val src = Rect(0, 0, Art.GW, Art.GH)
    private val dst = Rect()
    private val barPaint = Paint()
    private val blitPaint = Paint().apply {
        isFilterBitmap = false   // nearest neighbour: pixels stay pixels
        isAntiAlias = false
        isDither = false
    }

    // ---- haptics -----------------------------------------------------------
    // Ported from Cat on a Fence, which had this and Dawn Patrol did not.
    // On a phone it is the cheapest perceived-quality win available: the
    // difference between a graze you see and a graze you FEEL.
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    } catch (e: Throwable) { null }

    /** Never let a missing motor cost the player their game. */
    private fun buzz(ms: Long, amplitude: Int) {
        if (sim.muted) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
        } catch (e: Throwable) { }
    }

    // ---- input -----------------------------------------------------------
    private var leftPointer = -1
    private var rightPointer = -1

    // ---- loop ------------------------------------------------------------
    private var running = false
    private var lastFrameNanos = 0L
    private var warmed = false

    init { isFocusable = true }

    // ---------------------------------------------------------------- loop
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Building four AudioTracks is four trips through AudioFlinger. On
        // the main thread that is a stall in the first frames, exactly when
        // the player is forming their first impression of how it responds.
        Thread { sound.load() }.apply { isDaemon = true }.start()
        warmUp()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
        sound.release()
    }

    // Pause on window FOCUS loss, not just onPause - the notification shade
    // steals focus without ever calling the activity's onPause.
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        sound.startEngine()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        running = false
        sound.stopEngine()
        // Losing focus freezes the sortie instead of handing it back live.
        // The notification shade, a call, the recents switcher - all of them
        // used to resume you exactly where you were, which on this game may
        // be mid-dive with a shell already in the air.
        releaseThumbs()
        sim.pause()
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Drop any held finger, so nothing is latched across a freeze. */
    private fun releaseThumbs() {
        leftPointer = -1
        rightPointer = -1
        sim.climbing = false
        sim.firing = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) {
            // Normalise to a 60Hz frame and clamp, so the game behaves the
            // same on 90/120Hz panels and survives a stall without teleporting.
            val dt = ((frameTimeNanos - lastFrameNanos) / 16_666_667f).coerceIn(0.25f, 2.5f)
            step(dt)
        }
        lastFrameNanos = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(dt: Float) {
        val wasCrashed = sim.crashed
        val livesBefore = sim.lives
        val burstsBefore = countBursts()
        val grazesBefore = sim.grazes
        val sectorsBefore = sim.sectors
        val legBefore = sim.leg
        val chainBefore = sim.chain
        val promotedBefore = sim.promoted

        sim.update(dt)

        if (sim.lives < livesBefore) { sound.playHit(); buzz(55, 255) }
        if (!wasCrashed && sim.crashed) {
            sound.playHit()
            // landing and dying are both ends of a sortie and must never
            // feel the same in the hand
            if (sim.landed) buzz(40, 120) else buzz(140, 255)
        }
        if (countBursts() > burstsBefore) {
            // near or far decides WHICH sound, not how loud
            if (sim.shake > 0.4f || sim.grazeFlash > 0f) sound.playThudNear()
            else sound.playThud()
        }
        if (sim.chain > chainBefore && sim.chain >= 2) sound.playChain()
        if (sim.sectors > sectorsBefore || sim.promoted != promotedBefore) sound.playFanfare()
        sound.engineSick = sim.isDamaged(PART_ENGINE)
        if (sim.grazes > grazesBefore) buzz(12, 70)
        if (sim.sectors > sectorsBefore) buzz(90, 200)
        if (sim.leg != legBefore && sim.leg == LEG_HOME) buzz(30, 150)

        sound.muted = sim.muted
        sound.suspended = sim.frozen()
        // engine note rises as you dive and bleeds off as you haul back
        sound.setEnginePitch(
            ((sim.speed - Tune.SPEED_MIN) / (Tune.SPEED_MAX - Tune.SPEED_MIN))
                .coerceIn(0f, 1f))
    }

    private fun countBursts(): Int {
        var n = 0
        for (b in sim.bursts) if (b.alive) n++
        return n
    }

    // --------------------------------------------------------------- input
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val halfW = width / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                if (sim.crashed) {
                    // Only once the card has had time to be read, and only on
                    // a genuinely new touch - dying mid-tap must not be taken
                    // as "play again".
                    if (sim.canRestart()) restart()
                    return true
                }
                if (sim.showFullBriefing) {
                    // SWALLOWED on purpose. This tap means "I have read the
                    // card", and letting it fall through is what made a new
                    // player's first ever action drop the aeroplane out of
                    // the sky. It puts the card away; the plane keeps flying
                    // straight and level until they press LEFT.
                    sim.dismissBriefing()
                    return true
                }
                // The HUD strip is not a control surface: only its buttons
                // live there, so reaching for pause can never open fire.
                // dst is empty until the first layout; a touch cannot arrive
                // before then, but dividing by it would be silent NaN if one
                // did, and NaN compares false against every hit test.
                val dw = dst.width(); val dh = dst.height()
                val gx = if (dw > 0) (event.getX(i) - dst.left) / dw * Art.GW else -1f
                val gy = if (dh > 0) (event.getY(i) - dst.top) / dh * Art.GH else -1f
                if (Art.inHud(gy)) {
                    if (Art.hitSpend(gx, gy)) {
                        if (sim.spend()) { sound.playBomb(); buzz(18, 90) }
                    } else if (Art.hitMute(gx, gy)) {
                        sim.toggleMute()
                        sound.muted = sim.muted
                        if (!sim.muted) sound.playThud()   // proof it is back
                    } else if (Art.hitPause(gx, gy)) {
                        releaseThumbs()
                        sim.pause()
                    }
                    return true
                }
                if (sim.frozen()) {
                    // Any tap outside the HUD starts the count-in, and is
                    // swallowed - coming back must not also be a climb.
                    sim.requestResume()
                    return true
                }
                // the ready screen's own controls, live only while loitering
                if (!sim.started) {
                    if (Art.hitPlanePick(gy)) { sim.nextPlane(); buzz(14, 80); return true }
                    if (Art.hitOptions(gy)) {
                        if (gx < Art.GW / 2) sim.toggleHanded() else sim.toggleMotion()
                        buzz(14, 80)
                        return true
                    }
                }
                // A left-handed player holds the climb - the control they
                // hold for most of a sortie - under their dominant thumb.
                val left = (event.getX(i) < halfW) != sim.leftHanded
                if (!sim.started) {
                    // The sortie begins on a LEFT press and that same press
                    // IS the climb, so the hand-over to physics starts with
                    // the plane going up, not down. A right-hand tap here is
                    // ignored rather than wasting a bomb before the off.
                    if (!left) return true
                    sim.start()
                }
                if (left) {
                    if (leftPointer < 0) leftPointer = id
                } else {
                    if (rightPointer < 0) rightPointer = id
                    if (sim.dropBomb()) sound.playBomb()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == leftPointer) leftPointer = -1
                if (id == rightPointer) rightPointer = -1
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { leftPointer = -1; rightPointer = -1 }
        }
        sim.climbing = leftPointer >= 0
        sim.firing = rightPointer >= 0
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Clear the held-input state as well as the sim. Without this, crashing
     * mid-climb left `climbing` latched on: the next sortie started pulling
     * up with nobody touching the screen.
     */
    private fun restart() {
        leftPointer = -1
        rightPointer = -1
        sim.climbing = false
        sim.firing = false
        sim.reset()
    }

    // -------------------------------------------------------------- sizing
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // One uniform scale with centring offsets - the fixed logical canvas.
        val s = minOf(w.toFloat() / Art.LW, h.toFloat() / Art.LH)
        val dw = (Art.LW * s).toInt()
        val dh = (Art.LH * s).toInt()
        val ox = (w - dw) / 2
        val oy = (h - dh) / 2
        dst.set(ox, oy, ox + dw, oy + dh)
    }

    // -------------------------------------------------------------- render
    override fun onDraw(canvas: Canvas) {
        renderer.render(sim)
        Warmup.expand(renderer.fb, renderer.palette(sim.phase()), px)

        bmp.setPixels(px, 0, Art.GW, 0, 0, Art.GW, Art.GH)

        // The bars are sky above and earth below, not black. On a tablet
        // or a foldable the fixed 100x175 canvas leaves a lot of frame,
        // and a black surround makes the game look like a phone app that
        // was not finished. The playfield geometry is untouched - the
        // extra area is decoration and can never be an advantage, which
        // Audit A35 pins.
        val pal = renderer.palette(sim.phase())
        barPaint.color = pal.paper or (0xFF shl 24)
        canvas.drawRect(0f, 0f, width.toFloat(), dst.top.toFloat(), barPaint)
        canvas.drawRect(0f, 0f, dst.left.toFloat(), height.toFloat(), barPaint)
        canvas.drawRect(dst.right.toFloat(), 0f, width.toFloat(),
            height.toFloat(), barPaint)
        barPaint.color = pal.ink or (0xFF shl 24)
        canvas.drawRect(dst.left.toFloat(), dst.bottom.toFloat(),
            dst.right.toFloat(), height.toFloat(), barPaint)
        canvas.drawBitmap(bmp, src, dst, blitPaint)
    }

    // -------------------------------------------------------------- warm-up
    /**
     * Drive the JIT through the real shipped methods while the briefing card
     * is on screen, so a first-ever launch is not spent interpreting the
     * renderer. The work itself is in Warmup, free of android.*, so Audit
     * A19 runs exactly this code. Any failure is dropped: a cold start is a
     * slow game, but a crashed warm-up is no game at all.
     */
    private fun warmUp() {
        if (warmed) return          // attach can happen more than once
        warmed = true
        val th = Thread {
            try { Warmup.run(4_000_000_000L) } catch (e: Throwable) { }
        }
        th.priority = Thread.MIN_PRIORITY
        th.isDaemon = true
        th.start()
    }
}
