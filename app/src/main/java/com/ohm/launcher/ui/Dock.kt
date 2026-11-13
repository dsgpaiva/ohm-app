package com.ohm.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.VoiceOverOff
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ohm.launcher.Ohm
import com.ohm.launcher.apps.AppInfo
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.OhmTheme

/** Bottom bar: car · pinned apps · drawer · recents · volume (SPEC.md §5.5). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dock(modifier: Modifier) {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    val apps by Ohm.apps.apps.collectAsState()
    val dock by Ohm.prefs.dockApps.state.collectAsState()
    val recents by Ohm.prefs.recentApps.state.collectAsState()
    val vol by Ohm.system.volume.collectAsState()
    val muted by Ohm.system.muted.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }

    Row(
        modifier.background(c.bg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockButton(Icons.Rounded.DirectionsCar, tr("Car"), selected = ui.sheet == Sheet.SETTINGS) { ui.toggle(Sheet.SETTINGS) }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.dp).height(36.dp).background(c.divider))
        Spacer(Modifier.width(8.dp))

        // Pinned apps are start-aligned so contextual buttons (end) never push them around.
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
            for (pkg in dock) {
                val a = apps.firstOrNull { it.pkg == pkg } ?: continue
                Box {
                    AppIcon(a, 48, Modifier.padding(horizontal = 6.dp).combinedClickable(onClick = { editing = null; Ohm.apps.launch(a) }, onLongClick = { editing = a.pkg }))
                    if (editing == a.pkg) DockEditMenu(a) { editing = null }
                }
            }
            DockButton(Icons.Rounded.Apps, tr("All apps"), selected = ui.sheet == Sheet.DRAWER) { ui.toggle(Sheet.DRAWER) }
            val rec = recents.filter { it !in dock }.mapNotNull { r -> apps.firstOrNull { it.pkg == r } }.take(3)
            if (rec.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(1.dp).height(28.dp).background(c.divider))
                Spacer(Modifier.width(4.dp))
                for (a in rec) AppIcon(a, 38, Modifier.padding(horizontal = 6.dp).clickable { Ohm.apps.launch(a) })
            }
            Spacer(Modifier.weight(1f))
            ContextualActions()
        }

        // volume
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Rounded.ChevronLeft, tr("Volume down"), size = 48.dp) { Ohm.system.step(false) }
            Box(Modifier.size(52.dp).clip(CircleShape).clickable { Ohm.system.toggleMute() }, contentAlignment = Alignment.Center) {
                Icon(if (muted || vol == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, tr("Mute"), tint = if (muted) Accent.danger else c.icon, modifier = Modifier.size(28.dp))
            }
            IconBtn(Icons.Rounded.ChevronRight, tr("Volume up"), size = 48.dp) { Ohm.system.step(true) }
        }
    }
}

/**
 * Context-dependent buttons in a fixed slot just left of the volume (SPEC-ideas §2.4):
 * navigation → end route / voice; call → mute / end; reverse → hide camera view.
 */
@Composable
private fun ContextualActions() {
    val enabled by Ohm.prefs.contextualDock.state.collectAsState()
    val nav by Ohm.nav.state.collectAsState()
    val voice by Ohm.prefs.voice.state.collectAsState()
    val banners by Ohm.banners.list.collectAsState()
    val reverse by Ohm.reverse.overlay.collectAsState()
    if (!enabled) return
    val call = banners.firstOrNull { it.kind == "call" }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        androidx.compose.animation.AnimatedVisibility(reverse) {
            ContextChip(tr("Hide camera"), Icons.Rounded.VisibilityOff) { Ohm.reverse.hideOverlay() }
        }
        androidx.compose.animation.AnimatedVisibility(call != null && !reverse) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextChip(tr("Mute"), Icons.Rounded.MicOff) {}
                ContextChip(tr("End call"), Icons.Rounded.CallEnd, danger = true) { call?.let { Ohm.banners.dismiss(it.id) } }
            }
        }
        androidx.compose.animation.AnimatedVisibility(nav.navigating && call == null && !reverse) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextChip(if (voice) tr("Voice on") else tr("Voice off"), if (voice) Icons.Rounded.RecordVoiceOver else Icons.Rounded.VoiceOverOff) { Ohm.prefs.voice.set(!voice) }
                ContextChip(tr("End route"), Icons.Rounded.Close, danger = true) { Ohm.nav.stop() }
            }
        }
    }
}

