export type PressureUnit = 'hPa' | 'inHg' | 'mmHg' | 'psi';
export type AltitudeUnit = 'm' | 'ft';
export type SpeedUnit = 'km/h' | 'mph' | 'kt' | 'm/s';
export type HeadingUnit = 'deg' | 'mils';
export type NorthMode = 'magnetic' | 'true';
export type ThemePalette = 'cyan' | 'emerald' | 'amber' | 'violet' | 'tactical_red' | 'phosphor_green';

export type PressureSource = 'none' | 'sensor' | 'manual' | 'simulated';

export interface PressureHistoryPoint {
  timestamp: number;
  pressure: number; // in hPa
  altitude: number | null; // in meters
}

export interface Waypoint {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  altitude: number | null; // meters (null when no altitude source was available)
  timestamp: number;
  color: string;
  notes?: string;
}

export interface TrackPoint {
  latitude: number;
  longitude: number;
  altitude: number | null;
  pressure: number | null;
  heading: number | null;
  speed: number | null; // m/s; null when the GPS reported no speed
  timestamp: number;
}

export interface TrackSession {
  isRecording: boolean;
  startTime: number | null;
  points: TrackPoint[];
  totalAscent: number; // meters
  totalDescent: number; // meters
  totalDistance: number; // meters
  maxAltitude: number | null;
  minAltitude: number | null;
}

export interface SensorState {
  // Compass & Orientation
  heading: number; // 0 - 359.9°
  pitch: number; // -90 to +90°
  roll: number; // -180 to +180°
  headingAccuracy: number | null; // degrees or null
  trueHeading: number;
  declination: number | null; // magnetic declination in degrees (WMM model; null until position is known)

  // Barometer & Altitude
  pressure: number | null; // in hPa; null when no barometer reading exists
  pressureSource: PressureSource;
  qnh: number; // Sea-level reference pressure, default 1013.25 hPa
  barometricAltitude: number | null; // best available altitude in meters (barometric, else GPS); null if neither
  relativeAltitudeZero: number; // tare offset in meters
  gpsAltitude: number | null; // in meters
  pressureHistory: PressureHistoryPoint[];
  pressureTrendRate: number | null; // hPa / hr
  weatherTendency: WeatherTendency | null;
  barometricTrend3h: BarometricTrend3Hour | null;

  // Variometer / Vertical Speed
  verticalSpeed: number; // m/s (climb or sink rate)
  sessionAscentGain: number; // meters gained during session
  sessionDescentLoss: number; // meters lost during session

  // Kinematics
  accelX: number;
  accelY: number;
  accelZ: number;
  gForce: number;
  gyroX: number;
  gyroY: number;
  gyroZ: number;

  // Environment & Magnetics
  magneticFlux: number | null; // measured, in microtesla (µT); null when no magnetometer
  expectedMagneticField: number | null; // WMM model field strength at this position (µT)
  magneticAnomaly: boolean;
  ambientLight: number | null; // lux; null when no light sensor

  // Geolocation
  latitude: number | null;
  longitude: number | null;
  gpsSpeed: number | null; // m/s
  gpsHeading: number | null;
  gpsAccuracy: number | null; // meters
  sunAzimuth: number | null; // degrees, computed from position + time
  sunElevation: number | null; // degrees

  // Hardware Status
  isHardwareOrientationAvailable: boolean;
  isHardwareMotionAvailable: boolean;
  isHardwareGyroAvailable: boolean;
  isGpsAvailable: boolean;
  isSimulationMode: boolean;
  wakeLockActive: boolean;
}

export type WeatherTendency = {
  label: string;
  category: 'storm' | 'deteriorating' | 'steady' | 'improving' | 'fair';
  description: string;
  deltaHpa: number; // over last hour
};

export type BarometricTrendCategory = 'rising' | 'falling' | 'steady';

export interface HourlyPressureMilestone {
  hourLabel: string; // e.g. "3h ago", "2h ago", "1h ago", "Now"
  hourOffset: number; // -3, -2, -1, 0
  pressure: number; // hPa
  deltaFromStart: number; // hPa difference relative to 3h ago
  timestamp: number;
}

export interface BarometricTrend3Hour {
  trend: BarometricTrendCategory; // 'rising' | 'falling' | 'steady'
  subCategory: 'rising_rapid' | 'rising' | 'steady' | 'falling' | 'falling_rapid';
  deltaHpa3h: number; // Change over 3 hours in hPa
  ratePerHour: number; // Average rate of change in hPa/hr
  pressure3hAgo: number; // Pressure at T-3h in hPa
  currentPressure: number; // Current pressure in hPa
  timestamp3hAgo: number;
  timeSpanHours: number; // Evaluation period (nominally 3.0)
  label: string; // Formatted summary e.g. "Rising (+1.6 hPa / 3h)"
  description: string; // Meteorological interpretation
  characteristicLabel: string; // WMO 0200 code interpretation e.g. "Continuous rise (+0.5 to +2.0 hPa/3h)"
  arrowDirection: 'up' | 'up_right' | 'right' | 'down_right' | 'down';
  hourlyMilestones: HourlyPressureMilestone[];
}

export interface UserPreferences {
  pressureUnit: PressureUnit;
  altitudeUnit: AltitudeUnit;
  speedUnit: SpeedUnit;
  headingUnit: HeadingUnit;
  northMode: NorthMode;
  palette: ThemePalette;
  hapticsEnabled: boolean;
  audioFeedbackEnabled: boolean;
  audioVariometerEnabled: boolean;
  wakeLockEnabled: boolean;
  targetWaypointId: string | null;
  compactTelemetry: boolean; // hide lower-priority sensor cards on the Telemetry tab

  // User-configurable speed threshold alert
  speedAlertEnabled: boolean;
  speedAlertThreshold: number; // Value in active speedUnit
  speedAlertHaptic: boolean; // Trigger haptic pulse when exceeding threshold
  speedAlertVisual: boolean; // Trigger visual alert banner and flashing HUD
  speedAlertAudio: boolean; // Trigger acoustic warning tone
}
