package com.ohm.companion

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

private val Blue = Color(0xFF2E78FF)

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLink()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleStart(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Color(0xFF171717), surface = Color(0xFF222222))) {
                Screen(onStart = { requestAndStart() }, onStop = { startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP)) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleStart(intent) }

    /** From the "Car connected — tap to share" notification. */
    private fun handleStart(i: Intent?) { if (i?.action == ACTION_START && Auto.hasLocation(this)) startLink() }

    companion object { const val ACTION_START = "com.ohm.companion.START" }

    private fun requestAndStart() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(list.toTypedArray())
    }

    private fun startLink() {
        val i = Intent(this, LinkService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}

@Composable
private fun Screen(onStart: () -> Unit, onStop: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by Link.state.collectAsState()
    var code by remember { mutableStateOf(Link.code(ctx)) }
    var ip by remember { mutableStateOf(Link.manualIp(ctx)) }
    var relay by remember { mutableStateOf(Link.relayNotifications(ctx)) }
    var granted by remember { mutableStateOf(false) }
    // Re-check whenever the user comes back from Settings.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        onPauseOrDispose {}
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
    val connected = st.connectedTo != null && now - st.lastAckAt < 15_000

    Column(
        Modifier.fillMaxSize().background(Color(0xFF171717)).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ohm Companion", fontSize = 30.sp, color = Color.White)
        Text("Shares this phone's GPS with the Ohm launcher in your car — handy when the head unit's own GPS is weak.", color = Color(0xFFA2A3A5))

        Card {
            Text("1. Connect", color = Color.White, fontSize = 18.sp)
            Text("Turn on your phone's hotspot and let the head unit join it (or put both on the same Wi-Fi).", color = Color(0xFFA2A3A5))
        }
        Card {
            Text("2. Pairing code", color = Color.White, fontSize = 18.sp)
            Text("Shown on the head unit in Controls › Location.", color = Color(0xFFA2A3A5))
            OutlinedTextField(code, { v -> code = v.filter(Char::isDigit).take(4); Link.setCode(ctx, code) }, label = { Text("Code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
            OutlinedTextField(ip, { v -> ip = v.trim(); Link.setManualIp(ctx, ip) }, label = { Text("Head-unit IP (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
        }
        AutoCard()
        ParkingCard()

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Relay notifications", color = Color.White)
                    Text("Messages appear on the head unit while sharing", color = Color(0xFFA2A3A5), fontSize = 13.sp)
                }
                Switch(relay, { v ->
                    relay = v; Link.setRelay(ctx, v)
                    if (v && !granted) ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                })
            }
            if (relay && !granted) RestrictedHelp()
        }

        Card {
            Text(
                when { !st.running -> "Not sharing"; connected -> "Connected to ${st.connectedTo}"; else -> "Sharing — looking for Ohm…" },
                color = if (connected) Color(0xFF25CB55) else Color.White, fontSize = 18.sp,
            )
            st.lastFix?.let { Text("GPS ±${it.accuracy.toInt()} m · ${"%.5f, %.5f".format(it.latitude, it.longitude)}", color = Color(0xFFA2A3A5), fontSize = 13.sp) }
            if (st.running) Text("${st.sent} packets sent", color = Color(0xFFA2A3A5), fontSize = 13.sp)
            st.error?.let { Text(it, color = Color(0xFFED4E3B)) }
            Spacer(Modifier.height(4.dp))
            if (!st.running) Button(onStart, enabled = code.length == 4, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start sharing") }
            else Button(onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED4E3B)), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Stop") }
        }
        Text("Data stays on your local network (UDP port ${Link.PORT}). The pairing code avoids mix-ups with other cars; it isn't encryption.", color = Color(0xFF757575), fontSize = 12.sp)
    }
}

/**
 * Android 13+ blocks notification access for apps installed from a file ("restricted settings").
 * Only the user can lift it, from the app's info page.
 */
@Composable
private fun RestrictedHelp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A2F12)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Notification access is blocked", color = Color(0xFFFFC107), fontSize = 16.sp)
        Text(
            "Android blocks this permission for apps installed from a file. To allow it:\n" +
                "1. Tap \"Open app info\" below.\n" +
                "2. Tap ⋮ (top-right) › \"Allow restricted settings\" and confirm.\n" +
                "3. Come back and switch on notification access for Ohm Companion.\n" +
                "If the ⋮ option isn't there, open the notification-access screen once first, then try again.",
            color = Color(0xFFE0E0E0), fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))) }) { Text("Open app info") }
            Button({ ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))) { Text("Notification access") }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF222222)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
