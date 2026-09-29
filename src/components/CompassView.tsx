import React, { useState } from 'react';
import {
  SensorState,
  UserPreferences,
  Waypoint,
} from '../types/sensors';
import {
  getCardinalDirection,
  calculateBearingDegrees,
  calculateDistanceMeters,
} from '../utils/calculations';
import { GlassCard } from './GlassCard';
import {
  Page,
  PageHeader,
  Toolbar,
  CardHeader,
  Stat,
} from './Layout';
import { CircularSpeedometerGauge } from './CircularSpeedometerGauge';
import {
  Compass,
  Sun,
  Lock,
  Unlock,
  Sliders,
  RotateCcw,
  CheckCircle2,
  AlertTriangle,
  Camera,
  Magnet,
} from 'lucide-react';

interface Props {
  sensors: SensorState;
  preferences: UserPreferences;
  waypoints: Waypoint[];
  onSetSimulationHeading: (h: number) => void;
  onSetSimulationPitchRoll: (pitch: number, roll: number) => void;
  onToggleSimulationMode: () => void;
  onUpdatePreferences: (prefs: Partial<UserPreferences>) => void;
  onRequestPermission: () => void;
  onOpenCameraSighting: () => void;
  onOpenCalibration: () => void;
  onSetSimulationSpeed?: (speedMs: number) => void;
}

