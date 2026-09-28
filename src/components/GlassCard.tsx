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
  const isButton = interactive || Boolean(onClick);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (isButton && onClick && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      onClick();
    }
  };

  return (
    <div
      onClick={onClick}
      onKeyDown={isButton ? handleKeyDown : undefined}
      role={isButton ? 'button' : undefined}
      tabIndex={isButton ? 0 : undefined}
      className={`
        relative rounded-3xl p-5
        bg-gradient-to-b from-white/[0.08] to-white/[0.02]
        backdrop-blur-xl
        border border-white/[0.12]
        shadow-[0_8px_32px_0_rgba(0,0,0,0.36),inset_0_1px_1px_0_rgba(255,255,255,0.2)]
        transition-[border-color,background-color,box-shadow,transform] duration-300 ease-out
        ${isButton ? 'cursor-pointer hover:-translate-y-0.5 hover:border-white/[0.25] hover:from-white/[0.12] hover:to-white/[0.04] hover:shadow-[0_14px_40px_0_rgba(0,0,0,0.45),inset_0_1px_1px_0_rgba(255,255,255,0.25)] active:translate-y-0 active:scale-[0.99]' : ''}
        ${className}
      `}
    >
      {/* Specular gloss top edge */}
      <div className="absolute inset-x-6 top-0 h-[1px] bg-gradient-to-r from-transparent via-white/30 to-transparent pointer-events-none" />
      {children}
    </div>
  );
};
