import React, { useState, useMemo } from 'react';
import {
  SensorState,
  UserPreferences,
} from '../types/sensors';
import {
  calculateSolarDay,
  calculateMoonPhase,
  SolarDayInfo,
  MoonPhaseInfo,
} from '../utils/celestial';
import { calculateWaterBoilingPoint, formatToDMS } from '../utils/calculations';
import {
  playEmergencyWhistle,
  stopEmergencyWhistle,
} from '../utils/audioHaptics';
import { GlassCard } from './GlassCard';
import { Page, PageHeader, Toolbar, CardHeader, Stat } from './Layout';
import {
  Sunrise,
  Sunset,
  Sun,
  Moon,
  Compass,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  Flame,
  Volume2,
  VolumeX,
  Copy,
  Check,
  CheckCircle2,
  Calendar,
  Sparkles,
  Tent,
} from 'lucide-react';

interface Props {
  sensors: SensorState;
  preferences: UserPreferences;
  onNavigateToCompass: () => void;
  onToggleSimulationMode: () => void;
  onRequestPermission: () => void;
  dayOffset?: number;
  onSetDayOffset?: (offset: number) => void;
}

export const NatureCampView: React.FC<Props> = ({
  sensors,
  preferences,
  onNavigateToCompass,
  onToggleSimulationMode,
  onRequestPermission,
  dayOffset: propsDayOffset,
  onSetDayOffset,
}) => {
  const [internalDayOffset, setInternalDayOffset] = useState<number>(0);
  const dayOffset = propsDayOffset !== undefined ? propsDayOffset : internalDayOffset;
  const setDayOffset = (valOrFn: number | ((prev: number) => number)) => {
    const next = typeof valOrFn === 'function' ? valOrFn(dayOffset) : valOrFn;
    if (onSetDayOffset) {
      onSetDayOffset(next);
    } else {
      setInternalDayOffset(next);
    }
  };
  const [isWhistling, setIsWhistling] = useState<boolean>(false);
  const [isSosFlashing, setIsSosFlashing] = useState<boolean>(false);
  const [copiedCoords, setCopiedCoords] = useState<boolean>(false);

  // Active calculated calendar date
  const activeDate = useMemo(() => {
    const d = new Date();
    d.setDate(d.getDate() + dayOffset);
    return d;
  }, [dayOffset]);

  const isToday = dayOffset === 0;

  // Real or simulated GPS coordinates: honest location handling without fabricated SF fallback
  const hasGps = sensors.latitude !== null && sensors.longitude !== null;
  const isSim = sensors.isSimulationMode;
  const lat = sensors.latitude ?? (isSim ? 37.7749 : null);
  const lon = sensors.longitude ?? (isSim ? -122.4194 : null);
  const hasLocation = lat !== null && lon !== null;

  // Calculate Solar Day for the selected date only when real GPS or simulation is active
  const solarDay: SolarDayInfo | null = useMemo(() => {
    if (!hasLocation || lat === null || lon === null) return null;
    return calculateSolarDay(lat, lon, activeDate);
  }, [hasLocation, lat, lon, activeDate]);

  // Calculate Moon Phase for the selected date (phase/illumination works globally, azimuth needs position)
  const moonPhase: MoonPhaseInfo = useMemo(() => {
    return calculateMoonPhase(activeDate, lat, lon);
  }, [activeDate, lat, lon]);

  // Water boiling point from current pressure or standard at altitude
  const currentPressure = sensors.pressure ?? 1013.25;
  const boilingPoint = useMemo(() => {
    return calculateWaterBoilingPoint(currentPressure);
  }, [currentPressure]);

  // Shelter Leveler (pitch & roll)
  const isLevel = Math.abs(sensors.pitch) < 1.0 && Math.abs(sensors.roll) < 1.0;
  const bubbleX = Math.max(-28, Math.min(28, (sensors.roll / 15) * 28));
  const bubbleY = Math.max(-28, Math.min(28, (sensors.pitch / 15) * 28));

  // Toggle emergency whistle
  const handleToggleWhistle = () => {
    if (isWhistling) {
      stopEmergencyWhistle();
      setIsWhistling(false);
    } else {
      const ok = playEmergencyWhistle();
      if (ok) setIsWhistling(true);
    }
  };

  // Copy GPS coordinates for emergency SOS
  const handleCopyCoordinates = () => {
    if (sensors.latitude === null || sensors.longitude === null) return;
    const text = `${sensors.latitude.toFixed(6)}, ${sensors.longitude.toFixed(6)} (${formatToDMS(
      sensors.latitude,
      true
    )}, ${formatToDMS(sensors.longitude, false)})`;
    navigator.clipboard?.writeText(text).catch(() => {});
    setCopiedCoords(true);
    setTimeout(() => setCopiedCoords(false), 2000);
  };

  // Format time helpers
  const formatTime = (d: Date | null) => {
    if (!d) return '—';
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const formatDayTitle = (d: Date) => {
    if (dayOffset === 0) return 'Today';
    if (dayOffset === 1) return 'Tomorrow';
    if (dayOffset === -1) return 'Yesterday';
    return d.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
  };

  // Dynamic Moon SVG rendering based on phase and illumination
  const renderMoonSvg = () => {
    const p = moonPhase.phase; // 0 to 1
    const illum = moonPhase.illuminationPercentage;
    const isWaxing = moonPhase.waxing;

    // In the Southern Hemisphere (lat < 0), the illuminated portion of the Moon is visually mirrored
    const isSouthern = lat !== null && lat < 0;
    const southernStyle = isSouthern ? { transform: 'scaleX(-1)' } : undefined;

    // Radius of moon disk is 36
    const r = 36;
    const cx = 40;
    const cy = 40;

    // Draw dark base moon disk
    // If Full Moon (phase ~ 0.5)
    if (p >= 0.47 && p <= 0.53) {
      return (
        <svg viewBox="0 0 80 80" className="w-16 h-16 drop-shadow-[0_0_12px_rgba(254,240,138,0.5)]" style={southernStyle}>
          <circle cx={cx} cy={cy} r={r} fill="#fef08a" />
          <circle cx={32} cy={30} r={6} fill="#fde047" opacity="0.3" />
          <circle cx={50} cy={46} r={8} fill="#fde047" opacity="0.3" />
          <circle cx={42} cy={24} r={4} fill="#fde047" opacity="0.25" />
        </svg>
      );
    }

    // If New Moon (phase ~ 0 or 1)
    if (p <= 0.03 || p >= 0.97) {
      return (
        <svg viewBox="0 0 80 80" className="w-16 h-16 drop-shadow-[0_0_8px_rgba(56,189,248,0.2)]" style={southernStyle}>
          <circle cx={cx} cy={cy} r={r} fill="#0f172a" stroke="#334155" strokeWidth="1.5" />
          <path d="M 40 4 A 36 36 0 0 1 40 76 A 33 36 0 0 0 40 4 Z" fill="#1e293b" opacity="0.4" />
        </svg>
      );
    }

    // Generalized crescent & gibbous rendering via SVG arc
    const kNorm = (illum / 100) * 2 - 1; // -1 (new) to +1 (full)
    const curveR = Math.max(0.1, Math.abs(kNorm) * r);
    const sweep = isWaxing ? 1 : 0;
    const innerSweep = (isWaxing && kNorm < 0) || (!isWaxing && kNorm >= 0) ? 0 : 1;

    return (
      <svg viewBox="0 0 80 80" className="w-16 h-16 drop-shadow-[0_0_10px_rgba(226,232,240,0.4)]" style={southernStyle}>
        {/* Dark back of moon */}
        <circle cx={cx} cy={cy} r={r} fill="#090d16" stroke="#334155" strokeWidth="1.2" />
        {/* Illuminated portion */}
        <path
          d={`M ${cx} ${cy - r} 
              A ${r} ${r} 0 0 ${sweep} ${cx} ${cy + r} 
              A ${curveR} ${r} 0 0 ${innerSweep} ${cx} ${cy - r} Z`}
          fill="#f8fafc"
        />
        <circle cx={cx} cy={cy} r={r} fill="none" stroke="rgba(255,255,255,0.2)" strokeWidth="1" />
      </svg>
    );
  };

  return (
    <Page>
      {/* SOS Visual Screen Strobe Overlay */}
      {isSosFlashing && (
        <div
          onClick={() => setIsSosFlashing(false)}
          className="fixed inset-0 z-50 bg-white animate-ping flex flex-col items-center justify-center cursor-pointer p-6"
        >
          <div className="bg-black/80 backdrop-blur-md px-6 py-4 rounded-3xl text-center text-white">
            <div className="text-xl font-black text-rose-500 tracking-widest animate-pulse">EMERGENCY SOS STROBE</div>
            <div className="text-xs text-slate-300 mt-2">Tap anywhere to turn off</div>
          </div>
        </div>
      )}

      <PageHeader
        icon={<Tent className="w-5 h-5 text-amber-400" />}
        title="Nature & Camp"
        subtitle="Solar ephemeris, moon phases & wilderness tools"
        actions={
          <button
            onClick={onNavigateToCompass}
            className="flex items-center space-x-1 px-3 py-1.5 rounded-full text-xs font-semibold bg-white/10 hover:bg-white/20 border border-white/15 text-slate-200 transition-all active:scale-95"
            title="View Bearings on Compass"
          >
            <Compass className="w-3.5 h-3.5 text-cyan-400" />
            <span>Compass</span>
          </button>
        }
      />

      {/* Date Stepper Toolbar */}
      <Toolbar>
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setDayOffset((prev) => prev - 1)}
            aria-label="Previous day"
            className="p-1.5 rounded-full bg-white/10 hover:bg-white/20 border border-white/15 text-slate-300 transition-all"
            title="Previous Day"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>

          <label
            className="relative flex items-center gap-1.5 px-3 py-1 rounded-full bg-white/10 hover:bg-white/15 border border-white/15 text-xs font-semibold text-slate-200 cursor-pointer transition-all"
            title="Click to jump to any date"
          >
            <Calendar className="w-3.5 h-3.5 text-amber-400" />
            <span>{formatDayTitle(activeDate)}</span>
            <span className="text-[11px] text-slate-400 font-normal">
              ({activeDate.toLocaleDateString([], { month: 'numeric', day: 'numeric' })})
            </span>
            <input
              type="date"
              aria-label="Select expedition date"
              className="absolute inset-0 opacity-0 cursor-pointer w-full h-full"
              value={`${activeDate.getFullYear()}-${String(activeDate.getMonth() + 1).padStart(2, '0')}-${String(activeDate.getDate()).padStart(2, '0')}`}
              onChange={(e) => {
                if (!e.target.value) return;
                const [y, m, d] = e.target.value.split('-').map(Number);
                const sel = new Date(y, m - 1, d);
                const tod = new Date();
                tod.setHours(0, 0, 0, 0);
                sel.setHours(0, 0, 0, 0);
                const diff = Math.round((sel.getTime() - tod.getTime()) / 86400000);
                setDayOffset(diff);
              }}
            />
          </label>

          <button
            onClick={() => setDayOffset((prev) => prev + 1)}
            aria-label="Next day"
            className="p-1.5 rounded-full bg-white/10 hover:bg-white/20 border border-white/15 text-slate-300 transition-all"
            title="Next Day"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>

        <div className="flex items-center gap-2">
          {!isToday && (
            <button
              onClick={() => setDayOffset(0)}
              className="text-xs px-2.5 py-1 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30 hover:bg-amber-500/30 transition-all font-medium"
            >
              Reset to Today
            </button>
          )}

          {sensors.isSimulationMode && (
            <span className="text-[11px] px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 font-mono">
              Simulated Location
            </span>
          )}
        </div>
      </Toolbar>

      {/* If No GPS fix yet: honest notice */}
      {!hasGps && !sensors.isSimulationMode && (
        <GlassCard className="w-full !p-3 border-amber-500/30 bg-amber-500/10 mb-2">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2 min-w-0 text-xs text-amber-200">
              <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" />
              <span>Awaiting GPS location to calculate exact local sunrise/sunset azimuths for your campsite.</span>
            </div>
            <button
              onClick={onRequestPermission}
              className="px-2.5 py-1 rounded-lg bg-amber-500 text-black text-xs font-bold shrink-0 hover:bg-amber-400"
            >
              Enable GPS
            </button>
          </div>
        </GlassCard>
      )}

      {/* 1. Solar Ephemeris & Daylight Schedule Card */}
      <GlassCard className="w-full !p-4 border-amber-500/25 bg-gradient-to-b from-amber-950/15 to-transparent">
        <CardHeader
          icon={<Sun className="text-amber-400 w-4 h-4 shrink-0" />}
          title={`Sun & Solar Ephemeris (${formatDayTitle(activeDate)})`}
        >
          <span className="text-[11px] text-amber-300 font-mono">
            {!solarDay
              ? 'Awaiting GPS'
              : solarDay.isPolarDay
              ? 'Midnight Sun'
              : solarDay.isPolarNight
              ? 'Polar Night'
              : `${Math.floor(solarDay.daylightDurationMinutes / 60)}h ${solarDay.daylightDurationMinutes % 60}m daylight`}
          </span>
        </CardHeader>

        {/* Daylight Remaining Chip (Only for Today) */}
        {isToday && solarDay && solarDay.daylightRemainingMinutes !== null && (
          <div className="mb-2 px-3 py-1.5 rounded-xl bg-white/[0.04] border border-amber-500/20 flex items-center justify-between text-xs">
            <span className="text-slate-300">Remaining Daylight Today:</span>
            <span className="font-mono font-bold text-amber-300">
              {solarDay.daylightRemainingMinutes > 0
                ? `${Math.floor(solarDay.daylightRemainingMinutes / 60)}h ${solarDay.daylightRemainingMinutes % 60}m before sunset`
                : 'Daylight ended · Use headlamp'}
            </span>
          </div>
        )}

        {/* Sunrise & Sunset Direction Grid */}
        <div className="grid grid-cols-2 gap-3 my-2">
          {/* Sunrise Card */}
          <div className="p-3 rounded-2xl bg-white/[0.05] border border-amber-500/20 flex flex-col justify-between">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-amber-300">
                <Sunrise className="w-4 h-4 text-amber-400" />
                <span>Sunrise</span>
              </div>
              <span className="text-[11px] text-slate-400 font-mono">{solarDay ? formatTime(solarDay.sunriseTime) : '—'}</span>
            </div>

            <div className="my-2">
              <div className="text-2xl font-black text-white font-mono tracking-tight flex items-baseline gap-1.5">
                <span>{solarDay && solarDay.sunriseAzimuth !== null ? `${Math.round(solarDay.sunriseAzimuth)}°` : '—'}</span>
                <span className="text-sm font-bold text-amber-400">{solarDay?.sunriseCardinal ?? ''}</span>
              </div>
              <div className="text-[11px] text-slate-300 mt-0.5">Horizon Azimuth</div>
            </div>

            <div className="text-[11px] text-amber-200/80 border-t border-white/10 pt-1.5 mt-1">
              Golden Hr: {solarDay ? `${formatTime(solarDay.goldenHourMorning?.start ?? null)} – ${formatTime(solarDay.goldenHourMorning?.end ?? null)}` : '—'}
            </div>
          </div>

          {/* Sunset Card */}
          <div className="p-3 rounded-2xl bg-white/[0.05] border border-orange-500/20 flex flex-col justify-between">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-orange-400">
                <Sunset className="w-4 h-4 text-orange-400" />
                <span>Sunset</span>
              </div>
              <span className="text-[11px] text-slate-400 font-mono">{solarDay ? formatTime(solarDay.sunsetTime) : '—'}</span>
            </div>

            <div className="my-2">
              <div className="text-2xl font-black text-white font-mono tracking-tight flex items-baseline gap-1.5">
                <span>{solarDay && solarDay.sunsetAzimuth !== null ? `${Math.round(solarDay.sunsetAzimuth)}°` : '—'}</span>
                <span className="text-sm font-bold text-orange-400">{solarDay?.sunsetCardinal ?? ''}</span>
              </div>
              <div className="text-[11px] text-slate-300 mt-0.5">Horizon Azimuth</div>
            </div>

            <div className="text-[11px] text-orange-200/80 border-t border-white/10 pt-1.5 mt-1">
              Golden Hr: {solarDay ? `${formatTime(solarDay.goldenHourEvening?.start ?? null)} – ${formatTime(solarDay.goldenHourEvening?.end ?? null)}` : '—'}
            </div>
          </div>
        </div>

        {/* Solar Noon & Current Sun Angle */}
        <div className="grid grid-cols-3 gap-2 mt-2 pt-2 border-t border-white/10 text-xs">
          <Stat
            label="Solar Noon"
            icon={<Sun className="w-3 h-3 text-amber-400 shrink-0" />}
            value={solarDay ? formatTime(solarDay.solarNoonTime) : '—'}
          />
          <Stat
            label="Live Sun Az"
            tone="amber"
            value={solarDay && (hasGps || isSim) ? `${Math.round(solarDay.sunAzimuth)}°` : '—'}
          />
          <Stat
            label="Sun Elevation"
            tone={solarDay && solarDay.sunElevation > 0 ? 'emerald' : 'cyan'}
            value={solarDay && (hasGps || isSim) ? `${solarDay.sunElevation > 0 ? '+' : ''}${solarDay.sunElevation}°` : '—'}
          />
        </div>

        {/* Campsite Pitching Advice */}
        {solarDay && solarDay.sunriseAzimuth !== null && (
          <div className="mt-3 p-2.5 rounded-xl bg-amber-500/10 border border-amber-500/20 text-[11px] text-amber-200 flex items-start gap-2">
            <Tent className="w-3.5 h-3.5 text-amber-400 mt-0.5 shrink-0" />
            <div>
              <span className="font-semibold text-amber-300">Campsite Pitching Tip: </span>
              Pitch tent doorway towards <span className="font-bold text-white">{Math.round(solarDay.sunriseAzimuth)}° {solarDay.sunriseCardinal}</span> to receive warm morning sunrise. Place cooking/fire ring sheltered downwind.
            </div>
          </div>
        )}
      </GlassCard>

      {/* 2. Moon Phase & Night Sky Stargazing Card */}
      <GlassCard className="w-full !p-4 border-indigo-500/25 bg-gradient-to-b from-indigo-950/20 to-transparent">
        <CardHeader
          icon={<Moon className="text-indigo-400 w-4 h-4 shrink-0" />}
          title={`Moon Phase & Stargazing (${formatDayTitle(activeDate)})`}
        >
          <span className="text-[11px] text-indigo-300 font-mono">
            {moonPhase.ageDays}d / 29.5d cycle
          </span>
        </CardHeader>

        <div className="flex items-center justify-between gap-4 my-2">
          {/* Visual SVG Moon */}
          <div className="flex flex-col items-center shrink-0">
            {renderMoonSvg()}
            <span className="text-[11px] text-slate-400 font-mono mt-1">
              {moonPhase.waxing ? 'Waxing' : 'Waning'}
            </span>
          </div>

          {/* Moon Stats */}
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-lg font-bold text-white">{moonPhase.phaseName}</span>
              <span className="text-base">{moonPhase.phaseEmoji}</span>
            </div>
            <div className="text-xs text-indigo-300 font-mono font-semibold mt-0.5">
              {moonPhase.illuminationPercentage}% Illuminated
            </div>

            <div className="grid grid-cols-2 gap-2 mt-2 pt-2 border-t border-white/10 text-[11px] text-slate-300 font-mono">
              <div>
                <span className="text-slate-400">Full Moon in: </span>
                <span className="font-bold text-white">{moonPhase.daysToNextFullMoon}d</span>
              </div>
              <div>
                <span className="text-slate-400">New Moon in: </span>
                <span className="font-bold text-white">{moonPhase.daysToNextNewMoon}d</span>
              </div>
            </div>

            {moonPhase.moonAzimuth !== null && (
              <div className="text-[11px] text-slate-300 mt-1.5 flex items-center gap-2">
                <span>Azimuth: <strong className="text-indigo-300 font-mono">{moonPhase.moonAzimuth}°</strong></span>
                <span>•</span>
                <span>Alt: <strong className="text-indigo-300 font-mono">{moonPhase.moonElevation! > 0 ? `+${moonPhase.moonElevation}°` : `${moonPhase.moonElevation}°`}</strong></span>
              </div>
            )}
          </div>
        </div>

        {/* Stargazing / Dark Sky Quality Assessment */}
        <div className="mt-3 p-3 rounded-2xl bg-white/[0.04] border border-white/10">
          <div className="flex items-center justify-between mb-1">
            <div className="flex items-center gap-1.5 text-xs font-bold text-white">
              <Sparkles className="w-3.5 h-3.5 text-yellow-300" />
              <span>Stargazing &amp; Dark Sky Rating</span>
            </div>
            <span
              className={`text-[11px] px-2 py-0.5 rounded-full font-bold uppercase ${
                moonPhase.stargazing.rating === 'excellent'
                  ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'
                  : moonPhase.stargazing.rating === 'good'
                  ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40'
                  : moonPhase.stargazing.rating === 'fair'
                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                  : 'bg-rose-500/20 text-rose-300 border border-rose-500/40'
              }`}
            >
              {moonPhase.stargazing.rating}
            </span>
          </div>
          <div className="text-xs font-semibold text-slate-200 mt-0.5">{moonPhase.stargazing.title}</div>
          <div className="text-[11px] text-slate-300 opacity-90 mt-1 leading-relaxed">
            {moonPhase.stargazing.description}
          </div>
        </div>
      </GlassCard>

      {/* 3. Campsite Pitch & Shelter Leveler */}
      <GlassCard className="w-full !p-4">
        <CardHeader
          icon={<CheckCircle2 className="text-emerald-400 w-4 h-4 shrink-0" />}
          title="Camp & Shelter Leveler"
        >
          <span className={`text-[11px] font-mono ${isLevel ? 'text-emerald-400 font-bold' : 'text-amber-400'}`}>
            {isLevel ? '● Perfectly Level' : 'Tilt Detected'}
          </span>
        </CardHeader>

        <div className="flex items-center justify-between gap-4 my-2">
          {/* Circular Bullseye Level */}
          <div className="relative w-24 h-24 rounded-full border border-white/20 bg-black/40 flex items-center justify-center shrink-0">
            <div className="absolute w-full h-[1px] bg-white/10" />
            <div className="absolute h-full w-[1px] bg-white/10" />
            <div className="w-16 h-16 rounded-full border border-white/15" />
            <div
              className={`w-8 h-8 rounded-full border transition-colors ${
                isLevel ? 'border-emerald-400 bg-emerald-500/20 shadow-[0_0_12px_#34d399]' : 'border-white/25'
              }`}
            />
            {/* Fluid Bubble */}
            <div
              className={`absolute w-5 h-5 rounded-full transition-all duration-75 shadow-md ${
                isLevel
                  ? 'bg-gradient-to-tr from-emerald-400 to-cyan-300'
                  : 'bg-gradient-to-tr from-white/90 to-white/50 border border-white/40'
              }`}
              style={{ transform: `translate(${bubbleX}px, ${bubbleY}px)` }}
            />
          </div>

          <div className="flex-1 space-y-2 text-xs">
            <div className="flex justify-between items-center bg-white/[0.04] p-2 rounded-xl border border-white/10">
              <span className="text-slate-400">Roll (Side-to-Side):</span>
              <span className="font-mono font-bold text-white">{sensors.roll > 0 ? `+${sensors.roll}°` : `${sensors.roll}°`}</span>
            </div>
            <div className="flex justify-between items-center bg-white/[0.04] p-2 rounded-xl border border-white/10">
              <span className="text-slate-400">Pitch (Head-to-Toe):</span>
              <span className="font-mono font-bold text-white">{sensors.pitch > 0 ? `+${sensors.pitch}°` : `${sensors.pitch}°`}</span>
            </div>
            <div className="text-[11px] text-slate-400 leading-tight">
              Essential for leveling camp stoves, RV/camper sleeping setups, and pitching tent floors so you don't slide overnight.
            </div>
          </div>
        </div>
      </GlassCard>

      {/* 4. Altitude Cooking & Water Boiling Point */}
      <GlassCard className="w-full !p-4">
        <CardHeader
          icon={<Flame className="text-orange-400 w-4 h-4 shrink-0" />}
          title="Wilderness Cooking & Water Boiling"
        >
          <span className="text-[11px] text-slate-400 font-mono">
            {sensors.pressure !== null ? `${sensors.pressure} hPa` : 'Standard 1013 hPa'}
          </span>
        </CardHeader>

        <div className="grid grid-cols-2 gap-3 my-2">
          <div className="p-3 rounded-2xl bg-white/[0.04] border border-white/10">
            <div className="text-xs text-slate-400">Water Boiling Point</div>
            <div className="text-2xl font-black text-orange-400 font-mono mt-1">
              {boilingPoint.celsius}°C
            </div>
            <div className="text-xs text-slate-300 font-mono mt-0.5">{boilingPoint.fahrenheit}°F</div>
          </div>

          <div className="p-3 rounded-2xl bg-white/[0.04] border border-white/10">
            <div className="text-xs text-slate-400">Elevation Cooking Rule</div>
            <div className="text-sm font-bold text-white mt-1">+15% Cook Time</div>
            <div className="text-[11px] text-slate-400 mt-0.5">For dehydrated trail meals at current elevation</div>
          </div>
        </div>

        <div className="p-2.5 rounded-xl bg-orange-500/10 border border-orange-500/20 text-[11px] text-orange-200 mt-2">
          <span className="font-semibold text-orange-300">Water Purification Notice: </span>
          At high altitudes, lower air pressure reduces the boiling temperature. Maintain a continuous rolling boil for a full <strong>1 minute</strong> (or <strong>3 minutes above 2,000m / 6,500ft</strong>) to reliably sterilize water.
        </div>
      </GlassCard>

      {/* 5. Emergency SOS & Alpine Distress Whistle */}
      <GlassCard className="w-full !p-4 border-rose-500/30 bg-gradient-to-b from-rose-950/20 to-transparent">
        <CardHeader
          icon={<AlertTriangle className="text-rose-400 w-4 h-4 shrink-0" />}
          title="Emergency Alpine Distress Tools"
        >
          <span className="text-[11px] text-rose-300 font-mono">3.0 kHz Audio &amp; Visual</span>
        </CardHeader>

        <div className="grid grid-cols-2 gap-3 my-2">
          {/* Piercing Rescue Whistle */}
          <button
            onClick={handleToggleWhistle}
            className={`p-3 rounded-2xl border flex flex-col items-center justify-center transition-all active:scale-95 ${
              isWhistling
                ? 'bg-rose-500 text-white border-rose-400 shadow-[0_0_20px_rgba(244,63,94,0.6)] animate-pulse'
                : 'bg-white/[0.06] hover:bg-white/[0.12] border-white/15 text-slate-200'
            }`}
          >
            {isWhistling ? <VolumeX className="w-6 h-6 mb-1 text-white" /> : <Volume2 className="w-6 h-6 mb-1 text-rose-400" />}
            <span className="text-xs font-bold">{isWhistling ? 'Stop Whistle' : 'Alpine Whistle'}</span>
            <span className="text-[10px] opacity-80 mt-0.5">High-Pitch Acoustic</span>
          </button>

          {/* Visual Strobe Screen */}
          <button
            onClick={() => setIsSosFlashing(true)}
            className="p-3 rounded-2xl bg-white/[0.06] hover:bg-white/[0.12] border border-white/15 flex flex-col items-center justify-center text-slate-200 transition-all active:scale-95"
          >
            <AlertTriangle className="w-6 h-6 mb-1 text-amber-400" />
            <span className="text-xs font-bold">SOS Visual Flash</span>
            <span className="text-[10px] opacity-80 mt-0.5">Emergency Screen Strobe</span>
          </button>
        </div>

        {/* GPS Coordinates & Emergency Copy */}
        <div className="mt-2 p-2.5 rounded-xl bg-black/40 border border-white/10 flex items-center justify-between text-xs">
          <div className="min-w-0">
            <div className="text-[10px] uppercase font-bold text-slate-400">Current GPS Fix for Rescuers</div>
            <div className="font-mono text-white truncate text-[11px] mt-0.5">
              {sensors.latitude !== null && sensors.longitude !== null
                ? `${sensors.latitude.toFixed(5)}°, ${sensors.longitude.toFixed(5)}°`
                : 'Acquiring GPS fix…'}
            </div>
          </div>
          <button
            onClick={handleCopyCoordinates}
            disabled={sensors.latitude === null}
            className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 border border-white/15 text-xs text-slate-200 disabled:opacity-50"
          >
            {copiedCoords ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5 text-slate-300" />}
            <span>{copiedCoords ? 'Copied' : 'Copy'}</span>
          </button>
        </div>
      </GlassCard>
    </Page>
  );
};