export const CompassView: React.FC<Props> = ({
  sensors,
  preferences,
  waypoints,
  onSetSimulationHeading,
  onSetSimulationPitchRoll,
  onToggleSimulationMode,
  onUpdatePreferences,
  onRequestPermission,
  onOpenCameraSighting,
  onOpenCalibration,
  onSetSimulationSpeed,
}) => {
  const [lockedHeading, setLockedHeading] = useState<number | null>(null);
  // Real (or simulated) orientation data is flowing; otherwise every heading-derived value would be invented
  const live = sensors.isHardwareOrientationAvailable || sensors.isSimulationMode;

  // Active heading depending on user preference (True North vs Magnetic North)
  const currentHeading = preferences.northMode === 'true' ? sensors.trueHeading : sensors.heading;
  const cardinal = getCardinalDirection(currentHeading);

  // Locked course difference
  const headingDiff = lockedHeading !== null
    ? ((currentHeading - lockedHeading + 540) % 360) - 180
    : 0;

  // Active target waypoint
  const activeTarget = waypoints.find((w) => w.id === preferences.targetWaypointId);
  let targetBearing: number | null = null;
  let targetDistance: number | null = null;
  let targetRelativeAngle: number | null = null;

  if (activeTarget && sensors.latitude !== null && sensors.longitude !== null) {
    targetBearing = calculateBearingDegrees(
      sensors.latitude,
      sensors.longitude,
      activeTarget.latitude,
      activeTarget.longitude
    );
    targetDistance = calculateDistanceMeters(
      sensors.latitude,
      sensors.longitude,
      activeTarget.latitude,
      activeTarget.longitude
    );
    targetRelativeAngle = ((targetBearing - currentHeading + 540) % 360) - 180;
  }

  // Spirit level calculations (Pitch & Roll)
  const isLevel = live && Math.abs(sensors.pitch) < 1.0 && Math.abs(sensors.roll) < 1.0;
  const bubbleX = Math.max(-32, Math.min(32, (sensors.roll / 15) * 32));
  const bubbleY = Math.max(-32, Math.min(32, (sensors.pitch / 15) * 32));

  // Format heading display
  const headingDisplay = !live
    ? '---°'
    : preferences.headingUnit === 'mils'
    ? `${Math.round((currentHeading / 360) * 6400)} MIL`
    : `${Math.round(currentHeading).toString().padStart(3, '0')}°`;

  return (
    <Page>
      <PageHeader
        icon={<Compass className="w-5 h-5 text-cyan-400" />}
        title="Compass"
        subtitle="Heading, bearing & navigation"
        actions={
          <button
  onClick={onOpenCameraSighting}
  className="flex items-center space-x-1 px-3 py-1.5 rounded-full text-xs font-semibold bg-gradient-to-r from-cyan-500/20 to-blue-500/20 hover:from-cyan-500/30 hover:to-blue-500/30 border border-cyan-400/40 text-cyan-300 shadow-[0_0_10px_rgba(56,189,248,0.2)] transition-all active:scale-95"
  title="Launch Augmented Reality Camera Sight"
>
  <Camera className="w-3.5 h-3.5 text-cyan-400" />
  <span>AR Sight</span>
</button>
        }
      />

      <Toolbar>
        <div className="flex items-center gap-2">
          <button
            onClick={() =>
              onUpdatePreferences({
                northMode: preferences.northMode === 'true' ? 'magnetic' : 'true',
              })
            }
            disabled={sensors.declination === null}
            title={sensors.declination === null ? 'True North needs a GPS position to look up magnetic declination' : 'Toggle True / Magnetic North'}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-full text-xs font-medium bg-white/10 hover:bg-white/20 border border-white/15 transition-all text-slate-200 disabled:opacity-60"
          >
            <Compass className="w-3.5 h-3.5 text-cyan-400" />
            <span>{preferences.northMode === 'true' ? 'True North' : 'Magnetic'}</span>
            <span className="text-[11px] text-cyan-300 opacity-80">
              {sensors.declination === null
                ? '(needs GPS)'
                : `(${sensors.declination >= 0 ? '+' : ''}${sensors.declination}°)`}
            </span>
          </button>

          {sensors.magneticAnomaly && (
            <button
              onClick={onOpenCalibration}
              className="flex items-center space-x-1 px-2.5 py-1 rounded-full text-[11px] font-medium bg-amber-500/20 border border-amber-500/40 text-amber-300 animate-pulse"
              title="Calibrate Magnetometer"
            >
              <AlertTriangle className="w-3 h-3 text-amber-400" />
              <span>Interference</span>
            </button>
          )}
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => setLockedHeading(lockedHeading === null ? Math.round(currentHeading) : null)}
            disabled={!live}
            className={`flex items-center space-x-1 px-2.5 py-1.5 rounded-full text-xs font-medium border transition-all ${
              lockedHeading !== null
                ? 'bg-rose-500/20 border-rose-500/50 text-rose-300'
                : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
            }`}
          >
            {lockedHeading !== null ? (
              <>
                <Lock className="w-3.5 h-3.5 text-rose-400" />
                <span>{lockedHeading}°</span>
              </>
            ) : (
              <>
                <Unlock className="w-3.5 h-3.5 text-slate-400" />
                <span>Lock</span>
              </>
            )}
          </button>

          <button
            onClick={onToggleSimulationMode}
            title="Toggle Simulator"
            className={`p-2 rounded-full border transition-all ${
              sensors.isSimulationMode
                ? 'bg-cyan-500/20 border-cyan-500/50 text-cyan-300 shadow-[0_0_12px_rgba(56,189,248,0.3)]'
                : 'bg-white/10 hover:bg-white/20 border-white/15 text-slate-300'
            }`}
          >
            <Sliders className="w-3.5 h-3.5" />
          </button>
        </div>
      </Toolbar>

      {/* No compass data: explain instead of showing an invented heading */}
      {!live && (
        <GlassCard className="w-full !p-4 border-amber-500/30 bg-amber-500/10 animate-fade-in">
          <div className="flex items-center justify-between gap-3">
            <div className="text-xs text-amber-200 min-w-0">
              <div className="font-semibold flex items-center space-x-1">
                <AlertTriangle className="w-3.5 h-3.5 text-amber-400 shrink-0" />
                <span>No compass data</span>
              </div>
              <div className="text-[11px] text-slate-300 opacity-90 mt-0.5">
                Allow motion &amp; orientation access, or use a device with a magnetometer. The simulator lets you explore the app without sensors.
              </div>
            </div>
            <div className="flex flex-col gap-1.5 shrink-0">
              <button
                onClick={onRequestPermission}
                className="px-3 py-1.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-black text-xs font-semibold shadow-md"
              >
                Enable
              </button>
              <button
                onClick={onToggleSimulationMode}
                className="px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/20 border border-white/15 text-xs font-semibold text-slate-200"
              >
                Simulator
              </button>
            </div>
          </div>
        </GlassCard>
      )}

      {/* Target Navigation Banner if target is set */}
      {activeTarget && targetBearing !== null && (
        <GlassCard className="w-full !p-4 !rounded-2xl border-cyan-500/30 bg-cyan-950/20">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2.5">
              <div
                className="w-3 h-3 rounded-full shadow-[0_0_8px_currentColor]"
                style={{ backgroundColor: activeTarget.color }}
              />
              <div>
                <div className="text-xs font-semibold text-slate-100 flex items-center space-x-1">
                  <span>To: {activeTarget.name}</span>
                </div>
                <div className="text-[11px] text-slate-400">
                  Bearing {Math.round(targetBearing)}° •{' '}
                  {targetDistance! >= 1000
                    ? `${(targetDistance! / 1000).toFixed(2)} km`
                    : `${Math.round(targetDistance!)} m`}
                </div>
              </div>
            </div>

            <div className="flex items-center space-x-2">
              <div className="text-right">
                <div className="text-xs font-bold text-cyan-400">
                  {!live
                    ? 'NO HEADING'
                    : Math.abs(Math.round(targetRelativeAngle!)) <= 3
                    ? 'ON COURSE'
                    : `${Math.abs(Math.round(targetRelativeAngle!))}° ${
                        targetRelativeAngle! > 0 ? 'RIGHT' : 'LEFT'
                      }`}
                </div>
              </div>
              <button
                onClick={() => onUpdatePreferences({ targetWaypointId: null })}
                className="p-1 rounded-full hover:bg-white/10 text-slate-400"
              >
                <RotateCcw className="w-3 h-3" />
              </button>
            </div>
          </div>
        </GlassCard>
      )}

      {/* Large Digital Readout */}
      <div className="text-center my-1">
        <div className="flex items-baseline justify-center space-x-3">
          <span className="text-5xl font-black tracking-tight text-transparent bg-clip-text bg-gradient-to-b from-white via-slate-100 to-slate-400 font-mono drop-shadow-[0_4px_16px_rgba(255,255,255,0.15)]">
            {headingDisplay}
          </span>
          <span className="text-2xl font-bold text-cyan-400 tracking-wider">
            {live ? cardinal : ''}
          </span>
        </div>

        {/* Course deviation or accuracy subline */}
        {lockedHeading !== null ? (
          <div className="text-xs mt-1 font-mono">
            {Math.abs(headingDiff) <= 1 ? (
              <span className="text-emerald-400 font-semibold">● On Locked Track</span>
            ) : (
              <span className={headingDiff > 0 ? 'text-amber-400' : 'text-rose-400'}>
                {Math.abs(Math.round(headingDiff))}° {headingDiff > 0 ? 'Steer Left' : 'Steer Right'}
              </span>
            )}
          </div>
        ) : (
          <div className="text-xs text-slate-400 mt-1 flex items-center justify-center space-x-3">
            <span>Roll: {live ? (sensors.roll > 0 ? `+${sensors.roll}°` : `${sensors.roll}°`) : '—'}</span>
            <span>•</span>
            <span>Pitch: {live ? (sensors.pitch > 0 ? `+${sensors.pitch}°` : `${sensors.pitch}°`) : '—'}</span>
          </div>
        )}
      </div>

      {/* Liquid Glass Compass Dial Container */}
      <div className={`relative w-80 h-80 flex items-center justify-center select-none my-2 transition-[opacity,filter] duration-500 ${live ? '' : 'opacity-35 grayscale'}`}>
        {/* Ambient Glow Aura */}
        <div className="absolute inset-0 rounded-full bg-gradient-to-tr from-cyan-500/10 via-teal-500/10 to-indigo-500/15 blur-2xl pointer-events-none" />

        {/* Outer Frosted Glass Ring with Specular Edge */}
        <div className="absolute inset-0 rounded-full border border-white/20 bg-gradient-to-b from-white/[0.08] to-white/[0.02] backdrop-blur-2xl shadow-[0_12px_40px_rgba(0,0,0,0.5),inset_0_2px_4px_rgba(255,255,255,0.3)] pointer-events-none" />

        {/* Fixed Top Lubber Line Marker (Forward direction of device) */}
        <div className="absolute -top-1.5 z-30 flex flex-col items-center">
          <div className="w-1.5 h-4.5 bg-gradient-to-b from-rose-500 to-rose-400 rounded-full shadow-[0_0_10px_#f43f5e]" />
          <div className="w-0 h-0 border-l-[4px] border-l-transparent border-r-[4px] border-r-transparent border-t-[5px] border-t-rose-500" />
        </div>

        {/* Rotating Compass Card Dial */}
        <div
          className="relative w-[280px] h-[280px] rounded-full transition-transform duration-100 ease-out flex items-center justify-center"
          style={{
            transform: `rotate(${-currentHeading}deg)`,
          }}
        >
          {/* Dial Face SVG (Ticks, Cardinal Letters, Degrees) */}
          <svg className="absolute inset-0 w-full h-full" viewBox="0 0 280 280">
            <defs>
              <radialGradient id="dialGlass" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stopColor="rgba(255,255,255,0.03)" />
                <stop offset="75%" stopColor="rgba(255,255,255,0.01)" />
                <stop offset="100%" stopColor="rgba(0,0,0,0.4)" />
              </radialGradient>
              <linearGradient id="northArrow" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stopColor="#f43f5e" />
                <stop offset="100%" stopColor="#e11d48" />
              </linearGradient>
            </defs>

            {/* Inner background disc */}
            <circle cx="140" cy="140" r="134" fill="url(#dialGlass)" />
            <circle cx="140" cy="140" r="134" fill="none" stroke="rgba(255,255,255,0.1)" strokeWidth="1" />
            <circle cx="140" cy="140" r="102" fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth="1" strokeDasharray="2 4" />

            {/* Render 360 degree tick marks */}
            {Array.from({ length: 72 }).map((_, i) => {
              const deg = i * 5;
              const isMajor = deg % 30 === 0;
              const isMedium = deg % 10 === 0 && !isMajor;
              const innerRadius = isMajor ? 112 : isMedium ? 118 : 123;
              const outerRadius = 132;
              const angleRad = ((deg - 90) * Math.PI) / 180;
              const x1 = 140 + innerRadius * Math.cos(angleRad);
              const y1 = 140 + innerRadius * Math.sin(angleRad);
              const x2 = 140 + outerRadius * Math.cos(angleRad);
              const y2 = 140 + outerRadius * Math.sin(angleRad);

              return (
                <line
                  key={deg}
                  x1={x1}
                  y1={y1}
                  x2={x2}
                  y2={y2}
                  stroke={isMajor ? '#ffffff' : isMedium ? 'rgba(255,255,255,0.5)' : 'rgba(255,255,255,0.2)'}
                  strokeWidth={isMajor ? '2' : isMedium ? '1.5' : '1'}
                />
              );
            })}

            {/* Degree Numbers for every 30 degrees */}
            {[30, 60, 120, 150, 210, 240, 300, 330].map((deg) => {
              const rad = ((deg - 90) * Math.PI) / 180;
              const x = 140 + 98 * Math.cos(rad);
              const y = 140 + 98 * Math.sin(rad);
              return (
                <text
                  key={deg}
                  x={x}
                  y={y}
                  fill="rgba(255,255,255,0.45)"
                  fontSize="9"
                  fontFamily="monospace"
                  textAnchor="middle"
                  dominantBaseline="central"
                  transform={`rotate(${deg}, ${x}, ${y})`}
                >
                  {deg}
                </text>
              );
            })}

            {/* Cardinal Letters (N, E, S, W) */}
            <text
              x="140"
              y="42"
              fill="#f43f5e"
              fontSize="16"
              fontWeight="900"
              textAnchor="middle"
              dominantBaseline="central"
              filter="drop-shadow(0 0 6px rgba(244,63,94,0.6))"
            >
              N
            </text>
            <text
              x="238"
              y="140"
              fill="#e2e8f0"
              fontSize="15"
              fontWeight="800"
              textAnchor="middle"
              dominantBaseline="central"
            >
              E
            </text>
            <text
              x="140"
              y="238"
              fill="#94a3b8"
              fontSize="15"
              fontWeight="800"
              textAnchor="middle"
              dominantBaseline="central"
            >
              S
            </text>
            <text
              x="42"
              y="140"
              fill="#e2e8f0"
              fontSize="15"
              fontWeight="800"
              textAnchor="middle"
              dominantBaseline="central"
            >
              W
            </text>

            {/* Intercardinal Letters (NE, SE, SW, NW) */}
            {[
              { label: 'NE', deg: 45 },
              { label: 'SE', deg: 135 },
              { label: 'SW', deg: 225 },
              { label: 'NW', deg: 315 },
            ].map(({ label, deg }) => {
              const rad = ((deg - 90) * Math.PI) / 180;
              const x = 140 + 98 * Math.cos(rad);
              const y = 140 + 98 * Math.sin(rad);
              return (
                <text
                  key={label}
                  x={x}
                  y={y}
                  fill="rgba(56,189,248,0.7)"
                  fontSize="10"
                  fontWeight="700"
                  textAnchor="middle"
                  dominantBaseline="central"
                  transform={`rotate(${deg}, ${x}, ${y})`}
                >
                  {label}
                </text>
              );
            })}

            {/* Magnetic vs True North Pointer Needle */}
            <polygon
              points="140,46 145,130 135,130"
              fill="url(#northArrow)"
              filter="drop-shadow(0 0 8px rgba(244,63,94,0.5))"
            />
            <polygon
              points="140,234 144,150 136,150"
              fill="rgba(148,163,184,0.6)"
            />

            {/* Target waypoint marker if set */}
            {live && targetBearing !== null && (
              <g transform={`rotate(${targetBearing}, 140, 140)`}>
                <polygon
                  points="140,28 146,38 134,38"
                  fill="#38bdf8"
                  filter="drop-shadow(0 0 8px #38bdf8)"
                />
                <circle cx="140" cy="33" r="2" fill="#ffffff" />
              </g>
            )}

            {/* Sun indicator on dial */}
            {sensors.sunAzimuth !== null && (
              <g transform={`rotate(${sensors.sunAzimuth}, 140, 140)`}>
                <circle cx="140" cy="22" r="5" fill="#fbbf24" filter="drop-shadow(0 0 6px #fbbf24)" />
              </g>
            )}
          </svg>

          {/* Locked heading index flag */}
          {lockedHeading !== null && (
            <div
              className="absolute inset-0 pointer-events-none"
              style={{ transform: `rotate(${lockedHeading}deg)` }}
            >
              <div className="absolute top-1 left-1/2 -translate-x-1/2 w-2 h-2 rounded-full bg-rose-500 shadow-[0_0_8px_#f43f5e]" />
            </div>
          )}
        </div>

        {/* Central Integrated 2D Bullseye Spirit Level */}
        <div className="absolute z-20 w-24 h-24 rounded-full border border-white/20 bg-gradient-to-b from-white/[0.12] to-white/[0.04] backdrop-blur-md shadow-[inset_0_2px_8px_rgba(0,0,0,0.5)] flex items-center justify-center pointer-events-none">
          {/* Target Crosshairs */}
          <div className="absolute w-full h-[1px] bg-white/10" />
          <div className="absolute h-full w-[1px] bg-white/10" />
          
          {/* Concentric Calibration Rings */}
          <div className="w-16 h-16 rounded-full border border-white/15" />
          <div
            className={`w-8 h-8 rounded-full border transition-colors duration-300 ${
              isLevel
                ? 'border-emerald-400 bg-emerald-500/20 shadow-[0_0_12px_rgba(52,211,153,0.5)]'
                : 'border-white/25'
            }`}
          />

          {/* Fluid Level Bubble */}
          <div
            className={`absolute w-5 h-5 rounded-full transition-all duration-75 ease-out shadow-lg ${
              isLevel
                ? 'bg-gradient-to-tr from-emerald-400 to-cyan-300 shadow-[0_0_14px_#34d399]'
                : 'bg-gradient-to-tr from-white/90 to-white/50 border border-white/40'
            }`}
            style={{
              transform: `translate(${bubbleX}px, ${bubbleY}px)`,
            }}
          >
            <div className="w-1.5 h-1.5 rounded-full bg-white ml-1 mt-1 opacity-90" />
          </div>
        </div>
      </div>

      {/* Spirit Level & Precision Telemetry Card */}
      <GlassCard className="w-full !p-4">
        <div className="grid grid-cols-3 gap-2">
          <Stat
            label="Level"
            tone={isLevel ? 'emerald' : 'amber'}
            icon={isLevel ? <CheckCircle2 className="w-3 h-3 text-emerald-400 shrink-0" /> : undefined}
            value={!live ? '—' : isLevel ? 'Flat' : `${Math.max(Math.abs(sensors.pitch), Math.abs(sensors.roll)).toFixed(1)}°`}
          />
          <Stat
            label="Sun"
            tone="amber"
            icon={<Sun className="w-3 h-3 text-amber-400 shrink-0" />}
            value={sensors.sunAzimuth === null ? '—' : `${sensors.sunAzimuth}°`}
          />
          <Stat
            label="Mag"
            icon={<Magnet className="w-3 h-3 text-cyan-400 shrink-0" />}
            value={sensors.magneticFlux === null ? '—' : sensors.magneticFlux}
            unit={sensors.magneticFlux === null ? undefined : 'µT'}
            onClick={onOpenCalibration}
            title="Tap to run Figure-8 calibration"
          />
        </div>
      </GlassCard>

      {/* Circular Speedometer Gauge (Real-Time GPS Velocity & Overspeed Alert) */}
      <CircularSpeedometerGauge
        gpsSpeedMs={sensors.gpsSpeed}
        gpsAccuracy={sensors.gpsAccuracy}
        isGpsAvailable={sensors.isGpsAvailable}
        isSimulationMode={sensors.isSimulationMode}
        altitudeUnit={preferences.altitudeUnit}
        speedUnit={preferences.speedUnit}
        onUpdateSpeedUnit={(newUnit) => onUpdatePreferences({ speedUnit: newUnit })}
        speedAlertEnabled={preferences.speedAlertEnabled}
        speedAlertThreshold={preferences.speedAlertThreshold}
        speedAlertVisual={preferences.speedAlertVisual}
        onSetSimulationSpeed={onSetSimulationSpeed}
      />

      {/* Interactive Sensor Simulator Controls Drawer (Visible when simulation mode is active) */}
      {sensors.isSimulationMode && (
        <GlassCard className="w-full !p-4 border-cyan-500/30 bg-cyan-950/20">
          <CardHeader icon={<Sliders className="text-cyan-400 w-4 h-4 shrink-0" />} title="Live Sensor Simulator">
            <span className="text-[11px] text-cyan-400 font-mono">Interactive Testing</span>
          </CardHeader>

          <div className="space-y-3 text-xs">
            <div>
              <div className="flex justify-between mb-1 text-slate-300">
                <span>Heading / Azimuth</span>
                <span className="font-mono text-cyan-400">{Math.round(currentHeading)}°</span>
              </div>
              <input
                type="range"
                min="0"
                max="359"
                value={Math.round(currentHeading)}
                onChange={(e) => onSetSimulationHeading(Number(e.target.value))}
                className="w-full h-1.5 bg-white/20 rounded-lg appearance-none cursor-pointer accent-cyan-400"
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <div className="flex justify-between mb-1 text-slate-300">
                  <span>Pitch (Tilt X)</span>
                  <span className="font-mono text-cyan-400">{sensors.pitch}°</span>
                </div>
                <input
                  type="range"
                  min="-25"
                  max="25"
                  value={sensors.pitch}
                  onChange={(e) => onSetSimulationPitchRoll(Number(e.target.value), sensors.roll)}
                  className="w-full h-1.5 bg-white/20 rounded-lg appearance-none cursor-pointer accent-cyan-400"
                />
              </div>

              <div>
                <div className="flex justify-between mb-1 text-slate-300">
                  <span>Roll (Tilt Y)</span>
                  <span className="font-mono text-cyan-400">{sensors.roll}°</span>
                </div>
                <input
                  type="range"
                  min="-25"
                  max="25"
                  value={sensors.roll}
                  onChange={(e) => onSetSimulationPitchRoll(sensors.pitch, Number(e.target.value))}
                  className="w-full h-1.5 bg-white/20 rounded-lg appearance-none cursor-pointer accent-cyan-400"
                />
              </div>
            </div>

            <div className="flex justify-end pt-1">
              <button
                onClick={() => {
                  onSetSimulationHeading(0);
                  onSetSimulationPitchRoll(0, 0);
                }}
                className="text-[11px] text-slate-400 hover:text-slate-200 flex items-center space-x-1"
              >
                <RotateCcw className="w-3 h-3" />
                <span>Zero Tilt & North</span>
              </button>
            </div>
          </div>
        </GlassCard>
      )}
    </Page>
  );
};
