package com.ohm.launcher.ui

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohm.launcher.MainActivity
import com.ohm.launcher.Ohm
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.OhmTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun weatherIcon(code: Int, isDay: Boolean = true): ImageVector = when (code) {
    0 -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.Bedtime
    1, 2 -> Icons.Rounded.WbCloudy
    3, 45, 48 -> Icons.Rounded.Cloud
    in 51..67, in 80..82 -> Icons.Rounded.WaterDrop
    in 71..77, 85, 86 -> Icons.Rounded.AcUnit
    in 95..99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
    45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"
    in 80..82 -> "Showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"
    else -> "—"
}

/** Top bar over the map: lock · clock · weather · place · airbag · connectivity. */
@Composable
fun StatusBar(modifier: Modifier) {
    val ui = LocalUi.current
    val c = OhmTheme.colors
    val car by Ohm.car.state.collectAsState()
    val wx by Ohm.weather.state.collectAsState()
    val online by Ohm.system.online.collectAsState()
    val wifi by Ohm.system.wifi.collectAsState()
    val celsius by Ohm.prefs.tempC.state.collectAsState()
    val showWeather by Ohm.prefs.showWeather.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
    val time = SimpleDateFormat("HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(now))
    val textCol = c.textPrimary

    Box(modifier.height(44.dp).background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.85f), c.bg.copy(alpha = 0f))))) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val locked = car.locked
            if (locked != null) Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = c.icon, modifier = Modifier.size(16.dp))
            Text(time, style = OhmTheme.type.bodyStrong.copy(fontSize = 16.sp), color = textCol,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ui.toggle(Sheet.QUICK) }.padding(horizontal = 6.dp, vertical = 4.dp))
            if (showWeather) Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { ui.toggle(Sheet.WEATHER) }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val w = wx
                if (w != null) {
                    Icon(weatherIcon(w.code, w.isDay), null, tint = c.icon, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${com.ohm.launcher.core.Fmt.temp(w.tempC, celsius)}", style = OhmTheme.type.bodyStrong, color = textCol)
                } else {
                    val t = car.outsideTempC
                    Text(if (t != null) com.ohm.launcher.core.Fmt.temp(t.toDouble(), celsius) else "--°", style = OhmTheme.type.bodyStrong, color = textCol)
                }
            }
            if (showWeather) wx?.place?.let { Text(it, style = OhmTheme.type.label, color = c.textSecondary, maxLines = 1) }
            if (car.passengerAirbagOff != null) {
                Row(Modifier.clip(RoundedCornerShape(4.dp)).background(c.surface).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(tr("PASSENGER AIRBAG") + " " + if (car.passengerAirbagOff == true) tr("OFF") else tr("ON"), style = OhmTheme.type.caption.copy(fontSize = 9.sp), color = if (car.passengerAirbagOff == true) Accent.warning else c.textSecondary)
                }
            }
            Icon(if (!online) Icons.Rounded.WifiOff else if (wifi) Icons.Rounded.Wifi else Icons.Rounded.Air, null, tint = if (online) c.iconMuted else Accent.danger, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun WeatherPopup(modifier: Modifier) {
    val c = OhmTheme.colors
    val wx by Ohm.weather.state.collectAsState()
    val metric by Ohm.prefs.distanceKm.state.collectAsState()
    val celsius by Ohm.prefs.tempC.state.collectAsState()
    OhmCard(modifier.width(440.dp).clickable(interactionSource = noRipple(), indication = null) {}) {
        val w = wx
        if (w == null) {
            Text(tr("Weather unavailable — waiting for connection"), style = OhmTheme.type.body, color = c.textSecondary, modifier = Modifier.padding(24.dp))
            return@OhmCard
        }
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(w.code, w.isDay), null, tint = Accent.accent, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${com.ohm.launcher.core.Fmt.temp(w.tempC, celsius)}  ${tr(weatherLabel(w.code))}", style = OhmTheme.type.title, color = c.textPrimary)
                    Text(w.place ?: "", style = OhmTheme.type.label, color = c.textSecondary)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (h in w.hourly) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Caption(h.hour)
                    Icon(weatherIcon(h.code), null, tint = c.icon, modifier = Modifier.size(20.dp).padding(vertical = 2.dp))
                    Text("${com.ohm.launcher.core.Fmt.temp(h.tempC, celsius)}", style = OhmTheme.type.label, color = c.textPrimary)
                    Caption("${h.rainPct}%", color = Accent.accentSoft)
                }
            }
            Spacer(Modifier.height(12.dp))
            val lang = LocalLang.current
            for (d in w.daily) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val day = runCatching { SimpleDateFormat("EEE", Locale(lang)).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.day)!!) }.getOrDefault(d.day)
                Text(day.replaceFirstChar { it.uppercase() }, style = OhmTheme.type.label, color = c.textPrimary, modifier = Modifier.width(56.dp))
                Icon(weatherIcon(d.code), null, tint = c.icon, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Caption("${d.rainPct}%", Modifier.width(40.dp), color = Accent.accentSoft)
                Spacer(Modifier.weight(1f))
                Text("${com.ohm.launcher.core.Fmt.temp(d.min, celsius)} / ${com.ohm.launcher.core.Fmt.temp(d.max, celsius)}", style = OhmTheme.type.label, color = c.textPrimary)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Caption(tr("Humidity") + " ${w.humidity}%")
                Caption(tr("Wind") + " " + if (metric) "${w.windKmh.roundToInt()} km/h" else "${(w.windKmh / 1.609).roundToInt()} mph")
                Caption("UV ${w.daily.firstOrNull()?.uv?.roundToInt() ?: "-"}")
                w.aqi?.let { Caption(tr("Air quality") + " $it") }
            }
            Caption(tr("Open-Meteo · updated") + " " + SimpleDateFormat("HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(w.fetchedAt)), Modifier.padding(top = 8.dp), color = c.textHint)
        }
    }
}

@Composable
fun QuickSettings(modifier: Modifier) {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val mode by Ohm.prefs.themeMode.state.collectAsState()
    var bright by remember { mutableFloatStateOf(-1f) }
    OhmCard(modifier.width(420.dp).clickable(interactionSource = noRipple(), indication = null) {}) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("Quick settings"), style = OhmTheme.type.title, color = c.textPrimary)
            Segmented(
                listOf("auto_lights" to tr("Auto"), "light" to tr("Light"), "dark" to tr("Dark")),
                if (mode == "auto_time") "auto_lights" else mode, { Ohm.prefs.themeMode.set(it) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Brightness6, null, tint = c.icon)
                Spacer(Modifier.width(12.dp))
                OhmSlider(if (bright < 0) 0.7f else bright, { bright = it.coerceAtLeast(0.05f); (ctx as? MainActivity)?.setBrightness(bright) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(tr("Wi-Fi"), false, icon = Icons.Rounded.Wifi) { Ohm.apps.openSettings(Settings.ACTION_WIFI_SETTINGS) }
                Pill(tr("Bluetooth"), false, icon = Icons.Rounded.Bluetooth) { Ohm.apps.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
                Pill(tr("Clean screen"), false, icon = Icons.Rounded.CleaningServices) { ui.sheet = Sheet.NONE; ui.cleanMode = true }
            }
        }
    }
    @Suppress("unused") val a: Activity? = null
}
