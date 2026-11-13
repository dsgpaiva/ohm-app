package com.ohm.launcher.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.ohm.launcher.R

/** Colour tokens — adapted from VoltaOS Theme.qml (see SPEC.md §6.2). */
@Immutable
data class OhmColors(
    val isDark: Boolean,
    val bg: Color,
    val panel: Color,
    val card: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceInput: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val border: Color,
    val divider: Color,
    val icon: Color,
    val iconMuted: Color,
    val overlay: Color,
)

val AccentChoices = listOf(
    "Blue" to Color(0xFF2E78FF),
    "Teal" to Color(0xFF14B8A6),
    "Green" to Color(0xFF25A55F),
    "Orange" to Color(0xFFF08A24),
    "Red" to Color(0xFFE5483B),
    "Purple" to Color(0xFF8B5CF6),
)

/** Accent colour is user-selectable; reading it inside composables recomposes on change. */
object Accent {
    private val current = androidx.compose.runtime.mutableStateOf(AccentChoices[0].second)
    fun select(i: Int) { current.value = AccentChoices.getOrElse(i) { AccentChoices[0] }.second }
    val accent: Color get() = current.value
    val accentDeep: Color get() = lerp(current.value, Color.Black, 0.18f)
    val accentSoft: Color get() = lerp(current.value, Color.White, 0.55f)
    val success = Color(0xFF25CB55)
    val warning = Color(0xFFFFC107)
    val danger = Color(0xFFED4E3B)
    val dangerDeep = Color(0xFFB74134)
}

val LightColors = OhmColors(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    panel = Color(0xFFF2F2F2),
    card = Color(0xFFFFFFFF),
    surface = Color(0xFFF0F0F0),
    surfaceElevated = Color(0xFFE0E0E0),
    surfaceInput = Color(0xFFF0F0F0),
    textPrimary = Color(0xFF171717),
    textSecondary = Color(0xFF757575),
    textHint = Color(0xFFA2A3A5),
    border = Color(0xFFD0D1D2),
    divider = Color(0xFFE0E0E0),
    icon = Color(0xFF414141),
    iconMuted = Color(0xFFA2A3A5),
    overlay = Color(0x33171717),
)

val DarkColors = OhmColors(
    isDark = true,
    bg = Color(0xFF171717),
    panel = Color(0xFF111111),
    card = Color(0xFF222222),
    surface = Color(0x14FFFFFF),
    surfaceElevated = Color(0x24FFFFFF),
    surfaceInput = Color(0x1AFFFFFF),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA2A3A5),
    textHint = Color(0xFF757575),
    border = Color(0x26FFFFFF),
    divider = Color(0x14FFFFFF),
    icon = Color(0xFFE0E0E0),
    iconMuted = Color(0xFF757575),
    overlay = Color(0x99171717),
)

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

@Immutable
data class OhmType(
    val speed: TextStyle,
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

private fun ts(size: Int, w: FontWeight) = TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = w)

val OhmTypography = OhmType(
    speed = ts(64, FontWeight.Light).copy(textAlign = TextAlign.Center, letterSpacing = (-1).sp),
    display = ts(32, FontWeight.Light),
    title = ts(20, FontWeight.Medium),
    body = ts(15, FontWeight.Normal),
    bodyStrong = ts(15, FontWeight.Medium),
    label = ts(13, FontWeight.Medium),
    caption = ts(11, FontWeight.Normal),
)

/** Animation preference: full | reduced (half length) | off. */
object Motion {
    private val scale = androidx.compose.runtime.mutableFloatStateOf(1f)
    fun set(mode: String) { scale.floatValue = when (mode) { "off" -> 0f; "reduced" -> 0.5f; else -> 1f } }
    fun dur(ms: Int): Int = (ms * scale.floatValue).toInt()
    val off: Boolean get() = scale.floatValue == 0f
}

val LocalOhmColors = staticCompositionLocalOf { DarkColors }
val LocalLowPower = staticCompositionLocalOf { false }

object OhmTheme {
    val colors: OhmColors @Composable get() = LocalOhmColors.current
    val type: OhmType get() = OhmTypography
}

@Composable
private fun anim(c: Color) = animateColorAsState(c, tween(Motion.dur(300)), label = "theme").value

/** Provides colours, cross-fading between light and dark (300 ms). */
@Composable
fun OhmTheme(dark: Boolean, lowPower: Boolean, content: @Composable () -> Unit) {
    val t = if (dark) DarkColors else LightColors
    val animated = OhmColors(
        isDark = dark,
        bg = anim(t.bg), panel = anim(t.panel), card = anim(t.card),
        surface = anim(t.surface), surfaceElevated = anim(t.surfaceElevated), surfaceInput = anim(t.surfaceInput),
        textPrimary = anim(t.textPrimary), textSecondary = anim(t.textSecondary), textHint = anim(t.textHint),
        border = anim(t.border), divider = anim(t.divider), icon = anim(t.icon), iconMuted = anim(t.iconMuted),
        overlay = anim(t.overlay),
    )
    CompositionLocalProvider(LocalOhmColors provides animated, LocalLowPower provides lowPower, content = content)
}
