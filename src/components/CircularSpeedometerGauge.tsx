import React, { useState, useEffect, useMemo } from 'react';
import { AltitudeUnit, SpeedUnit } from '../types/sensors';
import { convertSpeedBetweenUnits } from '../utils/calculations';
import { GlassCard } from './GlassCard';
import { CardHeader } from './Layout';
import {
  Gauge,
  Zap,
  TrendingUp,
  Activity,
  Sliders,
  RotateCcw,
  Compass,
  Footprints,
  Satellite,
  Navigation,
  AlertTriangle,
  ShieldAlert,
} from 'lucide-react';

export type { SpeedUnit };

interface Props {
  gpsSpeedMs: number | null;
  gpsAccuracy: number | null;
  isGpsAvailable: boolean;
  isSimulationMode: boolean;
  altitudeUnit: AltitudeUnit;
  speedUnit?: SpeedUnit;
  onUpdateSpeedUnit?: (unit: SpeedUnit) => void;
  speedAlertEnabled?: boolean;
  speedAlertThreshold?: number;
  speedAlertVisual?: boolean;
  onSetSimulationSpeed?: (speedMs: number) => void;
}

export const CircularSpeedometerGauge: React.FC<Props> = ({
  gpsSpeedMs,
  gpsAccuracy,
  isGpsAvailable,
  isSimulationMode,
  altitudeUnit,
  speedUnit: propSpeedUnit,
  onUpdateSpeedUnit,
  speedAlertEnabled = false,
  speedAlertThreshold = 25,
  speedAlertVisual = true,
  onSetSimulationSpeed,
}) => {
  // Speed unit state initialized from props or altitudeUnit (ft -> mph, m -> km/h)
  const [internalSpeedUnit, setInternalSpeedUnit] = useState<SpeedUnit>(
    () => propSpeedUnit || (altitudeUnit === 'ft' ? 'mph' : 'km/h')
  );

  const speedUnit = propSpeedUnit || internalSpeedUnit;

  const handleUnitChange = (newUnit: SpeedUnit) => {
    setInternalSpeedUnit(newUnit);
    onUpdateSpeedUnit?.(newUnit);
  };

  const [sessionPeakSpeedMs, setSessionPeakSpeedMs] = useState<number>(0);
  const [showSimControls, setShowSimControls] = useState<boolean>(false);

  // Normalize current speed (m/s)
  const currentSpeedMs = Math.max(0, gpsSpeedMs ?? 0);

  // Update session peak speed
  useEffect(() => {
    if (currentSpeedMs > sessionPeakSpeedMs) {
      setSessionPeakSpeedMs(currentSpeedMs);
    }
  }, [currentSpeedMs, sessionPeakSpeedMs]);

  // Convert m/s to chosen unit
  const convertSpeed = (ms: number, unit: SpeedUnit): number => {
    switch (unit) {
      case 'mph':
        return ms * 2.236936;
      case 'kt':
        return ms * 1.943844;
      case 'm/s':
        return ms;
      case 'km/h':
      default:
        return ms * 3.6;
    }
  };

  const currentDisplaySpeed = convertSpeed(currentSpeedMs, speedUnit);
  const peakDisplaySpeed = convertSpeed(sessionPeakSpeedMs, speedUnit);

  // Convert speed threshold to active speedUnit if configured
  const thresholdInGaugeUnit = useMemo(() => {
    if (!speedAlertThreshold) return null;
    return convertSpeedBetweenUnits(speedAlertThreshold, propSpeedUnit ?? 'km/h', speedUnit);
  }, [speedAlertThreshold, propSpeedUnit, speedUnit]);

  // Determine if currently over speed limit
  const isOverspeed = Boolean(
    speedAlertEnabled &&
    thresholdInGaugeUnit !== null &&
    currentDisplaySpeed > thresholdInGaugeUnit
  );
  const overspeedDelta = thresholdInGaugeUnit !== null ? Math.max(0, currentDisplaySpeed - thresholdInGaugeUnit) : 0;

  // Determine scale maximum based on chosen unit and current speed
  const maxScale = useMemo(() => {
    switch (speedUnit) {
      case 'mph':
        return currentDisplaySpeed > 80 ? 140 : 90;
      case 'kt':
        return currentDisplaySpeed > 70 ? 120 : 80;
      case 'm/s':
        return currentDisplaySpeed > 35 ? 60 : 40;
      case 'km/h':
      default:
        return currentDisplaySpeed > 130 ? 200 : 140;
    }
  }, [speedUnit, currentDisplaySpeed]);

  // Gauge angles: 240 degree total sweep (-120° to +120°)
  // -120° at speed 0, +120° at maxScale
  const clampedSpeed = Math.min(maxScale, currentDisplaySpeed);
  const needleAngle = -120 + (clampedSpeed / maxScale) * 240;

  // Pace calculation (e.g., min:sec per km or mile)
  const paceFormatted = useMemo(() => {
    if (currentSpeedMs < 0.3) return '—';
    // seconds per km = 1000 / ms, seconds per mile = 1609.34 / ms
    const metersPerUnit = speedUnit === 'mph' ? 1609.34 : 1000;
    const paceSeconds = metersPerUnit / currentSpeedMs;
    if (paceSeconds > 3600) return '—';
    const mins = Math.floor(paceSeconds / 60);
    const secs = Math.floor(paceSeconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')} /${speedUnit === 'mph' ? 'mi' : 'km'}`;
  }, [currentSpeedMs, speedUnit]);

  // Activity classification
  const activityCategory = useMemo(() => {
    const kmh = currentSpeedMs * 3.6;
    if (kmh < 0.8) return { label: 'Stationary / Rest', color: 'text-slate-400 bg-white/10 border-white/15' };
    if (kmh < 6.5) return { label: 'Walking / Trekking', color: 'text-emerald-300 bg-emerald-500/15 border-emerald-500/30' };
    if (kmh < 18.0) return { label: 'Running / Jogging', color: 'text-cyan-300 bg-cyan-500/15 border-cyan-500/30' };
    if (kmh < 40.0) return { label: 'Cycling / Sprint', color: 'text-amber-300 bg-amber-500/15 border-amber-500/30' };
    if (kmh < 90.0) return { label: 'Vehicle Transit', color: 'text-blue-300 bg-blue-500/15 border-blue-500/30' };
    return { label: 'High Speed Velocity', color: 'text-rose-300 bg-rose-500/20 border-rose-500/40 animate-pulse' };
  }, [currentSpeedMs]);

  // Major ticks division
  const tickStep = maxScale <= 50 ? 5 : maxScale <= 100 ? 10 : 20;
  const tickCount = Math.floor(maxScale / tickStep);
  const majorTicks = Array.from({ length: tickCount + 1 }, (_, i) => i * tickStep);

  return (
    <GlassCard className="w-full !p-4">
      {/* Top Header & Unit Selector */}
      <CardHeader
        icon={<Gauge className="w-4 h-4 text-cyan-400 shrink-0" />}
        title={
          <span className="flex items-center gap-1.5">
            <span>GPS Speedometer</span>
            <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-ping" />
          </span>
        }
        subtitle="Real-time velocity • Satellite telemetry"
      >
          <div className="flex bg-white/10 rounded-full p-0.5 border border-white/15 text-[10px] font-mono">
            {(['km/h', 'mph', 'kt', 'm/s'] as const).map((unit) => (
              <button
                key={unit}
                onClick={() => handleUnitChange(unit)}
                className={`px-2 py-0.5 rounded-full transition-all ${
                  speedUnit === unit
                    ? 'bg-cyan-500 text-slate-950 font-bold shadow'
                    : 'text-slate-300 hover:text-white'
                }`}
              >
                {unit}
              </button>
            ))}
          </div>

          <button
            onClick={() => setShowSimControls(!showSimControls)}
            title="Adjust simulated velocity"
            className={`p-1.5 rounded-full border transition-all ${
              showSimControls
                ? 'bg-cyan-500/20 border-cyan-500/50 text-cyan-300 shadow-[0_0_10px_rgba(56,189,248,0.3)]'
                : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
            }`}
          >
            <Sliders className="w-3 h-3" />
          </button>
      </CardHeader>


      {/* Main Circular Speedometer Gauge Dial Container */}
      <div className="relative w-64 h-64 mx-auto flex items-center justify-center select-none my-1">
        {/* Outer Liquid Glass Bezel Glow (Flashing Crimson during overspeed) */}
        <div
          className={`absolute inset-0 rounded-full blur-xl pointer-events-none transition-all duration-300 ${
            isOverspeed && speedAlertVisual
              ? 'bg-rose-500/35 animate-pulse'
              : 'bg-gradient-to-tr from-cyan-500/10 via-teal-500/10 to-indigo-500/15'
          }`}
        />
        <div
          className={`absolute inset-0 rounded-full border transition-all duration-300 pointer-events-none ${
            isOverspeed && speedAlertVisual
              ? 'border-rose-500/80 bg-rose-950/20 shadow-[0_0_35px_rgba(244,63,94,0.65)] animate-pulse'
              : 'border-white/20 bg-gradient-to-b from-white/[0.08] to-white/[0.02] shadow-[0_10px_35px_rgba(0,0,0,0.5),inset_0_2px_4px_rgba(255,255,255,0.25)]'
          }`}
        />

        {/* Gauge SVG Face */}
        <svg className="absolute inset-0 w-full h-full" viewBox="0 0 260 260">
          <defs>
            <radialGradient id="speedGlassGrad" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="rgba(255,255,255,0.03)" />
              <stop offset="80%" stopColor="rgba(255,255,255,0.01)" />
              <stop offset="100%" stopColor="rgba(0,0,0,0.45)" />
            </radialGradient>
            <linearGradient id="speedArcGrad" x1="0%" y1="100%" x2="100%" y2="0%">
              <stop offset="0%" stopColor="#38bdf8" />
              <stop offset="35%" stopColor="#34d399" />
              <stop offset="70%" stopColor="#fbbf24" />
              <stop offset="100%" stopColor="#f43f5e" />
            </linearGradient>
            <linearGradient id="speedNeedleGrad" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stopColor="#38bdf8" />
              <stop offset="100%" stopColor="#0284c7" />
            </linearGradient>
          </defs>

          {/* Dial Background circle */}
          <circle cx="130" cy="130" r="120" fill="url(#speedGlassGrad)" />
          <circle cx="130" cy="130" r="120" fill="none" stroke="rgba(255,255,255,0.12)" strokeWidth="1" />

          {/* Background Track Arc (-120° to +120°: 240° sweep) */}
          <path
            d="M 45,200 A 98 98 0 1 1 215,200"
            fill="none"
            stroke="rgba(255,255,255,0.08)"
            strokeWidth="8"
            strokeLinecap="round"
          />

          {/* Progressive Speed Zones */}
          <path
            d="M 45,200 A 98 98 0 0 1 72,82"
            fill="none"
            stroke="rgba(56,189,248,0.3)"
            strokeWidth="5"
            strokeLinecap="round"
          />
          <path
            d="M 75,79 A 98 98 0 0 1 185,79"
            fill="none"
            stroke="rgba(52,211,153,0.3)"
            strokeWidth="5"
            strokeLinecap="round"
          />
          <path
            d="M 188,82 A 98 98 0 0 1 215,200"
            fill="none"
            stroke="rgba(244,63,94,0.3)"
            strokeWidth="5"
            strokeLinecap="round"
          />

          {/* Radial Ticks & Numeric Scale */}
          {majorTicks.map((val) => {
            // angle from -120° to +120°, mapped relative to 12 o'clock (-90°)
            const angleDeg = -120 + (val / maxScale) * 240 - 90;
            const rad = (angleDeg * Math.PI) / 180;
            const x1 = 130 + 104 * Math.cos(rad);
            const y1 = 130 + 104 * Math.sin(rad);
            const x2 = 130 + 114 * Math.cos(rad);
            const y2 = 130 + 114 * Math.sin(rad);

            // Numbers positioned inward
            const textX = 130 + 88 * Math.cos(rad);
            const textY = 130 + 88 * Math.sin(rad);

            return (
              <g key={val}>
                <line
                  x1={x1}
                  y1={y1}
                  x2={x2}
                  y2={y2}
                  stroke={val === 0 ? '#38bdf8' : 'rgba(255,255,255,0.6)'}
                  strokeWidth="2"
                  strokeLinecap="round"
                />
                <text
                  x={textX}
                  y={textY}
                  fill="rgba(255,255,255,0.55)"
                  fontSize="8.5"
                  fontFamily="monospace"
                  fontWeight="600"
                  textAnchor="middle"
                  dominantBaseline="central"
                >
                  {val}
                </text>
              </g>
            );
          })}

          {/* Minor intermediate ticks */}
          {Array.from({ length: 49 }).map((_, i) => {
            const frac = i / 48;
            const angleDeg = -120 + frac * 240 - 90;
            const rad = (angleDeg * Math.PI) / 180;
            const x1 = 130 + 108 * Math.cos(rad);
            const y1 = 130 + 108 * Math.sin(rad);
            const x2 = 130 + 114 * Math.cos(rad);
            const y2 = 130 + 114 * Math.sin(rad);
            return (
              <line
                key={i}
                x1={x1}
                y1={y1}
                x2={x2}
                y2={y2}
                stroke="rgba(255,255,255,0.2)"
                strokeWidth="1"
              />
            );
          })}

          {/* Speed Limit Threshold Marker Notch & Label */}
          {speedAlertEnabled && thresholdInGaugeUnit !== null && thresholdInGaugeUnit <= maxScale && (
            <g key="speed-limit-marker">
              {(() => {
                const angleDeg = -120 + (thresholdInGaugeUnit / maxScale) * 240 - 90;
                const rad = (angleDeg * Math.PI) / 180;
                const x1 = 130 + 100 * Math.cos(rad);
                const y1 = 130 + 100 * Math.sin(rad);
                const x2 = 130 + 118 * Math.cos(rad);
                const y2 = 130 + 118 * Math.sin(rad);
                const textX = 130 + 74 * Math.cos(rad);
                const textY = 130 + 74 * Math.sin(rad);

                return (
                  <>
                    <line
                      x1={x1}
                      y1={y1}
                      x2={x2}
                      y2={y2}
                      stroke="#f43f5e"
                      strokeWidth="3.5"
                      strokeLinecap="round"
                    />
                    <text
                      x={textX}
                      y={textY}
                      fill="#f43f5e"
                      fontSize="7"
                      fontFamily="monospace"
                      fontWeight="bold"
                      textAnchor="middle"
                      dominantBaseline="central"
                    >
                      LIMIT
                    </text>
                  </>
                );
              })()}
            </g>
          )}
        </svg>

        {/* Central Speedometer Liquid Core Readout */}
        <div
          className={`absolute z-20 w-36 h-36 rounded-full border transition-all duration-300 flex flex-col items-center justify-center text-center p-2 ${
            isOverspeed && speedAlertVisual
              ? 'border-rose-400 bg-rose-950/40 shadow-[inset_0_2px_12px_rgba(244,63,94,0.5),0_8px_25px_rgba(244,63,94,0.4)] animate-pulse'
              : 'border-white/20 bg-gradient-to-b from-white/[0.14] to-white/[0.04] shadow-[inset_0_2px_8px_rgba(0,0,0,0.5),0_8px_20px_rgba(0,0,0,0.4)]'
          }`}
        >
          {/* Movement Indicator Icon */}
          <div className="flex items-center space-x-1 text-cyan-400 mb-0.5">
            {isOverspeed && speedAlertVisual ? (
              <AlertTriangle className="w-3.5 h-3.5 text-rose-400 animate-bounce" />
            ) : (
              <Zap className="w-3.5 h-3.5 fill-cyan-400/30" />
            )}
            <span
              className={`text-[10px] font-bold uppercase tracking-wider ${
                isOverspeed && speedAlertVisual ? 'text-rose-300' : 'text-slate-300'
              }`}
            >
              {isOverspeed && speedAlertVisual ? 'Overspeed' : 'Velocity'}
            </span>
          </div>

          {/* Primary Speed Value */}
          <div
            className={`text-3xl font-black font-mono tracking-tight transition-colors ${
              isOverspeed && speedAlertVisual
                ? 'text-rose-300 drop-shadow-[0_2px_14px_rgba(244,63,94,0.8)]'
                : 'text-white drop-shadow-[0_2px_12px_rgba(56,189,248,0.35)]'
            }`}
          >
            {currentDisplaySpeed.toFixed(1)}
          </div>

          {/* Unit Label */}
          <div
            className={`text-[11px] font-bold font-mono tracking-widest uppercase ${
              isOverspeed && speedAlertVisual ? 'text-rose-400' : 'text-cyan-400'
            }`}
          >
            {speedUnit}
          </div>

          {/* Activity State or Overspeed Warning Badge */}
          <div className="mt-1">
            {isOverspeed && speedAlertVisual ? (
              <span className="text-[10px] font-bold px-2 py-0.5 rounded-full border border-rose-400/50 bg-rose-500/30 text-rose-200 flex items-center space-x-0.5 shadow-[0_0_8px_rgba(244,63,94,0.4)] font-mono">
                <span>LIMIT +{overspeedDelta.toFixed(1)}</span>
              </span>
            ) : (
              <span className={`text-[8.5px] font-semibold px-2 py-0.5 rounded-full border ${activityCategory.color}`}>
                {activityCategory.label}
              </span>
            )}
          </div>
        </div>

        {/* Rotating Speedometer Pointer Needle */}
        <div
          className="absolute inset-0 pointer-events-none transition-transform duration-200 ease-out flex items-center justify-center"
          style={{ transform: `rotate(${needleAngle}deg)` }}
        >
          <div className="relative w-full h-full flex items-center justify-center">
            {/* Glowing pointer needle bar */}
            <div
              className={`absolute top-9 w-1.5 h-24 rounded-full transition-all ${
                isOverspeed && speedAlertVisual
                  ? 'bg-gradient-to-t from-rose-500 via-rose-400 to-white shadow-[0_0_16px_#f43f5e]'
                  : 'bg-gradient-to-t from-cyan-400 via-cyan-300 to-white shadow-[0_0_12px_#38bdf8]'
              }`}
            />
            {/* Needle center cap hub */}
            <div
              className={`w-4 h-4 rounded-full border-2 border-white shadow-[0_0_8px_#38bdf8] z-30 transition-all ${
                isOverspeed && speedAlertVisual
                  ? 'bg-rose-500 shadow-[0_0_10px_#f43f5e]'
                  : 'bg-cyan-400 shadow-[0_0_8px_#38bdf8]'
              }`}
            />
          </div>
        </div>
      </div>

      {/* Speedometer Telemetry Strip (Peak, Pace, Accuracy) */}
      <div className="grid grid-cols-3 gap-2 text-center text-xs font-mono mt-3">
        {/* Peak Speed */}
        <div className="p-2 rounded-xl bg-white/[0.03] border border-white/[0.06]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Peak Velocity</span>
          <span className="text-sm font-bold text-cyan-300">
            {peakDisplaySpeed.toFixed(1)} {speedUnit}
          </span>
        </div>

        {/* Pace (Min/km or Min/mi) */}
        <div className="p-2 rounded-xl bg-white/[0.03] border border-white/[0.06]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">Current Pace</span>
          <span className="text-sm font-bold text-emerald-300">
            {paceFormatted}
          </span>
        </div>

        {/* GPS Fix / Accuracy */}
        <div className="p-2 rounded-xl bg-white/[0.03] border border-white/[0.06]">
          <span className="text-[10px] text-slate-400 uppercase font-sans block">GPS Accuracy</span>
          <span className="text-sm font-bold text-amber-300 flex items-center justify-center space-x-1">
            <Satellite className="w-3 h-3 text-amber-400" />
            <span>±{gpsAccuracy ?? 8}m</span>
          </span>
        </div>
      </div>

      {/* Simulator Drawer for testing velocity */}
      {showSimControls && (
        <div className="mt-3 pt-3 border-t border-cyan-500/20 bg-cyan-950/20 p-3 rounded-2xl">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-bold text-cyan-300 uppercase tracking-wider flex items-center space-x-1.5">
              <Sliders className="w-3.5 h-3.5 text-cyan-400" />
              <span>Simulate GPS Velocity</span>
            </span>
            <span className="text-[11px] font-mono text-cyan-400">
              {currentDisplaySpeed.toFixed(1)} {speedUnit}
            </span>
          </div>

          {/* Speed slider */}
          <input
            type="range"
            min="0"
            max={maxScale}
            step="0.5"
            value={currentDisplaySpeed}
            onChange={(e) => {
              const val = Number(e.target.value);
              // convert back to m/s
              const ms = speedUnit === 'mph' ? val / 2.236936 : speedUnit === 'kt' ? val / 1.943844 : speedUnit === 'm/s' ? val : val / 3.6;
              onSetSimulationSpeed?.(ms);
            }}
            className="w-full h-1.5 bg-white/20 rounded-lg appearance-none cursor-pointer accent-cyan-400 mb-2.5"
          />

          {/* Quick presets */}
          <div className="flex items-center justify-between text-[10px]">
            <span className="text-slate-400">Presets:</span>
            <div className="flex space-x-1">
              {[
                { label: '0 (Stop)', kmh: 0 },
                { label: '5 (Walk)', kmh: 5 },
                { label: '25 (Bike)', kmh: 25 },
                { label: '65 (Cruise)', kmh: 65 },
                { label: '120 (Fast)', kmh: 120 },
              ].map((p) => (
                <button
                  key={p.label}
                  onClick={() => onSetSimulationSpeed?.(p.kmh / 3.6)}
                  className="px-2 py-0.5 rounded-lg bg-white/10 hover:bg-white/20 border border-white/15 text-slate-300 font-mono transition-all"
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </GlassCard>
  );
};
