/**
 * Les glissés du téléphone : menu, feuilles du bas, écrans poussés.
 *
 * Par les événements tactiles et non par le glissé de framer-motion, pour
 * trois raisons :
 * - **on attrape un panneau à tout moment**, même pendant son animation
 *   d'entrée : le geste arrête l'animation et repart de là où elle en est ;
 * - **le geste n'est pris qu'une fois son sens connu** (au-delà de `SLOP`) :
 *   un appui reste un appui, et un défilement reste au navigateur. On ne
 *   pose donc aucun `touch-action` qui bloquerait le défilement des enfants ;
 * - une seule mécanique pour tous les panneaux, au lieu de trois copies.
 *
 * Une fois le geste pris, chaque `touchmove` est annulé (`preventDefault`) :
 * le navigateur ne fait plus rien défiler, et l'effet élastique général
 * (`lib/overscroll.ts`) voit l'événement annulé et ne s'en mêle pas.
 */

/** Déplacement (px) à partir duquel on décide du sens du geste. */
const SLOP = 8

export interface TouchDragOptions {
  /** L'axe suivi : `x` (menu, écran poussé) ou `y` (feuille). */
  axis: 'x' | 'y'
  /**
   * Prend-on ce geste ? Appelé une fois, au premier déplacement significatif,
   * avec le déplacement depuis l'appui et l'élément touché.
   */
  accept: (dx: number, dy: number, target: Element) => boolean
  /** Le geste est pris : arrêter l'animation en cours. */
  onStart: () => void
  /** Déplacement le long de l'axe depuis le début du geste (px). */
  onMove: (delta: number) => void
  /** Doigt levé : déplacement total et vitesse (px/s) le long de l'axe. */
  onEnd: (delta: number, velocity: number) => void
}

/** Branche le glissé sur `el` ; rend de quoi le débrancher. */
export function bindTouchDrag(el: HTMLElement, opts: TouchDragOptions): () => void {
  let id: number | null = null
  let x0 = 0
  let y0 = 0
  let state: 'idle' | 'deciding' | 'active' | 'ignored' = 'idle'
  let samples: { t: number; v: number }[] = []

  const along = (t: Touch) => (opts.axis === 'x' ? t.clientX - x0 : t.clientY - y0)
  const find = (list: TouchList) => {
    for (let i = 0; i < list.length; i++) if (list[i].identifier === id) return list[i]
    return null
  }

  const onTouchStart = (e: TouchEvent) => {
    if (state !== 'idle' || e.touches.length !== 1) return
    const t = e.touches[0]
    id = t.identifier
    x0 = t.clientX
    y0 = t.clientY
    state = 'deciding'
  }

  const onTouchMove = (e: TouchEvent) => {
    const t = find(e.changedTouches)
    if (!t || state === 'idle' || state === 'ignored') return
    if (state === 'deciding') {
      // Un geste déjà pris par un panneau plus profond (feuille dans un écran
      // poussé, par exemple) n'est pas pris une seconde fois.
      if (e.defaultPrevented) { state = 'ignored'; return }
      const dx = t.clientX - x0
      const dy = t.clientY - y0
      if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return
      if (!e.cancelable || !opts.accept(dx, dy, e.target as Element)) { state = 'ignored'; return }
      state = 'active'
      // Repartir d'ici : le seuil franchi ne doit pas faire sauter le panneau.
      if (opts.axis === 'x') x0 = t.clientX
      else y0 = t.clientY
      samples = []
      opts.onStart()
    }
    e.preventDefault()
    const d = along(t)
    const now = performance.now()
    samples.push({ t: now, v: d })
    while (samples.length > 2 && now - samples[0].t > 100) samples.shift()
    opts.onMove(d)
  }

  const finish = (e: TouchEvent) => {
    const t = find(e.changedTouches)
    if (!t) return
    if (state === 'active') {
      const d = along(t)
      const first = samples[0]
      const last = samples[samples.length - 1]
      const dt = first && last ? (last.t - first.t) / 1000 : 0
      const velocity = dt > 0 ? (last.v - first.v) / dt : 0
      opts.onEnd(d, velocity)
    }
    state = 'idle'
    id = null
  }

  el.addEventListener('touchstart', onTouchStart, { passive: true })
  el.addEventListener('touchmove', onTouchMove, { passive: false })
  el.addEventListener('touchend', finish, { passive: true })
  el.addEventListener('touchcancel', finish, { passive: true })
  return () => {
    el.removeEventListener('touchstart', onTouchStart)
    el.removeEventListener('touchmove', onTouchMove)
    el.removeEventListener('touchend', finish)
    el.removeEventListener('touchcancel', finish)
  }
}

/** Un champ qui garde le doigt pour lui : on ne glisse jamais depuis. */
export function isTouchControl(el: Element): boolean {
  return !!el.closest('input, textarea, select, [contenteditable="true"], [data-no-drag]')
}

/** L'ancêtre de `el` (jusqu'à `root` exclu) qui défile verticalement, s'il y en a un. */
export function verticalScroller(el: Element, root: Element): HTMLElement | null {
  for (let n: Element | null = el; n && n !== root; n = n.parentElement) {
    if (!(n instanceof HTMLElement)) continue
    const oy = getComputedStyle(n).overflowY
    if ((oy === 'auto' || oy === 'scroll') && n.scrollHeight > n.clientHeight + 1) return n
  }
  return null
}

/** Un ancêtre de `el` (jusqu'à `root` exclu) qui peut encore défiler vers la gauche. */
export function canScrollLeft(el: Element, root: Element): boolean {
  for (let n: Element | null = el; n && n !== root; n = n.parentElement) {
    if (!(n instanceof HTMLElement)) continue
    const ox = getComputedStyle(n).overflowX
    if ((ox === 'auto' || ox === 'scroll') && n.scrollWidth > n.clientWidth + 1 && n.scrollLeft > 0) return true
  }
  return false
}

/**
 * Au-delà d'une butée, le panneau résiste au lieu de buter net : il suit le
 * doigt de moins en moins (courbe d'iOS, `c = 0.55`).
 */
export function rubber(over: number, size: number): number {
  const c = 0.55
  const sign = Math.sign(over)
  const a = Math.abs(over)
  return sign * (1 - 1 / ((a * c) / Math.max(1, size) + 1)) * size
}
