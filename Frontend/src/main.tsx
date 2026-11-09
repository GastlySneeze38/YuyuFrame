import React from 'react'
import ReactDOM from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { MotionConfig } from 'framer-motion'
import App from './App'
import './index.css'
import { transition } from './lib/motion'
import { inTauri } from './lib/platform'
import { loadLanguage } from './i18n'
import { useStore } from './stores/useStore'
import { startAppearance } from './lib/appearance'

/** `reducedMotion` : « user » suit le système (Framer Motion supprime alors
 *  les déplacements et ne garde que les fondus) ; « always » quand le réglage
 *  Animations réduites est coché dans Apparence. `transition` fixe le rythme
 *  par défaut de toute l'application. */
function Root() {
  const reduce = useStore((s) => s.reduceMotion)
  return (
    <MotionConfig reducedMotion={reduce ? 'always' : 'user'} transition={transition}>
      {/* `v7_startTransition` : chaque changement d'adresse est une transition
          React. La page suivante se prépare par tranches de quelques
          millisecondes entre deux images, au lieu d'un bloc qui figeait
          l'écran 100 à 300 ms sur téléphone, et la page courante reste
          affichée et réactive jusqu'à ce qu'elle soit prête — y compris
          pendant le chargement d'une page à la demande, au lieu d'un écran
          vide (2026-11-09). */}
      <HashRouter future={{ v7_startTransition: true }}>
        <App />
      </HashRouter>
    </MotionConfig>
  )
}

function render() {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <Root />
    </React.StrictMode>
  )
}

// Thème, accent, taille… posés avant le premier rendu : pas d'éclair violet
// sur fond Nuit au démarrage quand on a choisi autre chose.
startAppearance()

// Ouverte dans un navigateur pendant le développement : on simule Tauri avant
// le premier rendu (voir `dev/browserPreview.ts`). Le `import()` est DANS la
// branche : en release `import.meta.env.DEV` vaut `false`, la branche
// disparaît à la compilation et le fichier avec elle.
// La langue choisie est chargée AVANT le premier rendu (le magasin est relu
// de façon synchrone depuis localStorage) : sinon l'interface s'afficherait
// en français le temps d'arriver. `loadLanguage` ne rejette jamais.
const ready = loadLanguage(useStore.getState().language)

if (import.meta.env.DEV && !inTauri) {
  import('./dev/browserPreview')
    .then((preview) => preview.installBrowserPreview())
    .then(() => ready)
    .then(render)
} else {
  void ready.then(render)
}
