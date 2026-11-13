package com.ohm.launcher.ui.spotify

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ohm.launcher.Ohm
import com.ohm.launcher.spotify.AuthState
import com.ohm.launcher.spotify.Page
import com.ohm.launcher.spotify.SpDevice
import com.ohm.launcher.spotify.SpItem
import com.ohm.launcher.spotify.SpotifyAuth
import com.ohm.launcher.spotify.parseItem
import com.ohm.launcher.ui.Caption
import com.ohm.launcher.ui.IconBtn
import com.ohm.launcher.ui.LocalUi
import com.ohm.launcher.ui.OhmSlider
import com.ohm.launcher.ui.OhmTextField
import com.ohm.launcher.ui.Pill
import com.ohm.launcher.ui.PrimaryButton
import com.ohm.launcher.ui.SecondaryButton
import com.ohm.launcher.ui.Sheet
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.OhmTheme
import com.ohm.launcher.ui.tr
import com.ohm.launcher.ui.trNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Spotify's brand green, used only for Spotify attribution / the logo dot. */
private val SpotifyGreen = Color(0xFF1ED760)

sealed class SpPage {
    data object Home : SpPage()
    data object Search : SpPage()
    data object Library : SpPage()
    data object Queue : SpPage()
    data object Lyrics : SpPage()
    data object Devices : SpPage()
    data class Detail(val item: SpItem) : SpPage()
}

/** Back-stack for the Spotify sheet (kept while the sheet is closed). */
object SpNav {
    val stack = mutableStateListOf<SpPage>(SpPage.Home)
    val top: SpPage get() = stack.last()
    fun go(p: SpPage) { if (top != p) stack.add(p) }
    fun root(p: SpPage) { stack.clear(); stack.add(p) }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); return true }
}

private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

// ============================================================ sheet

@Composable
fun SpotifySheet() {
    val auth by Ohm.spotify.auth.state.collectAsState()
    DisposableEffect(Unit) {
        Ohm.spotify.uiVisible = true
        Ohm.spotify.refreshDevices()
        onDispose { Ohm.spotify.uiVisible = false }
    }
    androidx.activity.compose.BackHandler(enabled = SpNav.stack.size > 1) { SpNav.back() }
    when (auth) {
        is AuthState.SignedIn -> SignedInLayout()
        else -> ConnectPage(auth)
    }
}

@Composable
private fun SignedInLayout() {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    val auth = Ohm.spotify.auth.state.collectAsState().value as? AuthState.SignedIn
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ---- left rail
            Column(Modifier.width(210.dp).fillMaxHeight().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 14.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(SpotifyGreen))
                    Spacer(Modifier.width(8.dp))
                    Text("Spotify", style = OhmTheme.type.title, color = c.textPrimary, modifier = Modifier.weight(1f))
                    IconBtn(Icons.Rounded.ArrowBack, tr("Close"), size = 36.dp, iconSize = 20.dp) { if (!SpNav.back()) ui.sheet = Sheet.NONE }
                }
                RailItem(Icons.Rounded.Home, "Home", SpNav.top == SpPage.Home) { SpNav.root(SpPage.Home) }
                RailItem(Icons.Rounded.Search, "Search", SpNav.top == SpPage.Search) { SpNav.root(SpPage.Search) }
                RailItem(Icons.Rounded.LibraryMusic, "Your Library", SpNav.top == SpPage.Library) { SpNav.root(SpPage.Library) }
                RailItem(Icons.Rounded.QueueMusic, "Queue", SpNav.top == SpPage.Queue) { SpNav.go(SpPage.Queue) }
                RailItem(Icons.Rounded.Lyrics, "Lyrics", SpNav.top == SpPage.Lyrics) { SpNav.go(SpPage.Lyrics) }
                RailItem(Icons.Rounded.SpeakerGroup, "Devices", SpNav.top == SpPage.Devices) { SpNav.go(SpPage.Devices) }
                Spacer(Modifier.weight(1f))
                if (auth != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                    Art(auth.avatar, 32.dp, circle = true)
                    Spacer(Modifier.width(10.dp))
                    Caption(auth.name)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (val p = SpNav.top) {
                    SpPage.Home -> HomePage()
                    SpPage.Search -> SearchPage()
                    SpPage.Library -> LibraryPage()
                    SpPage.Queue -> QueuePage()
                    SpPage.Lyrics -> LyricsPage()
                    SpPage.Devices -> DevicesPage()
                    is SpPage.Detail -> DetailPage(p.item)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        PlayerBar()
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OhmTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.surface else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) c.textPrimary else c.icon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(tr(label), style = OhmTheme.type.bodyStrong, color = if (selected) c.textPrimary else c.textSecondary)
    }
}

