package com.ohm.launcher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.ohm.launcher.Ohm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Only exists so we're allowed to read other apps' media sessions. */
class MediaListenerService : NotificationListenerService()

/** One media session seen on the unit (Diagnostics). */
data class SessionInfo(val pkg: String, val label: String, val state: String, val phone: Boolean)

/** Bluetooth A2DP-sink / AVRCP sessions are published by the system Bluetooth app or the vendor BT-music app. */
fun isPhoneSession(pkg: String, label: String): Boolean {
    val p = pkg.lowercase(); val l = label.lowercase()
    return p == "com.android.bluetooth" || listOf("bluetooth", "btmusic", "bt.music", "a2dp", "avrcp", ".bt").any { p.contains(it) } ||
        l.contains("bluetooth") || l.split(" ").contains("bt")
}

data class QueueEntry(val id: Long, val title: String, val subtitle: String)

data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    /** Seed for a generated cover when there is no art. */
    val artSeed: Int,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val playing: Boolean,
    val packageName: String,
    val appLabel: String,
    val queue: List<QueueEntry>,
    val activeQueueId: Long,
    val canLike: Boolean,
    val liked: Boolean,
    val demo: Boolean,
    /** device = app playing on this unit (standalone) · phone = Bluetooth/AVRCP from a phone · spotify = Spotify Connect (Web API) · demo */
    val origin: String = if (demo) "demo" else "device",
    val artUrl: String? = null,
    val shuffle: Boolean? = null,
    val repeat: String? = null,
) {
    /** radio | podcast | music */
    val kind: String get() = when {
        "radio" in packageName.lowercase() || "fm" in appLabel.lowercase().split(" ") -> "radio"
        "podcast" in packageName.lowercase() || "podcast" in appLabel.lowercase() -> "podcast"
        else -> "music"
    }

    fun livePosition(now: Long = SystemClock.elapsedRealtime()): Long =
        if (!playing) positionMs else (positionMs + ((now - updatedAt) * speed).toLong()).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
}

class MediaRepo(private val ctx: Context, private val scope: CoroutineScope) {
    private val _np = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _np.asStateFlow()
    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private var controller: MediaController? = null
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, MediaListenerService::class.java)
    private val demo = DemoPlayer()

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) = publish()
        override fun onSessionDestroyed() { pick(null) }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    fun init() {
        refreshAccess()
        scope.launch {
            Ohm.prefs.demoMedia.state.collect { publish() }
        }
        scope.launch { Ohm.prefs.mediaMode.state.collect { pick(null) } }
        scope.launch { Ohm.spotify.playback.collect { publish() } }
    }

    fun refreshAccess() {
        val ok = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        _hasAccess.value = ok
        if (!ok) { publish(); return }
        runCatching {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            pick(msm.getActiveSessions(component))
        }
    }

    fun openAccessSettings() {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun pick(list: List<MediaController>?) {
        // Ignore Ohm's own session (the built-in Spotify receiver): its data comes from Spotify
        // directly, with album art, through the Spotify Connect fallback below.
        val sessions = (list ?: runCatching { msm.getActiveSessions(component) }.getOrNull().orEmpty())
            .filter { it.packageName != ctx.packageName }
        _sessions.value = sessions.map { c ->
            val label = labelOf(c.packageName)
            SessionInfo(c.packageName, label, stateName(c.playbackState?.state), isPhoneSession(c.packageName, label))
        }
        // Standalone: apps on this unit (Spotify…). Phone: Bluetooth/AVRCP. Auto: whatever is playing.
        val mode = Ohm.prefs.mediaMode.value
        val allowed = sessions.filter { c ->
            val phone = isPhoneSession(c.packageName, labelOf(c.packageName))
            when (mode) { "standalone" -> !phone; "phone" -> phone; else -> true }
        }
        val best = allowed.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: allowed.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            // No local session: show Spotify Connect playback (phone, speaker… or this unit) if signed in.
            val sp = Ohm.spotify.playback.value
            val item = sp?.item
            _np.value = if (item != null && Ohm.spotify.signedIn) NowPlaying(
                title = item.name, artist = item.subtitle, album = "", art = null, artSeed = item.uri.hashCode(),
                durationMs = item.durationMs, positionMs = sp.progressMs, updatedAt = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sp.fetchedAt),
                speed = 1f, playing = sp.playing, packageName = "spotify-connect", appLabel = "Spotify" + (sp.device?.name?.let { " · $it" } ?: ""),
                queue = emptyList(), activeQueueId = -1, canLike = true, liked = sp.liked, demo = false, origin = "spotify",
                artUrl = item.image, shuffle = sp.shuffle, repeat = sp.repeat,
            ) else if (Ohm.prefs.demoMedia.value) demo.snapshot() else null
            return
        }
        val ps = c.playbackState
        val label = labelOf(c.packageName)
        val likeAction = ps?.customActions?.firstOrNull { a -> listOf("like", "thumb", "favorite", "heart", "love").any { a.action.lowercase().contains(it) || a.name.toString().lowercase().contains(it) } }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        _np.value = NowPlaying(
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            artSeed = title.hashCode(),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = ps?.position ?: 0,
            updatedAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0 } ?: 1f,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            packageName = c.packageName,
            appLabel = label,
            queue = c.queue.orEmpty().map { QueueEntry(it.queueId, it.description.title?.toString() ?: "", it.description.subtitle?.toString() ?: "") },
            activeQueueId = ps?.activeQueueItemId ?: -1,
            // Spotify running on this unit: Ohm can like through the Web API when signed in.
            canLike = likeAction != null || (c.packageName == com.ohm.launcher.spotify.Spotify.SPOTIFY_PKG && Ohm.spotify.signedIn),
            liked = c.packageName == com.ohm.launcher.spotify.Spotify.SPOTIFY_PKG && Ohm.spotify.playback.value?.let { it.liked && it.item?.name == title } == true,
            demo = false,
            origin = if (isPhoneSession(c.packageName, label)) "phone" else "device",
        )
    }

    private fun labelOf(pkg: String) = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "playing"; PlaybackState.STATE_PAUSED -> "paused"; PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"; PlaybackState.STATE_ERROR -> "error"; null -> "none"; else -> "state $s"
    }

    private val tc get() = controller?.transportControls
    private val isConnect get() = controller?.metadata == null && _np.value?.origin == "spotify"
    private val isDemo get() = controller?.metadata == null && !isConnect

    fun playPause() {
        if (isConnect) { Ohm.spotify.toggle(); return }
        if (isDemo) { demo.toggle(); publish(); return }
        if (_np.value?.playing == true) tc?.pause() else tc?.play()
    }
    fun next() { if (isConnect) Ohm.spotify.next() else if (isDemo) { demo.next(); publish() } else tc?.skipToNext() }
    fun prev() { if (isConnect) Ohm.spotify.previous() else if (isDemo) { demo.prev(); publish() } else tc?.skipToPrevious() }
    fun stop() { if (isDemo) { demo.pause(); publish() } else tc?.stop() }
    fun seekBy(ms: Long) {
        val np = _np.value ?: return
        val to = (np.livePosition() + ms).coerceAtLeast(0)
        if (isConnect) { Ohm.spotify.seek(to); return }
        if (isDemo) { demo.seek(to); publish() } else tc?.seekTo(to)
    }
    fun seekTo(ms: Long) { if (isConnect) Ohm.spotify.seek(ms) else if (isDemo) { demo.seek(ms); publish() } else tc?.seekTo(ms) }
    fun jumpTo(id: Long) { if (isDemo) { demo.jump(id.toInt()); publish() } else tc?.skipToQueueItem(id) }
    fun like() {
        if (isConnect || (controller?.packageName == com.ohm.launcher.spotify.Spotify.SPOTIFY_PKG && Ohm.spotify.signedIn)) { Ohm.spotify.toggleLike(); return }
        if (isDemo) { demo.like(); publish(); return }
        val ps = controller?.playbackState ?: return
        val a = ps.customActions.firstOrNull { a -> listOf("like", "thumb", "favorite", "heart", "love").any { a.action.lowercase().contains(it) } } ?: return
        tc?.sendCustomAction(a, Bundle.EMPTY)
    }
    /** Asks the current app (Spotify, YT Music…) to search and play. */
    fun playFromSearch(q: String) {
        if (isDemo) { demo.search(q); publish(); return }
        tc?.playFromSearch(q, Bundle.EMPTY)
    }

    private var tick: Job? = null
    fun keepTicking() {
        if (tick?.isActive == true) return
        tick = scope.launch { while (isActive) { delay(1000); if (isDemo && demo.advance()) publish() } }
    }
}

