package com.ohm.launcher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.ohm.launcher.Ohm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val label: String, val pkg: String, val activity: String, val icon: ImageBitmap?) {
    val key get() = pkg
}

class AppsRepo(private val ctx: Context, private val scope: CoroutineScope) {
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    fun init() {
        reload()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = reload()
        }, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun reload() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val pm = ctx.packageManager
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(i, 0)
                    .filter { it.activityInfo.packageName != ctx.packageName }
                    .map {
                        val icon = runCatching { it.loadIcon(pm).toBitmap(128, 128).asImageBitmap() }.getOrNull()
                        AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name, icon)
                    }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
            }
            _apps.value = list
            if (!Ohm.prefs.dockInitialized.value && list.isNotEmpty()) {
                Ohm.prefs.dockApps.set(defaultDock(list))
                Ohm.prefs.dockInitialized.set(true)
            }
        }
    }

    /** Best guesses for a car dock: phone, music, radio, CarPlay/AA bridge, video, camera, browser. */
    private fun defaultDock(list: List<AppInfo>): List<String> {
        val wanted = listOf(
            listOf("dialer", "phone", "bluetooth", "btphone", "contacts"),
            listOf("spotify", "youtube.music", "music", "deezer"),
            listOf("radio", "fm"),
            listOf("zlink", "tlink", "carplay", "autokit", "carlink", "android.projection"),
            listOf("youtube"),
            listOf("camera", "dvr", "avm"),
            listOf("chrome", "browser"),
            listOf("waze", "maps"),
        )
        val out = LinkedHashSet<String>()
        for (keys in wanted) {
            list.firstOrNull { a -> a.pkg !in out && keys.any { k -> a.pkg.lowercase().contains(k) || a.label.lowercase().contains(k) } }?.let { out += it.pkg }
        }
        if (out.size < 4) list.take(6 - out.size).forEach { out += it.pkg }
        return out.take(8).toList()
    }

    fun find(pkg: String) = _apps.value.firstOrNull { it.pkg == pkg }

    /** Set by the UI: opens Ohm's own Spotify screen instead of the Spotify app (Tesla-style). */
    var openSpotifyInside: (() -> Unit)? = null

    fun launch(app: AppInfo, record: Boolean = true): Boolean = runCatching {
        if (Ohm.driving.isBlocked(app.pkg)) {
            val lang = Ohm.prefs.language.value
            Ohm.banners.info(app.label, com.ohm.launcher.i18n.Strings.tr(lang, "Available when parked"))
            return false
        }
        if (record && app.pkg == com.ohm.launcher.spotify.Spotify.SPOTIFY_PKG && Ohm.prefs.spotifyInOhm.value && Ohm.spotify.signedIn) {
            openSpotifyInside?.let { it(); return@runCatching }
        }
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(app.pkg, app.activity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            if (record) Ohm.prefs.recentApps.set((listOf(app.pkg) + Ohm.prefs.recentApps.value.filter { it != app.pkg }).take(6))
        }.isSuccess

    fun launchPkg(pkg: String, record: Boolean = true): Boolean = find(pkg)?.let { launch(it, record) } ?: false

    fun pin(pkg: String) { val d = Ohm.prefs.dockApps.value; if (pkg !in d) Ohm.prefs.dockApps.set((d + pkg).take(8)) }
    fun unpin(pkg: String) = Ohm.prefs.dockApps.set(Ohm.prefs.dockApps.value - pkg)
    fun move(pkg: String, delta: Int) {
        val d = Ohm.prefs.dockApps.value.toMutableList()
        val i = d.indexOf(pkg); val j = i + delta
        if (i < 0 || j !in d.indices) return
        d[i] = d[j].also { d[j] = d[i] }
        Ohm.prefs.dockApps.set(d)
    }
    fun setHidden(pkg: String, hidden: Boolean) {
        val h = Ohm.prefs.hiddenApps.value
        Ohm.prefs.hiddenApps.set(if (hidden) (h + pkg).distinct() else h - pkg)
        if (hidden) unpin(pkg)
    }

    fun openExternalNav(app: String, lat: Double, lon: Double, name: String): Boolean = runCatching {
        val i = when (app) {
            "waze" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes"))
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).setPackage("com.google.android.apps.maps")
        }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrElse {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    fun openDialer() = runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    fun openSettings(action: String) = runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
