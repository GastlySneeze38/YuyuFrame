import { invoke } from '@tauri-apps/api/core'
import type { SupportCategory, TicketDetail, TicketSummary } from '@/types/support'
import type { LocalCrashReport, LocalCrashSummary, RemoteCrashSummary } from '@/types/crash'
import type { BackupDetail, BackupOverview, BackupSettings, BackupSummary, InstanceBackupSettings } from '@/types/backup'
import type { AuthStatus, CompatResult, DetectedLauncher, DeviceAuthResponse, ImportResult, Instance, JvmConfigPreview, JvmFormValues, HealthCheck, JavaReport, JavaStatus, JvmProfile, LoaderVersion, McOption, OptionsSummary, Mod, ModpackImportResult, ModpackIndexInfo, ModpackMeta, PackInfo, PackKind, PollResponse, SaveInfo, OptionsLinkInfo, ScanResult, SharedOptionsStatus, ShareExport, ShareLinkStatus, ShareImport, SharePreview, ShareScan, ShareSource, StatsData, StatsQuery, ReferencedMod, SyncDiff, SyncInstance, SyncManifest, SystemMemoryInfo, Version } from '@/types'

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
  /** E-mail à confirmer avant tout le reste : saisie du code imposée. */
  email_verification_required: boolean
  /** Adresse à laquelle le code a été envoyé. */
  pending_email: string | null
  /** valid | grace | expired — bandeau quand la licence hors ligne s'épuise. */
  license_state: 'valid' | 'grace' | 'expired'
  accounts: McAccountInfo[]
}

/** État de l'e-mail du compte, tel que le serveur vient de le dire. */
export interface YuyuEmailResp {
  email: string | null
  verification_required: boolean
  pending_email: string | null
}

/** Second facteur du compte : application (ou code de secours), code par
 *  e-mail, ou aucun moyen disponible. */
export type YuyuSecondFactor = 'totp' | 'email' | 'none'

export interface YuyuMfaResp {
  second_factor: YuyuSecondFactor
  backup_codes_left: number
}

export interface YuyuTotpSetupResp {
  /** À afficher en QR code. */
  otpauth_url: string
  /** Le même secret, pour une saisie à la main. */
  secret: string
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
  /** notice : bandeau en haut du launcher. home : bannière du tableau
   *  d'accueil. Les notes de version ne passent plus par là — voir
   *  `PatchNote`. */
  placement: 'notice' | 'home'
  /** Étiquette courte avant le titre (« ÉVÉNEMENT »). */
  kicker: string | null
  title: string | null
  /** none | festive — habillage du panneau d'accueil. */
  theme: 'none' | 'festive'
  ends_at: string | null
}

