/**
 * Rapports de plantage. Deux sources, deux formes :
 *
 * - le **disque** (`minecraft::crash` côté Rust), qui garde le rapport entier
 *   tant qu'il n'a pas été envoyé — et même après ;
 * - le **serveur** (`/v1/crashes`), qui fait autorité sur le statut donné par
 *   l'équipe.
 *
 * L'onglet Support montre les deux dans une même liste : un rapport envoyé
 * depuis un autre PC n'a pas de fichier ici, un rapport jamais envoyé n'a pas
 * de statut là-bas.
 */

/** new : personne ne l'a encore regardé. */
export type CrashStatus = 'new' | 'investigating' | 'known' | 'fixed' | 'wont_fix' | 'duplicate' | 'not_a_bug'

/** exit | crash_report | oom | loader | launcher */
export type CrashKind = string

export interface LocalCrashSummary {
  id: string
  instance_id: string
  instance_name: string
  title: string
  kind: CrashKind
  signature: string
  mc_version: string
  loader: string
  /** ISO 8601. */
  occurred_at: string
  /** Renseigné une fois envoyé : identifiant du rapport côté serveur. */
  sent_id: string | null
  /** Référence courte à donner à l'équipe (« CR-4F2A »). */
  sent_public_id: string | null
  mods_count: number
}

export interface CrashModEntry {
  name: string
  enabled: boolean
  size: number | null
}

/** Le rapport entier, tel qu'il est écrit sur le disque. */
export interface LocalCrashReport extends LocalCrashSummary {
  exit_code: number | null
  launcher_version: string
  os: string
  os_version: string | null
  arch: string
  cpu: string | null
  gpu: string | null
  ram_total_mb: number | null
  java_version: string | null
  java_path: string | null
  ram_alloc_mb: number | null
  jvm_args: string[]
  mods: CrashModEntry[]
  uptime_ms: number
  stack_trace: string | null
  log_tail: string
  sent_at: string | null
}

/** Ce que le serveur renvoie : la même chose, plus la réponse de l'équipe. */
export interface RemoteCrashSummary {
  id: string
  public_id: string
  title: string
  kind: CrashKind
  mc_version: string | null
  loader: string | null
  status: CrashStatus
  /** Réponse de l'équipe, écrite pour le joueur. */
  status_note: string | null
  status_updated_at: string | null
  occurred_at: string
  created_at: string
}

/**
 * Une ligne de la liste : le rapport local, celui du serveur, ou les deux
 * quand c'est le même plantage.
 */
export interface CrashEntry {
  key: string
  local: LocalCrashSummary | null
  remote: RemoteCrashSummary | null
}
