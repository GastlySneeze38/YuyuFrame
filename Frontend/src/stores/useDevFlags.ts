import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * Réglages de développement, posés depuis l'atelier (`pages/DevModals.tsx`,
 * Ctrl+Maj+M).
 *
 * `demoData` : montrer les données de démonstration (compétitif, missions,
 * passe de saison, amis) à la place des vraies routes, tant qu'elles n'existent
 * pas ou pour voir un écran rempli. Un seul interrupteur pour tous les écrans :
 * chacun avait sa propre règle, et l'on ne savait plus lequel montrait quoi.
 *
 * Lu seulement derrière `import.meta.env.DEV` : en production, `isDemo()`
 * vaut toujours faux, quoi que contienne le stockage.
 */
interface DevFlags {
  demoData: boolean
  setDemoData: (on: boolean) => void
}

export const useDevFlags = create<DevFlags>()(
  persist(
    (set) => ({
      demoData: true,
      setDemoData: (demoData) => set({ demoData }),
    }),
    { name: 'yuyuframe-dev-flags' },
  ),
)

/** Les données de démonstration sont-elles demandées ? Toujours non en production. */
export function useDemo(): boolean {
  const on = useDevFlags((s) => s.demoData)
  return import.meta.env.DEV && on
}

/** La même chose, hors d'un composant. */
export function isDemo(): boolean {
  return import.meta.env.DEV && useDevFlags.getState().demoData
}
