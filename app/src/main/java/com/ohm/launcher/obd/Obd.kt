package com.ohm.launcher.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.OhmLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class ObdData(
    /** off | no_permission | no_device | connecting | initialising | connected | error */
    val status: String = "off",
    val detail: String = "",
    val protocol: String? = null,
    val supported: Set<String> = emptySet(),
    val rpm: Double? = null,
    val speedKmh: Double? = null,
    val coolantC: Double? = null,
    val fuelPct: Double? = null,
    val batteryV: Double? = null,
    val loadPct: Double? = null,
    val ambientC: Double? = null,
    val intakeC: Double? = null,
    /** Fuel flow in L/h (measured or derived from MAF). */
    val fuelLph: Double? = null,
    val updatedAt: Long = 0,
) {
    val connected get() = status == "connected"
    /** Instant consumption in L/100 km (null when standing still). */
    val lPer100: Double? get() { val s = speedKmh ?: return null; val f = fuelLph ?: return null; return if (s > 5) f / s * 100 else null }
}

/**
 * OBD-II through an ELM327-compatible Bluetooth adapter (Classic Bluetooth, SPP).
 * Pair the adapter in Android's Bluetooth settings first, then choose it in Controls › Vehicle.
 */
class ObdManager(private val ctx: Context, private val scope: CoroutineScope) {
    private val _data = MutableStateFlow(ObdData())
    val data: StateFlow<ObdData> = _data.asStateFlow()
    private val _dtcs = MutableStateFlow<List<String>?>(null)
    val dtcs: StateFlow<List<String>?> = _dtcs.asStateFlow()

    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val io = Mutex()

    fun init() {
        scope.launch {
            kotlinx.coroutines.flow.combine(Ohm.prefs.obdEnabled.state, Ohm.prefs.obdDevice.state) { on, dev -> on to dev }
                .collect { (on, dev) -> if (on && dev.isNotEmpty()) start() else stop(if (on) "no_device" else "off") }
        }
    }

    fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<Pair<String, String>> = runCatching {
        if (!hasPermission()) return emptyList()
        ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .map { it.address to (it.name ?: it.address) }
            // OBD adapters usually call themselves OBDII / OBD2 / ELM327 / Vgate / V-LINK …
            .sortedByDescending { (_, n) -> listOf("obd", "elm", "vgate", "v-link", "vlink", "konnwei", "icar").any { n.lowercase().contains(it) } }
    }.getOrDefault(emptyList())

    private fun set(status: String, detail: String = "") = _data.update { it.copy(status = status, detail = detail) }

    fun start() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            var backoff = 3000L
            while (isActive) {
                if (!hasPermission()) { set("no_permission", "Allow \"Nearby devices\" for Ohm"); delay(10_000); continue }
                try {
                    connect()
                    initialise()
                    backoff = 3000
                    poll()
                } catch (e: Exception) {
                    if (!isActive) break
                    OhmLog.w("OBD", "Connection lost", e)
                    set("error", e.message ?: "Connection failed — retrying")
                }
                closeSocket()
                delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    fun stop(status: String = "off") {
        job?.cancel(); job = null
        closeSocket()
        _data.value = ObdData(status = status)
        pushToCar(ObdData())
    }

    private fun closeSocket() { runCatching { socket?.close() }; socket = null; input = null; output = null }

    @SuppressLint("MissingPermission")
    private fun connect() {
        set("connecting", Ohm.prefs.obdDeviceName.value)
        val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter ?: error("This unit has no Bluetooth")
        if (!adapter.isEnabled) error("Bluetooth is off")
        runCatching { adapter.cancelDiscovery() }
        val dev = adapter.getRemoteDevice(Ohm.prefs.obdDevice.value)
        val s = try {
            dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: Exception) {
            // Many cheap clones only accept an insecure RFCOMM connection.
            dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        }
        socket = s; input = s.inputStream; output = s.outputStream
        OhmLog.i("OBD", "Connected to ${dev.name ?: dev.address}")
    }

    /** Sends one command and returns the text up to the ">" prompt. */
    private suspend fun cmd(c: String, timeoutMs: Long = 2000): String = io.withLock {
        withContext(Dispatchers.IO) {
            val out = output ?: error("Not connected"); val inp = input ?: error("Not connected")
            while (inp.available() > 0) inp.read() // drop stale bytes
            out.write((c + "\r").toByteArray()); out.flush()
            val sb = StringBuilder()
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (inp.available() > 0) {
                    val ch = inp.read()
                    if (ch < 0) error("Adapter closed the connection")
                    if (ch == '>'.code) return@withContext sb.toString()
                    sb.append(ch.toChar())
                } else Thread.sleep(10)
            }
            error("Adapter didn't answer \"$c\"")
        }
    }

