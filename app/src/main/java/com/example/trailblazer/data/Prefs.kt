package com.example.trailblazer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trailblazer.core.geo.CoordinateFormat
import com.trailblazer.core.units.PressureUnit
import com.trailblazer.core.units.SpeedUnit
import com.trailblazer.core.units.TemperatureUnit
import com.trailblazer.core.units.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { System, Light, Dark }
enum class NorthReference { True, Magnetic }

/**
 * How often the recording service asks for a fix. Every mode but [Continuous] also lets the GNSS receiver sleep
 * once the accelerometer has seen the phone lie still for five minutes (see `MotionGate`). Stored by name.
 */
enum class TrackingMode(val intervalMs: Long, val motionGated: Boolean) {
    Continuous(1_000L, false),
    Balanced(15_000L, true),
    Expedition(60_000L, true),
    ExpeditionLong(300_000L, true),
}

/** A known reference for the barometric altimeter: either sea-level pressure (QNH) or the current elevation. */
data class AltimeterCalibration(val qnhHpa: Double, val setAtMs: Long)

data class Settings(
    val units: UnitSystem = UnitSystem.Metric,
    val speedUnit: SpeedUnit = SpeedUnit.KmH,
    val pressureUnit: PressureUnit = PressureUnit.Hpa,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.Celsius,
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DegreesMinutesSeconds,
    val north: NorthReference = NorthReference.True,
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val nightRed: Boolean = false,
    val forecastConsent: Boolean = false,
    /** Place search by name (Android's Geocoder). Off until the user turns it on. */
    val placeSearchConsent: Boolean = false,
    val speedAlertEnabled: Boolean = false,
    val speedAlertLimitMps: Double = 100 / 3.6,
    val stepsEnabled: Boolean = false,
    /** Step-counter reading (steps since boot) when counting started; null until the first reading. */
    val stepsBaseline: Long? = null,
    val calibration: AltimeterCalibration? = null,
    val targetWaypointId: String? = null,
    val levelPitchOffsetDeg: Double = 0.0,
    val levelRollOffsetDeg: Double = 0.0,
    /** Opt-in: the Now tab shows position as one line, without the satellite sky. */
    val compactPosition: Boolean = false,
    /** Ticks, a confirm on level and a steep warning on the Level screen. */
    val levelHaptics: Boolean = true,
    val trackingMode: TrackingMode = TrackingMode.Continuous,
)

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Settings in DataStore. Unknown or corrupt values fall back to the defaults above, field by field. */
class PrefsRepository(private val context: Context) {
    private object K {
        val units = stringPreferencesKey("units")
        val speedUnit = stringPreferencesKey("speed_unit")
        val pressureUnit = stringPreferencesKey("pressure_unit")
        val temperatureUnit = stringPreferencesKey("temperature_unit")
        val coordinateFormat = stringPreferencesKey("coordinate_format")
        val north = stringPreferencesKey("north")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val nightRed = booleanPreferencesKey("night_red")
        val forecastConsent = booleanPreferencesKey("forecast_consent")
        val placeSearchConsent = booleanPreferencesKey("place_search_consent")
        val speedAlertEnabled = booleanPreferencesKey("speed_alert_enabled")
        val speedAlertLimit = doublePreferencesKey("speed_alert_limit_mps")
        val stepsEnabled = booleanPreferencesKey("steps_enabled")
        val compactPosition = booleanPreferencesKey("compact_position")
        val levelHaptics = booleanPreferencesKey("level_haptics")
        val stepsBaseline = longPreferencesKey("steps_baseline")
        val qnh = doublePreferencesKey("calibration_qnh_hpa")
        val qnhSetAt = longPreferencesKey("calibration_set_at_ms")
        val target = stringPreferencesKey("target_waypoint")
        val levelPitch = doublePreferencesKey("level_pitch_offset_deg")
        val levelRoll = doublePreferencesKey("level_roll_offset_deg")
        val trackingMode = stringPreferencesKey("tracking_mode")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    val settings: Flow<Settings> = context.store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { readFrom(it) }

