import { create } from 'zustand'

type ToastVariant = 'error' | 'notice'

interface ErrorToastStore {
  message: string | null
  variant: ToastVariant
  show: (message: string, variant?: ToastVariant) => void
  hide: () => void
}

let hideTimer: ReturnType<typeof setTimeout> | null = null

// Store dédié (pas persisté, pas mêlé à useStore.ts) pour le popup global —
// avant ce fichier, chaque page/modale gérait son propre état `error`/`setError`
// affiché en banner inline, dispersé partout dans l'app. `variant` distingue
// les vraies erreurs (rouge) des notices neutres comme "Lancement annulé" (gris).
export const useErrorToast = create<ErrorToastStore>((set) => ({
  message: null,
  variant: 'error',
  show: (message, variant = 'error') => {
    if (hideTimer) clearTimeout(hideTimer)
    set({ message, variant })
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
  useErrorToast.getState().show(message, 'error')
}

/** Affiche une notice neutre (pas une erreur) dans le même popup global, en gris. */
export function showNotice(message: string): void {
  useErrorToast.getState().show(message, 'notice')
}
