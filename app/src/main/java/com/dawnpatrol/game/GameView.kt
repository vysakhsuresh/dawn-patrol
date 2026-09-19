package com.dawnpatrol.game

import android.content.Context
import android.graphics.Bitmap
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
    private val blitPaint = Paint().apply {
        isFilterBitmap = false   // nearest neighbour: pixels stay pixels
        isAntiAlias = false
        isDither = false
    }

    // ---- input -----------------------------------------------------------
    private var leftPointer = -1
    private var rightPointer = -1

    // ---- loop ------------------------------------------------------------
    private var running = false
    private var lastFrameNanos = 0L

    init { isFocusable = true }

    // ---------------------------------------------------------------- loop
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sound.load()
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
        Choreographer.getInstance().removeFrameCallback(this)
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

        sim.update(dt)

        if (sim.lives < livesBefore) sound.playHit()
        if (!wasCrashed && sim.crashed) sound.playHit()
        if (countBursts() > burstsBefore) sound.playThud()

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
                if (sim.crashed) { restart(); return true }
                // first touch launches the sortie; it also counts as input,
                // so the tap that starts you is not swallowed
                sim.start()
                if (event.getX(i) < halfW) {
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
        val pal = renderer.palette(sim.phase())

        val buf = renderer.fb.p
        val ink = pal.ink or (0xFF shl 24)
        val paper = pal.paper or (0xFF shl 24)
        var i = 0
        val n = buf.size
        while (i < n) {
            px[i] = if (buf[i].toInt() != 0) ink else paper
            i++
        }
        bmp.setPixels(px, 0, Art.GW, 0, 0, Art.GW, Art.GH)

        canvas.drawColor(Color.BLACK)   // letterbox bars
        canvas.drawBitmap(bmp, src, dst, blitPaint)
    }
}
