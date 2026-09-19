package com.dawnpatrol.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a Sim into a 1-bit Fb. No android.* here either, so the offline
 * frame dumper renders through exactly this code - a preview image is the
 * game's own output, not a second implementation of it.
 *
 * THE VALUE SYSTEM (this is what makes a 1-bit frame read at 100x175):
 *   sky      clean paper, at most a faint stipple gradient
 *   far      tall hazed objects - distance is haze, never a grey landmass
 *   earth    SOLID ink, one unbroken silhouette
 *   features solid ink with a 1px paper halo so they separate from it
 *   smoke / flak / beams - the ONLY mid-tones, so they pop
 * Depth between overlapping silhouettes is a 1-2px paper gap punched along
 * the nearer layer's crest, never a dither value. A 50% dither at this
 * resolution is a checkerboard, and a screen of checkerboard has no
 * hierarchy at all - that mistake cost a whole visual pass.
 *
 * At night the structure inverts: earth becomes the paper (black), features
 * are pale outlines, and a plane caught in a beam is a HOLE in the light.
 */
class Renderer {

    val fb = Fb(Art.GW, Art.GH)

    // ---- palette ---------------------------------------------------------
    /** ink, paper, and whether the night (inverted) structure is in force. */
    class Pal(val ink: Int, val paper: Int, val night: Boolean)

    private val keys = arrayOf(
        Pal(0x2A1D12, 0xE9B068, false),   // 0 dawn
        Pal(0x1A211E, 0xB7C3A8, false),   // 1 day
        Pal(0x241423, 0xC97A4E, false),   // 2 dusk
        Pal(0x2B2438, 0x14182B, false),   // 3 deep dusk - day structure, dark
        Pal(0x96B4CD, 0x080C13, true)     // 4 night - inverted
    )

    private fun lerpRgb(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val c = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or c
    }

    /**
     * Colours crossfade between keyframes; the night STRUCTURE flips as a
     * hard switch. The deep-dusk keyframe exists so that flip is invisible:
     * either side of it the earth is near-black and the sky is near-black,
     * so nothing visibly jumps when ink and paper swap roles.
     */
    fun palette(phase: Float): Pal {
        val i = floor(phase.toDouble()).toInt().coerceIn(0, 4)
        val j = (i + 1) % 5
        val t = phase - i
        val a = keys[i]; val b = keys[j]
        return Pal(lerpRgb(a.ink, b.ink, t), lerpRgb(a.paper, b.paper, t), a.night)
    }

    // =====================================================================
    fun render(sim: Sim) {
        val pal = palette(sim.phase())
        val night = pal.night
        fb.clear(0)

        sky(sim, night)
        farField(sim, night)
        earth(sim, night)
        features(sim, night)
        smoke(sim)
        if (night) beams(sim)
        enemies(sim, night)
        projectiles(sim)
        bursts(sim)
        // The full briefing covers the sky, and the text plates punch holes
        // straight through the aircraft - it reads as a rendering fault. The
        // world still scrolls behind the card, so the scene stays alive.
        if (sim.started || sim.crashed || !sim.showFullBriefing) plane(sim, night)
        pips(sim)
        hud(sim, night)
    }

