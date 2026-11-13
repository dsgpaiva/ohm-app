package com.ohm.launcher.core

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import com.ohm.launcher.Ohm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Speed-sensitive volume (like Tesla's): above 40 km/h the music volume is raised a little to
 * cover road and wind noise, and lowered again when slowing down. Manual changes are respected —
 * whatever you set becomes the new base level.
 */
class SpeedVolume(ctx: Context, private val scope: CoroutineScope) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var base: Int? = null
    private var applied = 0
    private var lastSet = -1
    private var smoothKmh = 0.0
    private var pending = 0
    private var pendingSince = 0L

    fun init() {
        scope.launch {
            while (isActive) {
                delay(1000)
                runCatching { tick() }
            }
        }
    }

    private fun tick() {
        val level = Ohm.prefs.speedVolume.value
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (base == null) base = current
        // Someone (driver, steering wheel, another app) changed the volume → that's the new base.
        if (lastSet >= 0 && current != lastSet) { base = (current - applied).coerceIn(0, max); lastSet = -1 }
        if (level == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC)) {
            if (applied != 0 && level == 0) set((base ?: current), 0)
            return
        }
        val kmh = (Ohm.car.state.value.speedKmh ?: Ohm.nav.state.value.speedKmh).toDouble()
        smoothKmh += (kmh - smoothKmh) * 0.3
        val want = (boostFor(smoothKmh, level) * max).roundToInt()
        // Only change after the new level has held for 3 s (no pumping at the threshold).
        val now = System.currentTimeMillis()
        if (want != pending) { pending = want; pendingSince = now; return }
        if (want == applied || now - pendingSince < 3000) return
        set(((base ?: current) + want).coerceIn(0, max), want)
    }

    companion object {
        /** Extra volume (fraction of the maximum) for a speed and level 1–3. */
        fun boostFor(kmh: Double, level: Int): Double {
            if (level <= 0 || kmh <= 40) return 0.0
            val perStep = listOf(0.0, 0.05, 0.075, 0.10)[level.coerceIn(0, 3)]
            val cap = listOf(0.0, 0.10, 0.15, 0.20)[level.coerceIn(0, 3)]
            return ((kmh - 40) / 30 * perStep).coerceAtMost(cap)
        }
    }

    private fun set(index: Int, offset: Int) {
        Ohm.system.quietUntil = System.currentTimeMillis() + 1500 // no volume banner for automatic changes
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        lastSet = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        applied = offset
    }
}

/** Five-band equalizer settings, applied to the built-in Spotify receiver's audio session. */
object Eq {
    val labels = listOf("Bass", "Low-mid", "Mid", "High-mid", "Treble")

    fun gains(): List<Int> = Ohm.prefs.eqBands.value.split(',').map { it.trim().toIntOrNull() ?: 0 }.let { l -> List(5) { l.getOrElse(it) { 0 } } }

    fun setGain(band: Int, db: Int) {
        val g = gains().toMutableList(); g[band] = db.coerceIn(-10, 10)
        Ohm.prefs.eqBands.set(g.joinToString(","))
    }

    fun reset() = Ohm.prefs.eqBands.set("0,0,0,0,0")

    /** Maps the 5 UI bands onto however many bands the device's equalizer has. */
    fun apply(eq: Equalizer?) {
        eq ?: return
        runCatching {
            val on = Ohm.prefs.eqOn.value
            eq.enabled = on
            if (!on) return
            val g = gains()
            val n = eq.numberOfBands.toInt()
            val (lo, hi) = eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            for (b in 0 until n) {
                val ui = if (n == 5) b else (b * 4.0 / (n - 1).coerceAtLeast(1)).roundToInt()
                eq.setBandLevel(b.toShort(), (g[ui] * 100).coerceIn(lo, hi).toShort())
            }
        }
    }
}
