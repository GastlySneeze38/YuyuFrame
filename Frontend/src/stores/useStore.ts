import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Instance, JvmVendor, Version, Account } from '@/types'
import { AUTH_SYSTEM_VERSION } from '@/config/authVersion'

export type YuyuPlan = 'free' | 'premium' | 'ultimate'

/** Cartes proposées en haut de la page Stats. */
export const STAT_CARD_IDS = [
  'time',
  'sessions',
  'average',
  'longest',
  'streak',
  'activeDays',
  'crashRate',
  'favorite',
] as const
export type StatCardId = (typeof STAT_CARD_IDS)[number]

/** Les quatre montrées à quelqu'un qui n'a rien choisi. */
export const DEFAULT_STAT_CARDS: StatCardId[] = ['time', 'sessions', 'average', 'streak']

export type StatsSort = 'time' | 'sessions' | 'recent' | 'crashes'
export type Lang = 'fr' | 'en' | 'es' | 'de' | 'it' | 'pt' | 'pl' | 'ru'

interface Store {
  // ── Session YuyuFrame ──
  // Les jetons vivent côté Rust (base locale + mémoire) et ne passent plus
  // jamais par le frontend : depuis la refonte /v1, le jeton d'accès ne dure
  // que 15 minutes et se renouvelle tout seul. Ici on ne garde que de quoi
  // afficher. Une session fermée côté serveur est détectée au premier appel
  // et vidée automatiquement (voir showApiError/isSessionExpiredError).
  yuyuSignedIn: boolean
  yuyuUsername: string | null
  yuyuEmail: string | null
  yuyuPlan: YuyuPlan
  yuyuPlanExpiresAt: number | null
  /** Licence hors ligne : « grace » déclenche le bandeau d'information. */
  yuyuLicenseState: 'valid' | 'grace' | 'expired'
  /** Mot de passe provisoire donné par le support : changement imposé avant
   * toute autre action (le serveur refuse le reste). */
  yuyuPasswordResetRequired: boolean
  setYuyuPasswordResetRequired: (required: boolean) => void
  setYuyuSession: (session: {
    username: string
    email?: string | null
    plan: YuyuPlan
    planExpiresAt: number | null
    licenseState?: 'valid' | 'grace' | 'expired'
    passwordResetRequired?: boolean
  }) => void
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

  /** RAM personnalisée (>8 Go) — remplace le palier "8 Go" dans RamPicker
   * quand définie. `null` = pas de custom, on garde le palier standard. */
  customRamMb: number | null
  setCustomRamMb: (mb: number | null) => void

  // ── JVM par défaut (P1-6, Phase 6) — mêmes réglages que ceux exposés par
  // JvmAdvancedSection sur chaque instance, mais comme valeur de départ pour
  // les nouvelles instances (voir CreateInstanceModal), pas une valeur
  // appliquée directement au lancement (chaque instance garde son propre
  // choix, modifiable indépendamment après création). ──
  defaultJvmVendor: JvmVendor
  setDefaultJvmVendor: (v: JvmVendor) => void
  defaultJvmCustomPath: string
  setDefaultJvmCustomPath: (p: string) => void
  defaultGcPolicy: string
  setDefaultGcPolicy: (p: string) => void

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

  /** Le launcher reste en vie sans fenêtre pendant une partie, pour compter
   *  la session et construire un rapport si le jeu plante. */
  allowBackground: boolean
  setAllowBackground: (v: boolean) => void

  // ── Page Stats (persisté) ─────────────────────────────────────────────────
  /** Période affichée, en jours. `0` = tout l'historique. */
  statsRangeDays: number
  setStatsRangeDays: (d: number) => void
  /** Cartes du haut, dans l'ordre choisi. Vide = les quatre par défaut. */
  statsCards: StatCardId[]
  setStatsCards: (cards: StatCardId[]) => void
  /** Critère de classement des instances. */
  statsInstanceSort: StatsSort
  setStatsInstanceSort: (s: StatsSort) => void

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
      yuyuSignedIn: false,
      yuyuUsername: null,
      yuyuEmail: null,
      yuyuPlan: 'free',
      yuyuPlanExpiresAt: null,
      yuyuLicenseState: 'valid',
      yuyuPasswordResetRequired: false,
      setYuyuPasswordResetRequired: (required) => set({ yuyuPasswordResetRequired: required }),
      setYuyuSession: (session) =>
        set({
          yuyuSignedIn: true,
          yuyuUsername: session.username,
          yuyuEmail: session.email ?? null,
          yuyuPlan: session.plan,
          yuyuPlanExpiresAt: session.planExpiresAt,
          yuyuLicenseState: session.licenseState ?? 'valid',
          yuyuPasswordResetRequired: session.passwordResetRequired ?? false,
        }),
      setYuyuPlan: (plan, planExpiresAt) =>
        set({ yuyuPlan: plan, yuyuPlanExpiresAt: planExpiresAt }),
      // Les comptes Minecraft appartiennent au PC (voir db::mc_account côté
      // backend) : quitter YuyuFrame ne les retire pas.
      clearYuyuSession: () =>
        set({
          yuyuSignedIn: false,
          yuyuUsername: null,
          yuyuEmail: null,
          yuyuPlan: 'free',
          yuyuPlanExpiresAt: null,
          yuyuLicenseState: 'valid',
          yuyuPasswordResetRequired: false,
        }),
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

      customRamMb: null,
      setCustomRamMb: (customRamMb) => set({ customRamMb }),

      defaultJvmVendor: 'auto',
      setDefaultJvmVendor: (defaultJvmVendor) => set({ defaultJvmVendor }),
      defaultJvmCustomPath: '',
      setDefaultJvmCustomPath: (defaultJvmCustomPath) => set({ defaultJvmCustomPath }),
      defaultGcPolicy: 'auto',
      setDefaultGcPolicy: (defaultGcPolicy) => set({ defaultGcPolicy }),

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

      allowBackground: true,
      setAllowBackground: (allowBackground) => set({ allowBackground }),

      statsRangeDays: 30,
      setStatsRangeDays: (statsRangeDays) => set({ statsRangeDays }),
      statsCards: DEFAULT_STAT_CARDS,
      setStatsCards: (statsCards) => set({ statsCards }),
      statsInstanceSort: 'time',
      setStatsInstanceSort: (statsInstanceSort) => set({ statsInstanceSort }),

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
        yuyuSignedIn: s.yuyuSignedIn,
        yuyuUsername: s.yuyuUsername,
        yuyuEmail: s.yuyuEmail,
        yuyuPlan: s.yuyuPlan,
        yuyuPlanExpiresAt: s.yuyuPlanExpiresAt,
        selectedInstanceId: s.selectedInstanceId,
        defaultRam: s.defaultRam,
        customRamMb: s.customRamMb,
        defaultJvmVendor: s.defaultJvmVendor,
        defaultJvmCustomPath: s.defaultJvmCustomPath,
        defaultGcPolicy: s.defaultGcPolicy,
        closeOnLaunch: s.closeOnLaunch,
        allowBackground: s.allowBackground,
        p2pEnabled: s.p2pEnabled,
        brightness: s.brightness,
        instanceSyncMode: s.instanceSyncMode,
        avoidBetaDependencies: s.avoidBetaDependencies,
        syncGameSettings: s.syncGameSettings,
        showConsole: s.showConsole,
        showHomeServers: s.showHomeServers,
        confirmServerLaunch: s.confirmServerLaunch,
        language: s.language,
        statsRangeDays: s.statsRangeDays,
        statsCards: s.statsCards,
        statsInstanceSort: s.statsInstanceSort,
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
