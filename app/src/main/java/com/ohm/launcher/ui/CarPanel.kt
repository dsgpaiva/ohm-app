package com.ohm.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirlineSeatReclineNormal
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohm.launcher.Ohm
import com.ohm.launcher.car.Car3DView
import com.ohm.launcher.car.CarPaints
import com.ohm.launcher.car.Gear
import com.ohm.launcher.core.Banner
import com.ohm.launcher.core.Fmt
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.LocalLowPower
import com.ohm.launcher.ui.theme.OhmTheme
import com.ohm.launcher.ui.theme.Motion
import kotlinx.coroutines.delay

@Composable
fun CarPanel(modifier: Modifier) {
    val c = OhmTheme.colors
    val car by Ohm.car.state.collectAsState()
    val nav by Ohm.nav.state.collectAsState()
    val metric by Ohm.prefs.speedKmh.state.collectAsState()
    val body by Ohm.prefs.carBody.state.collectAsState()
    val paintIdx by Ohm.prefs.carColor.state.collectAsState()
    val srcId by Ohm.car.sourceId.collectAsState()
    val demo = srcId == "demo"
    val speed = car.speedKmh ?: nav.speedKmh
    val layout by Ohm.prefs.layout.state.collectAsState()
    val showLimit by Ohm.prefs.showSpeedLimit.state.collectAsState()
    val showButtons by Ohm.prefs.showCarButtons.state.collectAsState()
    val showMusic by Ohm.prefs.showMusicWidget.state.collectAsState()

    Column(modifier.background(c.panel)) {
        // ---- gear + speed cluster
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.Top) {
            GearStrip(car.gear, demo) { g -> Ohm.car.demo?.setGear(g) }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (car.passengerAirbagOff == true) Icon(Icons.Rounded.AirlineSeatReclineNormal, tr("Passenger airbag off"), tint = Accent.warning, modifier = Modifier.size(20.dp))
                if (car.headlights == true) Icon(Icons.Rounded.Lightbulb, tr("Headlights on"), tint = Accent.success, modifier = Modifier.size(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(56.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${Fmt.speed(speed, metric)}", style = OhmTheme.type.speed, color = c.textPrimary)
                Text(if (metric) "km/h" else "mph", style = OhmTheme.type.label, color = c.textSecondary)
            }
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                if (showLimit) nav.speedLimit?.let { SpeedLimitSign(Fmt.speed(it.toFloat(), metric), over = speed > it + 3) }
            }
        }

        ObdStrip(car)

        // ---- 3D car (or trip summary in "map focus" layout)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (layout == "map") TripMini(Modifier.align(Alignment.Center))
            else Car3DView(
                Modifier.fillMaxSize(), car, body, CarPaints.getOrElse(paintIdx) { CarPaints[0] }.second,
                c.isDark, LocalLowPower.current,
            )
            if (showButtons && layout != "map") CarQuickButtons(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), demo)
        }

        // ---- notifications + music
        NotificationStack(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        if (showMusic) MusicWidget(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 6.dp))
        else Spacer(Modifier.height(12.dp))
    }
}

/** Current trip at a glance (map-focus layout). */
@Composable
private fun TripMini(modifier: Modifier) {
    val c = OhmTheme.colors
    val t by Ohm.trips.current.collectAsState()
    val km by Ohm.prefs.distanceKm.state.collectAsState()
    val kmh by Ohm.prefs.speedKmh.state.collectAsState()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val trip = t
        if (trip == null) { Caption(tr("No trip in progress")); return@Column }
        Text(com.ohm.launcher.core.Fmt.distance(trip.distanceM, km), style = OhmTheme.type.display, color = c.textPrimary)
        Caption(com.ohm.launcher.core.Fmt.duration(trip.durationS) + " · " + tr("avg") + " " + Fmt.speed(trip.avgKmh.toFloat(), kmh) + if (kmh) " km/h" else " mph")
    }
}

/** Fuel, consumption, engine temperature and battery from OBD-II (only when connected). */
@Composable
private fun ObdStrip(car: com.ohm.launcher.car.CarState) {
    val c = OhmTheme.colors
    val obd by Ohm.obd.data.collectAsState()
    if (!obd.connected) return
    val celsius by Ohm.prefs.tempC.state.collectAsState()
    val fuelUnit by Ohm.prefs.fuelUnit.state.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        car.fuelPct?.let { ObdChip(tr("Fuel"), "${it.toInt()} %", if (it < 12) Accent.warning else c.textPrimary) }
        obd.lPer100?.let { ObdChip(tr("Now"), Fmt.consumption(it, fuelUnit).substringBefore(' '), c.textPrimary) }
        car.coolantC?.let { ObdChip(tr("Engine"), Fmt.temp(it, celsius), if (it > 105) Accent.danger else c.textPrimary) }
        car.batteryV?.let { ObdChip(tr("Battery"), "%.1f V".format(it), if (it < 12.0) Accent.warning else c.textPrimary) }
    }
}

