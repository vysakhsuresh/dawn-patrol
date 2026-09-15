package com.dawnpatrol.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Short PCM tones synthesized at runtime - the pattern reused from Cat on a
 * Fence. Zero audio files: each clip is a MODE_STATIC AudioTrack, its buffer
 * written once, replayed by stop() / reloadStaticData() / play() so it never
 * re-synthesizes on the hot path.
 *
 * A continuous engine drone (MODE_STREAM, pitch tracking dive speed) is a
 * good next addition but a materially different AudioTrack mode - left for
 * a follow-up pass rather than half-tuned here.
 */
class Sound {

    private val sampleRate = 22050
    private var gun: AudioTrack? = null
    private var bomb: AudioTrack? = null
    private var hit: AudioTrack? = null

    fun load() {
        gun = makeClip(0.05) { t ->
            // short noisy crack: a high tone with fast exponential decay
            val decay = exp(-t * 60.0)
            sin(2.0 * PI * 1400.0 * t) * decay
        }
        bomb = makeClip(0.35) { t ->
            // descending whistle
            val freq = 700.0 - 550.0 * t
            val decay = exp(-t * 3.0)
            sin(2.0 * PI * freq * t) * decay
        }
        hit = makeClip(0.22) { t ->
            // low thud
            val freq = 160.0 - 90.0 * t
            val decay = exp(-t * 9.0)
            sin(2.0 * PI * freq * t) * decay
        }
    }

    private fun makeClip(durationSec: Double, gen: (Double) -> Double): AudioTrack {
        val n = (sampleRate * durationSec).toInt()
        val buf = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val v = gen(t).coerceIn(-1.0, 1.0)
            buf[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, buf.size)
        return track
    }

    private fun replay(track: AudioTrack?) {
        track ?: return
        track.stop()
        track.reloadStaticData()
        track.play()
    }

    fun playGun() = replay(gun)
    fun playBomb() = replay(bomb)
    fun playHit() = replay(hit)

    fun release() {
        gun?.release(); bomb?.release(); hit?.release()
        gun = null; bomb = null; hit = null
    }
}
