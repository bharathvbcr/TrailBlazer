import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle2, AlertTriangle, Info, X } from 'lucide-react';

type Tone = 'success' | 'error' | 'info';

interface ToastInput {
  message: string;
  tone?: Tone;
  /** Optional inline action, e.g. Undo. */
  action?: { label: string; onClick: () => void };
  duration?: number;
}

interface ToastItem extends ToastInput {
  id: number;
  leaving?: boolean;
}

const ToastContext = createContext<(t: ToastInput) => void>(() => {});

/** Show a transient message from anywhere under <ToastProvider>. */
export const useToast = () => useContext(ToastContext);

const TONE_STYLES: Record<Tone, { icon: React.ReactNode; ring: string }> = {
  success: { icon: <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />, ring: 'border-emerald-500/30' },
  error: { icon: <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />, ring: 'border-rose-500/40' },
  info: { icon: <Info className="w-4 h-4 text-cyan-400 shrink-0" />, ring: 'border-cyan-500/30' },
};

const MAX_VISIBLE = 3;

export const ToastProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [items, setItems] = useState<ToastItem[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Map<number, number>());

  const dismiss = useCallback((id: number) => {
    setItems((prev) => prev.map((t) => (t.id === id ? { ...t, leaving: true } : t)));
    window.setTimeout(() => setItems((prev) => prev.filter((t) => t.id !== id)), 200);
    const timer = timers.current.get(id);
    if (timer) window.clearTimeout(timer);
    timers.current.delete(id);
  }, []);

  const push = useCallback(
    (input: ToastInput) => {
      const id = nextId.current++;
      setItems((prev) => [...prev.filter((t) => !t.leaving).slice(-(MAX_VISIBLE - 1)), { ...input, id }]);
      timers.current.set(id, window.setTimeout(() => dismiss(id), input.duration ?? (input.action ? 6000 : 3500)));
    },
    [dismiss]
  );

  useEffect(() => {
    const active = timers.current;
    return () => active.forEach((t) => window.clearTimeout(t));
  }, []);

  return (
    <ToastContext.Provider value={push}>
      {children}
      {createPortal(
        <div
          aria-live="polite"
          className="fixed inset-x-4 z-[60] mx-auto max-w-sm flex flex-col gap-2 pointer-events-none bottom-[calc(6.5rem+var(--safe-bottom))]"
        >
          {items.map((t) => {
            const style = TONE_STYLES[t.tone ?? 'info'];
            return (
              <div
                key={t.id}
                role={t.tone === 'error' ? 'alert' : 'status'}
                className={`${t.leaving ? 'animate-sheet-out' : 'animate-sheet-in'} pointer-events-auto flex items-center gap-2.5 pl-3 pr-2 py-2.5 rounded-2xl bg-slate-900/90 backdrop-blur-xl border ${style.ring} shadow-[0_10px_30px_rgba(0,0,0,0.5)] text-xs text-slate-100`}
              >
                {style.icon}
                <span className="flex-1 min-w-0">{t.message}</span>
                {t.action && (
                  <button
                    onClick={() => {
                      t.action!.onClick();
                      dismiss(t.id);
                    }}
                    className="px-2.5 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-cyan-300 font-bold"
                  >
                    {t.action.label}
                  </button>
                )}
                <button
                  onClick={() => dismiss(t.id)}
                  aria-label="Dismiss"
                  className="w-7 h-7 flex items-center justify-center rounded-full text-slate-400 hover:text-white hover:bg-white/10"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              </div>
            );
          })}
        </div>,
        document.body
      )}
    </ToastContext.Provider>
  );
};
