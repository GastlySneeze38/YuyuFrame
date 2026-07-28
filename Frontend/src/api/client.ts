import { invoke } from '@tauri-apps/api/core'
import type { AuthStatus, DetectedLauncher, DeviceAuthResponse, ImportResult, Instance, Mod, ModpackMeta, PollResponse, SaveInfo, ScanResult, StatsData, SyncInstance, SystemMemoryInfo, Version } from '@/types'

// ── Types ────────────────────────────────────────────────────────────────────

export interface YuyuStatusResp {
  has_account: boolean
}

export interface YuyuLoginResp {
  token: string
  username: string
  plan: string
  plan_expires_at: number | null
  accounts: McAccountInfo[]
}

export interface SavedServer {
  name: string
  ip: string
}

export interface ServerPingInfo {
  motd: string
  players_online: number
  players_max: number
  version_name: string
  favicon: string | null
  latency_ms: number
}

export interface YuyuPlanResp {
  plan: string
  plan_expires_at: number | null
}

export interface YuyuCheckoutResp {
  checkout_url: string
}

export interface McAccountInfo {
  mc_username: string
  mc_uuid: string
  is_active: boolean
  is_offline: boolean
}

export interface ModrinthAdvancedSearchInput {
  query: string
  gameVersion?: string
  loader?: string
  /// Vrais tags de contenu Modrinth (technology, magic, adventure...), PAS le
  /// loader (déjà géré séparément par `loader`).
  categories?: string[]
  environment?: 'client' | 'server'
  license?: string
  openSourceOnly?: boolean
  sort?: 'relevance' | 'downloads' | 'follows' | 'newest' | 'updated'
  limit?: number
  offset?: number
  projectType?: string
}

export interface ModrinthSearchResponse {
  // Champs bruts Modrinth (project_id, slug, title, categories, client_side,
  // server_side, license, downloads, icon_url...) — pas de type strict ici,
  // voir modUtils.ts pour le mapping vers `ModrinthHit`.
  hits: Record<string, unknown>[]
  offset: number
  limit: number
  total_hits: number
}

// ── API ──────────────────────────────────────────────────────────────────────

