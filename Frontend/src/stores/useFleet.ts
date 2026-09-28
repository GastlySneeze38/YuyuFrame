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
 *
 * ── Qui remplit ce magasin ────────────────────────────────────────────────
 * `apply`, sur l'événement `fleet_config` que le backend émet dès qu'il a du
 * neuf. `load` ne sert plus qu'au tout premier rendu (le backend a peut-être
 * déjà sa configuration, par exemple au retour d'une partie) et à la
 * relecture périodique, qui n'est là qu'en filet.
 *
 * Avant l'événement, le seul chemin était ce sondage d'une minute : le
 * launcher s'ouvrait avec une configuration vide, et la bannière d'accueil
 * comme les modales de notes de version n'apparaissaient qu'une minute plus
 * tard — souvent après le lancement de la partie, donc jamais vues.
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
  /** Configuration poussée par le backend dès qu'il en a une nouvelle
   *  (événement `fleet_config`) — voir `FleetNotices`. */
  apply: (config: FleetConfig) => void
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
  apply: (config) => set({ config }),
  dismiss: (id) => set({ dismissed: [...get().dismissed, id] }),
  isEnabled: (key) => {
    const flag = get().config.flags.find((f) => f.key === key)
    return flag ? flag.enabled : true
  },
  flagMessage: (key) => get().config.flags.find((f) => f.key === key)?.message ?? null,
}))

/** Bannières du bandeau encore à afficher (non fermées par l'utilisateur). */
export function visibleAnnouncements() {
  const { config, dismissed } = useFleet.getState()
  return config.announcements.filter((a) => a.placement !== 'home' && !dismissed.includes(a.id))
}
