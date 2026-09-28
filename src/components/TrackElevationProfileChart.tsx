import React, { useState, useMemo, useEffect } from 'react';
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
import { TrackSession, TrackPoint, UserPreferences, SensorState, Waypoint } from '../types/sensors';
import { convertAltitude, calculateDistanceMeters } from '../utils/calculations';
import { GlassCard } from './GlassCard';
import { CardHeader } from './Layout';
import {
  Mountain,
  TrendingUp,
  TrendingDown,
  Navigation2,
  Clock,
  Gauge,
  Sparkles,
  Layers,
  Activity,
  Footprints,
  MapPin,
  Check,
  Play,
  Square,
  ArrowUp,
  ArrowDown,
  Sliders,
} from 'lucide-react';

interface Props {
  trackSession: TrackSession;
  preferences: UserPreferences;
  currentAltitude: number;
  sensors?: SensorState;
  onAddWaypoint?: (wp: Omit<Waypoint, 'id' | 'timestamp'>) => void;
  onToggleTrackRecording?: () => void;
}

// High-fidelity sample mountain traverse track for immediate visualization when user hasn't recorded yet
const SAMPLE_TRAVERSE_TRACK: TrackPoint[] = [
  { latitude: 37.7710, longitude: -122.4280, altitude: 48, pressure: 1007.5, heading: 45, speed: 1.2, timestamp: Date.now() - 3600000 },
  { latitude: 37.7722, longitude: -122.4255, altitude: 72, pressure: 1004.6, heading: 48, speed: 1.3, timestamp: Date.now() - 3300000 },
  { latitude: 37.7738, longitude: -122.4230, altitude: 115, pressure: 999.5, heading: 42, speed: 1.1, timestamp: Date.now() - 3000000 },
  { latitude: 37.7755, longitude: -122.4202, altitude: 168, pressure: 993.2, heading: 38, speed: 1.0, timestamp: Date.now() - 2700000 },
  { latitude: 37.7770, longitude: -122.4175, altitude: 224, pressure: 986.6, heading: 32, speed: 0.9, timestamp: Date.now() - 2400000 },
  { latitude: 37.7788, longitude: -122.4148, altitude: 286, pressure: 979.3, heading: 28, speed: 0.8, timestamp: Date.now() - 2100000 },
  { latitude: 37.7802, longitude: -122.4125, altitude: 342, pressure: 972.7, heading: 25, speed: 0.7, timestamp: Date.now() - 1800000 }, // Ridge Summit
  { latitude: 37.7815, longitude: -122.4105, altitude: 328, pressure: 974.4, heading: 60, speed: 1.0, timestamp: Date.now() - 1500000 },
  { latitude: 37.7828, longitude: -122.4075, altitude: 275, pressure: 980.6, heading: 75, speed: 1.3, timestamp: Date.now() - 1200000 },
  { latitude: 37.7842, longitude: -122.4045, altitude: 210, pressure: 988.2, heading: 82, speed: 1.4, timestamp: Date.now() - 900000 },
  { latitude: 37.7856, longitude: -122.4015, altitude: 154, pressure: 994.9, heading: 88, speed: 1.5, timestamp: Date.now() - 600000 },
  { latitude: 37.7870, longitude: -122.3985, altitude: 98, pressure: 1001.6, heading: 92, speed: 1.6, timestamp: Date.now() - 300000 },
  { latitude: 37.7885, longitude: -122.3955, altitude: 54, pressure: 1006.8, heading: 96, speed: 1.4, timestamp: Date.now() },
];

