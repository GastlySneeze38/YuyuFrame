import React from 'react'
import ReactDOM from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { MotionConfig } from 'framer-motion'
import App from './App'
import './index.css'
import { transition } from './lib/motion'

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
