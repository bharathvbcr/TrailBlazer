package com.example.trailblazer.weather

import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenMeteoClientTest {
    /** Trimmed from a real api.open-meteo.com response captured 2026-09-29. */
    private val sample = """{"latitude":46.54,"longitude":7.819999,"generationtime_ms":2.57,"utc_offset_seconds":7200,"timezone":"Europe/Zurich",
        "current":{"time":"2026-09-29T22:45","interval":900,"temperature_2m":7.6,"relative_humidity_2m":63,"apparent_temperature":5.1,"weather_code":61,
        "wind_speed_10m":3.6,"wind_direction_10m":174,"wind_gusts_10m":9.4,"pressure_msl":1024.8,"precipitation":0.00},
        "hourly":{"time":["2026-09-29T22:00","2026-09-29T23:00"],"temperature_2m":[7.5,null],"precipitation_probability":[3,0],"weather_code":[3,61]},
        "daily":{"time":["2026-09-29","2026-09-30"],"weather_code":[61,80],"temperature_2m_max":[12.5,10.6],"temperature_2m_min":[7.1,5.1],
        "precipitation_probability_max":[3,15],"precipitation_sum":[0.00,1.00],"wind_speed_10m_max":[7.2,5.1],"uv_index_max":[4.10,4.90],"new_field":"ignored"}}"""

    private class CountingTransport(val body: () -> String) : HttpTransport {
        val urls = ArrayList<String>()
        override suspend fun get(url: String): String {
            urls += url
            return body()
        }
    }

    private var now = 0L
    private val clock = Clock { now }

    @Test
    fun noNetworkCallBeforeConsent() = runTest {
        val t = CountingTransport { sample }
        val client = OpenMeteoClient(t, clock)
        assertEquals(ForecastResult.NotConsented, client.forecast(LatLon(46.5582, 7.8352), consented = false))
        assertEquals(ForecastResult.NotConsented, client.forecast(LatLon(46.5582, 7.8352), consented = false, force = true))
        assertEquals(0, t.urls.size)
    }

    @Test
    fun roundsCoordinatesParsesAndCaches() = runTest {
        val t = CountingTransport { sample }
        val client = OpenMeteoClient(t, clock)
        val r = client.forecast(LatLon(46.558271, 7.835219), consented = true) as ForecastResult.Ok
        assertEquals(1, t.urls.size)
        assertTrue(t.urls[0], t.urls[0].startsWith("https://api.open-meteo.com/v1/forecast?latitude=46.56&longitude=7.84&"))
        assertEquals(7.6, r.forecast.response.current!!.temperatureC!!, 1e-9)
        assertEquals(null, r.forecast.response.hourly!!.temperatureC[1])
        assertEquals(2, r.forecast.response.daily!!.time.size)

        now += 10 * 60_000
        val cached = client.forecast(LatLon(46.5583, 7.8352), consented = true) as ForecastResult.Ok
        assertTrue(cached.fromCache)
        assertEquals(1, t.urls.size)

        now += 31 * 60_000
        client.forecast(LatLon(46.5583, 7.8352), consented = true)
        assertEquals(2, t.urls.size)
    }

    @Test
    fun failuresAreReportedNotHidden() = runTest {
        val offline = OpenMeteoClient({ throw IOException("unreachable") }, clock)
        assertEquals(ForecastResult.Failed("unreachable"), offline.forecast(LatLon(1.0, 2.0), consented = true))
        val garbage = OpenMeteoClient({ "<html>captive portal</html>" }, clock)
        assertEquals(ForecastResult.Failed("unexpected response"), garbage.forecast(LatLon(1.0, 2.0), consented = true))
    }
}
