package com.dawnpatrol.game

import java.io.File

/**
 * Renders frames of the real game to PPM files.
 *
 * This is the point of keeping Fb/Renderer/Sim free of android.*: these
 * images are the shipped renderer's own output driven by the shipped
 * simulation, not a preview built alongside it. If a frame looks wrong
 * here, the game looks wrong.
 *
 * Run: tools/frames.sh
 */
object FrameDump {

    private fun write(r: Renderer, pal: Renderer.Pal, path: String) =
        writeFb(r.fb, pal, path)

    private fun writeFb(fb: Fb, pal: Renderer.Pal, path: String) {
        val w = fb.w; val h = fb.h
        val out = StringBuilder()
        out.append("P3\n$w $h\n255\n")
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = if (fb.p[y * w + x].toInt() != 0) pal.ink else pal.paper
                out.append((c shr 16) and 0xFF).append(' ')
                    .append((c shr 8) and 0xFF).append(' ')
                    .append(c and 0xFF).append(' ')
            }
            out.append('\n')
        }
        File(path).writeText(out.toString())
        println("wrote $path")
    }

    /** Fly until `stop` says the state is the one we want to photograph. */
    private fun fly(sim: Sim, maxTicks: Int, pilot: (Sim, Int) -> Unit,
                    stop: (Sim, Int) -> Boolean): Boolean {
        var t = 0
        while (t < maxTicks) {
            pilot(sim, t)
            sim.update(1f)
            if (stop(sim, t)) return true
            if (sim.crashed) return stop(sim, t)
            t++
        }
        return false
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = if (args.isNotEmpty()) args[0] else "out/kt"
        File(dir).mkdirs()
        val r = Renderer()

        // ---- 0  the opening briefing, before the first touch --------------
        run {
            val sim = Sim(MemStore())
            var t = 0
            while (t < 60) { sim.update(1f); t++ }
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f0_briefing.ppm")
        }

        // ---- A  READY: the card is gone, the aeroplane is still flying ----
        // The state the briefing tap now leaves you in. Nothing about this
        // frame should suggest a plane in trouble.
        run {
            val sim = Sim(MemStore())
            var t = 0
            while (t < 40) { sim.update(1f); t++ }
            sim.dismissBriefing()
            // land on a tick where the blinking prompt is lit
            while (t < 140) { sim.update(1f); t++ }
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/fA_ready.ppm")
            println("frameA ready: started=${sim.started} vy=${sim.vy}")
        }

        // ---- 1  dawn: the opening minute, quiet, still climbing out -------
        run {
            val sim = Sim(MemStore())
            sim.start()
            fly(sim, 4000, { s, _ -> s.climbing = s.altitude() < 46f },
                { s, t -> t > 230 })
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f1_dawn.ppm")
        }

        // ---- 2  a low strafing run with flak up and the multiplier going --
        run {
            val sim = Sim(MemStore())
            sim.start()
            var committedTo: Shot? = null
            var commitClimb = false
            val ok = fly(sim, 400000, { s, t ->
                val threat = s.shots.filter { it.alive && it.kind == K_SHELL }
                    .minByOrNull { it.fuse }
                if (threat !== committedTo) {
                    committedTo = threat
                    commitClimb = threat != null && s.altitude() < 38f
                }
                s.climbing = if (threat != null && threat.fuse < 34f) commitClimb
                             else s.altitude() < 26f
                s.firing = true
                if (t % 130 == 0) s.dropBomb()
            }, { s, _ ->
                s.mult >= 3 && s.bursts.any { it.alive && it.age < 6f } &&
                    s.shots.any { it.alive && it.kind == K_SHELL } && !s.crashed
            })
            println("frame2 reached target state: $ok  mult=${sim.mult}")
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f2_strafe.ppm")
        }

        // ---- 3  night, caught in a searchlight ----------------------------
        run {
            val sim = Sim(MemStore())
            sim.start()
            // cruise high: above MAX_ENGAGE the batteries cannot reach, which
            // is the only profile that reliably survives long enough to reach
            // darkness. Drop low once night falls, to get caught in a beam.
            // A lock is not guaranteed - searchlights are sparse, and the
            // pilot may be shot down first. So keep the best night frame seen
            // rather than photographing whatever state the crash left behind.
            var best = -1; var locked = false
            var t = 0
            while (t < 400000 && !sim.crashed) {
                if (sim.isNight()) sim.climbing = sim.altitude() < 52f else TestPilot.cruise(sim)
                sim.update(1f); t++
                if (!sim.isNight()) continue
                val rank = if (sim.lockTicks > 0f) 2 else 1
                if (rank > best) {
                    best = rank; locked = rank == 2
                    r.render(sim)
                    write(r, r.palette(sim.phase()), "$dir/f3_night.ppm")
                }
                if (locked) break
            }
            println("frame3 night reached=${best > 0} locked=$locked " +
                "rows=${sim.distance.toInt()}")
        }

        // ---- 4  the crash card -------------------------------------------
        run {
            val sim = Sim(MemStore())
            sim.start()
            fly(sim, 60000, { s, t ->
                s.climbing = s.altitude() < 14f && t < 1500
                s.firing = true
            }, { s, _ -> s.crashed && s.crashTicks > 30f })
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f4_crash.ppm")
            println("frame4 cause=${sim.crashCause} rows=${sim.distance.toInt()}")
        }

        // ---- 6  a scout attack: the answer to "nothing reaches me high" ---
        run {
            val sim = Sim(MemStore())
            sim.start()
            fly(sim, 400000, { s, _ ->
                s.climbing = s.py > Tune.CEIL_ROW + 6f     // cruise high
                s.firing = true
            }, { s, _ ->
                !s.crashed && s.enemies.any { e ->
                    e.alive && e.x - s.camX < Art.GW - 8 && e.x - s.camX > 40
                }
            })
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f6_scout.ppm")
            println("frame6 scouts=" + sim.enemies.count { it.alive })
        }

        // ---- 7  the moment a life is lost ---------------------------------
        run {
            val sim = Sim(MemStore())
            sim.start()
            fly(sim, 400000, { s, _ -> s.climbing = false }, { s, _ -> s.lifeFlash > 20f })
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f7_lifelost.ppm")
            println("frame7 lives=" + sim.lives + " cause=" + sim.lastLoss)
        }

        // ---- 8  a ground kill: LOOK at where the wreck comes to rest ------
        // This frame exists because "the plane hitting the ground feels odd"
        // was a real bug: the flat 22-column hitbox reached the mud up to 6
        // rows before any drawn pixel did (Audit A15). The point of this
        // picture is the gap under the wheels, so it is taken on the first
        // frame after the hit, before the card has finished drawing over it.
        run {
            var shot = false
            for (seed in 0 until 40) {
                val sim = Sim(MemStore())
                sim.start()
                var t = 0
                while (t < 40000 && !sim.gameOver) {
                    sim.climbing = t % 400 < (seed % 7)
                    sim.update(1f); t++
                }
                if (sim.crashCause != "GROUND") continue
                val prof = sim.bottomProfile(sim.planeSprite())
                val x0 = sim.camX + Art.PLAYER_X
                var gap = Float.MAX_VALUE
                for (c in prof.indices) if (prof[c] >= 0)
                    gap = minOf(gap, sim.groundAt(x0 + c) - (sim.py + prof[c]))
                r.render(sim)
                write(r, r.palette(sim.phase()), "$dir/f8_ground.ppm")
                println("frame8 GROUND kill, wreck sits %.2f rows clear".format(gap))
                shot = true
                break
            }
            if (!shot) println("frame8 SKIPPED - no GROUND death in 40 sorties")
        }

        // ---- 9  the ground-contact diagram --------------------------------
        // Side by side, at the world position where the two models disagree
        // most: LEFT is where the old flat 22-column box reported a crash,
        // RIGHT is where a drawn pixel actually reaches the earth. The
        // daylight under the left-hand aeroplane is the bug.
        run {
            val sim = Sim(MemStore())
            val spr = Art.SPR_PLANE
            val prof = sim.bottomProfile(spr)

            // find the world position with the largest disagreement
            var bestX = 0.0; var worst = -1f
            for (i in 0 until 6000) {
                val x0 = i * 7.0 + 0.5
                var hon = Float.MAX_VALUE; var box = Float.MAX_VALUE
                for (c in prof.indices) {
                    val gy = sim.groundAt(x0 + c)
                    if (prof[c] >= 0) hon = minOf(hon, gy - prof[c])
                    box = minOf(box, gy - Tune.PLANE_H)
                }
                if (hon - box > worst) { worst = hon - box; bestX = x0 }
            }

            val fb = Fb(100, 60)
            fb.clear(0)
            val half = 50
            for (pane in 0 until 2) {
                val ox = pane * half
                val px = ox + 12                       // where the nose goes
                var hon = Float.MAX_VALUE; var box = Float.MAX_VALUE
                for (c in prof.indices) {
                    val gy = sim.groundAt(bestX + c)
                    if (prof[c] >= 0) hon = minOf(hon, gy - prof[c])
                    box = minOf(box, gy - Tune.PLANE_H)
                }
                val py = if (pane == 0) box else hon
                // terrain, flattened into this pane and lifted into view
                val ref = sim.groundAt(bestX).toInt()
                for (c in 0 until half - 2) {
                    val gy = sim.groundAt(bestX - 12 + c).toInt() - ref + 44
                    fb.rect(ox + c, gy, 1, 60 - gy, 1)
                }
                val drawY = (py - sim.groundAt(bestX)).toInt() + 44
                fb.spriteClear(spr, px, drawY, 1, 0)
                fb.sprite(spr, px, drawY, 1)
                fb.text(if (pane == 0) "BOX" else "REAL", ox + 2, 3, 1)
            }
            fb.rect(half, 0, 1, 60, 1)
            writeFb(fb, r.palette(1f), "$dir/f9_contact.ppm")
            println("frame9 worst disagreement %.2f rows at worldX %.0f".format(worst, bestX))
        }

        // ---- 5  a dusk pass, to show the palette cycle --------------------
        run {
            val sim = Sim(MemStore())
            sim.start()
            fly(sim, 400000, { s, _ -> TestPilot.cruise(s) },
                { s, _ -> s.phase() >= 2.4f && s.phase() < 2.6f && !s.crashed })
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f5_dusk.ppm")
            println("frame5 phase=${sim.phase()} rows=${sim.distance.toInt()}")
        }
    }
}
