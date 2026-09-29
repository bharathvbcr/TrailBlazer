/**
 * TrailBlazer Celestial Engine
 * High-precision astronomical calculations for Sun, Sunrise/Sunset azimuths,
 * Moon phases, lunar illumination, and outdoor stargazing metrics.
 * 
 * Based on NOAA Solar Calculations and Meeus Astronomical Algorithms.
 */

export interface SolarDayInfo {
  date: Date;
  dateKey: string; // YYYY-MM-DD
  latitude: number;
  longitude: number;
  sunriseAzimuth: number | null; // Horizon azimuth in degrees (0-360)
  sunsetAzimuth: number | null; // Horizon azimuth in degrees (0-360)
  sunriseCardinal: string | null; // e.g. "ENE"
  sunsetCardinal: string | null; // e.g. "WNW"
  sunriseTime: Date | null;
  sunsetTime: Date | null;
  solarNoonTime: Date | null;
  dawnCivilTime: Date | null;
  duskCivilTime: Date | null;
  goldenHourMorning: { start: Date; end: Date } | null;
  goldenHourEvening: { start: Date; end: Date } | null;
  daylightDurationMinutes: number;
  isPolarDay: boolean;
  isPolarNight: boolean;
  sunAzimuth: number; // Current instantaneous sun azimuth (0-360)
  sunElevation: number; // Current instantaneous sun elevation (-90 to +90)
  daylightRemainingMinutes: number | null;
  solarStatus: 'night' | 'dawn' | 'day' | 'golden_hour' | 'dusk';
}

export type MoonPhaseName =
  | 'New Moon'
  | 'Waxing Crescent'
  | 'First Quarter'
  | 'Waxing Gibbous'
  | 'Full Moon'
  | 'Waning Gibbous'
  | 'Last Quarter'
  | 'Waning Crescent';

export interface MoonPhaseInfo {
  phase: number; // 0.0 to 1.0 (0=New, 0.25=First Quarter, 0.5=Full, 0.75=Last Quarter)
  fraction: number; // 0.0 to 1.0 (illuminated fraction)
  illuminationPercentage: number; // 0% to 100%
  phaseName: MoonPhaseName;
  phaseEmoji: string;
  ageDays: number; // 0 to 29.53 days
  waxing: boolean;
  daysToNextFullMoon: number;
  daysToNextNewMoon: number;
  moonAzimuth: number | null; // Topocentric azimuth (0-360) if coordinates provided
  moonElevation: number | null; // Topocentric elevation (-90 to +90) if coordinates provided
  moonriseTime: Date | null;
  moonsetTime: Date | null;
  stargazing: {
    rating: 'excellent' | 'good' | 'fair' | 'poor';
    title: string;
    description: string;
  };
}

const DEG2RAD = Math.PI / 180;
const RAD2DEG = 180 / Math.PI;

/**
 * Convert standard Date to Julian Date (JD)
 */
export function toJulianDate(date: Date): number {
  const time = date.getTime();
  return time / 86400000 + 2440587.5;
}

/**
 * Convert Julian Date back to standard JavaScript Date
 */
export function fromJulianDate(jd: number): Date {
  const time = (jd - 2440587.5) * 86400000;
  return new Date(time);
}

/**
 * Get cardinal direction string from degrees (16-point compass)
 */
export function getCardinal(deg: number): string {
  const normalized = ((deg % 360) + 360) % 360;
  const directions = [
    'N', 'NNE', 'NE', 'ENE',
    'E', 'ESE', 'SE', 'SSE',
    'S', 'SSW', 'SW', 'WSW',
    'W', 'WNW', 'NW', 'NNW'
  ];
  const index = Math.round(normalized / 22.5) % 16;
  return directions[index];
}

/**
 * Calculate Solar Coordinates and Equation of Time for a given Julian Date
 */
