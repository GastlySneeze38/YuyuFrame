import { useEffect, useRef, type ReactNode } from 'react'
import { bindTouchDrag, isTouchControl, rubber } from './gesture'
import { motionReduced } from './slide'

/** Distance (px) ou vitesse (px/s) d'un balayage qui change d'onglet. */
const SWIPE_OFFSET = 60
const SWIPE_VELOCITY = 400

/**
 * Des onglets qu'on change en balayant le contenu vers la gauche ou la
 * droite, comme les onglets d'Android (2026-11-10, d'abord écrit pour le
 * Compétitif le 2026-11-09). Pendant le geste, le contenu suit un peu le
 * doigt en résistant ; s'il n'y a rien derrière, il résiste davantage et
 * revient.
 *
 * Règles :
 * - **le niveau le plus profond prend le geste** : des onglets dans des
 *   onglets (Mods : Packs › Installés / Shaders / Ressources), c'est
 *   Installés / Shaders / Ressources qui changent. Chaque zone porte
 *   `data-swipe-tabs`, et ne prend que les gestes dont la zone la plus proche
 *   est elle-même ;
 * - au **premier onglet**, un balayage vers la droite n'est pas pris : sur un
 *   écran poussé, c'est le retour (`PushedScreen` laisse passer tant que
 *   `data-swipe-prev` dit qu'il y a un onglet avant) ;
 * - un geste plutôt vertical reste un défilement, un champ garde le doigt,
 *   et un contenu qui défile lui-même de côté garde son geste.
 *
 * Le contenu doit être **un seul élément** : c'est lui qui suit le doigt.
 * `animate` (vrai par défaut) fait arriver le nouvel onglet du côté vers
 * lequel on va ; à couper quand le contenu a déjà sa propre entrée.
 */
export function SwipeTabs<T>({ tabs, value, onChange, animate = true, className = 'flex min-h-0 flex-1 flex-col', children }: {
  tabs: readonly T[]
  value: T
  onChange: (next: T) => void
  animate?: boolean
  className?: string
  children: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)
  const latest = useRef({ tabs, value, onChange, animate })
  latest.current = { tabs, value, onChange, animate }

  useEffect(() => {
    const el = ref.current!
    const column = () => el.firstElementChild as HTMLElement | null
    // Doigt vers la gauche (d < 0) → onglet suivant.
    const neighbour = (d: number): { tab: T } | null => {
      const { tabs, value } = latest.current
      const i = tabs.indexOf(value) + (d < 0 ? 1 : -1)
      return i >= 0 && i < tabs.length ? { tab: tabs[i] } : null
    }
    return bindTouchDrag(el, {
      axis: 'x',
      accept: (dx, dy, target) =>
        Math.abs(dx) > Math.abs(dy) * 1.5 &&
        target.closest('[data-swipe-tabs]') === el &&
        latest.current.tabs.indexOf(latest.current.value) >= 0 &&
        !(dx > 0 && !neighbour(dx)) &&
        !isTouchControl(target) &&
        !canScrollHorizontally(target, el, dx),
      onStart: () => {
        const c = column()
        if (c) c.style.transition = 'none'
      },
      onMove: (d) => {
        const c = column()
        // Le doigt « tire » le contenu : plus fort quand il n'y a rien derrière.
        if (c) c.style.transform = `translateX(${rubber(d, neighbour(d) ? 90 : 40)}px)`
      },
      onEnd: (d, velocity) => {
        const next = neighbour(d)
        const c = column()
        if (next && (Math.abs(d) > SWIPE_OFFSET || Math.abs(velocity) > SWIPE_VELOCITY) && Math.sign(velocity || d) === Math.sign(d)) {
          if (c) {
            c.style.transition = ''
            c.style.transform = ''
          }
          latest.current.onChange(next.tab)
          // Le nouvel onglet arrive du côté vers lequel on va, après son rendu.
          if (latest.current.animate && !motionReduced()) {
            const from = d < 0 ? 24 : -24
            requestAnimationFrame(() => {
              column()?.animate(
                [{ transform: `translateX(${from}px)`, opacity: 0 }, { transform: 'none', opacity: 1 }],
                { duration: 200, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' },
              )
            })
          }
          return
        }
        if (c) {
          c.style.transition = 'transform 260ms cubic-bezier(0.2, 0.9, 0.25, 1)'
          c.style.transform = ''
          window.setTimeout(() => { c.style.transition = '' }, 300)
        }
      },
    })
  }, [])

  // Hors des onglets (`value` absent de `tabs`) : la zone ne prend rien.
  const first = tabs.indexOf(value) <= 0
  return (
    <div ref={ref} data-swipe-tabs="" data-swipe-prev={first ? undefined : ''} className={className}>
      {children}
    </div>
  )
}

/** Un ancêtre de `el` (jusqu'à `root` exclu) qui peut encore défiler dans le sens du geste. */
function canScrollHorizontally(el: Element, root: Element, dx: number): boolean {
  for (let n: Element | null = el; n && n !== root; n = n.parentElement) {
    if (!(n instanceof HTMLElement) || n.scrollWidth <= n.clientWidth + 1) continue
    const ox = getComputedStyle(n).overflowX
    if (ox !== 'auto' && ox !== 'scroll') continue
    if (dx > 0 ? n.scrollLeft > 0 : n.scrollLeft + n.clientWidth < n.scrollWidth - 1) return true
  }
  return false
}
