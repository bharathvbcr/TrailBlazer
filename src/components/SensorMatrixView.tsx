import React, { useState, useEffect } from 'react';
import { SensorState, UserPreferences, TrackSession, Waypoint } from '../types/sensors';
import { formatToDMS } from '../utils/calculations';
import { useAcousticSensor } from '../hooks/useAcousticSensor';
import { GlassCard } from './GlassCard';
import { Page, PageHeader, CardHeader, Unavailable } from './Layout';
import { TrackElevationProfileChart } from './TrackElevationProfileChart';
import {
  Activity,
  Magnet,
  Sun,
  Zap,
  RotateCw,
  Locate,
  AlertCircle,
  RotateCcw,
  Mic,
  MicOff,
  Volume2,
  Rows3,
  Rows4,
  Box,
} from 'lucide-react';
import { Accelerometer3DPlot } from './Accelerometer3DPlot';

interface Props {
  sensors: SensorState;
  preferences: UserPreferences;
  trackSession: TrackSession;
  onOpenCalibration: () => void;
  onAddWaypoint?: (wp: Omit<Waypoint, 'id' | 'timestamp'>) => void;
  onUpdatePreferences: (prefs: Partial<UserPreferences>) => void;
  locationEnabled: boolean;
  onSetupSensors: () => void;
}