function getSunCoordinates(jd: number) {
  const T = (jd - 2451545.0) / 36525; // Julian centuries since J2000.0

  // Mean solar longitude (degrees)
  const L0 = (280.46646 + T * (36000.76983 + T * 0.0003032)) % 360;

  // Mean anomaly of the Sun (degrees)
  const M = (357.52911 + T * (35999.05029 - 0.0001537 * T)) % 360;
  const Mrad = M * DEG2RAD;

  // Sun equation of the center
  const C =
    Math.sin(Mrad) * (1.914602 - T * (0.004817 + 0.000014 * T)) +
    Math.sin(2 * Mrad) * (0.019993 - 0.000101 * T) +
    Math.sin(3 * Mrad) * 0.000289;

  // True longitude of the Sun
  const trueLong = (L0 + C) % 360;

  // Apparent longitude
  const omega = (125.04 - 1934.136 * T) * DEG2RAD;
  const lambda = (trueLong - 0.00569 - 0.00478 * Math.sin(omega)) * DEG2RAD;

  // Mean obliquity of ecliptic
  const seconds = 21.448 - T * (46.815 + T * (0.00059 - T * 0.001813));
  const eps0 = 23 + (26 + seconds / 60) / 60;
  const eps = (eps0 + 0.00256 * Math.cos(omega)) * DEG2RAD;

  // Declination
  const sinDec = Math.sin(eps) * Math.sin(lambda);
  const declination = Math.asin(Math.max(-1, Math.min(1, sinDec)));

  // Right Ascension
  const y = Math.cos(eps) * Math.sin(lambda);
  const x = Math.cos(lambda);
  const rightAscension = Math.atan2(y, x);

  // Equation of Time (minutes)
  const yTan = Math.tan(eps / 2);
  const y2 = yTan * yTan;
  const eccentricity = 0.016708634 - T * (0.000042037 + 0.0000001267 * T);
  const L0rad = L0 * DEG2RAD;
  const eqTime =
    4 *
    RAD2DEG *
    (y2 * Math.sin(2 * L0rad) -
      2 * eccentricity * Math.sin(Mrad) +
      4 * eccentricity * y2 * Math.sin(Mrad) * Math.cos(2 * L0rad) -
      0.5 * y2 * y2 * Math.sin(4 * L0rad) -
      1.25 * eccentricity * eccentricity * Math.sin(2 * Mrad));

  return {
    declination, // radians
    rightAscension, // radians
    equationOfTime: eqTime, // minutes
  };
}

/**
 * Calculate instantaneous topocentric sun position (azimuth & elevation)
 */
export function getInstantaneousSunPosition(
  lat: number,
  lon: number,
  date: Date = new Date()
): { azimuth: number; elevation: number } {
  const jd = toJulianDate(date);
  const { declination, equationOfTime } = getSunCoordinates(jd);

  const utcHours =
    date.getUTCHours() +
    date.getUTCMinutes() / 60 +
    date.getUTCSeconds() / 3600 +
    date.getUTCMilliseconds() / 3600000;

  // True solar time in minutes
  const solarTimeMinutes = (utcHours * 60 + 4 * lon + equationOfTime + 1440) % 1440;
  const hourAngleRad = ((solarTimeMinutes / 4) - 180) * DEG2RAD;

  const latRad = lat * DEG2RAD;
  const sinLat = Math.sin(latRad);
  const cosLat = Math.cos(latRad);
  const sinDec = Math.sin(declination);
  const cosDec = Math.cos(declination);

  const sinElevation =
    sinLat * sinDec + cosLat * cosDec * Math.cos(hourAngleRad);
  const elevation = Math.asin(Math.max(-1, Math.min(1, sinElevation)));

  const cosAzimuth =
    (sinDec - sinLat * Math.sin(elevation)) /
    (cosLat * Math.cos(elevation) || 0.000001);
  let azimuth = Math.acos(Math.max(-1, Math.min(1, cosAzimuth))) * RAD2DEG;

  if (Math.sin(hourAngleRad) > 0) {
    azimuth = 360 - azimuth;
  }

  return {
    azimuth: Number(((azimuth + 180) % 360).toFixed(1)),
    elevation: Number((elevation * RAD2DEG).toFixed(1)),
  };
}

