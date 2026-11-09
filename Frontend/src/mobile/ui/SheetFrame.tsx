import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useTabActive } from '@/mobile/KeptTabs'
import { bindTouchDrag, isTouchControl, rubber, verticalScroller } from './gesture'
import { Slide, remaining } from './slide'

/** Ouvrir : la courbe des tiroirs d'iOS sur 340 ms. */
const OPEN_MS = 340
/** Fermer est une réponse : plus vif qu'ouvrir. */
const CLOSE_MS = 220
/** Revenir en place après un glissé trop court. */
const SETTLE_MS = 260
/** Au-delà de cette distance (px) vers le bas, ou de cette vitesse (px/s), on ferme. */
const CLOSE_OFFSET = 90
const CLOSE_VELOCITY = 500

/**
 * Le cadre de toutes les feuilles du bas du téléphone (`Sheet`, les tiroirs
 * Missions et Amis de `DrawerPanel`, le choix d'instance) : le fond assombri,
 * le panneau qui monte, la poignée, et les gestes.
 *
 * **Fermer d'un geste, de partout** (2026-11-09) :
 * - tirer vers le bas depuis n'importe quel endroit du panneau qui ne défile
 *   pas : la poignée (zone de 40 px de haut sur toute la largeur), l'en-tête,
 *   le pied ;
 * - tirer vers le bas dans la partie qui défile, quand elle est déjà tout en
 *   haut (comme sur iOS) ;
 * - balayer vers le bas sur le fond assombri, au-dessus de la feuille ; le
 *   toucher simplement la ferme aussi ;
 * - et tout ça **pendant l'animation d'ouverture** : le geste la reprend là
 *   où elle en est.
 *
 * Vers le haut, la feuille résiste au lieu de buter. Un glissé trop court la
 * remet en place. Ouverture et fermeture tournent sur la carte graphique
 * (`Slide`).
 *
 * Rendue dans `document.body`, et masquée avec l'onglet qui l'a ouverte quand
 * on en change (`mobile/KeptTabs`).
 */
export function SheetFrame({ open, onClose, label, dismissible = true, className = '', handle = true, children }: {
  open: boolean
  onClose: () => void
  /** Nom de la feuille pour les lecteurs d'écran. */
  label?: string
  /** Faux pour une feuille imposée : ni glissé, ni toucher du fond. */
  dismissible?: boolean
  /** Classes du panneau : hauteur, fond, bordure. */
  className?: string
  /** Dessiner la poignée en haut du panneau. */
  handle?: boolean
  children: ReactNode
}) {
  const tabActive = useTabActive()
  const show = open && tabActive
  const [mounted, setMounted] = useState(show)
  useEffect(() => { if (show) setMounted(true) }, [show])
  if (!mounted) return null
  return createPortal(
    <SheetLayer show={show} onClose={onClose} onClosed={() => setMounted(false)} label={label} dismissible={dismissible} className={className} handle={handle}>
      {children}
    </SheetLayer>,
    document.body,
  )
}

function SheetLayer({ show, onClose, onClosed, label, dismissible, className, handle, children }: {
  show: boolean
  onClose: () => void
  onClosed: () => void
  label?: string
  dismissible: boolean
  className: string
  handle: boolean
  children: ReactNode
}) {
  const panelRef = useRef<HTMLDivElement>(null)
  const backdropRef = useRef<HTMLDivElement>(null)
  const slide = useRef<Slide | null>(null)
  const height = useRef(0)
  // Les rappels changent à chaque rendu du parent : lus par référence, pour
  // ne pas rebrancher les gestes.
  const latest = useRef({ onClose, onClosed, dismissible })
  latest.current = { onClose, onClosed, dismissible }

  // Fermée au départ, avant la première image : pas d'éclair à sa place finale.
  useLayoutEffect(() => {
    const panel = panelRef.current!
    height.current = panel.offsetHeight
    slide.current = new Slide(panel, 'y', backdropRef.current, (v) => 1 - v / Math.max(1, height.current))
    slide.current.apply(height.current)
  }, [])

  // Ouvrir / fermer. Un aller-retour rapide reprend le trajet en cours.
  useEffect(() => {
    const s = slide.current!
    const h = height.current
    if (show) {
      void s.to(0, OPEN_MS)
    } else {
      const from = s.position()
      void s.to(h, remaining(from, h, h, CLOSE_MS)).then((done) => { if (done) latest.current.onClosed() })
    }
  }, [show])

  // La hauteur peut changer (contenu chargé) : la position fermée suit.
  useEffect(() => {
    const panel = panelRef.current!
    const ro = new ResizeObserver(() => { height.current = panel.offsetHeight })
    ro.observe(panel)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    const panel = panelRef.current!
    const backdrop = backdropRef.current!
    let base = 0
    const drag = {
      axis: 'y' as const,
      onStart: () => { base = slide.current!.stop() },
      onMove: (d: number) => {
        const v = base + d
        slide.current!.apply(v < 0 ? rubber(v, 120) : v)
      },
      onEnd: (_d: number, velocity: number) => {
        const s = slide.current!
        const pos = s.position()
        if (velocity > CLOSE_VELOCITY || (pos > CLOSE_OFFSET && velocity > -CLOSE_VELOCITY / 2)) latest.current.onClose()
        else void s.to(0, SETTLE_MS)
      },
    }
    const offPanel = bindTouchDrag(panel, {
      ...drag,
      accept: (dx, dy, target) => {
        if (!latest.current.dismissible || Math.abs(dy) <= Math.abs(dx) || isTouchControl(target)) return false
        // Dans ce qui défile : seulement vers le bas, et seulement tout en haut.
        const scroller = verticalScroller(target, panel)
        if (scroller && (dy < 0 || scroller.scrollTop > 0)) return false
        return true
      },
    })
    const offBackdrop = bindTouchDrag(backdrop, {
      ...drag,
      accept: (dx, dy) => latest.current.dismissible && Math.abs(dy) > Math.abs(dx),
    })
    return () => { offPanel(); offBackdrop() }
  }, [])

  return (
    <div className="fixed inset-0 z-50 flex flex-col justify-end" role="dialog" aria-modal="true" aria-label={label}>
      <div
        ref={backdropRef}
        className="absolute inset-0 bg-black/60"
        style={{ opacity: 0 }}
        onClick={dismissible ? onClose : undefined}
      />
      <div ref={panelRef} className={`relative flex flex-col overflow-hidden rounded-t-[22px] ${className}`}>
        {handle && (
          // La poignée : un trait de 40 px, dans une zone de 40 px de haut sur
          // toute la largeur — le doigt n'a pas à viser le trait.
          <div aria-hidden className={`flex h-10 shrink-0 items-start justify-center pt-2.5 ${dismissible ? 'cursor-grab' : ''}`}>
            <span className="h-[5px] w-10 rounded-full bg-white/25" />
          </div>
        )}
        {children}
      </div>
    </div>
  )
}
