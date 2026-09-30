# Architecture

## Modules

| Module | Kind | Package | Contains |
|---|---|---|---|
| `:core` | Pure Kotlin/JVM (no Android) | `com.trailblazer.core` | All the maths and file formats. Tested on the plain JVM in seconds. |
| `:app` | Android application | `com.example.trailblazer` | Sensors, location, storage, the tracking service and the Compose UI. |

`:core` has no Android imports, so everything that can be checked against a reference value (astronomy, geodesy, weather, GPX/KML, map links) lives there.

### `:core` packages

| Package | Responsibility |
|---|---|
| `math` | `mod360`, `angleDiff` (−180, 180], `crossesNorth`, `CircularLowPass`, `MedianFilter` |
| `time` | Julian day, ΔT, civil dates, ISO-8601 (always `Locale.ROOT`) |
| `astro` | Sun (NOAA/Meeus 25), moon (Meeus 47), coordinate transforms, refraction, `RiseSetFinder`, `SolarEvents`, `LunarPhase`, `LunarEvents`; planets (JPL elements), the bright-star catalogue, precession and apparent place, `NightSky` (darkness intervals, planet and Milky Way visibility, polar alignment), `MeteorShowers` (IMO list), `SkyProjection` (sky chart) |
| `geo` | `LatLon` (validated), haversine, bearings, destination, cross-track, DMS, `CoordinateParser` |
| `atmo` | ISA, QNH, density altitude, air density, boiling point, dew point |
| `weather` | `PressureTrend`, `Tendency`, `StormAlert`, `Zambretti` |
| `track` | `TrackPoint`, streaming `TrackStats`, `DouglasPeucker` |
| `io` | `XmlPull` (no DTD, no entities), `GpxReader`/`GpxWriter`, `KmlReader`/`KmlWriter` |
| `trip` | `Stop`, `Trip`, `LegCalculator`, `TripRules`, `DaylightPlanner` |
| `intents` | `MapLinks`: `geo:` URIs, Google Maps directions, OSM directions |
| `motion` | `Vec3`, g-force, inclination, the 3D-plot orbit camera |
| `alerts`, `sos`, `units` | Speed alert with hysteresis, morse/whistle timelines, unit conversion |

Every public function returns a *total* result: a value, `null`, or a sealed "why not" type. None of them returns 0, 1013.25 or NaN as a stand-in for "unknown".

### `:app` packages

| Package | Responsibility |
|---|---|
| `TrailApp.kt` | `AppContainer`: manual dependency injection. Every repository is a `lazy` singleton on an application-wide `SupervisorJob` scope. |
| `sensors` | `SensorSource` (the `SensorManager` seam), `Reading<T>`, and the barometer, magnetic, environment, motion, step, orientation and acoustic repositories. Also declination. |
| `location` | `LocationRepository` (`LocationManagerCompat`, no Play Services), `GnssRepository` |
| `permissions` | `AppPermission` and a `StateFlow` of grants refreshed on every resume |
| `data` | Room `TrailDb` (waypoints, trips + stops, tracks + points, pressure samples), DataStore `Prefs`, repositories, SAF `ImportExport` |
| `tracking` | `TrackRecordingService` (foreground, type `location`), `TrackRecorder` (filtering and batching), `TrackingController` |
| `weather` | `OpenMeteoClient` (consent-gated) and `PressureSampler` |
| `sos` | `TorchController`, `WhistlePlayer` |
| `links` | `LinkLookup`: resolves a short map link on the user's tap, through `RedirectProbe` (Location header only). The logic is `core/geo/ShortLinks`. |
| `places` | `PlaceSearch` (consent-gated, bounded, validated) over `PlaceSearchBackend`; `GeocoderBackend` is Android's `Geocoder` (the API 33 listener, or the blocking call on IO below 33). `SavedPlaces` lists waypoints and other trips' stops, nearest first. |
| `ui` | Theme, glass components, Nav3 navigation, one package per tab, `stars` (the stargazing screen, opened from the Sky tab with its place or from Tools with the live position) and settings |

## Data flow

```
SensorManager / LocationManager
        │  callbackFlow, conflated; the listener is unregistered in awaitClose
        ▼
Repository  ──  validate range → filter (median / circular low-pass) → Reading<T>
        │  shareReading(): StateFlow, WhileSubscribed(5 s)
        ▼
ViewModel   ──  combine readings with settings and :core maths → UI state
        │  collectAsStateWithLifecycle
        ▼
Composable
```

- **Sensors are only on while someone is looking.** `shareReading` stops the upstream flow (and so unregisters the Android listener) 5 s after the last collector leaves. The grace period covers configuration changes without re-registering.
- **Location** is gated twice. `live()` combines the location permission flow with a "location enabled" flow, which is fed by the `PROVIDERS_CHANGED` and `MODE_CHANGED` broadcasts. Revoking permission or switching location off yields `Unavailable` immediately, rather than a silent stall.
- **ViewModels never collect a sensor or location flow in `viewModelScope` on their own.** A ViewModel outlives its screen (another screen pushed on top, the app in the background), so a collection there would keep the hardware on. Side effects such as the speed alert are cold flows that the screen collects under `repeatOnLifecycle(STARTED)`. `NowViewModelTest` checks that no listener is registered with nobody watching.
- **The recording service** collects `location.live(1 s)` itself, independently of the UI. It writes to Room in batches, and the Trips tab observes Room.

