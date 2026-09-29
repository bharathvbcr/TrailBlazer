import { useState, useEffect, useRef, useCallback } from 'react';
import geomagnetism from 'geomagnetism';
import {
  SensorState,
  PressureHistoryPoint,
  UserPreferences,
} from '../types/sensors';
import {
  calculateBarometricAltitude,
  calculateSunPosition,
  analyzeWeatherTendency,
  calculateThreeHourBarometricTrend,
} from '../utils/calculations';
import {
  playCompassTick,
  playChime,
  updateAudioVariometer,
  stopAudioVariometer,
} from '../utils/audioHaptics';

const HISTORY_KEY = 'aeroglass_pressure_history';
const HISTORY_WINDOW_MS = 4 * 3600 * 1000;
const MIN_TREND_SPAN_MS = 10 * 60 * 1000;

/** Real pressure readings recorded on this device (persisted so trends survive reloads). */
function loadPressureHistory(): PressureHistoryPoint[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    if (!raw) return [];
    const cutoff = Date.now() - HISTORY_WINDOW_MS;
    return (JSON.parse(raw) as PressureHistoryPoint[]).filter(
      (p) => typeof p.timestamp === 'number' && typeof p.pressure === 'number' && p.timestamp >= cutoff
    );
  } catch {
    return [];
  }
}

/** Trend/tendency are only meaningful once readings span a useful period; otherwise they stay null. */
function deriveTrend(history: PressureHistoryPoint[], pressure: number | null) {
  const sorted = history.length > 1 ? history : [];
  const span = sorted.length ? sorted[sorted.length - 1].timestamp - sorted[0].timestamp : 0;
  if (pressure === null || span < MIN_TREND_SPAN_MS) {
    return { pressureTrendRate: null, weatherTendency: null, barometricTrend3h: null };
  }
  const trend3h = calculateThreeHourBarometricTrend(history, pressure);
  return {
    pressureTrendRate: trend3h.ratePerHour,
    weatherTendency: analyzeWeatherTendency(trend3h.ratePerHour, pressure),
    barometricTrend3h: trend3h,
  };
}

type NativeBridge = {
  getPressureHpa?: () => number;
  getMagneticFluxUt?: () => number;
  getLightLux?: () => number;
};

const SIM_LATITUDE = 37.7749;
const SIM_LONGITUDE = -122.4194;

