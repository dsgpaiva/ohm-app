package com.ohm.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

data class ParkedCar(val lat: Double, val lon: Double, val accuracy: Float?, val time: Long)

/** Remembers where the car was left: the phone's position when the car's Bluetooth disconnects. */
object Parking {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("parking", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("parking", v).apply()

    fun get(ctx: Context): ParkedCar? {
        val p = prefs(ctx)
        if (!p.contains("parkLatBits")) return null
        return ParkedCar(Double.fromBits(p.getLong("parkLatBits", 0)), Double.fromBits(p.getLong("parkLonBits", 0)),
            p.getFloat("parkAcc", -1f).takeIf { it >= 0 }, p.getLong("parkTime", 0))
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove("parkLatBits").remove("parkLonBits").remove("parkAcc").remove("parkTime").apply()

    /** Uses the freshest position we have: the sharing service's last fix, else Android's last known. */
    @SuppressLint("MissingPermission")
    fun record(ctx: Context) {
        if (!enabled(ctx)) return
        val shared = Link.state.value.lastFix?.takeIf { System.currentTimeMillis() - it.time < 120_000 }
        val loc = shared ?: run {
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val lm = ctx.getSystemService(LocationManager::class.java)
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
                .minByOrNull { if (it.hasAccuracy()) it.accuracy else 999f }
        } ?: return
        prefs(ctx).edit()
            .putLong("parkLatBits", loc.latitude.toBits()).putLong("parkLonBits", loc.longitude.toBits())
            .putFloat("parkAcc", if (loc.hasAccuracy()) loc.accuracy else -1f)
            .putLong("parkTime", System.currentTimeMillis()).apply()
        notifySaved(ctx)
    }

    private fun notifySaved(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("parking", "Parked car", NotificationManager.IMPORTANCE_LOW))
        val walk = PendingIntent.getActivity(ctx, 3, walkIntent(ctx) ?: return, PendingIntent.FLAG_IMMUTABLE)
        nm.notify(3, Notification.Builder(ctx, "parking").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Parking location saved").setContentText("Tap to walk back to your car").setContentIntent(walk).setAutoCancel(true).build())
    }

    /** Walking directions in Google Maps (or any map app). */
    fun walkIntent(ctx: Context): Intent? {
        val p = get(ctx) ?: return null
        val gm = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lon}&mode=w")).setPackage("com.google.android.apps.maps")
        return if (gm.resolveActivity(ctx.packageManager) != null) gm else mapIntent(ctx)
    }

    fun mapIntent(ctx: Context): Intent? {
        val p = get(ctx) ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode("My car")})"))
    }
}

@Composable
fun ParkingCard() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(Parking.enabled(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose {} }
    @Suppress("UNUSED_EXPRESSION") refresh
    val p = Parking.get(ctx)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF222222)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Find my car", color = Color.White, fontSize = 18.sp)
                Text("Saves where you parked when the phone disconnects from the car's Bluetooth.", color = Color(0xFFA2A3A5), fontSize = 13.sp)
            }
            Switch(on, { on = it; Parking.setEnabled(ctx, it) })
        }
        if (Auto.carAddress(ctx).isEmpty()) Text("Choose the car's Bluetooth under \"Start automatically\" so Ohm Companion knows when you leave the car.", color = Color(0xFFFFC107), fontSize = 13.sp)
        if (p != null) {
            Text("Parked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.time)) +
                (p.accuracy?.let { " · ±${it.toInt()} m" } ?: ""), color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ Parking.walkIntent(ctx)?.let { runCatching { ctx.startActivity(it) } } }) { Text("Walk there") }
                Button({ Parking.mapIntent(ctx)?.let { runCatching { ctx.startActivity(it) } } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))) { Text("Map") }
                Button({ Parking.clear(ctx); refresh++ }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))) { Text("Clear") }
            }
        } else Text("No parking spot saved yet.", color = Color(0xFF757575), fontSize = 13.sp)
    }
}
