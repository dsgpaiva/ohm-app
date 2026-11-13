package com.ohm.launcher.car

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a head unit's firmware reports car data, as discovered with tools/headunit (or by hand).
 *
 * {"rules":[
 *   {"field":"headlights","action":"com.vendor.ILL","extra":"state","on":"1"},
 *   {"field":"reverse","setting":"system/back_car","on":"1"},
 *   {"field":"gear","action":"com.vendor.GEAR","extra":"gear","values":{"P":"0","R":"1","N":"2","D":"3"}},
 *   {"field":"speed","action":"com.vendor.SPEED","extra":"speed","scale":0.01},
 *   {"field":"door_fl","action":"com.vendor.DOOR_FL_OPEN","value":"1"}      // action alone = "on"
 * ]}
 *
 * Fields: headlights, reverse, gear, speed, outside_temp, locked, door_fl, door_fr, door_rl, door_rr, trunk, hood.
 */
data class VendorRule(
    val field: String,
    val action: String? = null,
    val extra: String? = null,
    /** "system/key", "global/key" or "secure/key" (Android settings some firmwares write state to). */
    val setting: String? = null,
    /** Raw value meaning "on/true"; if null any non-zero / "true" value counts. */
    val on: String? = null,
    /** Constant used when the action has no extra (e.g. separate ON and OFF actions). */
    val value: String? = null,
    /** gear only: P/R/N/D → raw value */
    val values: Map<String, String> = emptyMap(),
    val scale: Double = 1.0,
    val offset: Double = 0.0,
) {
    fun json(): JSONObject = JSONObject().put("field", field).apply {
        action?.let { put("action", it) }; extra?.let { put("extra", it) }; setting?.let { put("setting", it) }
        on?.let { put("on", it) }; value?.let { put("value", it) }
        if (values.isNotEmpty()) put("values", JSONObject(values))
        if (scale != 1.0) put("scale", scale); if (offset != 0.0) put("offset", offset)
    }
}

object VendorMap {
    val fields = listOf("headlights", "reverse", "gear", "speed", "outside_temp", "locked", "door_fl", "door_fr", "door_rl", "door_rr", "trunk", "hood")

    fun parse(json: String): List<VendorRule> {
        if (json.isBlank()) return emptyList()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val a = o.optJSONArray("rules") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val field = r.optString("field")
            if (field !in fields) return@mapNotNull null
            val vals = r.optJSONObject("values")?.let { v -> v.keys().asSequence().associateWith { v.optString(it) } }.orEmpty()
            VendorRule(field, r.str("action"), r.str("extra"), r.str("setting"), r.str("on"), r.str("value"), vals,
                r.optDouble("scale", 1.0), r.optDouble("offset", 0.0))
        }.filter { it.action != null || it.setting != null }
    }

    private fun JSONObject.str(k: String) = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null

    fun truthy(raw: String, on: String?): Boolean =
        if (on != null) raw == on else raw == "1" || raw.equals("true", true) || raw.equals("on", true) || (raw.toDoubleOrNull()?.let { it != 0.0 } ?: false)

    /** Applies one raw value to the car state. */
    fun apply(rule: VendorRule, raw: String, s: CarState): CarState {
        val num = { raw.toDoubleOrNull()?.let { (it * rule.scale + rule.offset).toFloat() } }
        val b = { truthy(raw, rule.on) }
        return when (rule.field) {
            "headlights" -> s.copy(headlights = b())
            "reverse" -> if (b()) s.copy(gear = Gear.R) else s.copy(gear = if (s.gear == Gear.R) Gear.D else s.gear ?: Gear.D)
            "gear" -> (rule.values.entries.firstOrNull { it.value == raw }?.key ?: raw.uppercase())
                .let { g -> Gear.entries.firstOrNull { it.name == g } }?.let { s.copy(gear = it) } ?: s
            "speed" -> num()?.let { s.copy(speedKmh = it) } ?: s
            "outside_temp" -> num()?.let { s.copy(outsideTempC = it) } ?: s
            "locked" -> s.copy(locked = b())
            "door_fl" -> s.copy(doors = s.doors.copy(fl = b()))
            "door_fr" -> s.copy(doors = s.doors.copy(fr = b()))
            "door_rl" -> s.copy(doors = s.doors.copy(rl = b()))
            "door_rr" -> s.copy(doors = s.doors.copy(rr = b()))
            "trunk" -> s.copy(doors = s.doors.copy(trunk = b()))
            "hood" -> s.copy(doors = s.doors.copy(hood = b()))
            else -> s
        }
    }
}