@Composable
private fun ObdChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = OhmTheme.type.bodyStrong, color = color)
        Caption(label)
    }
}

@Composable
private fun GearStrip(gear: Gear?, interactive: Boolean, onSelect: (Gear) -> Unit) {
    val c = OhmTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (g in Gear.entries) {
            val sel = g == gear
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    .then(if (interactive) Modifier.clickable { onSelect(g) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(g.name, style = OhmTheme.type.title.copy(fontSize = 18.sp), color = if (sel) c.textPrimary else c.textHint.copy(alpha = if (gear == null) 0.4f else 1f))
            }
        }
    }
}

@Composable
private fun SpeedLimitSign(limit: Int, over: Boolean) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(Color.White).border(5.dp, Accent.danger, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$limit", style = OhmTheme.type.bodyStrong.copy(fontSize = 16.sp), color = if (over) Accent.danger else Color(0xFF171717))
    }
}

@Composable
private fun CarQuickButtons(modifier: Modifier, demo: Boolean) {
    val car by Ohm.car.state.collectAsState()
    val parked = car.gear == Gear.P || car.gear == null
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val locked = car.locked ?: true
        QuickChip(if (locked) tr("Locked") else tr("Unlocked"), if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, enabled = demo) { Ohm.car.demo?.toggleLock() }
        QuickChip(if (car.doors.trunk) tr("Close trunk") else tr("Trunk"), null, active = car.doors.trunk, enabled = demo && parked) { Ohm.car.demo?.toggleTrunk() }
        QuickChip(if (car.doors.hood) tr("Close hood") else tr("Hood"), null, active = car.doors.hood, enabled = demo && parked) { Ohm.car.demo?.toggleHood() }
        if (demo) QuickChip(tr("Doors"), null, active = car.doors.fl || car.doors.fr, enabled = parked) {
            Ohm.car.demo?.toggleDoor("fl"); Ohm.car.demo?.toggleDoor("fr")
        }
    }
}

@Composable
private fun QuickChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = OhmTheme.colors
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (active) Accent.accent.copy(alpha = 0.18f) else c.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = c.icon, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = OhmTheme.type.label, color = if (active) Accent.accent else c.textPrimary.copy(alpha = if (enabled) 1f else 0.6f), maxLines = 1)
    }
}

// ============================================================ notifications

@Composable
fun NotificationStack(modifier: Modifier) {
    val list by Ohm.banners.list.collectAsState()
    val volAt by Ohm.banners.volumeShownAt.collectAsState()
    var showVol by remember { mutableStateOf(false) }
    LaunchedEffect(volAt) { if (volAt > 0) { showVol = true; delay(2000); showVol = false } }
    Column(modifier.animateContentSize(tween(Motion.dur(200))), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in list) key(b.id) { BannerCard(b) }
        AnimatedVisibility(showVol, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { VolumeBanner() }
    }
}

@Composable
private fun VolumeBanner() {
    val c = OhmTheme.colors
    val v by Ohm.system.volume.collectAsState()
    val muted by Ohm.system.muted.collectAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (muted || v == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null, tint = c.icon, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceElevated)) {
            Box(Modifier.fillMaxWidth(if (muted) 0f else v).height(6.dp).background(Accent.accent))
        }
        Spacer(Modifier.width(12.dp))
        Text("${(v * 100).toInt()}", style = OhmTheme.type.label, color = c.textSecondary)
    }
}

@Composable
private fun BannerCard(b: Banner) {
    val c = OhmTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (b.kind == "call") LaunchedEffect(b.id) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val icon = when (b.kind) {
        "call" -> Icons.Rounded.Call; "message" -> Icons.Rounded.Sms; "offline" -> Icons.Rounded.WifiOff
        else -> Icons.Rounded.Info
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (b.kind == "call") Accent.success.copy(alpha = 0.18f) else c.surface), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (b.kind == "call") Accent.success else c.icon, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = OhmTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (b.kind == "call" && b.text == tr("Ongoing call")) {
                    val s = ((now - b.startedAt) / 1000).coerceAtLeast(0)
                    "${b.text} · %d:%02d".format(s / 60, s % 60)
                } else b.text
                Text(sub, style = OhmTheme.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (b.actions.isEmpty() && b.kind != "offline") IconBtn(Icons.Rounded.Close, tr("Dismiss"), size = 36.dp, iconSize = 18.dp) { Ohm.banners.dismiss(b.id) }
        }
        if (b.actions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in b.actions) {
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(when { a.danger -> Accent.danger; a.primary -> Accent.success; else -> c.surface })
                            .clickable { a.onClick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(a.label, style = OhmTheme.type.label, color = if (a.danger || a.primary) Color.White else c.textPrimary)
                    }
                }
            }
        }
    }
}
