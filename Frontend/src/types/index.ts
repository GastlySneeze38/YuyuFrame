export interface Version {
  id: string
  version_type: 'release' | 'snapshot'
  url: string
}

export interface AuthStatus {
  authenticated: boolean
  username: string | null
  uuid: string | null
}

export interface DeviceAuthResponse {
  user_code: string
  verification_uri: string
  expires_in: number
}

export interface PollResponse {
  status: 'pending' | 'success' | 'error'
  username: string | null
  error: string | null
}

export interface ProgressResponse {
  downloading: boolean
  current: number
  total: number
  message: string
  percent: number
}

export interface Mod {
  name: string
  size: number
  enabled: boolean
  sha1: string
}

/** Les deux familles de packs d'une instance.
 *  Mêmes chaînes que les `project_type` de Modrinth : la valeur sert autant à
 *  chercher qu'à ranger le fichier (voir `commands/instance/packs.rs`). */
export type PackKind = 'resourcepack' | 'shader'

export interface PackInfo {
  name: string
  size: number
  kind: PackKind
  /** Empreinte du fichier — relie un pack à son projet Modrinth, comme pour les mods. */
  sha1: string
}

/** État du modèle de réglages partagé entre instances. */
export interface SharedOptionsStatus {
  exists: boolean
  /** Horodatage de l'enregistrement, en secondes. `null` si absent. */
  saved_at: number | null
  option_count: number
}

/** Une ligne de `options.txt`. La valeur reste une chaîne brute : le fichier
 *  n'a pas de types, c'est l'éditeur qui interprète selon la clé. */
export interface McOption {
  key: string
  value: string
}

export type Loader = 'vanilla' | 'fabric' | 'forge' | 'neoforge' | 'quilt'

export interface DetectedSource {
  kind: 'multimc_prism' | 'curseforge' | 'atlauncher' | 'modrinth_app' | 'unknown'
  name: string | null
  mcVersion: string | null
  loader: string | null
}

export interface ScanResult {
  modsDir: string
  sourceRoot: string
  source: DetectedSource
  mods: Mod[]
  extraDirs: string[]
}

export interface DetectedInstance {
  path: string
  source: DetectedSource
}

export interface DetectedLauncher {
  kind: string
  displayName: string
  instances: DetectedInstance[]
}

export interface ImportResult {
  instanceId: string
  imported: Mod[]
  skipped: string[]
  extraCopied: number
  extraSkipped: number
}

export interface ImportProgressEvent {
  phase: 'mods' | 'extras'
  current: number
  total: number
  label?: string
}

export type JvmVendor = 'auto' | 'temurin' | 'openj9' | 'graal' | 'custom'

/** Comment les drapeaux tapés dans l'écran "Configuration JVM" se combinent
 * avec ceux générés par le launcher — voir `merge_jvm_args` côté Rust.
 * "append" : ils s'ajoutent et écrasent leurs homologues. "replace" : seule
 * la base obligatoire (heap + library path) est gardée. */
export type JvmArgsMode = 'append' | 'replace'

export interface Instance {
  id: string
  name: string
  mc_version: string
  loader: Loader
  ram_mb: number
  favorite: boolean
  description: string
  jvm_vendor: JvmVendor
  jvm_custom_path: string | null
  /** "auto" (défaut) ou une policy explicite — le jeu de valeurs valides dépend de `jvm_vendor`. */
  gc_policy: string
  /** Drapeaux JVM tapés à la main dans l'écran "Configuration JVM" (texte brut). */
  jvm_extra_args: string
  jvm_args_mode: JvmArgsMode
  /** Config JVM reliée (`JvmProfile.id`). Quand elle est là, elle remplace
   * intégralement les trois champs ci-dessus au lancement. */
  jvm_profile_id: string | null
  /** Icône choisie par l'utilisateur, en data URI. Vide = icône par défaut
   *  (voir `components/instances/InstanceIcon.tsx`). */
  icon: string
  /** Version du loader épinglée. Vide = la plus récente compatible, le
   *  comportement par défaut du launcher. */
  loader_version: string
}

/**
 * Un point de contrôle de l'installation — voir `commands/instance/repair.rs`.
 *
 * `status` vaut `ok`, `broken` (présent mais altéré), `missing`, ou `unknown`
 * quand la vérification elle-même n'a pas pu se faire (hors ligne, version de
 * loader résolue seulement au lancement). `unknown` n'est pas une erreur : il
 * dit qu'on ne sait pas, ce qui n'est pas la même chose que « tout va bien ».
 */
export interface HealthCheck {
  id: 'game' | 'libraries' | 'assets' | 'loader'
  status: 'ok' | 'broken' | 'missing' | 'unknown'
  broken: number
  total: number
  detail: string | null
}

/**
 * Une version de loader proposée au choix.
 *
 * `recommended` est la seule réponse utile à « laquelle prendre » : c'est
 * celle que le loader lui-même désigne, et celle que le launcher installerait
 * sans épinglage. `stable` distingue une version publiée d'une pré-version —
 * chez Quilt et NeoForge, les pré-versions sont la majorité de la liste.
 */
export interface LoaderVersion {
  version: string
  stable: boolean
  recommended: boolean
}