/** Une note de version du site — même contenu que sur yuyuframe.eu. */
export interface PatchNote {
  id: string
  version: string
  title: string
  /** Markdown, rendu par `PatchNotesModal`. */
  body: string
  /** ISO 8601. */
  published_at: string
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

/** Ce qui empêche le client intégré de se charger — `null` quand rien ne
 *  l'empêche. Renvoyé brut par le Rust (`agent_compat.rs`) : c'est
 *  l'interface qui met des mots dessus, dans la langue de l'utilisateur. */
export type AgentBlock = 'version' | 'loader' | 'files'

export interface AgentStatus {
  available: boolean
  block: AgentBlock | null
  /** Version de Java exigée par l'agent, pour l'expliquer sans la coder en
   *  dur ici. */
  min_java: number
}

/** L'avis du compte connecté. `comment` vide = note seule, non publiée sur
 *  le site mais comptée dans sa moyenne. */
export interface MyReview {
  rating: number
  comment: string
  created_at: string
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

/** `classic` = bras de 4 px (Steve), `slim` = 3 px (Alex). */
export type SkinVariant = 'classic' | 'slim'

/**
 * `url` = hébergé ailleurs, donc repartageable. `local` = PNG importé, rangé
 * sur ce PC seulement — réservé aux comptes hors ligne, car un fichier posé sur
 * un compte Microsoft est envoyé à Mojang et redevient une URL.
 */
export type SkinKind = 'url' | 'local'

/** Skin enregistré pour un compte. */
export interface SkinRef {
  kind: SkinKind
  /** URL, ou nom du fichier importé, selon `kind`. */
  source: string
  variant: SkinVariant
  /** `player:<pseudo>`, `url`, `file` ou `mojang` — d'où il vient. */
  origin: string
}

/** Skin déjà porté par un compte. Voir `account/skins/history.rs`. */
export interface SkinHistoryEntry {
  id: number
  kind: SkinKind
  source: string
  variant: SkinVariant
  origin: string
  first_seen_at: number
  last_used_at: number
  /** `null` si l'aperçu est indisponible (hébergeur éteint, fichier disparu). */
  data_uri: string | null
}

/**
 * Entrée du catalogue Ely.by — voir `account/skins/catalog.rs`.
 *
 * `url` est une adresse publique et `variant` notre modèle : une entrée du
 * catalogue est donc déjà une référence applicable, sans conversion. L'aperçu
 * n'y figure pas, il se demande à part pour les seules cases affichées.
 */
export interface CatalogSkin {
  id: number
  url: string
  variant: SkinVariant
  /** Couleur dominante annoncée par Ely.by, souvent absente. */
  color: string | null
  tags: string[]
  wearers: number
  likes: number
  views: number
}

export interface CatalogPage {
  items: CatalogSkin[]
  page: number
  last_page: number
}

/**
 * Format Ely.by. Leurs quatre options sont exclusives, et `new` ne contient
 * que des bras classiques — c'est lui qui sert de « classique » à l'interface,
 * faute d'une valeur qui couvre aussi le vieux 64×32.
 */
export type SkinFormat = 'old' | 'new' | 'slim'

/** Catégories d'Ely.by. La casse compte : `fantasy` est ignoré. */
export type SkinKindFilter =
  | 'Comics'
  | 'Adventure'
  | 'Heroes'
  | 'Evildoers'
  | 'Weekend'
  | 'Characters'
  | 'Historical'
  | 'Fantasy'
  | 'Scientific'
  | 'Other'

/** Skin trouvé chez Mojang, avec son aperçu déjà téléchargé. */
export interface ResolvedSkin {
  username: string
  uuid: string
  url: string
  variant: SkinVariant
  data_uri: string
}

/** Skin désigné et vérifié, prêt à appliquer — URL vérifiée ou fichier importé. */
export interface CheckedSkin {
  kind: SkinKind
  source: string
  variant: SkinVariant
  data_uri: string
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
    /** Les versions de loader disponibles pour ce couple, la plus récente
     *  d'abord. Vide pour `vanilla`, qui n'a pas de loader à versionner. */
    loader: (loader: string, mcVersion: string) =>
      invoke<LoaderVersion[]>('loader_versions', { loader, mcVersion }),
    /** Les loaders qui ont au moins une version pour cette version du jeu —
     *  « vanilla » toujours compris. Un loader dont on ne sait rien (réseau)
     *  y figure : mieux vaut un choix de trop qu'un choix retiré à tort. */
    loaderAvailability: (mcVersion: string) =>
      invoke<string[]>('loader_availability', { mcVersion }),
    /** Les versions du jeu utilisables avec ce loader. Vide pour « vanilla »,
     *  qui ne restreint rien. Question inverse de la précédente : elle se pose
     *  là où le loader n'est pas un choix (duplication, modpack). */
    loaderGameVersions: (loader: string) =>
      invoke<string[]>('loader_game_versions', { loader }),
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
    // `loaderVersion` absent ou vide = la plus récente compatible, exactement
    // ce que le launcher fait depuis toujours : les appelants qui ne gèrent
    // pas ce réglage n'ont rien à passer.
    update: (id: string, name: string, mc_version: string, loader: string, ram_mb: number, description?: string, jvm?: JvmFormValues, loader_version?: string) =>
      invoke<Instance>('instance_update', { id, name, mcVersion: mc_version, loader, ramMb: ram_mb, description, loaderVersion: loader_version, ...jvmInvokeArgs(jvm) }),
    duplicate: (sourceId: string, name: string, mc_version: string, ram_mb: number) =>
      invoke<Instance>('instance_duplicate', { sourceId, name, mcVersion: mc_version, ramMb: ram_mb }),
    toggleFavorite: (id: string) => invoke<Instance>('instance_toggle_favorite', { id }),
    startupSync: (mode: string) => invoke<void>('instance_startup_sync', { mode }),
    exportSettings: (instanceId: string) => invoke<void>('instance_export_settings', { instanceId }),
    applySettings: (instanceId: string) => invoke<boolean>('instance_apply_settings', { instanceId }),