@Composable
private fun ContextChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val c = OhmTheme.colors
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (danger) Accent.danger else c.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) Color.White else c.icon, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = OhmTheme.type.label, color = if (danger) Color.White else c.textPrimary, maxLines = 1)
    }
}

@Composable
private fun DockEditMenu(a: AppInfo, onDone: () -> Unit) {
    val c = OhmTheme.colors
    androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter, offset = androidx.compose.ui.unit.IntOffset(0, -200), onDismissRequest = onDone) {
        OhmCard(radius = 14.dp) {
            Row(Modifier.padding(6.dp)) {
                IconBtn(Icons.Rounded.ChevronLeft, tr("Move left")) { Ohm.apps.move(a.pkg, -1) }
                IconBtn(Icons.Rounded.PushPin, tr("Unpin"), tint = Accent.danger) { Ohm.apps.unpin(a.pkg); onDone() }
                IconBtn(Icons.Rounded.ChevronRight, tr("Move right")) { Ohm.apps.move(a.pkg, 1) }
            }
        }
    }
    @Suppress("unused") val x = c
}

@Composable
private fun DockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, selected: Boolean, onClick: () -> Unit) {
    val c = OhmTheme.colors
    IconBtn(icon, cd, size = 56.dp, iconSize = 30.dp, tint = if (selected) Accent.accent else c.icon,
        background = if (selected) Accent.accent.copy(alpha = 0.12f) else Color.Transparent, onClick = onClick)
}

@Composable
fun AppIcon(a: AppInfo, sizeDp: Int, modifier: Modifier = Modifier) {
    val c = OhmTheme.colors
    val driving by Ohm.driving.active.collectAsState()
    val blocked = driving && Ohm.driving.isBlocked(a.pkg)
    Box(modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 4).dp)).then(if (blocked) Modifier.alpha(0.3f) else Modifier), contentAlignment = Alignment.Center) {
        val ic = a.icon
        if (ic != null) Image(ic, a.label, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(c.surfaceElevated), contentAlignment = Alignment.Center) {
            Text(a.label.take(1), style = OhmTheme.type.title, color = c.textPrimary)
        }
    }
}

/** All apps, with search and an edit mode to pin / hide (SPEC.md §5.6). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer() {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    val apps by Ohm.apps.apps.collectAsState()
    val hidden by Ohm.prefs.hiddenApps.state.collectAsState()
    val dock by Ohm.prefs.dockApps.state.collectAsState()
    var q by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf(false) }
    val list = apps.filter { it.pkg !in hidden && (q.isBlank() || it.label.contains(q, true)) }

    Column(Modifier.fillMaxSize()) {
        SheetHeader(tr("Apps"), onClose = { ui.sheet = Sheet.NONE }) {
            Pill(if (edit) tr("Done") else tr("Edit"), edit, icon = Icons.Rounded.Edit) { edit = !edit }
            Spacer(Modifier.width(8.dp))
        }
        OhmTextField(q, { q = it }, tr("Search apps"), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), leading = Icons.Rounded.Search)
        if (edit) Caption(tr("Tap the pin to add or remove from the dock, the eye to hide"), Modifier.padding(horizontal = 24.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(112.dp), Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.pkg }) { a ->
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { if (!edit) { ui.sheet = Sheet.NONE; Ohm.apps.launch(a) } }, onLongClick = { edit = true })
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        AppIcon(a, 60)
                        if (edit) Row(Modifier.align(Alignment.TopEnd).padding(start = 40.dp)) {}
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(a.label, style = OhmTheme.type.caption, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                    if (edit) Row {
                        val pinned = a.pkg in dock
                        IconBtn(Icons.Rounded.PushPin, if (pinned) tr("Unpin") else tr("Pin to dock"), size = 40.dp, iconSize = 18.dp, tint = if (pinned) Accent.accent else c.iconMuted) {
                            if (pinned) Ohm.apps.unpin(a.pkg) else Ohm.apps.pin(a.pkg)
                        }
                        IconBtn(Icons.Rounded.VisibilityOff, tr("Hide"), size = 40.dp, iconSize = 18.dp, tint = c.iconMuted) { Ohm.apps.setHidden(a.pkg, true) }
                    }
                }
            }
        }
        if (apps.isEmpty()) Box(Modifier.fillMaxWidth().fillMaxHeight(0.3f), contentAlignment = Alignment.Center) { Caption(tr("Loading apps…")) }
    }
}