## The `Reading<T>` contract

```kotlin
sealed interface Reading<out T> {
    data class Unavailable(val reason: UnavailableReason) : Reading<Nothing> // NoHardware | PermissionDenied | Disabled
    data object Acquiring : Reading<Nothing>
    data class Value<out T>(val value: T, val accuracy: Accuracy, val timestampMs: Long, val stale: Boolean) : Reading<T>
}
```

Rules:

1. **No numeric fallback.** A missing sensor is `Unavailable(NoHardware)`, never a default number. The UI shows a different message and action for each reason.
2. **Out-of-range samples are dropped**, not clamped. Examples: pressure outside 300–1100 hPa, and non-finite values.
3. **Stale, not blank.** When a shared reading restarts, the last value is replayed with `stale = true` and stays on screen until a fresh value or an `Unavailable` replaces it. The upstream's initial `Acquiring` is filtered out while a cached value exists.
4. **Accuracy travels with the value.** It is Android's `SENSOR_STATUS_*` for sensors and a bucket of horizontal accuracy for location fixes.

## Persistence

- **Room `TrailDb`** uses UUID primary keys. Track points are indexed on `(trackId, seq)` and read with keyset paging (`pointsAfter`), so a 100k-point track is never loaded at once. Schemas are exported to `app/schemas/`.
- **Pressure samples** are recorded every 10 minutes while the app is in the foreground and pruned after 7 days.
- **DataStore** holds `Settings`. One `readFrom` mapper is the only place that decodes preferences.
- **Backup:** cloud backup includes `datastore/` only. Device-to-device transfer also includes the database. See `res/xml/data_extraction_rules.xml`.

## Tracking service lifecycle

1. `TrackingController.start()` creates an open track row and starts the service. The service calls `startForeground` (type `location`) immediately.
2. `TrackRecorder` keeps fixes with accuracy ≤ 30 m that moved at least max(3 m, accuracy / 2). It flushes every 10 points or every 5 s, in one transaction.
3. If location becomes `Unavailable`, recording pauses and records an `InterruptReason` (`LocationPermissionRevoked` or `LocationDisabled`), which the Trips tab explains.
4. After process death, `MainActivity` calls `resumeIfInterrupted()` on start. The service reattaches to the open track and never creates a new one on its own.

## UI

- **Navigation:** Navigation 3 `NavDisplay` with `@Serializable` route keys, and a `ViewModelStoreNavEntryDecorator` so each entry owns its ViewModels. Back from a tab root returns to Now. Pushes slide in, pops slide back, and tab switches crossfade. The system back gesture drives `predictivePopTransitionSpec` frame by frame: the page shrinks and moves away from the edge the swipe started on, and a cancelled gesture leaves it where it was (`PredictiveBackTest`).
- **Touch on plots:** every plot takes input only through `ui/components/Gestures.kt`. `scrubX` is tap plus a sideways drag; `pickPoint` is tap plus press-and-hold, then drag; `orbit` (the 3D plot) is a sideways drag, plus press-and-hold, then drag, to tilt. None of them claims a vertical drag, so a page scrolls from anywhere, including a plot. Do not use `detectDragGestures` on anything inside a scrolling page (`PlotGesturesTest`).
- **Bottom inset:** `LocalBottomInset` is the tab pill's whole footprint (the gesture bar, the 12 dp gap and the pill), measured before its padding. Every `ScreenScaffold` list ends that far up, so its last item scrolls clear of the pill (`BottomInsetTest`, with a simulated 48 dp gesture bar).
- **Glass:** Haze `hazeSource` on the content and `hazeBlur` on the chrome only (top bar, bottom bar, sheets). Real backdrop blur on API 31+, a tinted scrim below that. Content cards are not blurred, which keeps scrolling cheap.
- **Theme:** Material 3 `MaterialTheme` (stable 1.4.0, no Expressive APIs). Dynamic colour on API 31+, a Forest fallback palette, and a red night-vision scheme. Figures use tabular numerals.
- **Icons** are drawn as `ImageVector`s in `TrailIcons`, so there is no icon-font dependency.
- **Choosing a place:** every screen that needs a place uses `PlacePicker` (`ui/components/PlacePicker.kt`): Trips' *Find a place*, Sky's *Change place* and the share screen. The caller decides what "here" means (`HereOption`): a stop at the current fix for a trip, following the live position for Sky.
- **Shared text:** `MainActivity` (`singleTask`) takes `ACTION_SEND text/plain` on a fresh start or in `onNewIntent`, bounds it to 4,000 characters and hands it to `TrailNav`, which opens `ShareRoute` once. The place goes to the trip editor as `TripEditRoute.seed`; the editor adds it only after an existing trip has loaded, before its destination (`TripEditViewModelTest`).

## Adding a feature: checklist

1. Put the maths in `:core` with a reference-value test.
2. Expose the data as `Reading<T>` from a repository in `AppContainer`, using `shareReading`.
3. Ask for a permission only in context, with `PermissionGate` and a one-line rationale.
4. Give each feature exactly one home in the UI.
5. Add a Robolectric test for the unavailable state.
