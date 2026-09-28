import React, { useState } from 'react';
import { SensorState, UserPreferences, TrackSession, Waypoint } from '../types/sensors';
import { formatToDMS } from '../utils/calculations';
import { useAcousticSensor } from '../hooks/useAcousticSensor';
import { GlassCard } from './GlassCard';
import { TrackElevationProfileChart } from './TrackElevationProfileChart';
import {
  Activity,
  Magnet,
  Sun,
  Compass,
  Zap,
  RotateCw,
  Locate,
  AlertCircle,
  ShieldCheck,
  RotateCcw,
  Mic,
  MicOff,
  Volume2,
} from 'lucide-react';

interface Props {
  sensors: SensorState;
  preferences: UserPreferences;
  trackSession: TrackSession;
  onOpenCalibration: () => void;
  onAddWaypoint?: (wp: Omit<Waypoint, 'id' | 'timestamp'>) => void;
}

export const SensorMatrixView: React.FC<Props> = ({
  sensors,
  preferences,
  trackSession,
  onOpenCalibration,
  onAddWaypoint,
}) => {
  const [peakG, setPeakG] = useState<number>(1.0);
  const [enableMic, setEnableMic] = useState<boolean>(false);

  const { state: acoustic, startListening, stopListening, resetPeak: resetPeakAcoustic } = useAcousticSensor(enableMic);

  // Track peak G
  if (sensors.gForce > peakG) {
    setPeakG(sensors.gForce);
  }

  // Determine light condition label
  const getLightLevelText = (lux: number) => {
    if (lux < 1) return { label: 'Pitch Black / Night', category: 'dark' };
    if (lux < 50) return { label: 'Dim Interior / Twilight', category: 'dim' };
    if (lux < 300) return { label: 'Standard Home Lighting', category: 'normal' };
    if (lux < 800) return { label: 'Office / Workspace', category: 'bright' };
    if (lux < 10000) return { label: 'Overcast Daylight', category: 'daylight' };
    return { label: 'Direct Sunlight', category: 'sun' };
  };

  const lightInfo = getLightLevelText(sensors.ambientLight);

  return (
    <div className="flex flex-col items-center w-full max-w-md mx-auto space-y-4 pb-20">
      {/* Header */}
      <div className="w-full px-2 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-white tracking-tight flex items-center space-x-2">
            <Activity className="w-5 h-5 text-cyan-400" />
            <span>Sensor Matrix</span>
          </h2>
          <p className="text-xs text-slate-400">Live hardware telemetry, physics & elevation profile</p>
        </div>

        <div className="flex items-center space-x-1.5 px-2.5 py-1 rounded-full bg-white/10 border border-white/15 text-[11px] text-emerald-300">
          <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
          <span>Active</span>
        </div>
      </div>

      {/* 1. Recharts Track Elevation Profile Visualization */}
      <TrackElevationProfileChart
        trackSession={trackSession}
        preferences={preferences}
        currentAltitude={sensors.barometricAltitude}
        sensors={sensors}
        onAddWaypoint={onAddWaypoint}
      />

      {/* 2. Magnetic Field & Anomaly Detector (Gaussmeter) */}
      <GlassCard className="w-full !p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center space-x-2">
            <Magnet className="w-4 h-4 text-cyan-400" />
            <span className="text-xs font-bold text-white uppercase tracking-wider">
              Magnetic Flux Density
            </span>
          </div>

          <div className="flex items-center space-x-1.5">
            <button
              onClick={onOpenCalibration}
              className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-white/10 hover:bg-white/20 border border-white/15 text-slate-200 transition-all"
            >
              Calibrate
            </button>
            {sensors.magneticAnomaly ? (
              <span className="flex items-center space-x-1 text-[11px] font-semibold text-rose-400 bg-rose-500/15 px-2 py-0.5 rounded-full border border-rose-500/30">
                <AlertCircle className="w-3 h-3" />
                <span>Ferromagnetic Alert</span>
              </span>
            ) : (
              <span className="text-[11px] font-mono text-emerald-400 bg-emerald-500/15 px-2 py-0.5 rounded-full border border-emerald-500/30">
                Normal Earth Field
              </span>
            )}
          </div>
        </div>

        <div className="flex items-baseline justify-between mb-2">
          <div className="text-3xl font-black font-mono text-white">
            {sensors.magneticFlux.toFixed(1)}{' '}
            <span className="text-sm font-semibold text-cyan-400 font-sans">µT</span>
          </div>
          <span className="text-xs text-slate-400 font-mono">
            Nominal: 25 – 65 µT
          </span>
        </div>

        {/* Magnetic field flux progress bar */}
        <div className="w-full h-2 rounded-full bg-white/10 overflow-hidden relative mb-3">
          <div
            className={`h-full transition-all duration-300 rounded-full ${
              sensors.magneticFlux > 75
                ? 'bg-gradient-to-r from-amber-400 to-rose-500'
                : 'bg-gradient-to-r from-teal-400 to-cyan-400'
            }`}
            style={{ width: `${Math.min(100, (sensors.magneticFlux / 100) * 100)}%` }}
          />
        </div>

        <p className="text-[11px] text-slate-300 bg-white/[0.03] p-2.5 rounded-xl border border-white/[0.05] leading-relaxed">
          {sensors.magneticAnomaly
            ? 'High magnetic field detected. Nearby metals, electric cables, or car frames may distort compass bearings. Tap "Calibrate" to run figure-8 motion.'
            : 'Magnetic field is nominal. Compass readings have high confidence and low interference.'}
        </p>
      </GlassCard>

      {/* 3. Kinematics & Accelerometer G-Force */}
      <GlassCard className="w-full !p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center space-x-2">
            <Zap className="w-4 h-4 text-amber-400" />
            <span className="text-xs font-bold text-white uppercase tracking-wider">
              Accelerometer & G-Force
            </span>
          </div>

          <div className="flex items-center space-x-2">
            <button
              onClick={() => setPeakG(sensors.gForce)}
              title="Reset Peak G"
              className="text-[10px] text-slate-400 hover:text-slate-200 flex items-center space-x-1"
            >
              <RotateCcw className="w-2.5 h-2.5" />
              <span>Reset Peak</span>
            </button>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3 mb-3">
          <div className="p-3 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider block">Current G-Force</span>
            <div className="text-2xl font-black font-mono text-amber-300 mt-0.5">
              {sensors.gForce.toFixed(2)} <span className="text-xs font-sans text-slate-400">G</span>
            </div>
          </div>

          <div className="p-3 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider block">Peak G Recorded</span>
            <div className="text-2xl font-black font-mono text-cyan-300 mt-0.5">
              {peakG.toFixed(2)} <span className="text-xs font-sans text-slate-400">G</span>
            </div>
          </div>
        </div>

        {/* 3-Axis Acceleration vector bars */}
        <div className="space-y-2 text-xs font-mono">
          <div className="flex items-center justify-between">
            <span className="text-slate-400">X-Axis (Lateral)</span>
            <span className="text-slate-200">{sensors.accelX >= 0 ? `+${sensors.accelX}` : sensors.accelX} m/s²</span>
          </div>
          <div className="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
            <div
              className="h-full bg-cyan-400 transition-all duration-100"
              style={{ width: `${Math.min(100, Math.abs(sensors.accelX / 15) * 100)}%` }}
            />
          </div>

          <div className="flex items-center justify-between">
            <span className="text-slate-400">Y-Axis (Longitudinal)</span>
            <span className="text-slate-200">{sensors.accelY >= 0 ? `+${sensors.accelY}` : sensors.accelY} m/s²</span>
          </div>
          <div className="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
            <div
              className="h-full bg-teal-400 transition-all duration-100"
              style={{ width: `${Math.min(100, Math.abs(sensors.accelY / 15) * 100)}%` }}
            />
          </div>

          <div className="flex items-center justify-between">
            <span className="text-slate-400">Z-Axis (Vertical / Gravity)</span>
            <span className="text-slate-200">{sensors.accelZ >= 0 ? `+${sensors.accelZ}` : sensors.accelZ} m/s²</span>
          </div>
          <div className="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
            <div
              className="h-full bg-emerald-400 transition-all duration-100"
              style={{ width: `${Math.min(100, Math.abs(sensors.accelZ / 15) * 100)}%` }}
            />
          </div>
        </div>
      </GlassCard>

      {/* 4. Acoustic Sound Level (SPL Decibel Meter) */}
      <GlassCard className="w-full !p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center space-x-2">
            <Volume2 className="w-4 h-4 text-cyan-400" />
            <span className="text-xs font-bold text-white uppercase tracking-wider">
              Acoustic Sound Level (SPL)
            </span>
          </div>

          <button
            onClick={() => setEnableMic(!enableMic)}
            className={`flex items-center space-x-1 px-2.5 py-1 rounded-full text-[11px] font-semibold border transition-all ${
              enableMic
                ? 'bg-cyan-500/20 border-cyan-500/50 text-cyan-300'
                : 'bg-white/10 border-white/15 text-slate-300 hover:bg-white/20'
            }`}
          >
            {enableMic ? <Mic className="w-3 h-3 text-cyan-400" /> : <MicOff className="w-3 h-3 text-slate-400" />}
            <span>{enableMic ? 'Monitoring' : 'Enable Mic'}</span>
          </button>
        </div>

        {enableMic ? (
          <div>
            <div className="flex items-baseline justify-between mb-2">
              <div className="text-3xl font-black font-mono text-white">
                {acoustic.decibels}{' '}
                <span className="text-sm font-semibold text-cyan-400 font-sans">dB SPL</span>
              </div>
              <span className="text-xs font-mono text-slate-400">
                Peak: {acoustic.peakDecibels} dB
              </span>
            </div>

            <div className="w-full h-2 rounded-full bg-white/10 overflow-hidden relative mb-2">
              <div
                className={`h-full transition-all duration-150 rounded-full ${
                  acoustic.decibels > 80
                    ? 'bg-gradient-to-r from-amber-400 to-rose-500'
                    : 'bg-gradient-to-r from-teal-400 to-cyan-400'
                }`}
                style={{ width: `${Math.min(100, Math.max(5, ((acoustic.decibels - 30) / 75) * 100))}%` }}
              />
            </div>

            <div className="flex justify-between items-center text-[11px] text-slate-300 bg-white/[0.03] p-2 rounded-xl border border-white/[0.05]">
              <span>Classification:</span>
              <span className="font-semibold text-cyan-300">{acoustic.categoryLabel}</span>
            </div>
          </div>
        ) : (
          <p className="text-[11px] text-slate-400 bg-white/[0.02] p-2.5 rounded-xl border border-white/[0.04]">
            Tap "Enable Mic" to monitor real-time ambient decibels and sound pressure levels during hikes or outdoor operations.
          </p>
        )}
      </GlassCard>

      {/* 5. Gyroscope Angular Velocity */}
      <GlassCard className="w-full !p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center space-x-2">
            <RotateCw className="w-4 h-4 text-emerald-400" />
            <span className="text-xs font-bold text-white uppercase tracking-wider">
              Gyroscope Angular Velocity
            </span>
          </div>
          <span className="text-[11px] font-mono text-slate-400">deg / sec</span>
        </div>

        <div className="grid grid-cols-3 gap-2 text-center font-mono">
          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <span className="text-[10px] text-slate-400 uppercase font-sans block">Yaw (α)</span>
            <span className="text-base font-bold text-white mt-0.5 block">{sensors.gyroX.toFixed(1)}°/s</span>
          </div>
          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <span className="text-[10px] text-slate-400 uppercase font-sans block">Pitch (β)</span>
            <span className="text-base font-bold text-white mt-0.5 block">{sensors.gyroY.toFixed(1)}°/s</span>
          </div>
          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <span className="text-[10px] text-slate-400 uppercase font-sans block">Roll (γ)</span>
            <span className="text-base font-bold text-white mt-0.5 block">{sensors.gyroZ.toFixed(1)}°/s</span>
          </div>
        </div>
      </GlassCard>

      {/* 6. Ambient Illumination (Lux) */}
      <GlassCard className="w-full !p-4">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center space-x-2">
            <Sun className="w-4 h-4 text-amber-400" />
            <span className="text-xs font-bold text-white uppercase tracking-wider">
              Ambient Light Sensor
            </span>
          </div>
          <span className="text-[11px] font-medium text-amber-300 bg-amber-500/15 px-2 py-0.5 rounded-full border border-amber-500/30">
            {lightInfo.label}
          </span>
        </div>

        <div className="text-3xl font-black font-mono text-white mb-2">
          {Math.round(sensors.ambientLight)}{' '}
          <span className="text-sm font-semibold text-amber-400 font-sans">Lux</span>
        </div>

        <div className="w-full h-2 rounded-full bg-white/10 overflow-hidden relative">
          <div
            className="h-full bg-gradient-to-r from-amber-500 via-amber-300 to-yellow-200 transition-all duration-300 rounded-full"
            style={{ width: `${Math.min(100, (sensors.ambientLight / 2000) * 100)}%` }}
          />
        </div>
      </GlassCard>

      {/* 7. Geodesy & GPS Satellite Telemetry */}
      {sensors.latitude !== null && sensors.longitude !== null && (
        <GlassCard className="w-full !p-4">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center space-x-2">
              <Locate className="w-4 h-4 text-cyan-400" />
              <span className="text-xs font-bold text-white uppercase tracking-wider">
                Geodesy & GPS Navigation
              </span>
            </div>
            {sensors.gpsAccuracy !== null && (
              <span className="text-[11px] font-mono text-cyan-300">
                ±{sensors.gpsAccuracy}m precision
              </span>
            )}
          </div>

          <div className="space-y-2 text-xs font-mono">
            <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06] flex items-center justify-between">
              <span className="text-slate-400 font-sans">Decimal Coordinates:</span>
              <span className="text-slate-100 font-bold">
                {sensors.latitude.toFixed(5)}°, {sensors.longitude.toFixed(5)}°
              </span>
            </div>

            <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06] flex items-center justify-between">
              <span className="text-slate-400 font-sans">DMS Format:</span>
              <span className="text-slate-200 text-[11px]">
                {formatToDMS(sensors.latitude, true)}, {formatToDMS(sensors.longitude, false)}
              </span>
            </div>

            <div className="grid grid-cols-2 gap-2 pt-1 font-sans">
              <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06] text-center">
                <span className="text-[10px] text-slate-400 uppercase block">Ground Speed</span>
                <span className="text-base font-bold font-mono text-cyan-300 mt-0.5 block">
                  {((sensors.gpsSpeed ?? 0) * 3.6).toFixed(1)} km/h
                </span>
                <span className="text-[10px] text-slate-400 font-mono">
                  {((sensors.gpsSpeed ?? 0) * 2.237).toFixed(1)} mph
                </span>
              </div>

              <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06] text-center">
                <span className="text-[10px] text-slate-400 uppercase block">Solar Position</span>
                <span className="text-base font-bold font-mono text-amber-300 mt-0.5 block">
                  {sensors.sunAzimuth}° Az
                </span>
                <span className="text-[10px] text-slate-400 font-mono">
                  {sensors.sunElevation}° Elevation
                </span>
              </div>
            </div>
          </div>
        </GlassCard>
      )}
    </div>
  );
};