    // ── Options complètes (options.txt + config/ des mods) ───────────────
    // Distinct du modèle partagé juste au-dessus, qui ne connaît qu'
    // `options.txt` : ici c'est « donne-moi ta configuration », donc une
    // archive, et les réglages des mods comptent autant que ceux du jeu.
    /** Ce qu'une exportation emporterait, sans rien écrire. */
    optionsSummary: (instanceId: string) =>
      invoke<OptionsSummary>('instance_options_summary', { instanceId }),
    /** Écrit l'archive au chemin rendu par le sélecteur d'enregistrement, et
     *  rend le nombre de fichiers emportés. */
    exportOptions: (instanceId: string, path: string) =>
      invoke<number>('instance_export_options', { instanceId, path }),
    /** Reprend des options depuis une archive ou un `options.txt` nu, et rend
     *  le nombre de fichiers rétablis. */
    importOptions: (instanceId: string, path: string) =>
      invoke<number>('instance_import_options', { instanceId, path }),
    /** État du modèle `shared_options.txt` — ce qui décide si « synchroniser
     *  les paramètres Minecraft » a quelque chose à copier. */
    sharedOptionsStatus: () => invoke<SharedOptionsStatus>('shared_options_status'),
    /** Pousse le réglage vers le Rust, qui applique le modèle à la création
     *  de chaque instance (voir App.tsx). */
    setSyncGameSettings: (enabled: boolean) =>
      invoke<void>('set_sync_game_settings', { enabled }),
    openFolder: (instanceId: string) => invoke<void>('instance_open_folder', { instanceId }),
    /** Réglages de fenêtre du jeu. Les quatre ensemble : séparés, ils ne
     *  veulent rien dire — `custom` à faux rend les autres sans effet. */
    setWindow: (instanceId: string, custom: boolean, fullscreen: boolean, width: number, height: number) =>
      invoke<Instance>('instance_set_window', { instanceId, custom, fullscreen, width, height }),
    /** Pose l'icône depuis un fichier image, ou la retire avec `null`. Les
     *  octets sont relus et rangés en base par le Rust — le chemin ne survit
     *  pas à l'appel (voir `instances/icon.rs`). */
    setIcon: (instanceId: string, path: string | null) =>
      invoke<Instance>('instance_set_icon', { instanceId, path }),
    /** Examine l'installation (jeu, bibliothèques, ressources, loader) par le
     *  SHA1 — ce que le lancement ne vérifie pas, lui qui se contente de la
     *  taille. Voir `instances/health/repair.rs`. */
    diagnose: (instanceId: string) => invoke<HealthCheck[]>('instance_diagnose', { instanceId }),
    /** Remet en état ce que le diagnostic a trouvé abîmé, et rend le nombre de
     *  fichiers retéléchargés. Ne touche jamais au dossier de l'instance. */
    repair: (instanceId: string) => invoke<number>('instance_repair', { instanceId }),

