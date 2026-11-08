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

/** La même règle lue une fois, hors d'un composant. */
export function matches(): boolean {
  return typeof window !== 'undefined' && window.matchMedia(QUERY).matches
}

/** Vrai quand l'écran a la largeur d'un téléphone. Suit les changements. */
export function useIsMobile(): boolean {
  const [mobile, setMobile] = useState(matches)
  useEffect(() => {
    const query = window.matchMedia(QUERY)
    const update = () => setMobile(query.matches)
    update()
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])
  return mobile
}
