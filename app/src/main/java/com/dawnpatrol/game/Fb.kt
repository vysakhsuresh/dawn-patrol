package com.dawnpatrol.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A strict 1-bit software framebuffer - 0 = paper, 1 = ink.
 *
 * This is deliberately NOT android.graphics. The whole scene is composed
 * here in pure Kotlin, then blitted in one call (GameView turns it into a
 * Bitmap; the offline frame dumper writes it to a PPM). That means:
 *
 *  - The game and the preview renders run the SAME code, so a preview can
 *    never drift from what ships - the previous pipeline only guaranteed
 *    the sprite DATA matched, not the drawing.
 *  - No hairline seams. Drawing a 100x175 grid as ~17k separate scaled
 *    canvas.drawRect calls leaves sub-pixel gaps at most scale factors;
 *    a nearest-neighbour bitmap blit cannot.
 *  - Ordered dithering is per-pixel, which is what makes smoke, flak and
 *    searchlights read as tone instead of as shapes.
 */
class Fb(val w: Int, val h: Int) {

    val p = ByteArray(w * h)

    fun clear(v: Int = 0) {
        java.util.Arrays.fill(p, v.toByte())
    }

    inline fun set(x: Int, y: Int, v: Int) {
        if (x in 0 until w && y in 0 until h) p[y * w + x] = v.toByte()
    }

    fun setf(x: Float, y: Float, v: Int) = set(fr(x), fr(y), v)

    fun get(x: Int, y: Int): Int =
        if (x in 0 until w && y in 0 until h) p[y * w + x].toInt() else 0

    fun rect(x: Int, y: Int, rw: Int, rh: Int, v: Int) {
        val x0 = max(0, x); val x1 = min(w, x + rw)
        val y0 = max(0, y); val y1 = min(h, y + rh)
        for (j in y0 until y1) {
            val base = j * w
            for (i in x0 until x1) p[base + i] = v.toByte()
        }
    }

    fun frameRect(x: Int, y: Int, rw: Int, rh: Int, v: Int) {
        hline(x, x + rw - 1, y, v)
        hline(x, x + rw - 1, y + rh - 1, v)
        vline(x, y, y + rh - 1, v)
        vline(x + rw - 1, y, y + rh - 1, v)
    }

    fun hline(xa: Int, xb: Int, y: Int, v: Int) {
        val x0 = min(xa, xb); val x1 = max(xa, xb)
        for (x in x0..x1) set(x, y, v)
    }

    fun vline(x: Int, ya: Int, yb: Int, v: Int) {
        val y0 = min(ya, yb); val y1 = max(ya, yb)
        for (y in y0..y1) set(x, y, v)
    }

