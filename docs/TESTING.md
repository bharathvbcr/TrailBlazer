# Testing

## Commands

The build needs JDK 21. Robolectric's API 36 sandbox needs it, and Gradle 9.6 / AGP 9.4 run on it.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

The full gate:

```bash
./gradlew :core:jvmTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

To also check that R8 shrinking keeps everything that serialization and Room need:

```bash
./gradlew :app:assembleRelease
```

One class at a time:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.example.trailblazer.tracking.TrackRecorderTest'
```

Lint runs with `abortOnError = true`.

## Layers

| Layer | Where | Runner | What it proves |
|---|---|---|---|
| Golden values | `core/src/test/.../astro/AstroGoldenTest.kt` | JVM | Sun, moon and phase times match the US Naval Observatory. Meeus worked examples 12.a, 25.a, 47.a, 48.a and 49.a match. |
| Properties / fuzz | `AstroPropertyTest.kt` | JVM, fixed seed | 5,000 random places and times give no NaN, azimuth in [0, 360), rise < transit < set, and illumination in [0, 1]. Also the poles, ±180°, DST days, 2028-02-29, 1900 and 2100. |
| Geodesy | `GeoTest.kt`, `CoordinateParserTest.kt` | JVM | Haversine within 0.6% of a Vincenty oracle. A table of real share-link formats. Short links are refused by the parser (see Short links below). |
| Weather / atmosphere | `WeatherTest.kt` | JVM | A 300 m descent reads Steady. A 45-minute window is `Insufficient`. A 2-hour window is scaled to hPa/3h. Density altitude is null without temperature. |
| Formats | `GeoFormatsTest.kt`, `TrackTest.kt` | JVM | Moving time across a gap uses the average speed, not the speed of the first fix after it (an hour standing was 3,650 s of moving time before the fix). GPX and KML round-trips with XML escaping. XXE and entity inputs are inert. Douglas–Peucker reduces to ≤ 2,000 points. |
| Planets | `astro/PlanetsTest.kt` | JVM | 28 JPL Horizons (DE441) rows, all seven planets on four dates 1995–2040: astrometric position within the per-planet error of the JPL elements (Saturn 8.5′, others 1–2.5′), apparent-of-date place adds exactly the same error (so precession, nutation and aberration are right to under 3″), magnitude within 0.3. Meeus example 21.b for precession. Kepler converges for every eccentricity in use. Finite results from 2000 BC to AD 2999. Mercury and Venus never exceed their greatest elongations over 12 years. |
| Night sky | `astro/NightSkyTest.kt` | JVM, fixed seed | Meteor peaks land on the IMO 2026 calendar's dates. λ☉ is referred to J2000 (a ~9 h effect in 2026). Activity periods that span New Year. Interval building and intersection, including empty, touching and nested lists. Midnight sun (Tromsø, June) has no darkness; the equator has about 10 h. Moon-free darkness follows the phase. 300 random places and nights: every interval is inside the window, ordered, and moon-free ⊆ astronomical ⊆ nautical. The poles and ±180°. Polaris and σ Octantis move the right way round the pole (checked against their azimuth). The star catalogue is clean (no duplicates, no T CrB). Sky-chart orientation (east on the left with north at the top). |
| Stress / boundaries | `StressTest.kt` | JVM, fixed seed | 20,000 random and hostile strings through every coordinate parser: no exception, and every result is a valid position. ISO-8601 round trips. Hostile GPX/KML (deep nesting, huge numbers, NaN, entities) and the point limit. Angle helpers, `CircularLowPass` recovering after NaN, the median filter, `Dms`, geodesy, atmosphere and pressure trend at their edges. `RiseSetFinder` bounds. Map-link labels. Morse. |
| Misc | `MiscTest.kt` | JVM | Angle wrap, time, motion, SOS morse `...---...`, speed-alert re-arming, trip rules and map-link snapshots. |
| Sensors | `app/src/test/.../sensors/SensorRepositoryTest.kt` | JVM + Turbine, `FakeSensorSource` | Missing hardware is `Unavailable`. Median filtering and accuracy. Out-of-range samples dropped. The listener is released 5 s after the last subscriber, and the value comes back stale. A 10,000-event flood is conflated. The heading fallback chain. Steps restart when permission is granted. |
| Location | `location/LocationRepositoryTest.kt` | Robolectric | No permission gives `PermissionDenied` with nothing registered. Fixes are delivered. Switching location off gives `Disabled`. Invalid coordinates are dropped. NaN accuracy and altitude, negative speed, infinite vertical accuracy and a 720.5° bearing become "not reported" or are wrapped (`impossibleOptionalFieldsBecomeNotReported`). `SatelliteBoundaryTest` drops satellites with no real position and clamps C/N0. |
| Sensor robustness | `sensors/OrientationRobustnessTest.kt` | Robolectric (real `SensorManager` maths) | NaN and infinite rotation vectors never reach the compass and do not poison it. Non-finite accelerometer axes never reach the acceleration or the low-passed gravity fallback. A NaN step count is not read as a counter reset. Each was failing before its fix. |
| Tracking | `tracking/TrackRecorderTest.kt` | Robolectric + in-memory Room | Inaccurate, jittery and out-of-order fixes are filtered. Writes are batched. The open track resumes after process death. Pause. 100,000 points stream with bounded memory. |
| GPS duty cycle | `tracking/GnssDutyCycleTest.kt` | JVM + `FakeSensorSource`, sample timestamps set by the test | Balanced, Expedition and 5-min Expedition request their interval. After 5 min lying still (4:59 is not enough) the location request is removed; significant motion or motion in the samples brings it back, and stillness is counted again from the wake-up. Walking keeps GPS on, and so do fixes that move while the accelerometer feels nothing (a canoe). Time the samples do not cover is never counted as stillness. Continuous never rests and registers no accelerometer. Without significant motion or an accelerometer GPS stays on. A mode change applies while resting. NaN, short and backwards samples are not motion. |
| Battery Saver | `tracking/PowerSaveResumeTest.kt` | Robolectric, real `TrackRecordingService` + `ShadowPowerManager` / `ShadowLocationManager` | The Balanced setting reaches the location request (15 s). Battery Saver on (`LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF`), then off, with no fixes delivered for 40 minutes in between (the test withholds them; the shadow does not): still one track, still recording, still requested, and fixes continue in it. The 40-minute gap is not moving time: 270 s, where the pre-fix `TrackStats` gave 2,670 s. |
| Fix interval setting | `ui/settings/TrackingModeSettingTest.kt` | Robolectric + Compose | Settings offers Continuous (1 s), Balanced (15 s), Expedition (60 s) and Expedition (5 min); the pick is saved. |
| Network consent | `weather/OpenMeteoClientTest.kt` | JVM, fake `HttpTransport` | **Zero calls before consent.** Coordinates rounded to 0.01°. Responses cached. Failures are reported, not hidden. |
| SOS | `sos/TorchControllerTest.kt` | Robolectric `ShadowCameraManager` | No torch means `play` fails. The torch follows the SOS timeline and is off after cancellation. |
| Now ViewModel | `ui/now/NowViewModelTest.kt` | Robolectric | A ViewModel that no screen is collecting registers **no** location listener. The speed alert listens only while it is on and while collected, and releases the listener after the 5 s grace period. |
| Stargazing | `ui/stars/StargazeViewModelTest.kt` | Robolectric + in-memory Room | A stored position up to 24 h old stands in (marked last known) while GPS searches; an older one is not used. A chosen place gives tonight's plan, a chart and a polar alignment. The chart looks ahead by the requested offset. No place and no permission gives nothing, never a made-up sky. "Follow compass" without a compass never invents a heading. The altitude plots cover the whole noon-to-noon window with finite altitudes and agree with the rise/set maths beside them, in Denver, Sydney and Tromsø. `ChartIdentifyTest`: a tap finds the object under it at any facing, the brighter object wins a near tie, and empty sky, below-horizon objects and NaN taps find nothing. |
| Plot touch | `ui/components/PlotGesturesTest.kt` | Robolectric + Compose | A vertical swipe that starts on a Sparkline, RangeBars or the satellite sky scrolls the page, and a sideways swipe still scrubs. All three failed before `Gestures.kt` (`index 0, offset 0`). |
| Bottom inset | `ui/BottomInsetTest.kt` | Robolectric, simulated 48 dp gesture bar | The end of the Now and Tools lists scrolls clear of the tab pill. Before the fix, Now's last line ended at 696 dp under a pill starting at 674 dp. |
| Position card | `ui/now/PositionCardTest.kt` | Robolectric + real activity | With a fix, the card minimises to one line (±accuracy, no satellite section), the choice is saved, survives activity recreation, and expands again. |
| Back gesture | `ui/PredictiveBackTest.kt` | Robolectric `dispatchOnBackStarted/Progressed/Cancelled` | A cancelled back gesture stays on Settings; a completed one returns to Now; back from another tab root returns to Now without closing. This characterises existing behaviour; the animation is checked on a device. |
| Trip stops | `ui/trips/TripStopsUiTest.kt`, `TripEditViewModelTest.kt` | Robolectric + Compose | Add actions are visible without a menu, the prompt asks for the start and then the destination, a leg is shown between two stops, and Undo brings back a removed stop exactly (including a night stop's kind). A stale Undo changes nothing. |
| Level haptics | `core/.../motion/LevelHapticsTest.kt`, `ui/tools/LevelHapticsScreenTest.kt` | JVM; Robolectric shadow `SensorManager` + a recording `HapticFeedback` | One confirm on reaching level even with the reading jittering across 0.5°, re-armed only beyond 1°. Detents at 4°, 3°, 2° and 1° and none further out, with no chatter on a boundary. Cues at least 80 ms apart through fast sweeps and 50 random walks. The steep warning (20° roll, 25° pitch) fires once and re-arms 2° under. NaN, infinities and a clock jumping backwards are harmless. Mutation-checked: removing the hysteresis, the rate limit or the re-arm distance each fails a test. On the real screen: ticks then one confirm while tilting in; nothing with the switch off. |
| Moon phases | `core/.../astro/MoonPhasesTest.kt`, `ui/sky/MoonPhasesUiTest.kt`, `ui/sky/MoonDiscPixelTest.kt` | JVM; Robolectric native graphics | The next four principal phases are in cyclic order, each at its exact elongation (60 random dates 1990–2050), gaps within the measured 6.59–8.23 days, and the full moon of 2026-09-26 matches USNO. The Sky tab's dropdown opens closed, shows eight phase pictures with exactly one marked tonight, explains a tapped phase and lists four coming dates. The moon picture's lit pixel share equals its illuminated fraction within 4 %, on the right side. Before the fix, 6 of 7 cases drew 1 − k lit (a 86 % gibbous as a 13 % crescent). |
| Sky details and forecast | `ui/sky/SkyDetailsUiTest.kt`, `weather/ForecastTimesTest.kt` | Robolectric native graphics | On the real Sky tab, the Sun card names the sunrise and sunset directions, first and last light and the day length. The Moon card names at least two of rise, peak and set, and where the Moon is now. From a real Open-Meteo response, the forecast names days Today / Tomorrow / Thu with no `09-29` ISO fragments. Hours become instants using the response's UTC offset. A malformed date or time (month 13, 30 February, hour 25) gets no label rather than a wrong one. |
| Short links | `core/.../geo/ShortLinksTest.kt` | JVM | Only short-link hosts are ever asked, and only over HTTPS. Loops stop after 5 hops. Consent interstitials are unwrapped without loading them. The destination is parsed offline and named from the shared text. Text a maps app shares (name, address, link) is read with no lookup at all; it used to be "Not recognised". |
| Adding places | `places/PlaceSearchTest.kt`, `ui/trips/ShareAndPickUiTest.kt`, `ui/trips/TripEditViewModelTest.kt`, `ui/trips/TripStopsUiTest.kt` | JVM + Robolectric | Nothing is searched before consent, for a one-letter or over-long query, or without a geocoder (which is said, not shown as an empty list). Only the tidied query is sent; results are deduplicated and capped at 5; an offline or hanging service becomes a message. Real `Geocoder` addresses become names without bare house numbers. Saved places come nearest first and filter as you type. A place shared from Maps opens *Add to a trip* and starts a new trip. The clipboard is offered and read only on a tap. Search asks first, then remembers the answer. A shared place goes in before an existing trip's destination with every stop kept (seeding before the load would have saved a one-stop trip over it); a full trip says it was not added. |
| Label and value rows | `ui/components/LabelValueTest.kt` | Robolectric native graphics | A 39-digit value (a sensor range of Float.MAX_VALUE) no longer squeezes its label to one letter per line, as the Pixel's Camera V-Sync and Step Counter cards did. Sensor numbers from a million up read as powers of ten. Vendor sensor types are named from their string type. |
| Temperatures | `sensors/ThermalTest.kt` | JVM | From the Pixel 10 Pro XL's real sensor list: the chip sensors are offered, the infrared thermometer, ambient air and pressure are not. Impossible battery and chip values (including the phone's real `-18342`) are dropped. |
| Readable text | `ScreenshotTest.kt` | Robolectric native graphics | Renders Now, Sky, Trips, Tools, Level and Settings in light, dark and night-red. Every visible, enabled text node must have a contrast of at least 1.8 between its darkest and lightest pixels, so black text on a dark card fails. Against the pre-fix commit it flags 20 black texts in dark mode and 20 in night-red. PNGs go to `app/build/reports/screens/`. |
| Stargazing screen | `ui/stars/StargazeScreenshotTest.kt` | Robolectric native graphics | Renders the stargazing screen for a fixed place, in dark and night-red, to `app/build/reports/screens/`. |
| App smoke | `AppSmokeTest.kt` | Robolectric + Compose test (`createAndroidComposeRule`, v2) | No permission requested at launch. Honest "no compass / no barometer" states. Tabs and back navigation. State survives activity recreation. SOS stops on `ON_STOP`. The forecast is off by default. The bundled developer docs open from Settings (this fails if a doc is missing from the APK). |

