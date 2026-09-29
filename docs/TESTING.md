# Testing

## Commands

The build needs JDK 21. Robolectric's API 36 sandbox needs it, and Gradle 9.6 / AGP 9.4 run on it.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

The full gate:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
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
| Geodesy | `GeoTest.kt`, `CoordinateParserTest.kt` | JVM | Haversine within 0.6% of a Vincenty oracle. A table of real share-link formats. Short links are refused. |
| Weather / atmosphere | `WeatherTest.kt` | JVM | A 300 m descent reads Steady. A 45-minute window is `Insufficient`. A 2-hour window is scaled to hPa/3h. Density altitude is null without temperature. |
| Formats | `GeoFormatsTest.kt`, `TrackTest.kt` | JVM | GPX and KML round-trips with XML escaping. XXE and entity inputs are inert. Douglas–Peucker reduces to ≤ 2,000 points. |
| Misc | `MiscTest.kt` | JVM | Angle wrap, time, motion, SOS morse `...---...`, speed-alert re-arming, trip rules and map-link snapshots. |
| Sensors | `app/src/test/.../sensors/SensorRepositoryTest.kt` | JVM + Turbine, `FakeSensorSource` | Missing hardware is `Unavailable`. Median filtering and accuracy. Out-of-range samples dropped. The listener is released 5 s after the last subscriber, and the value comes back stale. A 10,000-event flood is conflated. The heading fallback chain. Steps restart when permission is granted. |
| Location | `location/LocationRepositoryTest.kt` | Robolectric | No permission gives `PermissionDenied` with nothing registered. Fixes are delivered. Switching location off gives `Disabled`. Invalid coordinates are dropped. |
| Tracking | `tracking/TrackRecorderTest.kt` | Robolectric + in-memory Room | Inaccurate, jittery and out-of-order fixes are filtered. Writes are batched. The open track resumes after process death. Pause. 100,000 points stream with bounded memory. |
| Network consent | `weather/OpenMeteoClientTest.kt` | JVM, fake `HttpTransport` | **Zero calls before consent.** Coordinates rounded to 0.01°. Responses cached. Failures are reported, not hidden. |
| SOS | `sos/TorchControllerTest.kt` | Robolectric `ShadowCameraManager` | No torch means `play` fails. The torch follows the SOS timeline and is off after cancellation. |
| App smoke | `AppSmokeTest.kt` | Robolectric + Compose test (`createAndroidComposeRule`, v2) | No permission requested at launch. Honest "no compass / no barometer" states. Tabs and back navigation. State survives activity recreation. SOS stops on `ON_STOP`. The forecast is off by default. The bundled developer docs open from Settings (this fails if a doc is missing from the APK). |

## Adding a USNO golden case

1. Fetch the day from the USNO API. `tz` is a fixed UTC offset in hours; use the offset in force on that date (Denver in June is −6). The test must use the same offset:

   ```
   https://aa.usno.navy.mil/api/rstt/oneday?date=2026-06-21&coords=39.7392,-104.9903&tz=-6
   ```

2. Add a `UsnoCase(name, date, lat, lon, tz, civilDawn, rise, transit, set, civilDusk, moonRise, moonSet)` to `AstroGoldenTest.usno`. Use `null` for a moon event that does not happen that day.
3. Tolerances are ±1.5 min for the sun and ±3 min for the moon, because USNO rounds to the minute. Do not widen them to make a case pass: a failure means the algorithm or the case is wrong.

## Robolectric notes

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

**Physical device:**
- The torch.
- The compass calibration prompt: bring a magnet near the phone.
- Sight: the degree strip lines up with a known landmark bearing.
- The sound meter responds.
- Steps.

**Permission revoked mid-recording:** Android kills the app process when a runtime permission is revoked. That path is therefore the *resume-after-process-death* path, which `TrackRecorderTest` covers, followed by `Unavailable(PermissionDenied)` from `LocationRepository`, which `LocationRepositoryTest` covers. The end-to-end sequence on a device is unverified.
