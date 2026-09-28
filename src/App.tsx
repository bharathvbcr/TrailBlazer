import React, { useState, useEffect, useRef } from 'react';
import {
  UserPreferences,
  Waypoint,
  TrackSession,
  TrackPoint,
} from './types/sensors';
import { useDeviceSensors } from './hooks/useDeviceSensors';
import { calculateDistanceMeters, convertSpeed } from './utils/calculations';
import { playSpeedAlertTone } from './utils/audioHaptics';
import { LiquidGlassBackground } from './components/LiquidGlassBackground';
import { CompassView } from './components/CompassView';
import { AltimeterBarometerView } from './components/AltimeterBarometerView';
import { SensorMatrixView } from './components/SensorMatrixView';
import { WaypointsNavView } from './components/WaypointsNavView';
import { SettingsModal } from './components/SettingsModal';
import { CameraSightingView } from './components/CameraSightingView';
import { CalibrationModal } from './components/CalibrationModal';
import {
  Compass,
  Mountain,
  Activity,
  MapPin,
  Settings,
  Sparkles,
  Eye,
  AlertTriangle,
} from 'lucide-react';

const STORAGE_PREFS_KEY = 'aeroglass_user_prefs';
const STORAGE_WAYPOINTS_KEY = 'aeroglass_waypoints';

const DEFAULT_PREFERENCES: UserPreferences = {
  pressureUnit: 'hPa',
  altitudeUnit: 'm',
  speedUnit: 'km/h',
  headingUnit: 'deg',
  northMode: 'magnetic',
  palette: 'cyan',
  hapticsEnabled: true,
  audioFeedbackEnabled: true,
  audioVariometerEnabled: false,
  wakeLockEnabled: false,
  targetWaypointId: null,

  speedAlertEnabled: true,
  speedAlertThreshold: 25,
  speedAlertHaptic: true,
  speedAlertVisual: true,
  speedAlertAudio: true,
};

const DEFAULT_WAYPOINTS: Waypoint[] = [
  {
    id: 'wp-1',
    name: 'Highland Ridge Camp',
    latitude: 37.7812,
    longitude: -122.4110,
    altitude: 185,
    timestamp: Date.now() - 3600000 * 2,
    color: '#38bdf8',
    notes: 'Camp shelter with fresh water spring',
  },
  {
    id: 'wp-2',
    name: 'Trailhead Parking',
    latitude: 37.7710,
    longitude: -122.4280,
    altitude: 42,
    timestamp: Date.now() - 3600000 * 5,
    color: '#fbbf24',
    notes: 'Vehicle parked near ranger post',
  },
];

type TabId = 'compass' | 'altimeter' | 'sensors' | 'waypoints';

const NAV_ITEMS = [
  { id: 'compass' as const, label: 'Compass', icon: Compass },
  { id: 'altimeter' as const, label: 'Altimeter', icon: Mountain },
  { id: 'sensors' as const, label: 'Telemetry', icon: Activity },
  { id: 'waypoints' as const, label: 'Tracks', icon: MapPin },
];

const TAB_SUBTITLES: Record<TabId, string> = {
  compass: 'Heading & navigation',
  altimeter: 'Altitude & barometer',
  sensors: 'Live sensor telemetry',
  waypoints: 'Waypoints & track recorder',
};

