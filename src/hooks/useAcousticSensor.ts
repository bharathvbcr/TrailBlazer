import { useState, useEffect, useRef, useCallback } from 'react';

export interface AcousticState {
  isActive: boolean;
  decibels: number;
  peakDecibels: number;
  noiseCategory: 'quiet' | 'moderate' | 'loud' | 'extreme';
  categoryLabel: string;
  hasPermission: boolean;
}

export function useAcousticSensor(enabled: boolean = false) {
  const [state, setState] = useState<AcousticState>({
    isActive: false,
    decibels: 32,
    peakDecibels: 32,
    noiseCategory: 'quiet',
    categoryLabel: 'Quiet / Ambient',
    hasPermission: false,
  });

  const audioCtxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const animFrameRef = useRef<number | null>(null);
  const peakRef = useRef<number>(32);

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
      analyser.fftSize = 256;
      analyser.smoothingTimeConstant = 0.8;
      source.connect(analyser);
      analyserRef.current = analyser;

      const dataArray = new Uint8Array(analyser.frequencyBinCount);

      const updateLevel = () => {
        if (!analyserRef.current) return;
        analyserRef.current.getByteFrequencyData(dataArray);

        // Compute RMS
        let sum = 0;
        for (let i = 0; i < dataArray.length; i++) {
          sum += dataArray[i] * dataArray[i];
        }
        const rms = Math.sqrt(sum / dataArray.length);
        // Map to approximate decibels SPL (30dB to 110dB)
        const db = Math.round(30 + (rms / 255) * 80);

        if (db > peakRef.current) {
          peakRef.current = db;
        }

        let category: AcousticState['noiseCategory'] = 'quiet';
        let label = 'Quiet Library (< 45 dB)';

        if (db > 85) {
          category = 'extreme';
          label = 'Extreme / Hearing Hazard (> 85 dB)';
        } else if (db > 70) {
          category = 'loud';
          label = 'Loud Traffic / Machine (70-85 dB)';
        } else if (db > 50) {
          category = 'moderate';
          label = 'Normal Conversation (50-70 dB)';
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
