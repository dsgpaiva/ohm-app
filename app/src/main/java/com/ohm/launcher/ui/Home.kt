package com.ohm.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ohm.launcher.Ohm
import com.ohm.launcher.car.Gear
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.OhmTheme
import com.ohm.launcher.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

enum class Sheet { NONE, DRAWER, NOW_PLAYING, SETTINGS, WEATHER, QUICK, SEARCH, SPOTIFY }

@Stable
class UiState {
    var sheet by mutableStateOf(Sheet.NONE)
    var settingsTab by mutableStateOf(0)
    var cleanMode by mutableStateOf(false)
    var follow by mutableStateOf(true)
    var searchQuery by mutableStateOf("")
    fun toggle(s: Sheet) { sheet = if (sheet == s) Sheet.NONE else s }
}

val LocalUi = staticCompositionLocalOf { UiState() }

@Composable
fun Root(slowFrames: StateFlow<Boolean>) {
    val lang by Ohm.prefs.language.state.collectAsState()
    val dark by Ohm.dayNight.dark.collectAsState()
    val lp by Ohm.prefs.lowPower.state.collectAsState()
    val slow by slowFrames.collectAsState()
    val lowPower = when (lp) { "on" -> true; "off" -> false; else -> slow }
    val ui = remember { UiState() }
    LaunchedEffect(ui) { Ohm.apps.openSpotifyInside = { ui.sheet = Sheet.SPOTIFY } }
    com.ohm.launcher.ui.theme.OhmTheme(dark = dark, lowPower = lowPower) {
        ProvideLang(lang) {
            val onboarded by Ohm.prefs.onboardingDone.state.collectAsState()
            CompositionLocalProvider(LocalUi provides ui) {
                if (onboarded) Home() else Onboarding { Ohm.prefs.onboardingDone.set(true) }
            }
        }
    }
}

@Composable
fun Home() {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    val rhd by Ohm.prefs.rhd.state.collectAsState()
    val savedFraction by Ohm.prefs.panelFraction.state.collectAsState()
    val layout by Ohm.prefs.layout.state.collectAsState()
    var fraction by remember(savedFraction, layout) { mutableFloatStateOf(if (layout == "map") 0.26f else savedFraction) }
    val reverse by Ohm.reverse.overlay.collectAsState()

    BackHandler(enabled = ui.sheet != Sheet.NONE) { ui.sheet = Sheet.NONE }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val totalW = maxWidth
        val density = LocalDensity.current
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val panel: @Composable () -> Unit = {
                    CarPanel(Modifier.width(totalW * fraction).fillMaxHeight())
                }
                val right: @Composable () -> Unit = {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        RightArea()
                        // Drag strip on the map's inner edge resizes the car panel (28–45 %).
                        Box(
                            Modifier
                                .align(if (rhd) Alignment.CenterEnd else Alignment.CenterStart)
                                .width(22.dp).fillMaxHeight(0.5f)
                                .pointerInput(rhd) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = { if (layout != "map") Ohm.prefs.panelFraction.set(fraction) },
                                    ) { _, dx ->
                                        val dxDp = with(density) { dx.toDp() }
                                        fraction = (fraction + (if (rhd) -1 else 1) * (dxDp / totalW)).coerceIn(if (layout == "map") 0.22f else 0.28f, 0.45f)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.width(5.dp).height(56.dp).clip(RoundedCornerShape(3.dp)).background(c.textSecondary.copy(alpha = 0.55f)))
                        }
                    }
                }
                if (rhd) { right(); panel() } else { panel(); right() }
            }
            Dock(Modifier.fillMaxWidth().height(76.dp))
        }
        if (ui.cleanMode) CleanModeOverlay { ui.cleanMode = false }
    }
    LaunchedEffect(reverse) { if (reverse) ui.sheet = Sheet.NONE }
}

/** Map + everything floating on it. */
@Composable
private fun RightArea() {
    val reverse by Ohm.reverse.overlay.collectAsState()
    val nav by Ohm.nav.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        MapPanel(Modifier.fillMaxSize())
        NavOverlays(nav)
        StatusBar(Modifier.align(Alignment.TopCenter).fillMaxWidth())
        if (!nav.navigating) SearchEntry(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 52.dp))
        MapControls(Modifier.align(Alignment.BottomEnd).padding(16.dp))

        SheetHost()

        AnimatedVisibility(reverse, enter = fadeIn(tween(Motion.dur(200))), exit = fadeOut(tween(Motion.dur(200)))) {
            ReverseCamera()
        }
    }
}

