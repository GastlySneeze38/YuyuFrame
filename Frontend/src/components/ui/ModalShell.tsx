import type { CSSProperties, ReactNode } from 'react'

// Avant ce fichier, cet overlay + cette carte existaient en copies quasi
// identiques dans Instances.tsx (ModalShell local) et ImportSourceModal.tsx.
// Si `title` est fourni, l'en-tête standard (titre + bouton fermer) est
// rendu ; sinon le contenu passé en `children` gère son propre en-tête
// (cas des modales avec un header plus riche, ex: détail d'un mod).
export function ModalShell({
  title,
  onClose,
  children,
  maxWidth = 'max-w-md',
  closeOnBackdrop = true,
  overlay = 'rgba(0,0,0,0.6)',
  blur = 4,
  cardStyle,
}: {
  title?: string
  onClose: () => void
  children: ReactNode
  maxWidth?: string
  closeOnBackdrop?: boolean
  overlay?: string
  blur?: number
  cardStyle?: CSSProperties
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: overlay, backdropFilter: `blur(${blur}px)` }}
      onClick={closeOnBackdrop ? (e) => { if (e.target === e.currentTarget) onClose() } : undefined}
    >
      <div
        className={`w-full ${maxWidth} rounded-2xl p-6 flex flex-col gap-5`}
        style={{ background: '#111118', border: '1px solid rgba(75,63,207,0.3)', boxShadow: '0 24px 80px rgba(0,0,0,0.6)', ...cardStyle }}
      >
        {title !== undefined && (
          <div className="flex flex-shrink-0 items-center justify-between">
            <p className="font-bold text-white" style={{ fontSize: 15 }}>{title}</p>
            <button
              onClick={onClose}
              className="flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-150"
              style={{ color: 'rgba(255,255,255,0.3)', background: 'rgba(255,255,255,0.05)' }}
              onMouseEnter={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.7)'; e.currentTarget.style.background = 'rgba(255,255,255,0.1)' }}
              onMouseLeave={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.3)'; e.currentTarget.style.background = 'rgba(255,255,255,0.05)' }}
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}>
                <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
              </svg>
            </button>
          </div>
        )}
        {children}
      </div>
    </div>
  )
}