/**
 * Calculate sunrise and sunset directions (azimuth) and event times for any day
 */
export function calculateSolarDay(
  lat: number,
  lon: number,
  inputDate: Date = new Date()
): SolarDayInfo {
  // Use noon UTC on the specified calendar day to compute stable daily solar metrics
  const calYear = inputDate.getFullYear();
  const calMonth = inputDate.getMonth();
  const calDay = inputDate.getDate();

  const noonUtc = new Date(Date.UTC(calYear, calMonth, calDay, 12, 0, 0));
  const jdNoon = toJulianDate(noonUtc);
  const { declination, equationOfTime } = getSunCoordinates(jdNoon);

  const latRad = lat * DEG2RAD;
  const cosLat = Math.cos(latRad);
  const sinLat = Math.sin(latRad);
  const cosDec = Math.cos(declination);
  const sinDec = Math.sin(declination);

  // Solar noon in minutes from UTC midnight
  const solarNoonMinutes = 720 - 4 * lon - equationOfTime;

  // Horizon zenith angles:
  // Standard sunrise/sunset: 90.833° (-0.833° solar altitude accounting for refraction & diameter)
  // Civil twilight: 96° (-6° solar altitude)
  // Golden hour upper limit: 84° (+6° solar altitude)
  const calcHourAngle = (zenithDeg: number): number | 'polar_day' | 'polar_night' => {
    const cosZenith = Math.cos(zenithDeg * DEG2RAD);
    const denom = cosLat * cosDec;
    if (Math.abs(denom) < 1e-7) return 0;
    const cosHA = (cosZenith - sinLat * sinDec) / denom;

    if (cosHA > 1) return 'polar_night';
    if (cosHA < -1) return 'polar_day';
    return Math.acos(Math.max(-1, Math.min(1, cosHA))) * RAD2DEG;
  };

  const haStandard = calcHourAngle(90.833);
  const haCivil = calcHourAngle(96.0);
  const haGolden = calcHourAngle(84.0);

  const isPolarDay = haStandard === 'polar_day';
  const isPolarNight = haStandard === 'polar_night';

  const makeDate = (minutesFromMidnightUtc: number): Date => {
    const base = new Date(Date.UTC(calYear, calMonth, calDay, 0, 0, 0));
    return new Date(base.getTime() + minutesFromMidnightUtc * 60000);
  };

  const solarNoonTime = makeDate(solarNoonMinutes);

  let sunriseTime: Date | null = null;
  let sunsetTime: Date | null = null;
  let sunriseAzimuth: number | null = null;
  let sunsetAzimuth: number | null = null;
  let daylightDurationMinutes = 0;

  if (typeof haStandard === 'number') {
    const sunriseMin = solarNoonMinutes - 4 * haStandard;
    const sunsetMin = solarNoonMinutes + 4 * haStandard;
    sunriseTime = makeDate(sunriseMin);
    sunsetTime = makeDate(sunsetMin);
    daylightDurationMinutes = Math.round(8 * haStandard);

    // Sunrise azimuth on the horizon (h0 = -0.833°)
    const h0Rad = -0.833 * DEG2RAD;
    const cosH0 = Math.cos(h0Rad);
    const sinH0 = Math.sin(h0Rad);

    const denomAz = cosLat * cosH0;
    if (Math.abs(denomAz) > 1e-7) {
      const cosAz = (sinDec - sinLat * sinH0) / denomAz;
      const azRise = Math.acos(Math.max(-1, Math.min(1, cosAz))) * RAD2DEG;
      sunriseAzimuth = Number(azRise.toFixed(1));
      sunsetAzimuth = Number(((360 - azRise) % 360).toFixed(1));
    }
  } else if (isPolarDay) {
    daylightDurationMinutes = 1440;
  } else {
    daylightDurationMinutes = 0;
  }

  // Civil Twilight
  let dawnCivilTime: Date | null = null;
  let duskCivilTime: Date | null = null;
  if (typeof haCivil === 'number') {
    dawnCivilTime = makeDate(solarNoonMinutes - 4 * haCivil);
    duskCivilTime = makeDate(solarNoonMinutes + 4 * haCivil);
  }

  // Golden Hour
  let goldenHourMorning: { start: Date; end: Date } | null = null;
  let goldenHourEvening: { start: Date; end: Date } | null = null;
  if (sunriseTime && typeof haGolden === 'number') {
    const goldenMorningEnd = makeDate(solarNoonMinutes - 4 * haGolden);
    goldenHourMorning = { start: sunriseTime, end: goldenMorningEnd };
  }
  if (sunsetTime && typeof haGolden === 'number') {
    const goldenEveningStart = makeDate(solarNoonMinutes + 4 * haGolden);
    goldenHourEvening = { start: goldenEveningStart, end: sunsetTime };
  }

  // Current real-time sun position
  const currentInstant = getInstantaneousSunPosition(lat, lon, new Date());
  const now = new Date();

  // Daylight remaining
  let daylightRemainingMinutes: number | null = null;
  if (sunsetTime && sunriseTime) {
    if (now >= sunriseTime && now < sunsetTime) {
      daylightRemainingMinutes = Math.max(0, Math.round((sunsetTime.getTime() - now.getTime()) / 60000));
    } else if (now < sunriseTime) {
      daylightRemainingMinutes = daylightDurationMinutes;
    } else {
      daylightRemainingMinutes = 0;
    }
  }

  // Solar Status
  let solarStatus: 'night' | 'dawn' | 'day' | 'golden_hour' | 'dusk' = 'night';
  if (currentInstant.elevation > 6) {
    solarStatus = 'day';
  } else if (currentInstant.elevation > -0.833) {
    solarStatus = 'golden_hour';
  } else if (currentInstant.elevation > -6) {
    solarStatus = currentInstant.azimuth < 180 ? 'dawn' : 'dusk';
  } else {
    solarStatus = 'night';
  }

  const pad = (n: number) => n.toString().padStart(2, '0');
  const dateKey = `${calYear}-${pad(calMonth + 1)}-${pad(calDay)}`;

  return {
    date: inputDate,
    dateKey,
    latitude: lat,
    longitude: lon,
    sunriseAzimuth,
    sunsetAzimuth,
    sunriseCardinal: sunriseAzimuth !== null ? getCardinal(sunriseAzimuth) : null,
    sunsetCardinal: sunsetAzimuth !== null ? getCardinal(sunsetAzimuth) : null,
    sunriseTime,
    sunsetTime,
    solarNoonTime,
    dawnCivilTime,
    duskCivilTime,
    goldenHourMorning,
    goldenHourEvening,
    daylightDurationMinutes,
    isPolarDay,
    isPolarNight,
    sunAzimuth: currentInstant.azimuth,
    sunElevation: currentInstant.elevation,
    daylightRemainingMinutes,
    solarStatus,
  };
}