// ============================================================ shared bits

@Composable
fun Art(url: String?, size: Dp, circle: Boolean = false, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.MusicNote, liked: Boolean = false) {
    val c = OhmTheme.colors
    val shape = if (circle) CircleShape else RoundedCornerShape(size / 12)
    Box(modifier.size(size).clip(shape).background(if (liked) Accent.accent else c.surfaceElevated), contentAlignment = Alignment.Center) {
        if (liked) Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(size * 0.45f))
        else if (url == null) Icon(icon, null, tint = c.iconMuted, modifier = Modifier.size(size * 0.4f))
        else AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr(text), style = OhmTheme.type.title, color = OhmTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Square card (album / playlist / show) or round (artist). */
@Composable
private fun Card(item: SpItem, width: Dp = 150.dp) {
    val c = OhmTheme.colors
    val round = item.type == "artist"
    Column(Modifier.width(width).clip(RoundedCornerShape(12.dp)).clickable { open(item) }.padding(8.dp), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
        Art(item.image, width - 16.dp, circle = round, liked = item.type == "collection")
        Spacer(Modifier.height(8.dp))
        Text(item.name, style = OhmTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start)
        Text(if (item.type == "collection") tr("Playlist") else item.subtitle.replace("Playlist", trNow("Playlist")).replace("Podcast", trNow("Podcast")).replace("Artist", trNow("Artist")),
            style = OhmTheme.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun open(item: SpItem) = when (item.type) {
    "track", "episode" -> Ohm.spotify.playItem(item).let { }
    else -> SpNav.go(SpPage.Detail(item))
}

@Composable
private fun CardRow(title: String, items: List<SpItem>) {
    if (items.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) { items(items, key = { it.uri }) { Card(it) } }
}

/** A track / episode row with play-on-tap and a ⋯ menu. */
@Composable
private fun TrackRow(item: SpItem, index: Int?, showArt: Boolean, onPlay: () -> Unit) {
    val c = OhmTheme.colors
    val pb by Ohm.spotify.playback.collectAsState()
    val current = pb?.item?.uri == item.uri
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null && !showArt) Text("${index + 1}", style = OhmTheme.type.label, color = if (current) Accent.accent else c.textSecondary, modifier = Modifier.width(32.dp))
        if (showArt) { Art(item.image, 44.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(item.name, style = OhmTheme.type.body, color = if (current) Accent.accent else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.explicit) {
                    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(c.textSecondary).padding(horizontal = 4.dp)) { Text("E", style = OhmTheme.type.caption.copy(fontSize = 9.sp), color = c.card) }
                    Spacer(Modifier.width(6.dp))
                }
                Caption(item.subtitle)
            }
        }
        if (item.durationMs > 0) Caption(fmt(item.durationMs), Modifier.padding(horizontal = 8.dp))
        Box {
            IconBtn(Icons.Rounded.MoreVert, tr("More"), size = 40.dp, iconSize = 20.dp, tint = c.iconMuted) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(tr("Add to queue")) }, { menu = false; Ohm.spotify.addToQueue(item) }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) })
                DropdownMenuItem({ Text(tr("Save to Liked Songs") + " / " + tr("Remove")) }, { menu = false; Ohm.spotify.toggleLike(item.uri) }, leadingIcon = { Icon(Icons.Rounded.FavoriteBorder, null) })
                item.parentUri?.takeIf { item.type == "track" }?.let { au ->
                    DropdownMenuItem({ Text(tr("Go to album")) }, { menu = false; SpNav.go(SpPage.Detail(SpItem("album", au, au.substringAfterLast(':'), "", "", item.image))) })
                }
                item.artistUris.firstOrNull()?.let { ar ->
                    DropdownMenuItem({ Text(tr("Go to artist")) }, { menu = false; SpNav.go(SpPage.Detail(SpItem("artist", ar, ar.substringAfterLast(':'), item.subtitle.substringBefore(','), "Artist", null))) })
                }
                DropdownMenuItem({ Text(tr("Open in Spotify")) }, { menu = false; Ohm.spotify.openInSpotify(item.uri) }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
            }
        }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.size(28.dp), color = Accent.accent, strokeWidth = 3.dp)
}

