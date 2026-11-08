import { useEffect, useRef } from 'react'
import { listen } from '@tauri-apps/api/event'

// Abonnement à un event Tauri pour la durée de vie du composant — avant ce hook,
// ce triplet (listen + stockage de la fn unlisten + cleanup) était dupliqué à
// l'identique dans Home.tsx et Server.tsx.
//
// Un seul abonnement Tauri par événement, gardé toute la session : chaque
// composant ne fait qu'ajouter ou retirer sa fonction. `listen` et `unlisten`
// sont des appels au Rust, ~40 ms chacun sur téléphone : l'accueil en faisait
// cinq en arrivant et cinq en partant (mesuré le 2026-11-08, Galaxy A32).

type Handler = (payload: unknown) => void

const handlers = new Map<string, Set<Handler>>()

function subscribe(event: string, handler: Handler): () => void {
  let set = handlers.get(event)
  if (!set) {
    set = new Set()
    handlers.set(event, set)
    const own = set
    // Jamais retiré : le prochain écran qui écoute le même événement le
    // retrouve prêt. Copie avant d'appeler, un gestionnaire pouvant se
    // désabonner pendant l'envoi.
    void listen(event, (e) => [...own].forEach((h) => h(e.payload))).catch(() => {
      // Hors de Tauri (aperçu navigateur sans maquette) : on réessaiera au
      // prochain abonnement.
      handlers.delete(event)
    })
  }
  set.add(handler)
  return () => { set.delete(handler) }
}

export function useTauriEvent<T>(event: string, handler: (payload: T) => void, deps: unknown[] = []) {
  // La dernière version du gestionnaire est toujours celle appelée : il n'y a
  // donc pas à se réabonner quand il change.
  const latest = useRef(handler)
  latest.current = handler

  useEffect(
    () => subscribe(event, (payload) => latest.current(payload as T)),
    // `deps` est gardé pour les appelants existants : il ne change plus rien
    // au gestionnaire appelé, seulement au moment du réabonnement.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [event, ...deps],
  )
}
