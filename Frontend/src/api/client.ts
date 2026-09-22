import { invoke } from '@tauri-apps/api/core'
import type { SupportCategory, TicketDetail, TicketSummary } from '@/types/support'
import type { LocalCrashReport, LocalCrashSummary, RemoteCrashSummary } from '@/types/crash'
import type { BackupDetail, BackupOverview, BackupSettings, BackupSummary, InstanceBackupSettings } from '@/types/backup'
import type { AuthStatus, DetectedLauncher, DeviceAuthResponse, ImportResult, Instance, JvmConfigPreview, JvmFormValues, JvmProfile, Mod, ModpackImportResult, ModpackIndexInfo, ModpackMeta, PollResponse, SaveInfo, ScanResult, StatsData, StatsQuery, ReferencedMod, SyncDiff, SyncInstance, SyncManifest, SystemMemoryInfo, Version } from '@/types'

// ── Types ────────────────────────────────────────────────────────────────────

export interface YuyuStatusResp {
  has_account: boolean
}

/** Session ouverte : les jetons restent côté Rust, jamais exposés ici. */
export interface YuyuSessionResp {
  username: string
  email: string | null
  plan: string
  plan_expires_at: number | null
  /** Mot de passe provisoire du support : changement imposé. */
  password_reset_required: boolean
  /** valid | grace | expired — bandeau quand la licence hors ligne s'épuise. */
  license_state: 'valid' | 'grace' | 'expired'
  accounts: McAccountInfo[]
}

// ── Pilotage depuis le back-office (GET /v1/config) ──────────────────────────

export interface FleetFlag {
  key: string
  enabled: boolean
  message: string | null
}

export interface FleetAnnouncement {
  id: string
  /** Ligne de détail ; seul texte d'une annonce de bandeau. */
  message: string
  level: 'info' | 'warning' | 'critical'
  /** notice : bandeau en haut du launcher. home : bannière du tableau d'accueil. */
  placement: 'notice' | 'home'
  /** Étiquette courte avant le titre (« ÉVÉNEMENT »). */
  kicker: string | null
  title: string | null
  /** none | festive — habillage du panneau d'accueil. */
  theme: 'none' | 'festive'
  ends_at: string | null
}

export interface FleetConfig {
  min_launcher_version: string | null
  min_version_message: string | null
  /** Ce launcher est sous la version minimale imposée. */
  update_required: boolean
  /** Cette version est interdite : motif affiché, jeu non lançable. */
  launcher_blocked: string | null
  blocked_agent_versions: { version: string; reason: string }[]
  flags: FleetFlag[]
  announcements: FleetAnnouncement[]
}

/** Un appareil connecté au compte (écran « Ma sécurité »). */
export interface YuyuDevice {
  id: string
  device_name: string | null
  os: string | null
  launcher_version: string | null
  last_used_at: string | null
  current: boolean
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

/** Une incompatibilité constatée entre deux mods installés. */
export interface ModConflict {
  /** Fichier du mod qui déclare la contrainte. */
  declared_by: string
  /** Fichier du mod visé. */
  target: string
  target_version: string
  /** breaks = incompatibilité déclarée, depends = version hors plage. */
  kind: 'breaks' | 'depends'
  /** La contrainte telle qu'écrite par le mod. */
  expected: string
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

/** Aplatit le bloc JVM vers les paramètres attendus par les commandes Tauri.
 * Un chemin vide devient `undefined` plutôt que `""` : côté Rust c'est un
 * `Option<String>` dont le `None` signifie "résous la JVM toi-même", alors
 * qu'une chaîne vide serait traitée comme un chemin (et échouerait). */
function jvmInvokeArgs(jvm?: JvmFormValues) {
  if (!jvm) return {}
  return {
    jvmVendor: jvm.vendor,
    jvmCustomPath: jvm.customPath?.trim() || undefined,
    gcPolicy: jvm.gcPolicy,
    jvmExtraArgs: jvm.extraArgs,
    jvmArgsMode: jvm.argsMode,
  }
}

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
    create: (name: string, mc_version: string, loader: string, ram_mb: number, description?: string, jvm?: JvmFormValues) =>
      invoke<Instance>('instance_create', { name, mcVersion: mc_version, loader, ramMb: ram_mb, description, ...jvmInvokeArgs(jvm) }),
    delete: (id: string) => invoke<void>('instance_delete', { id }),
    update: (id: string, name: string, mc_version: string, loader: string, ram_mb: number, description?: string, jvm?: JvmFormValues) =>
      invoke<Instance>('instance_update', { id, name, mcVersion: mc_version, loader, ramMb: ram_mb, description, ...jvmInvokeArgs(jvm) }),
    duplicate: (sourceId: string, name: string, mc_version: string, ram_mb: number) =>
      invoke<Instance>('instance_duplicate', { sourceId, name, mcVersion: mc_version, ramMb: ram_mb }),
    toggleFavorite: (id: string) => invoke<Instance>('instance_toggle_favorite', { id }),
    startupSync: (mode: string) => invoke<void>('instance_startup_sync', { mode }),
    exportSettings: (instanceId: string) => invoke<void>('instance_export_settings', { instanceId }),
    applySettings: (instanceId: string) => invoke<boolean>('instance_apply_settings', { instanceId }),
    openFolder: (instanceId: string) => invoke<void>('instance_open_folder', { instanceId }),
    // P1-6 (audit launcher, Phase 6) — aperçu de la ligne de commande réelle,
    // recalculée côté Rust par les mêmes fonctions qu'un vrai lancement.
    previewJvmConfig: (instanceId: string, mcVersion: string, ramMb: number, jvm: JvmFormValues) =>
      invoke<JvmConfigPreview>('preview_jvm_config', { instanceId, mcVersion, ramMb, ...jvmInvokeArgs(jvm) }),
    setJvmProfile: (instanceId: string, profileId: string | null) =>
      invoke<void>('instance_set_jvm_profile', { instanceId, profileId }),
  },

