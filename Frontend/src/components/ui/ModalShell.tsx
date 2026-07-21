import type { CSSProperties, ReactNode } from 'react'
import { CloseButton } from './CloseButton'

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
        className={`w-full ${maxWidth} rounded-2xl p-6 flex flex-col gap-5 bg-[#111118] border border-[rgba(75,63,207,0.3)] shadow-[0_24px_80px_rgba(0,0,0,0.6)]`}
        style={cardStyle}
      >
        {title !== undefined && (
          <div className="flex flex-shrink-0 items-center justify-between">
            <p className="font-bold text-white text-[15px]">{title}</p>
            <CloseButton onClick={onClose} />
          </div>
        )}
        {children}
      </div>
    </div>
  )
}
