import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { bindTouchDrag, canScrollLeft, isTouchControl, rubber } from './gesture'
import { Slide, remaining } from './slide'

const OPEN_MS = 320
/** Revenir est une réponse : plus vif qu'arriver. */
const CLOSE_MS = 220
const SETTLE_MS = 240
/** Au-delà de cette part de la largeur, ou de cette vitesse (px/s), on revient. */
const BACK_RATIO = 0.35
const BACK_VELOCITY = 500

/**
 * Un écran « poussé » sur téléphone : il glisse depuis la droite par-dessus
 * l'écran courant et repart par la droite, plus vite — revenir est une
 * réponse. En-tête en grille 48/1fr/48 : retour à gauche, titre centré (et
 * sa ligne secondaire), action facultative à droite. C'est le même dessin
 * que le contenu d'une instance et les sections des Réglages.
 *
 * **Retour d'un glissé vers la droite** (2026-11-09), parti de n'importe où
 * dans l'écran : l'écran suit le doigt et revient s'il a fait plus d'un tiers
 * du chemin ou s'il est lancé. Pas seulement depuis le bord : sur Android, le
 * bord gauche est pris par le geste « retour » du système. Un contenu qui
 * défile lui-même vers la gauche (liste horizontale) garde son geste.
 *
 * Posé en `absolute inset-0` : le parent qui le contient doit être
 * `relative` (en pratique, la racine de l'écran). `footer` reste collé en
 * bas, hors de la partie qui défile (le champ de réponse d'une
 * conversation, par exemple).
 */
export function PushedScreen(props: {
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
  const [mounted, setMounted] = useState(props.open)
  useEffect(() => { if (props.open) setMounted(true) }, [props.open])
  if (!mounted) return null
  return <PushedLayer {...props} onClosed={() => setMounted(false)} />
}

function PushedLayer({ open, onBack, onClosed, backLabel, title, subtitle, right, footer, children, bodyClassName = 'px-4 pt-4 pb-[max(24px,env(safe-area-inset-bottom))]' }: {
  open: boolean
  onBack: () => void
  onClosed: () => void
  backLabel: string
  title: ReactNode
  subtitle?: ReactNode
  right?: ReactNode
  footer?: ReactNode
  children: ReactNode
  bodyClassName?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const slide = useRef<Slide | null>(null)
  const width = useRef(0)
  const latest = useRef({ onBack, onClosed })
  latest.current = { onBack, onClosed }

  useLayoutEffect(() => {
    const el = ref.current!
    width.current = el.offsetWidth
    slide.current = new Slide(el, 'x')
    slide.current.apply(width.current)
  }, [])

  useEffect(() => {
    const s = slide.current!
    const w = width.current
    if (open) void s.to(0, OPEN_MS)
    else void s.to(w, remaining(s.position(), w, w, CLOSE_MS)).then((done) => { if (done) latest.current.onClosed() })
  }, [open])

  useEffect(() => {
    const el = ref.current!
    let base = 0
    return bindTouchDrag(el, {
      axis: 'x',
      // Vers la droite et franchement horizontal ; jamais depuis un champ ni
      // depuis un contenu qui peut encore défiler vers la gauche.
      accept: (dx, dy, target) => dx > 0 && dx > Math.abs(dy) * 1.2 && !isTouchControl(target) && !canScrollLeft(target, el),
      onStart: () => { base = slide.current!.stop() },
      onMove: (d) => {
        const v = base + d
        slide.current!.apply(v < 0 ? rubber(v, 40) : v)
      },
      onEnd: (_d, velocity) => {
        const s = slide.current!
        const pos = s.position()
        if (velocity > BACK_VELOCITY || (pos > width.current * BACK_RATIO && velocity > -BACK_VELOCITY / 2)) latest.current.onBack()
        else void s.to(0, SETTLE_MS)
      },
    })
  }, [])

  return (
    <div ref={ref} className="absolute inset-0 z-20 flex flex-col bg-[rgb(var(--bg-primary))]">
      {/* L'ombre sur l'écran d'en dessous : une bande en dégradé et non une
          `box-shadow` floue, que le téléphone redessinait à chaque image
          du glissement (même défaut que le menu, ui/NavDrawer). */}
      <span aria-hidden className="pointer-events-none absolute inset-y-0 right-full w-6 bg-gradient-to-l from-black/40 to-transparent" />
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
    </div>
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
