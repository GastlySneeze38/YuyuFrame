/**
 * L'effet élastique du téléphone : quand on tire une zone qui ne peut plus
 * défiler — tout en haut, tout en bas, ou une page qui tient entière à
 * l'écran —, son contenu se tasse d'un côté et s'étire de l'autre, puis
 * revient en place quand on lâche. La WebView ne le fait pas d'elle-même
 * (`overscroll-behavior: contain` partout dans l'appli).
 *
 * **Un transfert d'espace, rien ne déborde** (refait le 2026-11-09, la
 * première version agrandissait toute la zone par `scaleY`, ce qui faisait
 * sortir le contenu de son cadre et redessinait la zone à chaque image) :
 * les bords de la zone restent fixes, et chaque élément se décale selon sa
 * place. Tirer vers le bas étire l'espacement en haut et le tasse en bas ;
 * tirer vers le haut, l'inverse. Le décalage d'un élément à la hauteur
 * relative `p` (0 en haut, 1 en bas) vaut `4·A·p·(1−p)` : nul aux deux bords,
 * `A` au milieu ; sa pente est positive au-dessus du milieu (ça s'écarte) et
 * négative en dessous (ça se tasse). `p` est pris au bord de l'élément qui
 * avance (voir `shift`).
 *
 * **Fluide** : seule la propriété `translate` des éléments change — elle se
 * compose avec leur `transform` sans l'écraser —, posés sur leur propre
 * calque le temps du geste (`will-change`), et écrite une fois par image
 * (`requestAnimationFrame`), jamais à chaque événement tactile. Un coup de
 * doigt vif sur une zone en butée donne tout de suite un petit rebond, joué
 * par la carte graphique (`element.animate`).
 *
 * Un seul écouteur pour toute l'appli, au niveau du document : il trouve la
 * zone qui défile sous le doigt (tout élément en `overflow-y: auto|scroll`,
 * même s'il n'a rien à faire défiler). Il ne touche à rien d'autre :
 * - un geste déjà pris par un panneau (feuille, menu : `mobile/ui/gesture`)
 *   arrive annulé, et il est ignoré ;
 * - un geste plutôt horizontal est ignoré ;
 * - un champ ou `data-no-stretch` l'arrête.
 */

/** Décalage maximal au milieu de la zone, en part de sa hauteur. */
const MAX = 0.05
/** Distance de tirage (px) qui donne ~63 % du décalage maximal. */
const SPAN = 220
/** Retour en place au lâcher. */
const RELEASE_MS = 420
const RELEASE_EASE = 'cubic-bezier(0.2, 0.9, 0.25, 1)'
/** Un coup plus vif que ça (px/s) sur une zone en butée donne un rebond. */
const FLICK_VELOCITY = 500

interface Item {
  el: HTMLElement
  /** Hauteur relative du haut et du bas de l'élément dans la zone, de 0 à 1. */
  top: number
  bottom: number
}

function scrollerOf(el: Element | null): HTMLElement | null {
  for (let n = el; n && n !== document.body; n = n.parentElement) {
    if (!(n instanceof HTMLElement)) continue
    const oy = getComputedStyle(n).overflowY
    if (oy === 'auto' || oy === 'scroll') return n
  }
  return null
}

/**
 * Les éléments à décaler : les enfants de la zone, ou ceux de son unique
 * enfant (une liste enveloppée dans un bloc), sur trois niveaux au plus.
 * Seulement ceux qu'on voit.
 */
function itemsOf(scroller: HTMLElement): Item[] {
  let parent: HTMLElement = scroller
  for (let depth = 0; depth < 3; depth++) {
    const kids = [...parent.children].filter((c): c is HTMLElement => c instanceof HTMLElement && c.getClientRects().length > 0)
    if (kids.length !== 1) break
    parent = kids[0]
  }
  const box = scroller.getBoundingClientRect()
  const h = Math.max(1, box.height)
  const items: Item[] = []
  for (const c of parent.children) {
    if (!(c instanceof HTMLElement)) continue
    const r = c.getBoundingClientRect()
    if (r.height === 0 || r.bottom < box.top || r.top > box.bottom) continue
    const rel = (y: number) => Math.min(1, Math.max(0, (y - box.top) / h))
    items.push({ el: c, top: rel(r.top), bottom: rel(r.bottom) })
  }
  return items
}

/**
 * Décalage d'un élément pour un décalage maximal `a` (signé). Il se calcule
 * au bord qui avance vers le bord de la zone — le bas quand on tire vers le
 * bas, le haut quand on tire vers le haut : un élément posé contre ce bord
 * ne bouge pas, donc rien ne sort du cadre (au milieu, il débordait de 7 px).
 */
const shift = (a: number, it: Item) => {
  const p = a > 0 ? it.bottom : it.top
  return 4 * a * p * (1 - p)
}

