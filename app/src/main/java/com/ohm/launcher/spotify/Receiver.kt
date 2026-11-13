package com.ohm.launcher.spotify

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.system.Os
import androidx.browser.customtabs.CustomTabsIntent
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.OhmLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Ohm's built-in Spotify Connect receiver ("Ohm (car)"), powered by librespot — an unofficial,
 * open-source Spotify client (MIT). It runs as a native child process; its decoded audio
 * (PCM s16le, 44.1 kHz, stereo) comes through a FIFO and is played here with AudioTrack, so the
 * sound comes out of the head unit without the official Spotify app.
 *
 * Unofficial: it uses Spotify's private protocol, which Spotify's terms don't allow and which
 * Spotify changes from time to time. Off by default; the user turns it on knowingly.
 */
class Receiver(private val ctx: Context, private val scope: CoroutineScope) {

    enum class Status { OFF, MISSING, STARTING, WAITING_LOGIN, READY, PLAYING, SIGNING_IN, ERROR }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    private val binary get() = File(ctx.applicationInfo.nativeLibraryDir, "liblibrespot.so")
    private val cacheDir get() = File(ctx.filesDir, "librespot").apply { mkdirs() }
    private val fifo get() = File(ctx.cacheDir, "librespot.pcm")
    val hasCredentials get() = File(cacheDir, "credentials.json").exists()
    val available get() = binary.exists()

    private var proc: Process? = null
    private var job: Job? = null
    private var audioJob: Job? = null
    private var restarts = 0

    fun init() {
        scope.launch {
            Ohm.prefs.librespot.state.collect { on -> if (on) start() else stop() }
        }
    }

    private fun deviceName() = Ohm.prefs.librespotName.value.ifBlank { "Ohm (car)" }

    // ------------------------------------------------------------------ process

    fun start() {
        stop(keepStatus = true)
        if (!available) { set(Status.MISSING, "librespot isn't included in this build"); return }
        set(Status.STARTING, "Starting…")
        job = scope.launch(Dispatchers.IO) {
            while (isActive && Ohm.prefs.librespot.value) {
                runCatching { fifo.delete(); Os.mkfifo(fifo.absolutePath, 384 /* 0600 */) }
                    .onFailure { set(Status.ERROR, "Can't create audio pipe: ${it.message}"); return@launch }
                val args = listOf(
                    binary.absolutePath,
                    "--name", deviceName(),
                    "--device-type", "automobile",
                    "--backend", "pipe", "--device", fifo.absolutePath,
                    "--bitrate", Ohm.prefs.librespotBitrate.value.toString(),
                    "--cache", cacheDir.absolutePath, "--disable-audio-cache",
                    "--mixer", "softvol", "--volume-ctrl", "log", "--initial-volume", "80",
                    "--enable-volume-normalisation",
                    "--autoplay", "on",
                )
                val p = runCatching {
                    ProcessBuilder(args).redirectErrorStream(true)
                        .apply { environment()["HOME"] = cacheDir.absolutePath; environment()["TMPDIR"] = ctx.cacheDir.absolutePath }
                        .start()
                }.getOrElse { set(Status.ERROR, "Can't start librespot: ${it.message}"); return@launch }
                proc = p
                set(if (hasCredentials) Status.STARTING else Status.WAITING_LOGIN,
                    if (hasCredentials) "Connecting…" else "Sign in, or pick \"${deviceName()}\" in the Spotify app on a phone on the same Wi-Fi")
                startAudio()
                p.inputStream.bufferedReader().forEachLine { line -> onLog(line) }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                audioJob?.cancel()
                if (!Ohm.prefs.librespot.value) break
                restarts++
                OhmLog.w("Librespot", "Exited with code $code (restart #$restarts)")
                set(Status.ERROR, "Stopped (code $code) — restarting…")
                delay((2000L * restarts).coerceAtMost(30_000))
            }
        }
    }

    fun stop(keepStatus: Boolean = false) {
        job?.cancel(); job = null
        proc?.destroy(); proc = null
        audioJob?.cancel(); audioJob = null
        unblockReader()
        releaseAudio()
        if (!keepStatus) set(Status.OFF, "")
    }

    /** A reader blocked in open() on the FIFO only returns once a writer opens it; do that briefly. */
    private fun unblockReader() {
        runCatching {
            val fd = Os.open(fifo.absolutePath, android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_NONBLOCK, 0)
            Os.close(fd)
        } // ENXIO when nobody is reading — nothing to unblock.
    }

    private fun set(s: Status, d: String) { _status.value = s; _detail.value = d }

    private fun onLog(line: String) {
        // librespot logs: "[2026-… INFO  librespot_core::session] Authenticated as '…' !"
        when {
            "Authenticated as" in line -> { restarts = 0; set(Status.READY, "Signed in — ready as \"${deviceName()}\"") ; OhmLog.i("Librespot", line.substringAfter("] ").take(120)) }
            "Browse to:" in line -> openLogin(line.substringAfter("Browse to:").trim())
            " ERROR " in line || " WARN " in line -> { OhmLog.w("Librespot", line.substringAfter("] ").take(160)); if (" ERROR " in line) _detail.value = line.substringAfter("] ").take(140) }
            "Loading <" in line || "loading" in line.lowercase() && "track" in line.lowercase() -> OhmLog.i("Librespot", line.substringAfter("] ").take(120))
        }
    }

    // ------------------------------------------------------------------ sign-in (librespot's own OAuth)

