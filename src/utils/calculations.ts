import type {
  PressureUnit,
  AltitudeUnit,
  SpeedUnit,
  WeatherTendency,
  TrackPoint,
  PressureHistoryPoint,
  BarometricTrend3Hour,
  HourlyPressureMilestone,
  BarometricTrendCategory,
  NorthMode,
} from '../types/sensors.ts';

/**
 * Standard Barometric formula (hypsometric)
 * Pressure in hPa, QNH in hPa -> Altitude in meters
 */
export function calculateBarometricAltitude(pressureHpa: number, qnhHpa: number = 1013.25): number {
  if (!Number.isFinite(pressureHpa) || !Number.isFinite(qnhHpa) || pressureHpa <= 0 || qnhHpa <= 0) return 0;
  const ratio = Math.max(0, pressureHpa / qnhHpa);
  const alt = 44330.8 * (1 - Math.pow(ratio, 0.190263));
  return Number.isFinite(alt) ? alt : 0;
}

/**
 * Calculate expected Sea-Level Reference Pressure (QNH) from known altitude and current station pressure
 */
export function calculateQnhFromAltitude(pressureHpa: number, altitudeMeters: number): number {
  if (!Number.isFinite(pressureHpa) || !Number.isFinite(altitudeMeters) || pressureHpa <= 0) return 1013.25;
  if (altitudeMeters >= 44330) return 1013.25;
  const ratio = Math.max(0.00001, 1 - (altitudeMeters / 44330.8));
  const qnh = pressureHpa * Math.pow(ratio, -5.255);
  return Number.isFinite(qnh) && qnh > 0 ? qnh : 1013.25;
}

/**
 * Convert pressure from hPa to other units
 */
export function convertPressure(valueHpa: number, unit: PressureUnit): { value: number; label: string } {
  const safeVal = Number.isFinite(valueHpa) ? valueHpa : 0;
  switch (unit) {
    case 'inHg':
      return { value: safeVal * 0.029529983, label: 'inHg' };
    case 'mmHg':
      return { value: safeVal * 0.750061683, label: 'mmHg' };
    case 'psi':
      return { value: safeVal * 0.01450377, label: 'psi' };
    case 'hPa':
    default:
      return { value: safeVal, label: 'hPa' };
  }
}

/**
 * Convert altitude between meters and feet
 */
export function convertAltitude(valueMeters: number, unit: AltitudeUnit): { value: number; label: string } {
  const safeVal = Number.isFinite(valueMeters) ? valueMeters : 0;
  if (unit === 'ft') {
    return { value: safeVal * 3.28084, label: 'ft' };
  }
  return { value: safeVal, label: 'm' };
}

/**
 * Convert vertical speed (m/s) to appropriate units (m/min or ft/min)
 */
export function convertVerticalSpeed(speedMs: number, unit: AltitudeUnit): { value: number; label: string } {
  const safeVal = Number.isFinite(speedMs) ? speedMs : 0;
  if (unit === 'ft') {
    return { value: safeVal * 196.85, label: 'ft/min' };
  }
  return { value: safeVal * 60, label: 'm/min' };
}

/**
 * Convert speed from m/s to chosen SpeedUnit
 */
export function convertSpeed(speedMs: number, unit: SpeedUnit): { value: number; label: string } {
  const safeMs = Number.isFinite(speedMs) ? Math.max(0, speedMs) : 0;
  switch (unit) {
    case 'mph':
      return { value: Number((safeMs * 2.236936).toFixed(1)), label: 'mph' };
    case 'kt':
      return { value: Number((safeMs * 1.943844).toFixed(1)), label: 'kt' };
    case 'm/s':
      return { value: Number(safeMs.toFixed(1)), label: 'm/s' };
    case 'km/h':
    default:
      return { value: Number((safeMs * 3.6).toFixed(1)), label: 'km/h' };
  }
}

/**
 * Convert speed from chosen SpeedUnit to m/s
 */
export function convertSpeedToMs(value: number, fromUnit: SpeedUnit): number {
  const safeVal = Number.isFinite(value) ? Math.max(0, value) : 0;
  switch (fromUnit) {
    case 'mph':
      return safeVal / 2.236936;
    case 'kt':
      return safeVal / 1.943844;
    case 'm/s':
      return safeVal;
    case 'km/h':
    default:
      return safeVal / 3.6;
  }
}