@Composable
private fun SheetHost() {
    val ui = LocalUi.current
    val c = OhmTheme.colors
    // dim the map behind cards; tap outside closes
    AnimatedVisibility(ui.sheet != Sheet.NONE && ui.sheet != Sheet.QUICK && ui.sheet != Sheet.WEATHER, enter = fadeIn(tween(Motion.dur(150))), exit = fadeOut(tween(Motion.dur(150)))) {
        Box(Modifier.fillMaxSize().background(c.overlay).clickable(interactionSource = noRipple(), indication = null) { ui.sheet = Sheet.NONE })
    }
    for (s in listOf(Sheet.DRAWER, Sheet.NOW_PLAYING, Sheet.SETTINGS, Sheet.SEARCH, Sheet.SPOTIFY)) {
        AnimatedVisibility(
            ui.sheet == s,
            enter = slideInVertically(tween(Motion.dur(220))) { it / 3 } + fadeIn(tween(Motion.dur(220))),
            exit = slideOutVertically(tween(Motion.dur(180))) { it / 3 } + fadeOut(tween(Motion.dur(150))),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp)) {
                OhmCard(Modifier.fillMaxSize()) {
                    when (s) {
                        Sheet.DRAWER -> AppDrawer()
                        Sheet.NOW_PLAYING -> NowPlayingSheet()
                        Sheet.SETTINGS -> SettingsSheet()
                        Sheet.SEARCH -> SearchSheet()
                        Sheet.SPOTIFY -> com.ohm.launcher.ui.spotify.SpotifySheet()
                        else -> {}
                    }
                }
            }
        }
    }
    // small popovers anchored under the status bar
    AnimatedVisibility(ui.sheet == Sheet.WEATHER, enter = fadeIn(tween(Motion.dur(150))) + slideInVertically(tween(Motion.dur(200))) { -it / 8 }, exit = fadeOut(tween(Motion.dur(120)))) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = noRipple(), indication = null) { ui.sheet = Sheet.NONE }) {
            WeatherPopup(Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
        }
    }
    AnimatedVisibility(ui.sheet == Sheet.QUICK, enter = fadeIn(tween(Motion.dur(150))) + slideInVertically(tween(Motion.dur(200))) { -it / 8 }, exit = fadeOut(tween(Motion.dur(120)))) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = noRipple(), indication = null) { ui.sheet = Sheet.NONE }) {
            QuickSettings(Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
        }
    }
}

/** Demo rear-camera view with parking guidelines (real units: launch the camera app). */
@Composable
private fun ReverseCamera() {
    val c = OhmTheme.colors
    val src by Ohm.car.sourceId.collectAsState()
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B3035), Color(0xFF4B4F52), Color(0xFF6A6560)))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            fun guide(t0: Float, t1: Float, col: Color) {
                val p = Path()
                val l0 = Offset(w * (0.18f + 0.22f * t0), h * (1f - 0.62f * t0)); val l1 = Offset(w * (0.18f + 0.22f * t1), h * (1f - 0.62f * t1))
                val r0 = Offset(w - l0.x, l0.y); val r1 = Offset(w - l1.x, l1.y)
                p.moveTo(l0.x, l0.y); p.lineTo(l1.x, l1.y); p.lineTo(l1.x + w * 0.03f, l1.y)
                p.moveTo(r0.x, r0.y); p.lineTo(r1.x, r1.y); p.lineTo(r1.x - w * 0.03f, r1.y)
                drawPath(p, col, style = Stroke(width = 6.dp.toPx()))
            }
            guide(0f, 0.33f, Accent.danger)
            guide(0.33f, 0.66f, Accent.warning)
            guide(0.66f, 1f, Accent.success)
        }
        Row(Modifier.align(Alignment.TopStart).padding(20.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Videocam, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Rear camera") + if (src == "demo") " · " + tr("demo, no video feed") else " · " + tr("choose a camera app in Controls › Vehicle"), style = OhmTheme.type.label, color = Color.White)
        }
        Text(tr("Check surroundings for safety"), style = OhmTheme.type.caption, color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** Touch lock for wiping the screen; hold 3 s to exit. */
@Composable
private fun CleanModeOverlay(onExit: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(holding) {
        progress = 0f
        while (holding) {
            delay(30); progress += 0.01f
            if (progress >= 1f) { onExit(); break }
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(84.dp), color = Accent.accent, trackColor = Color.White.copy(alpha = 0.1f), strokeWidth = 4.dp)
                Icon(Icons.Rounded.CleaningServices, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(tr("Screen cleaning mode"), style = OhmTheme.type.title, color = Color.White)
            Text(tr("Press and hold anywhere for 3 seconds to exit"), style = OhmTheme.type.body, color = Color.White.copy(alpha = 0.6f))
        }
    }
}
