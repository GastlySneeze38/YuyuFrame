import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion, useDragControls } from 'framer-motion'

/** Distance de glissé vers le bas, ou vitesse, au-delà de laquelle la feuille se ferme :
 *  un coup de doigt rapide suffit, même court. */
const CLOSE_OFFSET = 90
const CLOSE_VELOCITY = 500

/** Courbe des tiroirs d'iOS (Ionic). */
const DRAWER_EASE = [0.32, 0.72, 0, 1] as const

/**
 * La feuille du téléphone : tout ce qui s'ouvre par-dessus un écran (fenêtre,
 * actions, filtres) monte du bas dans cette forme-là, la même partout.
 *
 * - une **poignée** en haut, qu'on tire vers le bas pour fermer ; un coup de
 *   doigt rapide suffit. Le glissé ne part que de la poignée et de l'en-tête :
 *   branché sur toute la feuille, il capterait aussi le doigt qui fait
 *   défiler son contenu ;
 * - toucher le fond la ferme aussi (sauf `dismissible={false}`) ;
 * - le contenu défile dans la feuille, l'en-tête et le pied restent en place ;
 * - la zone du bas de l'écran (barre des gestes) est respectée.
 *
 * Rendue dans `document.body` : la zone des pages porte un filtre de
 * luminosité, qui enfermerait la feuille au-dessus de la barre d'onglets.
 */
export function Sheet({ open, onClose, label, header, footer, children, full = false, dismissible = true, bodyClassName = 'px-5 pb-5' }: {
  open: boolean
  onClose: () => void
  /** Nom de la feuille pour les lecteurs d'écran. */
  label?: string
  /** Sous la poignée, fixe ; on peut aussi le tirer pour fermer. */
  header?: ReactNode
  /** En bas, fixe, au-dessus de la barre des gestes. */
  footer?: ReactNode
  children: ReactNode
  /** Presque tout l'écran (92 %) au lieu de la hauteur du contenu. */
  full?: boolean
  /** Faux pour une feuille imposée : ni glissé, ni toucher du fond. */
  dismissible?: boolean
  bodyClassName?: string
}) {
  const drag = useDragControls()

  return createPortal(
    <AnimatePresence>
      {open && (
        <div key="sheet" className="fixed inset-0 z-50 flex flex-col justify-end" role="dialog" aria-modal="true" aria-label={label}>
          <motion.div
            className="absolute inset-0 bg-black/60"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0, transition: { duration: 0.2 } }}
            transition={{ duration: 0.25 }}
            onClick={dismissible ? onClose : undefined}
          />
          <motion.div
            className={`relative flex max-h-[92dvh] flex-col overflow-hidden rounded-t-[22px] border-t border-white/[0.08] bg-bg-secondary ${full ? 'h-[92dvh]' : ''}`}
            initial={{ y: '100%' }}
            animate={{ y: 0, transition: { duration: 0.32, ease: DRAWER_EASE } }}
            // Fermer est une réponse : plus vif qu'ouvrir.
            exit={{ y: '100%', transition: { duration: 0.2, ease: DRAWER_EASE } }}
            drag={dismissible ? 'y' : false}
            dragControls={drag}
            dragListener={false}
            dragConstraints={{ top: 0, bottom: 0 }}
            // Vers le haut, la feuille résiste au lieu de buter ; vers le bas
            // elle suit le doigt.
            dragElastic={{ top: 0.05, bottom: 0.6 }}
            onDragEnd={(_, info) => {
              if (info.offset.y > CLOSE_OFFSET || info.velocity.y > CLOSE_VELOCITY) onClose()
            }}
          >
            {/* La zone qu'on tire : poignée + en-tête. `touch-none` la laisse
                entièrement au glissé, le navigateur n'y fait rien défiler. */}
            <div
              className={`shrink-0 select-none ${dismissible ? 'cursor-grab touch-none' : ''}`}
              onPointerDown={dismissible ? (e) => drag.start(e) : undefined}
            >
              <div className="flex justify-center pb-2 pt-2.5">
                <span className="h-[5px] w-10 rounded-full bg-white/25" />
              </div>
              {header}
            </div>

            <div className={`min-h-0 flex-1 overflow-y-auto overscroll-contain ${bodyClassName} ${footer ? '' : 'pb-[max(20px,env(safe-area-inset-bottom))]'}`}>
              {children}
            </div>

            {footer && (
              <div className="shrink-0 border-t border-white/[0.06] px-5 pb-[max(12px,env(safe-area-inset-bottom))] pt-3">
                {footer}
              </div>
            )}
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  )
}

/** En-tête standard d'une feuille : un titre, aligné à gauche sous la poignée. */
export function SheetTitle({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex min-h-[44px] items-center justify-between gap-3 px-5 pb-2">
      <h2 className="min-w-0 truncate text-[17px] font-bold text-txt-primary">{children}</h2>
      {action}
    </div>
  )
}
