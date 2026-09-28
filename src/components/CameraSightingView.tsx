import React, { useRef, useEffect, useState, useCallback } from 'react';
import { SensorState, UserPreferences, Waypoint } from '../types/sensors';
import {
  calculateBearingDegrees,
  calculateDistanceMeters,
  getCardinalDirection,
} from '../utils/calculations';
import {
  Camera,
  X,
  Crosshair,
  Lock,
  Unlock,
  AlertTriangle,
  RotateCcw,
  Volume2,
  VolumeX,
} from 'lucide-react';

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
  onClose,
}) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [isFrozen, setIsFrozen] = useState<boolean>(false);
  const [frozenHeading, setFrozenHeading] = useState<number | null>(null);
  const [frozenPitch, setFrozenPitch] = useState<number | null>(null);

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
    targetAngleDiff = ((targetBearing - activeHeading + 540) % 360) - 180;
  }

  // Request rear camera stream
  useEffect(() => {
    let currentStream: MediaStream | null = null;

    async function initCamera() {
      try {
        if (!navigator.mediaDevices?.getUserMedia) {
          throw new Error('Camera not supported in this browser.');
        }

        const mediaStream = await navigator.mediaDevices.getUserMedia({
          video: {
            facingMode: { ideal: 'environment' },
            width: { ideal: 1280 },
            height: { ideal: 720 },
          },
          audio: false,
        });

        currentStream = mediaStream;
        setStream(mediaStream);

        if (videoRef.current) {
          videoRef.current.srcObject = mediaStream;
          videoRef.current.play().catch(() => {});
        }
      } catch (err: unknown) {
        console.warn('Camera initiation failed:', err);
        setCameraError(err instanceof Error ? err.message : 'Camera access denied');
      }
    }

    initCamera();

    return () => {
      if (currentStream) {
        currentStream.getTracks().forEach((track) => track.stop());
      }
    };
  }, []);

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

  return (
    <div className="fixed inset-0 z-50 bg-black flex flex-col items-center justify-between overflow-hidden select-none">
      {/* Background Video Stream */}
      <video
        ref={videoRef}
        playsInline
        muted
        autoPlay
        className="absolute inset-0 w-full h-full object-cover"
      />

      {/* Fallback if camera is unavailable */}
      {cameraError && (
        <div className="absolute inset-0 flex flex-col items-center justify-center p-6 text-center bg-slate-950/80 backdrop-blur-md z-10">
          <div className="w-14 h-14 rounded-full bg-amber-500/20 text-amber-400 flex items-center justify-center mb-3">
            <Camera className="w-7 h-7" />
          </div>
          <h3 className="text-base font-bold text-white mb-1">Optical Viewfinder Simulated</h3>
          <p className="text-xs text-slate-400 max-w-xs mb-4">
            Camera permission was restricted or camera is absent. The tactical HUD remains fully responsive with orientation sensors.
          </p>
          <div className="flex space-x-2">
            <button
              onClick={() => setCameraError(null)}
              className="px-4 py-2 rounded-xl bg-white/10 text-white text-xs font-semibold"
            >
              Continue with HUD
            </button>
          </div>
        </div>
      )}

      {/* Camera Vignette / Glass Reflection Overlay */}
      <div className="absolute inset-0 pointer-events-none bg-radial-[circle_at_center,transparent_40%,rgba(0,0,0,0.5)_90%,rgba(0,0,0,0.8)_100%]" />

      {/* Top HUD Tape (Bearing Banner) */}
      <div className="relative z-20 w-full pt-4 px-4 flex flex-col items-center">
        {/* Top Control Bar */}
        <div className="w-full flex items-center justify-between mb-2">
          <div className="flex items-center space-x-2">
            <div className="flex items-center space-x-1.5 px-3 py-1 rounded-full bg-black/50 backdrop-blur-md border border-cyan-400/30 text-cyan-300 text-xs font-mono">
              <Crosshair className="w-3.5 h-3.5 text-cyan-400" />
              <span className="font-bold">AR SIGHTING HUD</span>
            </div>
            {isFrozen && (
              <span className="px-2 py-0.5 rounded-full bg-rose-500/30 border border-rose-500/50 text-rose-300 text-[10px] font-bold tracking-wider animate-pulse">
                HOLD
              </span>
            )}
          </div>

          <button
            onClick={onClose}
            className="p-2 rounded-full bg-black/60 backdrop-blur-md border border-white/20 text-white hover:bg-white/20 transition-all"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Live Azimuth Tape */}
        <div className="relative w-72 h-14 rounded-2xl bg-black/60 backdrop-blur-md border border-cyan-400/30 overflow-hidden shadow-[0_0_15px_rgba(56,189,248,0.2)] flex flex-col items-center justify-center">
          {/* Central reticle pointer marker */}
          <div className="absolute top-0 w-2 h-2 border-l border-r border-b border-cyan-400 z-20" />
          <div className="absolute bottom-0 w-0 h-0 border-l-[4px] border-l-transparent border-r-[4px] border-r-transparent border-b-[5px] border-b-cyan-400 z-20" />

          {/* Heading Readout */}
          <div className="flex items-baseline space-x-1.5 font-mono text-cyan-300">
            <span className="text-xl font-black">{Math.round(activeHeading).toString().padStart(3, '0')}°</span>
            <span className="text-xs font-bold text-white font-sans">{getCardinalDirection(activeHeading)}</span>
          </div>

          <div className="text-[10px] text-slate-400 font-mono">
            {preferences.northMode === 'true' ? 'True North' : 'Magnetic'}
          </div>
        </div>
      </div>

      {/* Center Tactical Sighting Reticle & Artificial Horizon */}
      <div className="relative z-20 w-80 h-80 flex items-center justify-center pointer-events-none">
        {/* Roll Tilt Horizon Line */}
        <div
          className="absolute w-64 h-[1px] bg-cyan-400/40 transition-transform duration-100 ease-out"
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
              className="w-3.5 h-3.5 rounded-full border border-white shadow-[0_0_10px_currentColor] animate-bounce"
              style={{ backgroundColor: activeTarget.color }}
            />
            <span className="text-[10px] font-bold text-white bg-black/70 px-1.5 py-0.5 rounded border border-white/20 mt-1 whitespace-nowrap">
              {activeTarget.name} ({(targetDistance! >= 1000 ? `${(targetDistance! / 1000).toFixed(1)}km` : `${Math.round(targetDistance!)}m`)})
            </span>
          </div>
        )}
      </div>

      {/* Bottom Telemetry Card & Hold Button */}
      <div className="relative z-20 w-full max-w-sm px-4 pb-6 flex flex-col items-center space-y-3">
        {/* Telemetry Bar */}
        <div className="w-full grid grid-cols-3 gap-2 text-center text-xs font-mono bg-black/65 backdrop-blur-md p-2.5 rounded-2xl border border-white/15 text-white">
          <div>
            <span className="text-[9px] text-slate-400 uppercase font-sans block">Pitch (Tilt)</span>
            <span className="text-cyan-300 font-bold">{activePitch > 0 ? `+${activePitch}°` : `${activePitch}°`}</span>
          </div>
          <div>
            <span className="text-[9px] text-slate-400 uppercase font-sans block">Station Alt</span>
            <span className="text-white font-bold">{sensors.barometricAltitude.toFixed(0)}m</span>
          </div>
          <div>
            <span className="text-[9px] text-slate-400 uppercase font-sans block">QNH Baro</span>
            <span className="text-amber-300 font-bold">{sensors.pressure.toFixed(1)}</span>
          </div>
        </div>

        {/* Action Controls */}
        <div className="w-full flex items-center justify-center space-x-3">
          <button
            onClick={handleToggleFreeze}
            className={`flex-1 py-3 px-4 rounded-2xl font-bold text-xs flex items-center justify-center space-x-2 shadow-lg transition-all active:scale-95 ${
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
