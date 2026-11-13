package com.ohm.launcher.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.OhmLog

/**
 * Navigation provider abstraction (SPEC-ideas §2.2). The UI only ever calls
 * [NavProviders.navigate]; whether that runs Ohm's own MapLibre/OSRM navigation or hands the
 * destination to Google Maps, Waze or any other app is decided here.
 */
interface NavProvider {
    val id: String
    val label: String
    /** True when navigation runs inside Ohm (map, maneuver card, ETA panel). */
    val embedded: Boolean
    fun available(ctx: Context): Boolean
    /** Returns false if the provider couldn't take the destination. */
    fun navigate(ctx: Context, dest: Place): Boolean
}

object InternalProvider : NavProvider {
    override val id = "internal"
    override val label = "Built-in"
    override val embedded = true
    override fun available(ctx: Context) = true
    override fun navigate(ctx: Context, dest: Place): Boolean { Ohm.nav.preview(dest); return true }
}

private abstract class IntentProvider(override val id: String, override val label: String, private val pkg: String?) : NavProvider {
    override val embedded = false
    abstract fun intent(dest: Place): Intent
    override fun available(ctx: Context): Boolean = pkg == null || runCatching { ctx.packageManager.getPackageInfo(pkg!!, 0); true }.getOrDefault(false)
    override fun navigate(ctx: Context, dest: Place): Boolean = runCatching {
        ctx.startActivity(intent(dest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrElse { OhmLog.w("Nav", "$label couldn't start", it); false }
}

private object GoogleMapsProvider : IntentProvider("google", "Google Maps", "com.google.android.apps.maps") {
    override fun intent(dest: Place) = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${dest.pos.lat},${dest.pos.lon}")).setPackage("com.google.android.apps.maps")
}

private object WazeProvider : IntentProvider("waze", "Waze", "com.waze") {
    override fun intent(dest: Place) = Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=${dest.pos.lat},${dest.pos.lon}&navigate=yes")).setPackage("com.waze")
}

/** Any installed app that handles geo: links (Sygic, OsmAnd, HERE, Magic Earth…). */
private object OtherAppProvider : IntentProvider("other", "Other app", null) {
    override fun intent(dest: Place) = Intent.createChooser(
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${dest.pos.lat},${dest.pos.lon}?q=${dest.pos.lat},${dest.pos.lon}(${Uri.encode(dest.name)})")), "Navigate")
}

object NavProviders {
    val all: List<NavProvider> = listOf(InternalProvider, GoogleMapsProvider, WazeProvider, OtherAppProvider)

    fun byId(id: String): NavProvider = all.firstOrNull { it.id == id } ?: InternalProvider

    fun available(ctx: Context) = all.filter { it.available(ctx) }

    /** The chosen provider, falling back to the built-in one if it isn't installed or fails. */
    fun navigate(ctx: Context, dest: Place) {
        val p = byId(Ohm.prefs.navApp.value)
        if (!p.available(ctx) || !p.navigate(ctx, dest)) InternalProvider.navigate(ctx, dest)
    }

    /** "Open in…" from the ETA panel: an external provider for the current destination. */
    fun handOff(ctx: Context, dest: Place) {
        val p = byId(Ohm.prefs.navApp.value).takeIf { !it.embedded && it.available(ctx) }
            ?: all.firstOrNull { !it.embedded && it.available(ctx) } ?: return
        p.navigate(ctx, dest)
    }
}