    /** Runs librespot once with --enable-oauth; it prints a URL and waits on 127.0.0.1:5588. */
    fun signIn() {
        if (!available) return
        stop(keepStatus = true)
        set(Status.SIGNING_IN, "Finish signing in in the browser…")
        scope.launch(Dispatchers.IO) {
            val p = runCatching {
                ProcessBuilder(binary.absolutePath, "--enable-oauth", "--oauth-port", "5588", "--cache", cacheDir.absolutePath,
                    "--disable-audio-cache", "--disable-discovery", "--backend", "pipe", "--device", File(ctx.cacheDir, "oauth.pcm").absolutePath,
                    "--name", deviceName())
                    .redirectErrorStream(true).apply { environment()["HOME"] = cacheDir.absolutePath }.start()
            }.getOrElse { set(Status.ERROR, "Can't start sign-in: ${it.message}"); return@launch }
            val reader = launch { p.inputStream.bufferedReader().forEachLine { onLog(it) } }
            // Done when librespot has stored reusable credentials.
            repeat(300) {
                if (hasCredentials) return@repeat
                delay(1000)
            }
            p.destroy(); reader.cancel()
            runCatching { File(ctx.cacheDir, "oauth.pcm").delete() }
            withContext(Dispatchers.Main) {
                bringOhmToFront()
                if (hasCredentials) { OhmLog.i("Librespot", "Credentials saved"); Ohm.prefs.librespot.set(true); start() }
                else set(Status.ERROR, "Sign-in didn't finish")
            }
        }
    }

    fun signOut() {
        stop(keepStatus = true)
        File(cacheDir, "credentials.json").delete()
        if (Ohm.prefs.librespot.value) start() else set(Status.OFF, "")
    }

    private fun openLogin(url: String) = scope.launch(Dispatchers.Main) {
        val u = Uri.parse(url)
        runCatching { CustomTabsIntent.Builder().build().launchUrl(ctx, u) }
            .onFailure { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun bringOhmToFront() = runCatching {
        ctx.startActivity(Intent(ctx, com.ohm.launcher.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ------------------------------------------------------------------ audio

    private val am = ctx.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var session: MediaSession? = null

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private fun startAudio() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(16 * 1024)
            // librespot opens the pipe when playback starts and closes it when it stops,
            // so keep re-opening it for as long as the process runs.
            while (isActive && proc?.isAlive == true) {
                // Blocks until librespot opens the pipe for writing.
                val input = runCatching { FileInputStream(fifo) }.getOrElse { OhmLog.w("Librespot", "FIFO", it); delay(1000); continue }
                input.use {
                    while (isActive) {
                        val n = runCatching { it.read(buf) }.getOrDefault(-1)
                        if (n <= 0) break
                        val t = track ?: createTrack() ?: break
                        t.write(buf, 0, n)
                    }
                }
                withContext(Dispatchers.Main) { releaseAudio() }
            }
        }
    }

    private suspend fun createTrack(): AudioTrack? = withContext(Dispatchers.Main) {
        val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minBuf * 4, 64 * 1024))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse { OhmLog.w("Librespot", "AudioTrack", it); null } ?: return@withContext null
        t.play()
        track = t
        // Equalizer on the receiver's own audio session.
        eq = runCatching { android.media.audiofx.Equalizer(0, t.audioSessionId) }.getOrNull()
        com.ohm.launcher.core.Eq.apply(eq)
        requestFocus()
        createSession()
        set(Status.PLAYING, "Playing on \"${deviceName()}\"")
        t
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> Ohm.spotify.pauseIfOn(deviceName())
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track?.setVolume(0.25f)
                    AudioManager.AUDIOFOCUS_GAIN -> track?.setVolume(1f)
                }
            }.build()
        focus = req
        am.requestAudioFocus(req)
    }

    /**
     * A media session so steering-wheel buttons and the system see "Spotify on Ohm".
     * Commands go to Spotify through the Web API (the receiver itself has no local controls).
     */
    private fun createSession() {
        if (session != null) return
        session = MediaSession(ctx, "OhmSpotify").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Ohm.spotify.toggleIfPaused()
                override fun onPause() { Ohm.spotify.pauseIfOn(deviceName()) }
                override fun onSkipToNext() { Ohm.spotify.next() }
                override fun onSkipToPrevious() { Ohm.spotify.previous() }
                override fun onSeekTo(pos: Long) = Ohm.spotify.seek(pos)
            })
            isActive = true
        }
        scope.launch {
            Ohm.spotify.playback.collect { p ->
                val s = session ?: return@collect
                val item = p?.item
                val art = item?.image?.let { loadArt(it) }
                s.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, item?.name ?: "")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, item?.subtitle ?: "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, item?.durationMs ?: 0)
                    .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
                    .build())
                s.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
                    .setState(if (p?.playing == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, p?.livePosition() ?: 0, 1f)
                    .build())
            }
        }
    }

    private var artUrl: String? = null
    private var artBitmap: android.graphics.Bitmap? = null

    /** Cover for the system media session (lock screen, head-unit widgets); cached for the current track. */
    private suspend fun loadArt(url: String): android.graphics.Bitmap? {
        if (url == artUrl) return artBitmap
        val bmp = withContext(Dispatchers.IO) {
            runCatching { java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        artUrl = url; artBitmap = bmp
        return bmp
    }

    private var eq: android.media.audiofx.Equalizer? = null

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(Ohm.prefs.eqOn.state, Ohm.prefs.eqBands.state) { _, _ -> }.collect { com.ohm.launcher.core.Eq.apply(eq) }
        }
    }

    private fun releaseAudio() {
        eq?.let { runCatching { it.release() } }
        eq = null
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        session?.release(); session = null
        if (_status.value == Status.PLAYING) set(Status.READY, "Ready as \"${deviceName()}\"")
    }
}