@Composable
private fun Empty(text: String) = Text(tr(text), style = OhmTheme.type.body, color = OhmTheme.colors.textSecondary, modifier = Modifier.padding(24.dp))

/** Paged loader: keeps items, loads the next page when the list nears its end. */
private class Paged(private val load: suspend (offset: Int, cursor: String?) -> Page) {
    val items = mutableStateListOf<SpItem>()
    var loading by mutableStateOf(false)
    var more by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    private var offset = 0
    private var cursor: String? = null
    suspend fun next() {
        if (loading || !more) return
        loading = true
        try {
            val p = load(offset, cursor)
            items.addAll(p.items.filter { n -> items.none { it.uri == n.uri } })
            offset = p.nextOffset; cursor = p.cursor; more = p.next && p.items.isNotEmpty()
        } catch (e: Exception) { error = e.message; more = false }
        loading = false
    }
}

@Composable
private fun PagedList(paged: Paged, header: LazyListScope.() -> Unit = {}, row: @Composable (Int, SpItem) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(paged) { paged.next() }
    LaunchedEffect(state, paged) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged().filter { it >= state.layoutInfo.totalItemsCount - 6 }
            .collect { paged.next() }
    }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
        header()
        itemsIndexed(paged.items, key = { _, it -> it.uri }) { i, it -> row(i, it) }
        if (paged.loading) item { Loading() }
        paged.error?.let { e -> item { Empty(e) } }
        if (!paged.loading && paged.items.isEmpty() && paged.error == null && !paged.more) item { Empty("Nothing here yet") }
    }
}

// ============================================================ home

@Composable
private fun HomePage() {
    val c = OhmTheme.colors
    val home by Ohm.spotify.home.collectAsState()
    val auth = Ohm.spotify.auth.state.collectAsState().value as? AuthState.SignedIn
    LaunchedEffect(Unit) { Ohm.spotify.loadHome() }
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..18 -> "Good afternoon"; else -> "Good evening" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(greet) + (auth?.name?.let { ", ${it.substringBefore(' ')}" } ?: ""), style = OhmTheme.type.display.copy(fontSize = 26.sp), color = c.textPrimary, modifier = Modifier.weight(1f))
                IconBtn(Icons.Rounded.Refresh, tr("Refresh")) { Ohm.spotify.loadHome(force = true) }
            }
        }
        // Shortcut tiles (Liked Songs + recent contexts)
        item {
            val tiles = listOf(Ohm.spotify.likedSongsItem()) + home.jumpBackIn.take(7)
            BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val cols = if (maxWidth > 700.dp) 4 else 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { t -> Tile(t, Modifier.weight(1f)) }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (home.loading && !home.loaded) item { Loading() }
        item { CardRow("Jump back in", home.jumpBackIn) }
        item { CardRow("Your top artists", home.topArtists) }
        item { CardRow("Your playlists", home.playlists) }
        item { CardRow("Your podcasts", home.shows) }
        if (home.topTracks.isNotEmpty()) {
            item { SectionTitle("On repeat") }
            items(home.topTracks.take(8), key = { "top" + it.uri }) { t -> TrackRow(t, null, true) { Ohm.spotify.playItem(t) } }
        }
        if (home.recentTracks.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            items(home.recentTracks.take(10), key = { "rec" + it.uri }) { t -> TrackRow(t, null, true) { Ohm.spotify.playItem(t) } }
        }
        item { Attribution() }
    }
}

@Composable
private fun Tile(item: SpItem, modifier: Modifier) {
    val c = OhmTheme.colors
    Row(modifier.height(56.dp).clip(RoundedCornerShape(8.dp)).background(c.surface).clickable { open(item) }, verticalAlignment = Alignment.CenterVertically) {
        Art(item.image, 56.dp, liked = item.type == "collection")
        Spacer(Modifier.width(10.dp))
        Text(if (item.type == "collection") tr("Liked Songs") else item.name, style = OhmTheme.type.label, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 6.dp))
    }
}

