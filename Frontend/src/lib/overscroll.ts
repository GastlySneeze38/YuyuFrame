/**
 * L'effet élastique du téléphone (2026-11-09) : quand on tire une zone qui
 * ne peut plus défiler — tout en haut, tout en bas, ou une page qui tient
 * entière à l'écran —, elle s'étire légèrement du côté tiré au lieu de rester
 * figée, puis revient en place quand on lâche. C'est l'étirement d'Android
 * 12+, que la WebView ne fait pas d'elle-même (`overscroll-behavior: contain`
 * partout dans l'appli coupe aussi l'enchaînement vers la page).
 *
 * Un seul écouteur pour toute l'appli, au niveau du document : il trouve la
 * zone qui défile sous le doigt (tout élément en `overflow-y: auto|scroll`,
 * même s'il n'a rien à faire défiler) et l'étire par `transform: scaleY`,
 * depuis le bord opposé — rien n'est remis en page, la carte graphique s'en
 * charge. Il ne touche à rien d'autre :
 * - un geste déjà pris par un panneau (feuille, menu : `mobile/ui/gesture`)
 *   arrive annulé, et il est ignoré ;
 * - un geste plutôt horizontal est ignoré ;
 * - un champ (curseur, saisie) ou `data-no-stretch` l'arrête.
 *
 * Seulement au toucher : la souris et le pavé du PC ne déclenchent rien.
 */

/** Étirement maximal (6 %), atteint progressivement. */
const MAX = 0.06
/** Distance de tirage (px) qui donne ~63 % de l'étirement maximal. */
const SPAN = 260
const RELEASE = 'transform 420ms cubic-bezier(0.2, 0.9, 0.25, 1)'

function scrollerOf(el: Element | null): HTMLElement | null {
  for (let n = el; n && n !== document.body; n = n.parentElement) {
    if (!(n instanceof HTMLElement)) continue
    const oy = getComputedStyle(n).overflowY
    if (oy === 'auto' || oy === 'scroll') return n
  }
  return null
}

export function installOverscroll(): void {
  let target: HTMLElement | null = null
  let id = -1
  let startX = 0
  let anchor = 0
  let stretched = false

  const reset = () => {
    if (target && stretched) {
      const el = target
      el.style.transition = RELEASE
      el.style.transform = ''
      const done = () => {
        el.style.transition = ''
        el.style.transformOrigin = ''
        el.removeEventListener('transitionend', done)
      }
      el.addEventListener('transitionend', done)
      window.setTimeout(done, 500)
    }
    target = null
    stretched = false
    id = -1
  }

  document.addEventListener('touchstart', (e) => {
    if (e.touches.length !== 1) { reset(); return }
    const t = e.touches[0]
    if ((e.target as Element | null)?.closest?.('input, textarea, select, [data-no-stretch]')) return
    target = scrollerOf(e.target as Element)
    id = t.identifier
    startX = t.clientX
    anchor = t.clientY
    stretched = false
  }, { passive: true, capture: true })

  document.addEventListener('touchmove', (e) => {
    if (!target) return
    let t: Touch | null = null
    for (let i = 0; i < e.changedTouches.length; i++) if (e.changedTouches[i].identifier === id) t = e.changedTouches[i]
    if (!t) return
    if (e.defaultPrevented) { reset(); return }
    const el = target
    const atTop = el.scrollTop <= 0
    const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 1
    const over = t.clientY - anchor
    // Plutôt horizontal : ce n'est pas un défilement.
    if (!stretched && Math.abs(t.clientX - startX) > Math.abs(over) + 4) return
    const pullingTop = atTop && over > 0
    const pullingBottom = atBottom && over < 0
    if (!pullingTop && !pullingBottom) {
      // La zone défile encore : le tirage se compte à partir de la butée.
      anchor = t.clientY
      if (stretched) { el.style.transform = ''; stretched = false }
      return
    }
    const k = MAX * (1 - Math.exp(-Math.abs(over) / SPAN))
    if (!stretched) {
      el.style.transition = 'none'
      el.style.transformOrigin = pullingTop ? 'top' : 'bottom'
      stretched = true
    }
    el.style.transform = `scaleY(${1 + k})`
  }, { passive: true })

  const end = (e: TouchEvent) => {
    for (let i = 0; i < e.changedTouches.length; i++) if (e.changedTouches[i].identifier === id) { reset(); return }
  }
  document.addEventListener('touchend', end, { passive: true })
  document.addEventListener('touchcancel', end, { passive: true })
}
