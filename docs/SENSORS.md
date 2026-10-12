# Sensors

All sensor access goes through `SensorSource` (`sensors/SensorSource.kt`). The real implementation registers a listener in a `callbackFlow` and unregisters it in `awaitClose`. The flow is conflated: a slow collector sees the latest sample, never a backlog.

## Sensor table

| Reading | Android sensor | Rate | Processing | Unavailable when |
|---|---|---|---|---|
| Pressure (hPa) | `TYPE_PRESSURE` | `SENSOR_DELAY_NORMAL` | Drops values outside 300–1100; median of 5 | No barometer |
| Magnetic field (µT) + accuracy | `TYPE_MAGNETIC_FIELD` | `SENSOR_DELAY_UI` | Drives the calibration prompt | No magnetometer |
| Light, temperature, humidity | `TYPE_LIGHT`, `TYPE_AMBIENT_TEMPERATURE`, `TYPE_RELATIVE_HUMIDITY` | `SENSOR_DELAY_NORMAL` | Range checks | Sensor missing (most phones have no temperature or humidity sensor) |
| Acceleration, gyroscope | `TYPE_ACCELEROMETER`, `TYPE_GYROSCOPE` | `SENSOR_DELAY_GAME` | — | Sensor missing |
| Gravity | `TYPE_GRAVITY`, falling back to a low-passed accelerometer | `SENSOR_DELAY_UI` | — | Neither present |
| Steps | `TYPE_STEP_COUNTER` | `SENSOR_DELAY_NORMAL` | A baseline is saved when steps are turned on | No sensor, or `ACTIVITY_RECOGNITION` denied (Android 10+) |
| Orientation | See the heading chain | `SENSOR_DELAY_UI` | Circular low-pass (α = 0.25) | No usable chain |
| Sound level (dBFS) | `AudioRecord` | 15 updates/s | RMS → dBFS | Microphone denied |

The sound meter reports **dBFS**, not dB SPL. Phone microphones are not calibrated, so an SPL figure would be invented. Audio is measured in memory and discarded.

## Heading chain

`OrientationRepository.headingSource` picks the first available source:

1. **`TYPE_ROTATION_VECTOR`** (fused accelerometer, gyroscope and magnetometer). This is the best choice.
2. **Accelerometer + magnetometer** (`getRotationMatrix`). It is noisier and sensitive to hand movement.
3. **`TYPE_GAME_ROTATION_VECTOR`**. It has no magnetometer, so the heading is **relative, not north**. The UI labels it that way.

If none are available, the compass reads `Unavailable(NoHardware)`.

### Remap modes (`Hold`)

| Mode | Use | Remap |
|---|---|---|
| `Flat` | Phone lying flat, screen up (compass dial) | Remaps X/Y by display rotation |
| `Upright` | Phone held up, camera forward (Sight) | Remaps X/Z by display rotation, so the heading is where the camera points |
| `Auto` | Switches to Upright when the phone tilts more than about 60° from flat (\|R[8]\| < 0.5) | — |

The display rotation (0/90/180/270) is read from the default display, so landscape and reverse orientations give the right heading.

## True north and declination

The declination is calculated using bundled World Magnetic Model (WMM) tables in `:core` (`WorldMagneticModel`) with the current fix, altitude, and time. True heading is `mod360(magnetic + declination)`, which always lies in [0, 360).

TrailBlazer embeds WMM2025/2030 coefficients directly in `:core`, guaranteeing sub-0.1° declination accuracy anywhere on Earth regardless of device OS vintage or expired firmware tables. When an instant falls outside the active WMM epoch or if evaluation fails, it falls back gracefully to Android's platform `GeomagneticField`.

## Calibration and compass leveling

When the magnetometer reports `SENSOR_STATUS_UNRELIABLE` or `ACCURACY_LOW`, the Now tab shows a calibration chip: move the phone in a figure-of-eight away from metal, magnetic cases and electronics. Android recalibrates by itself; the app only reports the status.

### Compass leveling & accuracy beam