/** Les trois sujets indépendants d'une config JVM. Ils sont édités séparément
 * parce qu'on change de ramasse-miettes sans toucher au compilateur — mélangés
 * dans une seule liste, impossible de dire quelle moitié d'un test a bougé. */
export type JvmFlagCategory = 'jvm' | 'gc' | 'jit'

/** Une configuration JVM réutilisable, reliable à plusieurs instances. */
export interface JvmProfile {
  id: string
  name: string
  /** `null` = garder la RAM choisie sur l'instance. */
  ram_mb: number | null
  jvm_vendor: JvmVendor
  jvm_custom_path: string | null
  gc_policy: string
  args_mode: JvmArgsMode
  args_jvm: string
  args_gc: string
  args_jit: string
  /** Jeu de drapeaux dont chaque catégorie est issue, en JSON
   * (`{"gc":"gc-brucethemoose"}`). Sert à rappeler d'où part la config et à
   * montrer les écarts introduits depuis — jamais interprété au lancement. */
  base_presets: string
}

/** Le bloc JVM d'une instance tel qu'édité dans l'UI. Regroupé en un objet
 * plutôt qu'en paramètres positionnels : `instance_create`/`instance_update`
 * en portaient déjà trois à la suite, et cinq `undefined` alignés dans un
 * appel finissent toujours par se décaler. */
export interface JvmFormValues {
  vendor: JvmVendor
  /** Chemin vers java.exe — vide/absent = résolution automatique. */
  customPath?: string
  gcPolicy: string
  extraArgs: string
  argsMode: JvmArgsMode
}

export interface JvmConfigPreview {
  java_path: string
  java_major: number
  jvm_args: string[]
}

export interface ModpackMeta {
  project_id: string
  version_id: string
  name: string
  author: string
  summary: string
  icon_url: string | null
  version_number: string
  downloads: number
  date_modified: string | null
  categories: string[]
  mod_files: string[]
}

/// Résultat d'un import de pack local (`modpack_install_from_path`) — la structure
/// du zip est détectée automatiquement côté backend (Modrinth/CurseForge reconnus
/// vs pack "générique" sans métadonnées exploitables, voir modpack.rs).
export type ModpackImportResult =
  | { kind: 'structured'; meta: ModpackMeta }
  | { kind: 'generic'; imported: number; failed: number }

/// Aperçu d'un pack avant install (ModpackDetailModal) — version MC, loader,
/// et noms des mods référencés. Résolu depuis le fichier du pack lui-même
/// (`modpack_fetch_index`/`modpack_fetch_curseforge_index`), pas depuis les
/// métadonnées de recherche (qui ne contiennent ni l'un ni l'autre).
export interface ModpackIndexInfo {
  mc_version: string | null
  loader: string
  mods: string[]
}

export interface Account {
  username: string
  uuid: string
  is_offline: boolean
}

/** Un mod synchronisé par référence : le serveur n'en stocke pas le contenu,
 *  seulement de quoi le retélécharger. */
export interface ReferencedMod {
  file: string
  project_id: string
  size: number
  enabled: boolean
}

/** Un fichier du manifeste : son chemin, sa taille, ses morceaux. */
export interface SyncFile {
  path: string
  size: number
  chunks: string[]
}

export interface SyncManifest {
  revision: number
  files: SyncFile[]
}

/** Un fichier qui diffère entre le PC et le serveur. */
export interface SyncDiffEntry {
  path: string
  kind: 'added' | 'modified' | 'removed'
  size: number
}

/** Ce qui changerait si on envoyait maintenant. */
export interface SyncDiff {
  revision: number
  entries: SyncDiffEntry[]
  unchanged: number
  /** Octets réellement à téléverser — pas la taille des fichiers modifiés,
   *  mais celle des morceaux que le serveur n'a pas encore. */
  upload_bytes: number
  local_files: number
  local_bytes: number
}

/** Forme de référence : `SyncInstance` de `LauncherAPI/src/routes/sync.rs`. */
export interface SyncInstance {
  id: number
  instance_name: string
  mc_version: string
  loader: string
  ram_mb: number
  /** Toujours 0 depuis que les mondes relèvent du backup et non de la sync. */
  save_count: number
  save_names: string[]
  /** Augmente à chaque envoi validé : c'est elle qui détecte un conflit. */
  revision: number
  total_bytes: number
  file_count: number
  /** ISO 8601. */
  updated_at: string
}

export interface SaveInfo {
  name: string
  updated_at: number
  size_bytes: number
}

export interface SyncProgress {
  phase: 'resolving_mods' | 'compressing' | 'uploading' | 'downloading' | 'installing_mods' | 'done'
  percent: number
  label: string
}

export interface ModInstallProgress {
  filename: string
  downloaded: number
  total: number
}

export interface ModpackInstallProgress {
  current: number
  total: number
  label: string
}

// Les statistiques de jeu vivent dans `types/stats.ts` depuis la refonte du
// 2026-09-21 : elles ont assez de formes (périodes, filtres, répartitions,
// sessions en cours) pour mériter leur propre fichier.
export type {
  DayStat,
  InstanceStat,
  InstanceRef,
  SessionEntry,
  StatBucket,
  StatsData,
  StatsQuery,
  StatsTotals,
} from './stats'

export interface SystemMemoryInfo {
  total_mb: number
  available_mb: number
  suggested_mb: number
}
