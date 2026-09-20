import { create } from 'zustand'
import { api } from '@/api/client'
import type { FleetConfig } from '@/api/client'

/**
 * Pilotage du launcher depuis le back-office (`GET /v1/config`, catégorie
 * Plateforme) : version minimale, version interdite, interrupteurs de
 * fonctionnalités et bannières.
 *
 * Le backend Rust relit la configuration au démarrage puis toutes les 15
 * minutes et garde la dernière connue ; ici on ne fait que la lire, donc
 * sans réseau ni attente. Tant que rien n'a été chargé, tout est autorisé :
 * une configuration indisponible ne doit jamais bloquer le launcher.
 */

const EMPTY: FleetConfig = {
  min_launcher_version: null,
  min_version_message: null,
  update_required: false,
  launcher_blocked: null,
  blocked_agent_versions: [],
  flags: [],
  announcements: [],
}

interface FleetStore {
  config: FleetConfig
  /** Bannières fermées à la main, par identifiant (le temps de la session). */
  dismissed: string[]
  load: () => Promise<void>
  /** Force une relecture depuis le serveur (après connexion, par exemple). */
  refresh: () => Promise<void>
  dismiss: (id: string) => void
  /** Une clé inconnue vaut « activée ». */
  isEnabled: (key: string) => boolean
  flagMessage: (key: string) => string | null
}

export const useFleet = create<FleetStore>((set, get) => ({
  config: EMPTY,
  dismissed: [],
  load: async () => {
    try {
      set({ config: await api.fleet.config() })
    } catch {
      // Jamais bloquant : on garde ce qu'on a.
    }
  },
  refresh: async () => {
    try {
      set({ config: await api.fleet.refresh() })
    } catch {
      /* idem */
    }
  },
  dismiss: (id) => set({ dismissed: [...get().dismissed, id] }),
  isEnabled: (key) => {
    const flag = get().config.flags.find((f) => f.key === key)
    return flag ? flag.enabled : true
  },
  flagMessage: (key) => get().config.flags.find((f) => f.key === key)?.message ?? null,
}))

/** Bannières encore à afficher (non fermées par l'utilisateur). */
export function visibleAnnouncements() {
  const { config, dismissed } = useFleet.getState()
  return config.announcements.filter((a) => !dismissed.includes(a.id))
}
