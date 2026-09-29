import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import { ToastProvider } from './components/Toast';
import './index.css';

// Synchronize Android Edge-to-Edge safe area insets (status bar, navigation bar, cutouts)
if (typeof window !== 'undefined') {
  interface AndroidBridgeInterface {
    getSafeAreaTop?: () => number;
    getSafeAreaBottom?: () => number;
    getSafeAreaLeft?: () => number;
    getSafeAreaRight?: () => number;
  }

  const bridge = (window as unknown as { AndroidBridge?: AndroidBridgeInterface }).AndroidBridge;

  const applyInsets = (top?: number, bottom?: number, left?: number, right?: number) => {
    const root = document.documentElement;
    if (top !== undefined && !Number.isNaN(top)) {
      root.style.setProperty('--safe-top', `${top}px`);
    }
    if (bottom !== undefined && !Number.isNaN(bottom)) {
      root.style.setProperty('--safe-bottom', `${bottom}px`);
    }
    if (left !== undefined && !Number.isNaN(left)) {
      root.style.setProperty('--safe-left', `${left}px`);
    }
    if (right !== undefined && !Number.isNaN(right)) {
      root.style.setProperty('--safe-right', `${right}px`);
    }
  };

  // Immediate read on startup from native bridge
  if (bridge) {
    try {
      const top = bridge.getSafeAreaTop?.();
      const bottom = bridge.getSafeAreaBottom?.();
      const left = bridge.getSafeAreaLeft?.();
      const right = bridge.getSafeAreaRight?.();
      applyInsets(top, bottom, left, right);
    } catch {
      // ignore
    }
  }

  // Real-time listener for runtime insets changes (rotation, keyboard, foldables)
  (window as unknown as {
    __onSafeAreaInsetsChanged?: (insets: { top: number; bottom: number; left: number; right: number }) => void;
  }).__onSafeAreaInsetsChanged = (insets) => {
    applyInsets(insets.top, insets.bottom, insets.left, insets.right);
  };
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ToastProvider>
      <App />
    </ToastProvider>
  </React.StrictMode>
);