@Composable
private fun Attribution() = Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(SpotifyGreen))
    Spacer(Modifier.width(8.dp))
    Caption(tr("Content provided by Spotify. Some personalised mixes and editorial playlists aren't available to third-party apps."))
}

// ============================================================ search

private val SearchTypes = listOf("all" to "All", "track" to "Songs", "artist" to "Artists", "album" to "Albums", "playlist" to "Playlists", "show" to "Podcasts", "episode" to "Episodes")

@Composable
private fun SearchPage() {
    val c = OhmTheme.colors
    var q by remember { mutableStateOf(SearchMemory.q) }
    var type by remember { mutableStateOf(SearchMemory.type) }
    var results by remember { mutableStateOf(SearchMemory.results) }
    var loading by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(q, type) {
        SearchMemory.q = q; SearchMemory.type = type
        if (q.length < 2) { results = emptyMap(); return@LaunchedEffect }
        delay(350); loading = true; offset = 0
        results = runCatching { Ohm.spotify.api.search(q, if (type == "all") SearchTypes.drop(1).map { it.first } else listOf(type)) }.getOrDefault(emptyMap())
        SearchMemory.results = results
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            OhmTextField(q, { q = it }, tr("What do you want to listen to?"), Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), leading = Icons.Rounded.Search,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions())
            LazyRow(Modifier.padding(vertical = 10.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchTypes) { (k, label) -> Pill(tr(label), type == k) { type = k } }
            }
        }
        if (loading) item { Loading() }
        if (type == "all") {
            results["track"]?.takeIf { it.isNotEmpty() }?.let { tracks ->
                item { SectionTitle("Songs") }
                items(tracks.take(5), key = { "s" + it.uri }) { t -> TrackRow(t, null, true) { Ohm.spotify.playItem(t) } }
            }
            for ((k, title) in listOf("artist" to "Artists", "album" to "Albums", "playlist" to "Playlists", "show" to "Podcasts", "episode" to "Episodes")) {
                results[k]?.let { list -> item(key = "row$k") { CardRow(title, list) } }
            }
        } else {
            val list = results[type].orEmpty()
            if (type == "track" || type == "episode") items(list, key = { it.uri }) { t -> TrackRow(t, null, true) { Ohm.spotify.playItem(t) } }
            else item { CardGrid(list) }
            if (list.size >= 10 + offset - 0 && list.isNotEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    SecondaryButton(tr("Load more")) {
                        scope.launch {
                            offset += 10
                            val more = runCatching { Ohm.spotify.api.search(q, listOf(type), offset)[type].orEmpty() }.getOrDefault(emptyList())
                            results = results + (type to (list + more).distinctBy { it.uri })
                        }
                    }
                }
            }
        }
        if (!loading && q.length >= 2 && results.values.all { it.isEmpty() }) item { Empty("No results") }
        if (q.length < 2) item { Empty("Search songs, artists, albums, playlists and podcasts.") }
    }
}

private object SearchMemory { var q = ""; var type = "all"; var results: Map<String, List<SpItem>> = emptyMap() }

@Composable
private fun CardGrid(items: List<SpItem>) {
    BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
        val cols = (maxWidth / 160.dp).toInt().coerceAtLeast(2)
        val w = maxWidth / cols
        Column { items.chunked(cols).forEach { row -> Row { row.forEach { Card(it, w) } } } }
    }
}

// ============================================================ library

private val LibTabs = listOf("playlists" to "Playlists", "albums" to "Albums", "artists" to "Artists", "shows" to "Podcasts", "episodes" to "Your Episodes")

