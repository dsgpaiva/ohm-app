package com.ohm.launcher.ui

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ohm.launcher.Ohm
import com.ohm.launcher.media.NowPlaying
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.LocalLowPower
import com.ohm.launcher.ui.theme.OhmTheme
import kotlinx.coroutines.delay

private fun fmtTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@Composable
private fun livePos(np: NowPlaying?): Long {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np?.playing, np?.title) { while (np?.playing == true) { t = System.nanoTime(); delay(500) } }
    @Suppress("UNUSED_EXPRESSION") t
    return np?.livePosition() ?: 0L
}

/** Generated cover (gradient) when the app gives no artwork. */
@Composable
fun Cover(np: NowPlaying, modifier: Modifier) {
    val art = np.art
    Box(modifier) {
        if (art == null && np.artUrl != null) coil3.compose.AsyncImage(np.artUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (art != null) Image(remember(art) { art.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            val h1 = ((np.artSeed and 0xFFFF) % 360).toFloat()
            val c1 = Color.hsv(h1, 0.55f, 0.75f); val c2 = Color.hsv((h1 + 50) % 360, 0.65f, 0.35f)
            Canvas(Modifier.fillMaxSize()) { drawRect(Brush.linearGradient(listOf(c1, c2), Offset.Zero, Offset(size.width, size.height))) }
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center).fillMaxSize(0.4f))
        }
    }
}

@Composable
fun MusicWidget(modifier: Modifier) {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    val np by Ohm.media.nowPlaying.collectAsState()
    val access by Ohm.media.hasAccess.collectAsState()
    val spotifyAuth by Ohm.spotify.auth.state.collectAsState()
    val spotifyOn = spotifyAuth is com.ohm.launcher.spotify.AuthState.SignedIn
    val pos = livePos(np)

    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.card).padding(12.dp)) {
        val n = np
        if (n == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = c.iconMuted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Nothing playing"), style = OhmTheme.type.bodyStrong, color = c.textPrimary)
                    if (!access) Caption(tr("Allow notification access to control music apps"))
                }
                if (!access) Pill(tr("Allow"), true) { Ohm.media.openAccessSettings() }
                if (spotifyOn) Pill("Spotify", false) { ui.sheet = Sheet.SPOTIFY }
            }
            return@Column
        }
        val spotifyHere = spotifyOn && (n.origin == "spotify" || n.packageName == com.ohm.launcher.spotify.Spotify.SPOTIFY_PKG)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(interactionSource = noRipple(), indication = null) { ui.toggle(if (spotifyHere) Sheet.SPOTIFY else Sheet.NOW_PLAYING) }) {
            Cover(n, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title.ifEmpty { "—" }, style = OhmTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(n.artist, n.appLabel).filter { it.isNotEmpty() }.joinToString(" · "), style = OhmTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (n.canLike) IconBtn(if (n.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, tr("Like"), size = 40.dp, iconSize = 20.dp, tint = if (n.liked) Accent.danger else c.icon) { Ohm.media.like() }
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(n, pos)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            when (n.kind) {
                "radio" -> {
                    IconBtn(Icons.Rounded.SkipPrevious, tr("Previous"), size = 44.dp) { Ohm.media.prev() }
                    PlayButton(n.playing)
                    IconBtn(Icons.Rounded.Stop, tr("Stop"), size = 44.dp) { Ohm.media.stop() }
                }
                "podcast" -> {
                    IconBtn(Icons.Rounded.Replay10, tr("Back 10 s"), size = 44.dp) { Ohm.media.seekBy(-10_000) }
                    PlayButton(n.playing)
                    IconBtn(Icons.Rounded.Forward10, tr("Forward 10 s"), size = 44.dp) { Ohm.media.seekBy(10_000) }
                }
                else -> {
                    IconBtn(Icons.Rounded.SkipPrevious, tr("Previous"), size = 44.dp) { Ohm.media.prev() }
                    PlayButton(n.playing)
                    IconBtn(Icons.Rounded.SkipNext, tr("Next"), size = 44.dp) { Ohm.media.next() }
                }
            }
            IconBtn(Icons.Rounded.Search, tr("Search music"), size = 44.dp, tint = c.iconMuted) {
                if (spotifyOn) { com.ohm.launcher.ui.spotify.SpNav.root(com.ohm.launcher.ui.spotify.SpPage.Search); ui.sheet = Sheet.SPOTIFY } else ui.sheet = Sheet.NOW_PLAYING
            }
            IconBtn(if (spotifyOn) Icons.Rounded.LibraryMusic else Icons.Rounded.QueueMusic, tr(if (spotifyOn) "Your Library" else "Queue"), size = 44.dp, tint = c.iconMuted) {
                if (spotifyOn) ui.toggle(Sheet.SPOTIFY) else ui.toggle(Sheet.NOW_PLAYING)
            }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean) {
    val c = OhmTheme.colors
    IconBtn(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) tr("Pause") else tr("Play"), size = 52.dp, iconSize = 32.dp, background = c.surface) { Ohm.media.playPause() }
}

@Composable
private fun ProgressBar(n: NowPlaying, pos: Long) {
    val c = OhmTheme.colors
    val frac = if (n.durationMs > 0) (pos.toFloat() / n.durationMs).coerceIn(0f, 1f) else 0f
    Column {
        Box(
            Modifier.fillMaxWidth().height(14.dp).pointerInput(n.durationMs) {
                detectTapGestures { o: Offset -> if (n.durationMs > 0) Ohm.media.seekTo((o.x / size.width * n.durationMs).toLong()) }
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceElevated)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(c.textPrimary))
            }
        }
        if (n.durationMs > 0) Row(Modifier.fillMaxWidth()) {
            Caption(fmtTime(pos)); Spacer(Modifier.weight(1f)); Caption(fmtTime(n.durationMs))
        }
    }
}

