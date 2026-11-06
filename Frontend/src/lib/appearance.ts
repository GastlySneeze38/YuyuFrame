import { getCurrentWebview } from '@tauri-apps/api/webview'
import { useStore } from '@/stores/useStore'
import { inTauri } from './platform'

/**
 * Applique les réglages d'apparence (2026-11-06) : thème de fond, couleur
 * d'accent, taille de l'interface, flou de verre, animations réduites.
 *
 * Tout passe par des attributs sur <html> (`data-theme`, `data-accent`,
 * `data-blur`, `data-motion`) qui changent les variables de `index.css` —
 * rien n'est redessiné à chaque image, contrairement à l'ancien
 * `filter: brightness()` posé sur toute la page.
 *
 * La taille passe par le zoom natif de la fenêtre (Tauri) : le texte reste
 * net et la mise en page se recalcule, là où un `transform: scale` flouterait
 * et un `zoom` CSS fausserait les mesures des écrans qui se mesurent.
 */
function apply() {
  const s = useStore.getState()
  const root = document.documentElement
  root.dataset.theme = s.appearanceTheme
  root.dataset.accent = s.accentColor
  root.dataset.blur = s.glassBlur ? 'on' : 'off'
  root.dataset.motion = s.reduceMotion ? 'reduced' : 'system'
  applyScale(s.uiScale)
}

let lastScale = 100

function applyScale(percent: number) {
  if (percent === lastScale) return
  lastScale = percent
  const factor = percent / 100
  if (inTauri) {
    // Le zoom de la fenêtre n'existe pas partout (téléphone) : on ignore.
    getCurrentWebview().setZoom(factor).catch((e) => console.warn('Zoom de la fenêtre refusé', e))
  } else {
    // Aperçu dans un navigateur (dev) : approximation suffisante.
    document.documentElement.style.zoom = String(factor)
  }
}

/** À appeler une fois, avant le premier rendu (main.tsx). */
export function startAppearance() {
  // La fenêtre démarre à 100 % : `applyScale` ne demande le zoom que s'il diffère.
  apply()
  useStore.subscribe((state, prev) => {
    if (
      state.appearanceTheme !== prev.appearanceTheme ||
      state.accentColor !== prev.accentColor ||
      state.glassBlur !== prev.glassBlur ||
      state.reduceMotion !== prev.reduceMotion ||
      state.uiScale !== prev.uiScale
    ) apply()
  })
}