    // ── Essai de compatibilité (onglet « Compatibilité ») ─────────────────
    // Ce n'est pas un diagnostic de fichiers mais un vrai lancement : la
    // promesse n'est pas « rien n'est abîmé » mais « ça démarre ». Long par
    // nature (il faut atteindre le menu principal), et suivi en direct par
    // l'événement `compat_log`.
    /** Démarre le jeu et le coupe dès qu'il est debout. Rend le verdict et,
     *  en cas d'échec, ce que le loader a reproché. */
    compatTest: (instanceId: string) => invoke<CompatResult>('instance_compat_test', { instanceId }),
    /** Coupe l'essai en cours. Le verdict rendu sera alors `cancelled`. */
    compatCancel: (instanceId: string) => invoke<void>('instance_compat_cancel', { instanceId }),

    // ── Java de l'instance (onglet « Java et mémoire ») ──────────────────
    // La version requise vient de la version du jeu, jamais d'un réglage :
    // ces commandes la donnent, pas l'inverse.
    javaStatus: (instanceId: string) => invoke<JavaStatus>('instance_java_status', { instanceId }),
    /** Cherche un Java de la bonne version sur la machine. Rend une
     *  proposition, n'enregistre rien. */
    javaDetect: (instanceId: string) => invoke<string | null>('instance_java_detect', { instanceId }),
    /** Désigne la JVM, ou `null` pour revenir à la résolution automatique. */
    setJavaPath: (instanceId: string, path: string | null) =>
      invoke<JavaStatus>('instance_set_java_path', { instanceId, path }),
    /** Installe le runtime recommandé — même fonction que le lancement, donc
     *  rien n'est retéléchargé si tout est déjà en place. */
    installJava: (instanceId: string) => invoke<JavaStatus>('instance_install_java', { instanceId }),
    /** La version majeure que rend cet exécutable, ou `null` si ce n'en est
     *  pas un. Pour valider un chemin choisi avec « Parcourir ». */
    probeJava: (path: string) => invoke<number | null>('java_probe', { path }),
    /** Examine l'installation Java employée : présence, complétude du dossier,
     *  réponse de la JVM. Le pendant de « Réparer » pour Java — le lancement
     *  se contente de trouver un exécutable. */
    inspectJava: (instanceId: string) => invoke<JavaReport>('instance_java_inspect', { instanceId }),
    /** Installe une version de Java choisie (vendeur + version majeure) et la
     *  pose sur l'instance comme chemin personnalisé. */
    installCustomJava: (instanceId: string, major: number, vendor: string) =>
      invoke<JavaStatus>('instance_install_custom_java', { instanceId, major, vendor }),
    // P1-6 (audit launcher, Phase 6) — aperçu de la ligne de commande réelle,
    // recalculée côté Rust par les mêmes fonctions qu'un vrai lancement.
    previewJvmConfig: (instanceId: string, mcVersion: string, ramMb: number, jvm: JvmFormValues) =>
      invoke<JvmConfigPreview>('preview_jvm_config', { instanceId, mcVersion, ramMb, ...jvmInvokeArgs(jvm) }),
    setJvmProfile: (instanceId: string, profileId: string | null) =>
      invoke<void>('instance_set_jvm_profile', { instanceId, profileId }),
  },

  // Partage d'instance sans hébergement (`instances/share/`).
  share: {
    /** Tout ce qui peut se partager, et d'où chaque fichier se télécharge. */
    scan: (instanceId: string) => invoke<ShareScan>('instance_share_scan', { instanceId }),
    /** Écrit le `.mrpack` des éléments choisis (désignés par leur `path`),
     *  avec la configuration Java et les options du client si demandé. */
    exportFile: (instanceId: string, paths: string[], includeJvm: boolean, includeClient: boolean, filePath: string) =>
      invoke<ShareExport>('instance_share_export', { instanceId, paths, includeJvm, includeClient, filePath }),
    /** Lien `yuyuframe://instance/…`, en une ou plusieurs parties. */
    link: (instanceId: string, paths: string[], includeJvm: boolean, includeClient: boolean) =>
      invoke<string[]>('instance_share_link', { instanceId, paths, includeJvm, includeClient }),
    preview: (source: ShareSource) => invoke<SharePreview>('instance_share_preview', { source }),
    /** Crée l'instance et installe le pack ; progression par `share_import_progress`.
     *  `applyJvm` : reprendre la configuration Java jointe (RAM comprise). */
    import: (source: ShareSource, name: string, ramMb: number, applyJvm: boolean) =>
      invoke<ShareImport>('instance_share_import', { source, name, ramMb, applyJvm }),
  },

