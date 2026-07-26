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

export interface Instance {
  id: string
  name: string
  mc_version: string
  loader: Loader
  ram_mb: number
  favorite: boolean
  description: string
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

export interface Account {
  username: string
  uuid: string
  is_offline: boolean
}

export interface SyncInstance {
  id: number
  instance_name: string
  mc_version: string
  loader: string
  ram_mb: number
  save_count: number
  save_names: string[]
  has_data: boolean
  updated_at: number
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

export interface InstanceStat {
  instance_id: string
  instance_name: string
  mc_version: string
  loader: string
  sessions: number
  total_secs: number
}

export interface RecentSession {
  instance_name: string
  mc_version: string
  loader: string
  started_at: number
  duration_secs: number
}

export interface DailyStat {
  date: string
  secs: number
}

export interface StatsData {
  total_sessions: number
  total_secs: number
  per_instance: InstanceStat[]
  recent_sessions: RecentSession[]
  daily: DailyStat[]
}

export interface SystemMemoryInfo {
  total_mb: number
  available_mb: number
  suggested_mb: number
}
