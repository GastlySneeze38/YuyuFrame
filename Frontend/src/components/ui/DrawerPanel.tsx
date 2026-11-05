import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion, useDragControls } from 'framer-motion'
import { useIsMobile } from '@/mobile/useIsMobile'

/** Distance de glissé vers le bas, ou vitesse, au-delà de laquelle la feuille se ferme. */
const CLOSE_OFFSET = 90
const CLOSE_VELOCITY = 500

/** Sortie d'une feuille du bas : 200 ms, courbe de tiroir d'iOS (Ionic).
 *  Partagée avec le choix d'instance (`mobile/home/InstanceSheet`). */
export const SHEET_EXIT = { duration: 0.2, ease: [0.32, 0.72, 0, 1] } as const

/**
 * Le cadre des tiroirs de l'accueil (Missions, Amis), posé dans
 * `document.body` : la bannière d'où ils s'ouvrent est transformée par ses
 * animations, et un `fixed` à l'intérieur s'y collerait.
 *
 * - Sur PC : un panneau de 360 px qui glisse depuis la droite, par-dessus la
 *   page (comme la liste d'amis de Discord). Un clic à côté le referme.
 * - Sur téléphone : une feuille qui monte du bas, la forme attendue sur iOS
 *   et Android, comme le choix d'instance (`mobile/home/InstanceSheet`). On la
 *   ferme en la tirant vers le bas par sa poignée, ou en touchant le fond. Pas
 *   de flou derrière : il se recalculerait pendant toute l'animation.
 */
export function DrawerPanel({ open, onClose, label, children }: {
  open: boolean
  onClose: () => void
  label: string
  children: ReactNode
}) {
  const mobile = useIsMobile()
  // La feuille ne se tire que par sa poignée : branché sur tout le panneau,
  // le glissé capterait aussi le doigt qui fait défiler la liste.
  const drag = useDragControls()

  return createPortal(
    <AnimatePresence>
      {open && (mobile ? (
        <div key="sheet" className="fixed inset-0 z-50 flex flex-col justify-end" role="dialog" aria-modal="true" aria-label={label}>
          <motion.div
            className="absolute inset-0 bg-black/60"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            onClick={onClose}
          />
          <motion.aside
            className="relative flex h-[85dvh] flex-col overflow-hidden rounded-t-[22px] border-t border-line bg-[#111116] pb-[env(safe-area-inset-bottom,0px)] text-txt-primary"
            initial={{ y: '100%' }}
            animate={{ y: 0 }}
            // Fermer est une réponse : plus vif qu'ouvrir, courbe de tiroir iOS.
            exit={{ y: '100%', transition: SHEET_EXIT }}
            transition={{ type: 'spring', damping: 32, stiffness: 340 }}
            drag="y"
            dragControls={drag}
            dragListener={false}
            dragConstraints={{ top: 0, bottom: 0 }}
            dragElastic={{ top: 0, bottom: 0.6 }}
            onDragEnd={(_, info) => {
              if (info.offset.y > CLOSE_OFFSET || info.velocity.y > CLOSE_VELOCITY) onClose()
            }}
          >
            {/* La poignée : la zone qu'on tire, laissée entièrement au glissé. */}
            <div className="flex shrink-0 cursor-grab touch-none select-none justify-center pb-1.5 pt-2.5" onPointerDown={(e) => drag.start(e)}>
              <span className="h-1 w-10 rounded-full bg-white/20" />
            </div>
            {children}
          </motion.aside>
        </div>
      ) : (
        <motion.div
          key="side"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 flex justify-end bg-black/45 backdrop-blur-[2px]"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) onClose()
          }}
        >
          <motion.aside
            initial={{ x: 40, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            exit={{ x: 40, opacity: 0 }}
            transition={{ type: 'spring', stiffness: 380, damping: 36 }}
            aria-label={label}
            className="m-2 flex w-[360px] max-w-[calc(100vw-16px)] flex-col overflow-hidden rounded-2xl border border-line bg-[#111116] text-txt-primary shadow-[0_24px_80px_rgba(0,0,0,0.7)]"
          >
            {children}
          </motion.aside>
        </motion.div>
      ))}
    </AnimatePresence>,
    document.body,
  )
}
