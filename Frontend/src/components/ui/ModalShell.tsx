import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { motion } from 'framer-motion'
import type { CSSProperties, ReactNode } from 'react'
import { CloseButton } from './CloseButton'
import { backdropVariants, modalVariants } from '@/lib/motion'
import { useIsMobile } from '@/mobile/useIsMobile'

/** Feuille du bas (téléphone) : monte de sa propre hauteur avec la courbe des
 *  tiroirs d'iOS, repart plus vite qu'elle n'est venue. En chaîne `transform`,
 *  calculée par le navigateur et non image par image en JavaScript. */
const SHEET_VARIANTS = {
  initial: { transform: 'translateY(100%)' },
  animate: { transform: 'translateY(0%)', transition: { duration: 0.32, ease: [0.32, 0.72, 0, 1] } },
  exit: { transform: 'translateY(100%)', transition: { duration: 0.2, ease: [0.32, 0.72, 0, 1] } },
} as const

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
  // Élargi le 2026-09-28 : les modales de communication tassaient trois
  // paragraphes et deux boutons dans 28 rem, ce qui donnait des lignes de six
  // mots et une impression d'écran encombré. Une modale n'a pas à être
  // étroite, elle a à être lisible.
  maxWidth = 'max-w-lg',
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

  // Sur téléphone, toutes les fenêtres deviennent des feuilles qui montent du
  // bas, la forme attendue sur iOS et Android : pleine largeur, collées au
  // bord, défilant dans leur cadre. Une fenêtre qui fixait sa hauteur (les
  // paramètres d'instance) prend presque tout l'écran.
  const mobile = useIsMobile()
  if (mobile) {
    const sheetStyle: CSSProperties = {
      ...cardStyle,
      ...(cardStyle?.height !== undefined ? { height: '92dvh' } : {}),
    }
    // Rendue à la racine du document : la zone des pages porte un filtre de
    // luminosité, qui enfermerait la feuille au-dessus de la barre d'onglets
    // (un `filter` sert de repère aux éléments `fixed` qu'il contient).
    return createPortal(
      <motion.div
        variants={backdropVariants}
        initial="initial"
        animate="animate"
        exit="exit"
        // Pas de flou derrière : il se recalculerait pendant toute la montée.
        className="fixed inset-0 z-50 flex flex-col justify-end"
        style={{ background: overlay }}
        onClick={closeOnBackdrop ? (e) => { if (e.target === e.currentTarget) onClose() } : undefined}
      >
        <motion.div
          variants={SHEET_VARIANTS}
          className="flex max-h-[92dvh] w-full flex-col gap-5 overflow-y-auto overscroll-contain rounded-t-[22px] border-t border-accent/30 bg-bg-card p-5 pb-[max(20px,env(safe-area-inset-bottom))]"
          style={sheetStyle}
        >
          {title !== undefined && (
            <div className="flex flex-shrink-0 items-center justify-between gap-3">
              <p className="text-[17px] font-bold leading-snug text-txt-primary">{title}</p>
              <div className="flex flex-shrink-0 items-center gap-2">
                {counter && counter.total > 1 && (
                  <span className="rounded-full border border-accent/40 bg-accent/15 px-2.5 py-0.5 text-[12px] font-semibold tabular-nums text-txt-primary">
                    {counter.index} / {counter.total}
                  </span>
                )}
                <CloseButton onClick={onClose} />
              </div>
            </div>
          )}
          {children}
        </motion.div>
      </motion.div>,
      document.body,
    )
  }

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
        className={`my-auto w-full ${maxWidth} rounded-2xl p-7 flex flex-col gap-5 bg-bg-card border border-accent/30 shadow-[0_24px_80px_rgba(0,0,0,0.6)]`}
        style={cardStyle}
      >
        {title !== undefined && (
          <div className="flex flex-shrink-0 items-center justify-between gap-3">
            <p className="font-bold text-txt-primary text-[17px] leading-snug">{title}</p>
            <div className="flex flex-shrink-0 items-center gap-2">
              {/* Affiché seulement à partir de deux : « 1 sur 1 » n'apprend
                  rien et laisse croire qu'il y a une suite. */}
              {counter && counter.total > 1 && (
                <span className="rounded-full border border-accent/40 bg-accent/15 px-2.5 py-0.5 text-[11px] font-semibold tabular-nums text-txt-primary">
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
