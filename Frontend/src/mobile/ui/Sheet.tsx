import type { ReactNode } from 'react'
import { SheetFrame } from './SheetFrame'

/**
 * La feuille du téléphone : tout ce qui s'ouvre par-dessus un écran (fenêtre,
 * actions, filtres) monte du bas dans cette forme-là, la même partout.
 *
 * - la poignée, l'en-tête et le pied se tirent vers le bas pour fermer ; la
 *   partie qui défile aussi, quand elle est tout en haut ; le fond assombri
 *   se balaie ou se touche (voir `SheetFrame`, qui porte tous les gestes) ;
 * - le contenu défile dans la feuille, l'en-tête et le pied restent en place ;
 * - la zone du bas de l'écran (barre des gestes) est respectée.
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
  return (
    <SheetFrame
      open={open}
      onClose={onClose}
      label={label}
      dismissible={dismissible}
      className={`max-h-[92dvh] border-t border-white/[0.08] bg-bg-secondary ${full ? 'h-[92dvh]' : ''}`}
    >
      {header && <div className="shrink-0 select-none">{header}</div>}

      <div className={`min-h-0 flex-1 overflow-y-auto overscroll-contain ${bodyClassName} ${footer ? '' : 'pb-[max(20px,env(safe-area-inset-bottom))]'}`}>
        {children}
      </div>

      {footer && (
        <div className="shrink-0 border-t border-white/[0.06] px-5 pb-[max(12px,env(safe-area-inset-bottom))] pt-3">
          {footer}
        </div>
      )}
    </SheetFrame>
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
