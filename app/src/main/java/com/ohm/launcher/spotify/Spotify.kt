package com.ohm.launcher.spotify

import android.content.Context
import android.content.Intent
import android.os.Build
import com.ohm.launcher.MainActivity
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.Http
import com.ohm.launcher.core.OhmLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class HomeData(
    val jumpBackIn: List<SpItem> = emptyList(),
    val recentTracks: List<SpItem> = emptyList(),
    val topArtists: List<SpItem> = emptyList(),
    val topTracks: List<SpItem> = emptyList(),
    val playlists: List<SpItem> = emptyList(),
    val shows: List<SpItem> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
)

data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val uri: String, val synced: List<LyricLine>, val plain: String?, val instrumental: Boolean)

/**
 * Spotify feature hub: sign-in, browsing, Connect playback on any device (this unit, phone,
 * speakers), queue, library and synced lyrics (LRCLIB — Spotify doesn't offer lyrics to apps).
 */
class Spotify(private val ctx: Context, private val scope: CoroutineScope) {
    val auth = SpotifyAuth(ctx)
    val api = SpotifyApi(auth)

    private val _playback = MutableStateFlow<SpPlayback?>(null)
    val playback: StateFlow<SpPlayback?> = _playback.asStateFlow()
    private val _devices = MutableStateFlow<List<SpDevice>>(emptyList())
    val devices: StateFlow<List<SpDevice>> = _devices.asStateFlow()
    private val _queue = MutableStateFlow<List<SpItem>>(emptyList())
    val queue: StateFlow<List<SpItem>> = _queue.asStateFlow()
    private val _home = MutableStateFlow(HomeData())
    val home: StateFlow<HomeData> = _home.asStateFlow()
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    /** Last user-facing problem (Premium required, no device…). */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set while the Spotify sheet is visible → faster polling. */
    @Volatile var uiVisible = false
    private var pollJob: Job? = null
    private var lastUri: String? = null

    val signedIn get() = auth.state.value is AuthState.SignedIn
    val userId get() = (auth.state.value as? AuthState.SignedIn)?.userId.orEmpty()

    fun init() {
        auth.init()
        scope.launch { Ohm.prefs.spotifyClientId.state.collect { if (it.trim() != auth.clientId || auth.state.value is AuthState.NoClientId) auth.init() } }
        if (signedIn) onSignedIn()
    }

    fun onSignedIn() {
        startPolling()
        loadHome()
    }

    fun signOut() {
        pollJob?.cancel()
        auth.signOut()
        _playback.value = null; _devices.value = emptyList(); _queue.value = emptyList(); _home.value = HomeData(); _lyrics.value = null
    }