    // ---- sky --------------------------------------------------------------
    private fun sky(sim: Sim, night: Boolean) {
        if (night) {
            var i = 0
            while (i < 90) {
                val h = World.hash32(i * 2654435761.toInt())
                val x = ((h shr 3) and 0x7Fu).toInt() % Art.GW
                val y = Art.SKY_TOP + ((h shr 11) and 0x7Fu).toInt() % (Art.HORIZON - Art.SKY_TOP - 26)
                // drift the starfield very slowly so it is not wallpaper
                val sx = ((x - (sim.camX * 0.04).toInt()) % Art.GW + Art.GW) % Art.GW
                if ((h and 7u) < 5u) fb.set(sx, y, 1)
                i++
            }
            for (y in Art.HORIZON - 20 until Art.HORIZON) {
                val t = (y - (Art.HORIZON - 20)) / 20f
                val lvl = (9f * t * t * t).toInt()
                if (lvl > 0) for (x in 0 until Art.GW) fb.npx(x, y, lvl)
            }
            return
        }
        for (y in Art.SKY_TOP until Art.HORIZON) {
            val t = (y - Art.SKY_TOP).toFloat() / (Art.HORIZON - Art.SKY_TOP)
            val k = max(0f, 1f - t)
            val lvl = (5.5f * k * k * k).toInt()
            if (lvl > 0) for (x in 0 until Art.GW) fb.npx(x, y, lvl)
        }
        // a low sun near dawn and dusk
        val ph = sim.phase()
        val sunT = when {
            ph < 1f -> 1f - ph
            ph in 2f..3f -> ph - 2f
            else -> 0f
        }
        if (sunT > 0.05f) {
            val sx = 74f
            val sy = Art.HORIZON - 30f
            val r = 11f
            fb.fillCircle(sx, sy, r, 0)
            fb.circle(sx, sy, r, 1)
            var b = -5
            while (b <= 7) {
                val half = sqrt(max(0f, r * r - b * b))
                fb.hline((sx - half).toInt(), (sx + half).toInt(), (sy + b).toInt(), 1)
                b += 4
            }
        }
    }

    // ---- far field ---------------------------------------------------------
    private fun hazeSprite(spr: Array<String>, x: Float, base: Float, lvl: Int, ink: Int) {
        val w = spr[0].length
        val gx = (x - w / 2f).toInt()
        val gy = base.toInt() - spr.size
        fb.spriteClear(spr, gx, gy, 1, 0)
        for (j in spr.indices) for (i in spr[j].indices) {
            if (spr[j][i] == 'X') fb.dpx(gx + i, gy + j, lvl, ink)
        }
    }

    private fun farField(sim: Sim, night: Boolean) {
        val para = 0.35
        val period = 13
        val lvl = if (night) 20 else 44
        val k0 = World.cell(sim.camX * para - 12, period.toDouble())
        val k1 = World.cell(sim.camX * para + Art.GW + 12, period.toDouble())
        for (k in k0..k1) {
            val h = World.hash32(k * 6271 + 17)
            val wx = k * period + ((h shr 7) % period.toUInt()).toInt()
            val x = (wx - sim.camX * para).toFloat()
            if (x < -14f || x > Art.GW + 14f) continue
            val base = Art.HORIZON - 3f - ((h shr 3) and 3u).toInt()
            val r = (h and 15u).toInt()
            when {
                r == 0 -> hazeSprite(Art.SPR_TREE, x, base - 3f, lvl, 1)
                r < 9 -> hazeSprite(Art.SPR_TREE, x, base, lvl, 1)
                else -> hazeSprite(Art.SPR_STUMP, x, base, lvl, 1)
            }
        }
    }

    // ---- earth ---------------------------------------------------------------
    private fun earth(sim: Sim, night: Boolean) {
        for (x in 0 until Art.GW) {
            val wx = sim.camX + x
            val gy = Math.round(sim.groundAt(wx)).toInt()
            if (night) {
                // earth is the paper at night: a pale crust and black below
                for (y in gy + 1 until Art.GH) fb.set(x, y, 0)
                fb.set(x, gy, 1)
            } else {
                for (y in max(Art.SKY_TOP, gy - 2) until gy) fb.set(x, y, 0)
                for (y in gy until Art.GH) fb.set(x, y, 1)
            }
        }
        // paper marks knocked out of the mass: furrows, craters, wire
        val v = if (night) 1 else 0
        for (x in 0 until Art.GW) {
            val wx = sim.camX + x
            val gy = Math.round(sim.groundAt(wx)).toInt()
            val h = World.hash32(floor(wx).toInt())
            if ((h and (if (night) 63u else 15u)) == 0u) {
                val len = 2 + ((h shr 8) and 3u).toInt()
                val yy = gy + 4 + ((h shr 12) % 34u).toInt()
                fb.hline(x, x + len, yy, v)
            }
            if ((h and (if (night) 255u else 127u)) == 9u) {
                val cy = gy + 6 + ((h shr 16) % 16u).toInt()
                fb.circle(x.toFloat(), cy.toFloat(), 3f + ((h shr 20) and 1u).toInt(), v)
            }
        }
    }

