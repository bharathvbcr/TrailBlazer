/**
 * Audio synthesis engine for mechanical compass clicks,
 * cardinal orientation locks, level bells, and glider-style audio variometer.
 */

let audioCtx: AudioContext | null = null;
let varioOsc: OscillatorNode | null = null;
let varioGain: GainNode | null = null;

function getAudioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  if (!audioCtx) {
    const AudioContextClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (AudioContextClass) {
      audioCtx = new AudioContextClass();
    }
  }
  if (audioCtx && audioCtx.state === 'suspended') {
    audioCtx.resume().catch(() => {});
  }
  return audioCtx;
}

/**
 * Play a subtle mechanical tick sound (like a precision bearing/bezel click)
 */
export function playCompassTick() {
  try {
    const ctx = getAudioContext();
    if (!ctx) return;

    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'triangle';
    osc.frequency.setValueAtTime(1400, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(300, ctx.currentTime + 0.015);

    gain.gain.setValueAtTime(0.04, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.015);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start();
    osc.stop(ctx.currentTime + 0.02);
  } catch {
    // ignore
  }
}

/**
 * Play harmonic chime when passing North or reaching 0° level
 */
export function playChime(isLevelLock: boolean = false) {
  try {
    const ctx = getAudioContext();
    if (!ctx) return;

    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'sine';
    const freq = isLevelLock ? 880 : 587.33; // A5 or D5
    osc.frequency.setValueAtTime(freq, ctx.currentTime);

    gain.gain.setValueAtTime(0.08, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.15);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start();
    osc.stop(ctx.currentTime + 0.16);
  } catch {
    // ignore
  }
}

/**
 * Audio Variometer sound (used by paragliders / sailplane pilots and hikers)
 * Positive vertical speed (> +0.3 m/s) emits pulsing higher pitch beeps
 * Negative vertical speed (< -0.8 m/s) emits a low sink tone
 */
export function updateAudioVariometer(verticalSpeedMs: number, enabled: boolean) {
  try {
    const ctx = getAudioContext();
    if (!ctx || !enabled) {
      stopAudioVariometer();
      return;
    }

    if (verticalSpeedMs > 0.3) {
      // Climb: high beeps
      if (!varioOsc) {
        varioOsc = ctx.createOscillator();
        varioGain = ctx.createGain();
        varioOsc.type = 'sine';
        varioOsc.connect(varioGain);
        varioGain.connect(ctx.destination);
        varioOsc.start();
      }

      const freq = Math.min(1800, 600 + verticalSpeedMs * 180);
      varioOsc.frequency.setValueAtTime(freq, ctx.currentTime);

      // Pulse gain for climb beeps
      const pulseRate = Math.min(8, 2 + verticalSpeedMs * 1.5);
      const isPulseOn = Math.sin(ctx.currentTime * pulseRate * 2 * Math.PI) > 0;
      varioGain?.gain.setValueAtTime(isPulseOn ? 0.05 : 0.0001, ctx.currentTime);
    } else if (verticalSpeedMs < -1.2) {
      // Sink tone (steady low hum)
      if (!varioOsc) {
        varioOsc = ctx.createOscillator();
        varioGain = ctx.createGain();
        varioOsc.type = 'sawtooth';
        varioOsc.connect(varioGain);
        varioGain.connect(ctx.destination);
        varioOsc.start();
      }
      varioOsc.frequency.setValueAtTime(220, ctx.currentTime);
      varioGain?.gain.setValueAtTime(0.03, ctx.currentTime);
    } else {
      stopAudioVariometer();
    }
  } catch {
    stopAudioVariometer();
  }
}

export function stopAudioVariometer() {
  if (varioOsc) {
    try {
      varioOsc.stop();
      varioOsc.disconnect();
    } catch {}
    varioOsc = null;
  }
  if (varioGain) {
    try {
      varioGain.disconnect();
    } catch {}
    varioGain = null;
  }
}

/**
 * Play an urgent dual-tone overspeed warning chime (750 Hz -> 950 Hz)
 */
export function playSpeedAlertTone() {
  try {
    const ctx = getAudioContext();
    if (!ctx) return;

    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'sawtooth';
    osc.frequency.setValueAtTime(750, ctx.currentTime);
    osc.frequency.linearRampToValueAtTime(950, ctx.currentTime + 0.12);

    gain.gain.setValueAtTime(0.08, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.25);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start();
    osc.stop(ctx.currentTime + 0.26);
  } catch {
    // ignore
  }
}

let whistleOsc1: OscillatorNode | null = null;
let whistleOsc2: OscillatorNode | null = null;
let whistleGain: GainNode | null = null;

/**
 * Play high-decibel piercing dual-frequency outdoor emergency whistle (2800Hz + 3100Hz)
 */
export function playEmergencyWhistle(): boolean {
  try {
    const ctx = getAudioContext();
    if (!ctx) return false;

    stopEmergencyWhistle();

    whistleOsc1 = ctx.createOscillator();
    whistleOsc2 = ctx.createOscillator();
    whistleGain = ctx.createGain();

    whistleOsc1.type = 'sine';
    whistleOsc1.frequency.setValueAtTime(2850, ctx.currentTime);

    whistleOsc2.type = 'triangle';
    whistleOsc2.frequency.setValueAtTime(3150, ctx.currentTime);

    whistleGain.gain.setValueAtTime(0.12, ctx.currentTime);

    whistleOsc1.connect(whistleGain);
    whistleOsc2.connect(whistleGain);
    whistleGain.connect(ctx.destination);

    whistleOsc1.start();
    whistleOsc2.start();
    return true;
  } catch {
    return false;
  }
}

export function stopEmergencyWhistle() {
  if (whistleOsc1) {
    try {
      whistleOsc1.stop();
      whistleOsc1.disconnect();
    } catch {}
    whistleOsc1 = null;
  }
  if (whistleOsc2) {
    try {
      whistleOsc2.stop();
      whistleOsc2.disconnect();
    } catch {}
    whistleOsc2 = null;
  }
  if (whistleGain) {
    try {
      whistleGain.disconnect();
    } catch {}
    whistleGain = null;
  }
}