    // ------------------------------------------------------------------ polling

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && signedIn) {
                refreshPlayback()
                val playing = _playback.value?.playing == true
                delay(when { uiVisible -> 1500L; playing -> 4000L; else -> 12_000L })
            }
        }
    }

    suspend fun refreshPlayback() {
        try {
            val p = api.playback()
            val uri = p?.item?.uri
            if (uri != null && uri != lastUri) {
                lastUri = uri
                val liked = runCatching { api.contains(listOf(uri)).firstOrNull() == true }.getOrDefault(false)
                _playback.value = p.copy(liked = liked)
                if (Ohm.prefs.lyrics.value) loadLyrics(p.item)
                if (uiVisible) refreshQueue()
            } else _playback.value = p?.copy(liked = _playback.value?.liked ?: false)
        } catch (e: SignedOutException) {
            pollJob?.cancel()
        } catch (e: Exception) {
            OhmLog.w("Spotify", "playback poll", e)
        }
    }

    fun refreshDevices() = scope.launch { runCatching { _devices.value = api.devices() }.onFailure { report(it) } }
    fun refreshQueue() = scope.launch { runCatching { _queue.value = api.queue().second }.onFailure { OhmLog.w("Spotify", "queue", it) } }

    // ------------------------------------------------------------------ home

    fun loadHome(force: Boolean = false) {
        if (_home.value.loading || (_home.value.loaded && !force)) return
        _home.update { it.copy(loading = true) }
        scope.launch {
            try {
                val recentD = async { runCatching { api.recentlyPlayed() }.getOrDefault(emptyList()) }
                val artistsD = async { runCatching { api.top("artists") }.getOrDefault(emptyList()) }
                val tracksD = async { runCatching { api.top("tracks") }.getOrDefault(emptyList()) }
                val plD = async { runCatching { api.myPlaylists().items }.getOrDefault(emptyList()) }
                val showsD = async { runCatching { api.savedShows().items }.getOrDefault(emptyList()) }
                val recent = recentD.await()
                val playlists = plD.await()
                // "Jump back in": distinct recent contexts (albums, playlists, artists).
                val ctxUris = recent.mapNotNull { (t, c) -> c ?: t.parentUri }.distinct().take(10)
                val known = playlists.associateBy { it.uri }
                val jump = ctxUris.map { uri ->
                    async {
                        known[uri] ?: runCatching { resolve(uri, recent.firstOrNull { (t, c) -> (c ?: t.parentUri) == uri }?.first) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
                _home.value = HomeData(jump, recent.map { it.first }.distinctBy { it.uri }.take(20), artistsD.await(), tracksD.await(), playlists, showsD.await(), loading = false, loaded = true)
            } catch (e: Exception) {
                report(e); _home.update { it.copy(loading = false) }
            }
        }
    }

    /** Turns a context URI into a card. */
    private suspend fun resolve(uri: String, sampleTrack: SpItem?): SpItem? {
        val (type, id) = uri.split(':').let { (it.getOrNull(1) ?: "") to (it.lastOrNull() ?: "") }
        return when (type) {
            "album" -> parseItem(api.album(id))
            "playlist" -> parseItem(api.playlist(id))
            "artist" -> parseItem(api.artist(id))
            "show" -> parseItem(api.show(id))
            "collection", "user" -> likedSongsItem()
            else -> sampleTrack
        }
    }

    fun likedSongsItem() = SpItem("collection", "spotify:user:$userId:collection", "liked", "Liked Songs", "Playlist", null)

    // ------------------------------------------------------------------ actions

    private fun report(e: Throwable) {
        val msg = when {
            e is SpotifyException && e.premiumRequired -> "Playback control needs Spotify Premium"
            e is SpotifyException && e.noDevice -> if (ctx.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG) == null)
                "Install Spotify on this head unit (and sign in) so music plays through the car — or pick your phone in Devices"
                else "Spotify on this unit didn't respond — open it once and make sure it's signed in"
            e is SignedOutException -> "Signed out of Spotify — connect again in Controls › Media"
            else -> e.message ?: "Spotify error"
        }
        OhmLog.w("Spotify", msg, e)
        _message.value = msg
        Ohm.banners.info("Spotify", msg)
    }

    fun clearMessage() { _message.value = null }

    /** Runs a control command (pause, next, seek…) on whatever device is playing. */
    private fun act(after: Long = 350, block: suspend (deviceId: String?) -> Unit) = scope.launch {
        try {
            try { block(null) }
            catch (e: SpotifyException) {
                if (!e.noDevice) throw e
                val d = targetDevice() ?: throw e
                block(d.id)
            }
            delay(after); refreshPlayback()
        } catch (e: Exception) { report(e) }
    }

    /** Starts playback explicitly on the target device (normally this head unit). */
    private fun playOn(label: String, block: suspend (deviceId: String) -> Unit) = scope.launch {
        try {
            val d = targetDevice() ?: throw SpotifyException(404, "No Spotify device found", "NO_ACTIVE_DEVICE")
            OhmLog.i("Spotify", "Play $label on ${d.name} (${d.type})")
            block(d.id)
            delay(700); refreshPlayback()
            if (_playback.value?.playing != true) { delay(1200); refreshPlayback() }
        } catch (e: Exception) { report(e) }
    }

    private fun unitNames(): List<String> = listOfNotNull(
        Build.MODEL,
        runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull(),
        runCatching { android.provider.Settings.Secure.getString(ctx.contentResolver, "bluetooth_name") }.getOrNull(),
        Ohm.prefs.spotifyLocalName.value.ifEmpty { null },
        // Ohm's own librespot receiver, when switched on
        if (Ohm.prefs.librespot.value) Ohm.prefs.librespotName.value.ifBlank { "Ohm (car)" } else null,
    ).filter { it.isNotBlank() }

    /** This head unit's own Spotify Connect device, if it's listed. */
    fun localDevice(list: List<SpDevice> = _devices.value): SpDevice? {
        val names = unitNames()
        return list.firstOrNull { d -> !d.restricted && (names.any { it.equals(d.name, true) } || d.type.equals("Automobile", true)) }
    }

    /**
     * Where music should play: the device you picked in Devices; otherwise this head unit (Spotify
     * on the unit is woken up if needed, so the sound comes out of the car speakers); otherwise
     * whatever is active.
     */
    suspend fun targetDevice(): SpDevice? {
        var list = api.devices().also { _devices.value = it }
        val pref = Ohm.prefs.spotifyDevice.value
        if (pref.isNotEmpty() && pref != THIS_UNIT) list.firstOrNull { it.name == pref && !it.restricted }?.let { return it }
        // Built-in receiver first (it's always running when enabled), then the Spotify app on this unit.
        if (Ohm.prefs.librespot.value) {
            val name = Ohm.prefs.librespotName.value.ifBlank { "Ohm (car)" }
            repeat(5) {
                list.firstOrNull { it.name == name }?.let { return it }
                delay(1000); list = api.devices().also { _devices.value = it }
            }
        }
        localDevice(list)?.let { return it }
        // Wake Spotify on this unit: it registers as a Connect device a few seconds after starting.
        val launch = ctx.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG)
        if (launch != null) {
            val before = list.map { it.id }.toSet()
            OhmLog.i("Spotify", "Starting Spotify on this unit")
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(2500)
            runCatching { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            repeat(10) {
                list = api.devices().also { _devices.value = it }
                localDevice(list)?.let { return it }
                // A device that appeared right after starting Spotify here is this unit — remember its name.
                list.firstOrNull { it.id !in before && !it.restricted }?.let { d ->
                    Ohm.prefs.spotifyLocalName.set(d.name)
                    OhmLog.i("Spotify", "This unit is \"${d.name}\"")
                    return d
                }
                delay(1000)
            }
            OhmLog.w("Spotify", "Spotify on this unit didn't appear as a Connect device — is it signed in?")
        } else OhmLog.w("Spotify", "Spotify app not installed on this unit")
        return list.firstOrNull { it.active } ?: list.firstOrNull { !it.restricted }
    }

    /** Kept for callers that only need an id. */
    suspend fun ensureDevice(): String? = targetDevice()?.id

    fun playItem(item: SpItem, shuffle: Boolean = false) = playOn(item.name) { dev ->
        if (shuffle) runCatching { api.shuffle(true) }
        when (item.type) {
            "track", "episode" -> api.play(dev, uris = listOf(item.uri))
            "collection" -> playLiked(dev, null)
            else -> api.play(dev, contextUri = item.uri)
        }
    }

    /** Play [track] inside its [context] (album, playlist, show) so next/previous follow the list. */
    fun playIn(context: SpItem, track: SpItem, siblings: List<SpItem>) = playOn(track.name) { dev ->
        when (context.type) {
            "collection" -> playLiked(dev, track)
            "artist" -> api.play(dev, uris = siblings.dropWhile { it.uri != track.uri }.map { it.uri }.ifEmpty { listOf(track.uri) })
            else -> api.play(dev, contextUri = context.uri, offsetUri = track.uri)
        }
    }

    private suspend fun playLiked(dev: String?, start: SpItem?) {
        try { api.play(dev, contextUri = "spotify:user:$userId:collection", offsetUri = start?.uri) }
        catch (e: SpotifyException) {
            if (e.noDevice || e.premiumRequired) throw e
            // Fallback: play the first 100 liked songs as a list.
            val songs = api.likedSongs().items.map { it.uri }
            api.play(dev, uris = if (start != null) listOf(start.uri) + songs.filter { it != start.uri } else songs)
        }
    }

    /** Used by the built-in receiver's media session / audio focus. */
    fun pauseIfOn(deviceName: String) { if (_playback.value?.playing == true && _playback.value?.device?.name == deviceName) act { api.pause() } }
    fun toggleIfPaused() { if (_playback.value?.playing != true) act { api.resume(it) } }

    fun toggle() { val p = _playback.value; if (p?.playing == true) { _playback.value = p.copy(playing = false, progressMs = p.livePosition(), fetchedAt = System.currentTimeMillis()); act { api.pause() } } else act { api.resume(it) } }
    fun next() = act(500) { api.next() }
    fun previous() = act(500) { api.previous() }
    fun seek(ms: Long) { _playback.update { it?.copy(progressMs = ms, fetchedAt = System.currentTimeMillis()) }; act { api.seek(ms) } }
    fun seekBy(delta: Long) = seek((_playback.value?.livePosition() ?: 0) + delta)
    fun setShuffle(on: Boolean) { _playback.update { it?.copy(shuffle = on) }; act { api.shuffle(on) } }
    fun cycleRepeat() {
        val next = when (_playback.value?.repeat) { "off" -> "context"; "context" -> "track"; else -> "off" }
        _playback.update { it?.copy(repeat = next) }
        act { api.repeat(next) }
    }
    fun setVolume(pct: Int) = act(200) { api.volume(pct) }
    fun transferTo(d: SpDevice) {
        Ohm.prefs.spotifyDevice.set(if (localDevice(listOf(d)) != null) THIS_UNIT else d.name)
        act(800) { api.transfer(d.id, true) }
    }
    fun addToQueue(item: SpItem) = scope.launch {
        try { api.addToQueue(item.uri); Ohm.banners.info("Spotify", "Added to queue: ${item.name}"); refreshQueue() }
        catch (e: Exception) { report(e) }
    }

    fun toggleLike(uri: String? = _playback.value?.item?.uri) = scope.launch {
        uri ?: return@launch
        val cur = _playback.value
        val liked = if (cur?.item?.uri == uri) cur.liked else runCatching { api.contains(listOf(uri)).first() }.getOrDefault(false)
        try {
            if (liked) api.remove(listOf(uri)) else api.save(listOf(uri))
            if (cur?.item?.uri == uri) _playback.value = cur.copy(liked = !liked)
            Ohm.banners.info("Spotify", if (liked) "Removed from Liked Songs" else "Added to Liked Songs")
        } catch (e: Exception) { report(e) }
    }

    suspend fun isSaved(uri: String) = runCatching { api.contains(listOf(uri)).firstOrNull() == true }.getOrDefault(false)
    fun setSaved(uri: String, saved: Boolean) = scope.launch {
        try { if (saved) api.save(listOf(uri)) else api.remove(listOf(uri)) } catch (e: Exception) { report(e) }
    }

    fun openInSpotify(uri: String) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Ohm.banners.info("Spotify", "Spotify app not installed on this unit") }

    // ------------------------------------------------------------------ lyrics (LRCLIB)

    private fun loadLyrics(item: SpItem) {
        if (item.type != "track") { _lyrics.value = null; return }
        scope.launch {
            _lyrics.value = null
            val artist = item.subtitle.substringBefore(',').trim()
            val url = "https://lrclib.net/api/get?track_name=${Http.enc(item.name)}&artist_name=${Http.enc(artist)}&duration=${item.durationMs / 1000}"
            val l = runCatching {
                val o = JSONObject(Http.get(url))
                Lyrics(item.uri, parseLrc(o.optString("syncedLyrics")), o.optString("plainLyrics").ifEmpty { null }, o.optBoolean("instrumental"))
            }.getOrNull()
            if (_playback.value?.item?.uri == item.uri) _lyrics.value = l ?: Lyrics(item.uri, emptyList(), null, false)
        }
    }

    companion object {
        const val SPOTIFY_PKG = "com.spotify.music"
        /** spotifyDevice value meaning "always this head unit". */
        const val THIS_UNIT = "@unit"

        fun parseLrc(text: String): List<LyricLine> {
            if (text.isBlank()) return emptyList()
            val re = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
            return text.lines().flatMap { line ->
                val stamps = re.findAll(line).toList()
                val words = line.replace(re, "").trim()
                stamps.map { m ->
                    val (mm, ss, frac) = m.destructured
                    val ms = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, words)
                }
            }.sortedBy { it.timeMs }
        }
    }
}
