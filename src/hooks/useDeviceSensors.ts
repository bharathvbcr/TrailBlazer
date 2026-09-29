import { useState, useEffect, useRef, useCallback } from 'react';
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

const INITIAL_HISTORY: PressureHistoryPoint[] = [
  { timestamp: Date.now() - 3600000 * 3, pressure: 1014.8, altitude: 120 },
  { timestamp: Date.now() - 3600000 * 2, pressure: 1014.2, altitude: 125 },
  { timestamp: Date.now() - 3600000 * 1, pressure: 1013.6, altitude: 130 },
  { timestamp: Date.now() - 1800000, pressure: 1013.4, altitude: 132 },
  { timestamp: Date.now(), pressure: 1013.25, altitude: 134 },
];

const SIM_LATITUDE = 37.7749;
const SIM_LONGITUDE = -122.4194;

export function useDeviceSensors(preferences: UserPreferences, options: { locationEnabled?: boolean } = {}) {
  const locationEnabled = options.locationEnabled ?? true;
  const hasRealFix = useRef(false);
  const [sensors, setSensors] = useState<SensorState>({
    heading: 0,
    pitch: 0,
    roll: 0,
    headingAccuracy: 1,
    trueHeading: 0,
    declination: 2.5, // nominal default

    pressure: 1013.25,
    qnh: 1013.25,
    barometricAltitude: 0,
    relativeAltitudeZero: 0,
    gpsAltitude: null,
    pressureHistory: INITIAL_HISTORY,
    pressureTrendRate: -0.52,
    weatherTendency: analyzeWeatherTendency(-0.52, 1013.25),
    barometricTrend3h: calculateThreeHourBarometricTrend(INITIAL_HISTORY, 1013.25),

    verticalSpeed: 0,
    sessionAscentGain: 0,
    sessionDescentLoss: 0,

    accelX: 0,
    accelY: 0,
    accelZ: 9.81,
    gForce: 1.0,
    gyroX: 0,
    gyroY: 0,
    gyroZ: 0,

    magneticFlux: 46.2,
    magneticAnomaly: false,
    ambientLight: 340,
    lightCondition: 'office',

    // No position until the GPS reports one (or the simulator supplies a sample location)
    latitude: null,
    longitude: null,
    gpsSpeed: 0,
    gpsHeading: null,
    gpsAccuracy: null,
    sunAzimuth: 142,
    sunElevation: 48,

    isHardwareOrientationAvailable: false,
    isHardwareMotionAvailable: false,
    isGpsAvailable: false,
    isSimulationMode: false,
    wakeLockActive: false,
  });

  const lastVibrateCardinalRef = useRef<number>(-1);
  const lastLevelVibrateRef = useRef<boolean>(false);
  const smoothedHeadingRef = useRef<number>(0);
  const lastTickHeadingRef = useRef<number>(0);

  // VSI / Variometer tracking
  const lastAltTimeRef = useRef<{ alt: number; time: number }>({ alt: 0, time: Date.now() });
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
    setSensors((prev) => {
      const alt = calculateBarometricAltitude(prev.pressure, newQnh);
      return {
        ...prev,
        qnh: Number(newQnh.toFixed(2)),
        barometricAltitude: Number(alt.toFixed(1)),
      };
    });
  }, []);

  const setManualPressure = useCallback((newPressure: number) => {
    setSensors((prev) => {
      const alt = calculateBarometricAltitude(newPressure, prev.qnh);
      const historyPoint: PressureHistoryPoint = {
        timestamp: Date.now(),
        pressure: newPressure,
        altitude: alt,
      };
      // Keep history points covering at least 4 hours so 3-hour trend remains continuously evaluated
      const fourHoursAgo = Date.now() - 4 * 3600 * 1000;
      const filtered = prev.pressureHistory.filter((pt) => pt.timestamp >= fourHoursAgo);
      const updatedHistory = [...filtered, historyPoint];
      const trend3h = calculateThreeHourBarometricTrend(updatedHistory, newPressure);
      const trendRate = trend3h.ratePerHour;
      return {
        ...prev,
        pressure: Number(newPressure.toFixed(2)),
        barometricAltitude: Number(alt.toFixed(1)),
        pressureHistory: updatedHistory,
        pressureTrendRate: trendRate,
        weatherTendency: analyzeWeatherTendency(trendRate, newPressure),
        barometricTrend3h: trend3h,
      };
    });
  }, []);

  const setDeclination = useCallback((newDec: number) => {
    setSensors((prev) => ({
      ...prev,
      declination: Number(newDec.toFixed(1)),
      trueHeading: Math.round(((prev.heading + newDec + 360) % 360) * 10) / 10,
    }));
  }, []);

  // Tare / Zero Relative Altitude
  const tareAltitude = useCallback(() => {
    setSensors((prev) => ({
      ...prev,
      relativeAltitudeZero: prev.barometricAltitude,
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
      if (prev.gpsAltitude === null) return prev;
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
        trueHeading: (norm + prev.declination) % 360,
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
      if (turningOn && prev.latitude === null) {
        // Supply a sample location so map/track features are demonstrable
        return { ...prev, isSimulationMode: true, latitude: SIM_LATITUDE, longitude: SIM_LONGITUDE, gpsAccuracy: 8, isGpsAvailable: true };
      }
      if (!turningOn && !hasRealFix.current) {
        return { ...prev, isSimulationMode: false, latitude: null, longitude: null, gpsAccuracy: null, isGpsAvailable: false };
      }
      return { ...prev, isSimulationMode: turningOn };
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
          trueHeading: Math.round(((normHeading + prev.declination) % 360) * 10) / 10,
          pitch: Math.round(pitch * 10) / 10,
          roll: Math.round(roll * 10) / 10,
          headingAccuracy: accuracy,
          isHardwareOrientationAvailable: true,
        };
      });
    };

    const handleMotion = (event: DeviceMotionEvent) => {
      hasMotionData = true;
      const acc = event.accelerationIncludingGravity || event.acceleration;
      const rot = event.rotationRate;

      const ax = acc?.x ?? 0;
      const ay = acc?.y ?? 0;
      const az = acc?.z ?? 9.81;

      const magnitude = Math.sqrt(ax * ax + ay * ay + az * az);
      const gForce = magnitude / 9.80665;

      const baseMagneticFlux = 45 + Math.abs(ax) * 1.5;
      const anomaly = baseMagneticFlux > 75;

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
          magneticFlux: Math.round(baseMagneticFlux * 10) / 10,
          magneticAnomaly: anomaly,
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
        const sun = calculateSunPosition(latitude, longitude);

        setSensors((prev) => {
          let updatedAlt = prev.barometricAltitude;
          if (altitude !== null && !prev.isSimulationMode) {
            updatedAlt = altitude;
          }

          return {
            ...prev,
            latitude: Number(latitude.toFixed(5)),
            longitude: Number(longitude.toFixed(5)),
            gpsAltitude: altitude !== null ? Number(altitude.toFixed(1)) : prev.gpsAltitude,
            gpsSpeed: speed !== null ? Number(speed.toFixed(1)) : 0,
            gpsHeading: heading !== null ? Number(heading.toFixed(1)) : null,
            gpsAccuracy: accuracy !== null ? Math.round(accuracy) : null,
            sunAzimuth: Math.round(sun.azimuth),
            sunElevation: Math.round(sun.elevation),
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

  // Periodic sensor snapshot recorder (updates pressure history & weather tendency)
  useEffect(() => {
    const interval = setInterval(() => {
      setSensors((prev) => {
        const point: PressureHistoryPoint = {
          timestamp: Date.now(),
          pressure: prev.pressure,
          altitude: prev.barometricAltitude,
        };
        const fourHoursAgo = Date.now() - 4 * 3600 * 1000;
        const filtered = prev.pressureHistory.filter((p) => p.timestamp >= fourHoursAgo);
        const newHistory = [...filtered, point];
        const trend3h = calculateThreeHourBarometricTrend(newHistory, prev.pressure);
        const roundedRate = trend3h.ratePerHour;

        return {
          ...prev,
          pressureHistory: newHistory,
          pressureTrendRate: roundedRate,
          weatherTendency: analyzeWeatherTendency(roundedRate, prev.pressure),
          barometricTrend3h: trend3h,
        };
      });
    }, 20000);

    return () => clearInterval(interval);
  }, []);

  return {
    sensors,
    setQnh,
    setManualPressure,
    setDeclination,
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
