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

## Planets (`astro/Planets.kt`)

- **Elements:** the JPL *Approximate Positions of the Planets* Keplerian elements (Standish & Williams 1992). Table 1 is used for 1800–2050 and Tables 2a/2b (with the extra mean-anomaly terms for Jupiter–Neptune) outside that range, back to 3000 BC and forward to AD 3000.
- **Method, as JPL describes it:** elements at T; ω = ϖ − Ω; M = L − ϖ (+ b·T² + c·cos fT + s·sin fT), wrapped to ±180°; Kepler's equation by Newton's method to 10⁻⁶° (at most 30 steps); rotate to the J2000 ecliptic; ε = 23.43928° to the equator.
- **Earth** is the Earth–Moon barycentre, which is at most 4,700 km from the Earth's centre.
- **Light time** is iterated three times, so the astrometric place is where the planet was when its light left.
- **Apparent place of date:** precession, nutation and aberration (see below). Altitude and azimuth use this.
- **Magnitude:** the Astronomical Almanac formulas quoted by Meeus (ch. 41), from r, Δ and the phase angle. Saturn adds its rings: the ring tilt B and the Sun–Earth difference ΔU in ring-plane longitude (Meeus ch. 45).
- **Checked against JPL Horizons (DE441)** for all seven planets on four dates between 1995 and 2040 (`PlanetsTest`). Worst errors in position: Mercury–Mars 0.74′, Jupiter 1.95′, Saturn 7.4′, Uranus 1.44′, Neptune 0.65′. Magnitudes agree with Horizons within 0.25. Horizons uses Mallama & Hilton (2018), and the largest difference is Venus at a large phase angle.

## Stars (`astro/Stars.kt`, `BrightStars.kt`)

- **Catalogue:** the 283 stars brighter than magnitude 3.5 in the Yale Bright Star Catalogue (5th revised edition, VizieR V/50), plus σ Octantis for southern polar alignment. Positions are J2000 (FK5), with proper motions.
- **Left out:** T Coronae Borealis and Mira. The catalogue lists them at maximum light (magnitude 2.0 and 3.0), but they are usually near magnitude 10 and 9. Plotting them as bright stars would show something that is not there.
- **Names:** IAU Working Group on Star Names; otherwise the Bayer designation in Greek (γ Per); otherwise the HR number.
- **Place of date:** proper motion (μα·cos δ and μδ, per year), then precession by the rigorous method of Meeus ch. 21 (ζ, z, θ from the IAU 1976 constants). Near the poles the declination comes from the cos form, because asin loses precision there. Checked against Meeus example 21.b (θ Persei, to 0.1″).
- **Apparent place:** nutation in right ascension and declination (Meeus 23.1) and annual aberration (Meeus 23.3, κ = 20.49552″). The first-order formulas clamp tan δ at |δ| = 89.5°, which costs well under 1″ on the sky. The Horizons comparison checks the whole chain. Our apparent-minus-astrometric difference matches Horizons' to under 0.05′ for every row.

## Night planning (`astro/NightSky.kt`)

- **The night window** runs from local noon to the next local noon. A night is never split at midnight, and DST nights are 23 or 25 hours long.
- **Darkness** is a list of intervals: astronomical darkness (Sun below −18°), nautical darkness (below −12°), and **moon-free darkness**. Moon-free darkness is astronomical darkness intersected with the Moon below the horizon, using the same h0 as moonrise and moonset. `Intervals.above` builds intervals from the `RiseSetFinder` crossings, and `Intervals.intersect` merges two sorted lists.
- **High latitudes in summer** have no astronomical darkness at all. The plan returns empty lists and the screen says so; it never pretends there is a dark window.
- **Planets and the Milky Way core:** rise, set and transit use the standard altitude −0.5667° for stars and planets (Meeus ch. 15). The best time is the highest altitude, sampled every 10 minutes while the Sun is below −12°. The Milky Way core is Sgr A* at J2000 17h 45m 40.0s, −29° 00′ 28″.
- **Polar alignment:** Polaris (HR 424) north of the equator, σ Octantis (HR 7228) south of it. The screen shows the local hour angle, the distance from the pole, and the position as seen facing the pole with the zenith up:
  - Facing north, stars circle the pole anticlockwise, so the angle from "up" is −HA.
  - Facing south, they circle clockwise, so the angle is +HA.
  - The tests check this against the star's actual azimuth six sidereal hours after culmination.
  - On the equator there is no answer, because the pole lies on the horizon.

## Meteor showers (`astro/MeteorShowers.kt`)

- **Data:** the IMO Working List of Visual Meteor Showers (Table 5 of the IMO 2026 Meteor Shower Calendar, J. Rendtel). It includes night-time showers with a ZHR of 5 or more, plus notable variable ones. A variable shower's ZHR is `null`, not a number.
- **Peaks** are defined by the Sun's longitude λ☉ for **equinox J2000**, which is how the IMO gives them. The app's solar longitude is apparent *of date*, so `solarLongitudeJ2000` removes aberration and nutation and subtracts general precession since J2000 (Lieske 1977). In 2026 that correction is about 0.36°, or 9 hours. The peak is the rising zero of sin(λ☉ − λpeak), found with `RiseSetFinder` in 1-day steps to 1 minute.
- **Checked:** every shower whose λ☉ is given to 0.1° or better lands on the IMO's 2026 maximum date. Integer-λ☉ showers land within 36 hours.
- **Hourly rate** for an observer is ZHR·sin(radiant altitude). This is the rate under the reference dark sky (limiting magnitude 6.5); the screen says that moonlight and light pollution lower it.

