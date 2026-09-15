package com.dawnpatrol.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

/**
 * Single custom View + Canvas. No engine, no XML layouts, no image or audio
 * assets - see README.md. Everything is drawn in a fixed GW x GH pixel grid
 * (see Art.kt), fit to the real screen with one uniform scale + centering
 * offset, exactly like Cat on a Fence's 400x700 logical canvas.
 *
 * SCOPE OF THIS FIRST PASS: the core climb/dive/gravity loop, procedural
 * scrolling terrain and features (all audited in mock/audit.py), collision,
 * and a minimal crash/restart cycle - solid-ink rendering, no dithered
 * atmosphere (smoke/flak/searchlight) yet, and no AA combat/scoring beyond
 * a placeholder line-push. That is deliberately left for the next pass: it
 * needs the flight feel signed off first (see README - "several rounds of
 * me saying it feels floaty" is expected), and I have no way to render or
 * eyeball a running APK from this sandbox, so the first cut stays simple
 * and easy to reason about rather than visually ambitious and unverified.
 */
class GameView(context: Context) : View(context), Choreographer.FrameCallback {

    // ---- rendering ------------------------------------------------------
    private val ink = Paint().apply { isAntiAlias = false; color = INK; style = Paint.Style.FILL }
    private val paper = Paint().apply { isAntiAlias = false; color = PAPER; style = Paint.Style.FILL }

    private var deviceScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var cellScale = 1f // deviceScale * Art.PX - draw directly in GW x GH grid units

    // ---- persistence ------------------------------------------------------
    private val prefs = context.getSharedPreferences("dawn_patrol", Context.MODE_PRIVATE)
    private var linePct = prefs.getFloat("line_pct", 0f)

    // ---- sound ------------------------------------------------------------
    private val sound = Sound()

    // ---- flight state (grid units, see Art.kt's HORIZON/CEILING scale) ----
    private var planeY = (Art.HORIZON - 40).toDouble()
    private var vy = 0.0
    private var camX = 0.0
    private var speed = Art.SPEED_MIN.toDouble()
    private var distanceFlown = 0.0
    private var crashed = false
    private var fireVisualTimer = 0.0

    // ---- input --------------------------------------------------------
    private var leftHeld = false

    // ---- procedural world snapshot, refreshed once per update() -------
    private var visibleFeatures: List<World.Slot> = emptyList()
    private var trenches: List<Pair<Double, UInt>> = emptyList()

    // ---- game loop ------------------------------------------------------
    private var running = false
    private var lastFrameNanos = 0L

