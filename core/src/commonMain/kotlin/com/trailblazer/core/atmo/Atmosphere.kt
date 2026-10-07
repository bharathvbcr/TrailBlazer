package com.trailblazer.core.atmo

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * International Standard Atmosphere (troposphere, 0–11 km) and related field formulas.
 * Every function returns null for physically invalid input instead of a plausible-looking default.
 */
object Isa {
    const val SEA_LEVEL_HPA = 1013.25
    const val SEA_LEVEL_K = 288.15
    const val LAPSE_K_PER_M = 0.0065
    private const val EXP = 0.190263 // R·L / (g·M)
    private const val SCALE_M = 44330.77 // T0 / L

    private fun validPressure(p: Double) = p.isFinite() && p in 1.0..1200.0

    /** Altitude (m) at which station pressure [pHpa] occurs given sea-level pressure [qnhHpa]. */
    fun altitudeM(pHpa: Double, qnhHpa: Double = SEA_LEVEL_HPA): Double? {
        if (!validPressure(pHpa) || !validPressure(qnhHpa)) return null
        return SCALE_M * (1 - (pHpa / qnhHpa).pow(EXP))
    }

    /** Pressure altitude: altitude in the standard atmosphere (QNH 1013.25). */
    fun pressureAltitudeM(pHpa: Double): Double? = altitudeM(pHpa)

    /** Sea-level pressure (QNH) from station pressure at a known elevation. */
    fun qnhHpa(pHpa: Double, elevationM: Double): Double? {
        if (!validPressure(pHpa) || !elevationM.isFinite() || elevationM < -500 || elevationM > 11_000) return null
        return pHpa / (1 - elevationM / SCALE_M).pow(1 / EXP)
    }

    /** Standard temperature (°C) at a pressure altitude. */
    fun standardTempC(pressureAltitudeM: Double): Double = 15.0 - LAPSE_K_PER_M * pressureAltitudeM
}

object DensityAltitude {
    private const val R_DRY = 287.05
    private const val RHO0 = 1.225

    /**
     * Density altitude (m) from station pressure and outside air temperature. Returns null when the
     * temperature is unknown: substituting the standard temperature (what the web app did) always
     * yields the pressure altitude and hides exactly the hot-day effect this number exists to show.
     */
    fun meters(pHpa: Double, outsideAirTempC: Double?): Double? {
        if (outsideAirTempC == null || !outsideAirTempC.isFinite() || outsideAirTempC < -90 || outsideAirTempC > 60) return null
        if (!pHpa.isFinite() || pHpa !in 100.0..1200.0) return null
        val rho = pHpa * 100 / (R_DRY * (outsideAirTempC + 273.15))
        return 44330.77 * (1 - (rho / RHO0).pow(0.234969))
    }
}

object AirDensity {
    /** Dry-air density (kg/m³); null without a real temperature. */
    fun kgPerM3(pHpa: Double, tempC: Double?): Double? {
        if (tempC == null || !tempC.isFinite() || tempC < -90 || tempC > 60) return null
        if (!pHpa.isFinite() || pHpa !in 100.0..1200.0) return null
        return pHpa * 100 / (287.05 * (tempC + 273.15))
    }
}

object BoilingPoint {
    /** Boiling point of water (°C) at pressure, from the Antoine equation (valid 1–100 °C, roughly 7–1013 hPa and a bit above). */
    fun celsius(pHpa: Double): Double? {
        if (!pHpa.isFinite() || pHpa !in 10.0..1200.0) return null
        val mmHg = pHpa * 0.750061683
        return 1730.63 / (8.07131 - log10(mmHg)) - 233.426
    }
}

object DewPoint {
    /** Magnus–Tetens dew point (°C), Alduchov & Eskridge constants; null for invalid input. */
    fun celsius(tempC: Double, relativeHumidityPct: Double): Double? {
        if (!tempC.isFinite() || tempC !in -45.0..60.0) return null
        if (!relativeHumidityPct.isFinite() || relativeHumidityPct <= 0.0 || relativeHumidityPct > 100.0) return null
        val a = 17.625
        val b = 243.04
        val g = ln(relativeHumidityPct / 100.0) + a * tempC / (b + tempC)
        return b * g / (a - g)
    }

    /** Relative humidity (%) from temperature and dew point — the inverse, used in tests. */
    fun relativeHumidity(tempC: Double, dewPointC: Double): Double {
        val a = 17.625
        val b = 243.04
        return 100 * exp(a * dewPointC / (b + dewPointC) - a * tempC / (b + tempC))
    }
}