A 2D compass dial is mathematically and physically at peak accuracy when held flat (screen perpendicular to gravity), because Earth's magnetic field dips sharply into the ground at mid-latitudes (60°–75° inclination). Tilting the device leaks vertical field components into horizontal heading calculations.

- **Level reticle & spirit bubble:** The center of the compass rose renders an integrated target reticle and dynamic spirit bubble. When the device is within ±2° of level (calibrated), the bubble snaps to center, turns the theme's good status color with a soft halo, and triggers a subtle haptic click (Pixel Camera leveling style).
- **Pixel camera visor & case zero calibration:** Devices with protruding camera visors (such as Pixel 6 through 10 Pro XL) or uneven cases rest with an inherent 2.5°–3.5° pitch tilt on tables or map boards. Users can tap the Level/Tilt chip on the Now compass card or "Set zero" in the Level tool to calibrate the resting surface as zero. Offsets are persisted across restarts in `Settings` (`levelPitchOffsetDeg`, `levelRollOffsetDeg`) and shared across the compass dial, reticle, and level tool.
- **Confidence wedge (accuracy beam):** An angular wedge matching Android's estimated heading accuracy (`SensorEvent.values[4]` on `ROTATION_VECTOR`) radiates around the forward lubber line (Google Maps style), visually communicating directional certainty. The beam dynamically broadens with tilt penalty when held off-level, illustrating the physical degradation from vertical geomagnetic field dip leakage.
- **Hysteresis & posture filtering:** `Hold.Auto` employs a 10° hysteresis deadband (switches to upright above 65° tilt, returns to flat below 55° tilt) to eliminate mode-chattering. Circular low-pass filters are cleanly reset on coordinate remapping or display rotation to prevent cross-frame contamination.
- **Contextual tilt guidance:** When tilted between 3° and 60°, the card notes the tilt angle and provides a hint to hold the device level.

## Location

`LocationRepository` uses `LocationManagerCompat`:

- **API 31+:** the `fused` provider.
- **API 24–30:** GPS and network providers together.

There is no Google Play Services dependency.

| Field | Source | Notes |
|---|---|---|
| Position | `Location.latitude/longitude` | Fixes with invalid coordinates (NaN, out of range) are dropped |
| Accuracy | `Location.accuracy` | Buckets: ≤10 m High, ≤30 m Medium, ≤100 m Low, otherwise Unreliable |
| Altitude | `mslAltitudeMeters` on API 34+ (`AltitudeConverter`, run on IO), otherwise the WGS-84 ellipsoid height | The datum is labelled in the UI. The ellipsoid height can differ from sea level by tens of metres. |
| Speed, bearing | `Location.speed/bearing` | `null` when not reported |

Every optional field is checked at this boundary, because mock providers and some chipsets send impossible values, and a NaN accuracy passes every `accuracy <= x` filter unnoticed. A field that is not finite or not physically possible becomes `null` ("not reported"); the fix itself is kept:

| Field | Kept when |
|---|---|
| Accuracy, vertical accuracy | finite, 0–100 000 m |
| Altitude | finite, −1 000 to 20 000 m |
| Speed | finite, 0–400 m/s |
| Bearing | finite; wrapped into [0, 360) |

`live()` becomes `Unavailable(PermissionDenied)` or `Unavailable(Disabled)` as soon as the permission is revoked or location is switched off (it listens for `PROVIDERS_CHANGED_ACTION` and `MODE_CHANGED_ACTION`).

**Last known position.** `lastKnown(maxAgeMs)` returns the newest position the system already holds (from the fused, GPS, network or passive provider), through the same validation as a live fix. It returns null without permission, with location switched off, or when nothing is recent enough. Only Stargazing uses it: while the live fix is still *Acquiring*, it takes a position up to 24 h old, labelled "last known; waiting for GPS". Tracking, the speed alert and *Mark waypoint* never see it.

## External BLE sensors

