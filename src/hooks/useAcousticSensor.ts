import { useState, useEffect, useRef, useCallback } from 'react';

export interface AcousticState {
  isActive: boolean;
  decibels: number | null; // input level in dBFS (0 = digital full scale); uncalibrated, not SPL
  peakDecibels: number | null;
  noiseCategory: 'quiet' | 'moderate' | 'loud' | 'extreme';
  categoryLabel: string;
  hasPermission: boolean;
}

export function useAcousticSensor(enabled: boolean = false) {
  const [state, setState] = useState<AcousticState>({
    isActive: false,
    decibels: null,
    peakDecibels: null,
    noiseCategory: 'quiet',
    categoryLabel: 'Waiting for microphone',
    hasPermission: false,
  });

  const audioCtxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const animFrameRef = useRef<number | null>(null);
  const peakRef = useRef<number | null>(null);

  const startListening = useCallback(async () => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) return;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      streamRef.current = stream;

      const AudioContextClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const ctx = new AudioContextClass();
      audioCtxRef.current = ctx;

      const source = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      analyser.smoothingTimeConstant = 0.8;
      source.connect(analyser);
      analyserRef.current = analyser;

      const dataArray = new Float32Array(analyser.fftSize);

      const updateLevel = () => {
        if (!analyserRef.current) return;
        analyserRef.current.getFloatTimeDomainData(dataArray);

        // RMS of the raw waveform, expressed relative to digital full scale (dBFS).
        // Browsers give no calibrated SPL, so this is an honest relative input level.
        let sum = 0;
        for (let i = 0; i < dataArray.length; i++) {
          sum += dataArray[i] * dataArray[i];
        }
        const rms = Math.sqrt(sum / dataArray.length);
        const db = Math.round(Math.max(-90, 20 * Math.log10(Math.max(rms, 1e-5))));

        if (peakRef.current === null || db > peakRef.current) {
          peakRef.current = db;
        }

        let category: AcousticState['noiseCategory'] = 'quiet';
        let label = 'Quiet input (< −50 dBFS)';

        if (db > -20) {
          category = 'extreme';
          label = 'Very loud input (> −20 dBFS)';
        } else if (db > -35) {
          category = 'loud';
          label = 'Loud input (−35 to −20 dBFS)';
        } else if (db > -50) {
          category = 'moderate';
          label = 'Moderate input (−50 to −35 dBFS)';
        }

        setState({
          isActive: true,
          decibels: db,
          peakDecibels: peakRef.current,
          noiseCategory: category,
          categoryLabel: label,
          hasPermission: true,
        });

        animFrameRef.current = requestAnimationFrame(updateLevel);
      };

      animFrameRef.current = requestAnimationFrame(updateLevel);
    } catch (err) {
      console.warn('Acoustic sensor permission denied or unavailable:', err);
      setState((prev) => ({ ...prev, isActive: false, hasPermission: false }));
    }
  }, []);

  const stopListening = useCallback(() => {
    if (animFrameRef.current) {
      cancelAnimationFrame(animFrameRef.current);
      animFrameRef.current = null;
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    }
    if (audioCtxRef.current) {
      audioCtxRef.current.close().catch(() => {});
      audioCtxRef.current = null;
    }
    setState((prev) => ({ ...prev, isActive: false }));
  }, []);

  const resetPeak = useCallback(() => {
    peakRef.current = state.decibels;
    setState((prev) => ({ ...prev, peakDecibels: prev.decibels }));
  }, [state.decibels]);

  useEffect(() => {
    if (enabled && !state.isActive) {
      startListening();
    } else if (!enabled && state.isActive) {
      stopListening();
    }
    return () => {
      stopListening();
    };
  }, [enabled, startListening, stopListening, state.isActive]);

  return {
    state,
    startListening,
    stopListening,
    resetPeak,
  };
}