/**
 * Calculate accurate Moon Phase, Lunar Age, Illumination %, and Stargazing rating
 * Meeus / Conway synodic astronomical calculation.
 */
export function calculateMoonPhase(
  date: Date = new Date(),
  lat?: number | null,
  lon?: number | null
): MoonPhaseInfo {
  const jd = toJulianDate(date);
  const T = (jd - 2451545.0) / 36525; // Julian centuries

  // Moon's mean elongation D (degrees)
  const D = (297.8501921 + 445267.1114034 * T - 0.0018819 * T * T) % 360;
  const Drad = D * DEG2RAD;

  // Sun's mean anomaly M
  const M = (357.5291092 + 35999.0502909 * T - 0.0001536 * T * T) % 360;
  const Mrad = M * DEG2RAD;

  // Moon's mean anomaly M'
  const Mprime = (134.9633964 + 477198.8675055 * T + 0.0087414 * T * T) % 360;
  const MprimeRad = Mprime * DEG2RAD;

  // Phase angle i
  const phaseAngleDeg =
    180 -
    D -
    6.289 * Math.sin(MprimeRad) +
    2.1 * Math.sin(Mrad) -
    1.274 * Math.sin(2 * Drad - MprimeRad) -
    0.658 * Math.sin(2 * Drad) -
    0.214 * Math.sin(2 * MprimeRad) -
    0.11 * Math.sin(Drad);

  const phaseAngleRad = ((phaseAngleDeg % 360) + 360) % 360 * DEG2RAD;

  // Illuminated fraction (0.0 to 1.0)
  const k = (1 + Math.cos(phaseAngleRad)) / 2;
  const fraction = Math.max(0, Math.min(1, k));
  const illuminationPercentage = Math.round(fraction * 100);

  // Normalized phase value P (0.0 to 1.0)
  const normalizedD = ((D % 360) + 360) % 360;
  const phase = normalizedD / 360;
  const waxing = normalizedD < 180;
  const SYNODIC_MONTH = 29.53058867;
  const ageDays = Number((phase * SYNODIC_MONTH).toFixed(1));

  // Determine exact phase name & emoji
  let phaseName: MoonPhaseName;
  let phaseEmoji: string;

  if (phase < 0.03 || phase >= 0.97) {
    phaseName = 'New Moon';
    phaseEmoji = '🌑';
  } else if (phase < 0.22) {
    phaseName = 'Waxing Crescent';
    phaseEmoji = '🌒';
  } else if (phase < 0.28) {
    phaseName = 'First Quarter';
    phaseEmoji = '🌓';
  } else if (phase < 0.47) {
    phaseName = 'Waxing Gibbous';
    phaseEmoji = '🌔';
  } else if (phase < 0.53) {
    phaseName = 'Full Moon';
    phaseEmoji = '🌕';
  } else if (phase < 0.72) {
    phaseName = 'Waning Gibbous';
    phaseEmoji = '🌖';
  } else if (phase < 0.78) {
    phaseName = 'Last Quarter';
    phaseEmoji = '🌗';
  } else {
    phaseName = 'Waning Crescent';
    phaseEmoji = '🌘';
  }

  // Days to next Full Moon and New Moon
  let daysToNextFullMoon: number;
  if (phase < 0.5) {
    daysToNextFullMoon = Number(((0.5 - phase) * SYNODIC_MONTH).toFixed(1));
  } else {
    daysToNextFullMoon = Number(((1.5 - phase) * SYNODIC_MONTH).toFixed(1));
  }
  const daysToNextNewMoon = Number(((1.0 - phase) * SYNODIC_MONTH).toFixed(1));

  // Stargazing rating for campers & nature observers
  let stargazingRating: 'excellent' | 'good' | 'fair' | 'poor';
  let stargazingTitle: string;
  let stargazingDescription: string;

  if (illuminationPercentage <= 20) {
    stargazingRating = 'excellent';
    stargazingTitle = 'Dark Sky Optimum';
    stargazingDescription =
      'Minimal lunar glare. Ideal conditions for observing the Milky Way, meteor showers, nebulae, and faint constellations.';
  } else if (illuminationPercentage <= 50) {
    stargazingRating = 'good';
    stargazingTitle = 'Moderate Sky Glow';
    stargazingDescription =
      'Subtle lunar ambient glow. Major constellations and planets remain crisp; deep-sky objects moderately visible.';
  } else if (illuminationPercentage <= 80) {
    stargazingRating = 'fair';
    stargazingTitle = 'Bright Moonwash';
    stargazingDescription =
      'Substantial lunar wash. Great for night landscape hiking without headlamps, but faint stars will be obscured.';
  } else {
    stargazingRating = 'poor';
    stargazingTitle = 'Peak Lunar Radiance';
    stargazingDescription =
      'Full moon illumination. Provides bright night terrain illumination for camp navigation; poor for deep-space astronomy.';
  }

  // Calculate topocentric Moon position (azimuth & elevation) if coordinates provided
  let moonAzimuth: number | null = null;
  let moonElevation: number | null = null;

  if (lat != null && lon != null && !isNaN(lat) && !isNaN(lon)) {
    const moonPos = calculateMoonPosition(lat, lon, date);
    moonAzimuth = moonPos.azimuth;
    moonElevation = moonPos.elevation;
  }

  return {
    phase: Number(phase.toFixed(3)),
    fraction: Number(fraction.toFixed(3)),
    illuminationPercentage,
    phaseName,
    phaseEmoji,
    ageDays,
    waxing,
    daysToNextFullMoon,
    daysToNextNewMoon,
    moonAzimuth,
    moonElevation,
    moonriseTime: null,
    moonsetTime: null,
    stargazing: {
      rating: stargazingRating,
      title: stargazingTitle,
      description: stargazingDescription,
    },
  };
}

