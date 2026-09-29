# Algorithms

Everything here is in `:core` and is tested on the JVM. The sign conventions are the same everywhere:

- **Latitude:** degrees, north positive, in [−90, 90].
- **Longitude:** degrees, east positive, in [−180, 180].
- **Azimuth and bearing:** degrees clockwise **from north**, in [0, 360).
- **Altitude and elevation:** degrees above the horizon.
- **Times:** epoch milliseconds (UTC). Callers pass a local-day window `[start, end)`, so "the sunrise on this day" follows the traveller's time zone.

## Time

- **Julian day** from epoch ms (Meeus). JDE = JD + ΔT.
- **ΔT** uses the Espenak & Meeus (NASA, 2006) polynomials, which are valid for 1900–2150. Outside that range the nearest fit is used. The error there is minutes, which only matters for lunar work far from the present.

## Sun (`astro/Sun.kt`, `SolarDay.kt`)

- **Position:** Meeus ch. 25 (low accuracy, about 0.01°), which is the algorithm behind the NOAA calculator. It uses apparent longitude, true obliquity with low-precision nutation (ch. 22, about 0.5″), and gives right ascension and declination.
- **Horizontal coordinates** (`HorizontalTransform`): hour angle from apparent sidereal time (ch. 12), then
  `az = atan2(sin H, cos H·sin φ − tan δ·cos φ) + 180°`, so azimuth counts from north.
  The web version added 180° a second time, which made noon in Denver read 358.5°.
- **Refraction:** the NOAA piecewise approximation (three regimes: above 5°, near the horizon, and below −0.575°). It is applied only to displayed altitudes; rise and set use the fixed h0 below.
- **Events:** zero crossings of `altitude(t) − h0` over the local day, found with `RiseSetFinder`.

  | Event | h0 |
  |---|---|
  | Sunrise and sunset | −0.833° (34′ refraction + 16′ semidiameter) |
  | Civil twilight | −6° |
  | Nautical twilight | −12° |
  | Astronomical twilight | −18° |
  | Golden hour | −4° to +6° |
  | Blue hour | −6° to −4° |

  Solar noon is upper transit: the rising zero of sin(hour angle). `DayType` is `PolarDay` or `PolarNight` when there is no horizon crossing in the window. Day length at the pole in midsummer is therefore 24 h, where the web version said 0.

## Moon (`astro/Moon.kt`, `Lunar.kt`)

- **Position:** Meeus ch. 47. The 47.A and 47.B series are truncated to the largest terms, which gives about 0.01° in longitude.
- **Topocentric altitude:** the geocentric altitude minus the parallax: `π·cos(alt)`.
- **Rise and set** use the geocentric altitude against `h0 = 0.7275·π − 0°34′` (Meeus ch. 15), with π the horizontal parallax. The moon can rise or set twice, or not at all, in a day; `RiseSetFinder` reports whatever it finds and `null` otherwise.
- **Phase:** illumination `k = (1 + cos i)/2` from the phase angle (ch. 48). The name and age come from the same elongation, so they always agree.
- **Next new moon, full moon and quarters:** the first rising zero of `sin(elongation − target)` within 35 days. It is found with `RiseSetFinder` (12 h step, 5 s tolerance), and the instant is checked against the USNO phase times in the tests.

## `RiseSetFinder`

This is Montenbruck & Pfleger's method (*Astronomy on the Personal Computer*, §3.8):

1. Sample the function at `t − h`, `t`, `t + h` (h = 1 h by default).
2. Fit a parabola to the three samples and solve for its roots. This finds up to two crossings per interval, such as a grazing moon or the short nights of polar summer.
3. Refine each root by bisection on the real function to 1 s.

Every root is checked against the real function, so a crossing is never guessed from the parabola alone.

## Geodesy (`geo/Geo.kt`)

- **Distance:** haversine on a sphere with R = 6371.0088 km (the IUGG mean radius). Against Vincenty on WGS-84 the error is within 0.6% (checked in tests). That is good enough for trip legs, which are straight-line distances by design; road distance is not guessed.
- **Initial and final bearing, destination point, cross-track distance:** from the standard spherical-trigonometry formulas. Results are normalised with `mod360`.
- **`angleDiff(a, b)`** returns a value in (−180, 180]. It is used for the dial's shortest-path animation and for the north-crossing buzz (`crossesNorth`), so the 359° → 1° wrap is correct.

## Coordinate parsing (`geo/CoordinateParser.kt`)

It accepts:

- decimal (`46.5582, 7.8352`, and hemisphere letters)
- DMS (`46°33'29.5"N 7°50'6.7"E`)
- `geo:` URIs
- Google Maps `@lat,lon`, `/place/`, `?q=`, `/dir/` and `?api=1` directions links (which keep every waypoint)
- OpenStreetMap `#map=z/lat/lon` and `mlat/mlon` links
- Apple Maps `ll=` / `daddr=` / `sll=` links

**Short links** (for example `maps.app.goo.gl`, `goo.gl`, `osm.org/go/`) are *refused* with `ShortLinkNeedsNetwork`. Resolving them would need a network request to Google, and the app is offline by default.

## Atmosphere (`atmo/Atmosphere.kt`)

- **ISA altitude:** `h = 44330.77 · (1 − (p/QNH)^0.190263)`. QNH from a known elevation is the inverse.
- **Density altitude:** `ρ = p/(R_d·T)`, then the ISA altitude for that density: `44330.77·(1 − (ρ/1.225)^0.234969)`. It returns **null** without a real outside-air temperature. The web version substituted the standard temperature, which always gives the pressure altitude, and it also counted the elevation twice.
- **Boiling point:** the Antoine equation for water (valid 1–100 °C).
- **Dew point:** Magnus–Tetens with the Alduchov & Eskridge (1996) constants.

