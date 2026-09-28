import React, { useState, useMemo } from 'react';
import {
  ResponsiveContainer,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
  ReferenceLine,
} from 'recharts';
import { TrackSession, TrackPoint, UserPreferences, SensorState, SpeedUnit } from '../types/sensors';
import { convertSpeed, convertSpeedBetweenUnits, calculateDistanceMeters } from '../utils/calculations';
import { GlassCard } from './GlassCard';
import { CardHeader } from './Layout';
import {
  Gauge,
  Zap,
  TrendingUp,
  Clock,
  Navigation2,
  AlertTriangle,
  Activity,
  Sliders,
  Sparkles,
  Footprints,
  ShieldAlert,
} from 'lucide-react';

interface Props {
  trackSession: TrackSession;
  preferences: UserPreferences;
  currentSpeedMs: number;
  sensors?: SensorState;
  onSetSimulationSpeed?: (speedMs: number) => void;
  onToggleTrackRecording?: () => void;
  onUpdatePreferences?: (prefs: Partial<UserPreferences>) => void;
}

// High-fidelity sample speed telemetry for immediate visualization when user hasn't recorded yet
const SAMPLE_SPEED_TRACK: TrackPoint[] = [
  { latitude: 37.7710, longitude: -122.4280, altitude: 48, pressure: 1007.5, heading: 45, speed: 0.8, timestamp: Date.now() - 3600000 },
  { latitude: 37.7722, longitude: -122.4255, altitude: 72, pressure: 1004.6, heading: 48, speed: 1.4, timestamp: Date.now() - 3300000 },
  { latitude: 37.7738, longitude: -122.4230, altitude: 115, pressure: 999.5, heading: 42, speed: 2.6, timestamp: Date.now() - 3000000 },
  { latitude: 37.7755, longitude: -122.4202, altitude: 168, pressure: 993.2, heading: 38, speed: 4.2, timestamp: Date.now() - 2700000 },
  { latitude: 37.7770, longitude: -122.4175, altitude: 224, pressure: 986.6, heading: 32, speed: 6.8, timestamp: Date.now() - 2400000 },
  { latitude: 37.7788, longitude: -122.4148, altitude: 286, pressure: 979.3, heading: 28, speed: 9.5, timestamp: Date.now() - 2100000 },
  { latitude: 37.7802, longitude: -122.4125, altitude: 342, pressure: 972.7, heading: 25, speed: 11.2, timestamp: Date.now() - 1800000 }, // Peak velocity burst
  { latitude: 37.7815, longitude: -122.4105, altitude: 328, pressure: 974.4, heading: 60, speed: 8.4, timestamp: Date.now() - 1500000 },
  { latitude: 37.7828, longitude: -122.4075, altitude: 275, pressure: 980.6, heading: 75, speed: 7.1, timestamp: Date.now() - 1200000 },
  { latitude: 37.7842, longitude: -122.4045, altitude: 210, pressure: 988.2, heading: 82, speed: 5.6, timestamp: Date.now() - 900000 },
  { latitude: 37.7856, longitude: -122.4015, altitude: 154, pressure: 994.9, heading: 88, speed: 3.8, timestamp: Date.now() - 600000 },
  { latitude: 37.7870, longitude: -122.3985, altitude: 98, pressure: 1001.6, heading: 92, speed: 2.1, timestamp: Date.now() - 300000 },
  { latitude: 37.7885, longitude: -122.3955, altitude: 54, pressure: 1006.8, heading: 96, speed: 1.1, timestamp: Date.now() },
];