/**
 * Topocentric Moon position (azimuth & elevation)
 */
function calculateMoonPosition(lat: number, lon: number, date: Date) {
  const jd = toJulianDate(date);
  const T = (jd - 2451545.0) / 36525;

  const Lprime = (218.3164477 + 481267.88123421 * T) % 360;
  const D = (297.8501921 + 445267.1114034 * T) % 360;
  const M = (357.5291092 + 35999.0502909 * T) % 360;
  const Mprime = (134.9633964 + 477198.8675055 * T) % 360;
  const F = (93.272095 + 483202.0175233 * T) % 360;

  const Mrad = M * DEG2RAD;
  const MprimeRad = Mprime * DEG2RAD;
  const Drad = D * DEG2RAD;
  const Frad = F * DEG2RAD;

  // Ecliptic longitude of Moon
  const lambda =
    Lprime +
    6.289 * Math.sin(MprimeRad) +
    1.274 * Math.sin(2 * Drad - MprimeRad) +
    0.658 * Math.sin(2 * Drad) +
    0.214 * Math.sin(2 * MprimeRad) -
    0.186 * Math.sin(Mrad) -
    0.114 * Math.sin(2 * Frad);

  // Ecliptic latitude of Moon
  const beta =
    5.128 * Math.sin(Frad) +
    0.281 * Math.sin(MprimeRad + Frad) +
    0.277 * Math.sin(MprimeRad - Frad) +
    0.173 * Math.sin(2 * Drad - Frad);

  const lambdaRad = lambda * DEG2RAD;
  const betaRad = beta * DEG2RAD;

  // Obliquity of Ecliptic
  const eps = 23.4392911 * DEG2RAD;

  // Equatorial coordinates
  const sinDec =
    Math.sin(betaRad) * Math.cos(eps) +
    Math.cos(betaRad) * Math.sin(eps) * Math.sin(lambdaRad);
  const declination = Math.asin(Math.max(-1, Math.min(1, sinDec)));

  const y =
    Math.sin(lambdaRad) * Math.cos(eps) -
    Math.tan(betaRad) * Math.sin(eps);
  const x = Math.cos(lambdaRad);
  const ra = Math.atan2(y, x);

  // Greenwich Mean Sidereal Time
  const theta0 = (280.46061837 + 360.98564736629 * (jd - 2451545.0)) % 360;
  const localSiderealTimeRad = (theta0 + lon) * DEG2RAD;
  const hourAngle = localSiderealTimeRad - ra;

  const latRad = lat * DEG2RAD;
  const sinLat = Math.sin(latRad);
  const cosLat = Math.cos(latRad);
  const cosDec = Math.cos(declination);

  const sinElevation =
    sinLat * Math.sin(declination) +
    cosLat * cosDec * Math.cos(hourAngle);
  const elevation = Math.asin(Math.max(-1, Math.min(1, sinElevation)));

  const cosAzimuth =
    (Math.sin(declination) - sinLat * Math.sin(elevation)) /
    (cosLat * Math.cos(elevation) || 0.000001);
  let azimuth = Math.acos(Math.max(-1, Math.min(1, cosAzimuth))) * RAD2DEG;

  if (Math.sin(hourAngle) > 0) {
    azimuth = 360 - azimuth;
  }

  return {
    azimuth: Number(((azimuth + 180) % 360).toFixed(1)),
    elevation: Number((elevation * RAD2DEG).toFixed(1)),
  };
}
