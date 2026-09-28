import React from 'react';

interface Props {
  children: React.ReactNode;
  className?: string;
  onClick?: () => void;
  interactive?: boolean;
}

export const GlassCard: React.FC<Props> = ({
  children,
  className = '',
  onClick,
  interactive = false,
}) => {
  return (
    <div
      onClick={onClick}
      className={`
        relative rounded-3xl p-5
        bg-gradient-to-b from-white/[0.08] to-white/[0.02]
        backdrop-blur-2xl
        border border-white/[0.12]
        shadow-[0_8px_32px_0_rgba(0,0,0,0.36),inset_0_1px_1px_0_rgba(255,255,255,0.2)]
        transition-all duration-300
        ${interactive ? 'cursor-pointer hover:border-white/[0.25] hover:from-white/[0.12] hover:to-white/[0.04] active:scale-[0.99]' : ''}
        ${className}
      `}
    >
      {/* Specular gloss top edge */}
      <div className="absolute inset-x-6 top-0 h-[1px] bg-gradient-to-r from-transparent via-white/30 to-transparent pointer-events-none" />
      {children}
    </div>
  );
};
