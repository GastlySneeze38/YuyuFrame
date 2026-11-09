import { useEffect, useLayoutEffect, useRef, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { DURATION, EASE_OUT } from '@/lib/motion'

/**
 * Transition entre les écrans : le nouveau monte en apparaissant.
 *
 * Il n'y a volontairement PAS d'`AnimatePresence` ici. La version précédente
 * en utilisait une en `mode="wait"`, qui garde l'ancienne page montée le
 * temps de sa sortie avant de monter la nouvelle. En changeant vite d'écran,
 * cette attente se bloquait : la page suivante était montée mais restait à
 * son état de départ — `opacity: 0` — donc cliquable et invisible. On avait
 * les zones sensibles sans le rendu, et seul un rechargement s'en sortait.
 *
 * Un simple élément animé, recréé à chaque chemin, n'a pas d'état à perdre :
 * l'entrée se rejoue forcément au montage, donc une page affichée est
 * toujours visible. Le prix est l'animation de sortie, que personne ne voyait
 * vraiment puisqu'elle durait 120 ms derrière l'entrée de la suivante.
 */
export function PageTransition({ children, instantBetween, hold = false }: {
  children: ReactNode
  /** Écrans entre lesquels on passe sans transition — les onglets du
   *  téléphone : changer d'onglet est instantané sur iOS et Android. */
  instantBetween?: string[]
  /** Page montée mais pas encore montrée (sous l'écran de démarrage) : elle
   *  reste à son état de départ et entre quand on la relâche. */
  hold?: boolean
}) {
  const { pathname } = useLocation()
  const previous = useRef(pathname)
  const instant = !!instantBetween
    && instantBetween.includes(pathname)
    && instantBetween.includes(previous.current)
  useEffect(() => { previous.current = pathname }, [pathname])
  return (
    <PageEnter key={pathname} play={!instant} hold={hold}>
      {children}
    </PageEnter>
  )
}

/**
 * L'entrée d'une page (fondu et légère montée), jouée par la carte graphique
 * une fois la page dessinée (2026-11-09).
 *
 * Avant, Framer Motion la lançait au montage : sur téléphone, monter la page
 * bloquait le fil principal 100 à 300 ms, et l'animation en perdait le début
 * ou saccadait. Ici la page est posée invisible avant son premier dessin,
 * puis, deux images plus tard — le montage est fini —, une animation
 * `element.animate` de `opacity` et `transform` la fait entrer. Ces deux
 * propriétés tournent sur le compositeur : rien de ce qui suit sur le fil
 * principal (chargements, effets de la page) ne peut plus la gêner.
 *
 * `play` à faux : affichée telle quelle. `hold` : retenue invisible jusqu'à ce
 * qu'il repasse à faux.
 */
export function PageEnter({ children, play = true, hold = false }: {
  children: ReactNode
  play?: boolean
  hold?: boolean
}) {
  const ref = useRef<HTMLDivElement>(null)
  const played = useRef(false)

  // Invisible avant le premier dessin, sans quoi elle apparaîtrait une image
  // en entier avant de repartir de zéro.
  useLayoutEffect(() => {
    if (play && ref.current) ref.current.style.opacity = '0'
  }, [])

  useEffect(() => {
    const el = ref.current
    if (!el || !play || hold || played.current) return
    played.current = true
    const reduced = document.documentElement.dataset.motion === 'reduced'
      || window.matchMedia('(prefers-reduced-motion: reduce)').matches
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => {
        el.style.opacity = ''
        el.animate(
          reduced
            ? [{ opacity: 0 }, { opacity: 1 }]
            : [{ opacity: 0, transform: 'translateY(8px)' }, { opacity: 1, transform: 'translateY(0)' }],
          // Pas de `fill` : à l'arrivée l'animation disparaît, et aucune
          // transformation ne reste sur l'enveloppe de la page, qui servirait
          // sinon de repère aux éléments `fixed` qu'elle contient.
          { duration: DURATION.base * 1000, easing: `cubic-bezier(${EASE_OUT.join(', ')})` },
        )
      })
    })
    return () => {
      cancelAnimationFrame(frame)
      // Démontée ou relancée avant d'avoir joué : la page ne reste pas
      // invisible.
      if (el.style.opacity === '0') el.style.opacity = ''
      played.current = false
    }
  }, [play, hold])

  return (
    <div ref={ref} className="h-full w-full">
      {children}
    </div>
  )
}
