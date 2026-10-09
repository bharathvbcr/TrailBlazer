package com.example.trailblazer

import android.app.Application
import android.content.Context
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.example.trailblazer.data.ImportExport
import com.example.trailblazer.data.PrefsRepository
import com.example.trailblazer.data.PressureHistory
import com.example.trailblazer.data.TrackRepository
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.data.TripRepository
import com.example.trailblazer.data.WaypointRepository
import com.example.trailblazer.location.GnssRepository
import com.example.trailblazer.location.LocationRepository
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.permissions.Permissions
import com.example.trailblazer.sensors.AcousticRepository
import com.example.trailblazer.sensors.AndroidBleTransport
import com.example.trailblazer.sensors.AndroidSensorSource
import com.example.trailblazer.sensors.BarometerRepository
import com.example.trailblazer.sensors.BleSensorManager
import com.example.trailblazer.sensors.BleTransport
import com.example.trailblazer.sensors.Clock
import com.example.trailblazer.sensors.EnvironmentRepository
import com.example.trailblazer.sensors.MagneticRepository
import com.example.trailblazer.sensors.MotionRepository
import com.example.trailblazer.sensors.OrientationRepository
import com.example.trailblazer.sensors.SensorSource
import com.example.trailblazer.sensors.StepRepository
import com.example.trailblazer.sensors.ThermalRepository
import com.example.trailblazer.tracking.TrackingController
import com.example.trailblazer.links.LinkLookup
import com.example.trailblazer.places.GeocoderBackend
import com.example.trailblazer.places.PlaceSearch
import com.example.trailblazer.places.PlaceSearchBackend
import com.example.trailblazer.links.RedirectProbe
import com.example.trailblazer.links.UrlConnectionRedirectProbe
import com.example.trailblazer.weather.HttpTransport
import com.example.trailblazer.weather.OpenMeteoClient
import com.example.trailblazer.weather.UrlConnectionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Manual dependency graph. Everything is created lazily, so a screen only starts the sensors it uses,
 * and tests can build a container with fake [SensorSource] / [HttpTransport] / [Clock].
 */
class AppContainer(
    private val context: Context,
    val sensorSource: SensorSource = AndroidSensorSource(context.getSystemService(SensorManager::class.java)),
    http: HttpTransport = UrlConnectionTransport(),
    val clock: Clock = Clock.System,
    val db: TrailDb = TrailDb.create(context),
    redirects: RedirectProbe = UrlConnectionRedirectProbe(),
    placeBackend: PlaceSearchBackend = GeocoderBackend(context),
    placeSearchAvailable: () -> Boolean = GeocoderBackend::isPresent,
    val bleTransport: BleTransport = AndroidBleTransport(context),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val permissions = Permissions(context)
    val prefs = PrefsRepository(context)

    private val locationManager: LocationManager by lazy { context.getSystemService(LocationManager::class.java) }

    private var cachedDisplayRotation: Int = 0
    private val displayRotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                cachedDisplayRotation = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
            }
        }
    }

    init {
        val dm = context.getSystemService(DisplayManager::class.java)
        cachedDisplayRotation = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        try {
            dm?.registerDisplayListener(displayRotationListener, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {}
    }

    private fun displayRotation(): Int = cachedDisplayRotation

    val bleSensorManager by lazy { BleSensorManager(context, bleTransport, scope, clock) }
    val barometer by lazy { BarometerRepository(sensorSource, scope, clock, bleSensorManager) }
    val magnetic by lazy { MagneticRepository(sensorSource, scope, clock) }
    val environment by lazy { EnvironmentRepository(sensorSource, scope, clock) }
    val motion by lazy { MotionRepository(sensorSource, scope, clock) }
    val thermal by lazy { ThermalRepository(context, sensorSource, clock) }
    val orientation by lazy { OrientationRepository(sensorSource, clock, ::displayRotation) }
    val steps by lazy { StepRepository(sensorSource, scope, clock, permissions.flowOf(AppPermission.ActivityRecognition)) }
    val acoustic by lazy { AcousticRepository(clock) { permissions.isGranted(AppPermission.Microphone) } }
    val location by lazy { LocationRepository(context, locationManager, permissions, scope, bleSensorManager, clock) }
    val gnss by lazy { GnssRepository(context, locationManager, permissions, scope) }

    val waypoints by lazy { WaypointRepository(db.waypoints(), clock) }
    val trips by lazy { TripRepository(db.trips(), clock) }
    val tracks by lazy { TrackRepository(db.tracks()) }
    val pressureHistory by lazy { PressureHistory(db.pressure(), clock) }

    val importExport by lazy { ImportExport(context.contentResolver, db, waypoints, trips, tracks) }
    val offlineMaps by lazy { com.example.trailblazer.data.OfflineMapManager(context, prefs) }

    val weather by lazy { OpenMeteoClient(http, clock) }
    val linkLookup by lazy { LinkLookup(redirects) }
    val placeSearch by lazy { PlaceSearch(placeBackend, placeSearchAvailable) }
    val tracking by lazy { TrackingController(context, tracks, permissions, sensorSource) }
}

class TrailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as TrailApp).container
