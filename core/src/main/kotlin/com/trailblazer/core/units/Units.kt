package com.trailblazer.core.units

/** Unit systems and conversions. Values are always stored in SI; conversion happens only for display. */
enum class UnitSystem { Metric, Imperial }

enum class SpeedUnit(val symbol: String, val perMps: Double) {
    KmH("km/h", 3.6), Mph("mph", 2.2369362920544), Knots("kn", 1.9438444924406), Mps("m/s", 1.0);

    fun fromMps(v: Double) = v * perMps
    fun toMps(v: Double) = v / perMps
}

enum class PressureUnit(val symbol: String, val perHpa: Double) {
    Hpa("hPa", 1.0), InHg("inHg", 0.0295299830714), MmHg("mmHg", 0.750061683);

    fun fromHpa(v: Double) = v * perHpa
    fun toHpa(v: Double) = v / perHpa
}

enum class TemperatureUnit(val symbol: String) {
    Celsius("°C"), Fahrenheit("°F");

    fun fromC(c: Double) = if (this == Celsius) c else c * 9.0 / 5.0 + 32.0
    fun toC(v: Double) = if (this == Celsius) v else (v - 32.0) * 5.0 / 9.0
}

object Length {
    const val M_PER_FT = 0.3048
    const val M_PER_MI = 1609.344

    fun elevation(m: Double, system: UnitSystem): Pair<Double, String> =
        if (system == UnitSystem.Metric) m to "m" else m / M_PER_FT to "ft"

    /** Distance with a unit that keeps the number readable (m→km, ft→mi). */
    fun distance(m: Double, system: UnitSystem): Pair<Double, String> = when (system) {
        UnitSystem.Metric -> if (m < 1000) m to "m" else m / 1000 to "km"
        UnitSystem.Imperial -> {
            val ft = m / M_PER_FT
            if (ft < 1000) ft to "ft" else m / M_PER_MI to "mi"
        }
    }
}
