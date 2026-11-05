import { useEffect, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { SHEET_EXIT } from '@/components/ui/DrawerPanel'
import { useT } from '@/i18n'

export interface SheetAction {
  id: string
  label: string
  /** Contenu d'un `<svg viewBox="0 0 24 24">` (chemins). */
  icon: ReactNode
  danger?: boolean
  disabled?: boolean
  /** Une question à confirmer dans la feuille avant d'agir (suppression). */
  confirm?: string
  onSelect: () => void
}

/**
 * La feuille d'actions du téléphone : le menu « … » du PC, qui monte du bas.
 *
 * En tête, l'objet visé (`header`) ; dessous, une ligne de 56 px par action.
 * Une action à confirmer remplace la liste par sa question et deux boutons,
 * dans la même feuille — pas de seconde fenêtre par-dessus la première.
 * Choisir une action referme la feuille avant d'agir.
 */
export function ActionSheet({ open, header, actions, onClose }: {
  open: boolean
  header: ReactNode
  actions: SheetAction[]
  onClose: () => void
}) {
  const t = useT()
  const [asking, setAsking] = useState<SheetAction | null>(null)

  // Chaque ouverture repart de la liste, pas d'une question restée en suspens.
  useEffect(() => { if (open) setAsking(null) }, [open])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const run = (action: SheetAction) => {
    onClose()
    action.onSelect()
  }

  return createPortal(
    <AnimatePresence>
      {open && (
        <div key="sheet" className="fixed inset-0 z-50 flex flex-col justify-end" role="dialog" aria-modal="true">
          <motion.div
            className="absolute inset-0 bg-black/60"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            onClick={onClose}
          />
          <motion.div
            className="relative flex flex-col gap-1 rounded-t-[22px] border-t border-line bg-bg-secondary px-3 pb-[max(12px,env(safe-area-inset-bottom))] pt-4"
            initial={{ transform: 'translateY(100%)' }}
            animate={{ transform: 'translateY(0%)', transition: { duration: 0.3, ease: [0.32, 0.72, 0, 1] } }}
            exit={{ transform: 'translateY(100%)', transition: SHEET_EXIT }}
          >
            <div className="mb-2 flex min-w-0 items-center gap-3 px-2">{header}</div>

            {!asking ? (
              actions.map((action) => (
                <button
                  key={action.id}
                  disabled={action.disabled}
                  onClick={() => (action.confirm ? setAsking(action) : run(action))}
                  className={`press flex h-14 w-full items-center gap-3.5 rounded-2xl px-3 text-left text-[16px] font-medium active:bg-white/[0.06] disabled:opacity-40 ${
                    action.danger ? 'text-[#fca5a5]' : 'text-txt-primary'
                  }`}
                >
                  <svg viewBox="0 0 24 24" fill="currentColor" className={`h-[22px] w-[22px] shrink-0 ${action.danger ? '' : 'text-txt-muted'}`}>
                    {action.icon}
                  </svg>
                  <span className="min-w-0 flex-1 truncate">{action.label}</span>
                </button>
              ))
            ) : (
              <div className="flex flex-col gap-3 px-2 pb-1 pt-1">
                <p className="text-[15px] text-txt-secondary">{asking.confirm}</p>
                <div className="flex gap-2">
                  <button
                    onClick={() => setAsking(null)}
                    className="press h-12 flex-1 rounded-2xl bg-white/[0.06] text-[15px] font-semibold text-txt-secondary"
                  >
                    {t('common.cancel')}
                  </button>
                  <button
                    onClick={() => run(asking)}
                    className="press h-12 flex-1 rounded-2xl bg-[rgba(200,50,50,0.18)] text-[15px] font-semibold text-[#fca5a5]"
                  >
                    {asking.label}
                  </button>
                </div>
              </div>
            )}
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  )
}

/** Les icônes des actions communes, pour qu'un même geste ait la même icône
 *  dans toutes les feuilles. */
export const SHEET_ICONS = {
  settings: <path d="M19.14 12.94a7.07 7.07 0 000-1.88l2.03-1.58a.5.5 0 00.12-.64l-1.92-3.32a.5.5 0 00-.6-.22l-2.39.96a7.03 7.03 0 00-1.63-.94l-.36-2.54a.5.5 0 00-.5-.42h-3.84a.5.5 0 00-.5.42l-.36 2.54c-.59.24-1.13.56-1.63.94l-2.39-.96a.5.5 0 00-.6.22L2.65 8.84a.5.5 0 00.12.64l2.03 1.58a7.07 7.07 0 000 1.88l-2.03 1.58a.5.5 0 00-.12.64l1.92 3.32c.13.22.39.3.6.22l2.39-.96c.5.38 1.04.7 1.63.94l.36 2.54c.04.24.25.42.5.42h3.84c.25 0 .46-.18.5-.42l.36-2.54c.59-.24 1.13-.56 1.63-.94l2.39.96c.21.08.47 0 .6-.22l1.92-3.32a.5.5 0 00-.12-.64l-2.03-1.58zM12 15.6A3.6 3.6 0 1112 8.4a3.6 3.6 0 010 7.2z" />,
  duplicate: <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />,
  delete: <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />,
  update: <path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z" />,
  versions: <path d="M12 5V2L8 6l4 4V7c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm-6 7c0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3c-3.31 0-6-2.69-6-6z" />,
  info: <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />,
}
