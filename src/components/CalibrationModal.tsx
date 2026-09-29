import React, { useState, useEffect } from 'react';
import { SensorState } from '../types/sensors';
import { Modal } from './Modal';
import { Magnet, CheckCircle2, RotateCcw, AlertTriangle, ShieldCheck } from 'lucide-react';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  sensors: SensorState;
}

export const CalibrationModal: React.FC<Props> = ({ isOpen, onClose, sensors }) => {
  const [progress, setProgress] = useState(20);
  const [isCalibrated, setIsCalibrated] = useState(false);

  // Simulate progress when user moves device (or based on sensor gyro/accel activity)
  useEffect(() => {
    if (!isOpen) return;
    setIsCalibrated(false);
    setProgress(15);

    const interval = setInterval(() => {
      setProgress((prev) => {
        const motionDelta = Math.abs(sensors.gyroX) + Math.abs(sensors.gyroY) + Math.abs(sensors.gyroZ);
        const increment = motionDelta > 10 ? 8 : 4;
        const next = Math.min(100, prev + increment);
        if (next >= 100) {
          setIsCalibrated(true);
        }
        return next;
      });
    }, 400);

    return () => clearInterval(interval);
  }, [isOpen, sensors.gyroX, sensors.gyroY, sensors.gyroZ]);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Calibration" size="sm" icon={<Magnet className="w-5 h-5 text-cyan-400" />}>
      <div className="text-center">
          <p className="text-xs text-slate-300 mb-4 leading-relaxed">
            Move your device in a smooth <strong>Figure-8 pattern</strong> through the air to calibrate the 3-axis Hall effect magnetometer sensors and eliminate magnetic bias.
          </p>

          {/* Animated 3D Figure-8 Graphic */}
          <div className="relative w-48 h-32 mx-auto my-2 flex items-center justify-center">
            <svg className="w-full h-full" viewBox="0 0 200 120">
              <defs>
                <linearGradient id="fig8Grad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stopColor="#38bdf8" />
                  <stop offset="50%" stopColor="#a855f7" />
                  <stop offset="100%" stopColor="#34d399" />
                </linearGradient>
              </defs>

              {/* Figure 8 path */}
              <path
                d="M 50,60 C 50,20 100,20 100,60 C 100,100 150,100 150,60 C 150,20 100,20 100,60 C 100,100 50,100 50,60 Z"
                fill="none"
                stroke="rgba(255,255,255,0.15)"
                strokeWidth="6"
                strokeLinecap="round"
              />

              <path
                d="M 50,60 C 50,20 100,20 100,60 C 100,100 150,100 150,60 C 150,20 100,20 100,60 C 100,100 50,100 50,60 Z"
                fill="none"
                stroke="url(#fig8Grad)"
                strokeWidth="4"
                strokeDasharray="20 10"
                className="animate-pulse"
              />

              {/* Moving phone indicator */}
              <circle cx="100" cy="60" r="7" fill="#38bdf8" filter="drop-shadow(0 0 8px #38bdf8)">
                <animateMotion
                  path="M 50,60 C 50,20 100,20 100,60 C 100,100 150,100 150,60 C 150,20 100,20 100,60 C 100,100 50,100 50,60 Z"
                  dur="4s"
                  repeatCount="indefinite"
                />
              </circle>
            </svg>
          </div>

          {/* Progress bar */}
          <div className="w-full bg-white/10 rounded-full h-2 mb-2 overflow-hidden">
            <div
              className={`h-full transition-all duration-300 rounded-full ${
                isCalibrated ? 'bg-emerald-400 shadow-[0_0_10px_#34d399]' : 'bg-cyan-400'
              }`}
              style={{ width: `${progress}%` }}
            />
          </div>

          <div className="flex justify-between items-center text-xs font-mono mb-4 text-slate-300">
            <span>Calibration Status</span>
            <span className={isCalibrated ? 'text-emerald-400 font-bold' : 'text-cyan-400'}>
              {isCalibrated ? 'OPTIMIZED (100%)' : `${progress}%`}
            </span>
          </div>

          <div className="p-2.5 rounded-xl bg-white/[0.04] border border-white/[0.08] text-[11px] text-slate-400 flex items-center justify-between mb-4">
            <span>Current Flux: {sensors.magneticFlux.toFixed(1)} µT</span>
            <span className={sensors.magneticAnomaly ? 'text-amber-400' : 'text-emerald-400'}>
              {sensors.magneticAnomaly ? 'Metal Proximity' : 'Clean Field'}
            </span>
          </div>

          <button
            onClick={onClose}
            className="w-full py-2.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-xs shadow-md transition-all active:scale-98"
          >
            {isCalibrated ? 'Calibration Complete' : 'Close'}
          </button>
      </div>
    </Modal>
  );
};
