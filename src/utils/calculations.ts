import {
  PressureUnit,
  AltitudeUnit,
  SpeedUnit,
  WeatherTendency,
  TrackPoint,
  PressureHistoryPoint,
  BarometricTrend3Hour,
  HourlyPressureMilestone,
  BarometricTrendCategory,
} from '../types/sensors';

/**
 * Standard Barometric formula (hypsometric)
 * Pressure in hPa, QNH in hPa -> Altitude in meters
 */
export function calculateBarometricAltitude(pressureHpa: number, qnhHpa: number = 1013.25): number {
  if (pressureHpa <= 0 || qnhHpa <= 0) return 0;
  // h = 44330.8 * (1 - (P / P0)^(1 / 5.255))
  return 44330.8 * (1 - Math.pow(pressureHpa / qnhHpa, 0.190263));
}

/**
 * Calculate expected Sea-Level Reference Pressure (QNH) from known altitude and current station pressure
 */
export function calculateQnhFromAltitude(pressureHpa: number, altitudeMeters: number): number {
  if (altitudeMeters >= 44330) return 1013.25;
  return pressureHpa * Math.pow(1 - (altitudeMeters / 44330.8), -5.255);
}

/**
 * Convert pressure from hPa to other units
 */
export function convertPressure(valueHpa: number, unit: PressureUnit): { value: number; label: string } {
  switch (unit) {
    case 'inHg':
      return { value: valueHpa * 0.029529983, label: 'inHg' };
    case 'mmHg':
      return { value: valueHpa * 0.750061683, label: 'mmHg' };
    case 'psi':
      return { value: valueHpa * 0.01450377, label: 'psi' };
    case 'hPa':
    default:
      return { value: valueHpa, label: 'hPa' };
  }
}

/**
 * Convert altitude between meters and feet
 */
export function convertAltitude(valueMeters: number, unit: AltitudeUnit): { value: number; label: string } {
  if (unit === 'ft') {
    return { value: valueMeters * 3.28084, label: 'ft' };
  }
  return { value: valueMeters, label: 'm' };
}

/**
 * Convert vertical speed (m/s) to appropriate units (m/min or ft/min)
 */
export function convertVerticalSpeed(speedMs: number, unit: AltitudeUnit): { value: number; label: string } {
  if (unit === 'ft') {
    return { value: speedMs * 196.85, label: 'ft/min' };
  }
  return { value: speedMs * 60, label: 'm/min' };
}

/**
 * Convert speed from m/s to chosen SpeedUnit
 */
export function convertSpeed(speedMs: number, unit: SpeedUnit): { value: number; label: string } {
  const safeMs = Math.max(0, speedMs);
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
  switch (fromUnit) {
    case 'mph':
      return value / 2.236936;
    case 'kt':
      return value / 1.943844;
    case 'm/s':
      return value;
    case 'km/h':
    default:
      return value / 3.6;
  }
}

/**
 * Convert speed between any two units
 */
export function convertSpeedBetweenUnits(value: number, fromUnit: SpeedUnit, toUnit: SpeedUnit): number {
  if (fromUnit === toUnit) return value;
  const ms = convertSpeedToMs(value, fromUnit);
  return convertSpeed(ms, toUnit).value;
}

/**
 * Cardinal and Intercardinal direction for degrees (0 - 360)
 */
export function getCardinalDirection(headingDeg: number): string {
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
  const R = 6371000; // Earth radius in meters
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;

  const a =
    Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
    Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return R * c;
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
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;

  const y = Math.sin(deltaLambda) * Math.cos(phi2);
  const x =
    Math.cos(phi1) * Math.sin(phi2) -
    Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);

  const theta = Math.atan2(y, x);
  return ((theta * 180) / Math.PI + 360) % 360;
}

/**
 * Approximate boiling point of water at given atmospheric pressure (°C and °F)
 */
export function calculateWaterBoilingPoint(pressureHpa: number): { celsius: number; fahrenheit: number } {
  const pMmHg = pressureHpa * 0.750062;
  const celsius = (1730.63 / (8.07131 - Math.log10(pMmHg))) - 233.426;
  const safeCelsius = Math.max(50, Math.min(115, celsius || 100));
  const fahrenheit = (safeCelsius * 9) / 5 + 32;
  return { celsius: Number(safeCelsius.toFixed(1)), fahrenheit: Number(fahrenheit.toFixed(1)) };
}