    // ---- ground features ------------------------------------------------------
    private fun stand(spr: Array<String>, x: Float, base: Float, night: Boolean) {
        val w = spr[0].length
        val gx = (x - w / 2f).toInt()
        val gy = base.toInt() - spr.size
        if (night) {
            for (j in spr.indices) for (i in spr[j].indices)
                if (spr[j][i] == 'X') fb.set(gx + i, gy + j, 0)
            for (j in spr.indices) for (i in spr[j].indices)
                if (spr[j][i] == 'X') fb.dpx(gx + i, gy + j, 16, 1)
            fb.spriteOutline(spr, gx, gy, 1)
        } else {
            fb.spriteClear(spr, gx, gy, 1, 0)
            fb.sprite(spr, gx, gy, 1)
        }
    }

    private fun features(sim: Sim, night: Boolean) {
        val ink = 1
        for (sl in sim.visibleSlots()) {
            val x = (sl.worldX - sim.camX).toFloat()
            if (x < -34f || x > Art.GW + 34f) continue
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            val dead = sim.isDead(k)
            val g = sim.groundAt(sl.worldX)
            when (sl.type) {
                World.TRENCH -> parapet(sim, sl, ink)
                World.SANDBAG -> {
                    for (d in -7..7) {
                        val xx = (x + d).toInt()
                        val gg = Math.round(sim.groundAt(sim.camX + xx)).toInt()
                        val hh = World.hash32(sl.worldX.toInt() + d)
                        var b = 1 + ((hh shr 6) and 1u).toInt() + (if (abs(d) < 4) 2 else 0)
                        while (b > 0) { fb.set(xx, gg - b, ink); b-- }
                    }
                }
                World.AA_GUN -> stand(if (dead) Art.SPR_AA_WRECK else Art.SPR_AA_GUN, x, g + 1, night)
                World.DEPOT -> stand(if (dead) Art.SPR_DEPOT_WRECK else Art.SPR_DEPOT, x, g + 1, night)
                World.TANK -> if (!dead) stand(Art.SPR_TANK, x, g + 1, night)
                            else stand(Art.SPR_AA_WRECK, x, g + 1, night)
                World.LIGHT -> stand(if (dead) Art.SPR_AA_WRECK else Art.SPR_SEARCHLIGHT, x, g + 1, night)
                World.WRECK -> stand(Art.SPR_AA_WRECK, x, g + 1, night)
                World.BALLOON -> if (!dead) balloon(sim, sl, x, g, night)
                else -> {}
            }
        }
    }

    private fun parapet(sim: Sim, sl: World.Slot, ink: Int) {
        val half = 11 + (sl.hash and 3u).toInt()
        for (d in -half..half) {
            val wx = sl.worldX + d
            val x = Math.round(wx - sim.camX).toInt()
            if (x < 0 || x >= Art.GW) continue
            val gy = Math.round(sim.groundAt(wx)).toInt()
            val gh = World.hash32(floor(wx).toInt() * 31 + 7)
            var bump = 1 + ((gh shr 5) and 1u).toInt()
            if (abs(d) > half - 3) bump += 2
            var b = 0
            while (b < bump) { fb.set(x, gy - 1 - b, ink); b++ }
        }
    }

    private fun balloon(sim: Sim, sl: World.Slot, x: Float, g: Float, night: Boolean) {
        val top = sim.balloonTop(sl)
        val spr = Art.SPR_BALLOON
        val bw = spr[0].length
        val gx = (x - bw / 2f).toInt()
        // cable first, so the envelope's halo cuts it cleanly
        fb.line(x, top + spr.size, x, g, 1, 4)
        if (night) {
            for (j in spr.indices) for (i in spr[j].indices)
                if (spr[j][i] == 'X') fb.set(gx + i, top.toInt() + j, 0)
            fb.spriteOutline(spr, gx, top.toInt(), 1)
        } else {
            fb.spriteClear(spr, gx, top.toInt(), 1, 0)
            fb.sprite(spr, gx, top.toInt(), 1)
        }
    }

