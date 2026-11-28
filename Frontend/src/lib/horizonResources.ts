import { create } from 'zustand'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'

/**
 * Les fichiers du jeu d'Horizon sur iPhone (2026-11-28) : l'iPhone ne peut
 * pas les préparer (il faudrait Java), il les télécharge depuis notre serveur
 * (LauncherAPI `GET /v1/horizon/resources/{version}/download`, compte
 * YuyuFrame connecté ; zips envoyés depuis le back-office). Le Rust reprend un
 * téléchargement coupé et vérifie l'empreinte (`horizon/transfer.rs`).
 *
 * Un magasin plutôt qu'un état d'écran : le téléchargement dure des minutes,
 * il doit survivre à la fermeture de la feuille, et trois endroits le
 * montrent — la feuille ouverte au lancement d'une instance
 * (`HorizonResourcesSheet`), le bloc de Paramètres → Stockage et celui de la
 * création d'une instance (`HorizonIosResources`).
 */
interface HorizonResourcesState {
  /** Installés ; `null` tant qu'on ne sait pas. */
  ready: boolean | null
  downloading: boolean
  /** Octets reçus et attendus (0 tant que le serveur ne l'a pas dit). */
  progress: { done: number; total: number } | null
  /** Instance à lancer une fois les fichiers installés : la feuille est ouverte. */
  pending: { instanceId: string; server?: string } | null
  refresh: () => Promise<boolean>
  download: () => Promise<boolean>
  ask: (instanceId: string, server?: string) => void
  dismiss: () => void
}

export const useHorizonResources = create<HorizonResourcesState>((set, get) => ({
  ready: null,
  downloading: false,
  progress: null,
  pending: null,
  refresh: async () => {
    const ready = await api.horizon.iosResourcesStatus().catch(() => false)
    set({ ready })
    return ready
  },
  download: async () => {
    if (get().downloading) return false
    set({ downloading: true, progress: { done: 0, total: 0 } })
    const unlisten = await listen<{ done: number; total: number }>('horizon_resources_progress', (e) => set({ progress: e.payload }))
    try {
      await api.horizon.iosResourcesDownload()
      set({ ready: true })
      return true
    } catch (e) {
      showError(e)
      return false
    } finally {
      unlisten()
      set({ downloading: false, progress: null })
    }
  },
  ask: (instanceId, server) => set({ pending: { instanceId, server } }),
  dismiss: () => set({ pending: null }),
}))
