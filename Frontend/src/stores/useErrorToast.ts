import { create } from 'zustand'
import { isNetworkError, isSessionExpiredError } from '@/lib/apiError'
import { useStore } from '@/stores/useStore'
import { t } from '@/i18n'

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

/**
 * À utiliser pour toute erreur venant d'un appel LauncherAPI déclenché par un
 * clic explicite (login, sync, checkout...) : une panne réseau s'affiche en
 * notice neutre avec `unreachableMessage` (le petit badge de la TitleBar
 * indique déjà l'état hors-ligne, pas besoin d'un toast rouge alarmant en
 * plus) ; toute autre erreur (mot de passe incorrect, quota dépassé...)
 * s'affiche normalement avec le message exact du serveur.
 */
export function showApiError(e: unknown, unreachableMessage: string): void {
  if (isNetworkError(e)) {
    useErrorToast.getState().show(unreachableMessage, 'notice')
    return
  }
  // Session YuyuFrame invalide/expirée (401 sur un appel authentifié) — on la
  // vide tout de suite plutôt que de laisser chaque appel suivant échouer en
  // boucle avec le même message brut ; l'UI retombe naturellement sur l'état
  // "non connecté" (bouton de connexion) dès le prochain rendu.
  if (isSessionExpiredError(e)) {
    useStore.getState().clearYuyuSession()
    useErrorToast.getState().show(t('common.sessionExpired'), 'notice')
    return
  }
  showError(e)
}
