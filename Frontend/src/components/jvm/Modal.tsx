import { useEffect, type ReactNode } from 'react'

/**
 * Coquille de modale des écrans de configuration JVM.
 *
 * Ces écrans reposent volontairement beaucoup dessus : tout ce qui n'est pas
 * l'objet direct du moment (les réglages de la JVM, le choix d'un jeu de
 * drapeaux, le catalogue) sort de la page et n'apparaît que quand on le
 * demande. Sinon l'écran est déjà saturé avant qu'une seule ligne de config
 * n'existe — et une page pleine de choses qu'on n'a pas encore décidées est
 * exactement ce qui décourage.
 */
export function JvmModal({ title, sub, onClose, width = 640, footer, children }: {
  title: string
  sub?: string
  onClose: () => void
  /** Largeur maximale en pixels — un catalogue a besoin de plus qu'un réglage. */
  width?: number
  footer?: ReactNode
  children: ReactNode
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="absolute inset-0 z-30 flex items-start justify-center overflow-auto bg-[rgba(5,5,10,0.75)] p-8 backdrop-blur-[2px]"
      onClick={onClose}
    >
      <div
        className="flex max-h-full w-full flex-col overflow-hidden rounded-2xl border border-[rgba(255,255,255,0.12)] bg-[#111018] shadow-[0_20px_60px_rgba(0,0,0,0.6)]"
        style={{ maxWidth: width }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex flex-shrink-0 items-start justify-between gap-3 border-b border-[rgba(255,255,255,0.07)] px-5 py-4">
          <div className="min-w-0">
            <h2 className="text-[14px] font-black tracking-[-0.01em] text-white">{title}</h2>
            {sub && <p className="mt-1 text-[11px] leading-relaxed text-[rgba(255,255,255,0.38)]">{sub}</p>}
          </div>
          <button
            onClick={onClose}
            className="flex h-[26px] w-[26px] flex-shrink-0 items-center justify-center rounded-lg text-[rgba(255,255,255,0.35)] transition-colors hover:bg-[rgba(255,255,255,0.07)] hover:text-white"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
              <path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
            </svg>
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-auto">{children}</div>

        {footer && (
          <div className="flex flex-shrink-0 items-center justify-end gap-2 border-t border-[rgba(255,255,255,0.07)] px-5 py-3">
            {footer}
          </div>
        )}
      </div>
    </div>
  )
}