    private suspend fun initialise() {
        set("initialising", "Talking to the car…")
        cmd("ATZ", 3000); delay(800)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) cmd(c)
        // The first request makes the adapter search for the car's protocol (can take ~10 s).
        val first = cmd("0100", 12_000)
        if (Elm.isError(first)) error("No answer from the car — is the ignition on?")
        val sup = HashSet<String>()
        sup += Elm.supported(first, 0x00)
        if ("20" in sup) sup += Elm.supported(cmd("0120", 4000), 0x20)
        if ("40" in sup) sup += Elm.supported(cmd("0140", 4000), 0x40)
        val proto = runCatching { cmd("ATDP").trim() }.getOrNull()
        _data.update { it.copy(status = "connected", detail = "", protocol = proto, supported = sup) }
        OhmLog.i("OBD", "Protocol $proto, ${sup.size} PIDs supported")
    }

    private suspend fun read(pid: String): IntArray? =
        if (pid in _data.value.supported) Elm.mode01(cmd("01$pid"), pid) else null

    private suspend fun poll() {
        var cycle = 0
        val fuelType = { Ohm.prefs.fuelType.value }
        while (true) {
            // Fast: every cycle (~3–5 per second on a good adapter)
            val rpm = read("0C")?.let(Elm::rpm)
            val speed = read("0D")?.let(Elm::speedKmh)
            val rate = read("5E")?.let(Elm::fuelRateLph)
            val maf = if (rate == null) read("10")?.let(Elm::mafGs) else null
            var d = _data.value.copy(rpm = rpm, speedKmh = speed, updatedAt = System.currentTimeMillis())
            // Slow: every 8th cycle
            if (cycle % 8 == 0) {
                d = d.copy(
                    coolantC = read("05")?.let(Elm::tempC),
                    fuelPct = read("2F")?.let(Elm::percent),
                    batteryV = read("42")?.let(Elm::voltage),
                    loadPct = read("04")?.let(Elm::percent),
                    ambientC = read("46")?.let(Elm::tempC),
                    intakeC = read("0F")?.let(Elm::tempC),
                )
                lambda = read("44")?.let(Elm::lambda)
            }
            d = d.copy(fuelLph = Elm.fuelFlowLph(fuelType(), rate, maf, lambda))
            _data.value = d
            pushToCar(d)
            cycle++
            delay(150)
        }
    }

    private var lambda: Double? = null

    /** Merge OBD values into the car state (speed only when the car source has none). */
    private fun pushToCar(d: ObdData) {
        Ohm.car.update { s ->
            s.copy(
                rpm = d.rpm, coolantC = d.coolantC, fuelPct = d.fuelPct, batteryV = d.batteryV, fuelLph = d.fuelLph,
                speedKmh = if (Ohm.car.sourceId.value != "demo" && d.speedKmh != null) d.speedKmh.toFloat() else s.speedKmh,
                outsideTempC = d.ambientC?.toFloat() ?: s.outsideTempC,
            )
        }
    }

    fun readDtcs() = scope.launch {
        if (!_data.value.connected) return@launch
        _dtcs.value = runCatching { Elm.dtcs(cmd("03", 5000)) }.getOrElse { OhmLog.w("OBD", "DTC read", it); emptyList() }
    }

    /** Mode 04: clears stored codes and turns off the check-engine light (also resets readiness monitors). */
    fun clearDtcs() = scope.launch {
        if (!_data.value.connected) return@launch
        runCatching { cmd("04", 5000) }.onFailure { OhmLog.w("OBD", "DTC clear", it) }
        OhmLog.i("OBD", "Trouble codes cleared")
        _dtcs.value = runCatching { Elm.dtcs(cmd("03", 5000)) }.getOrDefault(emptyList())
    }

    companion object { private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") }
}