/**
 * Convert speed between any two units
 */
export function convertSpeedBetweenUnits(value: number, fromUnit: SpeedUnit, toUnit: SpeedUnit): number {
  if (fromUnit === toUnit) return Number.isFinite(value) ? value : 0;
  const ms = convertSpeedToMs(value, fromUnit);
  return convertSpeed(ms, toUnit).value;
}

/**
 * Cardinal and Intercardinal direction for degrees (0 - 360)
 */
export function getCardinalDirection(headingDeg: number): string {
  if (!Number.isFinite(headingDeg)) return 'N';
  const normalized = (headingDeg % 360 + 360) % 360;
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
 * Great-circle distance between two GPS coordinates using Haversine formula (returns meters)
 */
export function calculateDistanceMeters(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  if (!Number.isFinite(lat1) || !Number.isFinite(lon1) || !Number.isFinite(lat2) || !Number.isFinite(lon2)) return 0;
  const R = 6371000; // Earth radius in meters
  const phi1 = (Math.max(-90, Math.min(90, lat1)) * Math.PI) / 180;
  const phi2 = (Math.max(-90, Math.min(90, lat2)) * Math.PI) / 180;
  const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;

  const a =
    Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
    Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
  const c = 2 * Math.atan2(Math.sqrt(Math.max(0, a)), Math.sqrt(Math.max(0, 1 - a)));

  const dist = R * c;
  return Number.isFinite(dist) ? dist : 0;
}

/**
 * Initial bearing from (lat1, lon1) to (lat2, lon2) in degrees (0 - 360)
 */
export function calculateBearingDegrees(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  if (!Number.isFinite(lat1) || !Number.isFinite(lon1) || !Number.isFinite(lat2) || !Number.isFinite(lon2)) return 0;
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;

  const y = Math.sin(deltaLambda) * Math.cos(phi2);
  const x =
    Math.cos(phi1) * Math.sin(phi2) -
    Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);

  const theta = Math.atan2(y, x);
  const deg = ((theta * 180) / Math.PI + 360) % 360;
  return Number.isFinite(deg) ? deg : 0;
}

/**
 * Approximate boiling point of water at given atmospheric pressure (°C and °F)
 */
export function calculateWaterBoilingPoint(pressureHpa: number): { celsius: number; fahrenheit: number } {
  if (!Number.isFinite(pressureHpa) || pressureHpa <= 0) {
    return { celsius: 100, fahrenheit: 212 };
  }
  const pMmHg = pressureHpa * 0.750062;
  const denom = 8.07131 - Math.log10(pMmHg);
  const celsius = Math.abs(denom) > 1e-6 ? (1730.63 / denom) - 233.426 : 100;
  const safeCelsius = Number.isFinite(celsius) ? Math.max(50, Math.min(115, celsius)) : 100;
  const fahrenheit = (safeCelsius * 9) / 5 + 32;
  return { celsius: Number(safeCelsius.toFixed(1)), fahrenheit: Number(fahrenheit.toFixed(1)) };
}

/**
 * Approximate air density at pressure and altitude (kg/m³)
 */
export function calculateAirDensity(pressureHpa: number, altitudeMeters: number): number {
  if (!Number.isFinite(pressureHpa) || !Number.isFinite(altitudeMeters) || pressureHpa <= 0) return 1.225;
  const pPa = pressureHpa * 100;
  const tempK = Math.max(150, 288.15 - 0.0065 * altitudeMeters);
  const R = 287.058; // specific gas constant for dry air
  const density = pPa / (R * tempK);
  return Number.isFinite(density) ? Number(density.toFixed(3)) : 1.225;
}

/**
 * Calculate Density Altitude (feet): altitude corrected for non-standard pressure and temperature
 */
