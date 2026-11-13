package com.ohm.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ohm.launcher.MainActivity
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.Eq
import com.ohm.launcher.core.UpdateState
import com.ohm.launcher.obd.Elm
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.OhmTheme
import kotlin.math.roundToInt

// ============================================================ OBD-II (Vehicle tab)

@Composable
fun ObdSection() {
    val c = OhmTheme.colors
    val ctx = LocalContext.current
    val on by Ohm.prefs.obdEnabled.state.collectAsState()
    val devName by Ohm.prefs.obdDeviceName.state.collectAsState()
    val d by Ohm.obd.data.collectAsState()
    val dtcs by Ohm.obd.dtcs.collectAsState()
    val celsius by Ohm.prefs.tempC.state.collectAsState()
    val fuelUnit by Ohm.prefs.fuelUnit.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Section("OBD-II adapter") {
        Note("Real data from the car's diagnostic port through an ELM327-compatible Bluetooth adapter: fuel level and consumption, engine temperature, RPM, battery voltage and trouble codes. Pair the adapter in Android's Bluetooth settings first (PIN is usually 1234 or 0000).")
        Toggle(Ohm.prefs.obdEnabled, "Use OBD-II adapter")
        if (!on) return@Section
        if (!Ohm.obd.hasPermission()) {
            SettingRow(tr("Bluetooth permission"), tr("Allow \"Nearby devices\" so Ohm can talk to the adapter"), onClick = { (ctx as? MainActivity)?.requestBluetooth() }) {
                Pill(tr("Allow"), true) { (ctx as? MainActivity)?.requestBluetooth() }
            }
        }
        SettingRow(tr("Adapter"), devName.ifEmpty { tr("Choose…") }, onClick = { picking = !picking })
        if (picking) Panel {
            val list = Ohm.obd.pairedDevices()
            if (list.isEmpty()) Text(tr("No paired Bluetooth devices — pair the adapter in Android's Bluetooth settings."), style = OhmTheme.type.caption, color = c.textSecondary)
            for ((addr, name) in list) SettingRow(name, addr, onClick = {
                Ohm.prefs.obdDevice.set(addr); Ohm.prefs.obdDeviceName.set(name); picking = false
            })
        }
        Choice(Ohm.prefs.fuelType, "Fuel", listOf("petrol" to "Petrol", "diesel" to "Diesel", "lpg" to "LPG"))
        Panel {
            Text(tr(when (d.status) {
                "connected" -> "Connected"; "connecting" -> "Connecting…"; "initialising" -> "Talking to the car…"
                "no_device" -> "Choose the adapter"; "no_permission" -> "Bluetooth permission needed"; "error" -> "Not connected"; else -> "Off"
            }), style = OhmTheme.type.bodyStrong, color = when (d.status) { "connected" -> Accent.success; "error" -> Accent.danger; else -> c.textPrimary })
            if (d.detail.isNotEmpty()) Caption(d.detail)
            if (d.connected) {
                d.protocol?.let { Mono("protocol", it) }
                Mono("supported", "${d.supported.size} values")
                d.rpm?.let { Mono("rpm", it.roundToInt().toString()) }
                d.speedKmh?.let { Mono("speed", "${it.roundToInt()} km/h") }
                d.coolantC?.let { Mono("coolant", com.ohm.launcher.core.Fmt.temp(it, celsius)) }
                d.fuelPct?.let { Mono("fuel level", "${it.roundToInt()} %") }
                d.batteryV?.let { Mono("battery", "%.1f V".format(it)) }
                d.loadPct?.let { Mono("engine load", "${it.roundToInt()} %") }
                d.fuelLph?.let { Mono("fuel flow", "%.2f L/h".format(it)) }
                d.lPer100?.let { Mono("consumption", com.ohm.launcher.core.Fmt.consumption(it, fuelUnit)) }
                if (d.fuelLph == null) Caption(tr("This car doesn't report fuel flow — trips keep using the typical-consumption estimate."))
            }
        }
        if (d.connected) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Read trouble codes")) { Ohm.obd.readDtcs() }
                if (!dtcs.isNullOrEmpty()) {
                    if (!confirmClear) SecondaryButton(tr("Clear codes")) { confirmClear = true }
                    else PrimaryButton(tr("Confirm: clear codes"), color = Accent.danger) { confirmClear = false; Ohm.obd.clearDtcs() }
                }
            }
            if (confirmClear) Note("Clearing turns off the check-engine light and resets the emission readiness monitors. The fault comes back if it's still there — fix the cause first.")
            dtcs?.let { list ->
                Panel {
                    if (list.isEmpty()) Text(tr("No trouble codes stored"), style = OhmTheme.type.body, color = Accent.success)
                    for (code in list) Mono(code, Elm.common[code] ?: trNow("look up this code online"))
                }
            }
        }
    }
}

// ============================================================ Audio (Media tab)

