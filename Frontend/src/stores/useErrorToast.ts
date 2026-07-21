import { create } from 'zustand'

interface ErrorToastStore {
  message: string | null
  show: (message: string) => void
  hide: () => void
}

let hideTimer: ReturnType<typeof setTimeout> | null = null

// Store dédié (pas persisté, pas mêlé à useStore.ts) pour le popup d'erreur
// global — avant ce fichier, chaque page/modale gérait son propre état
// `error`/`setError` affiché en banner inline, dispersé partout dans l'app.
export const useErrorToast = create<ErrorToastStore>((set) => ({
  message: null,
  show: (message) => {
    if (hideTimer) clearTimeout(hideTimer)
    set({ message })
    hideTimer = setTimeout(() => set({ message: null }), 4500)
  },
  hide: () => {
    if (hideTimer) clearTimeout(hideTimer)
    set({ message: null })
  },
}))

/** Formate n'importe quelle erreur attrapée et l'affiche dans le popup global. */
export function showError(e: unknown): void {
  const message = e instanceof Error ? e.message : typeof e === 'string' ? e : String(e)
  useErrorToast.getState().show(message)
}