## Sky chart (`astro/SkyProjection.kt`)

- **Projection:** azimuthal equidistant, with the zenith at the centre and the horizon on the rim. The radius is (90° − altitude)/90°.
- **Orientation:** like a planisphere, held overhead with the direction you face at the bottom. Because you look up at it, it is the mirror of a map: with north at the top, east is on the left.
- **Facing** defaults to the equator (south in the northern hemisphere, north in the southern). With *Follow compass* it is the true heading, which needs both the compass and a position fix for the declination. Without them the chart does not rotate and the screen says why.
- **Tap to identify** (`identify` in `ui/stars/StargazeViewModel.kt`): the tap is converted to the same unit-disc coordinates, then the nearest object above the horizon within 0.09 of the disc radius is chosen. The score is distance + 0.004 × magnitude, so on a near tie the brighter object wins.
- **Altitude plots:** each planet and the Milky Way core are sampled every 15 minutes across the noon-to-noon window (about 97 points each). The core uses one apparent place for the whole night, since precession and aberration move it by arcseconds in that time.

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

- What a maps app's Share button produces: the place name and address on the lines before the link. The link is parsed and the first line names the place.

**Short links** (for example `maps.app.goo.gl`, `goo.gl`, `osm.org/go/`) are *refused* by the parser with `ShortLinkNeedsNetwork`: they carry no coordinates, and only the shortener knows where they point. The place dialogs then offer **Look up online**. Nothing is sent until it is tapped. `ShortLinks.resolve` (`geo/ShortLinks.kt`) then:

- asks only short-link hosts, over HTTPS (an `http://` short link is upgraded), for their `Location` header. The request is a GET whose body is never read, with redirects not followed by the connection, no cookies and 8 s timeouts;
- follows at most 5 hops, and stops on a loop, a dead link or a redirect off HTTPS;
- unwraps `consent.google.com?continue=` and `google.com/url?q=` interstitials from their parameter instead of loading them;
- never loads the map page the link ends at. That full link is parsed offline like any other.

Google often shares a named place as `maps.google.com/maps?q=<name>&ftid=…`, which has no coordinates even after the lookup. The app says so and suggests sharing a dropped pin; it does not scrape Google's page.

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
- **`TripRules`:** at least two stops and at most 50. Consecutive stops within 1 m count as the same place, which includes the antimeridian and both poles. Planned dates never go backwards, including across an undated stop. Stop ids are unique. The editor will not save a failing trip and will not hand it to a map app. An imported route is still stored, and the trip list marks it as needing a fix.
- **`DaylightPlanner`:** sunrise and sunset at each stop on its planned day, in that stop's local-day window.

## Motion (`motion/Motion.kt`)

- **Inclination:** pitch and roll from gravity, and the gradient as `tan(pitch)·100 %`.
- **g-force:** `|a| / 9.80665`.
- **`OrbitCamera`:** the simple perspective projection used by the 3D accelerometer plot.

## Moon phase pictures

The lit part of the disc is a half disc (the lit side) plus or minus half an ellipse. The ellipse is the terminator, with half-width r·(1 − 2k), where k is the illuminated fraction. Its area share is therefore exactly k. For a crescent (k < 0.5) the terminator bulges into the lit half; for a gibbous it bulges out of it. The disc is turned 180° when the lit side is the left: waning from the northern hemisphere, waxing from the southern. `LunarPhase.upcoming` finds each principal phase with `next()` (a root of sin(elongation − target)) and sorts the four by time.

## Level haptics (`motion/LevelHaptics.kt`)

Tilt is the larger of |pitch| and |roll|. Every cue has hysteresis, because a resting phone's reading jitters by about 0.1°:

- **Level:** fires on entering 0.5° and re-arms only beyond 1°.
- **Detent:** a tick when the tilt moves into another whole-degree band within 4°. The band changes only once the tilt is 0.1° past the edge.
- **Steep (vehicle mode):** fires beyond 20° roll or 25° pitch, and re-arms 2° under both.

No two cues come closer than 80 ms. A Level or Steep cue that falls inside that gap is kept and fires on the next reading, not dropped. Non-finite readings are ignored, and a clock that jumps backwards resets the gap.

## References

- Jean Meeus, *Astronomical Algorithms*, 2nd ed., Willmann-Bell, 1998.
- E. M. Standish & J. G. Williams, *Keplerian Elements for Approximate Positions of the Major Planets*, JPL Solar System Dynamics.
- JPL Horizons (DE441), used for the planet golden values.
- D. Hoffleit & W. H. Warren, *The Bright Star Catalogue*, 5th revised ed., 1991 (VizieR V/50).
- J. Rendtel, *IMO Meteor Shower Calendar 2026*, IMO INFO(3-25).
- J. H. Lieske et al., "Expressions for the precession quantities", *A&A* 58, 1977.
- O. Montenbruck & T. Pfleger, *Astronomy on the Personal Computer*, 4th ed., Springer, 2000.
- NOAA Global Monitoring Laboratory, *Solar Calculation Details*.
- F. Espenak & J. Meeus, *Polynomial Expressions for Delta T*, NASA.
- ICAO Doc 7488, *Manual of the ICAO Standard Atmosphere*.
- O. Alduchov & R. Eskridge, "Improved Magnus form approximation of saturation vapor pressure", *J. Appl. Meteor.* 35, 1996.
- T. Vincenty, "Direct and inverse solutions of geodesics on the ellipsoid", *Survey Review* 23, 1975.
- D. Douglas & T. Peucker, *The Canadian Cartographer* 10(2), 1973.
- UK Met Office, *Pressure tendency* terms. NOAA/NWS observing handbook.