  // Configurations JVM réutilisables (écran /jvm) — reliables à plusieurs
  // instances, c'est tout leur intérêt pour comparer deux jeux de drapeaux
  // sur le même monde.
  jvmProfiles: {
    list: () => invoke<JvmProfile[]>('jvm_profile_list'),
    /** `fromId` duplique une config existante au lieu de partir de zéro. */
    create: (name: string, fromId?: string) =>
      invoke<JvmProfile>('jvm_profile_create', { name, fromId }),
    save: (profile: JvmProfile) => invoke<JvmProfile>('jvm_profile_save', { profile }),
    delete: (id: string) => invoke<void>('jvm_profile_delete', { id }),
  },

  yuyu: {
    status: () => invoke<YuyuStatusResp>('yuyu_status'),
    ping: () => invoke<boolean>('yuyu_ping'),
    register: (username: string, password: string, email?: string) =>
      invoke<YuyuSessionResp>('yuyu_register', { username, password, email: email || null }),
    /** `login` accepte le pseudo ou l'e-mail. */
    login: (login: string, password: string) =>
      invoke<YuyuSessionResp>('yuyu_login', { login, password }),
    logout: () => invoke<void>('yuyu_logout'),
    refreshPlan: () => invoke<YuyuPlanResp>('yuyu_refresh_plan'),
    createCheckout: (plan: string) =>
      invoke<YuyuCheckoutResp>('yuyu_create_checkout', { plan }),
    /** Lève aussi le mot de passe provisoire et ferme les autres appareils. */
    changePassword: (current: string, newPassword: string) =>
      invoke<void>('yuyu_change_password', { current, newPassword }),
    setEmail: (email: string) => invoke<void>('yuyu_set_email', { email }),
    listDevices: () => invoke<YuyuDevice[]>('yuyu_list_devices'),
    revokeDevice: (id: string) => invoke<void>('yuyu_revoke_device', { id }),
    /** Après ajout/retrait d'un compte Minecraft (sert au support). */
    syncMinecraftAccounts: () => invoke<void>('yuyu_sync_minecraft_accounts'),
  },

  /** Réglages poussés par le back-office (voir stores/useFleet.ts). */
  fleet: {
    /** Dernière configuration connue, sans appel réseau. */
    config: () => invoke<FleetConfig>('fleet_config'),
    /** Force une relecture depuis le serveur. */
    refresh: () => invoke<FleetConfig>('fleet_refresh'),
  },

