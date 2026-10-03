/**
 * Statistiques de jeu — forme de référence : `Backend/src/play/stats/mod.rs`.
 *
 * Tout est **local** : ces données n'ont jamais quitté le PC et ne sont plus
 * rattachées au compte YuyuFrame. Se déconnecter ne fait plus disparaître
 * l'historique, ce qui était le cas avant la refonte.
 */

export interface DayStat {
  /** Date locale, `AAAA-MM-JJ`. */
  date: string
  secs: number
  sessions: number
}

export interface InstanceStat {
  instance_id: string
  instance_name: string
  mc_version: string
  loader: string
  sessions: number
  total_secs: number
  /** Sessions terminées par un plantage. */
  crashed: number
  last_played_at: number
  avg_secs: number
}

/** Répartition par loader ou par version — même forme pour les deux. */
export interface StatBucket {
  key: string
  secs: number
  sessions: number
}

export interface SessionEntry {
  id: number
  instance_id: string
  instance_name: string
  mc_version: string
  loader: string
  started_at: number
  duration_secs: number
  crashed: boolean
  /** Rapport de plantage local, s'il a pu être construit. */
  crash_report_id: string | null
  /** La partie tourne encore : la durée grandit. */
  running: boolean
  /** Fin déduite après coup, pas observée — la durée est un minimum. */
  recovered: boolean
}

export interface StatsTotals {
  secs: number
  sessions: number
  crashed: number
  avg_secs: number
  longest_secs: number
  active_days: number
  current_streak: number
  longest_streak: number
  best_day: DayStat | null
}

export interface InstanceRef {
  id: string
  name: string
}

export interface StatsData {
  from: number
  to: number
  totals: StatsTotals
  /** Un point par jour de la période, jours sans jeu compris. */
  daily: DayStat[]
  /** Temps joué par heure de la journée, 0 h → 23 h (24 entrées). */
  hourly: number[]
  per_instance: InstanceStat[]
  per_loader: StatBucket[]
  per_version: StatBucket[]
  recent: SessionEntry[]
  /** Ce qui tourne en ce moment. */
  running: SessionEntry[]
  /** Tirées de tout l'historique : filtrer ne doit pas retirer une option. */
  known_instances: InstanceRef[]
  known_loaders: string[]
  known_versions: string[]
  first_session_at: number | null
}

export interface StatsQuery {
  /** Tout l'historique, à partir de la première partie connue — que seul le
   *  Rust sait dater. Prime sur `from`. */
  all?: boolean
  from?: number
  to?: number
  instanceId?: string
  loader?: string
  mcVersion?: string
}
