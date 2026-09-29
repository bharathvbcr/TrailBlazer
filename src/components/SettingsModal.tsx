import React, { useMemo } from 'react';
import { UserPreferences, SensorState, ThemePalette, SpeedUnit } from '../types/sensors';
import { convertSpeedBetweenUnits } from '../utils/calculations';
import { Modal } from './Modal';
import {
  Settings,
  Palette,
  Compass,
  Vibrate,
  Check,
  Shield,
  Layers,
  Volume2,
  AlertTriangle,
  Zap,
} from 'lucide-react';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  preferences: UserPreferences;
  sensors: SensorState;
  onUpdatePreferences: (prefs: Partial<UserPreferences>) => void;
  onToggleSimulation: () => void;
  onOpenCalibration: () => void;
}

const PALETTES: { id: ThemePalette; name: string; gradient: string }[] = [
  { id: 'cyan', name: 'Cyber Cyan', gradient: 'from-cyan-400 to-blue-500' },
  { id: 'emerald', name: 'Aurora Green', gradient: 'from-emerald-400 to-teal-500' },
  { id: 'amber', name: 'Sunset Amber', gradient: 'from-amber-400 to-orange-500' },
  { id: 'violet', name: 'Cosmic Violet', gradient: 'from-purple-400 to-indigo-500' },
  { id: 'tactical_red', name: 'Night Red (Tactical)', gradient: 'from-rose-600 to-red-800' },
  { id: 'phosphor_green', name: 'Phosphor Green', gradient: 'from-green-500 to-emerald-700' },
];

