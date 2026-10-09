package com.example.trailblazer.weather

import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.weather.OmCurrent
import com.trailblazer.core.weather.OmDaily
import com.trailblazer.core.weather.OmHourly
import com.trailblazer.core.weather.OmResponse
import com.trailblazer.core.weather.WmoCode
import com.trailblazer.core.weather.sanitized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** The one network seam in the app. Tests replace it to prove nothing is sent before consent. */
fun interface HttpTransport {
    /** Performs a GET and returns the body, or throws IOException. */
    suspend fun get(url: String): String
}

/** HTTPS GET with bounded timeouts and a bounded response size. No cookies, no identifiers, no retries. */
class UrlConnectionTransport(private val maxBytes: Int = 512 * 1024) : HttpTransport {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "only https is allowed" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("response too large")
                }
                out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }
}

typealias OmCurrent = com.trailblazer.core.weather.OmCurrent
typealias OmHourly = com.trailblazer.core.weather.OmHourly
typealias OmDaily = com.trailblazer.core.weather.OmDaily
typealias OmResponse = com.trailblazer.core.weather.OmResponse
typealias WmoCode = com.trailblazer.core.weather.WmoCode

data class Forecast(val response: OmResponse, val fetchedMs: Long, val requestedFor: LatLon)

sealed interface ForecastResult {
    data object NotConsented : ForecastResult
    data class Ok(val forecast: Forecast, val fromCache: Boolean) : ForecastResult
    data class Failed(val message: String) : ForecastResult
}

/**
 * Open-Meteo forecast (free, no key, CC BY 4.0, non-commercial use). Called only after the user has
 * opted in; coordinates are rounded to 0.01° (~1 km) before they leave the phone; results are cached
 * for 30 minutes per rounded location.
 */
class OpenMeteoClient(private val http: HttpTransport, private val clock: Clock) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }
    private val mutex = Mutex()
    private var cache: Forecast? = null

    fun url(p: LatLon): String = String.format(
        Locale.ROOT,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,precipitation" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,uv_index_max" +
            "&timezone=auto&forecast_days=3&forecast_hours=12",
        p.lat, p.lon,
    )

    private fun rounded(p: LatLon) = LatLon(Math.round(p.lat * 100) / 100.0, Math.round(p.lon * 100) / 100.0)

    suspend fun forecast(position: LatLon, consented: Boolean, force: Boolean = false): ForecastResult {
        if (!consented) return ForecastResult.NotConsented
        val key = rounded(position)
        return mutex.withLock {
            val c = cache
            if (!force && c != null && c.requestedFor == key && clock.nowMs() - c.fetchedMs < CACHE_MS) {
                return@withLock ForecastResult.Ok(c, fromCache = true)
            }
            try {
                val body = http.get(url(key))
                val parsed = json.decodeFromString(OmResponse.serializer(), body).sanitized()
                val f = Forecast(parsed, clock.nowMs(), key)
                cache = f
                ForecastResult.Ok(f, fromCache = false)
            } catch (e: IOException) {
                ForecastResult.Failed(e.message ?: "network error")
            } catch (e: SerializationException) {
                ForecastResult.Failed("unexpected response")
            } catch (e: IllegalArgumentException) {
                ForecastResult.Failed(e.message ?: "bad request")
            }
        }
    }

    fun clearCache() {
        cache = null
    }

    companion object {
        const val CACHE_MS = 30 * 60_000L
        const val ATTRIBUTION = "Weather data by Open-Meteo.com (CC BY 4.0)"
        const val ATTRIBUTION_URL = "https://open-meteo.com/"
    }
}