/** Silent fake player so the UI can be tested without any media app. */
private class DemoPlayer {
    private data class T(val title: String, val artist: String, val album: String, val dur: Long)
    private val tracks = listOf(
        T("Coastal Highway", "The Night Shifts", "Long Exposure", 214_000),
        T("Neon Tunnels", "Mira Sol", "Afterglow", 187_000),
        T("Ribeira at Dusk", "Lua Azul", "Margens", 243_000),
        T("Cruise Control", "Static Bloom", "Low Orbit", 199_000),
        T("Fado Electric", "Casa Ampere", "Correntes", 228_000),
    )
    private var idx = 0
    private var pos = 0L
    private var playing = false
    private var updatedAt = SystemClock.elapsedRealtime()
    private val liked = HashSet<Int>()

    fun snapshot(): NowPlaying {
        val t = tracks[idx]
        return NowPlaying(t.title, t.artist, t.album, null, idx * 7919 + 17, t.dur, pos, updatedAt, 1f, playing,
            "com.ohm.demo", "Demo player", tracks.mapIndexed { i, x -> QueueEntry(i.toLong(), x.title, x.artist) }, idx.toLong(),
            canLike = true, liked = idx in liked, demo = true)
    }

    private fun sync() {
        val now = SystemClock.elapsedRealtime()
        if (playing) pos += now - updatedAt
        updatedAt = now
    }

    fun toggle() { sync(); playing = !playing }
    fun pause() { sync(); playing = false }
    fun next() { idx = (idx + 1) % tracks.size; pos = 0; updatedAt = SystemClock.elapsedRealtime() }
    fun prev() { sync(); if (pos > 3000) pos = 0 else { idx = (idx - 1 + tracks.size) % tracks.size; pos = 0 }; updatedAt = SystemClock.elapsedRealtime() }
    fun seek(ms: Long) { pos = ms.coerceIn(0, tracks[idx].dur); updatedAt = SystemClock.elapsedRealtime() }
    fun jump(i: Int) { idx = i.coerceIn(0, tracks.size - 1); pos = 0; playing = true; updatedAt = SystemClock.elapsedRealtime() }
    fun like() { if (!liked.add(idx)) liked.remove(idx) }
    fun search(q: String) {
        val i = tracks.indexOfFirst { it.title.contains(q, true) || it.artist.contains(q, true) }
        if (i >= 0) jump(i)
    }
    /** Returns true when the track changed. */
    fun advance(): Boolean {
        if (!playing) return false
        sync()
        if (pos >= tracks[idx].dur) { next(); playing = true; return true }
        return false
    }
}