The Now tab can connect one Bluetooth Low Energy peripheral: a GNSS receiver or a barometer / thermometer / hygrometer. Scanning and connecting need the *Nearby devices* permission (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`) on Android 12+. `BLUETOOTH_SCAN` is declared `neverForLocation`: the app lists nearby sensors and never derives the phone's position from scan results, so Android 12+ needs no location permission for the scan (some beacon advertisements are filtered out, which does not affect location, environmental or serial sensors). On Android 11 and older a BLE scan returns nothing unless location access is allowed, so the dialog asks for that instead. A scan stops after 30 s, when the dialog closes, or when it fails (the reason is shown).

Only these characteristics are subscribed to, each only if the peripheral notifies on it; nothing else is touched:

| Service | Characteristic | Becomes |
|---|---|---|
| Location and Navigation (0x1819) | Location and Speed (0x2A67), Position Quality (0x2A69) | A position (`ble_lns`) |
| Environmental Sensing (0x181A) | Pressure (0x2A6D), Temperature (0x2A6E), Humidity (0x2A6F) | Barometer, ambient temperature, humidity |
| Nordic UART (6E400001-…) | TX (6E400003-…) | NMEA 0183 text (`ble_nmea`) |

A capability is offered only after its notifications were enabled. GATT operations (MTU request, descriptor writes, reads) run one at a time through `GattOpQueue`; an operation that fails or does not answer within 5 s is skipped and the capability it provided is dropped. The phone's own sensor is replaced only for a channel the peripheral really provides: a thermometer without a Pressure characteristic leaves the phone barometer running, and a barometer-only device leaves the phone GPS running.

**What counts as a fix.**

| Source | Accepted when | Accuracy given to the fix |
|---|---|---|
| NMEA GGA, RMC, GLL | Checksum present and correct; fix quality 1–5 (GGA) / status A and mode not N, E, M, S (RMC, GLL); minutes < 60; valid time | GGA: HDOP × 5 m. Otherwise 15 m (`BleFixPolicy.DEFAULT_ACCURACY_M`) |
| LNS Location and Speed | Location present; coordinates in range; and, only for a device whose LN Feature (0x2A6A, read once at connect) sets bit 20 *Position Status Supported*, Position Status *Position Ok* or *Estimated* | EHPE from Position Quality (≤ 10 s old) or HDOP × 5 m; otherwise 15 m. *Estimated* is 100 m, so it is shown but never recorded |

A device that does not support Position Status sends 0 in those bits next to valid coordinates, so its fixes are accepted. Until the LN Feature read completes, and if it fails, the device is treated as not supporting Position Status: a pending or failed read never drops fixes. An HDOP, EHPE or accuracy of 0 means "unknown", not "perfect", and falls back to the 15 m default.

Every BLE fix carries an accuracy, because `TrackRecorder` drops fixes without one and keeps only those at 30 m or better. The 15 m default is for a receiver that says the fix is valid but gives no error estimate. GGA and RMC (and GLL) that share one UTC second are merged into one fix; a receiver that sends only one of them delivers each fix after a 0.5 s wait. The fix time is the receiver's UTC (RMC date plus time) when it is within a day of the phone clock, else the time of day on the nearest calendar day.

## GNSS

`GnssRepository` uses `GnssStatusCompat` and reports, for each satellite:

- constellation (GPS, GLONASS, Galileo, BeiDou, QZSS, SBAS, IRNSS)
- SVID, C/N0 (dB-Hz), elevation, azimuth
- whether it was used in the fix

It needs precise location permission.

`Satellite.of` validates each chipset report: a satellite whose elevation is not finite or outside ±90°, or whose azimuth is not finite, is dropped. Azimuth is wrapped into [0, 360). C/N0 is clamped to 0–99 dB-Hz, and an unreadable C/N0 is shown as no signal.

## Temperature inside the phone

`ThermalRepository` shows, on the Sensors screen only:

- **Battery:** from the sticky `ACTION_BATTERY_CHANGED` broadcast (tenths of a degree), which needs no permission.
- **Chip temperatures:** a manufacturer's private sensor types whose type name ends in `temperature` or `_temp`. On a Pixel 10 Pro XL these are `com.google.sensor.pressure_temp` (barometer chip) and `com.google.sensor.gyro_temperature` (gyroscope chip).

These follow the phone's own heat (charging, the processor, a hand, a pocket), so the screen says so. They are never used as air temperature, for density altitude or for weather.

The infrared thermometer on Pixel 8 Pro and later (Melexis MLX90632, `com.google.sensor.fir_temperature`) is left out on purpose. It needs `com.google.sensor.permission.FAR_INFRARED_TEMPERATURE`, which is `signature|preinstalled` (`adb shell pm list permissions -f`), so no installed app can be granted it.

## Non-finite sensor samples

A sensor event with a NaN or infinite value is dropped where it arrives, never passed on:

- **Barometer, light, temperature, humidity:** finite and within a physical range.
- **Accelerometer, gyroscope, gravity:** all three axes finite. The gravity fallback low-pass (`g += 0.1·(a − g)`) would otherwise stay NaN for good after one bad sample.
- **Rotation vector:** a sample whose pitch or roll is non-finite is skipped. `CircularLowPass.update` returns null for a non-finite azimuth and leaves its state untouched.
- **Step counter:** finite and non-negative. `NaN.toLong()` is 0, which would look like a counter reset.

## Barometric altitude and weather

- **Altimeter:** the barometer gives an ISA altitude against a QNH. QNH is either the standard 1013.25 hPa or one the user sets from a known elevation or a local report. The label always says which.
- **Pressure history:** `PressureSampler` stores one sample every 10 minutes while the app is in the foreground, with the GPS elevation at that moment if a fix is under 2 minutes old. Samples are kept for 7 days.
- **Trend:** see `ALGORITHMS.md`.

## Sensor stream logger

*Sensors* (Diagnostics) has a **Record sensor data** switch. It writes raw accelerometer, gyroscope and barometer events, and raw GNSS measurements where the device reports them, to a CSV file in the app's private storage. Afterwards the user saves it with the system file picker, or discards it.

### Design

| Piece | Where | Role |
|---|---|---|
| `SensorCsv`, `ClockAnchor`, event rows | `core/.../sensors/log/SensorCsv.kt` | The format: RFC 4180 escaping, number text, UTC conversion. Android-free, tested on every `:core` target. |
| `SensorLogger` | `sensors/SensorLogger.kt` | One recorder per process (`AppContainer.sensorLogger`). Owns listeners, queue, writer, limits and the state the UI shows. |
| `SensorSource.register` | `sensors/SensorSource.kt` | The existing sensor seam, extended with a plain callback registration. `samples()` and `batchedSamples()` are built on it, so there is still one place that talks to `SensorManager`. |
| `GnssMeasurementSource` | `sensors/GnssMeasurementSource.kt` | Raw `GnssMeasurementsEvent` through `LocationManagerCompat`. Separate from `GnssRepository`, which reports `GnssStatus` (the satellite list). |
| `SensorLogFiles`, `SensorLogExporter` | `sensors/` | Private files and orphan repair; the bounded, cancellable copy to the picked document. |
| `SensorRecorderCard` | `ui/tools/` | The switch, live counters, Save and Discard. A stateless body plus a thin wrapper. |

**The sensor callback never touches the disk and never waits.** Each event is copied once (`SensorSample`) and offered with `Channel.trySend` to a bounded queue (16,384 events). One writer coroutine on `Dispatchers.IO` drains it into a 64 KiB `BufferedWriter` and flushes about once a second, so a crash loses at most that. The flush also runs when the queue stays empty (the writer waits for the next event for at most the flush interval, then flushes and checks free storage), so rows do not sit in memory when the sensors go quiet, and the foreground/background markers are flushed at once. Sensor callbacks run on a `HandlerThread`, not the main thread, and the UI sees counters published every 250 ms, not per sample, so recording does not cost UI frames.

**Overflow policy: drop the newest, and count it.** When the queue is full the incoming event is discarded and a counter goes up. The next event that does fit carries the drops that came before it, so the writer puts the `GAP` row exactly where the loss happened: after the last event that was kept, before the next one. The row says `dropped=N`, `first_dropped_ns` and `last_dropped_ns` (the sensor-clock times of the first and last lost event; the row's own `elapsed_ns` is the first) and `total_dropped`. Drops after the last event that fit are written as one `GAP` row (`at=end`) before the closing row, which carries the total. The screen shows the count in the error colour with a sentence saying the log has gaps. Events that were queued but never written because the recording stopped early (size cap, full disk) are counted as dropped too (`dropped_at_stop`). The invariant, tested with a 100,000 event burst: `written + dropped` equals the events delivered.

### Rates

| Sensor | Requested | Why |
|---|---|---|
| Accelerometer, gyroscope | 200 Hz (period 5,000 µs), or the sensor's `minDelay` if that is slower | Android 12+ limits the accelerometer, gyroscope and magnetometer to 200 Hz for an app without `HIGH_SAMPLING_RATE_SENSORS`, and a higher request can throw a `SecurityException`. TrailBlazer does not declare that permission (adding it is a product and security decision), so `SamplingRates` refuses anything above 200 Hz. `SENSOR_DELAY_FASTEST` is never used. |
| Barometer | 25 Hz, or `minDelay` if slower | The barometer is not among the rate-limited sensors. 25 Hz is a request, not a promise: phone barometers commonly top out around that rate, and the file records what the sensor delivered. |
| GNSS raw measurements | The chipset's rate (normally 1 Hz) | Not configurable through the platform API. |

Phones can deliver less than they are asked for. The closing row records what each sensor actually delivered (`observed_hz`). The rate-limit page also says motion sensors are limited whatever the permission when the user has switched the microphone off with the device toggle.

Sources: [Sensors overview, rate limiting](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview), [`SensorEvent.timestamp`](https://developer.android.com/reference/android/hardware/SensorEvent#timestamp), [Android 9: limited access to sensors in the background](https://developer.android.com/about/versions/pie/android-9.0-changes-all), [raw GNSS measurements](https://developer.android.com/develop/sensors-and-location/sensors/gnss).

### Limits (the recording stops by itself, with a visible reason)

| Limit | Value | Stop reason shown |
|---|---|---|
| File size | 200 MiB (about 90 minutes of accelerometer and gyroscope at 200 Hz) | "reached its size limit" |
| Duration | 4 hours | "reached its time limit" |
| Free storage | Does not start below 64 MiB free; stops if free space falls below it (checked at each flush) | "almost out of storage" |
| Write error | Any `IOException` | "could not be written to storage (…)" |

A stopped recording stays on the phone until the user saves or discards it. It is deleted only after a save succeeded, or by the explicit, confirmed **Discard**. Starting another recording is refused while one is waiting.

### Lifetime

The recorder belongs to the application, not to the screen. Leaving Diagnostics does not stop it, and no screen holds a listener, so none can leak. It stops when the user stops it, at a limit, on a write failure, or when the process ends. Closing the sources is in `finally`, including when the app scope is cancelled, and a cancelled recording still ends as a whole-rows file with its closing row. No foreground service and no permission are added.

**Background.** Android 9+ does not deliver continuous-reporting sensors to an app in the background that has no foreground service ([Android 9 behaviour changes](https://developer.android.com/about/versions/pie/android-9.0-changes-all): use a foreground service). The sensor logger starts none, but TrailBlazer's track recorder does (type `location`, with its notification). **While a track is recording, the app has a foreground service, so the sensors and raw GNSS keep flowing with the screen off and the sensor recording continues.** That follows from the Android 9 page; it was not measured on a device. With no track recording, the recording has gaps while the app is not visible (screen off, another app in front); `APP_BACKGROUND` and `APP_FOREGROUND` rows mark them (flushed at once), and the card's text says both things. A configuration change (rotation) is not reported as leaving the foreground.

**Always visible.** While a sensor recording runs, a "Recording sensors · m:ss · Stop" chip is shown on every screen (above the tab bar when there is one), and Stop works from there. A recording started in Diagnostics therefore cannot run unnoticed on another screen or with the phone in a pocket.

**Delete all data.** Settings → Delete all data stops a running sensor recording, cancels a save in progress and deletes every file in `noBackupFilesDir/sensorlogs` (finished, unsaved and half-written). If the recording does not stop in time, nothing at all is deleted and the dialog says so; if a file cannot be deleted the dialog says that. Raw GNSS with UTC timestamps is location history, so it is covered like tracks and waypoints.

**Other users of the accelerometer.** The recorder holds the accelerometer at 200 Hz. The GPS duty cycle (`GnssDutyCycle`) also listens to it, asking for 2 Hz batched. Android may deliver faster than a listener asked, so while a recording runs that listener probably receives 200 Hz too (inferred from the `registerListener` contract, not verified on a device). Its stillness gate is tuned for about 2 Hz and, fed 200 Hz, would follow real walking and read it as rest; so `GnssDutyCycle` now passes the gate at most one sample per 400 ms. A test feeds six minutes of 200 Hz walking and requires the receiver to stay on.

**Process death.** While recording, the file is `trailblazer-sensors-<UTC>.csv.part` in `noBackupFilesDir/sensorlogs/` (so it is not in cloud backup or device transfer). A normal stop renames it to `.csv`. On the next start, a `.part` file is an orphan: it is cut back to its last whole line, given a closing row (`stop_reason=interrupted;recovered=true;truncated_bytes=N`), renamed and offered with the message that it may end early. An orphan with no whole line is deleted. A `.csv` that was never saved is offered again too.

### CSV format

UTF-8, CRLF, RFC 4180: a field with a comma, quote or line break is quoted and its quotes doubled. One header row, then one row per event. Numbers are written with `toString()`, so there is no locale (no decimal commas) and a Float keeps its own digits (`0.1`, not `0.10000000149011612`). NaN and infinities are written as `NaN`, `Infinity`, `-Infinity`; an **empty cell means "not reported"**. Marker notes are `key=value;key=value` on one line.

| Column | Meaning |
|---|---|
| `utc_iso` | `yyyy-MM-ddTHH:mm:ss.ffffffZ`, microseconds. The sensor clock anchored once to the wall clock at the start (`utc = anchor_wall_ms + (elapsed_ns − anchor_elapsed_ns)`), so a later NTP step or clock change cannot make it jump. The two anchor values are in the `SESSION_START` row. Accuracy is about a millisecond plus the sensor clock's drift against the wall clock over the session. |
| `elapsed_ns` | The event time as the platform gave it. `SensorEvent.timestamp` uses the `SystemClock.elapsedRealtimeNanos()` time base. For GNSS it is `GnssClock.elapsedRealtimeNanos` when the chipset reports it (API 29+), otherwise the time the callback ran, and `note` says `elapsed=callback`. |
| `sensor_type` | `ACCELEROMETER` (m/s², gravity included), `GYROSCOPE` (rad/s), `PRESSURE` (hPa, in `x`), `GNSS_MEASUREMENT`, or a marker: `SESSION_START`, `SESSION_END`, `GAP`, `APP_BACKGROUND`, `APP_FOREGROUND`, `GNSS_RAW_STATUS`. |
| `x`, `y`, `z` | Raw values, unfiltered (a driver NaN stays NaN). A sensor reporting more than three values puts the rest in `note` as `v3=…;v4=…`. |
| `accuracy` | The raw `SensorEvent.accuracy`: 0 unreliable, 1 low, 2 medium, 3 high. Empty for GNSS and markers. |
| `svid`, `constellation` | Satellite number within its constellation, and the `GnssStatus.CONSTELLATION_*` code (1 GPS, 2 SBAS, 3 GLONASS, 4 QZSS, 5 BeiDou, 6 Galileo, 7 IRNSS; 0 on API below 26). |
| `cn0_dbhz` | Carrier-to-noise density, dB-Hz. |
| `received_sv_time_ns`, `received_sv_time_unc_ns`, `state` | The satellite's transmit time relative to the start of its system week (GLONASS: day), its 1-σ uncertainty, and the `GnssMeasurement.STATE_*` bit mask that says how far that time can be trusted. |
| `pseudorange_rate_mps`, `pseudorange_rate_unc_mps` | Uncorrected pseudorange rate (m/s) and uncertainty. |
| `time_offset_ns` | `GnssMeasurement.getTimeOffsetNanos`: measurement time = `clock_time_ns` + this. |
| `adr_m`, `adr_state` | Accumulated delta range (carrier phase, metres) and its state bit mask. |
| `carrier_freq_hz` | Carrier frequency; empty when the chipset does not say. |
| `clock_time_ns`, `full_bias_ns`, `bias_ns`, `hw_clock_discontinuity` | `GnssClock.getTimeNanos`, `getFullBiasNanos`, `getBiasNanos` (empty when not reported) and `getHardwareClockDiscontinuityCount` (a change means the receiver clock jumped: do not smooth across it). |
| `note` | Marker text, or `elapsed=callback` on a GNSS row. |

**The logger does not compute pseudoranges.** It records what a pseudorange needs: GPS time = `clock_time_ns` − (`full_bias_ns` + `bias_ns`), and the satellite transmit time `received_sv_time_ns`. Turning those into metres needs week and day rollover and constellation time-system handling, which belong in analysis software, and a guess made on the phone would look like a measurement.

**Rows that say how complete the file is.**

- `SESSION_START`: `csv_format`, `device`, the UTC anchor, the period requested from each sensor in µs (or `absent` / `refused`), `gnss_raw=on` or `off(<reason>)`, the limits, `overflow=drop_newest`.
- `GAP`: `dropped=N`, `first_dropped_ns`, `last_dropped_ns`, `total_dropped`; at the loss, or `at=end` for drops after the last kept event.
- `SESSION_END`: `stop_reason` (`user`, `size_cap`, `duration_cap`, `disk_low`, `write_failed`, `cancelled`, `interrupted`), `accepted`, `written`, `dropped`, `dropped_at_stop`, `late_after_stop`, `duration_ms`, per-sensor `counts` and `observed_hz`.

A file with no `SESSION_END` row, or with `recovered=true`, ended without the app closing it.

### Saving

`ActivityResultContracts.CreateDocument("text/csv")` opens the system picker with a suggested name, so the app needs no storage permission and the user chooses the location (including a USB drive or a cloud provider). The copy runs on IO in 64 KiB chunks, bounded by the file's size, checks for cancellation between chunks (there is a Cancel button) and is not reported as saved until the output stream has flushed and closed without error. A null result (the user backed out) changes nothing. A write failure or a full destination keeps the private file, deletes the half-written destination document, and shows the reason ("not enough space at the chosen location", or the system's message).

### Not verified on a device

The 200 Hz delivery rate, `minDelay` on specific phones, raw GNSS availability and field coverage (chipset dependent; mandatory since Android 10 per the GNSS page, but optional fields vary), whether sensors and raw GNSS really keep flowing with the screen off while a track records (inferred from the Android 9 page), what rate other listeners see while a recording holds 200 Hz, and the picker against third-party document providers were not exercised on hardware. The tests use a fake `SensorSource` and a fake GNSS source.

## Camera sighting

The Sight screen shows a CameraX `PreviewView` with bearings overlaid. The horizontal field of view comes from `CameraCharacteristics`:

```
2·atan(sensorWidth / (2·focalLength))
```

It is adjusted for the visible crop, which is why the degree strip lines up with the image. Heading comes from `OrientationRepository` in `Upright` mode.

## SOS

- **Torch:** `CameraManager.setTorchMode`, which needs no camera permission. It is turned off in `finally`.
- **Whistle:** `AudioTrack` tone at 3150 Hz with 10 ms ramps.
- **Screen:** flashes at no more than about 2.5 Hz, with a photosensitivity warning.

All three follow the morse `...---...` timeline (1 unit = 200 ms). They stop on `ON_STOP`, so leaving the screen always silences them.