  // Liens de partage, toutes sortes confondues (`Backend/src/share_link/mod.rs`).
  shareLink: {
    /** Ce qu'on a collé : sorte, parties reçues, parties manquantes. */
    status: (text: string) => invoke<ShareLinkStatus>('share_link_status', { text }),
    /** Sortes dont le contenu vit dans l'interface (`settings`) : le Rust ne
     *  fait que compresser et découper ; la validation est ici
     *  (`lib/launcherSettingsShare.ts`). */
    build: (kind: 'settings', text: string) => invoke<string[]>('share_link_build', { kind, text }),
    read: (kind: 'settings', text: string) => invoke<string>('share_link_read', { kind, text }),
    /** Les mêmes pour un contenu qui n'est pas du texte (`skin` : des pixels,
     *  `lib/skinLink.ts`). */
    buildBytes: (kind: 'skin', data: Uint8Array) =>
      invoke<string[]>('share_link_build_bytes', { kind, data: Array.from(data) }),
    readBytes: async (kind: 'skin', text: string) =>
      Uint8Array.from(await invoke<number[]>('share_link_read_bytes', { kind, text })),
  },

  // Options du jeu et du client intégré (`instances/settings/options_share.rs`) :
  // le `.properties` du client en fichier, et les deux dans un lien
  // `yuyuframe://options/…`. Mots de passe des macros jamais inclus.
  optionsShare: {
    /** Écrit le `.properties` du client ; rend le nombre de réglages. */
    exportClient: (instanceId: string, path: string) =>
      invoke<number>('instance_client_options_export', { instanceId, path }),
    /** Fusionne un `.properties` dans les options du client de l'instance. */
    importClient: (instanceId: string, path: string) =>
      invoke<number>('instance_client_options_import', { instanceId, path }),
    link: (instanceId: string, game: boolean, client: boolean) =>
      invoke<string[]>('instance_options_link', { instanceId, game, client }),
    preview: (link: string) => invoke<OptionsLinkInfo>('options_link_preview', { link }),
    /** Applique un lien à une instance (fusion) ; rend ce qui a été appliqué. */
    apply: (instanceId: string, link: string, game: boolean, client: boolean) =>
      invoke<OptionsLinkInfo>('instance_options_from_link', { instanceId, link, game, client }),
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
    /** `acceptPrivacy` : la case du formulaire, exigée par le serveur. */
    register: (username: string, password: string, email: string, acceptPrivacy: boolean) =>
      invoke<YuyuSessionResp>('yuyu_register', { username, password, email, acceptPrivacy }),
    /** `login` accepte le pseudo ou l'e-mail. */
    login: (login: string, password: string) =>
      invoke<YuyuSessionResp>('yuyu_login', { login, password }),
    logout: () => invoke<void>('yuyu_logout'),
    refreshPlan: () => invoke<YuyuPlanResp>('yuyu_refresh_plan'),
    createCheckout: (plan: string) =>
      invoke<YuyuCheckoutResp>('yuyu_create_checkout', { plan }),
    /** Lève aussi le mot de passe provisoire et ferme les autres appareils. */
    changePassword: (current: string, newPassword: string, mfaCode?: string) =>
      invoke<void>('yuyu_change_password', { current, newPassword, mfaCode: mfaCode || null }),
    /** Envoie un code à la nouvelle adresse ; elle ne remplace l'ancienne
     *  qu'après `verifyEmail`. */
    setEmail: (email: string, password: string, mfaCode?: string) =>
      invoke<YuyuEmailResp>('yuyu_set_email', { email, password, mfaCode: mfaCode || null }),
    /** Seconde étape de la connexion (`mfaToken` vient de l'erreur
     *  `mfa_required` de `login`). */
    mfaVerify: (mfaToken: string, code: string) => invoke<YuyuSessionResp>('yuyu_mfa_verify', { mfaToken, code }),
    mfaResend: (mfaToken: string) => invoke<void>('yuyu_mfa_resend', { mfaToken }),
    mfaStatus: () => invoke<YuyuMfaResp>('yuyu_mfa_status'),
    /** Code de reconfirmation par e-mail, pour un compte sans application. */
    mfaStepUp: () => invoke<void>('yuyu_mfa_step_up'),
    totpSetup: (password: string, mfaCode?: string) =>
      invoke<YuyuTotpSetupResp>('yuyu_totp_setup', { password, mfaCode: mfaCode || null }),
    /** Rend les codes de secours, affichés cette seule fois. */
    totpEnable: (code: string) => invoke<string[]>('yuyu_totp_enable', { code }),
    backupCodes: (code: string) => invoke<string[]>('yuyu_backup_codes', { code }),
    verifyEmail: (code: string) => invoke<YuyuEmailResp>('yuyu_verify_email', { code }),
    resendEmailCode: () => invoke<void>('yuyu_resend_email_code'),
    emailStatus: () => invoke<YuyuEmailResp>('yuyu_email_status'),
    /** Même réponse que le compte existe ou non. */
    forgotPassword: (login: string) => invoke<void>('yuyu_forgot_password', { login }),
    resetPassword: (login: string, code: string, newPassword: string) =>
      invoke<void>('yuyu_reset_password', { login, code, newPassword }),
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
  },