@Composable
private fun LibraryPage() {
    var tab by remember { mutableStateOf(LibMemory.tab) }
    val paged = remember(tab) {
        LibMemory.tab = tab
        LibMemory.cache.getOrPut(tab) {
            Paged { off, cur ->
                val api = Ohm.spotify.api
                when (tab) {
                    "albums" -> api.savedAlbums(off)
                    "artists" -> api.followedArtists(cur)
                    "shows" -> api.savedShows(off)
                    "episodes" -> api.savedEpisodes(off)
                    else -> api.myPlaylists(off)
                }
            }
        }
    }
    PagedList(paged, header = {
        item {
            Text(tr("Your Library"), style = OhmTheme.type.display.copy(fontSize = 26.sp), color = OhmTheme.colors.textPrimary, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
            LazyRow(Modifier.padding(vertical = 10.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibTabs) { (k, label) -> Pill(tr(label), tab == k) { tab = k } }
            }
        }
        if (tab == "playlists") item { LibRow(Ohm.spotify.likedSongsItem()) }
    }) { _, it -> if (it.type == "episode") TrackRow(it, null, true) { Ohm.spotify.playItem(it) } else LibRow(it) }
}

private object LibMemory { var tab = "playlists"; val cache = HashMap<String, Paged>() }

@Composable
private fun LibRow(item: SpItem) {
    val c = OhmTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open(item) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(item.image, 56.dp, circle = item.type == "artist", liked = item.type == "collection")
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (item.type == "collection") tr("Liked Songs") else item.name, style = OhmTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Caption(item.subtitle + (item.count?.let { " · $it" } ?: ""))
        }
    }
}

// ============================================================ detail

@Composable
private fun DetailPage(start: SpItem) {
    val c = OhmTheme.colors
    var item by remember(start.uri) { mutableStateOf(start) }
    var saved by remember(start.uri) { mutableStateOf<Boolean?>(null) }
    var albums by remember(start.uri) { mutableStateOf<List<SpItem>>(emptyList()) }
    var note by remember(start.uri) { mutableStateOf<String?>(null) }
    val api = Ohm.spotify.api
    val me = Ohm.spotify.userId
    LaunchedEffect(start.uri) {
        // Fill in missing metadata (e.g. "Go to album" only knows the URI).
        runCatching {
            when (start.type) {
                "album" -> parseItem(api.album(start.id))
                "playlist" -> parseItem(api.playlist(start.id))
                "artist" -> parseItem(api.artist(start.id))
                "show" -> parseItem(api.show(start.id))
                else -> null
            }
        }.getOrNull()?.let { item = it }
        if (start.type != "collection") saved = Ohm.spotify.isSaved(start.uri)
        if (start.type == "artist") albums = runCatching { api.artistAlbums(start.id) }.getOrDefault(emptyList())
    }
    val canList = when (item.type) { "playlist" -> item.ownerId == me || item.collaborative; else -> true }
    val paged = remember(item.uri, canList) {
        Paged { off, _ ->
            when (item.type) {
                "album" -> api.albumTracks(item.id, item.image, item.uri, off)
                "playlist" -> if (canList) api.playlistItems(item.id, off) else Page(emptyList(), false)
                "show" -> api.showEpisodes(item.id, item.image, item.uri, off)
                "collection" -> api.likedSongs(off)
                else -> Page(emptyList(), false)
            }
        }
    }
    if (item.type == "playlist" && !canList) note = "Spotify only lets third-party apps list the songs of playlists you own or collaborate on. Play and Shuffle still work."

    PagedList(paged, header = {
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.Bottom) {
                Art(item.image, 170.dp, circle = item.type == "artist", liked = item.type == "collection")
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Caption(tr(when (item.type) { "album" -> "Album"; "playlist", "collection" -> "Playlist"; "artist" -> "Artist"; "show" -> "Podcast"; else -> "" }).uppercase())
                    Text(if (item.type == "collection") tr("Liked Songs") else item.name, style = OhmTheme.type.display.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.type != "artist") Caption(item.subtitle.removePrefix("Playlist · ").removePrefix("Podcast"))
                    item.description?.takeIf { it.isNotBlank() }?.let {
                        Text(android.text.Html.fromHtml(it, 0).toString(), style = OhmTheme.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconBtn(Icons.Rounded.PlayArrow, tr("Play"), size = 56.dp, iconSize = 34.dp, tint = Color.White, background = Accent.accent) { Ohm.spotify.playItem(item) }
                        if (item.type != "show") IconBtn(Icons.Rounded.Shuffle, tr("Shuffle"), size = 48.dp, background = c.surface) { Ohm.spotify.playItem(item, shuffle = true) }
                        saved?.let { s ->
                            if (item.ownerId != me || item.type != "playlist") Pill(
                                tr(if (item.type == "artist" || item.type == "show") (if (s) "Following" else "Follow") else (if (s) "Saved" else "Save")), s,
                                icon = if (s) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                            ) { saved = !s; Ohm.spotify.setSaved(item.uri, !s) }
                        }
                        IconBtn(Icons.Rounded.OpenInNew, tr("Open in Spotify"), tint = c.iconMuted) { Ohm.spotify.openInSpotify(item.uri) }
                    }
                }
            }
            note?.let { Text(tr(it), style = OhmTheme.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        if (item.type == "artist") {
            item { Text(tr("Artist top tracks aren't available to third-party apps any more — Play starts the artist's mix in Spotify."), style = OhmTheme.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = 20.dp)) }
            val groups = albums.groupBy { if (it.count != null && it.count <= 3) "Singles & EPs" else "Albums" }
            groups["Albums"]?.let { item { SectionTitle("Albums"); CardGrid(it) } }
            groups["Singles & EPs"]?.let { item { SectionTitle("Singles & EPs"); CardGrid(it) } }
        }
    }) { i, t ->
        TrackRow(t, i, showArt = item.type != "album") { Ohm.spotify.playIn(item, t, paged.items) }
    }
}