export const api = {
  versions: {
    list: () => invoke<Version[]>('list_versions'),
  },

  instances: {
    list: () => invoke<Instance[]>('instance_list'),
    // Le backend renvoie des tuples Rust `(String, String)`, sérialisés par
    // serde comme des arrays JSON `[oldId, newId]` — mappés ici en objets
    // nommés pour rester lisible côté appelant (voir applyInstanceIdMigrations).
    getIdMigrations: async () => {
      const pairs = await invoke<[string, string][]>('instance_id_migrations')
      return pairs.map(([oldId, newId]) => ({ oldId, newId }))
    },
    create: (name: string, mc_version: string, loader: string, ram_mb: number, description?: string) =>
      invoke<Instance>('instance_create', { name, mcVersion: mc_version, loader, ramMb: ram_mb, description }),
    delete: (id: string) => invoke<void>('instance_delete', { id }),
    update: (id: string, name: string, mc_version: string, loader: string, ram_mb: number, description?: string) =>
      invoke<Instance>('instance_update', { id, name, mcVersion: mc_version, loader, ramMb: ram_mb, description }),
    duplicate: (sourceId: string, name: string, mc_version: string, ram_mb: number) =>
      invoke<Instance>('instance_duplicate', { sourceId, name, mcVersion: mc_version, ramMb: ram_mb }),
    toggleFavorite: (id: string) => invoke<Instance>('instance_toggle_favorite', { id }),
    startupSync: (mode: string) => invoke<void>('instance_startup_sync', { mode }),
    exportSettings: (instanceId: string) => invoke<void>('instance_export_settings', { instanceId }),
    applySettings: (instanceId: string) => invoke<boolean>('instance_apply_settings', { instanceId }),
    openFolder: (instanceId: string) => invoke<void>('instance_open_folder', { instanceId }),
  },

  yuyu: {
    status: () => invoke<YuyuStatusResp>('yuyu_status'),
    register: (username: string, password: string) =>
      invoke<YuyuLoginResp>('yuyu_register', { username, password }),
    login: (username: string, password: string) =>
      invoke<YuyuLoginResp>('yuyu_login', { username, password }),
    logout: () => invoke<void>('yuyu_logout'),
    refreshPlan: () => invoke<YuyuPlanResp>('yuyu_refresh_plan'),
    createCheckout: (plan: string) =>
      invoke<YuyuCheckoutResp>('yuyu_create_checkout', { plan }),
    devSimulatePayment: (plan: string) =>
      invoke<YuyuPlanResp>('yuyu_dev_simulate_payment', { plan }),
  },

  auth: {
    status: () => invoke<AuthStatus>('auth_status'),
    startDevice: () => invoke<DeviceAuthResponse>('auth_start_device'),
    poll: () => invoke<PollResponse>('auth_poll'),
    logout: () => invoke<void>('auth_logout'),
  },

  mc: {
    accounts: () => invoke<McAccountInfo[]>('mc_list_accounts'),
    switch: (uuid: string) => invoke<McAccountInfo>('mc_switch', { uuid }),
    delete: (uuid: string) => invoke<void>('mc_delete', { uuid }),
    addOffline: (username: string) => invoke<McAccountInfo>('mc_add_offline', { username }),
    setSkin: (uuid: string, sourcePath: string) => invoke<string>('set_account_skin', { uuid, sourcePath }),
    setSkinFromUrl: (uuid: string, url: string) => invoke<string>('set_account_skin_from_url', { uuid, url }),
    getSkin: (uuid: string) => invoke<string | null>('get_account_skin', { uuid }),
    removeSkin: (uuid: string) => invoke<void>('remove_account_skin', { uuid }),
  },

  deepLink: {
    // Lien yuyuframe://join?... reçu à un tout premier lancement (app pas
    // encore ouverte quand l'OS a passé l'URL en argument) — voir
    // commands::deep_link côté Rust pour pourquoi ça ne peut pas être un
    // simple event émis directement (frontend pas encore monté à ce moment).
    // Le cas "app déjà ouverte" arrive lui via l'event `deep_link_join`
    // (voir useTauriEvent dans App.tsx).
    takePending: () => invoke<string | null>('take_pending_deep_link'),
  },

  analytics: {
    // Passerelle générique pour les événements sans contrepartie backend
    // (clic, ouverture de modal, recherche...) — voir track_event côté Rust.
    // Toujours résolu (jamais rejeté) : un échec d'envoi PostHog ne doit
    // jamais faire planter l'action UI qui a déclenché le tracking.
    track: (event: string, properties?: Record<string, unknown>) =>
      invoke<void>('track_event', { event, properties }).catch(() => {}),
    isDisabled: () => invoke<boolean>('analytics_get_disabled'),
    setDisabled: (disabled: boolean) => invoke<void>('analytics_set_disabled', { disabled }),
  },

  launch: {
    start: (instanceId: string, avoidBeta = true, showConsole = true, connectServer?: string) =>
      invoke<void>('launch_game', { instanceId, avoidBeta, showConsole, connectServer }),
    startP2p: (instanceId: string, avoidBeta = true, showConsole = true, connectServer?: string) =>
      invoke<void>('launch_game', { instanceId, p2p: true, avoidBeta, showConsole, connectServer }),
    reloadAgent: () =>
      invoke<void>('reload_agent'),
    cancel: (instanceId: string) =>
      invoke<void>('cancel_launch', { instanceId }),
    listSavedServers: (instanceId: string) =>
      invoke<SavedServer[]>('list_saved_servers', { instanceId }),
    pingServer: (address: string) =>
      invoke<ServerPingInfo>('ping_server', { address }),
  },

  sync: {
    list: () => invoke<SyncInstance[]>('sync_list_instances'),
    listSaves: (instanceId: string) =>
      invoke<SaveInfo[]>('sync_list_saves', { instanceId }),
    push: (instanceId: string, saveNames: string[]) =>
      invoke<SyncInstance>('sync_push_instance', { instanceId, saveNames }),
    pull: (syncId: number, instanceId: string) =>
      invoke<void>('sync_pull_instance', { syncId, instanceId }),
    delete: (syncId: number) =>
      invoke<void>('sync_delete_instance', { syncId }),
  },

  stats: {
    get: () => invoke<StatsData>('stats_get'),
  },

  system: {
    memoryInfo: () => invoke<SystemMemoryInfo>('system_memory_info'),
    getDataRoot: () => invoke<string>('data_root_get'),
    setDataRoot: (newParent: string) => invoke<string>('data_root_set', { newParent }),
    openFolder: (path: string) => invoke<void>('open_folder', { path }),
  },

  mods: {
    list: (instanceId: string) => invoke<Mod[]>('mods_list', { instanceId }),
    toggle: (instanceId: string, name: string) =>
      invoke<Mod>('mods_toggle', { instanceId, name }),
    delete: (instanceId: string, name: string) =>
      invoke<void>('mods_delete', { instanceId, name }),
    install: (instanceId: string, url: string, filename: string) =>
      invoke<Mod>('mods_install', { instanceId, url, filename }),

    upload: async (instanceId: string, file: File): Promise<Mod> => {
      const data = Array.from(new Uint8Array(await file.arrayBuffer()))
      return invoke<Mod>('mods_upload', { instanceId, filename: file.name, data })
    },

    importPaths: (instanceId: string, paths: string[]) =>
      invoke<ImportResult>('mods_import_paths', { instanceId, paths }),

    icon: (instanceId: string, name: string) =>
      invoke<string>('mod_icon', { instanceId, name }),

    checkUpdateSafety: (
      instanceId: string,
      mcVersion: string,
      loader: string,
      candidates: Array<{ name: string; newVersion: string }>,
    ) => invoke<Array<{ name: string; safe: boolean; blockedBy: string[] }>>(
      'mods_check_update_safety', { instanceId, mcVersion, loader, candidates },
    ),

    searchAdvanced: (input: ModrinthAdvancedSearchInput) =>
      invoke<ModrinthSearchResponse>('mods_search_advanced', { input }),
  },

  modpacks: {
    fetchIndex: (fileUrl: string) =>
      invoke<{ mc_version: string | null; loader: string }>('modpack_fetch_index', { fileUrl }),
    install: (input: {
      instanceId: string
      fileUrl: string
      projectId: string
      versionId: string
      name: string
      author: string
      summary: string
      iconUrl: string | null
      versionNumber: string
      downloads: number
      dateModified: string | null
      categories: string[]
    }) => invoke<ModpackMeta>('modpack_install', { input }),
    installFromPath: (instanceId: string, filePath: string) =>
      invoke<ModpackMeta>('modpack_install_from_path', { instanceId, filePath }),
    getMeta: (instanceId: string) => invoke<ModpackMeta | null>('modpack_get_meta', { instanceId }),
    remove: (instanceId: string) => invoke<void>('modpack_remove', { instanceId }),
    renameFile: (instanceId: string, oldName: string, newName: string) =>
      invoke<ModpackMeta | null>('modpack_rename_file', { instanceId, oldName, newName }),
  },

  importSource: {
    detectLaunchers: () => invoke<DetectedLauncher[]>('import_detect_launchers'),
    scanFolder: (path: string) => invoke<ScanResult>('import_scan_folder', { path }),
    checkDuplicates: (sourceModsDir: string, targetInstanceId: string) =>
      invoke<string[]>('import_check_duplicates', { sourceModsDir, targetInstanceId }),
    apply: (input: {
      sourceModsDir: string
      sourceRoot: string
      selectedFiles: string[]
      extraDirs: string[]
      mode: 'new' | 'existing'
      targetInstanceId?: string
      newInstance?: { name: string; mcVersion: string; loader: string; ramMb: number }
    }) => invoke<ImportResult>('import_apply', input),
  },
}
