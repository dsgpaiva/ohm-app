package com.ohm.companion

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Ohm Companion protocol (v1): small JSON datagrams on UDP 47017, sent to every local broadcast
 * address (phone hotspot / shared Wi-Fi) and optionally to a fixed head-unit IP.
 *   {"ohm":1,"code":"1234","type":"loc","lat":..,"lon":..,"spd":m/s,"brg":deg,"acc":m,"time":epochMs,"dev":..,"bat":..,"net":..}
 *   {"ohm":1,"code":"1234","type":"hello",...}          every 5 s, also without a fix
 *   {"ohm":1,"code":"1234","type":"notif","app":..,"title":..,"text":..}
 * The head unit answers with {"ohm":1,"type":"ack","name":"Ohm","vehicle":..}.
 * The pairing code only stops accidental cross-talk; it is not encryption.
 */
data class LinkState(
    val running: Boolean = false,
    val connectedTo: String? = null,
    val lastAckAt: Long = 0,
    val lastFix: Location? = null,
    val sent: Int = 0,
    val error: String? = null,
)

object Link {
    const val PORT = 47017
    val state = MutableStateFlow(LinkState())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE)
    fun code(ctx: Context) = prefs(ctx).getString("code", "") ?: ""
    fun setCode(ctx: Context, v: String) = prefs(ctx).edit().putString("code", v).apply()
    fun manualIp(ctx: Context) = prefs(ctx).getString("ip", "") ?: ""
    fun setManualIp(ctx: Context, v: String) = prefs(ctx).edit().putString("ip", v).apply()
    fun relayNotifications(ctx: Context) = prefs(ctx).getBoolean("relay", false)
    /** The relay service is disabled in the manifest and only enabled while the user wants it. */
    fun setRelay(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("relay", v).apply()
        ctx.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(ctx, NotificationRelay::class.java),
            if (v) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    @Volatile var socket: DatagramSocket? = null

    fun base(ctx: Context, type: String) = JSONObject()
        .put("ohm", 1).put("code", code(ctx)).put("type", type)
        .put("dev", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        .put("bat", battery(ctx)).put("net", network(ctx))

    private fun battery(ctx: Context) = runCatching {
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun network(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "Other"
        }
    }.getOrDefault("?")

    /** All IPv4 broadcast addresses of up interfaces (hotspot, Wi-Fi) + the manual IP. */
    fun targets(ctx: Context): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
            }
        }
        manualIp(ctx).takeIf { it.isNotBlank() }?.let { ip -> runCatching { out += InetAddress.getByName(ip) } }
        if (out.isEmpty()) runCatching { out += InetAddress.getByName("255.255.255.255") }
        return out.distinct()
    }

    /** Must be called off the main thread. */
    fun send(ctx: Context, o: JSONObject) {
        val s = socket ?: return
        val bytes = o.toString().toByteArray()
        for (t in targets(ctx)) runCatching { s.send(DatagramPacket(bytes, bytes.size, t, PORT)) }
        state.update { it.copy(sent = it.sent + 1) }
    }
}

/** Foreground service: reads phone GPS and streams it to the head unit once a second. */
class LinkService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        try { startForegroundNotice() } catch (_: StopStart) { return START_NOT_STICKY }
        if (Link.socket == null) start()
        return START_STICKY
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("link", "Sharing with Ohm", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "link")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing location with Ohm")
            .setContentText("Your head unit receives GPS from this phone")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
        } catch (e: Exception) {
            // Started from the background without "Allow all the time" location: ask for one tap instead.
            Auto.notifyTapToStart(this)
            stopSelf()
            throw StopStart()
        }
    }

    private class StopStart : RuntimeException()

    @SuppressLint("MissingPermission")
    private fun start() {
        runCatching { lock = getSystemService(WifiManager::class.java).createMulticastLock("ohm-link").apply { setReferenceCounted(false); acquire() } }
        val sock = runCatching { DatagramSocket().apply { broadcast = true; soTimeout = 1000 } }.getOrElse {
            Link.state.update { s -> s.copy(error = it.message) }; stopSelf(); return
        }
        Link.socket = sock
        Link.state.update { it.copy(running = true, error = null) }

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LocationManager::class.java)
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) }
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this, Looper.getMainLooper()) }
        }
        // hello / keep-alive
        scope.launch { while (isActive) { Link.send(this@LinkService, Link.base(this@LinkService, "hello")); delay(5000) } }
        // acknowledgements from the head unit
        scope.launch {
            val buf = ByteArray(1024)
            while (isActive) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                val o = runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull() ?: continue
                if (o.optString("type") == "ack") Link.state.update {
                    it.copy(connectedTo = o.optString("vehicle").ifEmpty { o.optString("name", "Ohm") } + " (" + p.address.hostAddress + ")", lastAckAt = SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private var lastGps = 0L

    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) lastGps = SystemClock.elapsedRealtime()
        else if (SystemClock.elapsedRealtime() - lastGps < 10_000) return
        Link.state.update { it.copy(lastFix = location) }
        val o = Link.base(this, "loc").put("lat", location.latitude).put("lon", location.longitude).put("time", location.time)
        if (location.hasSpeed()) o.put("spd", location.speed.toDouble())
        if (location.hasBearing()) o.put("brg", location.bearing.toDouble())
        if (location.hasAccuracy()) o.put("acc", location.accuracy.toDouble())
        scope.launch { Link.send(this@LinkService, o) }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    override fun onDestroy() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        scope.cancel()
        Link.socket?.close(); Link.socket = null
        runCatching { lock?.release() }
        Link.state.update { it.copy(running = false, connectedTo = null) }
        super.onDestroy()
    }

    companion object { const val ACTION_STOP = "com.ohm.companion.STOP" }
}

/** Optional: forwards phone notifications (title + text) to the head unit while sharing is on. */
class NotificationRelay : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Link.relayNotifications(this) || Link.socket == null) return
        val n = sbn.notification ?: return
        if (sbn.isOngoing || sbn.packageName == packageName || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val o = Link.base(this, "notif").put("app", app).put("title", title.take(80)).put("text", text.take(200))
        scope.launch { Link.send(this@NotificationRelay, o) }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
