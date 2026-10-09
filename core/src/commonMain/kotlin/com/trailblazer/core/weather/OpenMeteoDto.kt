package com.trailblazer.core.weather

import com.trailblazer.core.math.mod360
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OmCurrent(
    val time: String? = null,
    @SerialName("temperature_2m") val temperatureC: Double? = null,
    @SerialName("relative_humidity_2m") val humidityPct: Double? = null,
    @SerialName("apparent_temperature") val apparentC: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("wind_speed_10m") val windKmh: Double? = null,
    @SerialName("wind_direction_10m") val windDirDeg: Double? = null,
    @SerialName("wind_gusts_10m") val gustKmh: Double? = null,
    @SerialName("pressure_msl") val pressureMslHpa: Double? = null,
    val precipitation: Double? = null,
)

@Serializable
data class OmHourly(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperatureC: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val precipProbPct: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
)

@Serializable
data class OmDaily(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val maxC: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val minC: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipProbPct: List<Double?> = emptyList(),
    @SerialName("precipitation_sum") val precipMm: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m_max") val windMaxKmh: List<Double?> = emptyList(),
    @SerialName("uv_index_max") val uvMax: List<Double?> = emptyList(),
)

@Serializable
data class OmResponse(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    /** The place's offset from UTC; hourly and daily times are wall-clock times there. */
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int? = null,
    val current: OmCurrent? = null,
    val hourly: OmHourly? = null,
    val daily: OmDaily? = null,
)

/** Real zones run from UTC−12 to UTC+14; ±18 h is java.time's own bound. */
private const val MAX_UTC_OFFSET_S = 18 * 3_600

/**
 * Physical plausibility at the network boundary. A value no weather station can report (500 % humidity, a
 * temperature of 10³⁰⁰ °C, a negative wind speed, a weather code outside WMO 0–99) becomes null, which the screen
 * shows as not reported, instead of being displayed. Wind direction is normalised into [0, 360).
 */
fun OmResponse.sanitized(): OmResponse {
    fun Double?.within(r: ClosedFloatingPointRange<Double>) = this?.takeIf { it.isFinite() && it in r }
    fun List<Double?>.within(r: ClosedFloatingPointRange<Double>) = map { it.within(r) }
    fun Int?.wmo() = this?.takeIf { it in 0..99 }
    val temp = -95.0..65.0
    val pct = 0.0..100.0
    val wind = 0.0..500.0
    val rain = 0.0..2_000.0
    return copy(
        latitude = latitude.within(-90.0..90.0),
        longitude = longitude.within(-180.0..180.0),
        utcOffsetSeconds = utcOffsetSeconds?.takeIf { it in -MAX_UTC_OFFSET_S..MAX_UTC_OFFSET_S },
        current = current?.let { c ->
            c.copy(
                temperatureC = c.temperatureC.within(temp),
                humidityPct = c.humidityPct.within(pct),
                apparentC = c.apparentC.within(temp),
                weatherCode = c.weatherCode.wmo(),
                windKmh = c.windKmh.within(wind),
                windDirDeg = c.windDirDeg?.takeIf { it.isFinite() }?.let { mod360(it) },
                gustKmh = c.gustKmh.within(wind),
                pressureMslHpa = c.pressureMslHpa.within(850.0..1_090.0),
                precipitation = c.precipitation.within(rain),
            )
        },
        hourly = hourly?.let { h ->
            h.copy(temperatureC = h.temperatureC.within(temp), precipProbPct = h.precipProbPct.within(pct), weatherCode = h.weatherCode.map { it.wmo() })
        },
        daily = daily?.let { d ->
            d.copy(
                weatherCode = d.weatherCode.map { it.wmo() },
                maxC = d.maxC.within(temp),
                minC = d.minC.within(temp),
                precipProbPct = d.precipProbPct.within(pct),
                precipMm = d.precipMm.within(rain),
                windMaxKmh = d.windMaxKmh.within(wind),
                uvMax = d.uvMax.within(0.0..20.0),
            )
        },
    )
}

/** WMO weather interpretation codes used by Open-Meteo. */
object WmoCode {
    fun describe(code: Int?): String = when (code) {
        null -> "—"
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Code $code"
    }
}
