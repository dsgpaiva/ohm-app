package com.ohm.launcher.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ohm.launcher.Ohm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL

data class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState()
    data class Installing(val info: UpdateInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * In-app updates. Ohm reads `update.json` from an address you set (e.g. the PC script
 * tools/serve-updates.py on your home Wi-Fi, or any HTTPS host), downloads the APK and hands it to
 * Android's installer. Android itself refuses an update that isn't signed with the same key, so a
 * plain-HTTP download on the local network can't install anything foreign.
 *
 * update.json: {"versionCode": 12, "versionName": "0.5.0", "apk": "Ohm-0.5.0.apk", "notes": "…"}
 */
class Updater(private val ctx: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: Pair<Long, String> get() = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        (if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()) to (pi.versionName ?: "?")
    }.getOrDefault(0L to "?")

    fun init() {
        scope.launch {
            delay(20_000) // let the car settle after start-up
            while (isActive) {
                if (Ohm.prefs.updateUrl.value.isNotBlank()) check(quiet = true)
                delay(6 * 3600_000L)
            }
        }
    }

    fun check(quiet: Boolean = false) = scope.launch {
        val url = Ohm.prefs.updateUrl.value.trim()
        if (url.isEmpty()) { _state.value = UpdateState.Error("Set the update address first"); return@launch }
        _state.value = UpdateState.Checking
        try {
            val json = JSONObject(String(fetch(url)))
            val apk = json.getString("apk")
            val info = UpdateInfo(json.getLong("versionCode"), json.optString("versionName"),
                URL(URL(url), apk).toString(), json.optString("notes"))
            if (info.versionCode > currentVersion.first) {
                _state.value = UpdateState.Available(info)
                OhmLog.i("Update", "Update available: ${info.versionName}")
                if (quiet) Ohm.banners.info("Ohm ${info.versionName}", com.ohm.launcher.i18n.Strings.tr(Ohm.prefs.language.value, "Update available — Controls › System"))
            } else _state.value = UpdateState.UpToDate
        } catch (e: Exception) {
            OhmLog.w("Update", "Check failed", e)
            _state.value = if (quiet) UpdateState.Idle else UpdateState.Error(e.message ?: "Couldn't reach the update address")
        }
    }

    /** Android requires the user to allow Ohm to install apps once ("Install unknown apps"). */
    fun canInstall() = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(info: UpdateInfo) = scope.launch {
        if (!canInstall()) { openInstallPermission(); return@launch }
        try {
            val file = File(ctx.cacheDir, "update.apk")
            _state.value = UpdateState.Downloading(info, 0f)
            withContext(Dispatchers.IO) {
                download(info.apkUrl, file) { p -> _state.value = UpdateState.Downloading(info, p) }
            }
            verify(file, info)
            _state.value = UpdateState.Installing(info)
            withContext(Dispatchers.IO) { commit(file) }
        } catch (e: Exception) {
            OhmLog.w("Update", "Install failed", e)
            _state.value = UpdateState.Error(e.message ?: "Update failed")
        }
    }

    /** Same app, newer version, same signing key — before bothering the installer. */
    private fun verify(file: File, info: UpdateInfo) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("The downloaded file isn't a valid APK")
        if (archive.packageName != ctx.packageName) error("That APK isn't Ohm (${archive.packageName})")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (code <= currentVersion.first) error("The APK isn't newer than the installed version")
        if (Build.VERSION.SDK_INT >= 28) {
            val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            if (mine != null && theirs != null && mine != theirs) error("The APK is signed with a different key — refusing to install")
        }
        if (info.versionCode != code) OhmLog.w("Update", "update.json says ${info.versionCode}, APK is $code")
    }

    private fun commit(file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            file.inputStream().use { input -> s.openWrite("ohm.apk", 0, file.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(pi.intentSender)
        }
    }

    fun onResult(status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS) return // the app restarts as the new version
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) _state.value = UpdateState.Error(message ?: "Installation cancelled")
    }

    // ------------------------------------------------------------------ transport

    private fun isLocal(host: String) = runCatching {
        val a = InetAddress.getByName(host)
        a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress
    }.getOrDefault(false)

    private suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) {
        val out = java.io.ByteArrayOutputStream()
        open(url) { input, _ -> input.copyTo(out) }
        out.toByteArray()
    }

    private fun download(url: String, file: File, progress: (Float) -> Unit) {
        open(url) { input, length ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); total += n
                    if (length > 0) progress(total.toFloat() / length)
                }
            }
        }
    }

    /**
     * HTTPS anywhere; plain HTTP only to addresses on the local network (your PC). Ohm blocks
     * cleartext traffic app-wide, so the LAN case uses a minimal HTTP/1.0 GET over a socket.
     */
    private fun open(url: String, body: (InputStream, Long) -> Unit) {
        val u = URI(url)
        when {
            u.scheme == "https" -> {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 30_000
                try {
                    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                    c.inputStream.use { body(it, c.contentLengthLong) }
                } finally { c.disconnect() }
            }
            u.scheme == "http" && isLocal(u.host) -> Socket(u.host, if (u.port > 0) u.port else 80).use { s ->
                s.soTimeout = 30_000
                val path = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
                s.getOutputStream().write("GET $path HTTP/1.0\r\nHost: ${u.host}\r\nUser-Agent: Ohm\r\n\r\n".toByteArray())
                val input = s.getInputStream().buffered()
                val status = readLine(input)
                val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                var length = -1L
                while (true) {
                    val h = readLine(input); if (h.isEmpty()) break
                    if (h.startsWith("content-length:", true)) length = h.substringAfter(':').trim().toLongOrNull() ?: -1
                }
                if (code !in 200..299) error("HTTP $code")
                body(input, length)
            }
            else -> error("Use https://, or http:// to a computer on your local network")
        }
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = i.read(); if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}

/** Receives the installer's result; asks the user to confirm when Android requires it. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        Ohm.updater.onResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