export const App: React.FC = () => {
  // Load preferences from localStorage or default
  const [preferences, setPreferences] = useState<UserPreferences>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_PREFS_KEY);
      return saved ? { ...DEFAULT_PREFERENCES, ...JSON.parse(saved) } : DEFAULT_PREFERENCES;
    } catch {
      return DEFAULT_PREFERENCES;
    }
  });

  // Load waypoints from localStorage or default
  const [waypoints, setWaypoints] = useState<Waypoint[]>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_WAYPOINTS_KEY);
      return saved ? JSON.parse(saved) : DEFAULT_WAYPOINTS;
    } catch {
      return DEFAULT_WAYPOINTS;
    }
  });

  const [activeTab, setActiveTab] = useState<TabId>('compass');
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isCameraSightingOpen, setIsCameraSightingOpen] = useState(false);
  const [isCalibrationOpen, setIsCalibrationOpen] = useState(false);

  // Track session recorder state
  const [trackSession, setTrackSession] = useState<TrackSession>({
    isRecording: false,
    startTime: null,
    points: [],
    totalAscent: 0,
    totalDescent: 0,
    totalDistance: 0,
    maxAltitude: 0,
    minAltitude: 0,
  });

  // Device sensors custom hook
  const {
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
  } = useDeviceSensors(preferences);

  // Persist preferences
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_PREFS_KEY, JSON.stringify(preferences));
    } catch {
      // ignore
    }
  }, [preferences]);

  // Persist waypoints
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_WAYPOINTS_KEY, JSON.stringify(waypoints));
    } catch {
      // ignore
    }
  }, [waypoints]);

  // Track recording interval
  const lastRecordedPointRef = useRef<TrackPoint | null>(null);

  useEffect(() => {
    if (!trackSession.isRecording || sensors.latitude === null || sensors.longitude === null) return;

    const interval = setInterval(() => {
      const currentLat = sensors.latitude!;
      const currentLon = sensors.longitude!;
      const currentAlt = sensors.barometricAltitude;
      const currentPoint: TrackPoint = {
        latitude: currentLat,
        longitude: currentLon,
        altitude: currentAlt,
        pressure: sensors.pressure,
        heading: preferences.northMode === 'true' ? sensors.trueHeading : sensors.heading,
        speed: sensors.gpsSpeed ?? 0,
        timestamp: Date.now(),
      };

      setTrackSession((prev) => {
        let deltaDist = 0;
        let deltaAsc = 0;
        let deltaDesc = 0;

        if (lastRecordedPointRef.current) {
          deltaDist = calculateDistanceMeters(
            lastRecordedPointRef.current.latitude,
            lastRecordedPointRef.current.longitude,
            currentLat,
            currentLon
          );
          const dAlt = currentAlt - lastRecordedPointRef.current.altitude;
          if (dAlt > 0) deltaAsc = dAlt;
          if (dAlt < 0) deltaDesc = Math.abs(dAlt);
        }

        lastRecordedPointRef.current = currentPoint;

        return {
          ...prev,
          points: [...prev.points, currentPoint],
          totalDistance: prev.totalDistance + deltaDist,
          totalAscent: prev.totalAscent + deltaAsc,
          totalDescent: prev.totalDescent + deltaDesc,
          maxAltitude: Math.max(prev.maxAltitude, currentAlt),
          minAltitude: prev.points.length === 0 ? currentAlt : Math.min(prev.minAltitude, currentAlt),
        };
      });
    }, 2500);

    return () => clearInterval(interval);
  }, [trackSession.isRecording, sensors.latitude, sensors.longitude, sensors.barometricAltitude, sensors.pressure, sensors.trueHeading, sensors.heading, sensors.gpsSpeed, preferences.northMode]);

  const toggleTrackRecording = () => {
    if (!trackSession.isRecording) {
      const now = Date.now();
      const currentLat = sensors.latitude ?? 37.7749;
      const currentLon = sensors.longitude ?? -122.4194;
      const startPoint: TrackPoint = {
        latitude: currentLat,
        longitude: currentLon,
        altitude: sensors.barometricAltitude,
        pressure: sensors.pressure,
        heading: preferences.northMode === 'true' ? sensors.trueHeading : sensors.heading,
        speed: sensors.gpsSpeed ?? 0,
        timestamp: now,
      };
      lastRecordedPointRef.current = startPoint;
      setTrackSession({
        isRecording: true,
        startTime: now,
        points: [startPoint],
        totalAscent: 0,
        totalDescent: 0,
        totalDistance: 0,
        maxAltitude: sensors.barometricAltitude,
        minAltitude: sensors.barometricAltitude,
      });
      triggerHaptic([30, 60, 30]);
    } else {
      setTrackSession((prev) => ({ ...prev, isRecording: false }));
      triggerHaptic(40);
    }
  };

  const updatePreferences = (partial: Partial<UserPreferences>) => {
    setPreferences((prev) => ({ ...prev, ...partial }));
  };

  const handleAddWaypoint = (wp: Omit<Waypoint, 'id' | 'timestamp'>) => {
    const newWp: Waypoint = {
      ...wp,
      id: `wp-${Date.now()}`,
      timestamp: Date.now(),
    };
    setWaypoints((prev) => [newWp, ...prev]);
    triggerHaptic([20, 40, 20]);
  };

  const handleDeleteWaypoint = (id: string) => {
    setWaypoints((prev) => prev.filter((w) => w.id !== id));
    if (preferences.targetWaypointId === id) {
      updatePreferences({ targetWaypointId: null });
    }
    triggerHaptic(20);
  };

  const handleSelectTarget = (id: string | null) => {
    updatePreferences({ targetWaypointId: id });
    triggerHaptic(25);
  };

  // Quick toggle night vision red mode
  const toggleNightTacticalMode = () => {
    const nextPalette = preferences.palette === 'tactical_red' ? 'cyan' : 'tactical_red';
    updatePreferences({ palette: nextPalette });
    triggerHaptic(20);
  };

  // Check if current GPS speed exceeds user-configured speed threshold
  const currentSpeedMs = sensors.gpsSpeed ?? 0;
  const currentSpeedInUserUnit = convertSpeed(currentSpeedMs, preferences.speedUnit).value;
  const isSpeedExceeded = Boolean(
    preferences.speedAlertEnabled && currentSpeedInUserUnit > preferences.speedAlertThreshold
  );
  const speedOvershoot = Math.max(0, currentSpeedInUserUnit - preferences.speedAlertThreshold);

  // Speed threshold alert triggers (Haptic pulse & Audio warning tone)
  const lastAlertTimeRef = useRef<number>(0);

  useEffect(() => {
    if (!isSpeedExceeded) return;

    const now = Date.now();
    // Pulse immediately on crossing threshold, then repeat pulse every 2.5s while overspeeding
    if (now - lastAlertTimeRef.current >= 2500) {
      lastAlertTimeRef.current = now;

      // 1. Haptic warning pulse alert
      if (preferences.speedAlertHaptic && preferences.hapticsEnabled) {
        triggerHaptic([180, 80, 180]); // Distinct warning double-pulse
      }

      // 2. Audio warning chime
      if (preferences.speedAlertAudio && preferences.audioFeedbackEnabled) {
        playSpeedAlertTone();
      }
    }
  }, [
    isSpeedExceeded,
    preferences.speedAlertHaptic,
    preferences.hapticsEnabled,
    preferences.speedAlertAudio,
    preferences.audioFeedbackEnabled,
    triggerHaptic,
  ]);

  return (
    <div className="relative min-h-screen text-slate-100 flex flex-col font-sans select-none overflow-x-hidden">
      {/* Background Liquid Glass Glows */}
      <LiquidGlassBackground palette={preferences.palette} />

      {/* Top Header */}
      <header className="sticky top-0 z-30 px-4 pb-3 pt-[calc(0.75rem+var(--safe-top))] bg-[#080b14]/70 backdrop-blur-xl border-b border-white/[0.08]">
        <div className="max-w-lg mx-auto flex items-center justify-between">
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-cyan-500 to-blue-600 flex items-center justify-center shadow-[0_0_14px_rgba(56,189,248,0.35)] border border-white/20 shrink-0">
              <Compass className="w-[18px] h-[18px] text-white" />
            </div>
            <div className="min-w-0">
              <h1 className="text-sm font-bold tracking-tight text-white flex items-center gap-1.5 leading-tight">
                <span>AeroGlass</span>
                <span className="text-[10px] px-1.5 py-px rounded-full bg-cyan-500/15 text-cyan-300 border border-cyan-500/30 font-mono font-medium tracking-wider">
                  PRO
                </span>
              </h1>
              <p className="text-[11px] text-slate-400 leading-tight truncate">{TAB_SUBTITLES[activeTab]}</p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {sensors.isSimulationMode && (
              <button
                onClick={toggleSimulationMode}
                title="Exit simulation"
                className="animate-fade-in flex items-center gap-1 px-2.5 py-1.5 rounded-full bg-cyan-500/15 border border-cyan-500/30 text-[11px] text-cyan-300 font-medium hover:bg-cyan-500/25"
              >
                <Sparkles className="w-3 h-3 animate-pulse" />
                <span>Sim</span>
              </button>
            )}

            <button
              onClick={toggleNightTacticalMode}
              title={preferences.palette === 'tactical_red' ? 'Exit Night Red' : 'Night Vision Red'}
              aria-label="Toggle night vision red mode"
              aria-pressed={preferences.palette === 'tactical_red'}
              className={`w-9 h-9 flex items-center justify-center rounded-xl border ${
                preferences.palette === 'tactical_red'
                  ? 'bg-rose-600/25 border-rose-500/70 text-rose-300 shadow-[0_0_12px_rgba(244,63,94,0.35)]'
                  : 'bg-white/[0.07] hover:bg-white/[0.14] border-white/[0.12] text-slate-300'
              }`}
            >
              <Eye className="w-4 h-4" />
            </button>

            <button
              onClick={() => {
                setIsSettingsOpen(true);
                triggerHaptic(15);
              }}
              aria-label="Settings and calibration"
              className="w-9 h-9 flex items-center justify-center rounded-xl bg-white/[0.07] hover:bg-white/[0.14] border border-white/[0.12] text-slate-300"
              title="Settings & Calibration"
            >
              <Settings className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 w-full max-w-lg mx-auto px-4 pt-4 pb-6 flex flex-col items-center">
        {/* Speed Threshold Warning Alert Banner */}
        {isSpeedExceeded && preferences.speedAlertVisual && (
          <div
            role="alert"
            className="animate-banner-in animate-alert-glow origin-top w-full mb-3 p-3 rounded-2xl bg-gradient-to-r from-rose-950/90 to-red-950/90 border border-rose-500/70 flex items-center justify-between gap-2"
          >
            <div className="flex items-center gap-2.5 min-w-0">
              <div className="p-2 rounded-xl bg-rose-500/25 border border-rose-400/60 shrink-0">
                <AlertTriangle className="w-5 h-5 text-amber-300" />
              </div>
              <div className="min-w-0">
                <div className="text-xs font-bold uppercase tracking-wider text-rose-100 flex items-center gap-1.5">
                  <span>Speed limit exceeded</span>
                  <span className="text-[10px] px-1.5 py-px rounded-full bg-rose-500/40 text-rose-50 border border-rose-400/50 font-mono normal-case tracking-normal">
                    +{speedOvershoot.toFixed(1)} {preferences.speedUnit}
                  </span>
                </div>
                <div className="text-[11px] text-slate-300 font-mono mt-0.5 truncate">
                  <span className="font-bold text-white">
                    {currentSpeedInUserUnit.toFixed(1)} {preferences.speedUnit}
                  </span>
                  {' / limit '}
                  <span className="font-bold text-amber-300">
                    {preferences.speedAlertThreshold.toFixed(1)}
                  </span>
                </div>
              </div>
            </div>

            <button
              onClick={() => setIsSettingsOpen(true)}
              className="px-3 py-1.5 rounded-xl bg-white/15 hover:bg-white/25 border border-white/20 text-xs font-semibold text-white whitespace-nowrap"
            >
              Adjust
            </button>
          </div>
        )}
        <div key={activeTab} className="tab-enter w-full">
        {activeTab === 'compass' && (
          <CompassView
            sensors={sensors}
            preferences={preferences}
            waypoints={waypoints}
            onSetSimulationHeading={setSimulationHeading}
            onSetSimulationPitchRoll={setSimulationPitchRoll}
            onToggleSimulationMode={toggleSimulationMode}
            onUpdatePreferences={updatePreferences}
            onRequestPermission={requestOrientationPermission}
            onOpenCameraSighting={() => setIsCameraSightingOpen(true)}
            onOpenCalibration={() => setIsCalibrationOpen(true)}
            onSetSimulationSpeed={setSimulationSpeed}
          />
        )}

        {activeTab === 'altimeter' && (
          <AltimeterBarometerView
            sensors={sensors}
            preferences={preferences}
            onSetQnh={setQnh}
            onSetManualPressure={setManualPressure}
            onTareAltitude={tareAltitude}
            onResetTare={resetTare}
            onCalibrateToGps={calibrateToGpsAltitude}
            onUpdatePreferences={updatePreferences}
            onSimulateTrendScenario={setSimulatedTrendScenario}
          />
        )}

        {activeTab === 'sensors' && (
          <SensorMatrixView
            sensors={sensors}
            preferences={preferences}
            trackSession={trackSession}
            onOpenCalibration={() => setIsCalibrationOpen(true)}
            onAddWaypoint={handleAddWaypoint}
          />
        )}

        {activeTab === 'waypoints' && (
          <WaypointsNavView
            sensors={sensors}
            preferences={preferences}
            waypoints={waypoints}
            onAddWaypoint={handleAddWaypoint}
            onDeleteWaypoint={handleDeleteWaypoint}
            onSelectTarget={handleSelectTarget}
            onNavigateToCompass={() => setActiveTab('compass')}
            trackSession={trackSession}
            onToggleTrackRecording={toggleTrackRecording}
            onSetSimulationSpeed={setSimulationSpeed}
            onUpdatePreferences={updatePreferences}
          />
        )}
        </div>
      </main>

      {/* Floating Liquid Glass Bottom Navigation Bar */}
      <nav
        aria-label="Primary"
        className="fixed inset-x-4 max-w-sm mx-auto z-40 bottom-[calc(1rem+var(--safe-bottom))]"
      >
        <div className="relative rounded-full p-1.5 bg-gradient-to-b from-white/[0.14] to-white/[0.04] backdrop-blur-2xl border border-white/20 shadow-[0_12px_36px_rgba(0,0,0,0.6),inset_0_1px_2px_rgba(255,255,255,0.25)] flex items-center gap-1">
          {NAV_ITEMS.map(({ id, label, icon: Icon }) => {
            const isActive = activeTab === id;
            return (
              <button
                key={id}
                onClick={() => {
                  if (!isActive) triggerHaptic(15);
                  setActiveTab(id);
                }}
                aria-label={label}
                aria-current={isActive ? 'page' : undefined}
                className={`relative flex items-center justify-center rounded-full min-h-[48px] overflow-hidden text-xs font-semibold transition-[flex-grow,color,background-color] duration-500 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                  isActive
                    ? 'flex-[2.4] bg-gradient-to-r from-cyan-400 to-teal-300 text-slate-950 shadow-[0_2px_14px_rgba(56,189,248,0.4)]'
                    : 'flex-1 text-slate-300 hover:text-white hover:bg-white/[0.08]'
                }`}
              >
                <span className="flex items-center gap-1.5 whitespace-nowrap">
                  <Icon className="w-[18px] h-[18px] shrink-0" />
                  <span
                    className={`overflow-hidden transition-[max-width,opacity] duration-500 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                      isActive ? 'max-w-[5rem] opacity-100' : 'max-w-0 opacity-0'
                    }`}
                  >
                    {label}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      </nav>

      {/* AR Camera Sighting Overlay Modal */}
      {isCameraSightingOpen && (
        <CameraSightingView
          sensors={sensors}
          preferences={preferences}
          waypoints={waypoints}
          onClose={() => setIsCameraSightingOpen(false)}
        />
      )}

      {/* Magnetometer Figure-8 Calibration Modal */}
      <CalibrationModal
        isOpen={isCalibrationOpen}
        onClose={() => setIsCalibrationOpen(false)}
        sensors={sensors}
      />

      {/* Settings Modal */}
      <SettingsModal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
        preferences={preferences}
        sensors={sensors}
        onUpdatePreferences={updatePreferences}
        onToggleSimulation={toggleSimulationMode}
        onOpenCalibration={() => setIsCalibrationOpen(true)}
      />
    </div>
  );
};