// ============================================================ queue / lyrics / devices

@Composable
private fun QueuePage() {
    val pb by Ohm.spotify.playback.collectAsState()
    val q by Ohm.spotify.queue.collectAsState()
    LaunchedEffect(Unit) { Ohm.spotify.refreshQueue() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SectionTitle("Now playing") { IconBtn(Icons.Rounded.Refresh, tr("Refresh")) { Ohm.spotify.refreshQueue() } } }
        pb?.item?.let { t -> item { TrackRow(t, null, true) {} } }
        item { SectionTitle("Next up") }
        if (q.isEmpty()) item { Empty("The queue is empty") }
        itemsIndexed(q, key = { i, it -> "$i${it.uri}" }) { _, t -> TrackRow(t, null, true) { Ohm.spotify.playItem(t) } }
    }
}

@Composable
private fun LyricsPage() {
    val c = OhmTheme.colors
    val lyr by Ohm.spotify.lyrics.collectAsState()
    val pb by Ohm.spotify.playback.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }
    val pos = pb?.livePosition(now) ?: 0
    val state = rememberLazyListState()
    val l = lyr
    val cur = l?.synced?.indexOfLast { it.timeMs <= pos + 300 } ?: -1
    LaunchedEffect(cur) { if (cur >= 0) state.animateScrollToItem((cur - 3).coerceAtLeast(0)) }
    when {
        pb?.item == null -> Empty("Nothing playing")
        l == null -> Loading()
        l.instrumental -> Empty("♪ Instrumental")
        l.synced.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = 40.dp, horizontal = 32.dp)) {
            itemsIndexed(l.synced) { i, line ->
                val col by animateColorAsState(if (i == cur) c.textPrimary else if (i < cur) c.textSecondary.copy(alpha = 0.6f) else c.textSecondary, label = "lyric")
                Text(line.text.ifEmpty { "♪" }, style = OhmTheme.type.display.copy(fontSize = 26.sp, fontWeight = if (i == cur) FontWeight.SemiBold else FontWeight.Normal), color = col,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { Ohm.spotify.seek(line.timeMs) }.padding(vertical = 8.dp))
            }
            item { Caption(tr("Lyrics from LRCLIB (community-sourced)"), Modifier.padding(top = 24.dp)) }
        }
        l.plain != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp)) {
            item { Text(l.plain, style = OhmTheme.type.title, color = c.textPrimary) }
            item { Caption(tr("Lyrics from LRCLIB (community-sourced)"), Modifier.padding(top = 24.dp)) }
        }
        else -> Empty("No lyrics found for this song")
    }
}

private fun deviceIcon(type: String) = when (type.lowercase()) {
    "smartphone" -> Icons.Rounded.PhoneAndroid
    "tablet" -> Icons.Rounded.Tablet
    "computer" -> Icons.Rounded.Computer
    "tv", "castvideo", "stb" -> Icons.Rounded.Tv
    "automobile" -> Icons.Rounded.DirectionsCar
    else -> Icons.Rounded.Speaker
}