/**
 * Approximate air density at pressure and altitude (kg/m³)
 */
export function calculateAirDensity(pressureHpa: number, altitudeMeters: number): number {
  const pPa = pressureHpa * 100;
  const tempK = Math.max(200, 288.15 - 0.0065 * altitudeMeters);
  const R = 287.058; // specific gas constant for dry air
  return Number((pPa / (R * tempK)).toFixed(3));
}

/**
 * Calculate Density Altitude (feet): altitude corrected for non-standard pressure and temperature
 */
export function calculateDensityAltitude(pressureHpa: number, altitudeMeters: number): number {
  // Pressure altitude in feet
  const pressureAltFt = (1013.25 - pressureHpa) * 30 + altitudeMeters * 3.28084;
  // Standard temperature at pressure altitude: 15 - 2 * (h / 1000)
  const standardTempC = 15 - (pressureAltFt / 1000) * 1.98;
  const outsideAirTempC = standardTempC; // nominal
  const densityAltFt = pressureAltFt + 118.8 * (outsideAirTempC - standardTempC);
  return Math.round(densityAltFt);
}

/**
 * Analyze barometric trend rate to produce weather insights
 */
export function analyzeWeatherTendency(deltaHpaPerHour: number, currentPressureHpa: number): WeatherTendency {
  if (deltaHpaPerHour < -2.0) {
    return {
      label: 'Rapid Drop — Gale / Storm Warning',
      category: 'storm',
      description: 'Barometer dropping rapidly (>2 hPa/hr). High risk of tempest, severe thunderstorm, or sudden cold squall. Seek shelter.',
      deltaHpa: deltaHpaPerHour,
    };
  } else if (deltaHpaPerHour < -0.8) {
    return {
      label: 'Falling — Deteriorating',
      category: 'deteriorating',
      description: 'Pressure is decreasing. Clouds thickening, wind picking up, chance of precipitation within 6-12 hours.',
      deltaHpa: deltaHpaPerHour,
    };
  } else if (deltaHpaPerHour > 2.0) {
    return {
      label: 'Rapid Surge — Cold Front Clearing',
      category: 'improving',
      description: 'Sudden pressure spike often marks cold front passage. Gusty winds shifting to dry, clear air with rapid visibility improvement.',
      deltaHpa: deltaHpaPerHour,
    };
  } else if (deltaHpaPerHour > 0.8) {
    return {
      label: 'Rising — Improving Conditions',
      category: 'improving',
      description: 'High pressure ridge establishing. Skies clearing, calm breeze, dry weather settling in.',
      deltaHpa: deltaHpaPerHour,
    };
  } else {
    if (currentPressureHpa > 1022) {
      return {
        label: 'Stable High — Fair & Dry',
        category: 'fair',
        description: 'Atmospheric pressure is steady and elevated. Extended fair weather, light winds, and crisp visibility.',
        deltaHpa: deltaHpaPerHour,
      };
    } else if (currentPressureHpa < 1000) {
      return {
        label: 'Low Pressure Center — Unsettled',
        category: 'deteriorating',
        description: 'Persistent low pressure trough. Damp, overcast skies and variable breeze.',
        deltaHpa: deltaHpaPerHour,
      };
    }
    return {
      label: 'Steady — Persistent Weather',
      category: 'steady',
      description: 'Minimal pressure fluctuation. Existing weather patterns will continue without major shifts.',
      deltaHpa: deltaHpaPerHour,
    };
  }
}

/**
 * Calculate real-time 3-hour barometric trend analysis and arrow indicator
 * Evaluates pressure changes over the last 3 hours (WMO 0200 code practice)
 * Returns categorized trend ('rising' | 'falling' | 'steady'), arrow vector,
 * hourly milestones, and atmospheric guidance.
 */
