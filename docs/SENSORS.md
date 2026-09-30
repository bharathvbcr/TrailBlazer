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

The declination comes from Android's `GeomagneticField` for the current fix, altitude and time. True heading is `mod360(magnetic + declination)`, which always lies in [0, 360).

**Limitation:** `GeomagneticField` uses the World Magnetic Model built into the device's firmware. Older Android releases ship older WMM coefficients, whose error grows over the years after the model epoch (typically a few tenths of a degree, more near the poles and in magnetic anomalies). TrailBlazer does not ship its own WMM table.

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