export const TrackElevationProfileChart: React.FC<Props> = ({
  trackSession,
  preferences,
  currentAltitude,
  sensors,
  onAddWaypoint,
  onToggleTrackRecording,
}) => {
  // Default to 'duration' mode as requested to visualize altitude changes over recording duration
  const [viewMode, setViewMode] = useState<'duration' | 'distance'>('duration');
  const [forceSampleData, setForceSampleData] = useState<boolean>(false);
  const [quickMarkSaved, setQuickMarkSaved] = useState<boolean>(false);

  // Determine whether to use live session data or fallback preview
  const hasLiveSession = trackSession.isRecording || trackSession.points.length > 0;
  const isShowingSample = !hasLiveSession || forceSampleData;

  // Real-time live points array:
  // If actively recording, append the current real-time telemetry point so the profile responds instantly
  const rawPoints = useMemo(() => {
    if (isShowingSample) {
      return SAMPLE_TRAVERSE_TRACK;
    }

    const points = [...trackSession.points];

    // If recording and we have current sensor altitude, append current real-time point
    if (trackSession.isRecording && sensors) {
      const now = Date.now();
      const last = points[points.length - 1];
      // Append if no points yet or if at least 400ms have passed since last stored point
      if (!last || now - last.timestamp >= 400) {
        points.push({
          latitude: sensors.latitude ?? 37.7749,
          longitude: sensors.longitude ?? -122.4194,
          altitude: currentAltitude,
          pressure: sensors.pressure,
          heading: preferences.northMode === 'true' ? sensors.trueHeading : sensors.heading,
          speed: sensors.gpsSpeed ?? 0,
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
    currentAltitude,
    preferences.northMode,
  ]);

  // Session start time
  const sessionStartTime = useMemo(() => {
    if (isShowingSample) {
      return SAMPLE_TRAVERSE_TRACK[0].timestamp;
    }
    return trackSession.startTime || (rawPoints[0] ? rawPoints[0].timestamp : Date.now());
  }, [isShowingSample, trackSession.startTime, rawPoints]);

  // Process data for Recharts
  const chartData = useMemo(() => {
    let cumulativeDistanceMeters = 0;
    const startAltitude = rawPoints[0]?.altitude ?? currentAltitude;
    const startAltConverted = convertAltitude(startAltitude, preferences.altitudeUnit);

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

      // Convert altitude to preference unit (m or ft)
      const altConverted = convertAltitude(pt.altitude, preferences.altitudeUnit);
      const altValue = Math.round(altConverted.value);
      const deltaFromStart = altValue - Math.round(startAltConverted.value);

      // Duration elapsed in seconds
      const elapsedSeconds = Math.max(0, Math.floor((pt.timestamp - sessionStartTime) / 1000));
      const mins = Math.floor(elapsedSeconds / 60);
      const secs = elapsedSeconds % 60;
      const durationLabel = `${mins}:${secs.toString().padStart(2, '0')}`;

      return {
        index: idx + 1,
        timestamp: pt.timestamp,
        elapsedSeconds,
        durationLabel,
        distance: distanceDisplay,
        distanceUnit: preferences.altitudeUnit === 'ft' ? 'mi' : 'km',
        altitude: altValue,
        deltaFromStart,
        altitudeUnit: altConverted.label,
        pressure: Number(pt.pressure.toFixed(1)),
        speed: Number((pt.speed * (preferences.altitudeUnit === 'ft' ? 2.237 : 3.6)).toFixed(1)),
        speedUnit: preferences.altitudeUnit === 'ft' ? 'mph' : 'km/h',
        rawAltitudeMeters: pt.altitude,
      };
    });
  }, [rawPoints, preferences.altitudeUnit, sessionStartTime, currentAltitude]);

  // Calculate summary metrics
  const altitudes = chartData.map((d) => d.altitude);
  const minAltitude = altitudes.length > 0 ? Math.min(...altitudes) : 0;
  const maxAltitude = altitudes.length > 0 ? Math.max(...altitudes) : 0;
  const totalElevationSpan = maxAltitude - minAltitude;

  const startAltitudeValue = chartData.length > 0 ? chartData[0].altitude : 0;
  const currentAltConverted = convertAltitude(currentAltitude, preferences.altitudeUnit);
  const currentAltValue = Math.round(currentAltConverted.value);
  const netAltitudeChange = currentAltValue - startAltitudeValue;

  // Active theme matching Material You Liquid Glass palette
  const theme = useMemo(() => {
    switch (preferences.palette) {
      case 'emerald':
      case 'phosphor_green':
        return {
          stroke: '#34d399',
          gradientStart: '#34d399',
          gradientEnd: '#059669',
          peakColor: '#10b981',
          accent: 'text-emerald-400',
          badgeBg: 'bg-emerald-500/20 border-emerald-500/30 text-emerald-300',
        };
      case 'amber':
        return {
          stroke: '#fbbf24',
          gradientStart: '#fbbf24',
          gradientEnd: '#d97706',
          peakColor: '#f59e0b',
          accent: 'text-amber-400',
          badgeBg: 'bg-amber-500/20 border-amber-500/30 text-amber-300',
        };
      case 'violet':
        return {
          stroke: '#c084fc',
          gradientStart: '#c084fc',
          gradientEnd: '#7c3aed',
          peakColor: '#a855f7',
          accent: 'text-purple-400',
          badgeBg: 'bg-purple-500/20 border-purple-500/30 text-purple-300',
        };
      case 'tactical_red':
        return {
          stroke: '#f87171',
          gradientStart: '#ef4444',
          gradientEnd: '#991b1b',
          peakColor: '#dc2626',
          accent: 'text-rose-400',
          badgeBg: 'bg-rose-500/20 border-rose-500/30 text-rose-300',
        };
      case 'cyan':
      default:
        return {
          stroke: '#38bdf8',
          gradientStart: '#38bdf8',
          gradientEnd: '#0284c7',
          peakColor: '#38bdf8',
          accent: 'text-cyan-400',
          badgeBg: 'bg-cyan-500/20 border-cyan-500/30 text-cyan-300',
        };
    }
  }, [preferences.palette]);

  // Quick format total recording duration
  const totalElapsedFormatted = useMemo(() => {
    if (chartData.length === 0) return '0:00';
    const totalSec = chartData[chartData.length - 1].elapsedSeconds;
    const mins = Math.floor(totalSec / 60);
    const secs = totalSec % 60;
    return `${mins}m ${secs.toString().padStart(2, '0')}s`;
  }, [chartData]);

  return (
    <GlassCard className="w-full !p-4">
      {/* Header and Toggle Controls */}
      <CardHeader
        icon={<Mountain className="w-4 h-4 text-cyan-400 shrink-0" />}
        title={
          <span className="flex flex-wrap items-center gap-1.5">
              <span>Elevation Profile</span>
              {trackSession.isRecording ? (
                <span className="flex items-center space-x-1 text-[10px] px-2 py-0.5 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/40 font-mono font-bold animate-pulse">
                  <span className="w-1.5 h-1.5 rounded-full bg-rose-400 animate-ping" />
                  <span>LIVE RECORDING</span>
                </span>
              ) : isShowingSample ? (
                <span className="text-[10px] px-2 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30 font-medium normal-case">
                  Sample Trail Preview
                </span>
              ) : (
                <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 font-mono font-medium normal-case">
                  {chartData.length} pts recorded
                </span>
              )}
          </span>
        }
        subtitle="Altitude variations over session duration"
      >
          {/* Quick Mark button */}
          {onAddWaypoint && sensors && (
            <button
              onClick={() => {
                if (sensors.latitude === null || sensors.longitude === null) {
                  alert('GPS location unavailable.');
                  return;
                }
                const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                onAddWaypoint({
                  name: `Elevation Mark (${timeStr})`,
                  latitude: sensors.latitude,
                  longitude: sensors.longitude,
                  altitude: sensors.barometricAltitude,
                  color: '#38bdf8',
                  notes: `Track Profile Mark • Alt: ${sensors.barometricAltitude.toFixed(1)}m • Pressure: ${sensors.pressure.toFixed(1)} hPa • Net Δ: ${netAltitudeChange >= 0 ? '+' : ''}${netAltitudeChange}${preferences.altitudeUnit}`,
                });
                setQuickMarkSaved(true);
                setTimeout(() => setQuickMarkSaved(false), 2500);
              }}
              className="flex items-center space-x-1 px-2.5 py-1 rounded-lg bg-cyan-500/20 hover:bg-cyan-500/30 border border-cyan-400/40 text-cyan-200 text-[10px] font-bold transition-all active:scale-95 shadow"
              title="Automatically capture current elevation as waypoint"
            >
              {quickMarkSaved ? <Check className="w-3 h-3 text-emerald-400" /> : <MapPin className="w-3 h-3 text-cyan-400" />}
              <span>{quickMarkSaved ? 'Marked!' : 'Quick Mark'}</span>
            </button>
          )}

          {/* X-Axis switcher: Duration vs Distance */}
          <div className="flex items-center space-x-1 bg-white/10 rounded-lg p-0.5 border border-white/10 text-[10px] font-medium">
            <button
              onClick={() => setViewMode('duration')}
              className={`px-2 py-0.5 rounded-md transition-all flex items-center space-x-1 ${
                viewMode === 'duration'
                  ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                  : 'text-slate-300 hover:text-white'
              }`}
              title="Visualize altitude over elapsed duration"
            >
              <Clock className="w-2.5 h-2.5" />
              <span>Duration</span>
            </button>
            <button
              onClick={() => setViewMode('distance')}
              className={`px-2 py-0.5 rounded-md transition-all flex items-center space-x-1 ${
                viewMode === 'distance'
                  ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                  : 'text-slate-300 hover:text-white'
              }`}
              title="Visualize altitude over distance traveled"
            >
              <Footprints className="w-2.5 h-2.5" />
              <span>Distance</span>
            </button>
          </div>
        
      </CardHeader>

      {/* Profile Overview Metric Strip */}
      <div className="grid grid-cols-4 gap-2 text-center text-xs font-mono mb-3">
        {/* Current Altitude */}
        <div className="p-2 rounded-xl bg-white/[0.04] border border-white/[0.06]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Current Alt</span>
          <span className="text-cyan-300 font-bold text-sm whitespace-nowrap">
            {currentAltValue}{' '}
            <span className="text-[10px] text-slate-400 font-sans">{preferences.altitudeUnit}</span>
          </span>
        </div>

        {/* Net Altitude Delta from Start */}
        <div className="p-2 rounded-xl bg-white/[0.04] border border-white/[0.06]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Net Δ Alt</span>
          <span
            className={`font-bold text-sm flex items-center justify-center space-x-0.5 whitespace-nowrap ${
              netAltitudeChange > 0
                ? 'text-emerald-400'
                : netAltitudeChange < 0
                ? 'text-rose-400'
                : 'text-slate-200'
            }`}
          >
            {netAltitudeChange > 0 ? (
              <ArrowUp className="w-3 h-3 text-emerald-400" />
            ) : netAltitudeChange < 0 ? (
              <ArrowDown className="w-3 h-3 text-rose-400" />
            ) : null}
            <span>
              {netAltitudeChange >= 0 ? `+${netAltitudeChange}` : netAltitudeChange}{' '}
              <span className="text-[10px] text-slate-400 font-sans">{preferences.altitudeUnit}</span>
            </span>
          </span>
        </div>

        {/* Peak Summit */}
        <div className="p-2 rounded-xl bg-white/[0.04] border border-white/[0.06]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Peak Summit</span>
          <span className="text-amber-300 font-bold text-sm whitespace-nowrap">
            {maxAltitude}{' '}
            <span className="text-[10px] text-slate-400 font-sans">{preferences.altitudeUnit}</span>
          </span>
        </div>

        {/* Total Elevation Span / Relief */}
        <div className="p-2 rounded-xl bg-white/[0.04] border border-white/[0.06]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Elev Span</span>
          <span className="text-emerald-300 font-bold text-sm whitespace-nowrap">
            +{totalElevationSpan}{' '}
            <span className="text-[10px] text-slate-400 font-sans">{preferences.altitudeUnit}</span>
          </span>
        </div>
      </div>

      {/* Main Recharts Container with Liquid Glass Glows */}
      <div className="relative w-full h-56 pt-1 select-none">
        {chartData.length < 2 && !isShowingSample ? (
          <div className="w-full h-full flex flex-col items-center justify-center text-center p-4 rounded-2xl bg-white/[0.02] border border-white/[0.08]">
            <Mountain className="w-8 h-8 text-cyan-400 mb-2 opacity-60 animate-bounce" />
            <div className="text-xs font-bold text-white mb-1">
              Recording Initialized
            </div>
            <p className="text-[11px] text-slate-400 max-w-xs">
              Waiting for subsequent GPS/barometric telemetry points to graph elevation changes...
            </p>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart
              data={chartData}
              margin={{ top: 12, right: 12, left: 0, bottom: 0 }}
            >
              <defs>
                <linearGradient id="elevationLiquidGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={theme.gradientStart} stopOpacity={0.65} />
                  <stop offset="60%" stopColor={theme.gradientEnd} stopOpacity={0.2} />
                  <stop offset="95%" stopColor={theme.gradientEnd} stopOpacity={0.02} />
                </linearGradient>
              </defs>

              {/* Grid lines */}
              <CartesianGrid
                strokeDasharray="3 3"
                vertical={false}
                stroke="rgba(255, 255, 255, 0.08)"
              />

              {/* X-Axis: Duration (timeLabel) or Distance */}
              <XAxis
                dataKey={viewMode === 'duration' ? 'durationLabel' : 'distance'}
                stroke="rgba(255, 255, 255, 0.3)"
                tick={{ fill: 'rgba(255, 255, 255, 0.55)', fontSize: 10, fontFamily: 'monospace' }}
                tickLine={{ stroke: 'rgba(255, 255, 255, 0.2)' }}
                axisLine={{ stroke: 'rgba(255, 255, 255, 0.15)' }}
                tickFormatter={(val) =>
                  viewMode === 'distance'
                    ? `${val} ${preferences.altitudeUnit === 'ft' ? 'mi' : 'km'}`
                    : `${val}`
                }
              />

              {/* Y-Axis: Elevation */}
              <YAxis
                domain={['auto', 'auto']}
                width={38}
                stroke="rgba(255, 255, 255, 0.3)"
                tick={{ fill: 'rgba(255, 255, 255, 0.55)', fontSize: 10, fontFamily: 'monospace' }}
                tickLine={{ stroke: 'rgba(255, 255, 255, 0.2)' }}
                axisLine={{ stroke: 'rgba(255, 255, 255, 0.15)' }}
                tickFormatter={(val) => `${val}`}
              />

              {/* Start Baseline reference line */}
              {startAltitudeValue > 0 && (
                <ReferenceLine
                  y={startAltitudeValue}
                  stroke="rgba(148, 163, 184, 0.45)"
                  strokeDasharray="4 4"
                  label={{
                    value: `Start ${startAltitudeValue}${preferences.altitudeUnit}`,
                    fill: '#94a3b8',
                    fontSize: 8.5,
                    position: 'insideBottomLeft',
                  }}
                />
              )}

              {/* Peak Summit reference line */}
              <ReferenceLine
                y={maxAltitude}
                stroke="rgba(251, 191, 36, 0.55)"
                strokeDasharray="3 3"
                label={{
                  value: `Peak ${maxAltitude}${preferences.altitudeUnit}`,
                  fill: '#fbbf24',
                  fontSize: 9,
                  position: 'top',
                }}
              />

              {/* Interactive Liquid Glass Tooltip */}
              <Tooltip
                content={({ active, payload }) => {
                  if (active && payload && payload.length) {
                    const data = payload[0].payload;
                    return (
                      <div className="p-3 rounded-2xl bg-black/85 backdrop-blur-xl border border-white/20 shadow-[0_8px_30px_rgba(0,0,0,0.8)] text-xs font-mono space-y-1.5 z-50 min-w-[200px]">
                        <div className="flex items-center justify-between border-b border-white/10 pb-1">
                          <span className="text-[10px] uppercase font-sans text-slate-400">
                            Point #{data.index}
                          </span>
                          <span className="text-cyan-300 font-bold flex items-center space-x-1">
                            <Clock className="w-3 h-3 text-cyan-400" />
                            <span>{data.durationLabel} ({data.distance} {data.distanceUnit})</span>
                          </span>
                        </div>

                        <div className="flex items-center justify-between">
                          <span className="text-slate-400 font-sans">Elevation:</span>
                          <span className="text-white font-bold text-sm whitespace-nowrap">
                            {data.altitude} {data.altitudeUnit}
                          </span>
                        </div>

                        <div className="flex items-center justify-between text-[11px]">
                          <span className="text-slate-400 font-sans">Net Δ from Start:</span>
                          <span
                            className={`font-bold ${
                              data.deltaFromStart >= 0 ? 'text-emerald-400' : 'text-rose-400'
                            }`}
                          >
                            {data.deltaFromStart >= 0 ? `+${data.deltaFromStart}` : data.deltaFromStart}{' '}
                            {data.altitudeUnit}
                          </span>
                        </div>

                        <div className="flex items-center justify-between text-[11px]">
                          <span className="text-slate-400 font-sans">Baro Pressure:</span>
                          <span className="text-amber-300">{data.pressure} hPa</span>
                        </div>

                        <div className="flex items-center justify-between text-[11px]">
                          <span className="text-slate-400 font-sans">Velocity:</span>
                          <span className="text-emerald-300">
                            {data.speed} {data.speedUnit}
                          </span>
                        </div>
                      </div>
                    );
                  }
                  return null;
                }}
              />

              {/* Main Liquid Area */}
              <Area
                type="monotone"
                dataKey="altitude"
                stroke={theme.stroke}
                strokeWidth={2.5}
                fillOpacity={1}
                fill="url(#elevationLiquidGrad)"
                activeDot={{
                  r: 5,
                  fill: theme.stroke,
                  stroke: '#ffffff',
                  strokeWidth: 2,
                  className: 'filter drop-shadow-[0_0_8px_currentColor]',
                }}
              />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* Footer controls & Track switch */}
      <div className="flex items-center justify-between pt-2.5 mt-2 border-t border-white/[0.08] text-[11px] text-slate-400">
        <div className="flex items-center space-x-1.5">
          <Clock className="w-3.5 h-3.5 text-cyan-400" />
          <span>
            Duration: <span className="text-white font-mono font-medium">{totalElapsedFormatted}</span> •{' '}
            {chartData.length} points
          </span>
        </div>

        <div className="flex items-center space-x-2">
          {hasLiveSession && (
            <button
              onClick={() => setForceSampleData(!forceSampleData)}
              className="text-[10px] text-cyan-300 hover:text-cyan-200 underline font-sans"
            >
              {forceSampleData ? 'Switch to My Track' : 'View Sample Hike'}
            </button>
          )}

          {!trackSession.isRecording && onToggleTrackRecording && (
            <button
              onClick={onToggleTrackRecording}
              className="flex items-center space-x-1 px-2.5 py-0.5 rounded-lg bg-emerald-500/20 hover:bg-emerald-500/30 border border-emerald-500/40 text-emerald-300 text-[10px] font-bold transition-all"
            >
              <Play className="w-2.5 h-2.5 fill-current" />
              <span>Start Track</span>
            </button>
          )}
        </div>
      </div>
    </GlassCard>
  );
};
