package com.ohm.launcher.obd

/**
 * ELM327 / OBD-II (SAE J1979) response parsing. Pure functions so they can be unit-tested.
 * Assumes echo, line feeds, spaces and headers are off (ATE0 ATL0 ATS0 ATH0) but copes with
 * spaces and multi-line answers anyway.
 */
object Elm {
    /** Errors the adapter answers with instead of data. */
    private val errors = listOf("NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS INIT", "BUS ERROR", "STOPPED", "?", "ERROR", "FB ERROR", "DATA ERROR")

    fun isError(raw: String): Boolean {
        val r = raw.uppercase()
        return errors.any { r.contains(it) }
    }

    /** Hex lines of a response, without prompt, "SEARCHING..." and whitespace. */
    fun lines(raw: String): List<String> = raw.uppercase()
        .replace(">", "")
        .split('\r', '\n')
        .map { it.replace("SEARCHING...", "").replace(" ", "").trim() }
        .filter { it.isNotEmpty() && it.all { c -> c.isDigit() || c in 'A'..'F' || c == ':' } }

    /**
     * Data bytes for mode 01 [pid] (e.g. "0C"), from the first ECU that answered.
     * CAN multi-frame prefixes like "0:" are tolerated.
     */
    fun mode01(raw: String, pid: String): IntArray? {
        if (isError(raw)) return null
        val key = "41" + pid.uppercase()
        for (l in lines(raw)) {
            val line = l.substringAfter(':')
            val i = line.indexOf(key)
            if (i < 0) continue
            val hex = line.substring(i + key.length)
            if (hex.length < 2) continue
            return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
        }
        return null
    }

    /** Bitmask answer of 0100 / 0120 / 0140 → supported PIDs (as "0C", "2F", …). */
    fun supported(raw: String, base: Int): Set<String> {
        val d = mode01(raw, "%02X".format(base)) ?: return emptySet()
        val out = HashSet<String>()
        for (byte in 0 until minOf(4, d.size)) for (bit in 0 until 8) {
            if (d[byte] and (0x80 shr bit) != 0) out += "%02X".format(base + byte * 8 + bit + 1)
        }
        return out
    }

    // ---- decoders (formulas from SAE J1979)
    fun rpm(d: IntArray) = (d[0] * 256 + d[1]) / 4.0
    fun speedKmh(d: IntArray) = d[0].toDouble()
    fun tempC(d: IntArray) = d[0] - 40.0
    fun percent(d: IntArray) = d[0] * 100.0 / 255
    fun voltage(d: IntArray) = (d[0] * 256 + d[1]) / 1000.0
    fun mafGs(d: IntArray) = (d[0] * 256 + d[1]) / 100.0
    fun fuelRateLph(d: IntArray) = (d[0] * 256 + d[1]) / 20.0
    /** Commanded equivalence ratio (lambda), PID 44. */
    fun lambda(d: IntArray) = (d[0] * 256 + d[1]) * 2.0 / 65536

    /**
     * Fuel flow in L/h. Uses PID 5E when the car has it; otherwise air mass (MAF) divided by the
     * air/fuel ratio and fuel density. For diesel the real ratio varies a lot, so MAF is only used
     * when the ECU also reports lambda (PID 44).
     */
    fun fuelFlowLph(fuelType: String, fuelRate: Double?, maf: Double?, lambda: Double?): Double? {
        if (fuelRate != null) return fuelRate
        maf ?: return null
        val (stoich, density) = when (fuelType) {
            "diesel" -> 14.5 to 832.0
            "lpg" -> 15.5 to 540.0
            else -> 14.7 to 745.0
        }
        val l = lambda ?: if (fuelType == "diesel") return null else 1.0
        return maf * 3600.0 / (stoich * l * density)
    }

    /** Mode 03 answer → trouble codes like "P0133". Handles CAN (count byte) and older protocols. */
    fun dtcs(raw: String): List<String> {
        if (isError(raw)) return emptyList()
        val out = ArrayList<String>()
        for (l in lines(raw)) {
            var hex = l.substringAfter(':')
            val i = hex.indexOf("43"); if (i < 0) continue
            hex = hex.substring(i + 2)
            if ((hex.length / 2) % 2 == 1) hex = hex.substring(2) // CAN: first byte is the number of codes
            for (k in 0 until hex.length / 4) {
                val a = hex.substring(k * 4, k * 4 + 2).toInt(16)
                val b = hex.substring(k * 4 + 2, k * 4 + 4).toInt(16)
                if (a == 0 && b == 0) continue
                val letter = "PCBU"[(a shr 6) and 3]
                out += "%c%d%X%02X".format(letter, (a shr 4) and 3, a and 0x0F, b)
            }
        }
        return out.distinct()
    }

    /** A few common meanings; anything else links to a web search in the UI. */
    val common = mapOf(
        "P0100" to "Mass air flow sensor circuit", "P0101" to "Mass air flow sensor range/performance",
        "P0115" to "Coolant temperature sensor circuit", "P0130" to "O2 sensor circuit (bank 1, sensor 1)",
        "P0133" to "O2 sensor slow response (bank 1, sensor 1)", "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)", "P0300" to "Random/multiple cylinder misfire",
        "P0301" to "Cylinder 1 misfire", "P0302" to "Cylinder 2 misfire", "P0303" to "Cylinder 3 misfire",
        "P0304" to "Cylinder 4 misfire", "P0380" to "Glow plug circuit", "P0400" to "EGR flow",
        "P0401" to "EGR flow insufficient", "P0420" to "Catalyst efficiency below threshold",
        "P0441" to "Evaporative emission purge flow", "P0500" to "Vehicle speed sensor",
        "P0562" to "System voltage low", "P2002" to "Diesel particulate filter efficiency below threshold",
        "P2463" to "Diesel particulate filter soot accumulation",
    )
}
