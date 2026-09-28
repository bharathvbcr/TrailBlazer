import React, { useState, useMemo } from 'react';
import {
  SensorState,
  UserPreferences,
  PressureHistoryPoint,
} from '../types/sensors';
import {
  convertPressure,
  convertAltitude,
  convertVerticalSpeed,
  calculateWaterBoilingPoint,
  calculateAirDensity,
  calculateDensityAltitude,
  calculateThreeHourBarometricTrend,
} from '../utils/calculations';
import { GlassCard } from './GlassCard';
import { Modal } from './Modal';
import {
  Gauge,
  Mountain,
  CloudRain,
  TrendingDown,
  TrendingUp,
  Minus,
  Sparkles,
  Sliders,
  RotateCcw,
  Coffee,
  Wind,
  Satellite,
  Info,
  ArrowUp,
  ArrowDown,
  ArrowRight,
  MoveRight,
  History,
  Clock,
  Volume2,
  VolumeX,
  AlertTriangle,
} from 'lucide-react';

interface Props {
  sensors: SensorState;
  preferences: UserPreferences;
  onSetQnh: (qnh: number) => void;
  onSetManualPressure: (p: number) => void;
  onTareAltitude: () => void;
  onResetTare: () => void;
  onCalibrateToGps: () => void;
  onUpdatePreferences: (prefs: Partial<UserPreferences>) => void;
  onSimulateTrendScenario?: (scenario: 'rising' | 'falling' | 'steady') => void;
}