    // ---- atmosphere -------------------------------------------------------------
    /** Smoke rises from everything that is still burning. */
    private fun smoke(sim: Sim) {
        for (sl in sim.visibleSlots()) {
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            val burns = sim.isDead(k) || sl.type == World.WRECK
            if (!burns) continue
            val x = (sl.worldX - sim.camX).toFloat()
            if (x < -20f || x > Art.GW + 20f) continue
            val g = sim.groundAt(sl.worldX)
            val h = sl.hash
            var i = 0
            while (i < 7) {
                val gg = World.hash32(h.toInt() + i * 2654435761.toInt())
                val rise = i * 4.6f + 3f
                val sway = sin((sim.ticks * 0.02f + i * 0.9f + ((gg and 31u).toInt()) * 0.2f).toDouble())
                    .toFloat() * (1.4f + i * 0.8f)
                val drift = i * 1.7f
                val r = 1.7f + i * 0.82f
                val lvl = max(2, 13 - i) * 28 / 10
                fb.dcircle(x + drift + sway, g - rise, r, lvl, 1)
                i++
            }
        }
    }

    private fun beams(sim: Sim) {
        for (sl in sim.visibleSlots()) {
            if (sl.type != World.LIGHT) continue
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            if (sim.isDead(k)) continue
            val bx = (sl.worldX - sim.camX).toFloat()
            if (bx < -60f || bx > Art.GW + 60f) continue
            val by = sim.groundAt(sl.worldX) - 7f
            val ang = sim.beamAngle(k)
            val reach = Tune.BEAM_RANGE
            val spread = Tune.BEAM_SPREAD
            val locked = sim.lockTicks > 0f
            for (y in Art.SKY_TOP until Art.HORIZON) {
                for (x in 0 until Art.GW) {
                    val dx = x - bx; val dy = y - by
                    val d = hypot(dx, dy)
                    if (d < 3f || d > reach) continue
                    val a = atan2(dy.toDouble(), dx.toDouble()).toFloat()
                    var da = a - ang
                    while (da > Math.PI.toFloat()) da -= 2f * Math.PI.toFloat()
                    while (da < -Math.PI.toFloat()) da += 2f * Math.PI.toFloat()
                    if (abs(da) > spread) continue
                    val edge = Math.pow((1f - abs(da) / spread).toDouble(), 0.6).toFloat()
                    val fall = 1f - (d / reach) * 0.5f
                    val l = 15f * edge * fall * (if (locked) 2.4f else 1f)
                    if (l >= 1f) fb.dpx(x, y, l.toInt(), 1)
                }
            }
        }
    }

    // ---- enemy scouts -----------------------------------------------------------
    private fun enemies(sim: Sim, night: Boolean) {
        for (e in sim.enemies) {
            if (!e.alive) continue
            val x = (e.x - sim.camX).toFloat()
            if (x < -26f || x > Art.GW + 26f) continue
            val spr = Art.SPR_PLANE
            val gx = x.toInt(); val gy = e.y.toInt()
            // mirrored, so an enemy reads instantly as coming the other way
            for (j in spr.indices) {
                val row = spr[j]
                for (i in row.indices) {
                    if (row[i] != 'X') continue
                    fb.set(gx + (row.length - 1 - i), gy + j, 0)
                }
            }
            for (j in spr.indices) {
                val row = spr[j]
                for (i in row.indices) {
                    if (row[i] != 'X') continue
                    for (dy in -1..1) for (dx in -1..1)
                        fb.set(gx + (row.length - 1 - i) + dx, gy + j + dy, 0)
                }
            }
            for (j in spr.indices) {
                val row = spr[j]
                for (i in row.indices) {
                    if (row[i] == 'X') fb.set(gx + (row.length - 1 - i), gy + j, 1)
                }
            }
        }
    }

