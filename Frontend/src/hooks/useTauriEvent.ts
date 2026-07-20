import { useEffect } from 'react'
import { listen } from '@tauri-apps/api/event'

// Abonnement à un event Tauri pour la durée de vie du composant — avant ce hook,
// ce triplet (listen + stockage de la fn unlisten + cleanup) était dupliqué à
// l'identique dans Home.tsx et Server.tsx.
export function useTauriEvent<T>(event: string, handler: (payload: T) => void, deps: unknown[] = []) {
  useEffect(() => {
    let unlisten: (() => void) | null = null
    let cancelled = false

    listen<T>(event, (e) => handler(e.payload)).then((fn) => {
      if (cancelled) fn()
      else unlisten = fn
    })

    return () => {
      cancelled = true
      unlisten?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
}