export function calculateDensityAltitude(pressureHpa: number, altitudeMeters: number): number {
  if (!Number.isFinite(pressureHpa) || !Number.isFinite(altitudeMeters)) return 0;
  const pressureAltFt = (1013.25 - pressureHpa) * 30 + altitudeMeters * 3.28084;
  const standardTempC = 15 - (pressureAltFt / 1000) * 1.98;
  const outsideAirTempC = standardTempC;
  const densityAltFt = pressureAltFt + 118.8 * (outsideAirTempC - standardTempC);
  return Number.isFinite(densityAltFt) ? Math.round(densityAltFt) : 0;
}

/**
 * Analyze barometric trend rate to produce weather insights
 */
export function analyzeWeatherTendency(deltaHpaPerHour: number, currentPressureHpa: number): WeatherTendency {
  const safeDelta = Number.isFinite(deltaHpaPerHour) ? deltaHpaPerHour : 0;
  const safeP = Number.isFinite(currentPressureHpa) ? currentPressureHpa : 1013.25;

  if (safeDelta < -2.0) {
    return {
      label: 'Rapid Drop — Gale / Storm Warning',
      category: 'storm',
      description: 'Barometer dropping rapidly (>2 hPa/hr). High risk of tempest, severe thunderstorm, or sudden cold squall. Seek shelter.',
      deltaHpa: safeDelta,
    };
  } else if (safeDelta < -0.8) {
    return {
      label: 'Falling — Deteriorating',
      category: 'deteriorating',
      description: 'Pressure is decreasing. Clouds thickening, wind picking up, chance of precipitation within 6-12 hours.',
      deltaHpa: safeDelta,
    };
  } else if (safeDelta > 2.0) {
    return {
      label: 'Rapid Surge — Cold Front Clearing',
      category: 'improving',
      description: 'Sudden pressure spike often marks cold front passage. Gusty winds shifting to dry, clear air with rapid visibility improvement.',
      deltaHpa: safeDelta,
    };
  } else if (safeDelta > 0.8) {
    return {
      label: 'Rising — Improving Conditions',
      category: 'improving',
      description: 'High pressure ridge establishing. Skies clearing, calm breeze, dry weather settling in.',
      deltaHpa: safeDelta,
    };
  } else {
    if (safeP > 1022) {
      return {
        label: 'Stable High — Fair & Dry',
        category: 'fair',
        description: 'Atmospheric pressure is steady and elevated. Extended fair weather, light winds, and crisp visibility.',
        deltaHpa: safeDelta,
      };
    } else if (safeP < 1000) {
      return {
        label: 'Low Pressure Center — Unsettled',
        category: 'deteriorating',
        description: 'Persistent low pressure trough. Damp, overcast skies and variable breeze.',
        deltaHpa: safeDelta,
      };
    }
    return {
      label: 'Steady — Persistent Weather',
      category: 'steady',
      description: 'Minimal pressure fluctuation. Existing weather patterns will continue without major shifts.',
      deltaHpa: safeDelta,
    };
  }
}

/**
 * Calculate real-time 3-hour barometric trend analysis and arrow indicator
 */
