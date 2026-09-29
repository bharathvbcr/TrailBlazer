import React, { useRef, useEffect, useState } from 'react';
import { SensorState, UserPreferences, Waypoint } from '../types/sensors';
import {
  calculateBearingDegrees,
  calculateDistanceMeters,
  getCardinalDirection,
  convertTrueToDialBearing,
  calculateRelativeTargetAngle,
} from '../utils/calculations';
import { Camera, X, Crosshair, Lock, Unlock, ChevronLeft, ChevronRight, Loader2 } from 'lucide-react';

const TAPE_SPAN = 90; // degrees visible across the azimuth tape
const CARDINALS: Record<number, string> = { 0: 'N', 45: 'NE', 90: 'E', 135: 'SE', 180: 'S', 225: 'SW', 270: 'W', 315: 'NW' };
const formatDistance = (m: number) => (m >= 1000 ? `${(m / 1000).toFixed(1)}km` : `${Math.round(m)}m`);
const signedDelta = (a: number, b: number) => ((a - b + 540) % 360) - 180;

interface Props {
  sensors: SensorState;
  preferences: UserPreferences;
  waypoints: Waypoint[];
  onClose: () => void;
}

export const CameraSightingView: React.FC<Props> = ({
  sensors,
  preferences,
  waypoints,
  onClose: onCloseRequest,
}) => {
  const [closing, setClosing] = useState(false);
  const closeTimer = useRef<number | null>(null);
  const onClose = () => {
    if (closing) return;
    setClosing(true);
    closeTimer.current = window.setTimeout(onCloseRequest, 180);
  };
  useEffect(() => () => { if (closeTimer.current) window.clearTimeout(closeTimer.current); }, []);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [isStarting, setIsStarting] = useState(true);
  const [attempt, setAttempt] = useState(0);
  const [hudOnly, setHudOnly] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [isFrozen, setIsFrozen] = useState<boolean>(false);
  const [frozenHeading, setFrozenHeading] = useState<number | null>(null);
  const [frozenPitch, setFrozenPitch] = useState<number | null>(null);

  const live = sensors.isHardwareOrientationAvailable || sensors.isSimulationMode;
  const activeHeading = isFrozen && frozenHeading !== null
    ? frozenHeading
    : (preferences.northMode === 'true' ? sensors.trueHeading : sensors.heading);

  const activePitch = isFrozen && frozenPitch !== null ? frozenPitch : sensors.pitch;
  const activeRoll = isFrozen ? 0 : sensors.roll;

  // Active target waypoint
  const activeTarget = waypoints.find((w) => w.id === preferences.targetWaypointId);
  let targetBearing: number | null = null;
  let targetDistance: number | null = null;
  let targetAngleDiff: number | null = null;

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
    const targetDialBearing = convertTrueToDialBearing(targetBearing, preferences.northMode, sensors.declination) ?? targetBearing;
    targetAngleDiff = live ? calculateRelativeTargetAngle(targetDialBearing, activeHeading) : null;
  }

  // Request rear camera stream (re-runs on retry). `cancelled` guards against a
  // late getUserMedia resolution after the view has been closed.
  useEffect(() => {
    let cancelled = false;
    let currentStream: MediaStream | null = null;
    setCameraError(null);
    setIsStarting(true);

    async function initCamera() {
      try {
        if (!navigator.mediaDevices?.getUserMedia) {
          throw new Error('Camera not supported in this browser.');
        }
        const mediaStream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
          audio: false,
        });
        if (cancelled) {
          mediaStream.getTracks().forEach((t) => t.stop());
          return;
        }
        currentStream = mediaStream;
        if (videoRef.current) {
          videoRef.current.srcObject = mediaStream;
          videoRef.current.play().catch(() => {});
        }
      } catch (err: unknown) {
        if (cancelled) return;
        console.warn('Camera initiation failed:', err);
        setCameraError(err instanceof Error ? err.message : 'Camera access denied');
      } finally {
        if (!cancelled) setIsStarting(false);
      }
    }

    initCamera();

    return () => {
      cancelled = true;
      currentStream?.getTracks().forEach((track) => track.stop());
    };
  }, [attempt]);

  const handleToggleFreeze = () => {
    if (!isFrozen) {
      setIsFrozen(true);
      setFrozenHeading(activeHeading);
      setFrozenPitch(activePitch);
    } else {
      setIsFrozen(false);
      setFrozenHeading(null);
      setFrozenPitch(null);
    }
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [closing]);

  // Lock page scroll and restore focus while the full-screen view is open
  useEffect(() => {
    const prevOverflow = document.body.style.overflow;
    const prevFocus = document.activeElement as HTMLElement | null;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prevOverflow;
      prevFocus?.focus?.();
    };
  }, []);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Camera sighting"
      className={`${closing ? 'animate-fade-out' : 'animate-fade-in'} fixed inset-0 z-50 bg-black flex flex-col items-center justify-between overflow-hidden select-none`}
    >
      {/* Background Video Stream */}
      <video
        ref={videoRef}
        playsInline
        muted
        autoPlay
        className="absolute inset-0 w-full h-full object-cover"
      />

      {/* Camera starting / unavailable states */}
      {isStarting && (
        <div className="absolute inset-0 z-10 flex items-center justify-center bg-black">
          <div className="flex items-center gap-2 text-xs text-slate-300 animate-fade-in">
            <Loader2 className="w-4 h-4 animate-spin text-cyan-400" />
            <span>Starting camera…</span>
          </div>
        </div>
      )}
      {cameraError && !hudOnly && (
        <div className="absolute inset-0 z-30 flex flex-col items-center justify-center p-6 text-center bg-slate-950/90 backdrop-blur-md animate-fade-in">
          <div className="w-14 h-14 rounded-full bg-amber-500/15 border border-amber-500/30 text-amber-400 flex items-center justify-center mb-3">
            <Camera className="w-7 h-7" />
          </div>
          <h3 className="text-base font-bold text-white mb-1">Camera unavailable</h3>
          <p className="text-xs text-slate-400 max-w-xs mb-1">{cameraError}</p>
          <p className="text-xs text-slate-500 max-w-xs mb-5">
            You can still use the sighting HUD with orientation sensors, or allow camera access and retry.
          </p>
          <div className="flex gap-2">
            <button onClick={() => setAttempt((n) => n + 1)} className="px-4 py-2.5 rounded-xl bg-cyan-500 text-slate-950 text-xs font-bold">
              Retry camera
            </button>
            <button onClick={() => setHudOnly(true)} className="px-4 py-2.5 rounded-xl bg-white/10 border border-white/15 text-white text-xs font-semibold">
              Continue with HUD
            </button>
          </div>
          <button onClick={onClose} className="mt-4 text-xs text-slate-400 hover:text-white underline underline-offset-4">
            Close
          </button>
        </div>
      )}

      {/* Camera Vignette / Glass Reflection Overlay */}
      <div className="absolute inset-0 pointer-events-none bg-radial-[circle_at_center,transparent_40%,rgba(0,0,0,0.5)_90%,rgba(0,0,0,0.8)_100%]" />

      {/* Top HUD: controls + scrolling azimuth tape */}
      <div className="relative z-20 w-full px-4 pt-[calc(1rem+var(--safe-top))] flex flex-col items-center">
        <div className="w-full flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-black/50 backdrop-blur-md border border-cyan-400/30 text-cyan-300 text-xs font-mono">
              <Crosshair className="w-3.5 h-3.5 text-cyan-400" />
              <span className="font-bold">AR SIGHT</span>
            </div>
            {isFrozen && (
              <span className="animate-fade-in px-2 py-1 rounded-full bg-rose-500/30 border border-rose-500/50 text-rose-200 text-[11px] font-bold tracking-wider">
                HOLD
              </span>
            )}
          </div>

          <button
            onClick={onClose}
            aria-label="Close camera sighting"
            className="w-10 h-10 flex items-center justify-center rounded-full bg-black/60 backdrop-blur-md border border-white/20 text-white hover:bg-white/20"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div
          role="img"
          aria-label={live ? `Heading ${Math.round(activeHeading)} degrees ${getCardinalDirection(activeHeading)}` : 'No compass data'}
          className="relative w-full max-w-xs rounded-2xl bg-black/60 backdrop-blur-md border border-cyan-400/30 overflow-hidden shadow-[0_0_15px_rgba(56,189,248,0.2)]"
        >
          {/* Scrolling ticks */}
          <div className="relative h-9 border-b border-white/10 [mask-image:linear-gradient(to_right,transparent,black_18%,black_82%,transparent)]">
            {live && Array.from({ length: TAPE_SPAN / 5 + 1 }, (_, i) => {
              const base = Math.round((activeHeading - TAPE_SPAN / 2) / 5) * 5;
              const deg = ((base + i * 5) % 360 + 360) % 360;
              const delta = signedDelta(deg, activeHeading);
              if (Math.abs(delta) > TAPE_SPAN / 2) return null;
              const major = deg % 15 === 0;
              const label = CARDINALS[deg] ?? (deg % 15 === 0 ? String(deg) : null);
              return (
                <div
                  key={i}
                  className="absolute top-0 -translate-x-1/2 flex flex-col items-center"
                  style={{ left: `${50 + (delta / TAPE_SPAN) * 100}%` }}
                >
                  <div className={`w-px ${major ? 'h-3 bg-cyan-300' : 'h-1.5 bg-white/40'}`} />
                  {label && (
                    <span className={`mt-0.5 text-[11px] font-mono leading-none ${CARDINALS[deg] ? 'text-white font-bold' : 'text-slate-400'}`}>
                      {label}
                    </span>
                  )}
                </div>
              );
            })}

            {/* Target bearing pip */}
            {activeTarget && targetAngleDiff !== null && Math.abs(targetAngleDiff) <= TAPE_SPAN / 2 && (
              <div
                className="absolute bottom-0.5 -translate-x-1/2 w-2 h-2 rounded-full border border-white"
                style={{ left: `${50 + (targetAngleDiff / TAPE_SPAN) * 100}%`, backgroundColor: activeTarget.color }}
              />
            )}
            {/* Fixed centre marker */}
            <div className="absolute inset-y-0 left-1/2 w-px bg-cyan-400 shadow-[0_0_6px_#38bdf8]" />
          </div>

          <div className="flex items-baseline justify-center gap-2 py-1.5 font-mono">
            <span className="text-xl font-black text-cyan-300 tabular-nums">
              {live ? `${Math.round(activeHeading).toString().padStart(3, '0')}°` : '---°'}
            </span>
            <span className="text-xs font-bold text-white font-sans">{live ? getCardinalDirection(activeHeading) : 'No compass data'}</span>
            <span className="text-[11px] text-slate-400">{preferences.northMode === 'true' ? 'True' : 'Mag'}</span>
          </div>
        </div>
      </div>

      {/* Center Tactical Sighting Reticle & Artificial Horizon */}
      <div className="relative z-20 w-72 h-72 flex items-center justify-center pointer-events-none">
        {/* Roll Tilt Horizon Line */}
        <div
          className={`absolute w-64 h-[1px] bg-cyan-400/40 transition-transform duration-100 ease-out ${live ? "" : "hidden"}`}
          style={{
            transform: `rotate(${-activeRoll}deg) translateY(${activePitch * 2}px)`,
          }}
        >
          {/* Horizon ticks */}
          <div className="absolute left-0 -top-2 w-3 h-2 border-l border-t border-cyan-400" />
          <div className="absolute right-0 -top-2 w-3 h-2 border-r border-t border-cyan-400" />
        </div>

        {/* Precision Optical Crosshairs */}
        <div className="absolute w-24 h-24 rounded-full border border-cyan-400/40 flex items-center justify-center">
          <div className="w-1.5 h-1.5 rounded-full bg-cyan-400 shadow-[0_0_8px_#38bdf8]" />
          <div className="absolute w-full h-[1px] bg-cyan-400/60" />
          <div className="absolute h-full w-[1px] bg-cyan-400/60" />
          <div className="w-12 h-12 rounded-full border border-cyan-400/20" />
        </div>

        {/* Mil-dot stadia markers */}
        <div className="absolute w-48 h-48 border border-white/10 rounded-full" />

        {/* Target Waypoint Indicator Floating on Horizon */}
        {activeTarget && targetAngleDiff !== null && Math.abs(targetAngleDiff) < 40 && (
          <div
            className="absolute z-30 flex flex-col items-center transition-transform duration-150"
            style={{
              transform: `translateX(${(targetAngleDiff / 40) * 130}px) translateY(-40px)`,
            }}
          >
            <div
              className="w-3.5 h-3.5 rounded-full border border-white shadow-[0_0_10px_currentColor]"
              style={{ backgroundColor: activeTarget.color }}
            />
            <span className="text-[11px] font-bold text-white bg-black/70 px-1.5 py-0.5 rounded border border-white/20 mt-1 whitespace-nowrap">
              {activeTarget.name} ({formatDistance(targetDistance!)})
            </span>
          </div>
        )}
      </div>

      {/* Off-screen target hint */}
      {activeTarget && targetAngleDiff !== null && Math.abs(targetAngleDiff) >= 40 && (
        <div
          className={`absolute z-20 top-1/2 -translate-y-1/2 ${targetAngleDiff < 0 ? 'left-2' : 'right-2'} pointer-events-none animate-fade-in`}
        >
          <div className="flex items-center gap-1 px-2 py-1.5 rounded-xl bg-black/65 backdrop-blur-md border border-white/20 text-white">
            {targetAngleDiff < 0 && <ChevronLeft className="w-4 h-4 text-cyan-300" />}
            <div className="text-center leading-tight">
              <div className="text-[11px] font-bold max-w-[6rem] truncate">{activeTarget.name}</div>
              <div className="text-[11px] font-mono text-cyan-300">
                {Math.abs(Math.round(targetAngleDiff))}° · {formatDistance(targetDistance!)}
              </div>
            </div>
            {targetAngleDiff > 0 && <ChevronRight className="w-4 h-4 text-cyan-300" />}
          </div>
        </div>
      )}

      {/* Bottom Telemetry Card & Hold Button */}
      <div className="relative z-20 w-full max-w-sm px-4 pb-[calc(1.5rem+var(--safe-bottom))] flex flex-col items-center space-y-3">
        {/* Telemetry Bar */}
        <div className="w-full grid grid-cols-3 gap-2 text-center text-xs font-mono bg-black/65 backdrop-blur-md p-2.5 rounded-2xl border border-white/15 text-white">
          <div>
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Pitch</span>
            <span className="text-cyan-300 font-bold">{!live ? '—' : activePitch > 0 ? `+${activePitch}°` : `${activePitch}°`}</span>
          </div>
          <div>
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Altitude</span>
            <span className="text-white font-bold">{sensors.barometricAltitude === null ? '—' : `${sensors.barometricAltitude.toFixed(0)}m`}</span>
          </div>
          <div>
            <span className="text-[11px] text-slate-400 uppercase font-sans block">Pressure</span>
            <span className="text-amber-300 font-bold">{sensors.pressure === null ? '—' : <>{sensors.pressure.toFixed(1)} <span className="text-[11px] text-slate-400">hPa</span></>}</span>
          </div>
        </div>

        {/* Action Controls */}
        <div className="w-full flex items-center justify-center space-x-3">
          <button
            onClick={handleToggleFreeze}
            className={`flex-1 py-3.5 px-4 rounded-2xl font-bold text-sm flex items-center justify-center space-x-2 shadow-lg ${
              isFrozen
                ? 'bg-rose-500 hover:bg-rose-400 text-white shadow-rose-500/30'
                : 'bg-cyan-500 hover:bg-cyan-400 text-slate-950 shadow-cyan-500/30'
            }`}
          >
            {isFrozen ? (
              <>
                <Unlock className="w-4 h-4" />
                <span>Resume Live AR</span>
              </>
            ) : (
              <>
                <Lock className="w-4 h-4" />
                <span>Hold / Sight Landmark</span>
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};
