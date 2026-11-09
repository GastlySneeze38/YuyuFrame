import { createContext, memo, Suspense, useContext, useRef, type ComponentType } from 'react'
import { UNSAFE_LocationContext, useLocation, useNavigationType, type Location, type NavigationType } from 'react-router-dom'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { PageEnter } from '@/components/PageTransition'

/**
 * Les onglets du téléphone restent montés (2026-11-09).
 *
 * Changer d'onglet démontait la page quittée et montait la suivante : 100 à
 * 300 ms de fil principal bloqué sur un téléphone milieu de gamme, plus ses
 * appels au Rust (~40 ms chacun), et la page arrivait par à-coups. Comme une
 * barre d'onglets native, chaque onglet est monté à sa première visite puis
 * seulement masqué (`display: none`) : y revenir est instantané, et on le
 * retrouve où on l'avait laissé (défilement, feuilles, saisie).
 *
 * Deux précautions :
 *  - un onglet masqué garde l'adresse qu'il avait quand on l'a quitté
 *    (contexte d'adresse de React Router), sans quoi il réagirait aux
 *    paramètres d'une autre page ;
 *  - ce qu'un onglet pose hors de lui (portails vers `body` : feuilles du bas,
 *    panneau des amis) suit `useTabActive()` et disparaît avec lui.
 */

const TabActive = createContext(true)

/** Faux quand la page qui lit ce contexte est un onglet masqué. Hors des
 *  onglets gardés (PC, autres pages), toujours vrai. */
export function useTabActive(): boolean {
  return useContext(TabActive)
}

interface LocationValue {
  location: Location
  navigationType: NavigationType
}

interface TabEntry extends LocationValue {
  /** Monté sous l'écran de démarrage : il fait son entrée à la révélation.
   *  Les autres arrivent sans transition, comme entre onglets natifs. */
  enter: boolean
}

const KeptTab = memo(function KeptTab({ Page, value, shown, enter, hold }: {
  Page: ComponentType
  value: LocationValue
  shown: boolean
  enter: boolean
  hold: boolean
}) {
  return (
    <div className="h-full w-full" style={shown ? undefined : { display: 'none' }}>
      <TabActive.Provider value={shown}>
        <UNSAFE_LocationContext.Provider value={value}>
          <PageEnter play={enter} hold={hold}>
            <ErrorBoundary resetKey={value.location.pathname}>
              <Suspense fallback={null}>
                <Page />
              </Suspense>
            </ErrorBoundary>
          </PageEnter>
        </UNSAFE_LocationContext.Provider>
      </TabActive.Provider>
    </div>
  )
})

/**
 * Les onglets visités, montés. `tabs` associe un chemin à sa page ; la page
 * de l'adresse courante est affichée, les autres déjà visitées sont masquées.
 * Ne rend rien pour une adresse qui n'est pas un onglet.
 */
export function KeptTabs({ tabs, hold }: {
  tabs: Record<string, ComponentType>
  /** Vrai tant que l'écran de démarrage couvre l'application. */
  hold: boolean
}) {
  const location = useLocation()
  const navigationType = useNavigationType()
  // Par onglet, la dernière adresse vue quand il était affiché. Le même objet
  // est rendu tant qu'il est masqué : `memo` ne le redessine donc pas à
  // chaque navigation ailleurs.
  const entries = useRef(new Map<string, TabEntry>())
  const active = location.pathname
  if (tabs[active]) {
    const prev = entries.current.get(active)
    if (!prev || prev.location !== location || prev.navigationType !== navigationType) {
      entries.current.set(active, { location, navigationType, enter: prev ? prev.enter : hold })
    }
  }
  return (
    <>
      {[...entries.current].map(([path, entry]) => (
        <KeptTab
          key={path}
          Page={tabs[path]}
          value={entry}
          shown={path === active}
          enter={entry.enter}
          hold={hold}
        />
      ))}
    </>
  )
}