export function calculateThreeHourBarometricTrend(
  history: PressureHistoryPoint[],
  currentPressure: number
): BarometricTrend3Hour {
  const now = Date.now();
  const threeHoursAgoMs = now - 3 * 3600 * 1000;
  const safeCurrentP = Number.isFinite(currentPressure) ? currentPressure : 1013.25;

  const validHistory = (history || []).filter(
    (p) => p && Number.isFinite(p.timestamp) && Number.isFinite(p.pressure) && p.pressure > 300 && p.pressure < 1100
  );

  // If no valid history, return clean steady baseline
  if (validHistory.length === 0) {
    const defaultMilestones: HourlyPressureMilestone[] = [
      { hourLabel: '3h ago', hourOffset: -3, pressure: safeCurrentP, deltaFromStart: 0, timestamp: threeHoursAgoMs },
      { hourLabel: '2h ago', hourOffset: -2, pressure: safeCurrentP, deltaFromStart: 0, timestamp: now - 2 * 3600 * 1000 },
      { hourLabel: '1h ago', hourOffset: -1, pressure: safeCurrentP, deltaFromStart: 0, timestamp: now - 1 * 3600 * 1000 },
      { hourLabel: 'Now', hourOffset: 0, pressure: safeCurrentP, deltaFromStart: 0, timestamp: now },
    ];
    return {
      trend: 'steady',
      subCategory: 'steady',
      deltaHpa3h: 0,
      ratePerHour: 0,
      pressure3hAgo: safeCurrentP,
      currentPressure: safeCurrentP,
      timestamp3hAgo: threeHoursAgoMs,
      timeSpanHours: 3,
      label: 'Steady (±0.0 hPa / 3h)',
      description: 'Atmospheric pressure is steady with no notable 3-hour deviation.',
      characteristicLabel: 'Steady — within ±0.5 hPa threshold',
      arrowDirection: 'right',
      hourlyMilestones: defaultMilestones,
    };
  }

  // Sort history points chronologically
  const sorted = [...validHistory].sort((a, b) => a.timestamp - b.timestamp);

  // Helper to interpolate pressure at any milestone timestamp
  const getPressureAtTime = (targetTime: number): number => {
    if (sorted.length === 0) return safeCurrentP;
    if (targetTime <= sorted[0].timestamp) {
      return sorted[0].pressure;
    }
    if (targetTime >= now || targetTime >= sorted[sorted.length - 1].timestamp) {
      return safeCurrentP;
    }
    for (let i = 0; i < sorted.length - 1; i++) {
      const p1 = sorted[i];
      const p2 = sorted[i + 1];
      if (targetTime >= p1.timestamp && targetTime <= p2.timestamp) {
        if (p2.timestamp === p1.timestamp) return p1.pressure;
        const factor = (targetTime - p1.timestamp) / (p2.timestamp - p1.timestamp);
        return p1.pressure + factor * (p2.pressure - p1.pressure);
      }
    }
    return sorted[sorted.length - 1].pressure;
  };

  const p3hAgo = getPressureAtTime(threeHoursAgoMs);
  const p2hAgo = getPressureAtTime(now - 2 * 3600 * 1000);
  const p1hAgo = getPressureAtTime(now - 1 * 3600 * 1000);
  const pNow = safeCurrentP;

  const rawDelta3h = pNow - p3hAgo;
  const deltaHpa3h = Number(rawDelta3h.toFixed(2));
  const ratePerHour = Number((rawDelta3h / 3).toFixed(2));

  const oldestTime = sorted[0].timestamp;
  const availableHours = Math.min(3, Math.max(0.1, (now - oldestTime) / 3600000));

  const hourlyMilestones: HourlyPressureMilestone[] = [
    {
      hourLabel: '3h ago',
      hourOffset: -3,
      pressure: Number(p3hAgo.toFixed(2)),
      deltaFromStart: 0,
      timestamp: threeHoursAgoMs,
    },
    {
      hourLabel: '2h ago',
      hourOffset: -2,
      pressure: Number(p2hAgo.toFixed(2)),
      deltaFromStart: Number((p2hAgo - p3hAgo).toFixed(2)),
      timestamp: now - 2 * 3600 * 1000,
    },
    {
      hourLabel: '1h ago',
      hourOffset: -1,
      pressure: Number(p1hAgo.toFixed(2)),
      deltaFromStart: Number((p1hAgo - p3hAgo).toFixed(2)),
      timestamp: now - 1 * 3600 * 1000,
    },
    {
      hourLabel: 'Now',
      hourOffset: 0,
      pressure: Number(pNow.toFixed(2)),
      deltaFromStart: deltaHpa3h,
      timestamp: now,
    },
  ];

  let trend: BarometricTrendCategory = 'steady';
  let subCategory: 'rising_rapid' | 'rising' | 'steady' | 'falling' | 'falling_rapid' = 'steady';
  let arrowDirection: 'up' | 'up_right' | 'right' | 'down_right' | 'down' = 'right';
  let label = '';
  let description = '';
  let characteristicLabel = '';

  const sign = deltaHpa3h > 0 ? '+' : '';

  if (deltaHpa3h >= 2.0) {
    trend = 'rising';
    subCategory = 'rising_rapid';
    arrowDirection = 'up';
    label = `Rising Rapidly (${sign}${deltaHpa3h.toFixed(1)} hPa / 3h)`;
    characteristicLabel = 'Rapid barometric surge (ΔP ≥ +2.0 hPa/3h)';
    description = 'Strong atmospheric pressure surge. High pressure ridge advancing rapidly with clearing conditions and brisk winds.';
  } else if (deltaHpa3h >= 0.5) {
    trend = 'rising';
    subCategory = 'rising';
    arrowDirection = 'up_right';
    label = `Rising (${sign}${deltaHpa3h.toFixed(1)} hPa / 3h)`;
    characteristicLabel = 'Continuous rise (+0.5 to +2.0 hPa/3h)';
    description = 'Barometer rising steadily over the last 3 hours. Atmospheric stabilization underway; improving visibility and clearing skies.';
  } else if (deltaHpa3h <= -2.0) {
    trend = 'falling';
    subCategory = 'falling_rapid';
    arrowDirection = 'down';
    label = `Falling Rapidly (${deltaHpa3h.toFixed(1)} hPa / 3h)`;
    characteristicLabel = 'Severe pressure plunge (ΔP ≤ -2.0 hPa/3h)';
    description = 'Severe barometric drop over the last 3 hours. Imminent storm system, squall line, or frontal gale approaching. Precaution advised.';
  } else if (deltaHpa3h <= -0.5) {
    trend = 'falling';
    subCategory = 'falling';
    arrowDirection = 'down_right';
    label = `Falling (${deltaHpa3h.toFixed(1)} hPa / 3h)`;
    characteristicLabel = 'Continuous decline (-0.5 to -2.0 hPa/3h)';
    description = 'Barometer declining consistently over the last 3 hours. Approaching trough or depression; expect cloud buildup and deteriorating weather.';
  } else {
    trend = 'steady';
    subCategory = 'steady';
    arrowDirection = 'right';
    label = `Steady (${sign}${deltaHpa3h.toFixed(1)} hPa / 3h)`;
    characteristicLabel = 'Steady — within ±0.5 hPa threshold';
    description = 'Atmospheric pressure has remained stable within ±0.5 hPa over the last 3 hours. Current weather conditions expected to persist.';
  }

  return {
    trend,
    subCategory,
    deltaHpa3h,
    ratePerHour,
    pressure3hAgo: Number(p3hAgo.toFixed(2)),
    currentPressure: Number(pNow.toFixed(2)),
    timestamp3hAgo: threeHoursAgoMs,
    timeSpanHours: Number(availableHours.toFixed(1)),
    label,
    description,
    characteristicLabel,
    arrowDirection,
    hourlyMilestones,
  };
}