    // ---- projectiles / bursts ------------------------------------------------------
    private fun projectiles(sim: Sim) {
        for (s in sim.shots) {
            if (!s.alive) continue
            val x = (s.x - sim.camX).toFloat()
            val y = s.y
            when (s.kind) {
                K_BULLET -> fb.line(x, y, x - 4f, y - 1.2f, 1, 0)
                K_EBULLET -> {
                    // fatter than your own tracer, so incoming reads as incoming
                    fb.line(x, y, x + 4f, y - s.vy * 2.6f, 1, 0)
                    fb.set(x.toInt(), y.toInt() + 1, 1)
                }
                K_BOMB -> fb.sprite(Art.SPR_BOMB, x.toInt(), y.toInt(), 1)
                K_SHELL -> {
                    // a shell is a telegraph: you must be able to see it coming
                    fb.line(x, y, x - s.vx * 3f, y - s.vy * 3f, 1, 0)
                    fb.set(x.toInt(), y.toInt(), 1)
                    fb.set(x.toInt() + 1, y.toInt(), 1)
                }
            }
        }
    }

    private fun bursts(sim: Sim) {
        for (b in sim.bursts) {
            if (!b.alive) continue
            val x = (b.x - sim.camX).toFloat()
            val age = (b.age / b.span).coerceIn(0f, 1f)
            if (age < 0.22f) {
                fb.dcircle(x, b.y, 1.6f + age * 7f, 64, 1)
                var a = 0
                while (a < 8) {
                    val th = a * Math.PI.toFloat() / 4f + 0.2f
                    val L = 4f + age * 26f
                    fb.line(x, b.y, x + cos(th.toDouble()).toFloat() * L,
                        b.y + sin(th.toDouble()).toFloat() * L, 1, 2)
                    a++
                }
            } else {
                val r = b.r * (0.5f + age)
                val lvl = (56f * Math.pow((1f - age).toDouble(), 0.8).toFloat()).toInt()
                fb.dcircle(x, b.y, r, max(6, lvl), 1, 0.3f)
                fb.dcircle(x + r * 0.5f, b.y - r * 0.45f, r * 0.55f, max(4, lvl - 14), 1)
                var a = 0
                while (a < 360) {
                    if ((World.hash32(x.toInt() * 31 + a) and 1u) != 0u) {
                        val th = Math.toRadians(a.toDouble())
                        fb.set((x + cos(th).toFloat() * r).toInt(),
                               (b.y + sin(th).toFloat() * r).toInt(), 1)
                    }
                    a += 11
                }
            }
        }
    }

    private fun pips(sim: Sim) {
        for (p in sim.pips) {
            if (!p.alive) continue
            val x = (p.x - sim.camX).toFloat()
            fb.plateText(p.text, x.toInt(), p.y.toInt(), 1, 1, 1, 1)
        }
    }

