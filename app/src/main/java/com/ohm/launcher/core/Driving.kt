package com.ohm.launcher.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.ohm.launcher.Ohm
import com.ohm.launcher.car.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Driving lockout (like Tesla): while the car moves, video, games and browsers can't be opened
 * and typing is disabled. Driving = above the set speed for 2 s and not in P; parked again after
 * 4 s below 3 km/h or when shifting to P. Each app can be allowed individually.
 */
class Driving(private val ctx: Context, private val scope: CoroutineScope) {
    private val _active = MutableStateFlow(false)
    /** True while the lockout applies (setting on + driving). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val knownBlocked = listOf(
        "youtube", "netflix", "primevideo", "amazon.avod", "disney", "hbo", "max.", "twitch", "tiktok", "musically",
        "vimeo", "plex", "kodi", "vlc", "mxtech", "skyshowtime", "rtp.play", "sic.", "tvi", "instagram", "facebook.katana",
    )
    private var browsers: Set<String> = emptySet()

    fun init() {
        browsers = runCatching {
            ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        scope.launch {
            var aboveSince = 0L
            var belowSince = 0L
            while (isActive) {
                delay(500)
                val car = Ohm.car.state.value
                val kmh = car.speedKmh ?: Ohm.nav.state.value.speedKmh
                val now = System.currentTimeMillis()
                val parked = car.gear == Gear.P
                if (kmh > Ohm.prefs.lockoutSpeed.value && !parked) { if (aboveSince == 0L) aboveSince = now; belowSince = 0 }
                else if (kmh < 3 || parked) { if (belowSince == 0L) belowSince = now; aboveSince = 0 }
                val driving = when {
                    _active.value -> !(parked || (belowSince > 0 && now - belowSince > 4000))
                    else -> aboveSince > 0 && now - aboveSince > 2000
                }
                val on = driving && Ohm.prefs.lockout.value
                if (on != _active.value) { _active.value = on; OhmLog.i("Lockout", if (on) "Driving — lockout on" else "Parked — lockout off") }
            }
        }
    }

    /** Would this app be blocked while driving (ignoring the user's allow-list)? */
    fun isRestrictedKind(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (listOf("music", "radio", "spotify", "podcast", "audio", "deezer", "tidal", "soundcloud").any { p.contains(it) }) return false
        if (knownBlocked.any { p.contains(it) } || pkg in browsers) return true
        val info = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        @Suppress("DEPRECATION")
        return info.category == ApplicationInfo.CATEGORY_VIDEO || info.category == ApplicationInfo.CATEGORY_GAME ||
            (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }

    fun isBlocked(pkg: String): Boolean = _active.value && isRestrictedKind(pkg) && pkg !in Ohm.prefs.lockoutAllowed.value

    fun allow(pkg: String, allowed: Boolean) {
        val l = Ohm.prefs.lockoutAllowed.value
        Ohm.prefs.lockoutAllowed.set(if (allowed) (l + pkg).distinct() else l - pkg)
    }
}
