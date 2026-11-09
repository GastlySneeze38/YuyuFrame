import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { useIsMobile } from '@/mobile/useIsMobile'
import { useTabActive } from '@/mobile/KeptTabs'
import { SheetFrame } from '@/mobile/ui/SheetFrame'

/**
 * Le cadre des tiroirs de l'accueil (Missions, Amis), posé dans
 * `document.body` : la bannière d'où ils s'ouvrent est transformée par ses
 * animations, et un `fixed` à l'intérieur s'y collerait.
 *
 * - Sur PC : un panneau de 360 px qui glisse depuis la droite, par-dessus la
 *   page (comme la liste d'amis de Discord). Un clic à côté le referme.
 * - Sur téléphone : une feuille qui monte du bas, la forme attendue sur iOS
 *   et Android, avec les gestes de toutes les feuilles (`mobile/ui/SheetFrame`).
 *   Pas de flou derrière : il se recalculerait pendant toute l'animation.
 */
export function DrawerPanel({ open, onClose, label, children }: {
  open: boolean
  onClose: () => void
  label: string
  children: ReactNode
}) {
  const mobile = useIsMobile()
  // Posé dans `body` : masqué avec l'onglet qui l'a ouvert quand on en
  // change (mobile/KeptTabs). Toujours vrai sur PC.
  const tabActive = useTabActive()

  if (mobile) {
    return (
      <SheetFrame
        open={open}
        onClose={onClose}
        label={label}
        // `sheet-touch` : plancher de lecture et de toucher pour le contenu partagé avec le PC (index.css).
        className="sheet-touch h-[85dvh] border-t border-line bg-[rgb(var(--bg-card))] pb-[env(safe-area-inset-bottom,0px)] text-txt-primary"
      >
        {children}
      </SheetFrame>
    )
  }

  return createPortal(
    <AnimatePresence>
      {open && tabActive && !mobile && (
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
            className="m-2 flex w-[360px] max-w-[calc(100vw-16px)] flex-col overflow-hidden rounded-2xl border border-line bg-[rgb(var(--bg-card))] text-txt-primary shadow-[0_24px_80px_rgba(0,0,0,0.7)]"
          >
            {children}
          </motion.aside>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  )
}