/**
 * Approximate sun position (azimuth and elevation) based on latitude, longitude, and date
 */
export function calculateSunPosition(lat: number, lon: number, date: Date = new Date()): { azimuth: number; elevation: number } {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return { azimuth: 0, elevation: 0 };
  }
  const safeLat = Math.max(-89.99, Math.min(89.99, lat));
  const safeLon = ((lon % 360) + 540) % 360 - 180;

  const rad = Math.PI / 180;
  const dayOfYear = Math.floor((date.getTime() - new Date(date.getFullYear(), 0, 0).getTime()) / 86400000);
  
  const declination = 23.45 * Math.sin(rad * ((360 / 365) * (dayOfYear - 81)));
  
  const utcHours = date.getUTCHours() + date.getUTCMinutes() / 60 + date.getUTCSeconds() / 3600;
  const solarTime = (((utcHours + safeLon / 15) % 24) + 24) % 24;
  const hourAngle = (solarTime - 12) * 15;

  const latRad = safeLat * rad;
  const decRad = declination * rad;
  const hraRad = hourAngle * rad;

  const sinElevation = Math.sin(latRad) * Math.sin(decRad) + Math.cos(latRad) * Math.cos(decRad) * Math.cos(hraRad);
  const elevation = Math.asin(Math.max(-1, Math.min(1, sinElevation))) / rad;

  const denom = Math.cos(latRad) * Math.cos(elevation * rad);
  const cosAzimuth = Math.abs(denom) > 1e-6
    ? (Math.sin(decRad) - Math.sin(latRad) * sinElevation) / denom
    : 0;
  let azimuth = Math.acos(Math.max(-1, Math.min(1, cosAzimuth))) / rad;
  if (Math.sin(hraRad) > 0) {
    azimuth = 360 - azimuth;
  }

  const safeAzimuth = Number.isFinite(azimuth) ? (azimuth + 180) % 360 : 0;
  const safeElevation = Number.isFinite(elevation) ? Math.round(elevation * 10) / 10 : 0;

  return {
    azimuth: safeAzimuth,
    elevation: safeElevation,
  };
}

