# TrailBlazer

A native Android companion for treks and road journeys. It includes a compass, sun and moon tables, a barometric weather trend, an altimeter, a trip planner that hands routes to your map app, a GPS track recorder, SOS signals and sensor diagnostics.

It is built with Jetpack Compose and Material 3. The navigation chrome uses a frosted-glass look from [Haze](https://github.com/chrisbanes/haze).

## Features

The app has four tabs. Each feature lives in exactly one place.

| Tab | What it does |
|---|---|
| **Now** | Compass dial (true or magnetic, with the declination shown), calibration prompt and position (DMS or decimal). Accuracy and satellite count. GPS and barometric altitude, each labelled. Speed, with an optional over-speed alert. **Mark waypoint**, and **Sight** (a camera view with sun, moon and target bearings). |
| **Sky** | Sunrise, sunset and solar noon. Civil, nautical and astronomical twilight. Golden and blue hour, daylight left, and sun azimuth and elevation. Moon phase, illumination, moonrise and moonset, next full and new moon. Pressure trend, Zambretti forecast and storm alert. An opt-in online forecast. |
| **Trips** | Trips (start, end, night stops, places to visit) with an offline route sketch, a leg table and daylight per stop. "Open in Maps" / Directions (Google or OSM) and Share. Waypoints and recorded tracks. GPX/KML import and export. |
| **Tools** | Level and inclinometer (with vehicle zero). Altimeter and barometer (QNH, boiling point, density altitude). SOS (torch, screen, whistle). Sensor diagnostics with the GNSS sky, a 3D accelerometer plot and a sound meter. |

Settings and the in-app developer docs open from the top bar.

**No fake data.** Every value is either a real reading, *Acquiring*, or *Unavailable* with a reason (no hardware, permission denied, or turned off). A value kept from before the screen paused is marked stale. There is no simulator and no default number.

## Privacy

- **Offline by default.** The app makes exactly one kind of network request: the Open-Meteo forecast. It is off until you turn it on in Sky. Coordinates are rounded to 0.01° (about 1 km) before they are sent, and answers are cached for 30 minutes. Map hand-off opens *your* map app via an intent; TrailBlazer itself does not contact any map service.
- **Nothing is requested at launch.** Each permission is asked for when you first use the feature that needs it:

  | Permission | Asked for when |
  |---|---|
  | Location | You tap *Allow* on a location card (Now, Sky, GNSS diagnostics) or start recording a track |
  | Notifications (Android 13+) | You start recording a track (optional; recording works without it) |
  | Camera | You open Sight (the SOS torch needs no permission) |
  | Microphone | You open the sound meter |
  | Activity recognition (Android 10+) | You turn on steps in Settings |

- **Backup.** Cloud backup contains settings only. The waypoint, trip and track database is included only in direct device-to-device transfer.
- **Delete all.** Settings → Privacy → *Delete all data* clears the database, the settings and the forecast cache.

## Building

You need JDK 21 and the Android SDK with platform 37.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

```bash
./gradlew :app:assembleRelease
```

| | Version |
|---|---|
| Gradle | 9.6.0 (wrapper, checksum-pinned) |
| AGP | 9.4.1 |
| Kotlin | 2.3.20 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 |
| Compose BOM | 2026.09.00 (Material 3 1.4.0) |

All versions are in `gradle/libs.versions.toml`.

## Documentation

Developer docs are in [`docs/`](docs/) and are also bundled into the app (Settings → Developer docs):

- [ARCHITECTURE.md](docs/ARCHITECTURE.md): modules, data flow and the `Reading<T>` contract.
- [SENSORS.md](docs/SENSORS.md): each sensor, its fallback chain, rates, calibration and GNSS.
- [ALGORITHMS.md](docs/ALGORITHMS.md): astronomy, atmosphere, weather, geodesy and track maths, with references.
- [TESTING.md](docs/TESTING.md): test layers, golden cases and device checks.

## Weather data attribution

The online forecast uses [Open-Meteo](https://open-meteo.com/) (CC BY 4.0). The free Open-Meteo API is for **non-commercial use only**. A commercial release needs an Open-Meteo API subscription or a different provider.

The previous React/WebView version is preserved at the git tag `web-final`.
