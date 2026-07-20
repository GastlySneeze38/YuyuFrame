import type { CSSProperties } from 'react'

// Bouton "X" de fermeture — SVG dupliqué dans ModalShell, ModDetailModal et
// UpgradeModal. Les couleurs par défaut correspondent au style de ModalShell ;
// UpgradeModal passe les siennes via `style` (positionnement + teintes propres).
export function CloseButton({
  onClick,
  size = 28,
  iconSize = 14,
  idleColor = 'rgba(255,255,255,0.3)',
  idleBg = 'rgba(255,255,255,0.05)',
  hoverColor = 'rgba(255,255,255,0.7)',
  hoverBg = 'rgba(255,255,255,0.1)',
  className = '',
  style,
}: {
  onClick: () => void
  size?: number
  iconSize?: number
  idleColor?: string
  idleBg?: string
  hoverColor?: string
  hoverBg?: string
  className?: string
  style?: CSSProperties
}) {
  return (
    <button
      onClick={onClick}
      className={`flex flex-shrink-0 items-center justify-center rounded-lg transition-all duration-150 ${className}`.trim()}
      style={{ width: size, height: size, color: idleColor, background: idleBg, ...style }}
      onMouseEnter={(e) => { e.currentTarget.style.color = hoverColor; e.currentTarget.style.background = hoverBg }}
      onMouseLeave={(e) => { e.currentTarget.style.color = idleColor; e.currentTarget.style.background = idleBg }}
    >
      <svg viewBox="0 0 24 24" fill="currentColor" width={iconSize} height={iconSize}>
        <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
      </svg>
    </button>
  )
}
