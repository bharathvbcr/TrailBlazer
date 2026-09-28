import React from 'react';
import { ThemePalette } from '../types/sensors';

interface Props {
  palette: ThemePalette;
}

export const LiquidGlassBackground: React.FC<Props> = ({ palette }) => {
  const getGradientOrbs = () => {
    switch (palette) {
      case 'tactical_red':
        return {
          primary: 'rgba(239, 68, 68, 0.22)',
          secondary: 'rgba(185, 28, 28, 0.18)',
          accent: 'rgba(153, 27, 27, 0.16)',
          bg: '#050203',
        };
      case 'phosphor_green':
        return {
          primary: 'rgba(34, 197, 94, 0.22)',
          secondary: 'rgba(21, 128, 61, 0.18)',
          accent: 'rgba(74, 222, 128, 0.14)',
          bg: '#020704',
        };
      case 'emerald':
        return {
          primary: 'rgba(52, 211, 153, 0.18)',
          secondary: 'rgba(20, 184, 166, 0.16)',
          accent: 'rgba(16, 185, 129, 0.12)',
          bg: '#080b14',
        };
      case 'amber':
        return {
          primary: 'rgba(251, 191, 36, 0.18)',
          secondary: 'rgba(249, 115, 22, 0.15)',
          accent: 'rgba(234, 179, 8, 0.12)',
          bg: '#080b14',
        };
      case 'violet':
        return {
          primary: 'rgba(168, 85, 247, 0.18)',
          secondary: 'rgba(99, 102, 241, 0.16)',
          accent: 'rgba(217, 70, 239, 0.12)',
          bg: '#080b14',
        };
      case 'cyan':
      default:
        return {
          primary: 'rgba(56, 189, 248, 0.18)',
          secondary: 'rgba(14, 165, 233, 0.15)',
          accent: 'rgba(45, 212, 191, 0.13)',
          bg: '#080b14',
        };
    }
  };

  const orbs = getGradientOrbs();

  return (
    <div
      className="fixed inset-0 pointer-events-none -z-10 overflow-hidden transition-colors duration-700"
      style={{ backgroundColor: orbs.bg }}
    >
      {/* Dynamic ambient organic liquid blur orbs */}
      <div
        className="absolute -top-32 -left-32 w-96 h-96 rounded-full blur-[100px] animate-pulse-slow transition-all duration-1000"
        style={{ backgroundColor: orbs.primary }}
      />
      <div
        className="absolute top-1/3 -right-28 w-80 h-80 rounded-full blur-[90px] animate-float-slow transition-all duration-1000"
        style={{ backgroundColor: orbs.secondary }}
      />
      <div
        className="absolute -bottom-24 left-1/4 w-96 h-96 rounded-full blur-[110px] animate-pulse-slow transition-all duration-1000"
        style={{ backgroundColor: orbs.accent }}
      />

      {/* Subtle fine glass mesh overlay */}
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_80%_80%_at_50%_-20%,rgba(120,119,198,0.1),rgba(255,255,255,0))]" />
      
      {/* Liquid refraction line */}
      <div className="absolute inset-0 opacity-[0.03] bg-[linear-gradient(45deg,#fff_25%,transparent_25%,transparent_75%,#fff_75%,#fff),linear-gradient(45deg,#fff_25%,transparent_25%,transparent_75%,#fff_75%,#fff)] bg-[size:40px_40px]" />
    </div>
  );
};
