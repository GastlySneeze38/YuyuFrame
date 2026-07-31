import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Instance, Version, Account } from '@/types'
import { AUTH_SYSTEM_VERSION } from '@/config/authVersion'

export type YuyuPlan = 'free' | 'premium' | 'ultimate'
export type Lang = 'fr' | 'en'

interface Store {
  // ── YuyuFrame session (persisté — le JWT dure 30 jours côté serveur, pas
  // besoin de se reconnecter à chaque lancement ; un token invalide/expiré
  // est détecté au premier appel API et la session est vidée automatiquement,
  // voir showApiError/isSessionExpiredError) ──
  yuyuToken: string | null
  yuyuUsername: string | null
  yuyuPlan: YuyuPlan
  yuyuPlanExpiresAt: number | null
  setYuyuSession: (token: string, username: string, plan: YuyuPlan, planExpiresAt: number | null) => void
  setYuyuPlan: (plan: YuyuPlan, planExpiresAt: number | null) => void
  clearYuyuSession: () => void
  isPremium: () => boolean
  isUltimate: () => boolean

  // ── Connectivité LauncherAPI (jamais persistée — revérifiée à chaque
  // démarrage) — `null` tant qu'aucun check n'a encore abouti, pour ne pas
  // afficher le badge hors-ligne une fraction de seconde avant le tout
  // premier ping. Purement indicatif : ne bloque jamais rien (voir App.tsx).
  apiOnline: boolean | null
  setApiOnline: (v: boolean | null) => void

  // ── Active Minecraft account ───────────────────────────────────────────────
  username: string | null
  uuid: string | null
  isOffline: boolean
  setUser: (username: string, uuid: string, isOffline: boolean) => void
  clearUser: () => void

  // ── Minecraft account list ─────────────────────────────────────────────────
  accounts: Account[]
  setAccounts: (accounts: Account[]) => void
  addAccount: (username: string, uuid: string, isOffline: boolean) => void
  removeAccount: (uuid: string) => void
  switchAccount: (uuid: string) => void

  // ── Versions (for instance creation) ──────────────────────────────────────
  versions: Version[]
  setVersions: (v: Version[]) => void

  // ── Instances ─────────────────────────────────────────────────────────────
  instances: Instance[]
  setInstances: (instances: Instance[]) => void
  addInstance: (instance: Instance) => void
  updateInstance: (instance: Instance) => void
  removeInstance: (id: string) => void

  selectedInstanceId: string | null
  setSelectedInstanceId: (id: string | null) => void
  selectedInstance: () => Instance | null

  // ── Settings (persisted) ──────────────────────────────────────────────────
  defaultRam: number
  setDefaultRam: (r: number) => void

  closeOnLaunch: boolean
  setCloseOnLaunch: (v: boolean) => void

  p2pEnabled: boolean
  setP2pEnabled: (v: boolean) => void

  brightness: number
  setBrightness: (b: number) => void

  instanceSyncMode: 'db_wins' | 'disk_wins'
  setInstanceSyncMode: (mode: 'db_wins' | 'disk_wins') => void

  avoidBetaDependencies: boolean
  setAvoidBetaDependencies: (v: boolean) => void

  syncGameSettings: boolean
  setSyncGameSettings: (v: boolean) => void

  showConsole: boolean
  setShowConsole: (v: boolean) => void

  showHomeServers: boolean
  setShowHomeServers: (v: boolean) => void

  confirmServerLaunch: boolean
  setConfirmServerLaunch: (v: boolean) => void

  language: Lang
  setLanguage: (l: Lang) => void

  // ── Game state (par instance) ─────────────────────────────────────────────
  runningInstances: string[]
  isInstanceRunning: (id: string) => boolean
  setInstanceRunning: (id: string, running: boolean) => void
  /** true si au moins une instance tourne (rétro-compat) */
  gameRunning: boolean
  setGameRunning: (r: boolean) => void

  // ── Last session ───────────────────────────────────────────────────────────
  lastSession: { instanceName: string; at: string } | null
  setLastSession: (s: { instanceName: string; at: string }) => void