@Composable
private fun DevicesPage() {
    val c = OhmTheme.colors
    val devices by Ohm.spotify.devices.collectAsState()
    LaunchedEffect(Unit) { while (true) { Ohm.spotify.refreshDevices(); delay(5000) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SectionTitle("Connect to a device") { IconBtn(Icons.Rounded.Refresh, tr("Refresh")) { Ohm.spotify.refreshDevices() } } }
        if (devices.isEmpty()) item { Empty("No devices — open Spotify on this unit or your phone. Ohm starts Spotify here automatically when you press Play.") }
        item {
            val pref by Ohm.prefs.spotifyDevice.state.collectAsState()
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(tr("Always play on this unit"), pref.isEmpty() || pref == com.ohm.launcher.spotify.Spotify.THIS_UNIT, icon = Icons.Rounded.DirectionsCar) {
                    Ohm.prefs.spotifyDevice.set(com.ohm.launcher.spotify.Spotify.THIS_UNIT)
                }
            }
        }
        items(devices, key = { it.id }) { d -> DeviceRow(d) }
        item {
            Text(tr("Spotify Connect: play on this head unit, your phone, or any speaker. Ohm remembers your choice."), style = OhmTheme.type.caption, color = c.textSecondary, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun DeviceRow(d: SpDevice) {
    val c = OhmTheme.colors
    var vol by remember(d.id, d.volume) { mutableFloatStateOf((d.volume ?: 50) / 100f) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        .background(if (d.active) Accent.accent.copy(alpha = 0.12f) else c.surface).clickable(enabled = !d.active && !d.restricted) { Ohm.spotify.transferTo(d) }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(deviceIcon(d.type), null, tint = if (d.active) Accent.accent else c.icon, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name + if (Ohm.spotify.localDevice(listOf(d)) != null) "  ·  " + trNow("this unit") else "", style = OhmTheme.type.bodyStrong, color = if (d.active) Accent.accent else c.textPrimary)
                Caption(d.type + if (d.active) " · " + trNow("Playing here") else if (d.restricted) " · " + trNow("Can't be controlled") else "")
            }
        }
        if (d.active && d.supportsVolume && d.volume != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Icon(Icons.Rounded.VolumeUp, null, tint = c.icon, modifier = Modifier.size(20.dp))
            OhmSlider(vol, { vol = it }, Modifier.weight(1f).padding(start = 8.dp))
            LaunchedEffect(vol) { delay(400); if (((d.volume) / 100f) != vol) Ohm.spotify.setVolume((vol * 100).toInt()) }
        }
    }
}

// ============================================================ player bar

@Composable
private fun PlayerBar() {
    val c = OhmTheme.colors
    val pb by Ohm.spotify.playback.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pb?.playing) { while (pb?.playing == true) { delay(500); now = System.currentTimeMillis() } }
    val p = pb
    val item = p?.item
    Column(Modifier.fillMaxWidth().background(c.card).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Art(item?.image, 56.dp, modifier = Modifier.clickable { item?.parentUri?.let { u -> SpNav.go(SpPage.Detail(SpItem(if (item.type == "episode") "show" else "album", u, u.substringAfterLast(':'), "", "", item.image))) } })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item?.name ?: tr("Nothing playing"), style = OhmTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Caption(item?.subtitle ?: tr("Pick something to play"))
                }
                if (item != null) IconBtn(if (p.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, tr("Like"), tint = if (p.liked) SpotifyGreen else c.icon) { Ohm.spotify.toggleLike() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconBtn(Icons.Rounded.Shuffle, tr("Shuffle"), tint = if (p?.shuffle == true) Accent.accent else c.icon, enabled = p != null) { Ohm.spotify.setShuffle(!(p?.shuffle ?: false)) }
                IconBtn(Icons.Rounded.SkipPrevious, tr("Previous"), size = 52.dp, iconSize = 30.dp) { Ohm.spotify.previous() }
                IconBtn(if (p?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, tr("Play"), size = 60.dp, iconSize = 36.dp, tint = Color.White, background = Accent.accent) { Ohm.spotify.toggle() }
                IconBtn(Icons.Rounded.SkipNext, tr("Next"), size = 52.dp, iconSize = 30.dp) { Ohm.spotify.next() }
                IconBtn(if (p?.repeat == "track") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, tr("Repeat"), tint = if (p?.repeat != null && p.repeat != "off") Accent.accent else c.icon, enabled = p != null) { Ohm.spotify.cycleRepeat() }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Rounded.Lyrics, tr("Lyrics"), tint = if (SpNav.top == SpPage.Lyrics) Accent.accent else c.icon) { if (SpNav.top == SpPage.Lyrics) SpNav.back() else SpNav.go(SpPage.Lyrics) }
                IconBtn(Icons.Rounded.QueueMusic, tr("Queue"), tint = if (SpNav.top == SpPage.Queue) Accent.accent else c.icon) { if (SpNav.top == SpPage.Queue) SpNav.back() else SpNav.go(SpPage.Queue) }
                Row(Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(18.dp)).clickable { SpNav.go(SpPage.Devices) }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(deviceIcon(p?.device?.type ?: ""), null, tint = if (p?.device != null) SpotifyGreen else c.icon, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Caption(p?.device?.name ?: tr("Devices"), color = if (p?.device != null) SpotifyGreen else c.textSecondary)
                }
            }
        }
        // progress
        val dur = item?.durationMs ?: 0
        val pos = p?.livePosition(now) ?: 0
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Caption(fmt(pos), Modifier.width(52.dp))
            Box(Modifier.weight(1f).height(18.dp).pointerInput(dur) { detectTapGestures { o -> if (dur > 0) Ohm.spotify.seek((o.x / size.width * dur).toLong()) } }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceElevated)) {
                    Box(Modifier.fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(c.textPrimary))
                }
            }
            Caption(fmt(dur), Modifier.width(52.dp).padding(start = 8.dp))
        }
    }
}

