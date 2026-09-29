import React, { useState } from 'react';
import { Compass, MapPin, Camera, Sparkles, Loader2 } from 'lucide-react';
import { Modal } from './Modal';

interface Props {
  isOpen: boolean;
  /** Dismiss without enabling anything. */
  onSkip: () => void;
  /** Ask for motion + location access. Resolves with which grants succeeded. */
  onEnable: () => Promise<{ motion: boolean }>;
  onUseSimulator: () => void;
  locationEnabled: boolean;
}

const Row: React.FC<{ icon: React.ReactNode; title: string; body: string; tag?: string }> = ({ icon, title, body, tag }) => (
  <div className="flex gap-3 p-3 rounded-2xl bg-white/[0.04] border border-white/[0.07]">
    <div className="w-9 h-9 rounded-xl bg-cyan-500/15 border border-cyan-500/30 text-cyan-300 flex items-center justify-center shrink-0">
      {icon}
    </div>
    <div className="min-w-0">
      <div className="flex items-center gap-2">
        <h4 className="text-xs font-bold text-white">{title}</h4>
        {tag && <span className="text-[11px] px-1.5 rounded-full bg-white/10 text-slate-300">{tag}</span>}
      </div>
      <p className="text-[11px] text-slate-400 leading-relaxed mt-0.5">{body}</p>
    </div>
  </div>
);

export const OnboardingModal: React.FC<Props> = ({ isOpen, onSkip, onEnable, onUseSimulator, locationEnabled }) => {
  const [busy, setBusy] = useState(false);

  const handleEnable = async () => {
    setBusy(true);
    try {
      await onEnable();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onSkip}
      title="Set up sensors"
      size="sm"
      icon={<Compass className="w-5 h-5 text-cyan-400" />}
    >
      <p className="text-xs text-slate-300 leading-relaxed mb-3">
        AeroGlass reads your device sensors locally. Nothing leaves your device. Your browser will ask for these permissions:
      </p>

      <div className="space-y-2 mb-4">
        <Row
          icon={<Compass className="w-4 h-4" />}
          title="Motion & orientation"
          body="Heading, tilt and the level. iPhones ask you to allow this explicitly."
        />
        <Row
          icon={<MapPin className="w-4 h-4" />}
          title="Location"
          body="GPS position, speed, altitude and tracks."
          tag={locationEnabled ? 'On' : undefined}
        />
        <Row
          icon={<Camera className="w-4 h-4" />}
          title="Camera & microphone"
          body="Only asked when you open AR Sight or turn on the sound meter."
          tag="Later"
        />
      </div>

      <div className="space-y-2">
        <button
          onClick={handleEnable}
          disabled={busy}
          className="w-full py-3 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-sm shadow-md flex items-center justify-center gap-2 disabled:opacity-70"
        >
          {busy && <Loader2 className="w-4 h-4 animate-spin" />}
          <span>{busy ? 'Waiting for permission…' : 'Enable sensors'}</span>
        </button>
        <div className="grid grid-cols-2 gap-2">
          <button
            onClick={onUseSimulator}
            className="py-2.5 rounded-xl bg-white/10 hover:bg-white/15 border border-white/15 text-xs font-semibold text-slate-200 flex items-center justify-center gap-1.5"
          >
            <Sparkles className="w-3.5 h-3.5 text-cyan-300" />
            <span>Try simulator</span>
          </button>
          <button onClick={onSkip} className="py-2.5 rounded-xl text-xs font-semibold text-slate-400 hover:text-white hover:bg-white/5">
            Not now
          </button>
        </div>
      </div>
    </Modal>
  );
};