  // ── Mods épinglés (persisté) — mod projectId pour lequel on a délibérément
  // basculé sur une version plus ancienne : on n'affiche plus le badge "mise
  // à jour disponible" pour ce mod tant que ce n'est pas désépinglé.
  pinnedMods: Record<string, boolean>
  isModPinned: (instanceId: string, projectId: string) => boolean
  setModPinned: (instanceId: string, projectId: string, pinned: boolean) => void

  // ── Durée de la phase "lancement" (JVM + chargement Minecraft, entre la fin
  // des téléchargements et game_ready) par instance — moyenne mobile en ms,
  // réutilisée pour animer la barre de progression sur les lancements suivants
  // au lieu de la laisser figée pendant cette phase (voir Home.tsx).
  launchPhaseDurations: Record<string, number>
  recordLaunchPhaseDuration: (instanceId: string, ms: number) => void

  // ── Serveurs épinglés sur l'accueil (persisté), par instance — voir
  // ServerManageModal. Max 3 par instance (correspond aux 3 slots de la
  // rangée sur Home.tsx) : toggleFavoriteServer renvoie `false` sans rien
  // changer si on tente d'en épingler un 4ᵉ.
  favoriteServers: Record<string, string[]>
  isServerFavorite: (instanceId: string, ip: string) => boolean
  toggleFavoriteServer: (instanceId: string, ip: string) => boolean

  // ── Notes de patch en attente (persisté) — posé par UpdateChecker juste
  // avant `relaunch()` (l'état mémoire ne survit pas au redémarrage complet
  // du process), lu une fois par App.tsx au montage suivant puis effacé.
  // Prend le pas sur le rappel compte hors ligne (voir App.tsx) : affiché
  // en premier, le rappel ne s'affiche qu'une fois les notes fermées.
  pendingPatchNotes: { version: string; notes: string } | null
  setPendingPatchNotes: (v: { version: string; notes: string } | null) => void

  // ── Version du système de connexion vue par cet utilisateur (persisté) —
  // comparée à AUTH_SYSTEM_VERSION (config/authVersion.ts) au démarrage par
  // App.tsx : si en retard, ReconnectModal s'affiche une fois puis cette
  // valeur est remise à jour. Défaut = version actuelle (pas de faux
  // déclenchement pour les utilisateurs déjà installés au moment où ce champ
  // a été introduit) ; seule une future hausse de la constante déclenche.
  authSystemVersion: number
  setAuthSystemVersion: (v: number) => void

  // ── Migration one-shot des ids d'instance (voir instance_id_migrations côté
  // backend, lib.rs) — remappe les clés persistées ci-dessus qui référencent
  // encore un ancien id d'instance renommé au nouveau format lisible.
  // Purement une histoire de confort perdu sinon (favoris, épingles, calibrage
  // de barre de progression) — jamais de fichier/mod/save touché.
  applyInstanceIdMigrations: (migrations: { oldId: string; newId: string }[]) => void
}