@Composable
fun AudioSection() {
    val c = OhmTheme.colors
    val eqOn by Ohm.prefs.eqOn.state.collectAsState()
    val bands by Ohm.prefs.eqBands.state.collectAsState()
    Section("Audio") {
        Choice(Ohm.prefs.speedVolume, "Speed-sensitive volume", listOf(0 to "Off", 1 to "Low", 2 to "Medium", 3 to "High"))
        Note("Raises the music a little above 40 km/h to cover road noise, and lowers it again as you slow down. Changing the volume yourself sets the new base level.")
        Toggle(Ohm.prefs.eqOn, "Equalizer", "For the built-in Spotify receiver")
        if (eqOn) {
            @Suppress("UNUSED_EXPRESSION") bands
            val g = Eq.gains()
            Eq.labels.forEachIndexed { i, label ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(label), style = OhmTheme.type.label, color = c.textPrimary, modifier = Modifier.width(90.dp))
                    OhmSlider(g[i].toFloat(), { Eq.setGain(i, it.roundToInt()) }, Modifier.weight(1f), range = -10f..10f, steps = 19)
                    Text((if (g[i] > 0) "+" else "") + "${g[i]} dB", style = OhmTheme.type.caption, color = c.textSecondary, modifier = Modifier.width(56.dp))
                }
            }
            Row(Modifier.padding(12.dp)) { SecondaryButton(tr("Reset")) { Eq.reset() } }
            Note("Other apps (Spotify app, radio, Bluetooth audio) use the head unit's own sound settings.")
        }
    }
}

// ============================================================ Driving lockout (Display tab + Apps tab)

@Composable
fun LockoutSection() {
    val on by Ohm.prefs.lockout.state.collectAsState()
    val speed by Ohm.prefs.lockoutSpeed.state.collectAsState()
    val active by Ohm.driving.active.collectAsState()
    Section("Driving lockout") {
        Toggle(Ohm.prefs.lockout, "Lock distracting features while driving", "Video, games, browsers and typing are disabled while the car moves")
        if (on) {
            SettingRow(tr("Active above"), "$speed km/h · " + tr(if (active) "active now" else "not active")) {}
            OhmSlider(speed.toFloat(), { Ohm.prefs.lockoutSpeed.set(it.roundToInt()) }, Modifier.padding(horizontal = 12.dp), range = 3f..30f, steps = 26)
            Note("Saved places, recent destinations, categories, music and calls keep working. Allow individual apps in Controls › Apps.")
        }
    }
}

@Composable
fun LockoutAppsSection() {
    val apps by Ohm.apps.apps.collectAsState()
    val allowed by Ohm.prefs.lockoutAllowed.state.collectAsState()
    val restricted = remember(apps) { apps.filter { Ohm.driving.isRestrictedKind(it.pkg) } }
    Section("Blocked while driving") {
        if (restricted.isEmpty()) Note("No video, game or browser apps found.")
        for (a in restricted) SettingRow(a.label, tr(if (a.pkg in allowed) "Allowed while driving" else "Blocked while driving")) {
            OhmSwitch(a.pkg in allowed) { Ohm.driving.allow(a.pkg, it) }
        }
    }
}

// ============================================================ Updates (System tab)

@Composable
fun UpdatesSection() {
    val st by Ohm.updater.state.collectAsState()
    val url by Ohm.prefs.updateUrl.state.collectAsState()
    var draft by remember(url) { mutableStateOf(url) }
    val (code, name) = Ohm.updater.currentVersion
    Section("Updates") {
        SettingRow(tr("Installed version"), "$name ($code)") {}
        Note("On your PC run  python tools/serve-updates.py  and paste the address it prints (same Wi-Fi). Any https:// address with an update.json works too.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OhmTextField(draft, { draft = it.trim() }, "http://192.168.1.x:8765/update.json", Modifier.weight(1f).padding(horizontal = 12.dp))
            SecondaryButton(tr("Save")) { Ohm.prefs.updateUrl.set(draft) }
        }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(tr("Check now")) { Ohm.prefs.updateUrl.set(draft); Ohm.updater.check() }
            when (val s = st) {
                is UpdateState.Checking -> CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), color = Accent.accent, strokeWidth = 3.dp)
                is UpdateState.UpToDate -> Caption(tr("Ohm is up to date"), color = Accent.success)
                is UpdateState.Available -> PrimaryButton(tr("Install") + " ${s.info.versionName}") { Ohm.updater.install(s.info) }
                is UpdateState.Error -> Caption(s.message, color = Accent.danger)
                else -> {}
            }
        }
        when (val s = st) {
            is UpdateState.Available -> if (s.info.notes.isNotBlank()) Note(s.info.notes)
            is UpdateState.Downloading -> Column(Modifier.padding(horizontal = 12.dp)) {
                Caption(tr("Downloading") + " ${s.info.versionName}…")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = Accent.accent)
            }
            is UpdateState.Installing -> Note("Confirm the installation in the Android dialog. Ohm restarts as the new version.")
            else -> {}
        }
        if (!Ohm.updater.canInstall()) SettingRow(tr("Allow Ohm to install updates"), tr("Android asks once: \"Install unknown apps\""), onClick = { Ohm.updater.openInstallPermission() })
    }
}