export const AltimeterBarometerView: React.FC<Props> = ({
  sensors,
  preferences,
  onSetQnh,
  onSetManualPressure,
  onTareAltitude,
  onResetTare,
  onCalibrateToGps,
  onUpdatePreferences,
  onSimulateTrendScenario,
}) => {
  const [showQnhModal, setShowQnhModal] = useState(false);
  const [showSimulation, setShowSimulation] = useState(false);
  const [hoveredPoint, setHoveredPoint] = useState<PressureHistoryPoint | null>(null);

  // 3-Hour Barometric Trend Analysis (WMO Code 0200 Standard Tendency)
  const trend3h = useMemo(() => {
    return (
      sensors.barometricTrend3h ||
      calculateThreeHourBarometricTrend(sensors.pressureHistory, sensors.pressure)
    );
  }, [sensors.barometricTrend3h, sensors.pressureHistory, sensors.pressure]);

  // Pressure unit conversion for 3-hour delta and baseline
  const deltaPressureConverted = convertPressure(Math.abs(trend3h.deltaHpa3h), preferences.pressureUnit);
  const pressure3hAgoConverted = convertPressure(trend3h.pressure3hAgo, preferences.pressureUnit);

  // Pressure unit conversion
  const pressureConverted = convertPressure(sensors.pressure, preferences.pressureUnit);
  const qnhConverted = convertPressure(sensors.qnh, preferences.pressureUnit);

  // Altitude unit conversion
  const rawAltitude = sensors.barometricAltitude;
  const relativeAltitude = sensors.relativeAltitudeZero !== 0
    ? rawAltitude - sensors.relativeAltitudeZero
    : null;

  const altitudeDisplay = convertAltitude(rawAltitude, preferences.altitudeUnit);
  const relativeDisplay = relativeAltitude !== null
    ? convertAltitude(relativeAltitude, preferences.altitudeUnit)
    : null;
  const gpsAltitudeDisplay = sensors.gpsAltitude !== null
    ? convertAltitude(sensors.gpsAltitude, preferences.altitudeUnit)
    : null;

  // Variometer / Vertical Speed
  const vsiDisplay = convertVerticalSpeed(sensors.verticalSpeed, preferences.altitudeUnit);
  const ascentDisplay = convertAltitude(sensors.sessionAscentGain, preferences.altitudeUnit);
  const descentDisplay = convertAltitude(sensors.sessionDescentLoss, preferences.altitudeUnit);

  // Atmospheric physics insights
  const boilingPoint = calculateWaterBoilingPoint(sensors.pressure);
  const airDensity = calculateAirDensity(sensors.pressure, sensors.barometricAltitude);
  const densityAltitudeFt = calculateDensityAltitude(sensors.pressure, sensors.barometricAltitude);
  const oxygenPct = Math.round((sensors.pressure / 1013.25) * 100);

  // Weather tendency badge styling
  const getTendencyBadge = () => {
    switch (sensors.weatherTendency.category) {
      case 'storm':
        return {
          icon: <CloudRain className="w-4 h-4 text-rose-400" />,
          color: 'text-rose-300 border-rose-500/40 bg-rose-500/15',
          trendIcon: <TrendingDown className="w-3.5 h-3.5 text-rose-400" />,
        };
      case 'deteriorating':
        return {
          icon: <CloudRain className="w-4 h-4 text-amber-400" />,
          color: 'text-amber-300 border-amber-500/40 bg-amber-500/15',
          trendIcon: <TrendingDown className="w-3.5 h-3.5 text-amber-400" />,
        };
      case 'improving':
        return {
          icon: <Sparkles className="w-4 h-4 text-cyan-400" />,
          color: 'text-cyan-300 border-cyan-500/40 bg-cyan-500/15',
          trendIcon: <TrendingUp className="w-3.5 h-3.5 text-cyan-400" />,
        };
      case 'fair':
        return {
          icon: <Sparkles className="w-4 h-4 text-emerald-400" />,
          color: 'text-emerald-300 border-emerald-500/40 bg-emerald-500/15',
          trendIcon: <Minus className="w-3.5 h-3.5 text-emerald-400" />,
        };
      case 'steady':
      default:
        return {
          icon: <Wind className="w-4 h-4 text-slate-300" />,
          color: 'text-slate-200 border-white/20 bg-white/10',
          trendIcon: <Minus className="w-3.5 h-3.5 text-slate-400" />,
        };
    }
  };

  const badge = getTendencyBadge();

  // Circular dial needle angle (scaled between 950 hPa and 1050 hPa)
  const pressureMin = 950;
  const pressureMax = 1050;
  const clampedPressure = Math.max(pressureMin, Math.min(pressureMax, sensors.pressure));
  const needleAngle = ((clampedPressure - pressureMin) / (pressureMax - pressureMin)) * 240 - 120; // -120° to +120°

  // Pressure history interactive chart calculation
  const history = sensors.pressureHistory;
  const minP = Math.min(...history.map((h) => h.pressure), sensors.pressure) - 0.5;
  const maxP = Math.max(...history.map((h) => h.pressure), sensors.pressure) + 0.5;
  const rangeP = Math.max(1, maxP - minP);

  const chartPoints = history.map((pt, idx) => {
    const x = (idx / Math.max(1, history.length - 1)) * 280;
    const y = 60 - ((pt.pressure - minP) / rangeP) * 50;
    return `${x},${y}`;
  }).join(' ');

  return (
    <div className="flex flex-col items-center w-full max-w-md mx-auto space-y-4 pb-20">
      {/* Top Unit & Action Bar */}
      <div className="flex items-center justify-between w-full px-2">
        <div className="flex items-center space-x-2">
          {/* Pressure unit switcher */}
          <div className="flex bg-white/10 rounded-full p-0.5 border border-white/15 text-[11px] font-medium">
            {(['hPa', 'inHg', 'mmHg'] as const).map((unit) => (
              <button
                key={unit}
                onClick={() => onUpdatePreferences({ pressureUnit: unit })}
                className={`px-2.5 py-1 rounded-full transition-all ${
                  preferences.pressureUnit === unit
                    ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                    : 'text-slate-300 hover:text-white'
                }`}
              >
                {unit}
              </button>
            ))}
          </div>

          {/* Altitude unit switcher */}
          <div className="flex bg-white/10 rounded-full p-0.5 border border-white/15 text-[11px] font-medium">
            {(['m', 'ft'] as const).map((unit) => (
              <button
                key={unit}
                onClick={() => onUpdatePreferences({ altitudeUnit: unit })}
                className={`px-2.5 py-1 rounded-full transition-all ${
                  preferences.altitudeUnit === unit
                    ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                    : 'text-slate-300 hover:text-white'
                }`}
              >
                {unit}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center space-x-2">
          {/* Audio Variometer Toggle Button */}
          <button
            onClick={() => onUpdatePreferences({ audioVariometerEnabled: !preferences.audioVariometerEnabled })}
            title="Audio Variometer (Acoustic climb beeps & sink tone)"
            className={`p-2 rounded-full border transition-all ${
              preferences.audioVariometerEnabled
                ? 'bg-emerald-500/20 border-emerald-500/50 text-emerald-300 shadow-[0_0_12px_rgba(52,211,153,0.3)] animate-pulse'
                : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
            }`}
          >
            {preferences.audioVariometerEnabled ? <Volume2 className="w-3.5 h-3.5" /> : <VolumeX className="w-3.5 h-3.5" />}
          </button>

          <button
            onClick={() => setShowSimulation(!showSimulation)}
            title="Adjust simulated altitude & pressure"
            className={`p-2 rounded-full border transition-all ${
              showSimulation
                ? 'bg-cyan-500/20 border-cyan-500/50 text-cyan-300 shadow-[0_0_12px_rgba(56,189,248,0.3)]'
                : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
            }`}
          >
            <Sliders className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Main Altimeter & Barometer Liquid Dial */}
      <div className="relative w-80 h-80 flex items-center justify-center select-none my-1">
        {/* Ambient Glow */}
        <div className="absolute inset-0 rounded-full bg-gradient-to-tr from-cyan-500/10 via-teal-500/10 to-indigo-500/15 blur-2xl pointer-events-none" />

        {/* Outer Liquid Glass Bezel */}
        <div className="absolute inset-0 rounded-full border border-white/20 bg-gradient-to-b from-white/[0.08] to-white/[0.02] backdrop-blur-2xl shadow-[0_12px_40px_rgba(0,0,0,0.5),inset_0_2px_4px_rgba(255,255,255,0.3)] pointer-events-none" />

        {/* Dial Face SVG (Ticks, Pressure Zones, Scale) */}
        <svg className="absolute inset-0 w-full h-full" viewBox="0 0 320 320">
          <defs>
            <radialGradient id="altGlass" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="rgba(255,255,255,0.03)" />
              <stop offset="80%" stopColor="rgba(255,255,255,0.01)" />
              <stop offset="100%" stopColor="rgba(0,0,0,0.4)" />
            </radialGradient>
            <linearGradient id="needleGrad" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stopColor="#38bdf8" />
              <stop offset="100%" stopColor="#0284c7" />
            </linearGradient>
          </defs>

          <circle cx="160" cy="160" r="148" fill="url(#altGlass)" />
          <circle cx="160" cy="160" r="148" fill="none" stroke="rgba(255,255,255,0.12)" strokeWidth="1" />

          {/* Color-coded Pressure Arc Bands */}
          <path
            d="M 62,216 A 120 120 0 0 1 76,104"
            fill="none"
            stroke="rgba(244,63,94,0.4)"
            strokeWidth="5"
            strokeLinecap="round"
          />
          <path
            d="M 78,100 A 120 120 0 0 1 160,40"
            fill="none"
            stroke="rgba(251,191,36,0.4)"
            strokeWidth="5"
            strokeLinecap="round"
          />
          <path
            d="M 164,40 A 120 120 0 0 1 258,216"
            fill="none"
            stroke="rgba(52,211,153,0.4)"
            strokeWidth="5"
            strokeLinecap="round"
          />

          {/* Render Gauge Scale Ticks (950 to 1050 hPa in steps of 2 hPa) */}
          {Array.from({ length: 51 }).map((_, i) => {
            const hpa = 950 + i * 2;
            const angleDeg = ((hpa - 950) / 100) * 240 - 120 - 90;
            const isMajor = hpa % 10 === 0;
            const innerR = isMajor ? 122 : 128;
            const outerR = 138;
            const rad = (angleDeg * Math.PI) / 180;
            const x1 = 160 + innerR * Math.cos(rad);
            const y1 = 160 + innerR * Math.sin(rad);
            const x2 = 160 + outerR * Math.cos(rad);
            const y2 = 160 + outerR * Math.sin(rad);

            return (
              <line
                key={hpa}
                x1={x1}
                y1={y1}
                x2={x2}
                y2={y2}
                stroke={isMajor ? '#ffffff' : 'rgba(255,255,255,0.3)'}
                strokeWidth={isMajor ? '2' : '1'}
              />
            );
          })}

          {/* Numbers along scale (960, 980, 1000, 1013, 1020, 1040) */}
          {[960, 980, 1000, 1013, 1030, 1045].map((val) => {
            const angleDeg = ((val - 950) / 100) * 240 - 120 - 90;
            const rad = (angleDeg * Math.PI) / 180;
            const x = 160 + 106 * Math.cos(rad);
            const y = 160 + 106 * Math.sin(rad);

            return (
              <text
                key={val}
                x={x}
                y={y}
                fill={val === 1013 ? '#38bdf8' : 'rgba(255,255,255,0.5)'}
                fontSize={val === 1013 ? '10' : '9'}
                fontWeight={val === 1013 ? 'bold' : 'normal'}
                fontFamily="monospace"
                textAnchor="middle"
                dominantBaseline="central"
              >
                {val}
              </text>
            );
          })}

          {/* Weather Zone Labels */}
          <text x="75" y="150" fill="rgba(244,63,94,0.6)" fontSize="8" fontWeight="bold">RAIN / STORM</text>
          <text x="135" y="66" fill="rgba(251,191,36,0.6)" fontSize="8" fontWeight="bold">CHANGE</text>
          <text x="220" y="150" fill="rgba(52,211,153,0.6)" fontSize="8" fontWeight="bold">FAIR</text>
        </svg>

        {/* Central Display Card (Altitude & Pressure Values) */}
        <div className="absolute z-20 w-44 h-44 rounded-full border border-white/20 bg-gradient-to-b from-white/[0.14] to-white/[0.04] backdrop-blur-md shadow-[inset_0_2px_8px_rgba(0,0,0,0.5),0_8px_20px_rgba(0,0,0,0.4)] flex flex-col items-center justify-center text-center p-2">
          {/* Altitude Category Icon */}
          <div className="flex items-center space-x-1 text-cyan-400 mb-0.5">
            <Mountain className="w-3.5 h-3.5" />
            <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-300">
              Altitude
            </span>
          </div>

          {/* Primary Altitude Readout */}
          <div className="text-3xl font-black font-mono tracking-tight text-white drop-shadow-[0_2px_10px_rgba(255,255,255,0.2)]">
            {altitudeDisplay.value.toFixed(0)}
            <span className="text-sm font-semibold ml-1 text-cyan-400 font-sans">
              {altitudeDisplay.label}
            </span>
          </div>

          {/* Relative Tare (if active) */}
          {relativeDisplay !== null ? (
            <div className="text-[11px] font-mono text-emerald-400 font-semibold bg-emerald-500/15 px-2 py-0.5 rounded-full border border-emerald-500/30 my-0.5">
              {relativeAltitude! >= 0 ? `+${relativeDisplay.value.toFixed(1)}` : relativeDisplay.value.toFixed(1)} {relativeDisplay.label} (Tare)
            </div>
          ) : (
            <div className="text-[10px] text-slate-400 font-mono my-0.5">
              QNH: {qnhConverted.value.toFixed(1)} {qnhConverted.label}
            </div>
          )}

          {/* Station Pressure & 3-Hour Trend Indicator Badge */}
          <div className="flex flex-col items-center justify-center mt-1">
            <div className="text-xs font-bold text-slate-200 font-mono">
              {pressureConverted.value.toFixed(2)} {pressureConverted.label}
            </div>

            {/* 3-Hour Trend Real-Time Arrow Badge */}
            <div
              className={`inline-flex items-center space-x-1 px-2 py-0.5 mt-1 rounded-full text-[10px] font-bold border transition-all ${
                trend3h.trend === 'rising'
                  ? 'bg-emerald-500/20 border-emerald-500/40 text-emerald-300 shadow-[0_0_8px_rgba(52,211,153,0.3)]'
                  : trend3h.trend === 'falling'
                  ? 'bg-rose-500/20 border-rose-500/40 text-rose-300 shadow-[0_0_8px_rgba(244,63,94,0.3)]'
                  : 'bg-cyan-500/15 border-cyan-500/30 text-cyan-200 shadow-[0_0_8px_rgba(56,189,248,0.2)]'
              }`}
              title={`3-Hour Trend: ${trend3h.trend.toUpperCase()} (${trend3h.deltaHpa3h > 0 ? '+' : ''}${trend3h.deltaHpa3h.toFixed(1)} hPa)`}
            >
              {trend3h.trend === 'rising' ? (
                <ArrowUp className="w-2.5 h-2.5 text-emerald-400 stroke-[2.5]" />
              ) : trend3h.trend === 'falling' ? (
                <ArrowDown className="w-2.5 h-2.5 text-rose-400 stroke-[2.5]" />
              ) : (
                <MoveRight className="w-2.5 h-2.5 text-cyan-300 stroke-[2.5]" />
              )}
              <span className="uppercase tracking-wider font-mono">3h {trend3h.trend}</span>
              <span className="font-mono opacity-80">
                ({trend3h.deltaHpa3h > 0 ? '+' : ''}{trend3h.deltaHpa3h.toFixed(1)})
              </span>
            </div>
          </div>
        </div>

        {/* Rotating Pressure Pointer Needle */}
        <div
          className="absolute inset-0 pointer-events-none transition-transform duration-300 ease-out flex items-center justify-center"
          style={{ transform: `rotate(${needleAngle}deg)` }}
        >
          <div className="relative w-full h-full flex items-center justify-center">
            <div className="absolute top-10 w-1.5 h-28 bg-gradient-to-t from-cyan-400 to-cyan-300 rounded-full shadow-[0_0_12px_#38bdf8]" />
            <div className="w-4 h-4 rounded-full bg-cyan-400 border-2 border-white shadow-[0_0_8px_#38bdf8] z-30" />
          </div>
        </div>
      </div>

      {/* Variometer (VSI - Vertical Speed Indicator) & Ascent/Descent Card */}
      <GlassCard className="w-full !p-3.5">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center space-x-1.5">
            <Gauge className="w-4 h-4 text-cyan-400" />
            <span className="text-xs font-bold text-white uppercase tracking-wider">
              Variometer & Vertical Rate
            </span>
          </div>
          <span className="text-[11px] font-mono text-slate-400">
            {vsiDisplay.label}
          </span>
        </div>

        {/* Variometer Vertical Speed Rate readout */}
        <div className="flex items-center justify-between mb-2 p-2 rounded-xl bg-white/[0.03] border border-white/[0.06]">
          <div className="flex items-center space-x-2">
            {sensors.verticalSpeed > 0.1 ? (
              <div className="p-1 rounded-lg bg-emerald-500/20 text-emerald-400">
                <ArrowUp className="w-4 h-4" />
              </div>
            ) : sensors.verticalSpeed < -0.1 ? (
              <div className="p-1 rounded-lg bg-amber-500/20 text-amber-400">
                <ArrowDown className="w-4 h-4" />
              </div>
            ) : (
              <div className="p-1 rounded-lg bg-white/10 text-slate-400">
                <Minus className="w-4 h-4" />
              </div>
            )}
            <div>
              <span className="text-[10px] text-slate-400 uppercase block">Climb / Sink Rate</span>
              <span className={`text-base font-black font-mono ${
                sensors.verticalSpeed > 0.1 ? 'text-emerald-400' : sensors.verticalSpeed < -0.1 ? 'text-amber-400' : 'text-slate-300'
              }`}>
                {sensors.verticalSpeed >= 0 ? `+${vsiDisplay.value.toFixed(0)}` : vsiDisplay.value.toFixed(0)} {vsiDisplay.label}
              </span>
            </div>
          </div>

          <div className="text-right text-xs font-mono">
            <span className="text-[10px] text-slate-400 uppercase font-sans block">In m/s</span>
            <span className="text-slate-200 font-bold">{sensors.verticalSpeed >= 0 ? `+${sensors.verticalSpeed}` : sensors.verticalSpeed} m/s</span>
          </div>
        </div>

        {/* Session Ascent & Descent accumulators */}
        <div className="grid grid-cols-2 gap-2 text-center text-xs font-mono pt-1">
          <div className="p-2 rounded-xl bg-emerald-500/10 border border-emerald-500/20">
            <span className="text-[10px] text-emerald-300/80 uppercase font-sans block">Session Ascent Gain</span>
            <span className="text-sm font-bold text-emerald-300">+{ascentDisplay.value.toFixed(0)} {ascentDisplay.label}</span>
          </div>
          <div className="p-2 rounded-xl bg-rose-500/10 border border-rose-500/20">
            <span className="text-[10px] text-rose-300/80 uppercase font-sans block">Session Descent Loss</span>
            <span className="text-sm font-bold text-rose-300">-{descentDisplay.value.toFixed(0)} {descentDisplay.label}</span>
          </div>
        </div>
      </GlassCard>

      {/* Altitude Tare & Calibration Quick Actions */}
      <div className="flex items-center justify-center space-x-2 w-full px-2">
        {relativeAltitude !== null ? (
          <button
            onClick={onResetTare}
            className="flex-1 flex items-center justify-center space-x-1.5 py-2.5 px-3 rounded-2xl bg-white/10 hover:bg-white/15 border border-white/15 text-xs font-semibold text-slate-200 transition-all active:scale-98"
          >
            <RotateCcw className="w-3.5 h-3.5 text-slate-400" />
            <span>Reset Zero</span>
          </button>
        ) : (
          <button
            onClick={onTareAltitude}
            className="flex-1 flex items-center justify-center space-x-1.5 py-2.5 px-3 rounded-2xl bg-white/10 hover:bg-white/15 border border-white/15 text-xs font-semibold text-slate-200 transition-all active:scale-98"
          >
            <Mountain className="w-3.5 h-3.5 text-cyan-400" />
            <span>Tare Elevation</span>
          </button>
        )}

        <button
          onClick={() => setShowQnhModal(true)}
          className="flex-1 flex items-center justify-center space-x-1.5 py-2.5 px-3 rounded-2xl bg-cyan-500/20 hover:bg-cyan-500/30 border border-cyan-500/40 text-xs font-semibold text-cyan-300 transition-all active:scale-98"
        >
          <Gauge className="w-3.5 h-3.5 text-cyan-400" />
          <span>Calibrate QNH</span>
        </button>

        {sensors.gpsAltitude !== null && (
          <button
            onClick={onCalibrateToGps}
            title="Sync QNH to current GPS elevation"
            className="p-2.5 rounded-2xl bg-white/10 hover:bg-white/15 border border-white/15 text-slate-300 transition-all"
          >
            <Satellite className="w-4 h-4 text-emerald-400" />
          </button>
        )}
      </div>

      {/* 3-Hour Barometric Trend Analysis Card */}
      <GlassCard className="w-full !p-4 border-cyan-500/30">
        {/* Header */}
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center space-x-2">
            <div className="p-1.5 rounded-xl bg-cyan-500/20 border border-cyan-500/40 text-cyan-300">
              <History className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider">
                3-Hour Barometric Trend Analysis
              </h3>
              <span className="text-[10px] text-slate-400 block font-sans">
                Real-Time Barometric Tendency (WMO Standard)
              </span>
            </div>
          </div>
          <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-white/10 border border-white/15 text-cyan-300">
            {trend3h.timeSpanHours}h Window
          </span>
        </div>

        {/* Hero Arrow Indicator Container */}
        <div
          className={`p-3.5 rounded-2xl border transition-all ${
            trend3h.trend === 'rising'
              ? 'bg-gradient-to-r from-emerald-950/40 via-emerald-900/20 to-transparent border-emerald-500/40 shadow-[0_0_24px_rgba(52,211,153,0.15)]'
              : trend3h.trend === 'falling'
              ? 'bg-gradient-to-r from-rose-950/40 via-rose-900/20 to-transparent border-rose-500/40 shadow-[0_0_24px_rgba(244,63,94,0.15)]'
              : 'bg-gradient-to-r from-cyan-950/40 via-slate-900/20 to-transparent border-cyan-500/30 shadow-[0_0_20px_rgba(56,189,248,0.1)]'
          }`}
        >
          <div className="flex items-center justify-between">
            {/* Left: Large Glowing Arrow Indicator & Status */}
            <div className="flex items-center space-x-3.5">
              <div
                className={`relative w-14 h-14 rounded-2xl flex items-center justify-center border-2 transition-all ${
                  trend3h.trend === 'rising'
                    ? 'bg-emerald-500/25 border-emerald-400 text-emerald-300 shadow-[0_0_20px_rgba(52,211,153,0.4)]'
                    : trend3h.trend === 'falling'
                    ? 'bg-rose-500/25 border-rose-400 text-rose-300 shadow-[0_0_20px_rgba(244,63,94,0.4)]'
                    : 'bg-cyan-500/20 border-cyan-400 text-cyan-200 shadow-[0_0_16px_rgba(56,189,248,0.3)]'
                }`}
              >
                {/* Arrow Indicator Icon */}
                {trend3h.trend === 'rising' ? (
                  <ArrowUp className="w-8 h-8 stroke-[2.75] animate-bounce" />
                ) : trend3h.trend === 'falling' ? (
                  <ArrowDown className="w-8 h-8 stroke-[2.75] animate-bounce" />
                ) : (
                  <MoveRight className="w-8 h-8 stroke-[2.75]" />
                )}
              </div>

              <div>
                <div className="flex items-center space-x-2">
                  <span
                    className={`text-lg font-black tracking-wide uppercase font-mono ${
                      trend3h.trend === 'rising'
                        ? 'text-emerald-300 drop-shadow-[0_0_8px_rgba(52,211,153,0.5)]'
                        : trend3h.trend === 'falling'
                        ? 'text-rose-300 drop-shadow-[0_0_8px_rgba(244,63,94,0.5)]'
                        : 'text-cyan-200 drop-shadow-[0_0_8px_rgba(56,189,248,0.4)]'
                    }`}
                  >
                    {trend3h.trend}
                  </span>
                  <span
                    className={`text-[10px] font-bold px-2 py-0.5 rounded-full border ${
                      trend3h.trend === 'rising'
                        ? 'bg-emerald-500/20 border-emerald-500/40 text-emerald-300'
                        : trend3h.trend === 'falling'
                        ? 'bg-rose-500/20 border-rose-500/40 text-rose-300'
                        : 'bg-cyan-500/20 border-cyan-500/40 text-cyan-200'
                    }`}
                  >
                    {trend3h.subCategory.replace('_', ' ').toUpperCase()}
                  </span>
                </div>

                <div className="text-[11px] text-slate-300 mt-0.5">
                  {trend3h.trend === 'rising'
                    ? 'Pressure rising (+0.5 hPa/3h threshold met)'
                    : trend3h.trend === 'falling'
                    ? 'Pressure falling (-0.5 hPa/3h threshold met)'
                    : 'Pressure steady (within ±0.5 hPa/3h threshold)'}
                </div>
              </div>
            </div>

            {/* Right: Pressure Delta Readout */}
            <div className="text-right">
              <span className="text-[10px] text-slate-400 uppercase font-mono block">3-Hour Net ΔP</span>
              <div
                className={`text-base font-black font-mono ${
                  trend3h.trend === 'rising'
                    ? 'text-emerald-300'
                    : trend3h.trend === 'falling'
                    ? 'text-rose-300'
                    : 'text-cyan-200'
                }`}
              >
                {trend3h.deltaHpa3h > 0 ? '+' : trend3h.deltaHpa3h < 0 ? '-' : '±'}
                {deltaPressureConverted.value.toFixed(preferences.pressureUnit === 'inHg' ? 3 : 2)}{' '}
                {deltaPressureConverted.label}
              </div>
              <span className="text-[10px] font-mono text-slate-400 block">
                ({trend3h.deltaHpa3h > 0 ? '+' : ''}{trend3h.deltaHpa3h.toFixed(1)} hPa •{' '}
                {trend3h.ratePerHour > 0 ? '+' : ''}{trend3h.ratePerHour.toFixed(2)} hPa/h)
              </span>
            </div>
          </div>
        </div>

        {/* 3-Hour Progression Milestone Steps (T-3h -> T-2h -> T-1h -> Now) */}
        <div className="mt-3 pt-3 border-t border-white/[0.08]">
          <div className="flex items-center justify-between text-[11px] text-slate-300 mb-2">
            <span className="font-semibold flex items-center space-x-1">
              <Clock className="w-3.5 h-3.5 text-cyan-400" />
              <span>Hourly Pressure Progression</span>
            </span>
            <span className="text-[10px] text-slate-400 font-mono">
              Base: {pressure3hAgoConverted.value.toFixed(preferences.pressureUnit === 'inHg' ? 2 : 1)}{' '}
              {pressure3hAgoConverted.label}
            </span>
          </div>

          <div className="grid grid-cols-4 gap-1.5 text-center">
            {trend3h.hourlyMilestones.map((m, idx) => {
              const isCurrent = idx === trend3h.hourlyMilestones.length - 1;
              const dSign = m.deltaFromStart > 0 ? '+' : '';
              return (
                <div
                  key={m.hourLabel}
                  className={`p-2 rounded-xl border transition-all ${
                    isCurrent
                      ? 'bg-cyan-500/20 border-cyan-400/50 shadow-[0_0_12px_rgba(56,189,248,0.25)]'
                      : 'bg-white/[0.03] border-white/[0.08]'
                  }`}
                >
                  <span
                    className={`text-[10px] font-bold block uppercase tracking-wider ${
                      isCurrent ? 'text-cyan-300' : 'text-slate-400'
                    }`}
                  >
                    {m.hourLabel}
                  </span>
                  <span className="text-xs font-bold font-mono text-white block mt-0.5">
                    {convertPressure(m.pressure, preferences.pressureUnit).value.toFixed(
                      preferences.pressureUnit === 'inHg' ? 2 : 1
                    )}
                  </span>
                  <span
                    className={`text-[10px] font-mono block mt-0.5 ${
                      m.deltaFromStart > 0.2
                        ? 'text-emerald-400 font-semibold'
                        : m.deltaFromStart < -0.2
                        ? 'text-rose-400 font-semibold'
                        : 'text-slate-400'
                    }`}
                  >
                    {idx === 0 ? 'Baseline' : `${dSign}${m.deltaFromStart.toFixed(1)} hPa`}
                  </span>
                </div>
              );
            })}
          </div>
        </div>

        {/* Meteorological Characteristic & Advisory */}
        <div className="mt-3 p-2.5 rounded-xl bg-white/[0.03] border border-white/[0.06] text-xs">
          <div className="flex items-center space-x-1.5 text-slate-200 font-semibold mb-1">
            <span
              className={`w-2 h-2 rounded-full ${
                trend3h.trend === 'rising'
                  ? 'bg-emerald-400 shadow-[0_0_8px_#34d399]'
                  : trend3h.trend === 'falling'
                  ? 'bg-rose-400 shadow-[0_0_8px_#f43f5e]'
                  : 'bg-cyan-400 shadow-[0_0_8px_#38bdf8]'
              }`}
            />
            <span className="text-slate-300 text-[11px] font-mono">{trend3h.characteristicLabel}</span>
          </div>
          <p className="text-[11px] text-slate-300 leading-relaxed font-sans">
            {trend3h.description}
          </p>
        </div>

        {/* Interactive 3-Hour Trend Quick Presets */}
        {onSimulateTrendScenario && (
          <div className="mt-3 pt-2.5 border-t border-white/[0.08] flex items-center justify-between">
            <span className="text-[10px] text-slate-400 uppercase font-mono">Test 3h Trend:</span>
            <div className="flex space-x-1.5">
              <button
                onClick={() => onSimulateTrendScenario('rising')}
                className={`px-2.5 py-1 rounded-lg border text-[10px] font-semibold flex items-center space-x-1 transition-all ${
                  trend3h.trend === 'rising'
                    ? 'bg-emerald-500/30 border-emerald-400 text-emerald-200 font-bold shadow-[0_0_8px_rgba(52,211,153,0.3)]'
                    : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
                }`}
              >
                <ArrowUp className="w-3 h-3 text-emerald-400" />
                <span>Rising (+2.1)</span>
              </button>
              <button
                onClick={() => onSimulateTrendScenario('steady')}
                className={`px-2.5 py-1 rounded-lg border text-[10px] font-semibold flex items-center space-x-1 transition-all ${
                  trend3h.trend === 'steady'
                    ? 'bg-cyan-500/30 border-cyan-400 text-cyan-200 font-bold shadow-[0_0_8px_rgba(56,189,248,0.3)]'
                    : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
                }`}
              >
                <MoveRight className="w-3 h-3 text-cyan-400" />
                <span>Steady (0.0)</span>
              </button>
              <button
                onClick={() => onSimulateTrendScenario('falling')}
                className={`px-2.5 py-1 rounded-lg border text-[10px] font-semibold flex items-center space-x-1 transition-all ${
                  trend3h.trend === 'falling'
                    ? 'bg-rose-500/30 border-rose-400 text-rose-200 font-bold shadow-[0_0_8px_rgba(244,63,94,0.3)]'
                    : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
                }`}
              >
                <ArrowDown className="w-3 h-3 text-rose-400" />
                <span>Falling (-2.4)</span>
              </button>
            </div>
          </div>
        )}
      </GlassCard>

      {/* Weather Tendency & Trend Insights Card */}
      <GlassCard className="w-full !p-4">
        <div className="flex items-start justify-between">
          <div className="flex items-center space-x-2">
            <div className={`p-2 rounded-2xl border ${badge.color}`}>
              {badge.icon}
            </div>
            <div>
              <div className="text-xs font-bold text-slate-100 flex items-center space-x-1">
                <span>{sensors.weatherTendency.label}</span>
              </div>
              <div className="text-[11px] text-slate-400 flex items-center space-x-1 mt-0.5 font-mono">
                {badge.trendIcon}
                <span>
                  {sensors.pressureTrendRate >= 0 ? `+${sensors.pressureTrendRate}` : sensors.pressureTrendRate} hPa/hr
                </span>
              </div>
            </div>
          </div>

          <div className="text-right">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider block">Station Pressure</span>
            <span className="text-sm font-bold font-mono text-cyan-400">
              {sensors.pressure.toFixed(1)} hPa
            </span>
          </div>
        </div>

        {sensors.weatherTendency.category === 'storm' && (
          <div className="mt-2.5 p-2 rounded-xl bg-rose-500/20 border border-rose-500/40 flex items-center space-x-2 text-rose-300 text-xs">
            <AlertTriangle className="w-4 h-4 text-rose-400 flex-shrink-0" />
            <span>Severe drop alert: High risk of sudden mountain gale or squall within 1-3 hrs.</span>
          </div>
        )}

        <p className="text-xs text-slate-300 mt-2.5 leading-relaxed bg-white/[0.03] p-2.5 rounded-2xl border border-white/[0.05]">
          {sensors.weatherTendency.description}
        </p>

        {/* 3-Hour History Interactive SVG Sparkline */}
        <div className="mt-3 pt-3 border-t border-white/[0.08]">
          <div className="flex justify-between items-center text-[10px] text-slate-400 mb-1">
            <span>Barometric Trend (Recent Hours)</span>
            <span className="font-mono text-cyan-400">
              {hoveredPoint ? `${hoveredPoint.pressure.toFixed(1)} hPa • ${hoveredPoint.altitude.toFixed(0)}m` : `${history.length} snapshots`}
            </span>
          </div>
          <div className="relative w-full h-16 bg-white/[0.02] rounded-xl border border-white/[0.05] p-1 flex items-center">
            <svg className="w-full h-full" viewBox="0 0 280 60" preserveAspectRatio="none">
              <defs>
                <linearGradient id="chartGradient" x1="0%" y1="0%" x2="0%" y2="1">
                  <stop offset="0%" stopColor="#38bdf8" stopOpacity="0.4" />
                  <stop offset="100%" stopColor="#38bdf8" stopOpacity="0.0" />
                </linearGradient>
              </defs>
              <polygon
                points={`0,60 ${chartPoints} 280,60`}
                fill="url(#chartGradient)"
              />
              <polyline
                fill="none"
                stroke="#38bdf8"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                points={chartPoints}
              />
              {/* Interactive nodes */}
              {history.map((pt, idx) => {
                const cx = (idx / Math.max(1, history.length - 1)) * 280;
                const cy = 60 - ((pt.pressure - minP) / rangeP) * 50;
                return (
                  <circle
                    key={pt.timestamp}
                    cx={cx}
                    cy={cy}
                    r={hoveredPoint === pt ? 4 : 2}
                    fill={hoveredPoint === pt ? '#f43f5e' : '#38bdf8'}
                    className="cursor-pointer transition-all"
                    onMouseEnter={() => setHoveredPoint(pt)}
                    onMouseLeave={() => setHoveredPoint(null)}
                  />
                );
              })}
            </svg>
          </div>
        </div>
      </GlassCard>

      {/* Atmospheric Physics & Outdoor Insights Grid */}
      <GlassCard className="w-full !p-3.5">
        <div className="text-[11px] font-semibold text-slate-300 uppercase tracking-wider mb-2 flex items-center space-x-1.5">
          <Info className="w-3.5 h-3.5 text-cyan-400" />
          <span>Atmospheric Physics & Density</span>
        </div>

        <div className="grid grid-cols-3 gap-2 text-center">
          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <div className="flex items-center justify-center space-x-1 text-[10px] text-slate-400">
              <Coffee className="w-3 h-3 text-amber-400" />
              <span>Boil Temp</span>
            </div>
            <div className="text-sm font-bold text-amber-300 font-mono mt-0.5">
              {boilingPoint.celsius}°C
            </div>
            <div className="text-[10px] text-slate-400 font-mono">{boilingPoint.fahrenheit}°F</div>
          </div>

          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <div className="flex items-center justify-center space-x-1 text-[10px] text-slate-400">
              <Wind className="w-3 h-3 text-cyan-400" />
              <span>Air Density</span>
            </div>
            <div className="text-sm font-bold text-cyan-300 font-mono mt-0.5">
              {airDensity}
            </div>
            <div className="text-[10px] text-slate-400">kg / m³</div>
          </div>

          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <div className="flex items-center justify-center space-x-1 text-[10px] text-slate-400">
              <Sparkles className="w-3 h-3 text-emerald-400" />
              <span>Density Alt</span>
            </div>
            <div className="text-sm font-bold text-emerald-300 font-mono mt-0.5">
              {densityAltitudeFt}
            </div>
            <div className="text-[10px] text-slate-400">ft (ISA)</div>
          </div>
        </div>

        {sensors.gpsAltitude !== null && (
          <div className="mt-2.5 pt-2.5 border-t border-white/[0.06] flex items-center justify-between text-xs px-1">
            <div className="flex items-center space-x-1.5 text-slate-400">
              <Satellite className="w-3.5 h-3.5 text-cyan-400" />
              <span>GPS Satellite Elevation:</span>
            </div>
            <span className="font-mono font-semibold text-slate-200">
              {gpsAltitudeDisplay?.value.toFixed(1)} {gpsAltitudeDisplay?.label}
            </span>
          </div>
        )}
      </GlassCard>

      {/* Simulator Drawer (Adjust altitude / pressure manually) */}
      {showSimulation && (
        <GlassCard className="w-full !p-4 border-cyan-500/30 bg-cyan-950/20">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center space-x-2">
              <Sliders className="w-4 h-4 text-cyan-400" />
              <span className="text-xs font-bold text-cyan-300 uppercase tracking-wider">
                Pressure & Altitude Simulator
              </span>
            </div>
            <span className="text-[11px] text-cyan-400 font-mono">Interactive</span>
          </div>

          <div className="space-y-3 text-xs">
            <div>
              <div className="flex justify-between mb-1 text-slate-300">
                <span>Station Pressure (hPa)</span>
                <span className="font-mono text-cyan-400">{sensors.pressure.toFixed(1)} hPa</span>
              </div>
              <input
                type="range"
                min="940"
                max="1060"
                step="0.5"
                value={sensors.pressure}
                onChange={(e) => onSetManualPressure(Number(e.target.value))}
                className="w-full h-1.5 bg-white/20 rounded-lg appearance-none cursor-pointer accent-cyan-400"
              />
            </div>

            <div className="flex items-center justify-between pt-1">
              <span className="text-[11px] text-slate-400">Quick Presets:</span>
              <div className="flex space-x-1.5">
                <button
                  onClick={() => onSetManualPressure(980)}
                  className="px-2 py-1 rounded-lg bg-rose-500/20 border border-rose-500/40 text-[10px] text-rose-300"
                >
                  Storm (980 hPa)
                </button>
                <button
                  onClick={() => onSetManualPressure(1013.25)}
                  className="px-2 py-1 rounded-lg bg-white/10 border border-white/20 text-[10px] text-slate-300"
                >
                  Sea Level (1013 hPa)
                </button>
                <button
                  onClick={() => onSetManualPressure(1030)}
                  className="px-2 py-1 rounded-lg bg-emerald-500/20 border border-emerald-500/40 text-[10px] text-emerald-300"
                >
                  High (1030 hPa)
                </button>
              </div>
            </div>

            {onSimulateTrendScenario && (
              <div className="pt-2 border-t border-cyan-500/20">
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-[11px] text-slate-300 font-semibold">3-Hour Barometric Tendency:</span>
                  <span className="text-[10px] font-mono text-cyan-300 uppercase">{trend3h.trend}</span>
                </div>
                <div className="grid grid-cols-3 gap-1.5">
                  <button
                    onClick={() => onSimulateTrendScenario('rising')}
                    className="py-1.5 px-2 rounded-lg bg-emerald-500/20 hover:bg-emerald-500/30 border border-emerald-500/40 text-[10px] font-semibold text-emerald-300 flex items-center justify-center space-x-1"
                  >
                    <ArrowUp className="w-3 h-3" />
                    <span>Rising</span>
                  </button>
                  <button
                    onClick={() => onSimulateTrendScenario('steady')}
                    className="py-1.5 px-2 rounded-lg bg-white/10 hover:bg-white/20 border border-white/20 text-[10px] font-semibold text-slate-200 flex items-center justify-center space-x-1"
                  >
                    <MoveRight className="w-3 h-3 text-cyan-400" />
                    <span>Steady</span>
                  </button>
                  <button
                    onClick={() => onSimulateTrendScenario('falling')}
                    className="py-1.5 px-2 rounded-lg bg-rose-500/20 hover:bg-rose-500/30 border border-rose-500/40 text-[10px] font-semibold text-rose-300 flex items-center justify-center space-x-1"
                  >
                    <ArrowDown className="w-3 h-3" />
                    <span>Falling</span>
                  </button>
                </div>
              </div>
            )}
          </div>
        </GlassCard>
      )}

      {/* QNH Calibration Modal */}
      <Modal isOpen={showQnhModal} onClose={() => setShowQnhModal(false)} title="Calibrate Sea-Level QNH" size="sm" icon={<Gauge className="w-5 h-5 text-cyan-400" />}>
              <p className="text-xs text-slate-300 mb-4 leading-relaxed">
                Altimeters measure elevation by comparing station pressure with reference sea-level pressure (QNH). Set the current local QNH from airport reports or weather stations.
              </p>

              <div className="space-y-3">
                <div>
                  <div className="flex justify-between text-xs text-slate-300 mb-1">
                    <span>Reference Pressure</span>
                    <span className="font-mono text-cyan-400 font-bold">{sensors.qnh.toFixed(2)} hPa</span>
                  </div>
                  <input
                    type="range"
                    min="960"
                    max="1060"
                    step="0.25"
                    value={sensors.qnh}
                    onChange={(e) => onSetQnh(Number(e.target.value))}
                    className="w-full h-2 bg-white/20 rounded-lg appearance-none cursor-pointer accent-cyan-400"
                  />
                </div>

                <div className="grid grid-cols-2 gap-2 pt-2">
                  <button
                    onClick={() => {
                      onSetQnh(1013.25);
                      setShowQnhModal(false);
                    }}
                    className="py-2 px-3 rounded-xl bg-white/10 hover:bg-white/20 border border-white/15 text-xs text-slate-200"
                  >
                    Standard (1013.25)
                  </button>

                  {sensors.gpsAltitude !== null && (
                    <button
                      onClick={() => {
                        onCalibrateToGps();
                        setShowQnhModal(false);
                      }}
                      className="py-2 px-3 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-xs font-semibold text-slate-950"
                    >
                      Use GPS Elevation
                    </button>
                  )}
                </div>
              </div>
      </Modal>
    </div>
  );
};