export const useStore = create<Store>()(
  persist(
    (set, get) => ({
      // YuyuFrame session
      yuyuToken: null,
      yuyuUsername: null,
      yuyuPlan: 'free',
      yuyuPlanExpiresAt: null,
      setYuyuSession: (token, username, plan, planExpiresAt) =>
        set({ yuyuToken: token, yuyuUsername: username, yuyuPlan: plan, yuyuPlanExpiresAt: planExpiresAt }),
      setYuyuPlan: (plan, planExpiresAt) =>
        set({ yuyuPlan: plan, yuyuPlanExpiresAt: planExpiresAt }),
      clearYuyuSession: () =>
        set({ yuyuToken: null, yuyuUsername: null, yuyuPlan: 'free', yuyuPlanExpiresAt: null, accounts: [], username: null, uuid: null }),
      isPremium: () => {
        const { yuyuPlan, yuyuPlanExpiresAt } = get()
        const active = yuyuPlan === 'premium' || yuyuPlan === 'ultimate'
        const notExpired = yuyuPlanExpiresAt === null || yuyuPlanExpiresAt > Date.now() / 1000
        return active && notExpired
      },
      isUltimate: () => {
        const { yuyuPlan, yuyuPlanExpiresAt } = get()
        const notExpired = yuyuPlanExpiresAt === null || yuyuPlanExpiresAt > Date.now() / 1000
        return yuyuPlan === 'ultimate' && notExpired
      },

      apiOnline: null,
      setApiOnline: (apiOnline) => set({ apiOnline }),

      // Active MC account
      username: null,
      uuid: null,
      isOffline: false,
      setUser: (username, uuid, isOffline) => set({ username, uuid, isOffline }),
      clearUser: () => set({ username: null, uuid: null, isOffline: false }),

      // MC account list
      accounts: [],
      setAccounts: (accounts) => set({ accounts }),
      addAccount: (username, uuid, isOffline) => {
        const accounts = get().accounts
        const idx = accounts.findIndex((a) => a.uuid === uuid)
        const next =
          idx >= 0
            ? accounts.map((a, i) => (i === idx ? { username, uuid, is_offline: isOffline } : a))
            : [...accounts, { username, uuid, is_offline: isOffline }]
        set({ accounts: next, username, uuid, isOffline })
      },
      removeAccount: (targetUuid) => {
        const accounts = get().accounts.filter((a) => a.uuid !== targetUuid)
        if (get().uuid === targetUuid) {
          const other = accounts[0] ?? null
          set({ accounts, username: other?.username ?? null, uuid: other?.uuid ?? null })
        } else {
          set({ accounts })
        }
      },
      switchAccount: (targetUuid) => {
        const account = get().accounts.find((a) => a.uuid === targetUuid)
        if (account) set({ username: account.username, uuid: account.uuid, isOffline: account.is_offline })
      },

      // Versions
      versions: [],
      setVersions: (versions) => set({ versions }),

      // Instances
      instances: [],
      setInstances: (instances) => set({ instances }),
      addInstance: (instance) => set((s) => ({ instances: [...s.instances, instance] })),
      updateInstance: (instance) =>
        set((s) => ({
          instances: s.instances.map((i) => (i.id === instance.id ? instance : i)),
        })),
      removeInstance: (id) =>
        set((s) => ({
          instances: s.instances.filter((i) => i.id !== id),
          selectedInstanceId: s.selectedInstanceId === id ? null : s.selectedInstanceId,
        })),

      selectedInstanceId: null,
      setSelectedInstanceId: (id) => set({ selectedInstanceId: id }),
      selectedInstance: () => {
        const { instances, selectedInstanceId } = get()
        return instances.find((i) => i.id === selectedInstanceId) ?? null
      },

      // Settings
      defaultRam: 4096,
      setDefaultRam: (defaultRam) => set({ defaultRam }),

      closeOnLaunch: false,
      setCloseOnLaunch: (closeOnLaunch) => set({ closeOnLaunch }),

      p2pEnabled: false,
      setP2pEnabled: (p2pEnabled) => set({ p2pEnabled }),

      brightness: 100,
      setBrightness: (brightness) => set({ brightness }),

      instanceSyncMode: 'db_wins',
      setInstanceSyncMode: (instanceSyncMode) => set({ instanceSyncMode }),

      avoidBetaDependencies: true,
      setAvoidBetaDependencies: (avoidBetaDependencies) => set({ avoidBetaDependencies }),

      syncGameSettings: false,
      setSyncGameSettings: (syncGameSettings) => set({ syncGameSettings }),

      showConsole: true,
      setShowConsole: (showConsole) => set({ showConsole }),

      showHomeServers: false,
      setShowHomeServers: (showHomeServers) => set({ showHomeServers }),

      confirmServerLaunch: true,
      setConfirmServerLaunch: (confirmServerLaunch) => set({ confirmServerLaunch }),

      language: 'fr',
      setLanguage: (language) => set({ language }),

      // Game (multi-instance)
      runningInstances: [],
      isInstanceRunning: (id) => get().runningInstances.includes(id),
      setInstanceRunning: (id, running) =>
        set((s) => ({
          runningInstances: running
            ? s.runningInstances.includes(id) ? s.runningInstances : [...s.runningInstances, id]
            : s.runningInstances.filter((x) => x !== id),
          gameRunning: running ? true : s.runningInstances.filter((x) => x !== id).length > 0,
        })),
      gameRunning: false,
      setGameRunning: (gameRunning) => set({ gameRunning }),

      // Last session
      lastSession: null,
      setLastSession: (lastSession) => set({ lastSession }),

      // Mods épinglés
      pinnedMods: {},
      isModPinned: (instanceId, projectId) => !!get().pinnedMods[`${instanceId}:${projectId}`],
      setModPinned: (instanceId, projectId, pinned) =>
        set((s) => {
          const key = `${instanceId}:${projectId}`
          const next = { ...s.pinnedMods }
          if (pinned) next[key] = true
          else delete next[key]
          return { pinnedMods: next }
        }),

      // Durée phase lancement
      launchPhaseDurations: {},
      recordLaunchPhaseDuration: (instanceId, ms) =>
        set((s) => {
          const prev = s.launchPhaseDurations[instanceId]
          // Moyenne mobile (70% historique / 30% dernière mesure) — lisse les
          // écarts ponctuels (ex: premier démarrage JVM après reboot) sans
          // garder un historique complet.
          const next = prev ? Math.round(prev * 0.7 + ms * 0.3) : ms
          return { launchPhaseDurations: { ...s.launchPhaseDurations, [instanceId]: next } }
        }),

      // Serveurs épinglés
      favoriteServers: {},
      isServerFavorite: (instanceId, ip) => !!get().favoriteServers[instanceId]?.includes(ip),
      toggleFavoriteServer: (instanceId, ip) => {
        const current = get().favoriteServers[instanceId] ?? []
        const isFav = current.includes(ip)
        if (!isFav && current.length >= 3) return false
        const next = isFav ? current.filter((x) => x !== ip) : [...current, ip]
        set((s) => ({ favoriteServers: { ...s.favoriteServers, [instanceId]: next } }))
        return true
      },

      // Notes de patch en attente
      pendingPatchNotes: null,
      setPendingPatchNotes: (pendingPatchNotes) => set({ pendingPatchNotes }),

      // Version du système de connexion
      authSystemVersion: AUTH_SYSTEM_VERSION,
      setAuthSystemVersion: (authSystemVersion) => set({ authSystemVersion }),

      // Migration ids d'instance
      applyInstanceIdMigrations: (migrations) => {
        if (migrations.length === 0) return
        set((s) => {
          const pinnedMods = { ...s.pinnedMods }
          const launchPhaseDurations = { ...s.launchPhaseDurations }
          const favoriteServers = { ...s.favoriteServers }
          let selectedInstanceId = s.selectedInstanceId

          for (const { oldId, newId } of migrations) {
            const oldPrefix = `${oldId}:`
            for (const key of Object.keys(pinnedMods)) {
              if (key.startsWith(oldPrefix)) {
                pinnedMods[`${newId}:${key.slice(oldPrefix.length)}`] = pinnedMods[key]
                delete pinnedMods[key]
              }
            }
            if (oldId in launchPhaseDurations) {
              launchPhaseDurations[newId] = launchPhaseDurations[oldId]
              delete launchPhaseDurations[oldId]
            }
            if (oldId in favoriteServers) {
              favoriteServers[newId] = favoriteServers[oldId]
              delete favoriteServers[oldId]
            }
            if (selectedInstanceId === oldId) selectedInstanceId = newId
          }

          return { pinnedMods, launchPhaseDurations, favoriteServers, selectedInstanceId }
        })
      },
    }),
    {
      name: 'yuyuframe-store',
      partialize: (s) => ({
        yuyuToken: s.yuyuToken,
        yuyuUsername: s.yuyuUsername,
        yuyuPlan: s.yuyuPlan,
        yuyuPlanExpiresAt: s.yuyuPlanExpiresAt,
        selectedInstanceId: s.selectedInstanceId,
        defaultRam: s.defaultRam,
        closeOnLaunch: s.closeOnLaunch,
        p2pEnabled: s.p2pEnabled,
        brightness: s.brightness,
        instanceSyncMode: s.instanceSyncMode,
        avoidBetaDependencies: s.avoidBetaDependencies,
        syncGameSettings: s.syncGameSettings,
        showConsole: s.showConsole,
        showHomeServers: s.showHomeServers,
        confirmServerLaunch: s.confirmServerLaunch,
        language: s.language,
        username: s.username,
        uuid: s.uuid,
        isOffline: s.isOffline,
        lastSession: s.lastSession,
        pinnedMods: s.pinnedMods,
        launchPhaseDurations: s.launchPhaseDurations,
        favoriteServers: s.favoriteServers,
        pendingPatchNotes: s.pendingPatchNotes,
        authSystemVersion: s.authSystemVersion,
      }),
    }
  )
)
