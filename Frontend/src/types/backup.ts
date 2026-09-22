/**
 * Sauvegardes d'instance — forme de référence : `Backend/src/backup/`.
 *
 * À ne pas confondre avec la synchronisation : la sync garde les mods et les
 * configurations identiques entre plusieurs PC, le backup empile des versions
 * datées des **mondes**. Deux PC sur lesquels on a joué donnent deux versions
 * d'un même monde, et aucune fusion n'a de sens — d'où deux fonctionnalités
 * et pas une.
 */

/** manual : bouton · launch : avant une partie · daily : échéance du jour. */
export type BackupTrigger = 'manual' | 'launch' | 'daily'

export interface BackupSummary {
  id: string
  instance_id: string
  instance_name: string
  /** Secondes Unix. */
  created_at: number
  trigger: BackupTrigger
  /** Dossiers couverts : « saves », « config », « mods ». */
  includes: string[]
  total_bytes: number
  file_count: number
  /** Renseigné une fois la sauvegarde poussée sur nos serveurs. */
  cloud_id: number | null
}

export interface BackupFile {
  path: string
  size: number
  chunks: string[]
}

export type BackupDetail = BackupSummary & { files: BackupFile[] }

export interface BackupSettings {
  enabled: boolean
  /** Les mondes : la raison d'être de la fonctionnalité. */
  include_worlds: boolean
  include_configs: boolean
  /** Lourd — un dossier de mods pèse souvent plus que les mondes. */
  include_mods: boolean
  on_launch: boolean
  daily: boolean
  /** Sauvegardes gardées par instance. `0` = pas de limite. */
  keep: number
  cloud: boolean
}

export interface BackupOverview {
  backups: BackupSummary[]
  /** Place réellement occupée, morceaux partagés comptés une seule fois. */
  disk_bytes: number
  settings: BackupSettings
}

export interface InstanceBackupSettings {
  settings: BackupSettings
  /** Faux : l'instance suit le réglage général. */
  custom: boolean
  global: BackupSettings
}