export function calculateThreeHourBarometricTrend(
  history: PressureHistoryPoint[],
  currentPressure: number
): BarometricTrend3Hour {
  const now = Date.now();
  const threeHoursAgoMs = now - 3 * 3600 * 1000;

  // If no history, return clean steady baseline
  if (!history || history.length === 0) {
    const defaultMilestones: HourlyPressureMilestone[] = [
      { hourLabel: '3h ago', hourOffset: -3, pressure: currentPressure, deltaFromStart: 0, timestamp: threeHoursAgoMs },
      { hourLabel: '2h ago', hourOffset: -2, pressure: currentPressure, deltaFromStart: 0, timestamp: now - 2 * 3600 * 1000 },
      { hourLabel: '1h ago', hourOffset: -1, pressure: currentPressure, deltaFromStart: 0, timestamp: now - 1 * 3600 * 1000 },
      { hourLabel: 'Now', hourOffset: 0, pressure: currentPressure, deltaFromStart: 0, timestamp: now },
    ];
    return {
      trend: 'steady',
      subCategory: 'steady',
      deltaHpa3h: 0,
      ratePerHour: 0,
      pressure3hAgo: currentPressure,
      currentPressure,
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
  const sorted = [...history].sort((a, b) => a.timestamp - b.timestamp);

  // Helper to interpolate pressure at any milestone timestamp
  const getPressureAtTime = (targetTime: number): number => {
    if (sorted.length === 0) return currentPressure;
    if (targetTime <= sorted[0].timestamp) {
      return sorted[0].pressure;
    }
    if (targetTime >= now || targetTime >= sorted[sorted.length - 1].timestamp) {
      return currentPressure;
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
  const pNow = currentPressure;

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

  // Standard meteorological barometric tendency criteria over 3 hours:
  // Steady: |delta| < 0.5 hPa
  // Rising: delta >= +0.5 hPa
  // Falling: delta <= -0.5 hPa
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
  const rad = Math.PI / 180;
  const dayOfYear = Math.floor((date.getTime() - new Date(date.getFullYear(), 0, 0).getTime()) / 86400000);
  
  const declination = 23.45 * Math.sin(rad * ((360 / 365) * (dayOfYear - 81)));
  
  const utcHours = date.getUTCHours() + date.getUTCMinutes() / 60 + date.getUTCSeconds() / 3600;
  const solarTime = (utcHours + lon / 15) % 24;
  const hourAngle = (solarTime - 12) * 15;

  const latRad = lat * rad;
  const decRad = declination * rad;
  const hraRad = hourAngle * rad;

  const sinElevation = Math.sin(latRad) * Math.sin(decRad) + Math.cos(latRad) * Math.cos(decRad) * Math.cos(hraRad);
  const elevation = Math.asin(Math.max(-1, Math.min(1, sinElevation))) / rad;

  const cosAzimuth = (Math.sin(decRad) - Math.sin(latRad) * sinElevation) / (Math.cos(latRad) * Math.cos(elevation * rad));
  let azimuth = Math.acos(Math.max(-1, Math.min(1, cosAzimuth))) / rad;
  if (Math.sin(hraRad) > 0) {
    azimuth = 360 - azimuth;
  }

  return {
    azimuth: (azimuth + 180) % 360,
    elevation: Math.round(elevation * 10) / 10,
  };
}

/**
 * Format coordinates to Degrees, Minutes, Seconds (DMS)
 */
export function formatToDMS(deg: number, isLatitude: boolean): string {
  const absolute = Math.abs(deg);
  const degrees = Math.floor(absolute);
  const minutesNotTruncated = (absolute - degrees) * 60;
  const minutes = Math.floor(minutesNotTruncated);
  const seconds = Math.floor((minutesNotTruncated - minutes) * 60);

  const direction = isLatitude
    ? deg >= 0 ? 'N' : 'S'
    : deg >= 0 ? 'E' : 'W';

  return `${degrees}°${minutes}'${seconds}" ${direction}`;
}

/**
 * Generate standard GPX 1.1 XML string from recorded track points
 */
export function generateGpxString(points: TrackPoint[], sessionName: string = 'AeroGlass Track'): string {
  const trkpts = points.map((p) => {
    const timeIso = new Date(p.timestamp).toISOString();
    return `      <trkpt lat="${p.latitude.toFixed(6)}" lon="${p.longitude.toFixed(6)}">
        <ele>${p.altitude.toFixed(1)}</ele>
        <time>${timeIso}</time>
        <extensions>
          <pressure>${p.pressure.toFixed(2)}</pressure>
          <heading>${p.heading.toFixed(1)}</heading>
          <speed>${p.speed.toFixed(1)}</speed>
        </extensions>
      </trkpt>`;
  }).join('\n');

  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="AeroGlass Sensors Pro" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>${sessionName}</name>
    <time>${new Date().toISOString()}</time>
  </metadata>
  <trk>
    <name>${sessionName}</name>
    <trkseg>
${trkpts}
    </trkseg>
  </trk>
</gpx>`;
}
