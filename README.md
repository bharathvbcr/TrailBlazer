# TrailBlazer

A native Android companion for treks and road journeys. It includes a compass, sun and moon tables, a barometric weather trend, an altimeter, a trip planner that hands routes to your map app, a GPS track recorder, SOS signals and sensor diagnostics.

It is built with Jetpack Compose and Material 3. The navigation chrome uses a frosted-glass look from [Haze](https://github.com/chrisbanes/haze).

## Features

The app has four tabs. Each feature lives in exactly one place.

| Tab | What it does |
|---|---|
| **Now** | Compass dial (true or magnetic, with the declination shown), calibration prompt and position (DMS or decimal), with accuracy, fix time and a satellite sky. The position card can be minimised to one line (coordinates, ±accuracy, satellites used/in view), and it stays that way. GPS and barometric altitude, each labelled. Speed, with an optional over-speed alert. **Mark waypoint**, **Sight** (a camera view with sun, moon and target bearings) and a quick **Level** button. |
| **Sky** | The Sun's path through the day as a drawing you scrub, and as seen looking up on a horizon compass. Sunrise and sunset with their compass directions, the highest point, first and last light, golden and blue hour, day length and its change from yesterday, and your shadow's length now. Moon phase and illumination; moonrise, highest point and moonset in time order with compass directions, and the Moon's height through the day and its path on its own horizon compass. The day drawings colour the sky by where the Sun is (day, sunset glow, twilight, stars at night), and the Moon is drawn at its real phase. Next full and new moon. A **Moon phases** dropdown shows the whole cycle as pictures (lit on the side you see from your hemisphere), where tonight is in it, what each phase looks like and when it is up, and the dates of the next four principal phases. Pressure trend, Zambretti forecast and storm alert. An opt-in online forecast: conditions now with a weather picture, feels-like, wind and gusts; the next hours as a line with times; the next days as bars named Today, Tomorrow and by weekday. **Tonight’s sky** opens stargazing for the place shown. |
| **Trips** | Trips (start, end, night stops, places to visit) with an offline route sketch, the straight-line leg shown between each pair of stops, and daylight per stop. Add a stop with **Find a place**: search by name or address (opt-in), paste coordinates or a map link, take what is on the clipboard, use where you are, or pick one of your waypoints or earlier stops, nearest first. Or, in Google Maps (or any maps app), tap **Share → TrailBlazer** on a place to start a new trip with it or add it before an existing trip's destination; tap a stop's name to rename it; removing a stop can be undone. "Open in Maps" / Directions (Google or OSM) and Share. Waypoints and recorded tracks. GPX/KML import and export. |
| **Tools** | Level and inclinometer (with vehicle zero), usable by feel: a tick at each degree within 4° of level, a firm pulse when level and, upright, a buzz when steep (can be switched off). Altimeter and barometer (QNH, boiling point, density altitude). **Stargazing** (below). SOS (torch, screen, whistle). Sensor diagnostics with the GNSS sky, a 3D accelerometer plot and a sound meter. |

Settings and the in-app developer docs open from the top bar.

**Stargazing** works offline, for your position or for the place chosen on the Sky tab:

- **Tonight:** when it is fully dark (Sun below −18°) and when it is dark with the Moon down, on a noon-to-noon timeline, with a ring for how much of the Moon is lit. Where the Sun never gets that low, it says so.
- **Sky chart** of the 283 stars brighter than magnitude 3.5, the planets, the Moon and the Milky Way core. Tap any dot to name it with its height, direction and brightness. Look ahead up to 12 hours, and optionally turn it with the compass.
- **Planets tonight:** one plot of every planet's altitude through the night over the darkness bands. Tap or drag it to read each altitude at that moment. Computed from the JPL planetary elements.
- **Milky Way core:** the same altitude plot, with when it is highest.
- **Meteor showers:** from the IMO list, as bars: the full peak rate, and the part you can expect from your latitude, with the peak date and the Moon's phase.
- **Polar alignment:** where Polaris (or σ Octantis in the south) sits around the pole, for setting up a telescope mount.
- **Dark-adaptation timer** as a filling ring, and a one-tap switch for red night vision.

**No fake data.** Every value is either a real reading, *Acquiring*, or *Unavailable* with a reason (no hardware, permission denied, or turned off). A value kept from before the screen paused is marked stale. There is no simulator and no default number.

## Privacy

- **Offline by default.** Nothing goes online until you turn it on or tap for it. The Open-Meteo forecast is off until you turn it on in Sky. Coordinates are rounded to 0.01° (about 1 km) before they are sent, and answers are cached for 30 minutes. Map hand-off opens *your* map app via an intent; TrailBlazer itself does not contact any map service, with one exception you trigger yourself: paste a short link (`maps.app.goo.gl/…`) and tap **Look up online**, and only that link is sent to the shortener that issued it, to learn where it points. **Place search** is off until you allow it the first time you search: then only the words to find are sent, when you ask (Go, Search, the clipboard chip, or a place shared to TrailBlazer), to the phone's own place-search service (Android's Geocoder; on Pixel phones it is run by Google). TrailBlazer does not send your location with them. Turn it off in Settings → Privacy.
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