    /** Bresenham. `dash` = 0 solid, else n on / n off. */
    fun line(xa: Float, ya: Float, xb: Float, yb: Float, v: Int, dash: Int = 0) {
        var x0 = fr(xa); var y0 = fr(ya)
        val x1 = fr(xb); val y1 = fr(yb)
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        var n = 0
        while (true) {
            if (dash == 0 || (n % (dash * 2)) < dash) set(x0, y0, v)
            n++
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    /** Sparse dotted line - `on` pixels lit out of every `period`. */
    fun lineDuty(xa: Float, ya: Float, xb: Float, yb: Float, v: Int,
                 on: Int = 1, period: Int = 5) {
        var x0 = fr(xa); var y0 = fr(ya)
        val x1 = fr(xb); val y1 = fr(yb)
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        var n = 0
        while (true) {
            if ((n % period) < on) set(x0, y0, v)
            n++
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    // ---- ordered dither ------------------------------------------------
    /** level 0..64 -> 0%..100% coverage. */
    fun dpx(x: Int, y: Int, level: Int, v: Int = 1) {
        if (level <= 0) return
        if (x < 0 || x >= w || y < 0 || y >= h) return
        if (Art.BAYER[(y and 7) * 8 + (x and 7)] < level) p[y * w + x] = v.toByte()
    }

    fun dpxf(x: Float, y: Float, level: Int, v: Int = 1) = dpx(fr(x), fr(y), level, v)

    /**
     * Noise dither, for smooth gradients like sky haze.
     *
     * Ordered (Bayer) dither at very low densities lights the same few cells
     * of every 8x8 tile, which at this resolution reads as a regular dotted
     * lattice - wallpaper, not atmosphere. Hashed noise has no lattice. The
     * ordered table is still the right tool for smoke, flak and beams, where
     * the structure reads as texture.
     */
    fun npx(x: Int, y: Int, level: Int, v: Int = 1) {
        if (level <= 0) return
        if (x < 0 || x >= w || y < 0 || y >= h) return
        val n = World.hash32(x * 73856093 xor (y * 19349663))
        if ((n % 64u).toInt() < level) p[y * w + x] = v.toByte()
    }

    fun drect(x: Int, y: Int, rw: Int, rh: Int, level: Int, v: Int = 1) {
        for (j in y until y + rh) for (i in x until x + rw) dpx(i, j, level, v)
    }

    fun dcircle(cx: Float, cy: Float, r: Float, level: Int, v: Int = 1,
                hollow: Float = 0f) {
        if (r <= 0f || level <= 0) return
        val r2 = r * r
        val h2 = (r * hollow) * (r * hollow)
        val y0 = fr(cy - r); val y1 = fr(cy + r)
        val x0 = fr(cx - r); val x1 = fr(cx + r)
        for (y in y0..y1) for (x in x0..x1) {
            val ddx = x - cx; val ddy = y - cy
            val d = ddx * ddx + ddy * ddy
            if (d in h2..r2) dpx(x, y, level, v)
        }
    }

    fun fillCircle(cx: Float, cy: Float, r: Float, v: Int) {
        val r2 = r * r
        for (y in fr(cy - r)..fr(cy + r)) for (x in fr(cx - r)..fr(cx + r)) {
            val ddx = x - cx; val ddy = y - cy
            if (ddx * ddx + ddy * ddy <= r2) set(x, y, v)
        }
    }

    /** Midpoint circle outline. */
    fun circle(cx: Float, cy: Float, r: Float, v: Int) {
        var x = fr(r); var y = 0; var d = 1 - fr(r)
        val icx = fr(cx); val icy = fr(cy)
        while (x >= y) {
            set(icx + x, icy + y, v); set(icx + y, icy + x, v)
            set(icx - x, icy + y, v); set(icx - y, icy + x, v)
            set(icx + x, icy - y, v); set(icx + y, icy - x, v)
            set(icx - x, icy - y, v); set(icx - y, icy - x, v)
            y++
            if (d < 0) d += 2 * y + 1 else { x--; d += 2 * (y - x) + 1 }
        }
    }

    // ---- sprites -------------------------------------------------------
    fun sprite(g: Array<String>, x: Int, y: Int, v: Int = 1) {
        for (j in g.indices) {
            val row = g[j]
            val gy = y + j
            if (gy < 0 || gy >= h) continue
            var i = 0
            while (i < row.length) {
                if (row[i] == 'X') {
                    var k = i
                    while (k < row.length && row[k] == 'X') k++
                    val xa = max(0, x + i); val xb = min(w, x + k)
                    val base = gy * w
                    for (px in xa until xb) p[base + px] = v.toByte()
                    i = k
                } else i++
            }
        }
    }

    /** Knock a sprite-shaped hole (plus padding) in whatever is underneath. */
    fun spriteClear(g: Array<String>, x: Int, y: Int, pad: Int = 1, v: Int = 0) {
        for (j in g.indices) {
            val row = g[j]
            for (i in row.indices) {
                if (row[i] != 'X') continue
                for (dy in -pad..pad) for (dx in -pad..pad) set(x + i + dx, y + j + dy, v)
            }
        }
    }

    /** Outline only - the night treatment for ground features. */
    fun spriteOutline(g: Array<String>, x: Int, y: Int, v: Int = 1) {
        for (j in g.indices) {
            val row = g[j]
            for (i in row.indices) {
                if (row[i] != 'X') continue
                var edge = false
                if (j == 0 || i >= g[j - 1].length || g[j - 1][i] != 'X') edge = true
                if (!edge && (j == g.size - 1 || i >= g[j + 1].length || g[j + 1][i] != 'X')) edge = true
                if (!edge && (i == 0 || row[i - 1] != 'X')) edge = true
                if (!edge && (i == row.length - 1 || row[i + 1] != 'X')) edge = true
                if (edge) set(x + i, y + j, v)
            }
        }
    }

    // ---- text ----------------------------------------------------------
    fun text(s: String, x: Int, y: Int, v: Int = 1, sp: Int = 1, scale: Int = 1): Int {
        var cx = x
        for (ch in s.uppercase()) {
            val g = Art.FONT[ch] ?: Art.FONT[' ']!!
            for (j in g.indices) {
                val row = g[j]
                for (i in row.indices) {
                    if (row[i] != 'X') continue
                    if (scale == 1) set(cx + i, y + j, v)
                    else rect(cx + i * scale, y + j * scale, scale, scale, v)
                }
            }
            cx += 3 * scale + sp
        }
        return cx
    }

    /** Text on a knocked-out plate, so it can never be lost in dither. */
    fun plateText(s: String, x: Int, y: Int, v: Int = 1, sp: Int = 1,
                  scale: Int = 1, pad: Int = 2) {
        val tw = textW(s, sp, scale)
        rect(x - pad, y - pad, tw + pad * 2, 5 * scale + pad * 2, 1 - v)
        text(s, x, y, v, sp, scale)
    }

    companion object {
        fun textW(s: String, sp: Int = 1, scale: Int = 1): Int =
            if (s.isEmpty()) 0 else s.length * (3 * scale + sp) - sp

        /** floor-to-int that is correct for negatives (see World's lesson #4). */
        fun fr(v: Float): Int = kotlin.math.floor(v.toDouble()).toInt()
    }
}

private fun fr(v: Float): Int = kotlin.math.floor(v.toDouble()).toInt()

fun Fb.textW(s: String, sp: Int = 1, scale: Int = 1): Int = Fb.textW(s, sp, scale)