## Looking at the screens without a device

Robolectric's native graphics mode (`@GraphicsMode(NATIVE)`) renders Compose to real bitmaps on the JVM, so the screenshot tests can save PNGs you can open:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.example.trailblazer.ScreenshotTest' --tests 'com.example.trailblazer.ui.stars.StargazeScreenshotTest'
open app/build/reports/screens
```

Blur (Haze) and dynamic colour are not rendered the way a device renders them, so check those on an emulator.

**Only one Gradle build at a time per checkout.** Two builds writing `app/build` at once corrupt each other's test results, and Gradle reports `java.io.EOFException`.

## Adding a Horizons golden case (planets)

Fetch the day with the Horizons API; use the geocentric observer (`500@399`) and quantities 1, 2, 9 and 20:

```
https://ssd.jpl.nasa.gov/api/horizons.api?format=text&COMMAND='499'&CENTER='500@399'&EPHEM_TYPE='OBSERVER'&START_TIME='2026-09-29 00:00'&STOP_TIME='2026-09-29 00:01'&STEP_SIZE='1 d'&QUANTITIES='1,2,9,20'&ANG_FORMAT='DEG'&EXTRA_PREC='YES'
```

Add a `Row` to `PlanetsTest.rows` with the astrometric and apparent RA/Dec, APmag and Δ. Keep the per-planet tolerances: they are just above the documented error of the JPL elements, so a failure means a real regression.

## Adding a USNO golden case

1. Fetch the day from the USNO API. `tz` is a fixed UTC offset in hours; use the offset in force on that date (Denver in June is −6). The test must use the same offset:

   ```
   https://aa.usno.navy.mil/api/rstt/oneday?date=2026-06-21&coords=39.7392,-104.9903&tz=-6
   ```

2. Add a `UsnoCase(name, date, lat, lon, tz, civilDawn, rise, transit, set, civilDusk, moonRise, moonSet)` to `AstroGoldenTest.usno`. Use `null` for a moon event that does not happen that day.
3. Tolerances are ±1.5 min for the sun and ±3 min for the moon, because USNO rounds to the minute. Do not widen them to make a case pass: a failure means the algorithm or the case is wrong.

## Robolectric notes

- **Settings leak between tests:** DataStore is a process-wide singleton and outlives each test's `Application`, so a test that changes a setting must set the value it relies on in `@Before` (see `LevelHapticsScreenTest`).
- **Time:** `SystemClock.uptimeMillis()` moves only when the looper is advanced (`shadowOf(Looper.getMainLooper()).idleFor(...)`), not during `Thread.sleep`.
- **Waiting for background work:** never sleep a fixed time. Room, DataStore and `Dispatchers.Default` finish on other threads, and a fixed 300 ms wait made the trip-save tests fail under full-suite load. Use `testing/awaitMainLooper { condition }` for ViewModel tests. With the Compose v2 rule, loop `rule.waitForIdle()` until the condition holds (see `PositionCardTest.settleUntil`): the rule runs coroutines on a test dispatcher that `waitUntil` does not advance.

- **SDK:** `app/src/test/resources/robolectric.properties` pins `sdk=36`, the target SDK. API 36 needs JDK 21.
- **JVM flags:** `app/build.gradle.kts` adds `--add-exports` / `--add-opens java.base/jdk.internal.access=ALL-UNNAMED`. Without them the SDK 36 sandbox fails with `IllegalAccessException` on JDK 21.
- **Coroutines:** `advanceUntilIdle()` does *not* drain work in `backgroundScope`. Tests that need a `backgroundScope` producer to finish use `runCurrent()`, as in the flood test.
- **Lazy lists:** in Compose tests, assert on items near the top *before* `performScrollToNode`, because scrolled-away items leave composition.
- **Asynchronous location:** on API 34+, fixes pass through `AltitudeConverter` on `Dispatchers.IO`, so `LocationRepositoryTest` polls, with a timeout, until the value lands.

## About "a failing test for every fix"

This is a rewrite, so the Kotlin tests cannot run against the old TypeScript. The bug fixes are pinned by facts that do not depend on either implementation. For example:

- The sun azimuth at upper transit is 180 ± 0.5° in the northern hemisphere and near 0° in the southern.
- USNO rise and set times.
- Day length at the pole in midsummer is 24 h.
- Density altitude is null without temperature.
- A 300 m descent at constant sea-level pressure reads Steady.

The old failures were reproduced by running the old code, which is kept at the tag `web-final`. For example, Denver at noon gave 358.5°, and density altitude came out at 9,819 ft instead of about 4,800 ft.

## Not covered by automated tests

Run these by hand before a release.

**Emulator, API 36, 30 and 24:**
- Glass blur on 31+ and the tint fallback on 24–30.
- Location granted in context.
- `adb emu geo fix <lon> <lat>`, then compare Sky against USNO.
- Record a track from GPX playback.
- Export via the system file picker.
- "Open in Maps".
- Airplane mode: the forecast shows its offline error and everything else works.
- Location services switched off while recording: recording pauses with an explanation.
- Expedition (5 min) on a real walk: fixes still pass the 30 m accuracy filter, GPS rests after 5 minutes on a table and wakes when the phone is picked up, and the notification says so. Whether the sensor hub keeps enough accelerometer samples while the phone sleeps (its FIFO) varies by phone; without them GPS simply stays on.

**Physical device:**
- The torch.
- The compass calibration prompt: bring a magnet near the phone.
- Sight: the degree strip lines up with a known landmark bearing.
- The sound meter responds.
- Steps.

**Permission revoked mid-recording:** Android kills the app process when a runtime permission is revoked. That path is therefore the *resume-after-process-death* path, which `TrackRecorderTest` covers, followed by `Unavailable(PermissionDenied)` from `LocationRepository`, which `LocationRepositoryTest` covers. The end-to-end sequence on a device is unverified.