  support: {
    categories: () => invoke<SupportCategory[]>('support_categories'),
    list: () => invoke<TicketSummary[]>('support_list'),
    /** Ouvre la conversation, et marque au passage les réponses comme lues. */
    get: (id: string) => invoke<TicketDetail>('support_get', { id }),
    create: (input: { category: string; subject: string; message: string; diagnostic?: string | null }) =>
      invoke<TicketDetail>('support_create', input),
    reply: (id: string, message: string) => invoke<TicketDetail>('support_reply', { id, message }),
    /** Retire un ticket clos de la liste ; l'équipe garde la conversation. */
    hide: (id: string) => invoke<void>('support_hide', { id }),
    /** Rapport montré à la personne avant l'envoi — jamais joint sans accord. */
    diagnostic: (instanceId?: string | null) =>
      invoke<string>('support_diagnostic', { instanceId: instanceId ?? null }),
  },

  /** Rapports de plantage. Rien ne part sans un clic : `send` est le seul
   *  appel qui sorte du poste. */
  crashes: {
    /** Les rapports gardés sur ce PC, envoyés ou non. */
    list: () => invoke<LocalCrashSummary[]>('crash_list'),
    get: (id: string) => invoke<LocalCrashReport>('crash_get', { id }),
    /** Efface le fichier local. Ce qui est déjà envoyé reste chez l'équipe. */
    delete: (id: string) => invoke<void>('crash_delete', { id }),
    send: (id: string) => invoke<RemoteCrashSummary>('crash_send', { id }),
    /** Les rapports envoyés par ce compte, avec le statut de l'équipe. */
    remote: () => invoke<RemoteCrashSummary[]>('crash_remote_list'),
    /** Retire un rapport envoyé de sa liste (et le fichier local avec). */
    hide: (id: string, localId?: string | null) =>
      invoke<void>('crash_remote_hide', { id, localId: localId ?? null }),
    /** Le rapport mis en forme, à coller ailleurs (Discord, auteur d'un mod). */
    asText: (id: string) => invoke<string>('crash_as_text', { id }),
  },

  plan: {
    /** Le Rust dit si la session peut ouvrir un écran payant. Rejette avec le
     *  code `plan_required` et `extra.required_plan` (voir PlanGate). */
    guard: (feature: string) => invoke<void>('plan_guard', { feature }),
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
    /** Renvoie le compte actif après suppression (`null` s'il n'en reste aucun). */
    delete: (uuid: string) => invoke<McAccountInfo | null>('mc_delete', { uuid }),
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
    /** Mods, configurations, packs de ressources et shaders. Jamais les
     *  mondes : ils relèvent du backup, qui empile des versions datées au
     *  lieu de prétendre fusionner deux parties jouées en parallèle. */
    push: (instanceId: string) => invoke<SyncInstance>('sync_push_instance', { instanceId }),
    pull: (syncId: number, instanceId: string) =>
      invoke<void>('sync_pull_instance', { syncId, instanceId }),
    delete: (syncId: number) =>
      invoke<void>('sync_delete_instance', { syncId }),
    /** Le contenu exact stocké sur le serveur — ce que la sync gardait
     *  jusqu'ici invisible. */
    manifest: (syncId: number) => invoke<SyncManifest>('sync_manifest', { syncId }),
    /** Ce qui changerait si on envoyait maintenant, sans rien envoyer. */
    diff: (syncId: number, instanceId: string) => invoke<SyncDiff>('sync_diff', { syncId, instanceId }),
    /** Les mods référencés : stockés comme une liste, pas comme des jars. */
    referencedMods: (syncId: number) => invoke<ReferencedMod[]>('sync_referenced_mods', { syncId }),
  },

  /** Sauvegardes d'instance (voir `types/backup.ts`). Locales par défaut ;
   *  les morceaux sont partagés entre sauvegardes, donc dix versions d'un
   *  monde coûtent à peine plus qu'une. */
  backup: {
    /** `instanceId` absent : toutes les instances. */
    list: (instanceId?: string | null) => invoke<BackupOverview>('backup_list', { instanceId: instanceId ?? null }),
    get: (instanceId: string, backupId: string) => invoke<BackupDetail>('backup_get', { instanceId, backupId }),
    create: (instanceId: string) => invoke<BackupSummary>('backup_create', { instanceId }),
    /** `replace` vide d'abord les dossiers couverts — le seul vrai retour en
     *  arrière. Sans lui, on ajoute et on écrase sans rien retirer. */
    restore: (instanceId: string, backupId: string, replace = false) =>
      invoke<number>('backup_restore', { instanceId, backupId, options: { replace } }),
    delete: (instanceId: string, backupId: string) => invoke<void>('backup_delete', { instanceId, backupId }),
    /** Ménage du dépôt de morceaux ; rend les octets libérés. */
    collectGarbage: () => invoke<number>('backup_collect_garbage'),
    settings: (instanceId?: string | null) =>
      invoke<InstanceBackupSettings>('backup_settings_get', { instanceId: instanceId ?? null }),
    saveSettings: (settings: BackupSettings, instanceId?: string | null) =>
      invoke<void>('backup_settings_save', { instanceId: instanceId ?? null, settings }),
    /** Rend une instance au réglage général. */
    resetSettings: (instanceId: string) => invoke<void>('backup_settings_reset', { instanceId }),
  },

