import React from 'react';

/** Root wrapper shared by every tab: consistent width, vertical rhythm and nav clearance. */
export const Page: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="flex flex-col items-center w-full max-w-md mx-auto space-y-4 pb-[calc(6.5rem+var(--safe-bottom))]">
    {children}
  </div>
);

interface PageHeaderProps {
  icon: React.ReactNode;
  title: string;
  subtitle: string;
  actions?: React.ReactNode;
}

/** Title block at the top of each tab, with an optional primary action on the right. */
export const PageHeader: React.FC<PageHeaderProps> = ({ icon, title, subtitle, actions }) => (
  <div className="w-full px-1 flex items-center justify-between gap-3">
    <div className="min-w-0">
      <h2 className="text-lg font-bold text-white tracking-tight flex items-center gap-2 leading-tight">
        {icon}
        <span className="truncate">{title}</span>
      </h2>
      <p className="text-xs text-slate-400 mt-0.5">{subtitle}</p>
    </div>
    {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
  </div>
);

/** Row of compact controls (toggles, unit switchers) directly under the page header. */
export const Toolbar: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="w-full px-1 flex flex-wrap items-center justify-between gap-2">{children}</div>
);

interface CardHeaderProps {
  icon?: React.ReactNode;
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  children?: React.ReactNode;
}

/** Header row used at the top of every card: icon + uppercase title, optional right-side controls. */
export const CardHeader: React.FC<CardHeaderProps> = ({ icon, title, subtitle, children }) => (
  <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 mb-3">
    <div className="flex items-center gap-2 min-w-0 flex-1 basis-40">
      {icon}
      <div className="min-w-0">
        <h3 className="text-xs font-bold text-white uppercase tracking-wider leading-tight">{title}</h3>
        {subtitle && <p className="text-[11px] text-slate-400 font-sans normal-case tracking-normal mt-0.5">{subtitle}</p>}
      </div>
    </div>
    {children && <div className="flex items-center gap-2 ml-auto">{children}</div>}
  </div>
);

/** Small heading between cards / lists. */
export const SectionLabel: React.FC<{ children: React.ReactNode; icon?: React.ReactNode; hint?: React.ReactNode }> = ({
  children,
  icon,
  hint,
}) => (
  <div className="w-full px-1 pt-1 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
    <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-300">
      {icon}
      <span>{children}</span>
    </div>
    {hint && <div className="text-[11px] text-slate-400">{hint}</div>}
  </div>
);

const TONES = {
  cyan: 'text-cyan-300',
  amber: 'text-amber-300',
  emerald: 'text-emerald-300',
  rose: 'text-rose-300',
  slate: 'text-slate-100',
} as const;

interface StatProps {
  label: string;
  value: React.ReactNode;
  unit?: string;
  icon?: React.ReactNode;
  tone?: keyof typeof TONES;
  onClick?: () => void;
  title?: string;
}

/** Compact metric tile: one-line label, non-wrapping value with optional unit. */
export const Stat: React.FC<StatProps> = ({ label, value, unit, icon, tone = 'cyan', onClick, title }) => {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      onClick={onClick}
      title={title}
      className={`min-w-0 p-2.5 rounded-2xl bg-white/[0.04] border border-white/[0.06] text-center flex flex-col items-center justify-center gap-0.5 ${
        onClick ? 'hover:bg-white/[0.08] cursor-pointer' : ''
      }`}
    >
      <span className="w-full truncate text-[11px] text-slate-400 uppercase font-medium tracking-wide flex items-center justify-center gap-1">
        {icon}
        <span className="truncate">{label}</span>
      </span>
      <span className={`text-sm font-bold font-mono whitespace-nowrap ${TONES[tone]}`}>
        {value}
        {unit && <span className="ml-1 text-[11px] font-medium text-slate-400">{unit}</span>}
      </span>
    </Tag>
  );
};
