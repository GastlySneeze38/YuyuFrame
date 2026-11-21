/** Le registre `horizon-mods` vu par le launcher (`horizon/content.rs`). */

export type HorizonKind = 'mod' | 'native-mod' | 'shader' | 'shaderpack' | 'resourcepack'

export interface HorizonVersion {
  version: string
  published: string
  changelog: string
  size: number
  /** Mod natif : le build d'Horizon (commit) pour lequel il est fait. */
  made_for: string | null
}

export interface HorizonProject {
  id: string
  kind: HorizonKind
  name: string
  summary: string
  description: string
  authors: string[]
  license: string
  source: string
  icon: string | null
  downloads: number
  stars: number
  updated: string
  categories: string[]
  tags: string[]
  screenshots: string[]
  /** Le projet a une clé : chaque version est signée par son auteur. */
  signed: boolean
  /** Les versions installables sur cette instance, la plus récente d'abord. */
  versions: HorizonVersion[]
}

export interface HorizonSupport {
  mod_api: string[]
  shader_api: string[]
  commit: string | null
}

export interface HorizonRegistry {
  projects: HorizonProject[]
  support: HorizonSupport
}

export interface HorizonInstalled {
  file: string
  kind: 'mod' | 'native-mod' | 'shader'
  id: string
  name: string
  version: string
  enabled: boolean
  size: number
  icon: string | null
  project: string | null
  update: string | null
  revoked: string | null
  made_for: string | null
  changes_game: boolean
}

export interface HorizonNativeStatus {
  allowed: boolean
  toolchain: boolean
  download_bytes: number
  unsupported?: boolean
}

export type HorizonLeftOutReason =
  | { kind: 'other_build'; made_for: string; why: string }
  | { kind: 'does_not_fit'; made_for: string; items: string[] }
  | { kind: 'changes_game' }
  | { kind: 'injections' }
  | { kind: 'crates'; names: string[] }
  | { kind: 'missing_dependency'; id: string }
  | { kind: 'forbids_unsafe' }
  | { kind: 'compile'; log: string }
  | { kind: 'unreadable'; error: string }

export type HorizonLeftOut = { id: string; file: string } & HorizonLeftOutReason

export interface HorizonNativeReport {
  instance_id: string
  base: string
  mods: string[]
  left_out: HorizonLeftOut[]
  /** Build (commit) → version publiée qui est ce build. */
  builds: Record<string, string>
  not_allowed: boolean
}