  /** Statistiques de jeu, entièrement locales (voir `types/stats.ts`). */
  stats: {
    /** `query` absent : les 30 derniers jours, sans filtre. */
    get: (query?: StatsQuery) => invoke<StatsData>('stats_get', { query: query ?? null }),
    /** Efface tout l'historique de jeu de ce PC. Irréversible. */
    clear: () => invoke<number>('stats_clear'),
  },

  system: {
    memoryInfo: (loader?: string, modCount?: number) => invoke<SystemMemoryInfo>('system_memory_info', { loader, modCount }),
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
    installCurseforge: (instanceId: string, url: string, filename: string) =>
      invoke<Mod>('curseforge_mod_install', { instanceId, url, filename }),

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

    /** Incompatibilités déjà présentes entre les mods installés, dans les
     *  deux sens (`depends` hors plage et `breaks` déclarés). */
    checkConflicts: (instanceId: string, mcVersion: string, loader: string) =>
      invoke<ModConflict[]>('mods_check_conflicts', { instanceId, mcVersion, loader }),

    searchAdvanced: (input: ModrinthAdvancedSearchInput) =>
      invoke<ModrinthSearchResponse>('mods_search_advanced', { input }),
  },

  // Passe par le proxy LauncherAPI (voir Server/LauncherAPI/src/routes/curseforge.rs)
  // — la clé CurseForge n'est jamais côté client. Réponses en JSON brut (pas
  // de type strict) : la forme exacte des payloads CurseForge sera affinée
  // une fois le premier écran de recherche câblé dessus.
  curseforge: {
    search: (query: string, opts?: {
      gameVersion?: string; classId?: string; pageSize?: number; index?: number
      categoryId?: string; sortField?: string; sortOrder?: string; modLoaderType?: string
    }) =>
      invoke<unknown>('curseforge_search', {
        query,
        gameVersion: opts?.gameVersion,
        classId: opts?.classId,
        pageSize: opts?.pageSize,
        index: opts?.index,
        categoryId: opts?.categoryId,
        sortField: opts?.sortField,
        sortOrder: opts?.sortOrder,
        modLoaderType: opts?.modLoaderType,
      }),
    modDetails: (modId: number) => invoke<unknown>('curseforge_mod_details', { modId }),
    modFiles: (modId: number, gameVersion?: string) =>
      invoke<unknown>('curseforge_mod_files', { modId, gameVersion }),
    categories: () => invoke<unknown>('curseforge_categories'),
    localFingerprints: (instanceId: string) =>
      invoke<Array<{ name: string; fingerprint: number }>>('curseforge_local_fingerprints', { instanceId }),
    fingerprintMatches: (fingerprints: number[]) =>
      invoke<unknown>('curseforge_fingerprint_matches', { fingerprints }),
  },

  modpacks: {
    fetchIndex: (fileUrl: string) =>
      invoke<ModpackIndexInfo>('modpack_fetch_index', { fileUrl }),
    fetchCurseforgeIndex: (fileUrl: string) =>
      invoke<ModpackIndexInfo>('modpack_fetch_curseforge_index', { fileUrl }),
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
    installCurseforge: (input: {
      instanceId: string
      fileUrl: string
      modId: number
      fileId: number
      name: string
      author: string
      summary: string
      iconUrl: string | null
      versionNumber: string
      downloads: number
      dateModified: string | null
      categories: string[]
    }) => invoke<ModpackMeta>('modpack_install_curseforge', { input }),
    installFromPath: (instanceId: string, filePath: string) =>
      invoke<ModpackImportResult>('modpack_install_from_path', { instanceId, filePath }),
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
