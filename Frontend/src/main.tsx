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

function render() {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      {/* `reducedMotion="user"` : si le système demande des animations réduites,
          Framer Motion supprime les déplacements et ne garde que les fondus.
          `transition` fixe le rythme par défaut de toute l'application. */}
      <MotionConfig reducedMotion="user" transition={transition}>
        <HashRouter>
          <App />
        </HashRouter>
      </MotionConfig>
    </React.StrictMode>
  )
}

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
