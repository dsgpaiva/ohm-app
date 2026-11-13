package com.ohm.launcher

import android.Manifest
import android.os.Bundle
import android.view.FrameMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ohm.launcher.ui.Root
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val locationPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Ohm.location.startUnitGps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            Ohm.prefs.hideSystemBars.state.collect { hide -> applyBars(hide) }
        }
        watchFrameTimes()

        if (!Ohm.nav.hasLocationPermission()) {
            locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        setContent { Root(slowFrames) }
    }

    private val btPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** "Nearby devices" (Android 12+) for the OBD-II adapter. */
    fun requestBluetooth() { if (android.os.Build.VERSION.SDK_INT >= 31) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) }

    fun requestLocation() =
        locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    /** Screen brightness for this window only (0..1, or -1 for system default). */
    fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    private fun applyBars(hide: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars(Ohm.prefs.hideSystemBars.value)
    }

    override fun onResume() {
        super.onResume()
        Ohm.media.refreshAccess()
        Ohm.apps.reload()
        Ohm.caps.refresh()
    }

    /** True when frames have been slow (> 25 ms average) for ~5 s → auto low-power mode (SPEC.md §10). */
    val slowFrames = MutableStateFlow(false)

    private fun watchFrameTimes() {
        val samples = ArrayDeque<Long>()
        var slowSince = 0L
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            val ms = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
            samples.addLast(ms)
            if (samples.size > 60) samples.removeFirst()
            if (samples.size < 30) return@addOnFrameMetricsAvailableListener
            val avg = samples.average()
            com.ohm.launcher.core.Perf.frameMs.value = avg
            val now = System.currentTimeMillis()
            if (avg > 25) {
                if (slowSince == 0L) slowSince = now
                if (now - slowSince > 5000 && !slowFrames.value) { slowFrames.value = true; com.ohm.launcher.core.Perf.slow.value = true; com.ohm.launcher.core.OhmLog.w("Perf", "Slow frames (avg ${avg.toInt()} ms) — low-power mode") }
            } else slowSince = 0L
        }, android.os.Handler(mainLooper))
    }

    init {
        // A launcher never leaves Home on Back; Compose BackHandlers (added later) close cards first.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }
}
