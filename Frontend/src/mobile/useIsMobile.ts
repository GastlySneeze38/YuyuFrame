import { useEffect, useState } from 'react'

/**
 * Largeur sous laquelle on montre les écrans du téléphone.
 *
 * La fenêtre du launcher PC ne descend jamais sous 900 px (`tauri.conf.json`),
 * donc elle ne bascule jamais. Pour l'instant, la vue téléphone ne s'atteint
 * que par l'aperçu navigateur (mode appareil de Chrome) ; l'appli Android
 * s'appuiera sur la même règle.
 */
const QUERY = '(max-width: 640px)'

/**
 * Disposition figée : la fenêtre du PC se réduit exprès en petit panneau de
 * 380 px pendant la connexion Microsoft (`pages/Login.tsx`). Sans ce gel,
 * l'application entière passait en vue téléphone, la page de connexion était
 * démontée, et en partant elle remettait la fenêtre en grand — le code ne
 * restait pas affiché une seconde.
 */
let held: boolean | null = null
const listeners = new Set<() => void>()

/** Gèle (`true`) ou libère (`false`) la disposition actuelle. */
export function holdLayout(on: boolean) {
  held = on ? (held ?? rawMatches()) : null
  listeners.forEach((l) => l())
}

function rawMatches(): boolean {
  return typeof window !== 'undefined' && window.matchMedia(QUERY).matches
}

/** La même règle lue une fois, hors d'un composant. */
export function matches(): boolean {
  return held ?? rawMatches()
}

/** Vrai quand l'écran a la largeur d'un téléphone. Suit les changements. */
export function useIsMobile(): boolean {
  const [mobile, setMobile] = useState(matches)
  useEffect(() => {
    const query = window.matchMedia(QUERY)
    const update = () => setMobile(matches())
    update()
    query.addEventListener('change', update)
    listeners.add(update)
    return () => {
      query.removeEventListener('change', update)
      listeners.delete(update)
    }
  }, [])
  return mobile
}