/** Big Now Playing + queue + search (opens on the right side). */
@Composable
fun NowPlayingSheet() {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val lowPower = LocalLowPower.current
    val np by Ohm.media.nowPlaying.collectAsState()
    var q by remember { mutableStateOf("") }
    val pos = livePos(np)
    val n = np

    Box(Modifier.fillMaxSize()) {
        if (n != null && !lowPower) {
            Cover(n, Modifier.fillMaxSize().blur(60.dp))
            Box(Modifier.fillMaxSize().background(c.card.copy(alpha = 0.82f)))
        }
        Column(Modifier.fillMaxSize()) {
            SheetHeader(tr("Now playing"), onClose = { ui.sheet = Sheet.NONE }) {
                IconBtn(Icons.Rounded.Equalizer, tr("Equalizer")) {
                    runCatching { ctx.startActivity(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Ohm.banners.info(trNow("Equalizer"), trNow("No equalizer app found on this unit")) }
                }
            }
            if (n == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("Nothing playing"), style = OhmTheme.type.title, color = c.textSecondary) }
                return@Column
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Cover(n, Modifier.weight(1f, fill = false).aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
                    Spacer(Modifier.height(16.dp))
                    Text(n.title, style = OhmTheme.type.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(n.artist, n.album).filter { it.isNotEmpty() }.joinToString(" · "), style = OhmTheme.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    ProgressBar(n, pos)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Icons.Rounded.Replay10, tr("Back 10 s")) { Ohm.media.seekBy(-10_000) }
                        IconBtn(Icons.Rounded.SkipPrevious, tr("Previous"), size = 56.dp, iconSize = 30.dp) { Ohm.media.prev() }
                        IconBtn(if (n.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, tr("Play"), size = 64.dp, iconSize = 38.dp, background = c.surface) { Ohm.media.playPause() }
                        IconBtn(Icons.Rounded.SkipNext, tr("Next"), size = 56.dp, iconSize = 30.dp) { Ohm.media.next() }
                        IconBtn(Icons.Rounded.Forward10, tr("Forward 10 s")) { Ohm.media.seekBy(10_000) }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    OhmTextField(q, { q = it }, tr("Search in") + " " + n.appLabel, Modifier.fillMaxWidth(), leading = Icons.Rounded.Search,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) { Ohm.media.playFromSearch(q); q = "" } }))
                    Spacer(Modifier.height(12.dp))
                    Caption(tr("Up next"), Modifier.padding(start = 4.dp, bottom = 6.dp))
                    if (n.queue.isEmpty()) Caption(tr("This app doesn't share its queue"), Modifier.padding(4.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(n.queue, key = { it.id }) { e ->
                            val active = e.id == n.activeQueueId
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (active) Accent.accent.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable { Ohm.media.jumpTo(e.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (active) { Icon(Icons.Rounded.GraphicEq, null, tint = Accent.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, style = OhmTheme.type.body, color = if (active) Accent.accent else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (e.subtitle.isNotEmpty()) Caption(e.subtitle)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
