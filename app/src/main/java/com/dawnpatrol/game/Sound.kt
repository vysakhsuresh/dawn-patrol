package com.dawnpatrol.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * All audio is synthesized at runtime. Zero audio files.
 *
 * Two mechanisms, because they are genuinely different problems:
 *
 *  - Short effects (gun, bomb, thud, hit) are MODE_STATIC tracks whose
 *    buffer is written once and replayed with stop() / reloadStaticData() /
 *    play(). Nothing is synthesized on the hot path.
 *
 *  - The engine is a continuous MODE_STREAM drone on its own thread, with
 *    its pitch tracking airspeed - so diving audibly winds the engine up
 *    and hauling back bleeds it off. That is the single strongest piece of
 *    feedback for a control scheme with no discrete jump to hear.
 *
 * Every call is wrapped: a device that refuses to give us an AudioTrack
 * should cost the player their sound, never their game.
 */
class Sound {

    private val rate = 22050

    // Built on a background thread (four AudioTrack builds are four trips
    // through AudioFlinger, which is a visible stall on the main thread at
    // startup), read on the main thread - so they have to be volatile, and
    // `released` has to be checked after the build in case the view went
    // away while we were still making them.
    @Volatile private var gun: AudioTrack? = null
    @Volatile private var bomb: AudioTrack? = null
    @Volatile private var thud: AudioTrack? = null
    @Volatile private var hit: AudioTrack? = null
    @Volatile private var released = false

    // ---- engine drone ----------------------------------------------------
    @Volatile private var enginePitch = 0f
    @Volatile private var engineOn = false
    private var engineThread: Thread? = null
    private var engineTrack: AudioTrack? = null

    fun load() {
        try {
            gun = clip(0.05) { t -> sin(2.0 * PI * 1400.0 * t) * exp(-t * 60.0) }
            bomb = clip(0.35) { t -> sin(2.0 * PI * (700.0 - 550.0 * t) * t) * exp(-t * 3.0) }
            thud = clip(0.20) { t ->
                // flak: a band-limited crack rather than a clean tone
                val n = sin(2.0 * PI * 190.0 * t) + 0.6 * sin(2.0 * PI * 437.0 * t)
                n * exp(-t * 14.0) * 0.6
            }
            hit = clip(0.5) { t -> sin(2.0 * PI * (160.0 - 120.0 * t) * t) * exp(-t * 4.0) }
            if (released) release()
        } catch (e: Throwable) {
            gun = null; bomb = null; thud = null; hit = null
        }
    }

    private fun clip(seconds: Double, gen: (Double) -> Double): AudioTrack {
        val n = (rate * seconds).toInt()
        val buf = ShortArray(n)
        for (i in 0 until n) {
            val v = gen(i.toDouble() / rate).coerceIn(-1.0, 1.0)
            buf[i] = (v * Short.MAX_VALUE * 0.8).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, buf.size)
        return track
    }

    private fun replay(track: AudioTrack?) {
        val t = track ?: return
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Throwable) { /* never let audio take the game down */ }
    }

    fun playGun() = replay(gun)
    fun playBomb() = replay(bomb)
    fun playThud() = replay(thud)
    fun playHit() = replay(hit)

    // ---- engine -----------------------------------------------------------
    /** 0 = hauling back and slow, 1 = full dive. */
    fun setEnginePitch(v: Float) { enginePitch = v }

    fun startEngine() {
        if (engineThread != null) return
        engineOn = true
        val th = Thread {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufSize = maxOf(minBuf, 2048)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                engineTrack = track
                track.play()

                val chunk = ShortArray(512)
                var phase = 0.0
                var phase2 = 0.0
                while (engineOn) {
                    // a rotary engine is a low buzz with a beating second
                    // partial - a pure sine reads as a test tone, not a motor
                    val p = enginePitch
                    val f = 52.0 + 46.0 * p
                    val step = 2.0 * PI * f / rate
                    val step2 = 2.0 * PI * (f * 2.51) / rate
                    for (i in chunk.indices) {
                        phase += step; phase2 += step2
                        if (phase > 2 * PI) phase -= 2 * PI
                        if (phase2 > 2 * PI) phase2 -= 2 * PI
                        // clipped sine -> harmonics, so it sounds mechanical
                        val a = sin(phase)
                        val s = (if (a > 0.32) 0.32 else if (a < -0.32) -0.32 else a) * 2.2
                        val v = (s * 0.55 + sin(phase2) * 0.18) * (0.22 + 0.16 * p)
                        chunk[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                    }
                    track.write(chunk, 0, chunk.size)
                }
                track.stop()
                track.release()
            } catch (e: Throwable) {
                // no engine note on this device; the game plays on
            } finally {
                engineTrack = null
            }
        }
        th.isDaemon = true
        engineThread = th
        th.start()
    }

    fun stopEngine() {
        engineOn = false
        engineThread = null
    }

    fun release() {
        released = true
        stopEngine()
        try { gun?.release(); bomb?.release(); thud?.release(); hit?.release() }
        catch (e: Throwable) { }
        gun = null; bomb = null; thud = null; hit = null
    }
}