    // ---- the aeroplane ----------------------------------------------------------
    private fun plane(sim: Sim, night: Boolean) {
        // Sim owns the choice, so the drawn shape and the collided shape
        // can never drift apart.
        val spr = sim.planeSprite()
        val gx = Art.PLAYER_X
        val gy = Math.round(sim.py).toInt()

        if (night && sim.lockTicks > 0f) {
            // caught in the light: pour a bright pool onto the aircraft and
            // punch it out as a hole, so the silhouette reads as a shadow
            val cx = gx + Tune.PLANE_W / 2f
            val cy = gy + Tune.PLANE_H / 2f
            for (y in (gy - 9)..(gy + Tune.PLANE_H + 9)) {
                for (x in (gx - 11)..(gx + Tune.PLANE_W + 11)) {
                    val d = ((x - cx) / 21f) * ((x - cx) / 21f) + ((y - cy) / 12f) * ((y - cy) / 12f)
                    if (d <= 1f) fb.dpx(x, y, (58f * Math.pow((1f - d).toDouble(), 0.45).toFloat()).toInt(), 1)
                }
            }
            for (j in spr.indices) for (i in spr[j].indices) {
                if (spr[j][i] != 'X') continue
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = j + dy; val xx = i + dx
                    val inside = yy in spr.indices && xx >= 0 && xx < spr[yy].length && spr[yy][xx] == 'X'
                    if (!inside) fb.set(gx + xx, gy + yy, 1)
                }
            }
            fb.sprite(spr, gx, gy, 0)
            return
        }
        // blink through the grace period so it is obvious you are briefly safe
        if (sim.invuln > 0f && ((sim.ticks / 4f).toInt() and 1) == 0) {
            fb.spriteClear(spr, gx, gy, 1, 0)
            fb.spriteOutline(spr, gx, gy, 1)
            return
        }
        fb.spriteClear(spr, gx, gy, 1, 0)
        fb.sprite(spr, gx, gy, 1)
    }

    // ---- HUD ----------------------------------------------------------------------
    private fun hud(sim: Sim, night: Boolean) {
        val ink = 1
        fb.rect(0, 0, Art.GW, Art.HUD_H, ink)
        val paper = 0

        fb.text("ALT " + sim.altitude().toInt().coerceAtLeast(0), 3, 2, paper)

        // score, right-aligned - the number the player is actually chasing
        val sc = sim.score.toString()
        fb.text(sc, Art.GW - 3 - Fb.textW(sc), 2, paper)

        // lives, left - machines you have left, countable at a glance
        for (i in 0 until sim.lives) {
            fb.sprite(Art.SPR_LIFE, 3 + i * 7, 9, paper)
        }

        // bombs remaining, right - diegetic and instantly countable
        val bx = Art.GW - 3 - sim.bombs * 4
        for (i in 0 until sim.bombs) {
            fb.sprite(Art.SPR_BOMB, bx + i * 4, 8, paper)
        }

        // front line meter: the long-term objective
        val mx = 4; val mw = Art.GW - 8; val my = 15; val mh = 3
        fb.frameRect(mx, my, mw, mh, paper)
        val fill = ((mw - 2) * sim.linePct).toInt()
        for (i in 0 until mw - 2) {
            val lv = if (i < fill) 64 else 14
            fb.dpx(mx + 1 + i, my + 1, lv, paper)
            fb.dpx(mx + 1 + i, my + 2, lv, paper)
        }
        fb.vline(mx + 1 + fill, my - 1, my + mh, paper)
        for (x in 0 until Art.GW step 2) fb.set(x, Art.HUD_H - 1, paper)

        // greed meter - only shows when it is actually earning
        if (sim.mult > 1) {
            fb.plateText("X" + sim.mult, Art.PLAYER_X + 2, Math.round(sim.py).toInt() - 12,
                1, 2, 2, 1)
        }

        if (night && sim.lockTicks > 0f) {
            fb.plateText("LOCKED", Art.PLAYER_X + 30, Math.round(sim.py).toInt() - 14, 1)
        }

        balloonWarning(sim)

        if (!sim.started && !sim.crashed) {
            if (sim.showFullBriefing) briefing(sim) else startPrompt(sim)
        }

        // A life going missing from the HUD is not enough on its own - the
        // player is looking at the aeroplane, not the corner. Say it loudly.
        if (sim.lifeFlash > 0f && !sim.crashed) {
            val b = "HIT - " + sim.lastLoss
            val w = Fb.textW(b, 1, 2)
            fb.plateText(b, (Art.GW - w) / 2, 56, 1, 1, 2, 3)
            val l = if (sim.lives == 1) "1 MACHINE LEFT"
                    else sim.lives.toString() + " MACHINES LEFT"
            fb.plateText(l, (Art.GW - Fb.textW(l)) / 2, 72, 1, 1, 1, 2)
        }

        if (sim.crashed) crashCard(sim)
    }

    /**
     * A chevron for a balloon that has not scrolled on yet. This is not
     * decoration: Audit A8's proof that a pilot on the deck can climb over
     * a balloon depends on this lead. Remove it and low flying becomes a
     * coin flip.
     */
    private fun balloonWarning(sim: Sim) {
        for (sl in sim.visibleSlots()) {
            if (sl.type != World.BALLOON) continue
            val k = World.cell(sl.worldX, Art.SLOT_W.toDouble())
            if (sim.isDead(k)) continue
            val x = (sl.worldX - sim.camX).toFloat()
            if (x <= Art.GW - 2f || x > Art.GW + Tune.BALLOON_WARN) continue
            val top = sim.balloonTop(sl)
            val ex = Art.GW - 5
            val ey = top.toInt()
            // a blinking arrow at the edge, at the balloon's altitude
            if (((sim.ticks / 9f).toInt() and 1) == 0) {
                for (d in 0..3) {
                    fb.set(ex + d, ey - d, 1); fb.set(ex + d, ey + d, 1)
                    fb.set(ex + d + 1, ey - d, 1); fb.set(ex + d + 1, ey + d, 1)
                }
                fb.hline(ex - 3, ex + 1, ey, 1)
            }
        }
    }

    /**
     * The opening briefing. Shown once, before the first sortie ever flown.
     *
     * A player who does not know that LOW is where the points are will fly
     * high, score nothing, and conclude the game is empty - so the risk/
     * reward line is on the card, not buried in a tutorial nobody reads.
     */
    private fun briefing(sim: Sim) {
        val title = "DAWN PATROL"
        val tw = Fb.textW(title, 2, 2)
        fb.plateText(title, (Art.GW - tw) / 2, 21, 1, 2, 2, 3)

        val lines = listOf(
            "" to 0,
            "HOLD LEFT  -  CLIMB" to 1,
            "LET GO  -  GLIDE DOWN" to 1,
            "TAP RIGHT  -  BOMB" to 1,
            "HOLD RIGHT  -  GUNS" to 1,
            "" to 0,
            "TARGETS" to 1,
            "DEPOT 280   TANK 140" to 1,
            "BALLOON 170  GUN 110" to 1,
            "" to 0,
            "FLY LOW TO MULTIPLY" to 1,
            "UP TO X9  -  BUT THE" to 1,
            "FLAK AIMS BETTER LOW" to 1,
            "" to 0,
            "MIND THE CABLES" to 1
        )
        var y = 40
        for ((line, on) in lines) {
            if (on == 1) {
                val w = Fb.textW(line)
                fb.plateText(line, (Art.GW - w) / 2, y, 1, 1, 1, 1)
            }
            y += if (on == 1) 8 else 4
        }
        // This tap only puts the card away - it does not launch anything, so
        // it must not promise flight.
        if (((sim.ticks / 22f).toInt() and 1) == 0) {
            val s2 = "TAP TO BEGIN"
            fb.plateText(s2, (Art.GW - Fb.textW(s2)) / 2, 152, 1, 1, 1, 2)
        }
    }

    /**
     * READY: the aeroplane is flying straight and level and the sortie starts
     * on a LEFT press. Naming the side matters - the first control a player
     * touches should be the one that keeps them in the air.
     */
    private fun startPrompt(sim: Sim) {
        if (sim.bestScore > 0) {
            val b = "BEST " + sim.bestScore
            fb.plateText(b, (Art.GW - Fb.textW(b)) / 2, 40, 1, 1, 1, 2)
        }
        if (((sim.ticks / 22f).toInt() and 1) == 0) {
            val s2 = "HOLD LEFT TO FLY"
            fb.plateText(s2, (Art.GW - Fb.textW(s2)) / 2, 150, 1, 1, 1, 2)
        }
        // a standing reminder of the other thumb, small and out of the way
        val s3 = "RIGHT = BOMB + GUNS"
        fb.plateText(s3, (Art.GW - Fb.textW(s3)) / 2, 160, 1, 1, 1, 1)
    }

    private fun crashCard(sim: Sim) {
        // `crashed` only ever means the run is over, so there is one title.
        val title = "GAME OVER"
        val tw = Fb.textW(title, 2, 2)
        fb.plateText(title, (Art.GW - tw) / 2, 36, 1, 2, 2, 3)
        val lines = listOf(
            "SCORE  " + sim.score,
            "BEST   " + sim.bestScore,
            "CAUSE  " + sim.crashCause,
            "LINE   " + (sim.linePct * 100f).toInt() + "%",
            "GUNS " + sim.killsAA + "  DEPOTS " + sim.killsDepot,
            "ROWS   " + sim.distance.toInt()
        )
        var y = 56
        for (l in lines) {
            val w = Fb.textW(l)
            fb.plateText(l, (Art.GW - w) / 2, y, 1, 1, 1, 1)
            y += 8
        }
        if (sim.canRestart() && ((sim.crashTicks / 20f).toInt() and 1) == 0) {
            val s = "TAP TO FLY AGAIN"
            fb.plateText(s, (Art.GW - Fb.textW(s)) / 2, 152, 1, 1, 1, 2)
        }
    }
}
