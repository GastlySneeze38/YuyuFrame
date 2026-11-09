/**
 * L'effet élastique du téléphone : quand on tire une zone qui ne peut plus
 * défiler — tout en haut, tout en bas, ou une page qui tient entière à
 * l'écran —, son contenu suit le doigt en résistant, puis revient en place
 * quand on lâche. La WebView ne le fait pas d'elle-même
 * (`overscroll-behavior: contain` partout dans l'appli).
 *
 * **Le « rubber band » d'iOS** : tout le contenu glisse d'un bloc, rien ne se
 * déforme, et plus on tire, moins il suit (décalage `MAX_PX·(1 − e^(−d/SPAN))`,
 * qui part à un tiers de la vitesse du doigt et plafonne à 40 px). Ce qui
 * passe le bord est rogné par la zone elle-même. Troisième version du
 * 2026-11-09 : un `scaleY` de toute la zone faisait déborder et saccadait ;
 * puis un « transfert d'espace » laissait immobiles les blocs du bord et
 * décalait ceux du milieu jusqu'à 26 px, ce qui se lisait comme un bug.
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

/** Décalage maximal du contenu (px), atteint en tirant franchement. */
const MAX_PX = 40
/** Distance de tirage (px) qui donne ~63 % du décalage maximal. */
const SPAN = 120
/** Retour en place au lâcher. */
const RELEASE_MS = 420
const RELEASE_EASE = 'cubic-bezier(0.2, 0.9, 0.25, 1)'
/** Un coup plus vif que ça (px/s) sur une zone en butée donne un rebond. */
const FLICK_VELOCITY = 500

interface Item {
  el: HTMLElement
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
 * Les éléments à décaler : les enfants directs de la zone que l'on voit. Tous
 * reçoivent le même décalage, donc le contenu bouge d'un bloc.
 */
function itemsOf(scroller: HTMLElement): Item[] {
  const box = scroller.getBoundingClientRect()
  const items: Item[] = []
  for (const c of scroller.children) {
    if (!(c instanceof HTMLElement)) continue
    const r = c.getBoundingClientRect()
    if (r.height === 0 || r.bottom < box.top || r.top > box.bottom) continue
    items.push({ el: c })
  }
  return items
}

/** Décalage du contenu pour un tirage de `d` px au-delà de la butée (signé). */
const pull = (d: number) => Math.sign(d) * MAX_PX * (1 - Math.exp(-Math.abs(d) / SPAN))

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
    const v = `0 ${amount.toFixed(2)}px`
    for (const it of items) it.el.style.translate = v
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
    // Un rebond discret : 10 à 20 px selon la vivacité du coup.
    const d = Math.sign(velocity) * Math.min(20, 10 + Math.abs(velocity) * 0.004)
    for (const it of itemsOf(s)) {
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
    amount = pull(over)
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
