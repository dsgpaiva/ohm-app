package com.ohm.launcher.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.OhmLog

/**
 * Lets a laptop configure Ohm's car mapping over ADB (tools/headunit/ohm_hu.py push-map):
 *
 *   adb shell am broadcast -n com.ohm.launcher/.car.ConfigReceiver -a com.ohm.launcher.SET_VENDOR_MAP \
 *       --es map64 <base64 JSON> [--es actions "a,b,c"] [--ez use true]
 *
 * Protected by android.permission.DUMP in the manifest, which only the shell (ADB) and the
 * system hold — ordinary apps can't send it.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val json = intent.getStringExtra("map64")?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }
            ?: intent.getStringExtra("map")
        val rules = json?.let { VendorMap.parse(it) }
        if (json != null && rules.isNullOrEmpty() && json.isNotBlank()) {
            OhmLog.w("Config", "Vendor map from ADB had no valid rules")
            resultData = "error: no valid rules"
            return
        }
        json?.let { Ohm.prefs.vendorMap.set(it) }
        intent.getStringExtra("actions")?.let { Ohm.prefs.vendorActions.set(it) }
        if (intent.getBooleanExtra("use", true)) Ohm.prefs.carSource.set("vendor")
        val msg = "Car mapping updated over ADB: ${rules?.size ?: 0} rule(s)"
        OhmLog.i("Config", msg)
        Ohm.banners.info("Ohm", msg)
        resultData = "ok: ${rules?.size ?: 0} rules"
    }

    companion object { const val ACTION = "com.ohm.launcher.SET_VENDOR_MAP" }
}
