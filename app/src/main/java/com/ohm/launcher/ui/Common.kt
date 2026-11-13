package com.ohm.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ohm.launcher.i18n.Strings
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.OhmTheme

val LocalLang = staticCompositionLocalOf { "en" }

/** Translate an English UI string (English text is the key — see i18n/Strings.kt). */
@Composable
fun tr(en: String): String = Strings.tr(LocalLang.current, en)

/** Non-composable variant for click handlers and coroutines. */
fun trNow(en: String): String = Strings.tr(com.ohm.launcher.Ohm.prefs.language.value, en)

@Composable
fun ProvideLang(lang: String, content: @Composable () -> Unit) = CompositionLocalProvider(LocalLang provides lang, content = content)

/** Floating card used over the map (Tesla-style sheet). */
@Composable
fun OhmCard(modifier: Modifier = Modifier, radius: Dp = 16.dp, content: @Composable () -> Unit) {
    val c = OhmTheme.colors
    Box(
        modifier
            .shadow(if (c.isDark) 0.dp else 10.dp, RoundedCornerShape(radius), clip = false)
            .clip(RoundedCornerShape(radius))
            .background(c.card)
            .then(if (c.isDark) Modifier.border(1.dp, c.border, RoundedCornerShape(radius)) else Modifier)
    ) { content() }
}

@Composable
fun noRipple() = remember { MutableInteractionSource() }

/** Round icon button, ≥ 48 dp touch target. */
@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    tint: Color = OhmTheme.colors.icon,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(iconSize))
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = OhmTheme.colors
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent.accent else c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) Color.White else c.icon, modifier = Modifier.size(18.dp))
        Text(text, style = OhmTheme.type.label, color = if (selected) Color.White else c.textPrimary, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = Accent.accent, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)) }
        Text(text, style = OhmTheme.type.bodyStrong, color = Color.White, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = OhmTheme.colors
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = c.icon, modifier = Modifier.size(20.dp)); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)) }
        Text(text, style = OhmTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1)
    }
}

@Composable
fun SheetHeader(title: String, onClose: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = OhmTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = OhmTheme.type.title, color = c.textPrimary, modifier = Modifier.weight(1f))
        trailing()
        IconBtn(Icons.Rounded.Close, tr("Close"), onClick = onClose)
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = OhmTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OhmTheme.type.body, color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = OhmTheme.type.caption, color = c.textSecondary)
        }
        trailing()
    }
}

@Composable
fun OhmSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = OhmTheme.colors
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Accent.accent,
            uncheckedThumbColor = c.textSecondary, uncheckedTrackColor = c.surfaceElevated, uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun OhmSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0) {
    val c = OhmTheme.colors
    Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = modifier,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent.accent, inactiveTrackColor = c.surfaceElevated),
    )
}

/** Segmented control (pill group). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = OhmTheme.colors
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((v, label) in options) {
            val sel = v == selected
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (sel) c.card else Color.Transparent)
                    .clickable { onSelect(v) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = OhmTheme.type.label, color = if (sel) c.textPrimary else c.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
fun OhmTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    singleLine: Boolean = true,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val c = OhmTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.surfaceInput).padding(horizontal = 14.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { Icon(leading, null, tint = c.iconMuted, modifier = Modifier.size(20.dp)); androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp)) }
        // Driving lockout: no typing while the car moves (saved places, recents, buttons still work).
        val locked by com.ohm.launcher.Ohm.driving.active.collectAsState()
        if (locked) {
            Text(tr("Typing is disabled while driving"), style = OhmTheme.type.body, color = c.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            return@Row
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = OhmTheme.type.body, color = c.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value, onChange, singleLine = singleLine,
                textStyle = OhmTheme.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(Accent.accent),
                keyboardActions = keyboardActions, keyboardOptions = keyboardOptions,
                modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = OhmTheme.colors.textSecondary, style: TextStyle = OhmTheme.type.caption) =
    Text(text, style = style, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
