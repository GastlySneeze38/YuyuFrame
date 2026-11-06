import type { ReactNode } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { EASE_OUT } from '@/lib/motion'

/**
 * Un écran « poussé » sur téléphone : il glisse depuis la droite par-dessus
 * l'écran courant et repart par la droite, plus vite — revenir est une
 * réponse. En-tête en grille 48/1fr/48 : retour à gauche, titre centré (et
 * sa ligne secondaire), action facultative à droite. C'est le même dessin
 * que le contenu d'une instance et les sections des Réglages.
 *
 * Posé en `absolute inset-0` : le parent qui le contient doit être
 * `relative` (en pratique, la racine de l'écran). `footer` reste collé en
 * bas, hors de la partie qui défile (le champ de réponse d'une
 * conversation, par exemple).
 */
export function PushedScreen({ open, onBack, backLabel, title, subtitle, right, footer, children, bodyClassName = 'px-4 pt-4 pb-[max(24px,env(safe-area-inset-bottom))]' }: {
  open: boolean
  onBack: () => void
  backLabel: string
  title: ReactNode
  subtitle?: ReactNode
  right?: ReactNode
  footer?: ReactNode
  children: ReactNode
  bodyClassName?: string
}) {
  const reduce = useReducedMotion()
  return (
    <AnimatePresence>
      {open && (
        <motion.div
          initial={reduce ? { opacity: 0 } : { transform: 'translateX(100%)' }}
          animate={reduce ? { opacity: 1 } : { transform: 'translateX(0%)', transitionEnd: { transform: 'none' } }}
          exit={reduce ? { opacity: 0, transition: { duration: 0.15 } } : { transform: 'translateX(100%)', transition: { duration: 0.22, ease: EASE_OUT } }}
          transition={{ duration: 0.32, ease: [0.32, 0.72, 0, 1] }}
          className="absolute inset-0 z-20 flex flex-col bg-[rgb(var(--bg-primary))] shadow-[-12px_0_32px_rgba(0,0,0,0.45)]"
        >
          <header className="grid shrink-0 grid-cols-[48px_1fr_48px] items-center border-b border-white/[0.06] px-1 pb-2 pt-[max(8px,env(safe-area-inset-top))]">
            <button onClick={onBack} aria-label={backLabel} className="press flex h-11 w-11 items-center justify-center rounded-full text-[rgb(var(--accent-text))]">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
                <path d="M15 6l-6 6 6 6" />
              </svg>
            </button>
            <div className="flex min-w-0 flex-col items-center px-1 text-center">
              <span className="max-w-full truncate text-[16px] font-bold leading-tight text-white">{title}</span>
              {subtitle && <span className="max-w-full truncate text-[12px] leading-tight text-white/55">{subtitle}</span>}
            </div>
            <div className="flex justify-end">{right}</div>
          </header>
          <div className={`min-h-0 flex-1 overflow-y-auto overscroll-contain ${bodyClassName}`}>{children}</div>
          {footer}
        </motion.div>
      )}
    </AnimatePresence>
  )
}

/** Le bouton « … » d'en-tête d'un écran poussé (44 px). */
export function PushedMoreButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button onClick={onClick} aria-label={label} aria-haspopup="dialog" className="press flex h-11 w-11 items-center justify-center rounded-full text-white/70">
      <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5"><circle cx="5" cy="12" r="2" /><circle cx="12" cy="12" r="2" /><circle cx="19" cy="12" r="2" /></svg>
    </button>
  )
}
