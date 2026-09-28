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
  <div className="w-full px-1 pt-1 flex items-center justify-between gap-3">
    <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-300">
      {icon}
      <span>{children}</span>
    </div>
    {hint && <div className="text-[11px] text-slate-400">{hint}</div>}
  </div>
);
