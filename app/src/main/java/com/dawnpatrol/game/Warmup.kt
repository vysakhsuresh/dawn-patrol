package com.dawnpatrol.game

/**
 * The cold-start warm-up, and the 1-bit -> ARGB expansion it shares with
 * the real blit.
 *
 * WHY THIS EXISTS. On a first-ever launch there is no ART profile for the
 * app yet, so every method runs interpreted until it has been called often
 * enough to be compiled. Sim.update and Renderer.render walk all 17500
 * pixels several times per frame; interpreted, that is tens of milliseconds
 * a frame. The frame rate collapses, and because the loop clamps dt the game
 * ALSO drops into slow motion - so the aeroplane both looks laggy and
 * answers the stick late. It comes right on the second launch, once the
 * profile has been written and compiled in the background, which is exactly
 * why it is so easy to ship: it only ever happens to people opening the game
 * for the first time.
 *
 * The cure is to spend the seconds the briefing card is on screen calling
 * the real shipped methods, off-screen, on a background thread. The JIT
 * counts invocations PER METHOD, so it has to be these methods and not a
 * copy of them - which is why `expand` lives here and the renderer's blit
 * calls it rather than holding its own loop.
 *
 * WHY IT IS NOT IN GameView: keeping it free of android.* means Audit A19
 * runs the same code the device runs, and can prove it terminates, stays
 * inside its budget and never throws. A warm-up that crashed the app would
 * be far worse than a slow first minute.
 */
object Warmup {

    /** 1 bit per pixel -> ARGB, straight into the bitmap's backing array. */
    fun expand(fb: Fb, pal: Renderer.Pal, out: IntArray) {
        val buf = fb.p
        val ink = pal.ink or (0xFF shl 24)
        val paper = pal.paper or (0xFF shl 24)
        var i = 0
        val n = buf.size
        while (i < n) {
            out[i] = if (buf[i].toInt() != 0) ink else paper
            i++
        }
        }

    /**
     * Fly a whole sortie off-screen, touching nothing the main thread owns.
     *
     * Its Sim, Renderer and pixel buffer are its own; World and Art are
     * immutable, so this is safe to run beside the live game. It stops at
     * `budgetNanos` whatever happens, so on a device that is already fast it
     * costs a moment of one idle core and nothing else.
     *
     * @return the number of frames it managed, for the audit to look at.
     */
    fun run(budgetNanos: Long, now: () -> Long = { System.nanoTime() }): Int {
        val s = Sim(MemStore())
        val r = Renderer()
        val out = IntArray(Art.GW * Art.GH)
        val deadline = now() + budgetNanos
        var frames = 0

        // the loiter, which is what is actually on screen right now...
        var i = 0
        while (i < 240 && now() < deadline) {
            s.update(1f); r.render(s); expand(r.fb, r.palette(s.phase()), out)
            i++; frames++
        }
        // ...then a real sortie, so the flak, bomb, gun, scout, banner and
        // end-card paths are all compiled before they are first needed.
        s.dismissBriefing()
        s.start()
        i = 0
        while (i < 2400 && now() < deadline) {
            s.climbing = s.altitude() < 44f
            s.firing = (i and 15) < 6
            if (i % 140 == 0) s.dropBomb()
            s.update(1f)
            r.render(s)
            expand(r.fb, r.palette(s.phase()), out)
            if (s.gameOver && s.crashTicks > Tune.GAMEOVER_LOCKOUT) s.reset()
            i++; frames++
        }
        return frames
    }
}