/**
 * Format coordinates to Degrees, Minutes, Seconds (DMS)
 */
export function formatToDMS(deg: number, isLatitude: boolean): string {
  if (!Number.isFinite(deg)) {
    return isLatitude ? `0°00'00" N` : `0°00'00" E`;
  }
  const absolute = Math.abs(deg);
  const degrees = Math.floor(absolute);
  const minutesNotTruncated = (absolute - degrees) * 60;
  const minutes = Math.floor(minutesNotTruncated);
  const seconds = Math.floor((minutesNotTruncated - minutes) * 60);

  const direction = isLatitude
    ? deg >= 0 ? 'N' : 'S'
    : deg >= 0 ? 'E' : 'W';

  return `${degrees}°${minutes.toString().padStart(2, '0')}'${seconds.toString().padStart(2, '0')}" ${direction}`;
}

function escapeXml(unsafe: string): string {
  return (unsafe || '').replace(/[<>&'"]/g, (c) => {
    switch (c) {
      case '<': return '&lt;';
      case '>': return '&gt;';
      case '&': return '&amp;';
      case '\'': return '&apos;';
      case '"': return '&quot;';
      default: return c;
    }
  });
}

/**
 * Generate standard GPX 1.1 XML string from recorded track points
 */
export function generateGpxString(points: TrackPoint[], sessionName: string = 'TrailBlazer Track'): string {
  const safeSessionName = escapeXml(sessionName);
  const validPoints = (points || []).filter(
    (p) => p && Number.isFinite(p.latitude) && Number.isFinite(p.longitude) && Number.isFinite(p.timestamp)
  );

  const trkpts = validPoints.map((p) => {
    const timeIso = new Date(p.timestamp).toISOString();
    return `      <trkpt lat="${p.latitude.toFixed(6)}" lon="${p.longitude.toFixed(6)}">
${p.altitude !== null && Number.isFinite(p.altitude) ? `        <ele>${p.altitude.toFixed(1)}</ele>\n` : ''}        <time>${timeIso}</time>
        <extensions>
${p.pressure !== null && Number.isFinite(p.pressure) ? `          <pressure>${p.pressure.toFixed(2)}</pressure>\n` : ''}${p.heading !== null && Number.isFinite(p.heading) ? `          <heading>${p.heading.toFixed(1)}</heading>\n` : ''}${p.speed !== null && Number.isFinite(p.speed) ? `          <speed>${p.speed.toFixed(1)}</speed>` : ''}
        </extensions>
      </trkpt>`;
  }).join('\n');

  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="TrailBlazer" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>${safeSessionName}</name>
    <time>${new Date().toISOString()}</time>
  </metadata>
  <trk>
    <name>${safeSessionName}</name>
    <trkseg>
${trkpts}
    </trkseg>
  </trk>
</gpx>`;
}

/**
 * Convert a True North bearing to dial angle in degrees given the user's northMode and declination.
 * In True North mode, the dial angle equals trueAzimuth.
 * In Magnetic North mode, dial angle = trueAzimuth - declination.
 */
export function convertTrueToDialBearing(
  trueAzimuth: number | null,
  northMode: NorthMode,
  declination: number | null
): number | null {
  if (trueAzimuth === null || !Number.isFinite(trueAzimuth)) return null;
  if (northMode === 'true') return ((trueAzimuth % 360) + 360) % 360;
  const decl = declination ?? 0;
  return ((trueAzimuth - decl) % 360 + 360) % 360;
}

/**
 * Calculate the relative angular difference (-180° to +180°) between physical device pointing heading and target.
 * Positive = steer right, Negative = steer left.
 */
export function calculateRelativeTargetAngle(
  targetBearingTrue: number,
  deviceTrueHeading: number
): number {
  return ((targetBearingTrue - deviceTrueHeading + 540) % 360) - 180;
}
