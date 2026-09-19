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

    private fun write(r: Renderer, pal: Renderer.Pal, path: String) {
        val w = r.fb.w; val h = r.fb.h
        val out = StringBuilder()
        out.append("P3\n$w $h\n255\n")
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = if (r.fb.p[y * w + x].toInt() != 0) pal.ink else pal.paper
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
            val ok = fly(sim, 400000, { s, _ ->
                val wantHigh = !s.isNight()
                s.climbing = if (wantHigh) s.py > 30f else s.altitude() < 52f
            }, { s, _ -> s.isNight() && s.lockTicks > 0f && !s.crashed })
            println("frame3 night+locked: $ok  phase=${sim.phase()}")
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f3_night.ppm")
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

        // ---- 5  a dusk pass, to show the palette cycle --------------------
        run {
            val sim = Sim(MemStore())
            sim.start()
            fly(sim, 400000, { s, _ -> s.climbing = s.py > 34f },
                { s, _ -> s.phase() >= 2.4f && s.phase() < 2.6f && !s.crashed })
            r.render(sim)
            write(r, r.palette(sim.phase()), "$dir/f5_dusk.ppm")
            println("frame5 phase=${sim.phase()}")
        }
    }
}
