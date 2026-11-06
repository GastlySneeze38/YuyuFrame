import { create } from 'zustand'
import { api } from '@/api/client'
import type { FleetAnnouncement, FleetConfig } from '@/api/client'
import { readCache, writeCache } from '@/lib/persistentCache'

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
  /** Bannière d'accueil imposée par l'atelier de développement
   *  (`pages/DevModals.tsx`) ; jamais lue hors `import.meta.env.DEV`. Elle vit
   *  à côté de `config` et non dedans : la configuration est remplacée en
   *  entier à chaque chargement, l'essai disparaîtrait au bout d'une minute. */
  devHomeBanner: FleetAnnouncement | null
  setDevHomeBanner: (banner: FleetAnnouncement | null) => void
  /** Une clé inconnue vaut « activée ». */
  isEnabled: (key: string) => boolean
  flagMessage: (key: string) => string | null
}

/** La dernière configuration reçue sert au premier rendu (bannière d'accueil,
 *  interrupteurs) en attendant la nouvelle — une semaine au plus : au-delà,
 *  une bannière périmée gênerait plus qu'elle n'aide. */
const CACHE_KEY = 'fleet-config'
const CACHE_MAX_AGE = 7 * 24 * 3600_000

function keep(config: FleetConfig): FleetConfig {
  writeCache(CACHE_KEY, config)
  return config
}

export const useFleet = create<FleetStore>((set, get) => ({
  config: readCache<FleetConfig>(CACHE_KEY, CACHE_MAX_AGE)?.value ?? EMPTY,
  dismissed: [],
  load: async () => {
    try {
      set({ config: keep(await api.fleet.config()) })
    } catch {
      // Jamais bloquant : on garde ce qu'on a.
    }
  },
  refresh: async () => {
    try {
      set({ config: keep(await api.fleet.refresh()) })
    } catch {
      /* idem */
    }
  },
  apply: (config) => set({ config: keep(config) }),
  dismiss: (id) => set({ dismissed: [...get().dismissed, id] }),
  devHomeBanner: null,
  setDevHomeBanner: (devHomeBanner) => set({ devHomeBanner }),
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