export function installOverscroll(): void {
  let scroller: HTMLElement | null = null
  let id = -1
  let startX = 0
  let anchor = 0
  let lastY = 0
  let samples: { t: number; y: number }[] = []
  let items: Item[] | null = null
  let amount = 0
  let frame = 0

  const paint = () => {
    frame = 0
    if (!items) return
    for (const it of items) it.el.style.translate = `0 ${shift(amount, it).toFixed(2)}px`
  }
  const schedule = () => { if (!frame) frame = requestAnimationFrame(paint) }

  const grab = (s: HTMLElement) => {
    items = itemsOf(s)
    for (const it of items) {
      // Un rebond encore en cours laisse la main au doigt.
      it.el.getAnimations().forEach((a) => { if (a.id === 'stretch') a.cancel() })
      it.el.style.transition = 'none'
      it.el.style.willChange = 'translate'
    }
  }

  const release = () => {
    if (frame) { cancelAnimationFrame(frame); frame = 0 }
    if (!items) return
    const list = items
    items = null
    amount = 0
    for (const it of list) {
      it.el.style.transition = `translate ${RELEASE_MS}ms ${RELEASE_EASE}`
      it.el.style.translate = ''
    }
    window.setTimeout(() => {
      for (const it of list) {
        if (it.el.style.translate) continue
        it.el.style.transition = ''
        it.el.style.willChange = ''
      }
    }, RELEASE_MS + 50)
  }

  /** Un coup vif contre la butée : un rebond court, sans attendre de tirage. */
  const bounce = (s: HTMLElement, velocity: number) => {
    const h = s.clientHeight
    const a = Math.sign(velocity) * Math.min(MAX * h, Math.abs(velocity) * 0.012)
    for (const it of itemsOf(s)) {
      const d = shift(a, it)
      const anim = it.el.animate(
        [{ translate: '0 0' }, { translate: `0 ${d.toFixed(2)}px`, offset: 0.3 }, { translate: '0 0' }],
        { duration: 380, easing: 'cubic-bezier(0.2, 0.8, 0.3, 1)' },
      )
      anim.id = 'stretch'
    }
  }

  document.addEventListener('touchstart', (e) => {
    if (e.touches.length !== 1) { release(); scroller = null; return }
    const t = e.touches[0]
    if ((e.target as Element | null)?.closest?.('input, textarea, select, [data-no-stretch]')) { scroller = null; return }
    release()
    scroller = scrollerOf(e.target as Element)
    id = t.identifier
    startX = t.clientX
    anchor = t.clientY
    lastY = t.clientY
    samples = [{ t: performance.now(), y: t.clientY }]
    // Déjà en butée : les calques se préparent dès que le doigt se pose, et
    // non au premier mouvement, où leur création coûtait une image de 70 à
    // 120 ms en plein geste. Sans tirage, ils sont rendus au lâcher.
    const s = scroller
    if (s && (s.scrollTop <= 0 || s.scrollTop + s.clientHeight >= s.scrollHeight - 1)) grab(s)
  }, { passive: true, capture: true })

  document.addEventListener('touchmove', (e) => {
    if (!scroller) return
    let t: Touch | null = null
    for (let i = 0; i < e.changedTouches.length; i++) if (e.changedTouches[i].identifier === id) t = e.changedTouches[i]
    if (!t) return
    if (e.defaultPrevented) { release(); scroller = null; return }
    const now = performance.now()
    samples.push({ t: now, y: t.clientY })
    while (samples.length > 2 && now - samples[0].t > 80) samples.shift()
    lastY = t.clientY
    const s = scroller
    const over = t.clientY - anchor
    // Plutôt horizontal : ce n'est pas un défilement.
    if (amount === 0 && Math.abs(t.clientX - startX) > Math.abs(over) + 4) return
    const atTop = s.scrollTop <= 0
    const atBottom = s.scrollTop + s.clientHeight >= s.scrollHeight - 1
    const pulling = (atTop && over > 0) || (atBottom && over < 0)
    if (!pulling) {
      // La zone défile encore : le tirage se compte à partir de la butée.
      anchor = t.clientY
      if (items) { amount = 0; schedule() }
      return
    }
    if (!items) grab(s)
    amount = Math.sign(over) * MAX * s.clientHeight * (1 - Math.exp(-Math.abs(over) / SPAN))
    schedule()
  }, { passive: true })

  const end = (e: TouchEvent) => {
    let t: Touch | null = null
    for (let i = 0; i < e.changedTouches.length; i++) if (e.changedTouches[i].identifier === id) t = e.changedTouches[i]
    if (!t || !scroller) return
    const s = scroller
    const stretched = !!items && Math.abs(amount) > 2
    const first = samples[0]
    const dt = first ? (performance.now() - first.t) / 1000 : 0
    const velocity = dt > 0 ? (lastY - first.y) / dt : 0
    release()
    scroller = null
    // Un coup vif qui n'a pas eu le temps d'étirer : le rebond tout de suite.
    if (!stretched && Math.abs(velocity) > FLICK_VELOCITY) {
      const atTop = s.scrollTop <= 0
      const atBottom = s.scrollTop + s.clientHeight >= s.scrollHeight - 1
      if ((atTop && velocity > 0) || (atBottom && velocity < 0)) bounce(s, velocity)
    }
  }
  document.addEventListener('touchend', end, { passive: true })
  document.addEventListener('touchcancel', end, { passive: true })
}