// ============================================================ connect

@Composable
private fun ConnectPage(state: AuthState) {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedId by Ohm.prefs.spotifyClientId.state.collectAsState()
    var id by remember(savedId) { mutableStateOf(savedId) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(28.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(SpotifyGreen))
                Spacer(Modifier.width(10.dp))
                Text(tr("Connect Spotify"), style = OhmTheme.type.display, color = c.textPrimary, modifier = Modifier.weight(1f))
                IconBtn(Icons.Rounded.ArrowBack, tr("Close")) { ui.sheet = Sheet.NONE }
            }
            Text(tr("Ohm talks to Spotify through your own free developer app. One-time setup, about 3 minutes:"), style = OhmTheme.type.body, color = c.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
        }
        item { Step(1, "On a phone or computer open developer.spotify.com/dashboard, sign in and create an app. Tick \"Web API\".") }
        item {
            Step(2, "Add this Redirect URI exactly:")
            Text(SpotifyAuth.REDIRECT, style = OhmTheme.type.title.copy(fontSize = 18.sp), color = Accent.accent, modifier = Modifier.padding(start = 52.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(8.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        item { Step(3, "In User Management add the e-mail of your Spotify account (Development Mode: up to 5 users; the app owner needs Premium).") }
        item {
            Step(4, "Copy the app's Client ID and paste it here:")
            Row(Modifier.padding(start = 52.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OhmTextField(id, { id = it.trim() }, "Client ID", Modifier.widthIn(max = 420.dp).weight(1f))
                Spacer(Modifier.width(10.dp))
                SecondaryButton(tr("Save")) { Ohm.prefs.spotifyClientId.set(id); Ohm.spotify.auth.onClientIdChanged() }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            val busy = state is AuthState.SigningIn
            PrimaryButton(if (busy) tr("Waiting for sign-in in the browser…") else tr("Sign in with Spotify"), Modifier.padding(start = 52.dp).widthIn(min = 300.dp), color = if (savedId.isBlank()) c.surfaceElevated else Accent.accent) {
                if (savedId.isNotBlank() && !busy) scope.launch { Ohm.spotify.auth.signIn(ctx) }
            }
            (state as? AuthState.Error)?.let { Text(tr(it.message), style = OhmTheme.type.body, color = Accent.danger, modifier = Modifier.padding(start = 52.dp, top = 10.dp)) }
            Text(tr("Your password goes only to Spotify's own sign-in page. Ohm stores just an encrypted access token on this unit."), style = OhmTheme.type.caption, color = c.textSecondary, modifier = Modifier.padding(start = 52.dp, top = 14.dp))
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = OhmTheme.colors
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) { Text("$n", style = OhmTheme.type.bodyStrong, color = c.textPrimary) }
        Spacer(Modifier.width(16.dp))
        Text(tr(text), style = OhmTheme.type.body, color = c.textPrimary, modifier = Modifier.padding(top = 7.dp))
    }
}
