/** Le registre `horizon-mods` vu par le launcher (`horizon/content.rs`). */

export type HorizonKind = 'mod' | 'native-mod' | 'shader' | 'shaderpack' | 'resourcepack' | 'datapack' | 'modpack'

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
  | { kind: 'engine'; error: string }
  | { kind: 'injections'; error: string }
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

/** Un monde de l'instance (dossier de `saves/`), le plus récent d'abord. */
export interface HorizonWorld {
  folder: string
  modified: number
}

/** Un pack de données `.zip` posé dans un monde. */
export interface HorizonDataPack {
  world: string
  file: string
  description: string
  enabled: boolean
  project: string | null
}

/** Un modpack `.hpack` installé dans l'instance. */
export interface HorizonInstalledPack {
  id: string
  name: string
  version: string
  sha256: string
  /** `id version` de chaque mod du pack. */
  mods: string[]
}

/** Les réglages du jeu d'une instance Horizon sur téléphone (`horizon-engine-api`,
 *  `settings`). `null` = non réglé : le jeu prend son défaut. À l'écriture, seuls
 *  les champs donnés changent. */
export interface HorizonEngineSettings {
  /** 25 à 200 (%). */
  resolution_scale: number | null
  /** 10 à 260 par dizaines ; 260 = illimité. */
  max_fps: number | null
  battery_mode: boolean | null
  cutout: 'full' | 'avoid' | null
  orientation: 'landscape' | 'portrait' | 'both' | null
  rotation: 'automatic' | 'inverted_automatic' | 'fixed' | 'fixed_reversed' | null
  /** Index de `HORIZON_ASPECTS`. */
  aspect: number | null
  gyroscope: boolean | null
  /** 0,1 à 3,0. */
  gyro_sensitivity: number | null
}

/** Les formats d'écran du moteur, dans l'ordre de `settings::ASPECTS`. */
export const HORIZON_ASPECTS = ['Native', '1:1', '5:4', '4:3', '3:2', '16:10', '16:9', '18:9', '19.5:9', '20:9', '21:9'] as const

/** Les dossiers d'une instance Horizon qu'un joueur peut ouvrir (`files::Folder`). */
export type HorizonFolder =
  | 'mods'
  | 'native_mods'
  | 'resource_packs'
  | 'shader_packs'
  | 'worlds'
  | 'screenshots'
  | 'skins'
  | 'configuration'

/** Un fichier ou dossier d'une liste (`files::Item`). */
export interface HorizonFileItem {
  name: string
  dir: boolean
  size: number
  /** Millisecondes depuis 1970, 0 si inconnu. */
  modified: number
}