    suspend fun update(transform: (Settings) -> Settings) {
        context.store.edit { p ->
            // Compute the new state from the stored one, then write every field so the store stays complete.
            val n = transform(readFrom(p))
            p[K.units] = n.units.name
            p[K.speedUnit] = n.speedUnit.name
            p[K.pressureUnit] = n.pressureUnit.name
            p[K.temperatureUnit] = n.temperatureUnit.name
            p[K.coordinateFormat] = n.coordinateFormat.name
            p[K.north] = n.north.name
            p[K.theme] = n.theme.name
            p[K.dynamicColor] = n.dynamicColor
            p[K.nightRed] = n.nightRed
            p[K.forecastConsent] = n.forecastConsent
            p[K.placeSearchConsent] = n.placeSearchConsent
            p[K.speedAlertEnabled] = n.speedAlertEnabled
            p[K.speedAlertLimit] = n.speedAlertLimitMps
            p[K.stepsEnabled] = n.stepsEnabled
            p[K.compactPosition] = n.compactPosition
            p[K.levelHaptics] = n.levelHaptics
            val b = n.stepsBaseline
            if (b == null) p.remove(K.stepsBaseline) else p[K.stepsBaseline] = b
            val c = n.calibration
            if (c == null) { p.remove(K.qnh); p.remove(K.qnhSetAt) } else { p[K.qnh] = c.qnhHpa; p[K.qnhSetAt] = c.setAtMs }
            val t = n.targetWaypointId
            if (t == null) p.remove(K.target) else p[K.target] = t
            p[K.levelPitch] = n.levelPitchOffsetDeg
            p[K.levelRoll] = n.levelRollOffsetDeg
            p[K.trackingMode] = n.trackingMode.name
        }
    }

    private fun readFrom(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            units = p.enum(K.units, d.units),
            speedUnit = p.enum(K.speedUnit, d.speedUnit),
            pressureUnit = p.enum(K.pressureUnit, d.pressureUnit),
            temperatureUnit = p.enum(K.temperatureUnit, d.temperatureUnit),
            coordinateFormat = p.enum(K.coordinateFormat, d.coordinateFormat),
            north = p.enum(K.north, d.north),
            theme = p.enum(K.theme, d.theme),
            dynamicColor = p[K.dynamicColor] ?: d.dynamicColor,
            nightRed = p[K.nightRed] ?: d.nightRed,
            forecastConsent = p[K.forecastConsent] ?: d.forecastConsent,
            placeSearchConsent = p[K.placeSearchConsent] ?: d.placeSearchConsent,
            speedAlertEnabled = p[K.speedAlertEnabled] ?: d.speedAlertEnabled,
            speedAlertLimitMps = p[K.speedAlertLimit]?.takeIf { it.isFinite() && it > 0 } ?: d.speedAlertLimitMps,
            stepsEnabled = p[K.stepsEnabled] ?: d.stepsEnabled,
            compactPosition = p[K.compactPosition] ?: d.compactPosition,
            levelHaptics = p[K.levelHaptics] ?: d.levelHaptics,
            stepsBaseline = p[K.stepsBaseline]?.takeIf { it >= 0 },
            calibration = p[K.qnh]?.takeIf { it.isFinite() && it in 900.0..1100.0 }?.let { AltimeterCalibration(it, p[K.qnhSetAt] ?: 0L) },
            targetWaypointId = p[K.target],
            levelPitchOffsetDeg = p[K.levelPitch]?.takeIf { it.isFinite() } ?: d.levelPitchOffsetDeg,
            levelRollOffsetDeg = p[K.levelRoll]?.takeIf { it.isFinite() } ?: d.levelRollOffsetDeg,
            trackingMode = p.enum(K.trackingMode, d.trackingMode),
        )
    }

    suspend fun clear() {
        context.store.edit { it.clear() }
    }
}