export const SensorMatrixView: React.FC<Props> = ({
  sensors,
  preferences,
  trackSession,
  onOpenCalibration,
  onAddWaypoint,
  onUpdatePreferences,
  locationEnabled,
  onSetupSensors,
}) => {
  const compact = preferences.compactTelemetry;
  // Highest G actually observed this session (null until motion data arrives)
  const [peakG, setPeakG] = useState<number | null>(null);
  const [enableMic, setEnableMic] = useState<boolean>(false);
  const [accelViewMode, setAccelViewMode] = useState<'3d' | 'split' | 'bars'>('3d');

  const { state: acoustic, startListening, stopListening, resetPeak: resetPeakAcoustic } = useAcousticSensor(enableMic && !compact);

  // Track peak G safely in an effect
  const motionLive = sensors.isHardwareMotionAvailable || sensors.isSimulationMode;
  useEffect(() => {
    if (motionLive && (peakG === null || sensors.gForce > peakG)) {
      setPeakG(sensors.gForce);
    }
  }, [motionLive, sensors.gForce, peakG]);

  // Determine light condition label
  const getLightLevelText = (lux: number) => {
    if (lux < 1) return { label: 'Pitch Black / Night', category: 'dark' };
    if (lux < 50) return { label: 'Dim Interior / Twilight', category: 'dim' };
    if (lux < 300) return { label: 'Standard Home Lighting', category: 'normal' };
    if (lux < 800) return { label: 'Office / Workspace', category: 'bright' };
    if (lux < 10000) return { label: 'Overcast Daylight', category: 'daylight' };
    return { label: 'Direct Sunlight', category: 'sun' };
  };

  const lightInfo = sensors.ambientLight !== null ? getLightLevelText(sensors.ambientLight) : null;

  return (
    <Page>
      <PageHeader
        icon={<Activity className="w-5 h-5 text-cyan-400" />}
        title="Telemetry"
        subtitle="Live sensors & elevation"
        actions={
          <button
            onClick={() => onUpdatePreferences({ compactTelemetry: !compact })}
            aria-pressed={compact}
            title={compact ? 'Show all sensor cards' : 'Show fewer cards'}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full border text-xs font-semibold ${
              compact
                ? 'bg-cyan-500/20 border-cyan-500/50 text-cyan-300'
                : 'bg-white/10 hover:bg-white/15 border-white/15 text-slate-300'
            }`}
          >
            {compact ? <Rows3 className="w-3.5 h-3.5" /> : <Rows4 className="w-3.5 h-3.5" />}
            <span>{compact ? 'Compact' : 'Full'}</span>
          </button>
        }
      />

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
        <CardHeader icon={<Magnet className="text-cyan-400 w-4 h-4 shrink-0" />} title="Magnetic Flux Density">
          <div className="flex items-center space-x-1.5">
            <button
              onClick={onOpenCalibration}
              className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-white/10 hover:bg-white/20 border border-white/15 text-slate-200 transition-all"
            >
              Calibrate
            </button>
            {sensors.magneticFlux === null ? null : sensors.magneticAnomaly ? (
              <span className="flex items-center space-x-1 text-[11px] font-semibold text-rose-400 bg-rose-500/15 px-2 py-0.5 rounded-full border border-rose-500/30">
                <AlertCircle className="w-3 h-3" />
                <span>Ferromagnetic Alert</span>
              </span>
            ) : (
              <span className="text-[11px] font-mono text-emerald-400 bg-emerald-500/15 px-2 py-0.5 rounded-full border border-emerald-500/30">
                Normal field
              </span>
            )}
          </div>
        </CardHeader>

        {sensors.magneticFlux !== null ? (
          <>

        <div className="flex items-baseline justify-between mb-2">
          <div className="text-3xl font-black font-mono text-white">
            {sensors.magneticFlux.toFixed(1)}{' '}
            <span className="text-sm font-semibold text-cyan-400 font-sans">µT</span>
          </div>
          {sensors.expectedMagneticField !== null && (
            <span className="text-xs text-slate-400 font-mono" title="World Magnetic Model estimate for your position">
              Expected here: {sensors.expectedMagneticField} µT
            </span>
          )}
        </div>

        {/* Magnetic field flux progress bar */}
        <div className="w-full h-2 rounded-full bg-white/10 overflow-hidden relative mb-3">
          <div
            className={`h-full transition-all duration-300 rounded-full ${
              sensors.magneticAnomaly
                ? 'bg-gradient-to-r from-amber-400 to-rose-500'
                : 'bg-gradient-to-r from-teal-400 to-cyan-400'
            }`}
            style={{ width: `${Math.min(100, (sensors.magneticFlux / 100) * 100)}%` }}
          />
        </div>

        <p className="text-[11px] text-slate-300 bg-white/[0.03] p-2.5 rounded-xl border border-white/[0.05] leading-relaxed">
          {sensors.magneticAnomaly
            ? 'High magnetic field detected. Nearby metals, electric cables, or car frames may distort compass bearings. Tap "Calibrate" to run figure-8 motion.'
            : 'Field strength is close to what is expected here. Compass readings should be reliable.'}
        </p>
</>
        ) : (
          <Unavailable>
            No magnetometer data. Browsers rarely expose one; the Android app and some Chrome versions do. Your compass heading is unaffected.
            {sensors.expectedMagneticField !== null && ` The World Magnetic Model expects about ${sensors.expectedMagneticField} µT at your position.`}
          </Unavailable>
        )}
      </GlassCard>

      {/* 3. Kinematics & Accelerometer G-Force */}
      <GlassCard className="w-full !p-4">
        <CardHeader icon={<Zap className="text-amber-400 w-4 h-4 shrink-0" />} title="Accelerometer & G‑Force">
          <div className="flex items-center space-x-2">
            {/* View Mode Toggle */}
            <div className="flex items-center gap-0.5 bg-white/10 p-0.5 rounded-full border border-white/10 text-[10px]">
              {(['3d', 'split', 'bars'] as const).map((mode) => (
                <button
                  key={mode}
                  onClick={() => setAccelViewMode(mode)}
                  className={`px-2 py-0.5 rounded-full font-semibold transition-all ${
                    accelViewMode === mode
                      ? 'bg-amber-500/25 text-amber-300 border border-amber-500/40'
                      : 'text-slate-400 hover:text-white'
                  }`}
                >
                  {mode === '3d' ? '3D Plot' : mode === 'split' ? 'Split' : '2D Bars'}
                </button>
              ))}
            </div>

            <button
              onClick={() => setPeakG(sensors.gForce)}
              title="Reset Peak G"
              className="text-[11px] text-slate-400 hover:text-slate-200 flex items-center space-x-1"
            >
              <RotateCcw className="w-2.5 h-2.5" />
              <span className="hidden sm:inline">Reset</span>
            </button>
          </div>
        </CardHeader>

        {motionLive ? (
          <div className="space-y-3">
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
              <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
                <span className="text-[10px] text-slate-400 uppercase tracking-wider block">Current G-Force</span>
                <div className="text-xl font-black font-mono text-amber-300 mt-0.5">
                  {sensors.gForce.toFixed(2)} <span className="text-xs font-sans text-slate-400">G</span>
                </div>
              </div>

              <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
                <span className="text-[10px] text-slate-400 uppercase tracking-wider block">Peak G Recorded</span>
                <div className="text-xl font-black font-mono text-cyan-300 mt-0.5">
                  {peakG !== null ? peakG.toFixed(2) : '—'} <span className="text-xs font-sans text-slate-400">G</span>
                </div>
              </div>

              <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06] col-span-2 sm:col-span-1">
                <span className="text-[10px] text-slate-400 uppercase tracking-wider block">Vector Magnitude</span>
                <div className="text-xl font-black font-mono text-teal-300 mt-0.5">
                  {Math.sqrt(sensors.accelX * sensors.accelX + sensors.accelY * sensors.accelY + sensors.accelZ * sensors.accelZ).toFixed(1)}{' '}
                  <span className="text-xs font-sans text-slate-400">m/s²</span>
                </div>
              </div>
            </div>

            {/* 3D Accelerometer Plot */}
            {(accelViewMode === '3d' || accelViewMode === 'split') && (
              <Accelerometer3DPlot
                accelX={sensors.accelX}
                accelY={sensors.accelY}
                accelZ={sensors.accelZ}
                gForce={sensors.gForce}
                peakG={peakG}
                height={accelViewMode === '3d' ? 300 : 230}
                onResetPeak={() => setPeakG(sensors.gForce)}
              />
            )}

            {/* 3-Axis Acceleration vector bars */}
            {(accelViewMode === 'bars' || accelViewMode === 'split') && (
              <div className="space-y-2 text-xs font-mono pt-1">
                <div className="flex items-center justify-between">
                  <span className="text-slate-400 flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-cyan-400 inline-block" />
                    X-Axis (Lateral)
                  </span>
                  <span className="text-slate-200">{sensors.accelX >= 0 ? `+${sensors.accelX}` : sensors.accelX} m/s²</span>
                </div>
                <div className="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
                  <div
                    className="h-full bg-cyan-400 transition-all duration-100"
                    style={{ width: `${Math.min(100, Math.abs(sensors.accelX / 15) * 100)}%` }}
                  />
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-400 flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-teal-400 inline-block" />
                    Y-Axis (Longitudinal)
                  </span>
                  <span className="text-slate-200">{sensors.accelY >= 0 ? `+${sensors.accelY}` : sensors.accelY} m/s²</span>
                </div>
                <div className="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
                  <div
                    className="h-full bg-teal-400 transition-all duration-100"
                    style={{ width: `${Math.min(100, Math.abs(sensors.accelY / 15) * 100)}%` }}
                  />
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-400 flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-amber-400 inline-block" />
                    Z-Axis (Vertical / Gravity)
                  </span>
                  <span className="text-slate-200">{sensors.accelZ >= 0 ? `+${sensors.accelZ}` : sensors.accelZ} m/s²</span>
                </div>
                <div className="w-full h-1.5 rounded-full bg-white/10 overflow-hidden">
                  <div
                    className="h-full bg-amber-400 transition-all duration-100"
                    style={{ width: `${Math.min(100, Math.abs(sensors.accelZ / 15) * 100)}%` }}
                  />
                </div>
              </div>
            )}
          </div>
        ) : (
          <Unavailable>No accelerometer data. Allow motion access or use a device with motion sensors.</Unavailable>
        )}
      </GlassCard>

      {compact ? (
        <button
          onClick={() => onUpdatePreferences({ compactTelemetry: false })}
          className="w-full py-3 rounded-2xl border border-dashed border-white/15 text-xs text-slate-400 hover:text-white hover:bg-white/[0.05]"
        >
          Compact view · Sound, gyroscope &amp; light hidden — <span className="text-cyan-300 font-semibold">Show all</span>
        </button>
      ) : (
        <>
      {/* 4. Microphone input level (relative dBFS, uncalibrated) */}
      <GlassCard className="w-full !p-4">
        <CardHeader icon={<Volume2 className="text-cyan-400 w-4 h-4 shrink-0" />} title="Sound Input Level">
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
        </CardHeader>

        {enableMic ? (
          <div>
            <div className="flex items-baseline justify-between mb-2">
              <div className="text-3xl font-black font-mono text-white">
                {acoustic.decibels ?? '—'}{' '}
                <span className="text-sm font-semibold text-cyan-400 font-sans">dBFS</span>
              </div>
              <span className="text-xs font-mono text-slate-400">
                Peak: {acoustic.peakDecibels ?? '—'} dBFS
              </span>
            </div>

            <div className="w-full h-2 rounded-full bg-white/10 overflow-hidden relative mb-2">
              <div
                className={`h-full transition-all duration-150 rounded-full ${
                  (acoustic.decibels ?? -90) > -20
                    ? 'bg-gradient-to-r from-amber-400 to-rose-500'
                    : 'bg-gradient-to-r from-teal-400 to-cyan-400'
                }`}
                style={{ width: `${Math.min(100, Math.max(2, (((acoustic.decibels ?? -90) + 90) / 90) * 100))}%` }}
              />
            </div>

            <div className="flex justify-between items-center text-[11px] text-slate-300 bg-white/[0.03] p-2 rounded-xl border border-white/[0.05]">
              <span>Level:</span>
              <span className="font-semibold text-cyan-300">{acoustic.categoryLabel}</span>
            </div>
            <p className="text-[11px] text-slate-500 mt-2 leading-relaxed">
              Relative to your microphone&apos;s full scale. Phones don&apos;t report calibrated sound pressure, so this is not dB SPL.
            </p>
          </div>
        ) : (
          <p className="text-[11px] text-slate-400 bg-white/[0.02] p-2.5 rounded-xl border border-white/[0.04]">
            Tap "Enable Mic" to show the live microphone input level. The microphone is only used while this is on and audio is never stored.
          </p>
        )}
      </GlassCard>

      {/* 5. Gyroscope Angular Velocity */}
      <GlassCard className="w-full !p-4">
        <CardHeader icon={<RotateCw className="text-emerald-400 w-4 h-4 shrink-0" />} title="Gyroscope Angular Velocity">
          <span className="text-[11px] font-mono text-slate-400">deg / sec</span>
        </CardHeader>

        {sensors.isHardwareGyroAvailable ? (
          <>

        <div className="grid grid-cols-3 gap-2 text-center font-mono">
          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Yaw (α)</span>
            <span className="text-base font-bold text-white mt-0.5 block">{sensors.gyroX.toFixed(1)}°/s</span>
          </div>
          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Pitch (β)</span>
            <span className="text-base font-bold text-white mt-0.5 block">{sensors.gyroY.toFixed(1)}°/s</span>
          </div>
          <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06]">
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Roll (γ)</span>
            <span className="text-base font-bold text-white mt-0.5 block">{sensors.gyroZ.toFixed(1)}°/s</span>
          </div>
        </div>
</>
        ) : (
          <Unavailable>No gyroscope data. Allow motion access or use a device with motion sensors.</Unavailable>
        )}
      </GlassCard>

      {/* 6. Ambient Illumination (Lux) */}
      <GlassCard className="w-full !p-4">
        <CardHeader icon={<Sun className="text-amber-400 w-4 h-4 shrink-0" />} title="Ambient Light Sensor">
          {lightInfo && (
            <span className="text-[11px] font-medium text-amber-300 bg-amber-500/15 px-2 py-0.5 rounded-full border border-amber-500/30">
              {lightInfo.label}
            </span>
          )}
        </CardHeader>

        {sensors.ambientLight !== null && lightInfo ? (
          <>

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
</>
        ) : (
          <Unavailable>No ambient light sensor available on this device or browser.</Unavailable>
        )}
      </GlassCard>

        </>
      )}

      {/* 7. Geodesy & GPS Satellite Telemetry */}
      {sensors.latitude !== null && sensors.longitude !== null ? (
        <GlassCard className="w-full !p-4">
          <CardHeader icon={<Locate className="text-cyan-400 w-4 h-4 shrink-0" />} title="Geodesy & GPS Navigation">
            {sensors.gpsAccuracy !== null && (
              <span className="text-[11px] font-mono text-cyan-300">
                ±{sensors.gpsAccuracy}m precision
              </span>
            )}
          </CardHeader>

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
                <span className="text-[11px] text-slate-400 uppercase block">Ground Speed</span>
                <span className="text-base font-bold font-mono text-cyan-300 mt-0.5 block">
                  {sensors.gpsSpeed === null ? '—' : `${(sensors.gpsSpeed * 3.6).toFixed(1)} km/h`}
                </span>
                <span className="text-[11px] text-slate-400 font-mono">
                  {sensors.gpsSpeed === null ? 'no speed reported' : `${(sensors.gpsSpeed * 2.237).toFixed(1)} mph`}
                </span>
              </div>

              <div className="p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06] text-center">
                <span className="text-[11px] text-slate-400 uppercase block">Solar Position</span>
                <span className="text-base font-bold font-mono text-amber-300 mt-0.5 block">
                  {sensors.sunAzimuth}° Az
                </span>
                <span className="text-[11px] text-slate-400 font-mono">
                  {sensors.sunElevation}° Elevation
                </span>
              </div>
            </div>
          </div>
        </GlassCard>
      ) : (
        <GlassCard className="w-full !p-4">
          <CardHeader icon={<Locate className="text-slate-400 w-4 h-4 shrink-0" />} title="Geodesy & GPS Navigation" />
          <p className="text-xs text-slate-400 leading-relaxed">
            {locationEnabled
              ? 'Searching for a GPS fix. Move to open sky if this takes long.'
              : 'Location is off, so position, ground speed and solar data are unavailable.'}
          </p>
          {!locationEnabled && (
            <button
              onClick={onSetupSensors}
              className="mt-3 px-3 py-2 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 text-xs font-bold"
            >
              Enable location
            </button>
          )}
        </GlassCard>
      )}
    </Page>
  );
};
