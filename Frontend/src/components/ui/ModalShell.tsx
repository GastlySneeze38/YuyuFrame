import { useEffect } from 'react'
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
  // Un clic à côté ne ferme plus : trop de saisies perdues d'un geste
  // involontaire. On ferme par la croix, par Échap ou par un bouton.
  closeOnBackdrop = false,
  closeOnEscape = true,
  overlay = 'rgba(0,0,0,0.6)',
  blur = 4,
  cardStyle,
  counter,
}: {
  title?: string
  onClose: () => void
  children: ReactNode
  maxWidth?: string
  closeOnBackdrop?: boolean
  /** Échap ferme la modale. À couper pour une modale imposée (mot de passe
   * provisoire, par exemple), qui passe déjà un `onClose` sans effet. */
  closeOnEscape?: boolean
  overlay?: string
  blur?: number
  cardStyle?: CSSProperties
  /** Rang dans une série de modales enchaînées (voir `useModalQueue`) : sans
   * lui, trois messages d'affilée donnent l'impression d'une boucle sans fin
   * plutôt que d'une liste qui se vide. */
  counter?: { index: number; total: number }
}) {
  // Échap ferme : c'est la sortie attendue maintenant que le clic à côté ne
  // fait plus rien. `capture` pour passer avant les champs de saisie.
  useEffect(() => {
    if (!closeOnEscape) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [closeOnEscape, onClose])

  return (
    <motion.div
      variants={backdropVariants}
      initial="initial"
      animate="animate"
      exit="exit"
      // Défilement quand la carte dépasse l'écran.
      //
      // `items-center` seul ne suffit pas : une fois la carte plus haute que
      // la fenêtre, le centrage la fait déborder des DEUX côtés, et ce qui
      // sort par le haut devient inatteignable — le navigateur ne défile pas
      // avant le début du conteneur. D'où `items-start` plus une marge
      // automatique sur la carte : les marges automatiques centrent tant
      // qu'il y a de la place et se réduisent au rembourrage sinon, sans
      // jamais rogner le haut.
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto p-4"
      style={{ background: overlay, backdropFilter: `blur(${blur}px)` }}
      onClick={closeOnBackdrop ? (e) => { if (e.target === e.currentTarget) onClose() } : undefined}
    >
      <motion.div
        variants={modalVariants}
        className={`my-auto w-full ${maxWidth} rounded-2xl p-6 flex flex-col gap-5 bg-bg-card border border-accent/30 shadow-[0_24px_80px_rgba(0,0,0,0.6)]`}
        style={cardStyle}
      >
        {title !== undefined && (
          <div className="flex flex-shrink-0 items-center justify-between gap-3">
            <p className="font-bold text-txt-primary text-[15px]">{title}</p>
            <div className="flex flex-shrink-0 items-center gap-2">
              {/* Affiché seulement à partir de deux : « 1 sur 1 » n'apprend
                  rien et laisse croire qu'il y a une suite. */}
              {counter && counter.total > 1 && (
                <span className="rounded-full border border-accent/30 bg-accent/10 px-2 py-0.5 text-[10px] font-semibold tabular-nums text-txt-secondary">
                  {counter.index} / {counter.total}
                </span>
              )}
              <CloseButton onClick={onClose} />
            </div>
          </div>
        )}
        {children}
      </motion.div>
    </motion.div>
  )
}
