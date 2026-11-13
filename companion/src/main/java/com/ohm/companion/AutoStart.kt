package com.ohm.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * Hands-free sharing: when the phone connects to the car's Bluetooth (i.e. the car starts),
 * sharing starts by itself; when it disconnects (car off), it stops. Settings persist, so
 * nothing has to be re-linked — Ohm on the head unit already remembers the pairing code.
 */
object Auto {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("auto", false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auto", v).apply()
    fun carAddress(ctx: Context) = prefs(ctx).getString("carAddr", "") ?: ""
    fun carName(ctx: Context) = prefs(ctx).getString("carName", "") ?: ""
    fun setCar(ctx: Context, addr: String, name: String) = prefs(ctx).edit().putString("carAddr", addr).putString("carName", name).apply()

    fun hasBtPermission(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    fun hasLocation(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    /** "Allow all the time" — needed to read GPS when sharing starts with the app closed. */
    fun hasBackgroundLocation(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun ignoresBattery(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    @SuppressLint("MissingPermission")
    fun pairedDevices(ctx: Context): List<Pair<String, String>> = runCatching {
        if (!hasBtPermission(ctx)) return emptyList()
        ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .map { it.address to (it.name ?: it.address) }.sortedBy { it.second.lowercase() }
    }.getOrDefault(emptyList())

    fun start(ctx: Context) {
        val i = Intent(ctx, LinkService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    /** If Android refuses a background start, offer a one-tap notification instead. */
    fun notifyTapToStart(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("auto", "Car connected", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_START), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(ctx, "auto").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Car connected").setContentText("Tap to share location with Ohm").setContentIntent(open).setAutoCancel(true).build())
    }
}

/** Listens for the car's Bluetooth connecting / disconnecting (allowed for manifest receivers). */
class CarConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        if (device?.address == null || device.address != Auto.carAddress(ctx)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                ctx.getSystemService(NotificationManager::class.java).cancel(3) // old "parking saved"
                if (Auto.enabled(ctx)) runCatching { Auto.start(ctx) }.onFailure { Auto.notifyTapToStart(ctx) }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                // Car switched off / left: remember where (before sharing stops, while the last fix is fresh).
                Parking.record(ctx)
                if (Auto.enabled(ctx)) ctx.stopService(Intent(ctx, LinkService::class.java))
                ctx.getSystemService(NotificationManager::class.java).cancel(2)
            }
        }
    }
}

// ============================================================ UI

@Composable
fun AutoCard() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(Auto.enabled(ctx)) }
    var carName by remember { mutableStateOf(Auto.carName(ctx)) }
    var picking by remember { mutableStateOf(false) }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; if (it) picking = true }
    val bgPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val fgPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose {} }
    @Suppress("UNUSED_EXPRESSION") refresh

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF222222)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Start automatically", color = Color.White, fontSize = 18.sp)
                Text("Share when the phone connects to the car's Bluetooth, stop when it disconnects. No need to open this app.", color = Color(0xFFA2A3A5), fontSize = 13.sp)
            }
            Switch(on, { v -> on = v; Auto.setEnabled(ctx, v); if (v && carName.isEmpty()) { if (Auto.hasBtPermission(ctx)) picking = true else if (Build.VERSION.SDK_INT >= 31) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) } })
        }
        if (!on) return@Column

        // Which Bluetooth device is the car
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
            if (Auto.hasBtPermission(ctx)) picking = !picking else if (Build.VERSION.SDK_INT >= 31) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Car Bluetooth", color = Color.White, modifier = Modifier.weight(1f))
            Text(carName.ifEmpty { "Choose…" }, color = if (carName.isEmpty()) Color(0xFFFFC107) else Color(0xFF2E78FF))
        }
        if (picking) {
            val devices = Auto.pairedDevices(ctx)
            if (devices.isEmpty()) Text("No paired Bluetooth devices. Pair the head unit with this phone first.", color = Color(0xFFA2A3A5), fontSize = 13.sp)
            for ((addr, name) in devices) Text(name, color = Color.White, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { Auto.setCar(ctx, addr, name); carName = name; picking = false }.padding(vertical = 10.dp, horizontal = 8.dp))
        }

        // Checklist: everything that has to be true for a hands-free start
        Text("Checklist", color = Color(0xFFA2A3A5), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Check("Pairing code set", Link.code(ctx).length == 4, null, null)
        Check("Car chosen", carName.isNotEmpty(), "Choose") { if (Auto.hasBtPermission(ctx)) picking = true else if (Build.VERSION.SDK_INT >= 31) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        Check("Location allowed", Auto.hasLocation(ctx), "Allow") { fgPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        Check("Location \"Allow all the time\"", Auto.hasBackgroundLocation(ctx), "Allow") {
            if (Build.VERSION.SDK_INT >= 29 && Auto.hasLocation(ctx)) bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else fgPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
        Check("Not stopped by battery saver", Auto.ignoresBattery(ctx), "Allow") {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        Text("Also make sure the head unit joins your phone's hotspot (or a shared Wi-Fi) automatically. Many phones can switch the hotspot on by themselves for known devices.",
            color = Color(0xFF757575), fontSize = 12.sp)
    }
}

@Composable
private fun Check(label: String, ok: Boolean, action: String?, onClick: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Color(0xFF25CB55) else Color(0xFFFFC107)))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (!ok && action != null && onClick != null) TextButton(onClick) { Text(action) }
    }
}