export const TrackSpeedTrendChart: React.FC<Props> = ({
  trackSession,
  preferences,
  currentSpeedMs,
  sensors,
  onSetSimulationSpeed,
  onToggleTrackRecording,
  onUpdatePreferences,
}) => {
  const [viewMode, setViewMode] = useState<'duration' | 'distance'>('duration');
  const [forceSampleData, setForceSampleData] = useState<boolean>(false);
  const [showSimDrawer, setShowSimDrawer] = useState<boolean>(false);

  // Active speed unit
  const speedUnit = preferences.speedUnit;

  // Determine whether to use live session data or sample preview
  const hasLiveSession = trackSession.isRecording || trackSession.points.length > 0;
  const isShowingSample = !hasLiveSession || forceSampleData;

  // Real-time live points array:
  // If actively recording, append the current real-time telemetry point so the profile responds instantly
  const rawPoints = useMemo(() => {
    if (isShowingSample) {
      return SAMPLE_SPEED_TRACK;
    }

    const points = [...trackSession.points];

    // If recording and sensors are active, append current real-time point
    if (trackSession.isRecording) {
      const now = Date.now();
      const last = points[points.length - 1];
      if (!last || now - last.timestamp >= 400) {
        points.push({
          latitude: sensors?.latitude ?? 37.7749,
          longitude: sensors?.longitude ?? -122.4194,
          altitude: sensors?.barometricAltitude ?? 0,
          pressure: sensors?.pressure ?? 1013.25,
          heading: sensors ? (preferences.northMode === 'true' ? sensors.trueHeading : sensors.heading) : 0,
          speed: currentSpeedMs,
          timestamp: now,
        });
      }
    }

    return points;
  }, [
    isShowingSample,
    trackSession.isRecording,
    trackSession.points,
    sensors,
    currentSpeedMs,
    preferences.northMode,
  ]);

  // Session start time
  const sessionStartTime = useMemo(() => {
    if (isShowingSample) {
      return SAMPLE_SPEED_TRACK[0].timestamp;
    }
    return trackSession.startTime || (rawPoints[0] ? rawPoints[0].timestamp : Date.now());
  }, [isShowingSample, trackSession.startTime, rawPoints]);

  // Speed threshold in active unit
  const thresholdSpeed = preferences.speedAlertThreshold;
  const isAlertEnabled = preferences.speedAlertEnabled;

  // Process data for Recharts
  const chartData = useMemo(() => {
    let cumulativeDistanceMeters = 0;

    return rawPoints.map((pt, idx) => {
      if (idx > 0) {
        const prev = rawPoints[idx - 1];
        const dist = calculateDistanceMeters(prev.latitude, prev.longitude, pt.latitude, pt.longitude);
        cumulativeDistanceMeters += dist;
      }

      // Convert distance to km or miles
      const distanceDisplay = preferences.altitudeUnit === 'ft'
        ? Number((cumulativeDistanceMeters / 1609.34).toFixed(2)) // miles
        : Number((cumulativeDistanceMeters / 1000).toFixed(2)); // km

      // Convert speed to active speedUnit
      const speedConverted = convertSpeed(pt.speed, speedUnit);
      const speedVal = speedConverted.value;

      // Duration elapsed in seconds
      const elapsedSeconds = Math.max(0, Math.floor((pt.timestamp - sessionStartTime) / 1000));
      const mins = Math.floor(elapsedSeconds / 60);
      const secs = elapsedSeconds % 60;
      const durationLabel = `${mins}:${secs.toString().padStart(2, '0')}`;

      // Calculate pace (min/km or min/mi)
      let paceFormatted = '—';
      if (pt.speed > 0.3) {
        const metersPerUnit = speedUnit === 'mph' ? 1609.34 : 1000;
        const paceSec = metersPerUnit / pt.speed;
        if (paceSec < 3600) {
          const pMins = Math.floor(paceSec / 60);
          const pSecs = Math.floor(paceSec % 60);
          paceFormatted = `${pMins}:${pSecs.toString().padStart(2, '0')}`;
        }
      }

      const isOverLimit = isAlertEnabled && speedVal > thresholdSpeed;

      return {
        index: idx + 1,
        timestamp: pt.timestamp,
        elapsedSeconds,
        durationLabel,
        distance: distanceDisplay,
        distanceUnit: preferences.altitudeUnit === 'ft' ? 'mi' : 'km',
        speed: speedVal,
        speedMs: pt.speed,
        speedUnit: speedConverted.label,
        pace: paceFormatted,
        paceUnit: speedUnit === 'mph' ? 'min/mi' : 'min/km',
        altitude: Math.round(pt.altitude),
        altitudeUnit: preferences.altitudeUnit === 'ft' ? 'ft' : 'm',
        isOverLimit,
      };
    });
  }, [rawPoints, speedUnit, sessionStartTime, preferences.altitudeUnit, isAlertEnabled, thresholdSpeed]);

  // Statistical calculations
  const speeds = chartData.map((d) => d.speed);
  const currentSpeedConverted = convertSpeed(currentSpeedMs, speedUnit).value;
  const maxSpeed = speeds.length > 0 ? Math.max(...speeds) : 0;
  const minSpeed = speeds.length > 0 ? Math.min(...speeds) : 0;
  const avgSpeed = speeds.length > 0
    ? Number((speeds.reduce((a, b) => a + b, 0) / speeds.length).toFixed(1))
    : 0;

  // Overspeed statistics
  const overspeedPointsCount = isAlertEnabled ? chartData.filter((d) => d.speed > thresholdSpeed).length : 0;
  const isCurrentlyOverLimit = isAlertEnabled && currentSpeedConverted > thresholdSpeed;

  // Pace for current speed
  const currentPaceFormatted = useMemo(() => {
    if (currentSpeedMs < 0.3) return '—';
    const metersPerUnit = speedUnit === 'mph' ? 1609.34 : 1000;
    const paceSec = metersPerUnit / currentSpeedMs;
    if (paceSec > 3600) return '—';
    const pMins = Math.floor(paceSec / 60);
    const pSecs = Math.floor(paceSec % 60);
    return `${pMins}:${pSecs.toString().padStart(2, '0')}`;
  }, [currentSpeedMs, speedUnit]);

  // Theme palette styling
  const theme = useMemo(() => {
    switch (preferences.palette) {
      case 'emerald':
      case 'phosphor_green':
        return {
          stroke: '#34d399',
          gradientStart: '#34d399',
          gradientEnd: '#059669',
          accent: 'text-emerald-400',
          badgeBg: 'bg-emerald-500/20 border-emerald-500/30 text-emerald-300',
        };
      case 'amber':
        return {
          stroke: '#fbbf24',
          gradientStart: '#fbbf24',
          gradientEnd: '#d97706',
          accent: 'text-amber-400',
          badgeBg: 'bg-amber-500/20 border-amber-500/30 text-amber-300',
        };
      case 'violet':
        return {
          stroke: '#c084fc',
          gradientStart: '#c084fc',
          gradientEnd: '#7c3aed',
          accent: 'text-purple-400',
          badgeBg: 'bg-purple-500/20 border-purple-500/30 text-purple-300',
        };
      case 'tactical_red':
        return {
          stroke: '#f87171',
          gradientStart: '#ef4444',
          gradientEnd: '#991b1b',
          accent: 'text-rose-400',
          badgeBg: 'bg-rose-500/20 border-rose-500/30 text-rose-300',
        };
      case 'cyan':
      default:
        return {
          stroke: '#38bdf8',
          gradientStart: '#38bdf8',
          gradientEnd: '#0284c7',
          accent: 'text-cyan-400',
          badgeBg: 'bg-cyan-500/20 border-cyan-500/30 text-cyan-300',
        };
    }
  }, [preferences.palette]);

  // Speed unit toggle handler
  const handleUnitChange = (unit: SpeedUnit) => {
    if (onUpdatePreferences) {
      const convertedThreshold = convertSpeedBetweenUnits(
        preferences.speedAlertThreshold,
        preferences.speedUnit,
        unit
      );
      onUpdatePreferences({
        speedUnit: unit,
        speedAlertThreshold: Math.round(convertedThreshold),
      });
    }
  };

  return (
    <GlassCard className="w-full !p-4">
      {/* Header and Toggle Controls */}
      <CardHeader
        icon={<Gauge className="w-4 h-4 text-cyan-400 shrink-0" />}
        title={
          <span className="flex flex-wrap items-center gap-1.5">
              <span>GPS Speed Trends</span>
              {trackSession.isRecording && (
                <span className="flex items-center space-x-1 text-[10px] px-1.5 py-px rounded-full bg-rose-500/25 text-rose-300 border border-rose-500/40 font-mono animate-pulse">
                  <span className="w-1.5 h-1.5 rounded-full bg-rose-400" />
                  <span>LIVE</span>
                </span>
              )}
              {isShowingSample && !trackSession.isRecording && (
                <span className="text-[10px] px-1.5 py-px rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 font-mono">
                  DEMO TRACK
                </span>
              )}
          </span>
        }
        subtitle="Velocity telemetry profile over recording timeline"
      >
          <div className="flex bg-white/10 rounded-full p-0.5 border border-white/15 text-[10px] font-mono">
            <button
              onClick={() => setViewMode('duration')}
              className={`px-2 py-0.5 rounded-full transition-all ${
                viewMode === 'duration'
                  ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                  : 'text-slate-300 hover:text-white'
              }`}
            >
              Duration
            </button>
            <button
              onClick={() => setViewMode('distance')}
              className={`px-2 py-0.5 rounded-full transition-all ${
                viewMode === 'distance'
                  ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                  : 'text-slate-300 hover:text-white'
              }`}
            >
              Distance
            </button>
          </div>

          <button
            onClick={() => setShowSimDrawer(!showSimDrawer)}
            title="Adjust simulated speed or preview"
            className={`p-1.5 rounded-full border transition-all ${
              showSimDrawer
                ? 'bg-cyan-500/25 border-cyan-400 text-cyan-200 shadow-[0_0_10px_rgba(56,189,248,0.3)]'
                : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
            }`}
          >
            <Sliders className="w-3 h-3" />
          </button>
        
      </CardHeader>

      {/* Speed Telemetry Metric Cards */}
      <div className="grid grid-cols-4 gap-2 text-center text-xs font-mono mb-3">
        {/* Current Speed */}
        <div
          className={`p-2 rounded-xl border transition-all ${
            isCurrentlyOverLimit
              ? 'bg-rose-950/40 border-rose-500/50 text-rose-200 animate-pulse'
              : 'bg-white/[0.04] border-white/[0.08]'
          }`}
        >
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Current</span>
          <span className={`text-sm font-black ${isCurrentlyOverLimit ? 'text-rose-300' : 'text-white'}`}>
            {currentSpeedConverted.toFixed(1)}
          </span>
          <span className="text-[10px] text-cyan-400 ml-0.5">{speedUnit}</span>
        </div>

        {/* Average Speed */}
        <div className="p-2 rounded-xl bg-white/[0.04] border border-white/[0.08]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Average</span>
          <span className="text-sm font-bold text-cyan-300">{avgSpeed.toFixed(1)}</span>
          <span className="text-[10px] text-slate-400 ml-0.5">{speedUnit}</span>
        </div>

        {/* Peak Speed */}
        <div className="p-2 rounded-xl bg-white/[0.04] border border-white/[0.08]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Peak</span>
          <span className="text-sm font-bold text-emerald-300">{maxSpeed.toFixed(1)}</span>
          <span className="text-[10px] text-slate-400 ml-0.5">{speedUnit}</span>
        </div>

        {/* Current Pace */}
        <div className="p-2 rounded-xl bg-white/[0.04] border border-white/[0.08]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Pace</span>
          <span className="text-xs font-bold text-amber-300 truncate block mt-0.5">{currentPaceFormatted}</span>
          <span className="text-[10px] text-slate-400 block font-sans">
            {speedUnit === 'mph' ? '/mi' : '/km'}
          </span>
        </div>
      </div>

      {/* Speed Alert Warning Banner if Limit Exceeded */}
      {isAlertEnabled && isCurrentlyOverLimit && (
        <div className="mb-3 p-2 rounded-xl bg-rose-500/20 border border-rose-500/40 text-rose-200 text-xs flex items-center justify-between animate-pulse">
          <div className="flex items-center space-x-1.5">
            <AlertTriangle className="w-3.5 h-3.5 text-yellow-300 shrink-0" />
            <span className="font-semibold">
              Speed Limit ({thresholdSpeed} {speedUnit}) Exceeded by +{(currentSpeedConverted - thresholdSpeed).toFixed(1)} {speedUnit}!
            </span>
          </div>
          <span className="text-[10px] px-1.5 py-0.5 rounded bg-rose-500/40 text-white font-mono font-bold">
            OVERSPEED
          </span>
        </div>
      )}

      {/* Recharts Area Chart Container */}
      <div className="w-full h-52 select-none relative">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart
            data={chartData}
            margin={{ top: 10, right: 10, left: -20, bottom: 0 }}
          >
            <defs>
              <linearGradient id="speedAreaGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={theme.gradientStart} stopOpacity={0.65} />
                <stop offset="60%" stopColor={theme.gradientEnd} stopOpacity={0.2} />
                <stop offset="95%" stopColor="#000000" stopOpacity={0.0} />
              </linearGradient>
            </defs>

            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.08)" vertical={false} />

            <XAxis
              dataKey={viewMode === 'duration' ? 'durationLabel' : 'distance'}
              stroke="rgba(255,255,255,0.4)"
              tick={{ fontSize: 9, fill: 'rgba(255,255,255,0.5)', fontFamily: 'monospace' }}
              tickLine={{ stroke: 'rgba(255,255,255,0.15)' }}
              minTickGap={25}
            />

            <YAxis
              stroke="rgba(255,255,255,0.4)"
              tick={{ fontSize: 9, fill: 'rgba(255,255,255,0.5)', fontFamily: 'monospace' }}
              tickLine={{ stroke: 'rgba(255,255,255,0.15)' }}
              domain={[0, (dataMax: number) => Math.max(Math.ceil((dataMax + 2) / 5) * 5, thresholdSpeed + 5)]}
              unit={` ${speedUnit}`}
            />

            <Tooltip
              content={({ active, payload }) => {
                if (active && payload && payload.length > 0) {
                  const data = payload[0].payload;
                  return (
                    <div className="p-2.5 rounded-xl bg-slate-900/90 backdrop-blur-md border border-white/20 shadow-xl text-xs font-mono space-y-1 z-50">
                      <div className="flex items-center justify-between space-x-3 text-slate-400 text-[10px] pb-1 border-b border-white/10">
                        <span>Point #{data.index}</span>
                        <span>{data.durationLabel} ({data.distance} {data.distanceUnit})</span>
                      </div>
                      <div className="flex items-center justify-between space-x-3 text-white font-bold">
                        <span className="flex items-center space-x-1">
                          <Zap className="w-3 h-3 text-cyan-400" />
                          <span>Velocity:</span>
                        </span>
                        <span className={data.isOverLimit ? 'text-rose-400 font-black' : 'text-cyan-300'}>
                          {data.speed} {data.speedUnit}
                        </span>
                      </div>
                      <div className="flex items-center justify-between space-x-3 text-slate-300 text-[11px]">
                        <span>Pace:</span>
                        <span className="text-amber-300">{data.pace} {data.paceUnit}</span>
                      </div>
                      <div className="flex items-center justify-between space-x-3 text-slate-300 text-[11px]">
                        <span>Altitude:</span>
                        <span className="text-emerald-300">{data.altitude} {data.altitudeUnit}</span>
                      </div>
                      {isAlertEnabled && (
                        <div className="flex items-center justify-between space-x-3 text-[10px] pt-1 border-t border-white/10">
                          <span>Limit Status:</span>
                          <span className={data.isOverLimit ? 'text-rose-400 font-bold' : 'text-emerald-400'}>
                            {data.isOverLimit ? `OVERSPEED (+${(data.speed - thresholdSpeed).toFixed(1)})` : 'UNDER LIMIT'}
                          </span>
                        </div>
                      )}
                    </div>
                  );
                }
                return null;
              }}
            />

            {/* Average Speed Reference Line */}
            {avgSpeed > 0 && (
              <ReferenceLine
                y={avgSpeed}
                stroke="#38bdf8"
                strokeDasharray="4 4"
                strokeWidth={1.5}
                label={{
                  value: `AVG ${avgSpeed}`,
                  fill: '#38bdf8',
                  fontSize: 8,
                  position: 'insideTopRight',
                  fontFamily: 'monospace',
                }}
              />
            )}

            {/* Speed Limit Reference Line (if Alert Enabled) */}
            {isAlertEnabled && thresholdSpeed > 0 && (
              <ReferenceLine
                y={thresholdSpeed}
                stroke="#f43f5e"
                strokeDasharray="5 3"
                strokeWidth={1.8}
                label={{
                  value: `LIMIT ${thresholdSpeed} ${speedUnit}`,
                  fill: '#f43f5e',
                  fontSize: 8,
                  position: 'insideBottomRight',
                  fontFamily: 'monospace',
                  fontWeight: 'bold',
                }}
              />
            )}

            {/* Primary Speed Area Plot */}
            <Area
              type="monotone"
              dataKey="speed"
              stroke={theme.stroke}
              strokeWidth={2.2}
              fill="url(#speedAreaGrad)"
              isAnimationActive={true}
              animationDuration={600}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      {/* Speed Unit Pills & Demo Controls Footer */}
      <div className="flex items-center justify-between pt-2 border-t border-white/[0.08] text-xs">
        <div className="flex items-center space-x-1.5">
          <span className="text-[10px] text-slate-400 font-mono">Unit:</span>
          <div className="flex bg-white/10 rounded-lg p-0.5 border border-white/10 text-[10px] font-mono">
            {(['km/h', 'mph', 'kt', 'm/s'] as const).map((unit) => (
              <button
                key={unit}
                onClick={() => handleUnitChange(unit)}
                className={`px-1.5 py-0.5 rounded transition-all ${
                  speedUnit === unit
                    ? 'bg-cyan-500 text-slate-950 font-bold'
                    : 'text-slate-300 hover:text-white'
                }`}
              >
                {unit}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center space-x-1.5">
          {hasLiveSession && (
            <button
              onClick={() => setForceSampleData(!forceSampleData)}
              className="text-[10px] text-slate-400 hover:text-cyan-300 transition-colors flex items-center space-x-1"
            >
              <Sparkles className="w-3 h-3 text-cyan-400" />
              <span>{forceSampleData ? 'Show Live Recording' : 'Preview Demo'}</span>
            </button>
          )}

          {!trackSession.isRecording && onToggleTrackRecording && (
            <button
              onClick={onToggleTrackRecording}
              className="px-2.5 py-1 rounded-lg bg-cyan-500/20 hover:bg-cyan-500/30 border border-cyan-400/40 text-cyan-200 text-[10px] font-bold transition-all active:scale-95"
            >
              Start Recording
            </button>
          )}
        </div>
      </div>

      {/* Simulator Drawer for testing speed trend reactivity */}
      {showSimDrawer && (
        <div className="mt-3 pt-3 border-t border-cyan-500/20 bg-cyan-950/20 p-2.5 rounded-2xl">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-bold text-cyan-300 uppercase tracking-wider flex items-center space-x-1.5">
              <Sliders className="w-3.5 h-3.5 text-cyan-400" />
              <span>Speed Simulator Controls</span>
            </span>
            <span className="text-[11px] font-mono text-cyan-300 font-bold">
              {currentSpeedConverted.toFixed(1)} {speedUnit}
            </span>
          </div>

          <div className="space-y-2">
            <input
              type="range"
              min="0"
              max={speedUnit === 'mph' ? 100 : speedUnit === 'm/s' ? 40 : 140}
              step="0.5"
              value={currentSpeedConverted}
              onChange={(e) => {
                const val = Number(e.target.value);
                const ms = speedUnit === 'mph' ? val / 2.236936 : speedUnit === 'kt' ? val / 1.943844 : speedUnit === 'm/s' ? val : val / 3.6;
                onSetSimulationSpeed?.(ms);
              }}
              className="w-full h-1.5 bg-white/20 rounded-lg appearance-none cursor-pointer accent-cyan-400"
            />

            <div className="flex items-center justify-between text-[10px]">
              <span className="text-slate-400">Presets:</span>
              <div className="flex space-x-1">
                {[
                  { label: 'Stop', kmh: 0 },
                  { label: 'Walk (5)', kmh: 5 },
                  { label: 'Jog (12)', kmh: 12 },
                  { label: 'Cycle (25)', kmh: 25 },
                  { label: 'Car (70)', kmh: 70 },
                  { label: 'Sprint (110)', kmh: 110 },
                ].map((p) => (
                  <button
                    key={p.label}
                    onClick={() => onSetSimulationSpeed?.(p.kmh / 3.6)}
                    className="px-1.5 py-0.5 rounded bg-white/10 hover:bg-white/20 border border-white/15 text-slate-300 font-mono transition-all"
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
    </GlassCard>
  );
};