  /**
   * Skins — voir `account/skins/skin.rs` : un skin est une URL déjà
   * hébergée (celle d'un compte premium chez Mojang, ou celle que
   * l'utilisateur fournit) plus le modèle à employer. Le launcher n'héberge
   * rien, donc il n'y a pas de skin « à envoyer » : seulement à désigner.
   */
  skin: {
    /** Skin d'un joueur premium, cherché par son pseudo. */
    resolvePlayer: (username: string) => invoke<ResolvedSkin>('skin_resolve_player', { username }),
    /** Skin que Mojang sert pour ce compte en ce moment. */
    ofAccount: (uuid: string) => invoke<ResolvedSkin>('skin_of_account', { uuid }),
    /** Vérifie qu'une URL mène bien à un skin, et en rend l'aperçu. */
    checkUrl: (url: string) => invoke<CheckedSkin>('skin_check_url', { url }),
    /** Range un PNG du disque. Ce qu'il devient dépend du compte — voir `apply`. */
    importFile: (sourcePath: string) => invoke<CheckedSkin>('skin_import_file', { sourcePath }),
    /** Lit un PNG du disque comme base de dessin, sans le ranger : rend son
     *  data URI validé. */
    readFile: (sourcePath: string) => invoke<string>('skin_read_file', { sourcePath }),
    /**
     * Range un PNG produit par l'éditeur. Il devient un skin importé comme un
     * autre ; le modèle vient de l'éditeur, seul à le connaître.
     */
    importBytes: (data: string, variant: SkinVariant) =>
      invoke<CheckedSkin>('skin_import_bytes', { data, variant }),
    /** Écrit le PNG à l'emplacement choisi dans le sélecteur d'enregistrement. */
    exportPng: (data: string, path: string) => invoke<void>('skin_export_png', { data, path }),
    /**
     * Compte Microsoft : posé chez Mojang — et si c'est un fichier, envoyé chez
     * eux, ce qui le transforme en skin hébergé. Hors ligne : enregistré ici.
     */
    apply: (uuid: string, kind: SkinKind, source: string, variant: SkinVariant, origin: string) =>
      invoke<SkinRef>('skin_apply', { uuid, kind, source, variant, origin }),
    /** Skins déjà portés, du plus récent au plus ancien. */
    history: (uuid: string) => invoke<SkinHistoryEntry[]>('skin_history', { uuid }),
    historyForget: (uuid: string, id: number) => invoke<void>('skin_history_forget', { uuid, id }),
    remove: (uuid: string) => invoke<void>('skin_remove', { uuid }),
    current: (uuid: string) => invoke<SkinRef | null>('skin_current', { uuid }),
    /** Data URI du skin enregistré — `null` si aucun, ou aperçu indisponible. */
    preview: (uuid: string) => invoke<string | null>('skin_preview_for_account', { uuid }),

    /**
     * Catalogue Ely.by. Les étiquettes tiennent lieu de recherche : les skins
     * n'ont pas de titre. 40 entrées par page, taille imposée par Ely.by.
     */
    catalog: (
      page: number,
      tags: string[],
      color: string | null,
      /** `old`, `new` (= bras classiques) ou `slim` ; `null` = tous. */
      format: SkinFormat | null,
      /** Catégorie Ely.by, casse comprise ; `null` = toutes. */
      kind: SkinKindFilter | null,
      /**
       * Lève les deux filtres à la fois : celui d'Ely.by et celui de
       * YuyuFrame. Un seul interrupteur, parce qu'un bouton « afficher le
       * contenu sensible » qui continuerait d'en retirer ne tiendrait sa
       * promesse qu'à moitié.
       *
       * Quand il est à `false`, une page rend moins de 40 entrées —
       * l'appelant ne doit pas supposer de taille fixe.
       */
      showSensitive: boolean,
    ) =>
      invoke<CatalogPage>('skin_catalog_browse', {
        page,
        tags,
        color,
        format,
        kind,
        showSensitive,
      }),
    /**
     * Aperçus des seules cases affichées, dans l'ordre demandé. `null` pour
     * celles dont l'image n'a pas pu être obtenue.
     */
    catalogPreviews: (urls: string[]) => invoke<(string | null)[]>('skin_catalog_previews', { urls }),
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

  /** Événements émis pendant que la fenêtre n'existait plus (fermée au
   *  lancement) — récupérés au montage et rejoués. Voir commands::pending. */
  pending: {
    take: () => invoke<{ name: string; payload: unknown }[]>('take_pending_events'),
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
    /** `useAgent` : jouer avec le client intégré ou sans lui (voir
     *  `agentStatus`). Omis = avec, comme avant l'arrivée du choix. */
    start: (instanceId: string, avoidBeta = true, showConsole = true, connectServer?: string, useAgent = true) =>
      invoke<void>('launch_game', { instanceId, avoidBeta, showConsole, connectServer, useAgent }),
    startP2p: (instanceId: string, avoidBeta = true, showConsole = true, connectServer?: string, useAgent = true) =>
      invoke<void>('launch_game', { instanceId, p2p: true, avoidBeta, showConsole, connectServer, useAgent }),
    /** Ce que le client intégré peut faire sur cette version — et ce qui
     *  l'en empêche, quand quelque chose l'en empêche. La réponse vient du
     *  Rust : la liste des versions tissables vit dans le code de l'agent,
     *  pas dans l'interface. */
    agentStatus: (mcVersion: string, loader?: string | null) =>
      invoke<AgentStatus>('launcher_agent_status', { mcVersion, loader: loader ?? null }),
    reloadAgent: () =>
      invoke<void>('reload_agent'),
    cancel: (instanceId: string) =>
      invoke<void>('cancel_launch', { instanceId }),
    /** Les instances qui tournent, d'après le Rust — la seule source qui
     *  survive à la fermeture de la fenêtre au lancement. */
    running: () => invoke<string[]>('running_instances'),
    listSavedServers: (instanceId: string) =>
      invoke<SavedServer[]>('list_saved_servers', { instanceId }),
    pingServer: (address: string) =>
      invoke<ServerPingInfo>('ping_server', { address }),
  },

  /** Avis déposés depuis le launcher, sous le compte YuyuFrame. Un seul par
   *  compte : on le remplace, on n'en empile pas. Le pseudo affiché sur le
   *  site est celui du compte, ajouté côté serveur. */
  reviews: {
    mine: () => invoke<MyReview | null>('review_mine'),
    /** `comment` vide (ou absent) = la note seule : elle compte dans la
     *  moyenne du site sans y publier de carte. */
    submit: (rating: number, comment?: string) =>
      invoke<void>('review_submit', { rating, comment: comment ?? '' }),
    remove: () => invoke<void>('review_delete'),
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

  /** Fenêtre principale et arrière-plan. */
  window: {
    /** Efface la fenêtre après un lancement. Ferme (et rend la mémoire de la
     *  webview) quand l'arrière-plan est autorisé, réduit sinon. */
    hideForLaunch: (enabled: boolean) => invoke<{ mode: 'closed' | 'minimized' | 'kept' }>('window_hide_for_launch', { enabled }),
    /** Pousse le réglage vers le Rust : la boucle d'événements de Tauri décide
     *  de s'éteindre ou non sans pouvoir lire la base. */
    setBackgroundAllowed: (allowed: boolean) => invoke<void>('window_set_background_allowed', { allowed }),
    backgroundStatus: () => invoke<{ allowed: boolean; game_running: boolean }>('window_background_status'),
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

  /** Packs de ressources et shaders de l'instance. Pas de `toggle` ici,
   *  contrairement aux mods : c'est le jeu qui active un pack (options.txt
   *  pour les ressources, configuration du mod de shaders pour les shaders),
   *  pas un renommage de fichier. */
  packs: {
    list: (instanceId: string, kind: PackKind) =>
      invoke<PackInfo[]>('packs_list', { instanceId, kind }),
    install: (instanceId: string, kind: PackKind, url: string, filename: string) =>
      invoke<PackInfo>('packs_install', { instanceId, kind, url, filename }),
    delete: (instanceId: string, kind: PackKind, name: string) =>
      invoke<void>('packs_delete', { instanceId, kind, name }),
    importPaths: (instanceId: string, kind: PackKind, paths: string[]) =>
      invoke<PackInfo[]>('packs_import_paths', { instanceId, kind, paths }),
  },

  /** `options.txt` de l'instance. `read` rend une liste vide tant que le jeu
   *  n'a jamais été lancé — c'est Minecraft qui crée le fichier. `write`
   *  n'applique que les clés fournies et rend le fichier relu. */
  mcOptions: {
    read: (instanceId: string) => invoke<McOption[]>('mc_options_read', { instanceId }),
    write: (instanceId: string, changes: McOption[]) =>
      invoke<McOption[]>('mc_options_write', { instanceId, changes }),
  },

  // Notes de version — la table du site (Contenu → Patch notes du
  // back-office), lue directement chez lui. `null` = aucune note publiée, ou
  // site injoignable : dans les deux cas l'interface n'affiche rien.
  patchNotes: {
    latest: () => invoke<PatchNote | null>('patch_notes_latest'),
    /** L'historique, plus récent d'abord — onglet « Notes de version ». */
    list: () => invoke<PatchNote[]>('patch_notes_list'),
  },

  // Pays d'où l'on se connecte, demandé une seule fois au premier démarrage
  // pour choisir la langue (voir `i18n/detect.ts`). `null` = question sans
  // réponse : hors ligne, ou pays que Cloudflare ne sait pas situer.
  locale: {
    detectCountry: () => invoke<string | null>('detect_country'),
  },

  // Réglages du client intégré : mêmes paires clé/valeur, mais dans le
  // `.properties` que l'agent relit à chaque démarrage (voir
  // `instances/settings/agent_options.rs`). Liste vide tant que l'agent n'a
  // jamais tourné sur cette instance — l'interface affiche alors les valeurs
  // par défaut des modules.
  agentOptions: {
    read: (instanceId: string) => invoke<McOption[]>('agent_options_read', { instanceId }),
    write: (instanceId: string, changes: McOption[]) =>
      invoke<McOption[]>('agent_options_write', { instanceId, changes }),
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
