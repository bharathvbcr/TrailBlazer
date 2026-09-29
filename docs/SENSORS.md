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

## Calibration

When the magnetometer reports `SENSOR_STATUS_UNRELIABLE` or `ACCURACY_LOW`, the Now tab shows a calibration chip: move the phone in a figure-of-eight away from metal and magnets. Android recalibrates by itself; the app only reports the status.

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

`live()` becomes `Unavailable(PermissionDenied)` or `Unavailable(Disabled)` as soon as the permission is revoked or location is switched off (it listens for `PROVIDERS_CHANGED_ACTION` and `MODE_CHANGED_ACTION`).

## GNSS

`GnssRepository` uses `GnssStatusCompat` and reports, for each satellite:

- constellation (GPS, GLONASS, Galileo, BeiDou, QZSS, SBAS, IRNSS)
- SVID, C/N0 (dB-Hz), elevation, azimuth
- whether it was used in the fix

It needs precise location permission.

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