    init {
        isFocusable = true
    }

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
    // steals focus without calling the activity's onPause.
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) {
            val dt = ((frameTimeNanos - lastFrameNanos) / 16_666_667f).coerceIn(0.25f, 2.5f)
            update(dt)
        }
        lastFrameNanos = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // -------------------------------------------------------------- update
    private fun update(dtFloat: Float) {
        if (crashed) return
        val dt = dtFloat.toDouble()

        if (fireVisualTimer > 0.0) fireVisualTimer = (fireVisualTimer - dt).coerceAtLeast(0.0)

        // Left half held = climb. Released = dive. Gravity always pulls down.
        if (leftHeld) vy -= Art.CLIMB_ACC.toDouble() * dt else vy += Art.GRAVITY.toDouble() * dt
        vy = vy.coerceIn(-Art.VY_MAX_UP.toDouble(), Art.VY_MAX_DN.toDouble())
        planeY += vy * dt
        if (planeY < Art.CEILING.toDouble()) {
            planeY = Art.CEILING.toDouble()
            if (vy < 0.0) vy = 0.0
        }

        // Speed follows the climb/dive state: diving means better accuracy
        // (lower altitude) and worse exposure (faster into the flak) - the
        // core tension from the brief. This mapping is a first guess, not
        // yet tuned against real play.
        val vyMaxUp = Art.VY_MAX_UP.toDouble()
        val vyMaxDn = Art.VY_MAX_DN.toDouble()
        val speedT = ((vy + vyMaxUp) / (vyMaxUp + vyMaxDn)).coerceIn(0.0, 1.0)
        speed = Art.SPEED_MIN.toDouble() + (Art.SPEED_MAX.toDouble() - Art.SPEED_MIN.toDouble()) * speedT

        camX += speed * dt
        distanceFlown += speed * dt

        refreshVisibleFeatures()

        val groundBelowPlayer = World.surfaceY(camX + Art.PLAYER_X.toDouble(), trenches)
        if (planeY + PLANE_HALF_H >= groundBelowPlayer) {
            crashed = true
            sound.playHit()
            onCrash()
        }
    }

    private fun refreshVisibleFeatures() {
        val slots = World.visibleSlots(camX, Art.GW.toDouble())
        val feats = ArrayList<World.Slot>()
        val tr = ArrayList<Pair<Double, UInt>>()
        for (k in slots) {
            val s = World.slot(k)
            feats.add(s)
            if (s.type == World.TRENCH) tr.add(s.worldX to s.hash)
        }
        visibleFeatures = feats
        trenches = tr
    }

    private fun onCrash() {
        linePct = (linePct + (distanceFlown * LINE_PUSH_PER_UNIT).toFloat()).coerceIn(0f, 1f)
        prefs.edit().putFloat("line_pct", linePct).apply()
    }

    private fun restart() {
        planeY = (Art.HORIZON - 40).toDouble()
        vy = 0.0
        camX = 0.0
        distanceFlown = 0.0
        crashed = false
        fireVisualTimer = 0.0
    }

    // --------------------------------------------------------------- input
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (crashed) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) restart()
            return true
        }
        val halfW = width / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(event.actionIndex)
                if (x < halfW) leftHeld = true else fire()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                var stillLeft = false
                for (i in 0 until event.pointerCount) {
                    if (i == event.actionIndex) continue
                    if (event.getX(i) < halfW) stillLeft = true
                }
                leftHeld = stillLeft
            }
            MotionEvent.ACTION_CANCEL -> leftHeld = false
        }
        return true
    }

    private fun fire() {
        fireVisualTimer = 0.12
        sound.playGun()
    }

    // -------------------------------------------------------------- sizing
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        deviceScale = minOf(w / Art.LW.toFloat(), h / Art.LH.toFloat())
        offsetX = (w - Art.LW * deviceScale) / 2f
        offsetY = (h - Art.LH * deviceScale) / 2f
        cellScale = deviceScale * Art.PX
    }

    // -------------------------------------------------------------- render
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK) // letterbox bars outside the fitted area
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(cellScale, cellScale)

        canvas.drawRect(0f, 0f, Art.GW.toFloat(), Art.GH.toFloat(), paper)
        drawEarth(canvas)
        drawFeatures(canvas)
        drawPlane(canvas)
        if (fireVisualTimer > 0.0) drawTracer(canvas)
        drawHud(canvas)
        if (crashed) drawCrashOverlay(canvas)

        canvas.restore()
    }

    private fun drawEarth(canvas: Canvas) {
        for (x in 0 until Art.GW) {
            val gy = World.surfaceY(camX + x, trenches).toFloat()
            canvas.drawRect(x.toFloat(), gy, x + 1f, Art.GH.toFloat(), ink)
        }
    }

    private fun drawFeatures(canvas: Canvas) {
        for (s in visibleFeatures) {
            val x = (s.worldX - camX).toFloat()
            if (x < -34f || x > Art.GW + 34f) continue
            val groundY = World.surfaceY(s.worldX, trenches).toFloat()
            when (s.type) {
                World.AA_GUN -> standOn(canvas, Art.SPR_AA_GUN, x, groundY)
                World.DEPOT -> standOn(canvas, Art.SPR_DEPOT, x, groundY)
                World.TANK -> standOn(canvas, Art.SPR_TANK, x, groundY)
                World.LIGHT -> standOn(canvas, Art.SPR_SEARCHLIGHT, x, groundY)
                World.WRECK -> standOn(canvas, Art.SPR_AA_WRECK, x, groundY)
                World.BALLOON -> {
                    val top = Art.SKY_TOP + 16 + ((s.hash shr 3) and 15u).toInt()
                    canvas.drawLine(x, top + Art.SPR_BALLOON.size.toFloat(), x, groundY, ink)
                    standOnTop(canvas, Art.SPR_BALLOON, x, top.toFloat())
                }
                World.TRENCH, World.SANDBAG -> { /* folded into the earth silhouette itself */ }
            }
        }
    }

    /** Stand a sprite on groundY, centred on x, with a paper halo behind it. */
    private fun standOn(canvas: Canvas, rows: Array<String>, x: Float, groundY: Float) {
        standOnTop(canvas, rows, x, groundY - rows.size)
    }

    private fun standOnTop(canvas: Canvas, rows: Array<String>, x: Float, top: Float) {
        val w = rows.maxOf { it.length }
        val h = rows.size
        val gx = x - w / 2f
        canvas.drawRect(gx - 1f, top - 1f, gx + w + 1f, top + h + 1f, paper)
        canvas.drawSprite(rows, gx, top, ink)
    }

    private fun drawPlane(canvas: Canvas) {
        val sprite = when {
            vy < -0.15 -> Art.SPR_PLANE_CLIMB
            vy > 0.15 -> Art.SPR_PLANE_DIVE
            else -> Art.SPR_PLANE
        }
        val gx = Art.PLAYER_X.toFloat()
        val gy = planeY.toFloat()
        val w = sprite.maxOf { it.length }
        val h = sprite.size
        canvas.drawRect(gx - 1f, gy - 1f, gx + w + 1f, gy + h + 1f, paper)
        canvas.drawSprite(sprite, gx, gy, ink)
    }

    private fun drawTracer(canvas: Canvas) {
        val gx = Art.PLAYER_X + 20f
        val gy = planeY.toFloat() + 5f
        canvas.drawLine(gx, gy, gx + 20f, gy + 6f, ink)
    }

    private fun drawHud(canvas: Canvas) {
        canvas.drawRect(0f, 0f, Art.GW.toFloat(), Art.HUD_H.toFloat(), ink)
        val alt = (Art.HORIZON - planeY).toInt().coerceAtLeast(0)
        canvas.drawBitmapText("ALT $alt", 3f, 2f, paper)

        val bx = 4f; val bw = Art.GW - 8f; val by = 8f; val bh = 4f
        canvas.drawRectOutline(bx, by, bw, bh, paper)
        val fill = (bw - 2f) * linePct
        canvas.drawRect(bx + 1f, by + 1f, bx + 1f + fill, by + bh - 1f, paper)
    }

    private fun drawCrashOverlay(canvas: Canvas) {
        val msg = "SHOT DOWN"
        val mw = textWidth(msg, scale = 2)
        canvas.drawRect((Art.GW - mw) / 2f - 3f, 34f, (Art.GW - mw) / 2f + mw + 3f, 52f, paper)
        canvas.drawBitmapText(msg, (Art.GW - mw) / 2f, 37f, ink, scale = 2)

        val tap = "TAP TO SCRAMBLE"
        val tw = textWidth(tap)
        canvas.drawRect((Art.GW - tw) / 2f - 2f, 158f, (Art.GW - tw) / 2f + tw + 2f, 165f, paper)
        canvas.drawBitmapText(tap, (Art.GW - tw) / 2f, 159f, ink)
    }

    // ------------------------------------------------------ draw helpers
    /** Run-length batch each row's 'X' runs into canvas.drawRect calls. */
    private fun Canvas.drawSprite(rows: Array<String>, gx: Float, gy: Float, paint: Paint) {
        for (ry in rows.indices) {
            val row = rows[ry]
            var i = 0
            while (i < row.length) {
                if (row[i] == 'X') {
                    var j = i
                    while (j < row.length && row[j] == 'X') j++
                    drawRect(gx + i, gy + ry, gx + j, gy + ry + 1f, paint)
                    i = j
                } else {
                    i++
                }
            }
        }
    }

    private fun Canvas.drawRectOutline(x: Float, y: Float, w: Float, h: Float, paint: Paint) {
        drawRect(x, y, x + w, y + 1f, paint)
        drawRect(x, y + h - 1f, x + w, y + h, paint)
        drawRect(x, y, x + 1f, y + h, paint)
        drawRect(x + w - 1f, y, x + w, y + h, paint)
    }

    private fun Canvas.drawBitmapText(s: String, gx: Float, gy: Float, paint: Paint, spacing: Float = 1f, scale: Int = 1) {
        var cx = gx
        for (ch in s.uppercase()) {
            val glyph = Art.FONT[ch] ?: Art.FONT[' ']!!
            if (scale == 1) {
                drawSprite(glyph, cx, gy, paint)
            } else {
                for (ry in glyph.indices) {
                    val row = glyph[ry]
                    for (rx in row.indices) {
                        if (row[rx] == 'X') {
                            drawRect(
                                cx + rx * scale, gy + ry * scale,
                                cx + rx * scale + scale, gy + ry * scale + scale, paint
                            )
                        }
                    }
                }
            }
            cx += (3 * scale + spacing)
        }
    }

    private fun textWidth(s: String, spacing: Float = 1f, scale: Int = 1): Float =
        s.length * (3 * scale + spacing) - spacing

    companion object {
        private val INK = 0xFF1A160F.toInt()
        private val PAPER = 0xFFE9B068.toInt()
        private const val PLANE_HALF_H = 4.0

        // Placeholder scoring: nudges the front-line meter by distance flown
        // alone. The real mechanic - depots and AA guns destroyed, tracked
        // per world slot and persisted - is the next milestone, once the
        // flight feel itself is signed off.
        private const val LINE_PUSH_PER_UNIT = 0.00006
    }
}