## Weather (`weather/Weather.kt`)

### Pressure trend

`PressureTrend` takes the stored samples from the last 3 hours and picks a *basis*:

| Basis | When | Series used |
|---|---|---|
| `Station` | Elevation spread ≤ 20 m | Raw station pressure (most precise) |
| `SeaLevel` | Elevation changed | Each sample reduced to sea level with its own GPS elevation |
| `StationElevationUnknown` | No elevation data | Station pressure. The label says this is correct only if you stayed at the same elevation. |

The slope is a least-squares fit over the samples actually in the window, scaled to **hPa per 3 h**. It needs at least 60 minutes of span and at least 6 samples; otherwise it returns `Insufficient`. The web version always divided by 3 h, whatever the window.

A 300 m descent at constant sea-level pressure reads *Steady* (tested). That is the case the web version got wrong: descending read as "rising, fine weather".

### Tendency: one scale for every label

Thresholds on |Δp| in hPa per 3 h, after the UK Met Office wording:

| Tendency | Threshold |
|---|---|
| Steady | < 0.5 (the Met Office uses 0.1; phone barometers drift more) |
| Slowly | < 1.6 |
| (plain) | < 3.6 |
| Quickly | ≤ 6.0 |
| Very rapidly | > 6.0 |

### Storm alert

The alert turns on at a fall of ≥ 4 hPa/3 h and turns off only once the fall is gentler than 2 hPa/3 h. The hysteresis stops it flickering.

### Zambretti

The Negretti & Zambra forecaster. It needs sea-level pressure in 950–1050 hPa and the 3-hour tendency; outside that range it returns null. It uses the standard Z-number formulas and letter tables for falling, steady and rising pressure. It is a rule of thumb for temperate latitudes, labelled "Local forecast (Zambretti)" in the Sky tab.

## Tracks (`track/Track.kt`)

- **`TrackStats`** streams over the points:
  - distance (haversine sum)
  - moving time (speed ≥ 0.5 m/s)
  - maximum speed
  - elevation gain and loss with a **3 m hysteresis** band, so GPS and barometer noise does not add up to phantom climbing
- **`DouglasPeucker`** is iterative (no recursion, so a 100k-point track cannot overflow the stack). `simplifyToMax(n)` searches for the smallest tolerance that yields ≤ n points. Imported routes over 50 points are simplified to 50 stops. The track detail map path is stride-sampled while streaming, then simplified to at most 500 points. Charts average the points into fixed buckets. Stored tracks are never altered.

## GPX and KML (`io/`)

- **`XmlPull`** is a minimal streaming reader. It never expands DTDs or entities beyond the five predefined ones and numeric character references, so XXE and "billion laughs" inputs have no effect.
- **`GpxReader.read` and `KmlReader.read`** stop with an error beyond 1,000,000 points (`maxPoints`).
- **`GpxReader`** reads `wpt`, `rte`/`rtept` and `trk`/`trkseg`/`trkpt`. A track without timestamps is imported as a *route*; the app does not invent times.
- **`KmlReader`** reads `Point` and `LineString` placemarks.
- **Writers** escape `& < > " '` and stream their output. They write tracks as GPX `trk` and KML `LineString`.

## Map hand-off (`intents/MapLinks.kt`)

- **`geo:lat,lon?q=lat,lon(label)`** works with any installed map app.
- **Google Maps directions:** `https://www.google.com/maps/dir/?api=1&origin=…&destination=…&waypoints=…&travelmode=…`. Google allows at most **9 intermediate waypoints**, so longer trips are split into chained links, each starting where the last ended.
- **OpenStreetMap directions** support two points only, so there is one link per leg.

The app builds the URL and fires an intent. It never contacts a map service itself.

## Trips (`trip/Trip.kt`)

- **`LegCalculator`:** straight-line legs, with distance and initial bearing.
- **`TripRules`:** at least two stops, no two consecutive stops at the same place, and planned dates that never go backwards.
- **`DaylightPlanner`:** sunrise and sunset at each stop on its planned day, in that stop's local-day window.

## Motion (`motion/Motion.kt`)

- **Inclination:** pitch and roll from gravity, and the gradient as `tan(pitch)·100 %`.
- **g-force:** `|a| / 9.80665`.
- **`OrbitCamera`:** the simple perspective projection used by the 3D accelerometer plot.

## References

- Jean Meeus, *Astronomical Algorithms*, 2nd ed., Willmann-Bell, 1998.
- O. Montenbruck & T. Pfleger, *Astronomy on the Personal Computer*, 4th ed., Springer, 2000.
- NOAA Global Monitoring Laboratory, *Solar Calculation Details*.
- F. Espenak & J. Meeus, *Polynomial Expressions for Delta T*, NASA.
- ICAO Doc 7488, *Manual of the ICAO Standard Atmosphere*.
- O. Alduchov & R. Eskridge, "Improved Magnus form approximation of saturation vapor pressure", *J. Appl. Meteor.* 35, 1996.
- T. Vincenty, "Direct and inverse solutions of geodesics on the ellipsoid", *Survey Review* 23, 1975.
- D. Douglas & T. Peucker, *The Canadian Cartographer* 10(2), 1973.
- UK Met Office, *Pressure tendency* terms. NOAA/NWS observing handbook.