export const SettingsModal: React.FC<Props> = ({
  isOpen,
  onClose,
  preferences,
  sensors,
  onUpdatePreferences,
  onToggleSimulation,
  onOpenCalibration,
}) => {
  const speedPresets = useMemo(() => {
    switch (preferences.speedUnit) {
      case 'mph':
        return [
          { label: 'Walk', val: 3 },
          { label: 'Run', val: 8 },
          { label: 'Bike', val: 15 },
          { label: 'City', val: 30 },
          { label: 'Suburban', val: 45 },
          { label: 'Highway', val: 65 },
        ];
      case 'kt':
        return [
          { label: 'Drift', val: 3 },
          { label: 'Slow', val: 8 },
          { label: 'Cruise', val: 15 },
          { label: 'Planing', val: 25 },
          { label: 'Fast', val: 40 },
          { label: 'Transit', val: 60 },
        ];
      case 'm/s':
        return [
          { label: 'Walk', val: 1.5 },
          { label: 'Jog', val: 3.5 },
          { label: 'Cycle', val: 7 },
          { label: 'Sprint', val: 14 },
          { label: 'Auto', val: 20 },
          { label: 'Fast', val: 30 },
        ];
      case 'km/h':
      default:
        return [
          { label: 'Walk', val: 5 },
          { label: 'Run', val: 12 },
          { label: 'Bike', val: 25 },
          { label: 'City', val: 50 },
          { label: 'Rural', val: 80 },
          { label: 'Highway', val: 100 },
        ];
    }
  }, [preferences.speedUnit]);

  const maxSliderSpeed = useMemo(() => {
    switch (preferences.speedUnit) {
      case 'mph': return 120;
      case 'kt': return 100;
      case 'm/s': return 50;
      case 'km/h':
      default: return 160;
    }
  }, [preferences.speedUnit]);

  const handleSpeedUnitChange = (newUnit: SpeedUnit) => {
    const convertedThreshold = convertSpeedBetweenUnits(
      preferences.speedAlertThreshold,
      preferences.speedUnit,
      newUnit
    );
    onUpdatePreferences({
      speedUnit: newUnit,
      speedAlertThreshold: Math.round(convertedThreshold),
    });
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Settings" icon={<Settings className="w-5 h-5 text-cyan-400" />}>
          <div className="space-y-4 text-xs">
            {/* 1. Material You Liquid Glass Palette */}
            <div>
              <label className="flex items-center space-x-1.5 text-slate-300 font-semibold mb-2">
                <Palette className="w-4 h-4 text-cyan-400" />
                <span>Liquid Glass Palette & Night Modes</span>
              </label>
              <div className="grid grid-cols-2 gap-2">
                {PALETTES.map((p) => (
                  <button
                    key={p.id}
                    onClick={() => onUpdatePreferences({ palette: p.id })}
                    className={`flex items-center space-x-2 p-2.5 rounded-xl border transition-all ${
                      preferences.palette === p.id
                        ? 'border-white/50 bg-white/15 shadow-md'
                        : 'border-white/10 bg-white/[0.04] hover:bg-white/10'
                    }`}
                  >
                    <div className={`w-4 h-4 rounded-full bg-gradient-to-tr ${p.gradient}`} />
                    <span className="text-xs text-white font-medium">{p.name}</span>
                    {preferences.palette === p.id && (
                      <Check className="w-3.5 h-3.5 text-cyan-400 ml-auto" />
                    )}
                  </button>
                ))}
              </div>
            </div>

            {/* 2. Unit Preferences */}
            <div className="pt-2 border-t border-white/[0.08]">
              <label className="flex items-center space-x-1.5 text-slate-300 font-semibold mb-2">
                <Layers className="w-4 h-4 text-cyan-400" />
                <span>Measurement Units</span>
              </label>

              <div className="space-y-2.5">
                <div className="flex items-center justify-between">
                  <span className="text-slate-400">Altitude Unit</span>
                  <div className="flex bg-white/10 rounded-lg p-0.5 border border-white/10">
                    <button
                      onClick={() => onUpdatePreferences({ altitudeUnit: 'm' })}
                      className={`px-3 py-1 rounded-md transition-all ${
                        preferences.altitudeUnit === 'm'
                          ? 'bg-cyan-500 text-slate-950 font-bold'
                          : 'text-slate-300'
                      }`}
                    >
                      Meters (m)
                    </button>
                    <button
                      onClick={() => onUpdatePreferences({ altitudeUnit: 'ft' })}
                      className={`px-3 py-1 rounded-md transition-all ${
                        preferences.altitudeUnit === 'ft'
                          ? 'bg-cyan-500 text-slate-950 font-bold'
                          : 'text-slate-300'
                      }`}
                    >
                      Feet (ft)
                    </button>
                  </div>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-400">Pressure Unit</span>
                  <div className="flex bg-white/10 rounded-lg p-0.5 border border-white/10">
                    {(['hPa', 'inHg', 'mmHg', 'psi'] as const).map((unit) => (
                      <button
                        key={unit}
                        onClick={() => onUpdatePreferences({ pressureUnit: unit })}
                        className={`px-2 py-1 rounded-md transition-all ${
                          preferences.pressureUnit === unit
                            ? 'bg-cyan-500 text-slate-950 font-bold'
                            : 'text-slate-300'
                        }`}
                      >
                        {unit}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-400">Velocity Unit</span>
                  <div className="flex bg-white/10 rounded-lg p-0.5 border border-white/10">
                    {(['km/h', 'mph', 'kt', 'm/s'] as const).map((unit) => (
                      <button
                        key={unit}
                        onClick={() => handleSpeedUnitChange(unit)}
                        className={`px-2 py-1 rounded-md transition-all ${
                          preferences.speedUnit === unit
                            ? 'bg-cyan-500 text-slate-950 font-bold'
                            : 'text-slate-300'
                        }`}
                      >
                        {unit}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-400">Heading Format</span>
                  <div className="flex bg-white/10 rounded-lg p-0.5 border border-white/10">
                    <button
                      onClick={() => onUpdatePreferences({ headingUnit: 'deg' })}
                      className={`px-2.5 py-1 rounded-md transition-all ${
                        preferences.headingUnit === 'deg'
                          ? 'bg-cyan-500 text-slate-950 font-bold'
                          : 'text-slate-300'
                      }`}
                    >
                      Degrees (360°)
                    </button>
                    <button
                      onClick={() => onUpdatePreferences({ headingUnit: 'mils' })}
                      className={`px-2.5 py-1 rounded-md transition-all ${
                        preferences.headingUnit === 'mils'
                          ? 'bg-cyan-500 text-slate-950 font-bold'
                          : 'text-slate-300'
                      }`}
                    >
                      NATO Mils (6400)
                    </button>
                  </div>
                </div>
              </div>
            </div>

            {/* 3. Speed Threshold Alert Section */}
            <div className="pt-2 border-t border-white/[0.08] space-y-3">
              <div className="flex items-center justify-between">
                <div>
                  <label className="flex items-center space-x-1.5 text-slate-200 font-semibold">
                    <Zap className="w-4 h-4 text-cyan-400" />
                    <span>GPS Speed Limit & Threshold Alert</span>
                  </label>
                  <span className="text-[11px] text-slate-400 block mt-0.5">
                    Trigger haptic pulse and visual warnings when exceeding limit
                  </span>
                </div>
                <input
                  aria-label="Enable speed limit alert"
                  type="checkbox"
                  checked={preferences.speedAlertEnabled}
                  onChange={(e) => onUpdatePreferences({ speedAlertEnabled: e.target.checked })}
                  className="w-4 h-4 accent-cyan-400 rounded cursor-pointer"
                />
              </div>

              {preferences.speedAlertEnabled && (
                <div className="space-y-3 p-3 rounded-2xl bg-white/[0.03] border border-white/[0.08]">
                  {/* Threshold Slider & Number Input */}
                  <div>
                    <div className="flex items-center justify-between mb-1.5 text-xs text-slate-300">
                      <span className="font-medium">Speed Limit Threshold</span>
                      <div className="flex items-center space-x-1.5">
                        <input
                          aria-label="Speed limit threshold value"
                          type="number"
                          min="1"
                          max={maxSliderSpeed}
                          value={preferences.speedAlertThreshold}
                          onChange={(e) =>
                            onUpdatePreferences({
                              speedAlertThreshold: Math.max(1, Number(e.target.value)),
                            })
                          }
                          className="w-16 px-1.5 py-0.5 rounded-lg bg-white/10 border border-white/20 text-right font-mono font-bold text-white text-xs focus:outline-none focus:border-cyan-400"
                        />
                        <span className="font-mono text-cyan-400 font-bold text-xs">
                          {preferences.speedUnit}
                        </span>
                      </div>
                    </div>

                    <input
                      aria-label="Speed limit threshold slider"
                      type="range"
                      min="1"
                      max={maxSliderSpeed}
                      step={preferences.speedUnit === 'm/s' ? 0.5 : 1}
                      value={preferences.speedAlertThreshold}
                      onChange={(e) =>
                        onUpdatePreferences({ speedAlertThreshold: Number(e.target.value) })
                      }
                      className="w-full h-1.5 bg-white/20 rounded-lg appearance-none cursor-pointer accent-cyan-400"
                    />
                  </div>

                  {/* Quick Presets */}
                  <div>
                    <span className="text-[11px] text-slate-400 uppercase font-mono block mb-1">
                      Quick Presets:
                    </span>
                    <div className="grid grid-cols-3 gap-1.5">
                      {speedPresets.map((preset) => (
                        <button
                          key={preset.label}
                          onClick={() => onUpdatePreferences({ speedAlertThreshold: preset.val })}
                          className={`py-1.5 px-1 rounded-lg border text-[11px] font-mono transition-all leading-tight ${
                            Math.abs(preferences.speedAlertThreshold - preset.val) < 0.5
                              ? 'bg-cyan-500/25 border-cyan-400 text-cyan-200 font-bold shadow-sm'
                              : 'bg-white/5 hover:bg-white/10 border-white/10 text-slate-300'
                          }`}
                        >
                          {preset.label} <span className="opacity-70">{preset.val}</span>
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* Alert Modalities (Haptic, Visual, Audio) */}
                  <div className="pt-2 border-t border-white/[0.06] space-y-2">
                    <span className="text-[11px] text-slate-400 uppercase font-mono block">
                      Alert Modalities:
                    </span>

                    <label className="flex items-center justify-between cursor-pointer">
                      <div className="flex items-center space-x-2">
                        <Vibrate className="w-3.5 h-3.5 text-emerald-400" />
                        <div>
                          <div className="text-slate-300 text-xs">Haptic Warning Pulse</div>
                          <div className="text-[11px] text-slate-400">Rhythmic vibration alert when exceeding limit</div>
                        </div>
                      </div>
                      <input
                        aria-label="Haptic warning pulse"
                        type="checkbox"
                        checked={preferences.speedAlertHaptic}
                        onChange={(e) => onUpdatePreferences({ speedAlertHaptic: e.target.checked })}
                        className="w-4 h-4 accent-cyan-400 rounded cursor-pointer"
                      />
                    </label>

                    <label className="flex items-center justify-between cursor-pointer">
                      <div className="flex items-center space-x-2">
                        <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />
                        <div>
                          <div className="text-slate-300 text-xs">Visual Alert Banner & Flashing HUD</div>
                          <div className="text-[11px] text-slate-400">Pulsing red glow & on-screen overspeed banner</div>
                        </div>
                      </div>
                      <input
                        aria-label="Visual alert banner"
                        type="checkbox"
                        checked={preferences.speedAlertVisual}
                        onChange={(e) => onUpdatePreferences({ speedAlertVisual: e.target.checked })}
                        className="w-4 h-4 accent-cyan-400 rounded cursor-pointer"
                      />
                    </label>

                    <label className="flex items-center justify-between cursor-pointer">
                      <div className="flex items-center space-x-2">
                        <Volume2 className="w-3.5 h-3.5 text-cyan-400" />
                        <div>
                          <div className="text-slate-300 text-xs">Acoustic Warning Tone</div>
                          <div className="text-[11px] text-slate-400">Dual-tone audio beep on speed breach</div>
                        </div>
                      </div>
                      <input
                        aria-label="Acoustic warning tone"
                        type="checkbox"
                        checked={preferences.speedAlertAudio}
                        onChange={(e) => onUpdatePreferences({ speedAlertAudio: e.target.checked })}
                        className="w-4 h-4 accent-cyan-400 rounded cursor-pointer"
                      />
                    </label>
                  </div>
                </div>
              )}
            </div>

            {/* 3. Audio & Haptics Feedback */}
            <div className="pt-2 border-t border-white/[0.08] space-y-2.5">
              <label className="flex items-center space-x-1.5 text-slate-300 font-semibold mb-2">
                <Volume2 className="w-4 h-4 text-cyan-400" />
                <span>Audio & Haptics Feedback</span>
              </label>

              <div className="flex items-center justify-between">
                <div>
                  <div className="text-slate-200 font-medium">Haptic Feedback</div>
                  <div className="text-[11px] text-slate-400">Vibrate on cardinals and 0° level</div>
                </div>
                <input
                  aria-label="Haptic feedback"
                  type="checkbox"
                  checked={preferences.hapticsEnabled}
                  onChange={(e) => onUpdatePreferences({ hapticsEnabled: e.target.checked })}
                  className="w-4 h-4 accent-cyan-400 rounded cursor-pointer"
                />
              </div>

              <div className="flex items-center justify-between">
                <div>
                  <div className="text-slate-200 font-medium">Acoustic Compass Clicks</div>
                  <div className="text-[11px] text-slate-400">Mechanical bezel clicks on degree changes</div>
                </div>
                <input
                  aria-label="Audio feedback clicks"
                  type="checkbox"
                  checked={preferences.audioFeedbackEnabled}
                  onChange={(e) => onUpdatePreferences({ audioFeedbackEnabled: e.target.checked })}
                  className="w-4 h-4 accent-cyan-400 rounded cursor-pointer"
                />
              </div>

              <div className="flex items-center justify-between">
                <div>
                  <div className="text-slate-200 font-medium">Glider Audio Variometer</div>
                  <div className="text-[11px] text-slate-400">Acoustic climb beeps and sink tone</div>
                </div>
                <input
                  aria-label="Audio variometer"
                  type="checkbox"
                  checked={preferences.audioVariometerEnabled}
                  onChange={(e) => onUpdatePreferences({ audioVariometerEnabled: e.target.checked })}
                  className="w-4 h-4 accent-cyan-400 rounded cursor-pointer"
                />
              </div>

              <div className="flex items-center justify-between">
                <div>
                  <div className="text-slate-200 font-medium">Keep Screen Awake</div>
                  <div className="text-[11px] text-slate-400">Prevent sleep during navigation</div>
                </div>
                <input
                  aria-label="Keep screen awake"
                  type="checkbox"
                  checked={preferences.wakeLockEnabled}
                  onChange={(e) => onUpdatePreferences({ wakeLockEnabled: e.target.checked })}
                  className="w-4 h-4 accent-cyan-400 rounded cursor-pointer"
                />
              </div>
            </div>

            {/* 4. Sensor Calibration & Simulation */}
            <div className="pt-2 border-t border-white/[0.08] space-y-2.5">
              <label className="flex items-center space-x-1.5 text-slate-300 font-semibold mb-2">
                <Compass className="w-4 h-4 text-cyan-400" />
                <span>Sensor Calibration & Diagnostics</span>
              </label>

              <div className="flex items-center justify-between">
                <div>
                  <div className="text-slate-200 font-medium">Magnetometer Calibration</div>
                  <div className="text-[11px] text-slate-400">Figure-8 motion guide</div>
                </div>
                <button
                  onClick={() => {
                    onClose();
                    onOpenCalibration();
                  }}
                  className="px-3 py-1 rounded-lg bg-cyan-500/20 border border-cyan-500/40 text-cyan-300 text-xs font-semibold hover:bg-cyan-500/30"
                >
                  Calibrate
                </button>
              </div>

              <div className="flex items-center justify-between">
                <div>
                  <div className="text-slate-200 font-medium">Interactive Simulator Drawer</div>
                  <div className="text-[11px] text-slate-400">Manual sliders for testing dials</div>
                </div>
                <button
                  onClick={onToggleSimulation}
                  className={`px-3 py-1 rounded-lg border text-xs font-semibold transition-all ${
                    sensors.isSimulationMode
                      ? 'bg-cyan-500/20 border-cyan-500/50 text-cyan-300'
                      : 'bg-white/10 border-white/15 text-slate-300'
                  }`}
                >
                  {sensors.isSimulationMode ? 'Enabled' : 'Disabled'}
                </button>
              </div>
            </div>

            {/* 5. Hardware Diagnostic Status */}
            <div className="pt-2 border-t border-white/[0.08]">
              <div className="text-[11px] text-slate-400 uppercase font-semibold tracking-wider mb-2 flex items-center space-x-1">
                <Shield className="w-3.5 h-3.5 text-slate-400" />
                <span>Sensor Diagnostics</span>
              </div>
              <div className="p-2.5 rounded-xl bg-white/[0.03] border border-white/[0.06] space-y-1.5 font-mono text-[11px]">
                <div className="flex justify-between">
                  <span className="text-slate-400">Orientation (Magnetometer/Gyro):</span>
                  <span className={sensors.isHardwareOrientationAvailable ? 'text-emerald-400' : 'text-amber-400'}>
                    {sensors.isHardwareOrientationAvailable ? 'Active' : 'Fallback / Simulated'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-400">Accelerometer (G-Force):</span>
                  <span className={sensors.isHardwareMotionAvailable ? 'text-emerald-400' : 'text-amber-400'}>
                    {sensors.isHardwareMotionAvailable ? 'Active' : 'Fallback / Simulated'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-400">GPS Satellite Positioning:</span>
                  <span className={sensors.isGpsAvailable ? 'text-emerald-400' : 'text-amber-400'}>
                    {sensors.isGpsAvailable ? 'Locked' : 'Acquiring'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-400">Screen WakeLock:</span>
                  <span className={sensors.wakeLockActive ? 'text-emerald-400' : 'text-slate-400'}>
                    {sensors.wakeLockActive ? 'Active (Awake)' : 'Inactive'}
                  </span>
                </div>
              </div>
            </div>

            <div className="pt-2">
              <button
                onClick={onClose}
                className="w-full py-2.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-xs shadow-md transition-all active:scale-98"
              >
                Done
              </button>
            </div>
          </div>
    </Modal>
  );
};
