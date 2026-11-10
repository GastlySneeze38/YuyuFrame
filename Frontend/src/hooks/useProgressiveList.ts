import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Une longue liste montrée au fur et à mesure (2026-11-10) : d'abord `step`
 * éléments, puis `step` de plus chaque fois qu'un repère posé en bas de la
 * liste approche de l'écran (400 px d'avance, pour que la suite soit là avant
 * qu'on l'atteigne). Quand tout ce qui est chargé est montré et que le serveur
 * en a encore (`onMore`), c'est la page suivante qu'on demande.
 *
 * L'historique des duels et la liste d'amis dessinaient tout d'un coup : des
 * dizaines de lignes avec leur tête de joueur, pour un écran qui en montre
 * huit.
 *
 * Usage : `const { visible, sentinel } = useProgressiveList(items.length, { onMore })`,
 * puis `items.slice(0, visible)` et `<div ref={sentinel} />` après la liste.
 */
export function useProgressiveList(total: number, { step = 20, onMore, resetKey }: {
  step?: number
  /** La page suivante du serveur ; absent quand il n'y en a plus. */
  onMore?: () => void
  /** Change → on repart de `step` (autre mode, autre filtre). */
  resetKey?: unknown
} = {}) {
  const [visible, setVisible] = useState(step)
  useEffect(() => setVisible(step), [resetKey, step])

  // Lus dans l'observateur sans le recréer à chaque rendu.
  const state = useRef({ total, visible, onMore })
  state.current = { total, visible, onMore }

  const observer = useRef<IntersectionObserver | null>(null)
  const target = useRef<HTMLElement | null>(null)
  const sentinel = useCallback(
    (el: HTMLElement | null) => {
      observer.current?.disconnect()
      observer.current = null
      target.current = el
      if (!el || typeof IntersectionObserver === 'undefined') return
      // L'avance de 400 px ne vaut que pour la racine observée : c'est donc
      // la zone qui défile autour de la liste, pas la fenêtre (nos listes
      // défilent dans leur carte ou leur colonne).
      const root = scrollParent(el)
      observer.current = new IntersectionObserver(
        ([entry]) => {
          if (!entry.isIntersecting) return
          const { total, visible, onMore } = state.current
          if (visible < total) setVisible((v) => v + step)
          else onMore?.()
        },
        { root, rootMargin: '400px 0px' },
      )
      observer.current.observe(el)
    },
    [step],
  )

  // Le repère peut rester à l'écran après un ajout (liste courte, grand
  // écran, page du serveur arrivée) : l'observateur ne prévient qu'aux
  // changements, donc on le réobserve, ce qui refait la vérification.
  useEffect(() => {
    const o = observer.current
    const el = target.current
    if (!o || !el) return
    o.unobserve(el)
    o.observe(el)
  }, [visible, total, onMore])

  useEffect(() => () => observer.current?.disconnect(), [])

  return { visible: Math.min(visible, total), sentinel }
}

/** Le plus proche parent qui défile verticalement ; `null` = la fenêtre. */
function scrollParent(el: HTMLElement): HTMLElement | null {
  for (let n = el.parentElement; n; n = n.parentElement) {
    const oy = getComputedStyle(n).overflowY
    if (oy === 'auto' || oy === 'scroll') return n
  }
  return null
}
