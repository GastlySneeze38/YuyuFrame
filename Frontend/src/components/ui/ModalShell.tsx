import { motion } from 'framer-motion'
import type { CSSProperties, ReactNode } from 'react'
import { CloseButton } from './CloseButton'
import { backdropVariants, modalVariants } from '@/lib/motion'

// Avant ce fichier, cet overlay + cette carte existaient en copies quasi
// identiques dans Instances.tsx (ModalShell local) et ImportSourceModal.tsx.
// Si `title` est fourni, l'en-tête standard (titre + bouton fermer) est
// rendu ; sinon le contenu passé en `children` gère son propre en-tête
// (cas des modales avec un header plus riche, ex: détail d'un mod).
//
// Le mouvement vit ici : toutes les modales du launcher passent par cette
// coquille, donc elles s'animent toutes de la même façon (fond qui
// s'assombrit, carte qui monte) sans que chaque écran ait à s'en occuper.
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
    <motion.div
      variants={backdropVariants}
      initial="initial"
      animate="animate"
      exit="exit"
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: overlay, backdropFilter: `blur(${blur}px)` }}
      onClick={closeOnBackdrop ? (e) => { if (e.target === e.currentTarget) onClose() } : undefined}
    >
      <motion.div
        variants={modalVariants}
        className={`w-full ${maxWidth} rounded-2xl p-6 flex flex-col gap-5 bg-bg-card border border-accent/30 shadow-[0_24px_80px_rgba(0,0,0,0.6)]`}
        style={cardStyle}
      >
        {title !== undefined && (
          <div className="flex flex-shrink-0 items-center justify-between">
            <p className="font-bold text-txt-primary text-[15px]">{title}</p>
            <CloseButton onClick={onClose} />
          </div>
        )}
        {children}
      </motion.div>
    </motion.div>
  )
}