export function useDeviceSensors(preferences: UserPreferences, options: { locationEnabled?: boolean } = {}) {
  const locationEnabled = options.locationEnabled ?? true;
  const hasRealFix = useRef(false);
  const [sensors, setSensors] = useState<SensorState>(() => ({
    // Orientation: zero until a real (or simulated) source reports; UI gates on availability flags
    heading: 0,
    pitch: 0,
    roll: 0,
    headingAccuracy: null,
    trueHeading: 0,
    declination: null,

    // Barometer: no reading exists until a device sensor or the user provides one
    pressure: null,
    pressureSource: 'none',
    qnh: 1013.25, // standard-atmosphere reference (user adjustable), not a measurement
    barometricAltitude: null,
    relativeAltitudeZero: 0,
    gpsAltitude: null,
    pressureHistory: loadPressureHistory(),
    pressureTrendRate: null,
    weatherTendency: null,
    barometricTrend3h: null,

    verticalSpeed: 0,
    sessionAscentGain: 0,
    sessionDescentLoss: 0,

    accelX: 0,
    accelY: 0,
    accelZ: 0,
    gForce: 0,
    gyroX: 0,
    gyroY: 0,
    gyroZ: 0,

    magneticFlux: null,
    expectedMagneticField: null,
    magneticAnomaly: false,
    ambientLight: null,

    // No position until the GPS reports one (or the simulator supplies a sample location)
    latitude: null,
    longitude: null,
    gpsSpeed: null,
    gpsHeading: null,
    gpsAccuracy: null,
    sunAzimuth: null,
    sunElevation: null,

    isHardwareOrientationAvailable: false,
    isHardwareMotionAvailable: false,
    isGpsAvailable: false,
    isSimulationMode: false,
    wakeLockActive: false,
  }));
  const realHistoryRef = useRef<PressureHistoryPoint[]>([]);
  const lastHistoryWriteRef = useRef(0);

  const lastVibrateCardinalRef = useRef<number>(-1);
  const lastLevelVibrateRef = useRef<boolean>(false);
  const smoothedHeadingRef = useRef<number>(0);
  const headingSeededRef = useRef(false);
  const lastTickHeadingRef = useRef<number>(0);

  // VSI / Variometer tracking
  const lastAltTimeRef = useRef<{ alt: number | null; time: number }>({ alt: null, time: Date.now() });
  const smoothedVsiRef = useRef<number>(0);
  const wakeLockSentinelRef = useRef<unknown>(null);

  // Trigger haptic feedback
  const triggerHaptic = useCallback((pattern: number | number[]) => {
    if (!preferences.hapticsEnabled) return;
    try {
      const bridge = (window as unknown as { AndroidBridge?: { vibrate: (duration: number) => void } }).AndroidBridge;
      if (bridge && typeof bridge.vibrate === 'function') {
        const duration = Array.isArray(pattern) ? (pattern[0] || 40) : pattern;
        bridge.vibrate(duration);
      }
    } catch {
      // ignore
    }
    if (typeof navigator !== 'undefined' && navigator.vibrate) {
      try {
        navigator.vibrate(pattern);
      } catch {
        // ignore
      }
    }
  }, [preferences.hapticsEnabled]);

  // Request screen wake lock
  useEffect(() => {
    let isCancelled = false;

    async function manageWakeLock() {
      if (typeof navigator === 'undefined' || !('wakeLock' in navigator)) return;

      if (preferences.wakeLockEnabled) {
        try {
          const wl = await (navigator.wakeLock as unknown as { request: (type: string) => Promise<unknown> }).request('screen');
          if (!isCancelled) {
            wakeLockSentinelRef.current = wl;
            setSensors((prev) => ({ ...prev, wakeLockActive: true }));
          }
        } catch {
          setSensors((prev) => ({ ...prev, wakeLockActive: false }));
        }
      } else {
        if (wakeLockSentinelRef.current) {
          try {
            await (wakeLockSentinelRef.current as { release: () => Promise<void> }).release();
          } catch {}
          wakeLockSentinelRef.current = null;
        }
        setSensors((prev) => ({ ...prev, wakeLockActive: false }));
      }
    }

    manageWakeLock();

    return () => {
      isCancelled = true;
      if (wakeLockSentinelRef.current) {
        try {
          (wakeLockSentinelRef.current as { release: () => Promise<void> }).release();
        } catch {}
      }
    };
  }, [preferences.wakeLockEnabled]);

  // Audio Variometer effect
  useEffect(() => {
    if (preferences.audioVariometerEnabled) {
      updateAudioVariometer(sensors.verticalSpeed, true);
    } else {
      stopAudioVariometer();
    }
    return () => {
      stopAudioVariometer();
    };
  }, [sensors.verticalSpeed, preferences.audioVariometerEnabled]);

  // Request iOS permission if needed
  const requestOrientationPermission = useCallback(async () => {
    if (
      typeof window !== 'undefined' &&
      typeof (DeviceOrientationEvent as unknown as { requestPermission?: () => Promise<string> }).requestPermission === 'function'
    ) {
      try {
        const response = await (DeviceOrientationEvent as unknown as { requestPermission: () => Promise<string> }).requestPermission();
        return response === 'granted';
      } catch (err) {
        console.warn('Orientation permission error:', err);
        return false;
      }
    }
    return true;
  }, []);

  // Update pressure or QNH
  const setQnh = useCallback((newQnh: number) => {
    setSensors((prev) => ({
      ...prev,
      qnh: newQnh,
      barometricAltitude: prev.pressure !== null ? calculateBarometricAltitude(prev.pressure, newQnh) : prev.barometricAltitude,
    }));
  }, []);

  // A pressure reading from the user (or the simulator); recorded in history as a real data point
  const setManualPressure = useCallback((newPressure: number) => {
    setSensors((prev) => {
      const alt = calculateBarometricAltitude(newPressure, prev.qnh);
      const history = [
        ...prev.pressureHistory.filter((pt) => pt.timestamp >= Date.now() - HISTORY_WINDOW_MS),
        { timestamp: Date.now(), pressure: Number(newPressure.toFixed(2)), altitude: Number(alt.toFixed(1)) },
      ];
      return {
        ...prev,
        pressure: Number(newPressure.toFixed(2)),
        pressureSource: prev.isSimulationMode ? 'simulated' : 'manual',
        barometricAltitude: Number(alt.toFixed(1)),
        pressureHistory: history,
        ...deriveTrend(history, newPressure),
      };
    });
  }, []);

  // Tare / Zero Relative Altitude
  const tareAltitude = useCallback(() => {
    setSensors((prev) => ({
      ...prev,
      relativeAltitudeZero: prev.barometricAltitude ?? 0,
    }));
    triggerHaptic(20);
  }, [triggerHaptic]);

  const resetTare = useCallback(() => {
    setSensors((prev) => ({
      ...prev,
      relativeAltitudeZero: 0,
    }));
    triggerHaptic(20);
  }, [triggerHaptic]);

  // Calibrate QNH so current barometric altitude matches GPS altitude
  const calibrateToGpsAltitude = useCallback(() => {
    setSensors((prev) => {
      if (prev.gpsAltitude === null || prev.pressure === null) return prev;
      const gpsAlt = prev.gpsAltitude;
      const newQnh = prev.pressure * Math.pow(1 - (gpsAlt / 44330.8), -5.255);
      return {
        ...prev,
        qnh: Number(newQnh.toFixed(2)),
        barometricAltitude: Number(gpsAlt.toFixed(1)),
      };
    });
    triggerHaptic([30, 50, 30]);
  }, [triggerHaptic]);

  // Simulation setters for desktop / manual testing
  const setSimulationHeading = useCallback((h: number) => {
    setSensors((prev) => {
      const norm = (h % 360 + 360) % 360;
      return {
        ...prev,
        heading: norm,
        trueHeading: (norm + (prev.declination ?? 0)) % 360,
      };
    });
  }, []);

  const setSimulationPitchRoll = useCallback((pitch: number, roll: number) => {
    setSensors((prev) => ({
      ...prev,
      pitch,
      roll,
    }));
  }, []);

  const toggleSimulationMode = useCallback(() => {
    setSensors((prev) => {
      const turningOn = !prev.isSimulationMode;
      if (turningOn) {
        // Remember real readings so simulated values never contaminate them
        realHistoryRef.current = prev.pressureSource === 'simulated' ? realHistoryRef.current : prev.pressureHistory;
        return {
          ...prev,
          isSimulationMode: true,
          ...(prev.latitude === null
            ? { latitude: SIM_LATITUDE, longitude: SIM_LONGITUDE, gpsAccuracy: 8, isGpsAvailable: true }
            : {}),
        };
      }
      const restored = realHistoryRef.current;
      const leavingSimPressure = prev.pressureSource === 'simulated';
      return {
        ...prev,
        isSimulationMode: false,
        ...(hasRealFix.current
          ? {}
          : { latitude: null, longitude: null, gpsAccuracy: null, gpsSpeed: null, isGpsAvailable: false }),
        ...(leavingSimPressure
          ? {
              pressure: null,
              pressureSource: 'none' as const,
              barometricAltitude: prev.gpsAltitude,
              pressureHistory: restored,
              ...deriveTrend(restored, null),
            }
          : {}),
      };
    });
  }, []);

  const setSimulationSpeed = useCallback((speedMs: number) => {
    setSensors((prev) => ({
      ...prev,
      gpsSpeed: Number(Math.max(0, speedMs).toFixed(1)),
      isGpsAvailable: true,
    }));
  }, []);

  // Quick preset scenario tester for 3-hour barometric trend
  const setSimulatedTrendScenario = useCallback((scenario: 'rising' | 'falling' | 'steady') => {
    setSensors((prev) => {
      if (prev.pressure === null || !prev.isSimulationMode) return prev;
      const now = Date.now();
      const currentP = prev.pressure;
      let p3h: number;
      let p2h: number;
      let p1h: number;

      if (scenario === 'rising') {
        p3h = currentP - 2.1;
        p2h = currentP - 1.4;
        p1h = currentP - 0.7;
      } else if (scenario === 'falling') {
        p3h = currentP + 2.4;
        p2h = currentP + 1.6;
        p1h = currentP + 0.8;
      } else {
        p3h = currentP + 0.1;
        p2h = currentP - 0.05;
        p1h = currentP + 0.05;
      }

      const simulatedHistory: PressureHistoryPoint[] = [
        { timestamp: now - 3 * 3600 * 1000, pressure: Number(p3h.toFixed(2)), altitude: calculateBarometricAltitude(p3h, prev.qnh) },
        { timestamp: now - 2 * 3600 * 1000, pressure: Number(p2h.toFixed(2)), altitude: calculateBarometricAltitude(p2h, prev.qnh) },
        { timestamp: now - 1 * 3600 * 1000, pressure: Number(p1h.toFixed(2)), altitude: calculateBarometricAltitude(p1h, prev.qnh) },
        { timestamp: now - 1800 * 1000, pressure: Number(((p1h + currentP) / 2).toFixed(2)), altitude: calculateBarometricAltitude((p1h + currentP) / 2, prev.qnh) },
        { timestamp: now, pressure: currentP, altitude: prev.barometricAltitude },
      ];

      const trend3h = calculateThreeHourBarometricTrend(simulatedHistory, currentP);
      return {
        ...prev,
        pressureHistory: simulatedHistory,
        pressureSource: 'simulated' as const,
        pressureTrendRate: trend3h.ratePerHour,
        weatherTendency: analyzeWeatherTendency(trend3h.ratePerHour, currentP),
        barometricTrend3h: trend3h,
      };
    });
    triggerHaptic(25);
  }, [triggerHaptic]);

  // Hardware sensor listeners
  useEffect(() => {
    let hasOrientationData = false;
    let hasMotionData = false;

    const handleOrientation = (event: DeviceOrientationEvent) => {
      // Only trust events that carry a north reference. Relative-alpha events (or events with
      // no data, as desktop browsers fire once) would produce a made-up heading.
      const hasNorthReference =
        typeof (event as unknown as { webkitCompassHeading?: number }).webkitCompassHeading === 'number' ||
        event.absolute === true ||
        (event.type as string) === 'deviceorientationabsolute';
      if (!hasNorthReference || (event.alpha === null && typeof (event as unknown as { webkitCompassHeading?: number }).webkitCompassHeading !== 'number')) return;
      hasOrientationData = true;
      let rawHeading = 0;
      let accuracy: number | null = null;

      if (typeof (event as unknown as { webkitCompassHeading?: number }).webkitCompassHeading === 'number') {
        const iosHeading = (event as unknown as { webkitCompassHeading: number }).webkitCompassHeading;
        if (!isNaN(iosHeading)) {
          rawHeading = iosHeading;
          accuracy = (event as unknown as { webkitCompassAccuracy?: number }).webkitCompassAccuracy ?? 1;
        }
      } else if (event.alpha !== null && !isNaN(event.alpha)) {
        rawHeading = (360 - event.alpha) % 360;
      }

      // Smooth heading with low-pass filter to prevent jumpy needle
      // Seed the filter with the first real reading so the needle doesn't sweep in from 0°
      if (!headingSeededRef.current) {
        smoothedHeadingRef.current = rawHeading;
        lastTickHeadingRef.current = rawHeading;
        headingSeededRef.current = true;
      }
      let current = smoothedHeadingRef.current;
      let diff = rawHeading - current;
      if (diff > 180) diff -= 360;
      if (diff < -180) diff += 360;
      current = (current + diff * 0.25 + 360) % 360;
      smoothedHeadingRef.current = current;

      // Acoustic tick every 5 degrees rotated
      if (preferences.audioFeedbackEnabled) {
        const diffFromLastTick = Math.abs(current - lastTickHeadingRef.current);
        if (diffFromLastTick >= 5) {
          lastTickHeadingRef.current = current;
          playCompassTick();
        }
      }

      const pitch = event.beta !== null ? Math.max(-90, Math.min(90, event.beta)) : 0;
      const roll = event.gamma !== null ? Math.max(-180, Math.min(180, event.gamma)) : 0;

      // Haptic & chime on cardinal crosses
      const cardinalIndex = Math.round(current / 90) % 4;
      const distToCardinal = Math.abs(current - cardinalIndex * 90);
      if (distToCardinal < 1.5 && lastVibrateCardinalRef.current !== cardinalIndex) {
        lastVibrateCardinalRef.current = cardinalIndex;
        triggerHaptic(12);
        if (preferences.audioFeedbackEnabled && cardinalIndex === 0) {
          playChime(false);
        }
      } else if (distToCardinal > 5) {
        lastVibrateCardinalRef.current = -1;
      }

      // Haptic & chime on spirit level zero (pitch & roll < 0.7°)
      const isLevel = Math.abs(pitch) < 0.7 && Math.abs(roll) < 0.7;
      if (isLevel && !lastLevelVibrateRef.current) {
        lastLevelVibrateRef.current = true;
        triggerHaptic([10, 30, 10]);
        if (preferences.audioFeedbackEnabled) {
          playChime(true);
        }
      } else if (!isLevel) {
        lastLevelVibrateRef.current = false;
      }

      setSensors((prev) => {
        if (prev.isSimulationMode) return prev;
        const normHeading = Math.round(current * 10) / 10;
        return {
          ...prev,
          heading: normHeading,
          trueHeading: Math.round(((normHeading + (prev.declination ?? 0)) % 360) * 10) / 10,
          pitch: Math.round(pitch * 10) / 10,
          roll: Math.round(roll * 10) / 10,
          headingAccuracy: accuracy,
          isHardwareOrientationAvailable: true,
        };
      });
    };

    const handleMotion = (event: DeviceMotionEvent) => {
      const acc = event.accelerationIncludingGravity || event.acceleration;
      if (!acc || acc.x === null || acc.y === null || acc.z === null) return;
      hasMotionData = true;
      const rot = event.rotationRate;

      const ax = acc?.x ?? 0;
      const ay = acc?.y ?? 0;
      const az = acc?.z ?? 0;

      const magnitude = Math.sqrt(ax * ax + ay * ay + az * az);
      const gForce = magnitude / 9.80665;

      setSensors((prev) => {
        if (prev.isSimulationMode) return prev;
        return {
          ...prev,
          accelX: Math.round(ax * 100) / 100,
          accelY: Math.round(ay * 100) / 100,
          accelZ: Math.round(az * 100) / 100,
          gForce: Math.round(gForce * 100) / 100,
          gyroX: Math.round((rot?.alpha ?? 0) * 10) / 10,
          gyroY: Math.round((rot?.beta ?? 0) * 10) / 10,
          gyroZ: Math.round((rot?.gamma ?? 0) * 10) / 10,
          isHardwareMotionAvailable: true,
        };
      });
    };

    window.addEventListener('deviceorientationabsolute' as unknown as keyof WindowEventMap, handleOrientation as EventListener, true);
    window.addEventListener('deviceorientation', handleOrientation, true);
    window.addEventListener('devicemotion', handleMotion, true);

    const timer = setTimeout(() => {
      if (!hasOrientationData) {
        setSensors((prev) => ({ ...prev, isHardwareOrientationAvailable: false }));
      }
      if (!hasMotionData) {
        setSensors((prev) => ({ ...prev, isHardwareMotionAvailable: false }));
      }
    }, 2000);

    return () => {
      window.removeEventListener('deviceorientationabsolute' as unknown as keyof WindowEventMap, handleOrientation as EventListener, true);
      window.removeEventListener('deviceorientation', handleOrientation, true);
      window.removeEventListener('devicemotion', handleMotion, true);
      clearTimeout(timer);
    };
  }, [triggerHaptic, preferences.audioFeedbackEnabled]);

  // GPS Geolocation Watcher (deferred until the user has opted in)
  useEffect(() => {
    if (!locationEnabled) return;
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      setSensors((prev) => ({ ...prev, isGpsAvailable: false }));
      return;
    }

    const watchId = navigator.geolocation.watchPosition(
      (pos) => {
        hasRealFix.current = true;
        const { latitude, longitude, altitude, speed, heading, accuracy } = pos.coords;
        setSensors((prev) => {
          // GPS altitude is the fallback only while no barometric reading exists
          let updatedAlt = prev.barometricAltitude;
          if (altitude !== null && !prev.isSimulationMode && prev.pressure === null) {
            updatedAlt = altitude;
          }

          return {
            ...prev,
            latitude: Number(latitude.toFixed(5)),
            longitude: Number(longitude.toFixed(5)),
            gpsAltitude: altitude !== null ? Number(altitude.toFixed(1)) : prev.gpsAltitude,
            gpsSpeed: speed !== null ? Number(speed.toFixed(1)) : null,
            gpsHeading: heading !== null ? Number(heading.toFixed(1)) : null,
            gpsAccuracy: accuracy !== null ? Math.round(accuracy) : null,
            isGpsAvailable: true,
            barometricAltitude: updatedAlt,
          };
        });
      },
      (err) => {
        console.warn('Geolocation warning:', err.message);
        setSensors((prev) => ({ ...prev, isGpsAvailable: false }));
      },
      {
        enableHighAccuracy: true,
        maximumAge: 4000,
        timeout: 10000,
      }
    );

    return () => {
      navigator.geolocation.clearWatch(watchId);
    };
  }, [locationEnabled]);

  // Variometer computation (Vertical Speed Derivative)
  useEffect(() => {
    const vsiInterval = setInterval(() => {
      const now = Date.now();
      const currentAlt = sensors.barometricAltitude;
      const last = lastAltTimeRef.current;
      if (currentAlt === null || last.alt === null) {
        lastAltTimeRef.current = { alt: currentAlt, time: now };
        return;
      }
      const dt = Math.max(0.2, (now - last.time) / 1000); // seconds

      const rawVsi = (currentAlt - last.alt) / dt; // m/s
      // Exponential filter for smooth readings
      smoothedVsiRef.current = smoothedVsiRef.current * 0.7 + rawVsi * 0.3;
      const roundedVsi = Number(smoothedVsiRef.current.toFixed(2));

      lastAltTimeRef.current = { alt: currentAlt, time: now };

      const deltaAlt = currentAlt - last.alt;

      setSensors((prev) => ({
        ...prev,
        verticalSpeed: roundedVsi,
        sessionAscentGain: deltaAlt > 0 ? Number((prev.sessionAscentGain + deltaAlt).toFixed(1)) : prev.sessionAscentGain,
        sessionDescentLoss: deltaAlt < 0 ? Number((prev.sessionDescentLoss + Math.abs(deltaAlt)).toFixed(1)) : prev.sessionDescentLoss,
      }));
    }, 800);

    return () => clearInterval(vsiInterval);
  }, [sensors.barometricAltitude]);

  // Re-evaluate the trend as time passes (no synthetic points are added)
  useEffect(() => {
    const interval = setInterval(() => {
      setSensors((prev) =>
        prev.pressure === null ? prev : { ...prev, ...deriveTrend(prev.pressureHistory, prev.pressure) }
      );
    }, 60000);
    return () => clearInterval(interval);
  }, []);

  // Persist genuine pressure history (never simulated data)
  useEffect(() => {
    if (sensors.isSimulationMode || sensors.pressureSource === 'simulated') return;
    try {
      localStorage.setItem(HISTORY_KEY, JSON.stringify(sensors.pressureHistory));
    } catch {
      // ignore
    }
  }, [sensors.pressureHistory, sensors.isSimulationMode, sensors.pressureSource]);

  // Magnetic declination + expected field strength from the World Magnetic Model
  useEffect(() => {
    if (sensors.latitude === null || sensors.longitude === null) {
      setSensors((prev) =>
        prev.declination === null && prev.expectedMagneticField === null ? prev : { ...prev, declination: null, expectedMagneticField: null }
      );
      return;
    }
    try {
      const info = geomagnetism
        .model(new Date(), { allowOutOfBoundsModel: true })
        .point([sensors.latitude, sensors.longitude]);
      setSensors((prev) => ({
        ...prev,
        declination: Number(info.decl.toFixed(1)),
        expectedMagneticField: Number((info.f / 1000).toFixed(1)),
        trueHeading: Math.round((((prev.heading + info.decl) % 360) + 360) % 360 * 10) / 10,
      }));
    } catch (err) {
      console.warn('Declination model unavailable:', err);
    }
  }, [sensors.latitude, sensors.longitude]);

  // Sun position: pure astronomy from position + clock
  useEffect(() => {
    const update = () => {
      setSensors((prev) => {
        if (prev.latitude === null || prev.longitude === null) {
          return prev.sunAzimuth === null ? prev : { ...prev, sunAzimuth: null, sunElevation: null };
        }
        const sun = calculateSunPosition(prev.latitude, prev.longitude);
        return { ...prev, sunAzimuth: Math.round(sun.azimuth), sunElevation: Math.round(sun.elevation) };
      });
    };
    update();
    const interval = setInterval(update, 60000);
    return () => clearInterval(interval);
  }, [sensors.latitude, sensors.longitude]);

  // Environmental sensors: only genuine hardware sources (Android bridge, Generic Sensor API)
  useEffect(() => {
    const cleanups: Array<() => void> = [];
    const w = window as unknown as Record<string, unknown> & { AndroidBridge?: NativeBridge };

    const setMag = (flux: number) =>
      setSensors((prev) => {
        const expected = prev.expectedMagneticField;
        const anomaly = expected ? Math.abs(flux - expected) / expected > 0.35 : flux < 20 || flux > 80;
        return { ...prev, magneticFlux: Math.round(flux * 10) / 10, magneticAnomaly: anomaly };
      });
    const setLight = (lux: number) => setSensors((prev) => ({ ...prev, ambientLight: Math.round(lux) }));

    const startGenericSensor = (name: string, onReading: (sensor: Record<string, number>) => void) => {
      try {
        const Ctor = w[name] as (new (opts: { frequency: number }) => EventTarget & { start: () => void; stop: () => void } & Record<string, number>) | undefined;
        if (!Ctor) return;
        const sensor = new Ctor({ frequency: 4 });
        sensor.addEventListener('reading', () => onReading(sensor));
        sensor.addEventListener('error', () => {});
        sensor.start();
        cleanups.push(() => sensor.stop());
      } catch {
        // sensor blocked by permissions policy or unsupported
      }
    };
    startGenericSensor('Magnetometer', (s) => setMag(Math.hypot(s.x, s.y, s.z)));
    startGenericSensor('AmbientLightSensor', (s) => setLight(s.illuminance));

    const bridge = w.AndroidBridge;
    if (bridge) {
      const poll = () => {
        const flux = bridge.getMagneticFluxUt?.();
        if (typeof flux === 'number' && isFinite(flux)) setMag(flux);
        const lux = bridge.getLightLux?.();
        if (typeof lux === 'number' && isFinite(lux)) setLight(lux);
        const hpa = bridge.getPressureHpa?.();
        if (typeof hpa === 'number' && isFinite(hpa) && hpa > 300 && hpa < 1100) {
          setSensors((prev) => {
            if (prev.isSimulationMode) return prev;
            const now = Date.now();
            const alt = calculateBarometricAltitude(hpa, prev.qnh);
            let history = prev.pressureHistory;
            if (now - lastHistoryWriteRef.current >= 60000) {
              lastHistoryWriteRef.current = now;
              history = [
                ...history.filter((pt) => pt.timestamp >= now - HISTORY_WINDOW_MS),
                { timestamp: now, pressure: Number(hpa.toFixed(2)), altitude: Number(alt.toFixed(1)) },
              ];
            }
            return {
              ...prev,
              pressure: Number(hpa.toFixed(2)),
              pressureSource: 'sensor',
              barometricAltitude: Number(alt.toFixed(1)),
              pressureHistory: history,
              ...(history === prev.pressureHistory ? {} : deriveTrend(history, hpa)),
            };
          });
        }
      };
      poll();
      const interval = window.setInterval(poll, 1000);
      cleanups.push(() => window.clearInterval(interval));
    }

    return () => cleanups.forEach((fn) => fn());
  }, []);

  return {
    sensors,
    setQnh,
    setManualPressure,
    tareAltitude,
    resetTare,
    calibrateToGpsAltitude,
    setSimulationHeading,
    setSimulationPitchRoll,
    setSimulationSpeed,
    toggleSimulationMode,
    setSimulatedTrendScenario,
    requestOrientationPermission,
    triggerHaptic,
  };
}
