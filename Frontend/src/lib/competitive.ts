import bois from '@/assets/ranks/bois.png'
import pierre from '@/assets/ranks/pierre.png'
import fer from '@/assets/ranks/fer.png'
import or from '@/assets/ranks/or.png'
import diamant from '@/assets/ranks/diamant.png'
import netherite from '@/assets/ranks/netherite.png'
import champion from '@/assets/ranks/champion.png'
import { withoutDefaultPort } from '@/lib/serverAddress'

/**
 * Le classement compétitif, côté launcher (onglet Compétitif).
 *
 * Les règles sont celles de docs/scoring/classement.md, calculées par le jeu
 * et vérifiées par LauncherAPI (`content-core/src/combat/ranked.rs`). Ici on
 * ne fait que LIRE : les constantes sont une copie de celles de `ranked.rs`
 * et de `PvpRank.java`, à faire bouger ensemble.
 *
 * Ce que le serveur rend (`Server/LauncherAPI/src/launcher/ranked/`) : la
 * position par mode (`/ranked/state`), l'historique des duels vérifiés
 * (`/ranked/history`), statistiques et saisons (`/ranked/summary`) et le
 * classement entre joueurs (`/ranked/leaderboard`). Défis et passe de saison
 * n'ont pas encore de route. Les données de démonstration restent derrière
 * l'interrupteur de l'atelier de développement.
 */

/** Ordre de `ranked.rs::TIERS` ; la clé sert aux traductions. */
export const TIER_KEYS = ['wood', 'stone', 'iron', 'gold', 'diamond', 'netherite', 'champion'] as const
export type TierKey = (typeof TIER_KEYS)[number]

/** La texture du bloc de chaque rang (celle du jeu, `textures/rank/`). */
export const TIER_TEXTURES: Record<TierKey, string> = {
  wood: bois,
  stone: pierre,
  iron: fer,
  gold: or,
  diamond: diamant,
  netherite,
  champion,
}

/** La couleur de chaque rang, celle de son matériau (`PvpRank.COLORS`). */
export const TIER_COLORS: Record<TierKey, string> = {
  wood: '196 142 88',
  stone: '164 168 176',
  iron: '226 230 238',
  gold: '255 206 72',
  diamond: '96 232 236',
  netherite: '172 132 176',
  champion: '244 112 228',
}

export const DIVISIONS_PER_TIER = 3
export const CHAMPION = (TIER_KEYS.length - 1) * DIVISIONS_PER_TIER
export const DIVISION_POINTS = 500
export const PLACEMENT_DUELS = 10
/** Ce que rapporte une victoire de base dans chaque rang (`BASE` de ranked.rs). */
const BASE_WIN = [25, 24, 22, 20, 19, 18, 17]

/** Codes des modes de `ranked.rs::Mode::code`. */
export type ModeKey = 'mace' | 'sword'
export const MODE_CODES: Record<number, ModeKey> = { 1: 'mace', 2: 'sword' }
export const MODES: ModeKey[] = ['mace', 'sword']

/** La position d'un mode, telle que l'interface la lit. */
export interface Standing {
  mode: ModeKey
  /** 0 (Bois 1) … CHAMPION ; sans valeur pendant le placement. */
  division: number
  /** Dans la division, 0–499 (sans plafond en Champion). */
  points: number
  /** Duels classés ; pendant le placement, ceux qui y comptent. */
  duels: number
  placed: boolean
}

export function tierOf(division: number): TierKey {
  return TIER_KEYS[Math.min(Math.floor(division / DIVISIONS_PER_TIER), TIER_KEYS.length - 1)]
}

/** 1, 2 ou 3 ; `null` en Champion, qui n'a qu'une division. */
export function divisionNumber(division: number): number | null {
  return division >= CHAMPION ? null : (division % DIVISIONS_PER_TIER) + 1
}

/** Victoires de base qu'il manque pour la division suivante — un ordre de
 *  grandeur, comme « Encore ~6 victoires » du panneau de fin de duel. */
export function winsToNext(s: Standing): number | null {
  if (!s.placed || s.division >= CHAMPION) return null
  const base = BASE_WIN[Math.min(Math.floor(s.division / DIVISIONS_PER_TIER), BASE_WIN.length - 1)]
  return Math.max(1, Math.ceil((DIVISION_POINTS - s.points) / base))
}

/** Le mode où l'on est le plus avancé : c'est lui que l'onglet montre d'abord. */
export function bestMode(standings: Standing[]): ModeKey {
  const score = (s: Standing) => (s.placed ? 1000 + s.division * DIVISION_POINTS + s.points : s.duels)
  const best = [...standings].sort((a, b) => score(b) - score(a))[0]
  return best?.mode ?? 'mace'
}

/** Une position vide : rien joué dans ce mode. */
export function emptyStanding(mode: ModeKey): Standing {
  return { mode, division: 0, points: 0, duels: 0, placed: false }
}

/**
 * Lit la réponse de `GET /v1/ranked/state/{uuid}` : `{ state: LadderState }`,
 * dont `state.ladder.standings` porte une ligne par mode joué
 * (`SavedStanding` : mode, division, points, duels…).
 */
export function readStandings(answer: unknown): Standing[] {
  const raw = (answer as { state?: { ladder?: { standings?: unknown[] } } })?.state?.ladder?.standings
  const found = new Map<ModeKey, Standing>()
  for (const row of Array.isArray(raw) ? raw : []) {
    const r = row as { mode?: number; division?: number; points?: number; duels?: number }
    const mode = MODE_CODES[r.mode ?? 0]
    if (!mode) continue
    const duels = Number(r.duels) || 0
    found.set(mode, {
      mode,
      division: Math.max(0, Math.min(CHAMPION, Number(r.division) || 0)),
      points: Number(r.points) || 0,
      duels,
      placed: duels >= PLACEMENT_DUELS,
    })
  }
  return MODES.map((m) => found.get(m) ?? emptyStanding(m))
}

// ── Ce que le serveur ne rend pas encore ─────────────────────────────────

export interface LeaderboardRow {
  position: number
  name: string
  /** Compte Minecraft : la tête se lit chez Mojang (lib/heads.ts). Absent en démo. */
  uuid?: string
  division: number
  points: number
  /** Pourcentage de victoires. */
  winRate: number
  me?: boolean
}

export interface DuelRow {
  id: string
  mode: ModeKey
  /** Heure du duel, en millisecondes. */
  at: number
  opponent: string
  /** Compte Minecraft de l'adversaire, quand le serveur l'a recoupé. */
  opponentUuid?: string | null
  result: 'win' | 'loss' | 'draw'
  /** Note du duel, sur 500. */
  score: number
  /** RR gagnés ou perdus ; `null` pour un duel de placement (points cachés). */
  rr: number | null
  placement?: boolean
  durationSecs: number
  /** Adresse du serveur de jeu où le duel s'est joué. */
  server: string
}

/** Ce qu'un serveur de jeu a donné, calculé depuis l'historique. */
export interface ServerStats {
  server: string
  duels: number
  wins: number
  losses: number
  avgScore: number
  /** RR gagnés ou perdus là-bas (hors placement). */
  rr: number
  lastPlayed: number
}

/** La couleur d'une note sur 500, du rouge (faible) au cyan (excellente). */
export function scoreColor(score: number): string {
  if (score < 150) return '239 68 68'
  if (score < 225) return '249 115 22'
  if (score < 300) return '250 204 21'
  if (score < 375) return '74 222 128'
  return '96 232 236'
}

/** Regroupe les duels par serveur, le plus joué d'abord. */
export function serverStats(duels: DuelRow[]): ServerStats[] {
  const by = new Map<string, DuelRow[]>()
  // Un duel sans serveur connu (jeu plus ancien) n'a pas de carte de serveur.
  for (const d of duels) if (d.server) by.set(d.server, [...(by.get(d.server) ?? []), d])
  return [...by.entries()]
    .map(([server, list]) => ({
      server,
      duels: list.length,
      wins: list.filter((d) => d.result === 'win').length,
      losses: list.filter((d) => d.result === 'loss').length,
      avgScore: Math.round(list.reduce((a, d) => a + d.score, 0) / list.length),
      rr: list.reduce((a, d) => a + (d.rr ?? 0), 0),
      lastPlayed: Math.max(...list.map((d) => d.at)),
    }))
    .sort((a, b) => b.duels - a.duels)
}

/** Les sortes de défis ; chacune a sa phrase dans les traductions
 *  (`competitive.challenges.kinds.<kind>`). */
export type ChallengeKind =
  | 'winDuels'
  | 'playDuels'
  | 'scoreAbove'
  | 'scoresAbove'
  | 'streak'
  | 'quickWin'
  | 'longWin'
  | 'foes'
  | 'servers'
  | 'bothModes'
  | 'rrGain'

/** Facile, moyenne, difficile ; `challenge` : le défi de la semaine. */
export type ChallengeDifficulty = 'easy' | 'medium' | 'hard' | 'challenge'

/** Une mission, tirée pour ce joueur par le serveur (LauncherAPI `launcher/missions/`). */
export interface Challenge {
  id: string
  kind: ChallengeKind
  period: 'daily' | 'weekly'
  difficulty: ChallengeDifficulty
  /** `null` : n'importe quel mode. */
  mode: ModeKey | null
  /** La valeur de la phrase : une note, des secondes, des RR ; pour une
   *  mission qui compte, le même nombre que `goal`. */
  target: number
  /** Où l'on en est, sur `goal`. */
  progress: number
  goal: number
  /** Fin de la mission, en millisecondes. */
  endsAt: number
  /** XP du passe de saison que rapporte la mission. */
  xp: number
  /** Une mission d'un jour précédent, encore ouverte. */
  carried?: boolean
  /** Peut être relancée maintenant. */
  rerollable?: boolean
}

/** Ce qui entoure les missions : relances, bonus de journée. */
export interface MissionsMeta {
  rerollsLeft: number
  /** XP du bonus « les trois missions du jour finies ». */
  dayBonusXp: number
  dayBonusDone: boolean
}

/** Le passe de saison : des paliers d'XP, chacun avec un cosmétique. Réservé
 *  aux plans payants ; les missions rapportent de l'XP à tout le monde. */
export interface BattlePass {
  tier: number
  tiers: number
  xpInTier: number
  xpPerTier: number
  /** La récompense mise en avant : celle du palier suivant. */
  featured: { kind: CosmeticKind; name: string; tier: number }
  /** XP rapportés par chaque palier des missions du jour (les cases 1 à 4). */
  checkpointXp: number
  /** Missions du jour terminées, sur `checkpoints`. */
  checkpointsDone: number
  checkpoints: number
  objectives: PassObjective[]
  /** La récompense de chaque palier, du 1er au dernier (`tiers` entrées). */
  rewards: PassReward[]
}

/** Une récompense du passe : une couleur de profil, une bannière ou le titre
 *  de la saison (LauncherAPI `migrations/0033`), dessinée par le launcher. */
export interface PassReward {
  kind: CosmeticKind
  /** Nom de la couleur ou de la bannière, texte du titre ; pas traduit. */
  name: string
  /** La bannière (`aurora`…), la couleur (`#rrggbb`) ou le code du titre. */
  code?: string | null
  /** Palier gratuit : accordé à tout le monde, pas seulement au passe. */
  free?: boolean
}

// ── Missions et passe (`GET /v1/pass`, LauncherAPI `launcher/pass.rs`) ───

export interface ApiPass {
  season: { number: number; name: string; ends_at: number | null } | null
  challenges: {
    id: number
    kind: ChallengeKind
    period: 'daily' | 'weekly'
    difficulty?: ChallengeDifficulty
    mode: ModeKey | null
    target: number
    progress: number
    goal: number
    done: boolean
    ends_at: number
    xp: number
    carried?: boolean
    rerollable?: boolean
  }[]
  /** Absents d'un serveur d'avant les missions tirées. */
  rerolls_left?: number
  day_bonus_xp?: number
  day_bonus_done?: boolean
  xp: number
  xp_per_tier: number
  tier: number
  tiers: number
  /** `free` absent d'un serveur d'avant les paliers gratuits. */
  rewards: { tier: number; kind: CosmeticKind; name: string; code: string | null; free?: boolean }[]
}

/** Les récompenses qui méritent d'être mises en avant comme objectif. */
const BIG_REWARDS: CosmeticKind[] = ['title', 'banner']

/**
 * La réponse du serveur, comme les écrans la lisent. `pass` vaut `null` sans
 * saison en cours ou sans palier réglé (« bientôt »), `challenges` sans
 * mission active.
 */
export function readPass(api: ApiPass): { challenges: Challenge[] | null; pass: BattlePass | null; season: Season | null; meta: MissionsMeta } {
  const challenges: Challenge[] = api.challenges.map((c) => ({
    id: String(c.id),
    kind: c.kind,
    period: c.period,
    difficulty: c.difficulty ?? (c.period === 'weekly' ? 'medium' : 'easy'),
    mode: c.mode,
    target: c.target,
    progress: c.progress,
    goal: c.goal,
    endsAt: c.ends_at,
    xp: c.xp,
    carried: c.carried,
    rerollable: c.rerollable,
  }))
  const meta: MissionsMeta = { rerollsLeft: api.rerolls_left ?? 0, dayBonusXp: api.day_bonus_xp ?? 0, dayBonusDone: api.day_bonus_done ?? false }
  const season: Season | null = api.season
    ? { id: String(api.season.number), number: api.season.number, name: api.season.name, start: null, end: api.season.ends_at, rewards: ['badge', 'badge', 'badge', 'badge', 'badge', 'badge', 'title'], peak: null }
    : null
  if (!api.season || api.tiers === 0) return { challenges: challenges.length ? challenges : null, pass: null, season, meta }

  // Un palier sans récompense réglée (trou dans la liste) : un insigne sans nom.
  const byTier = new Map(api.rewards.map((r) => [r.tier, r]))
  const rewards: PassReward[] = Array.from({ length: api.tiers }, (_, i) => {
    const r = byTier.get(i + 1)
    return r ? { kind: r.kind, name: r.name, code: r.code, free: r.free ?? false } : { kind: 'badge', name: '—' }
  })
  const perTier = Math.max(1, api.xp_per_tier)
  const maxed = api.tier >= api.tiers
  const featuredIndex = Math.min(api.tier, api.tiers - 1)
  // Les paliers du jour : les missions tirées aujourd'hui, pas celles reportées.
  const daily = challenges.filter((c) => c.period === 'daily' && !c.carried)
  const nextBig = rewards.findIndex((r, i) => i >= api.tier && BIG_REWARDS.includes(r.kind))
  const objectives: PassObjective[] = [{ kind: 'tiers', done: api.tier, total: api.tiers }]
  if (nextBig >= 0) {
    const xpTo = (nextBig + 1) * perTier
    objectives.unshift({
      kind: 'unlock',
      reward: rewards[nextBig].kind,
      name: rewards[nextBig].name,
      xpLeft: Math.max(0, xpTo - api.xp),
      ratio: Math.min(1, api.xp / xpTo),
      endsAt: api.season.ends_at ?? 0,
    })
  }
  return {
    challenges: challenges.length ? challenges : null,
    season,
    meta,
    pass: {
      tier: api.tier,
      tiers: api.tiers,
      xpInTier: maxed ? perTier : api.xp - api.tier * perTier,
      xpPerTier: perTier,
      featured: { kind: rewards[featuredIndex].kind, name: rewards[featuredIndex].name, tier: featuredIndex + 1 },
      checkpointXp: daily[0]?.xp ?? 0,
      checkpointsDone: daily.filter((c) => c.progress >= c.goal).length,
      checkpoints: daily.length,
      objectives,
      rewards,
    },
  }
}

export type PassObjective =
  | { kind: 'unlock'; reward: CosmeticKind; name: string; xpLeft: number; ratio: number; endsAt: number }
  | { kind: 'tiers'; done: number; total: number }
  | { kind: 'rank'; tier: TierKey; reward: CosmeticKind; ratio: number }

export interface ModeStats {
  wins: number
  losses: number
  draws: number
  avgScore: number
  bestScore: number
  bestStreak: number
  currentStreak: number
  /** RR gagnés ou perdus sur les sept derniers jours. */
  rrWeek: number
}

/** Les sortes de cosmétiques donnés en fin de saison ; chacune a son nom et
 *  son icône (`competitive.seasons.kinds.<kind>`, `CosmeticIcon`). */
/** `banner` et `accent` : une bannière ou une couleur de profil (débloquée
 *  pour de vrai dans la bibliothèque du profil). */
export type CosmeticKind = 'badge' | 'nametag' | 'cape' | 'aura' | 'title' | 'banner' | 'accent'

export interface Season {
  id: string
  number: number
  /** Le nom propre de la saison (« Impact »), pas traduit. */
  name: string
  /** Début et fin, en millisecondes ; `null` = pas de date (saison sans route). */
  start: number | null
  end: number | null
  /** Ce que rapporte chaque rang, de Bois à Champion (même ordre que `TIER_KEYS`). */
  rewards: CosmeticKind[]
  /** Le meilleur rang atteint dans cette saison (division), `null` sans placement. */
  peak: number | null
}

/** Une seule saison, sans dates, quand le serveur ne connaît pas encore les
 *  saisons : les logos de rang, qui existent déjà en jeu. */
export function fallbackSeason(standing: Standing): Season {
  return {
    id: 'current',
    number: 1,
    name: '',
    start: null,
    end: null,
    rewards: ['badge', 'badge', 'badge', 'badge', 'badge', 'badge', 'title'],
    peak: standing.placed ? standing.division : null,
  }
}

export interface CompetitiveData {
  standings: Standing[]
  /** `null` : pas de route pour l'instant. */
  leaderboard: Partial<Record<ModeKey, LeaderboardRow[]>> | null
  /** Le classement entre amis ; absent ou `null` : pas de route. */
  friendsLeaderboard?: Partial<Record<ModeKey, LeaderboardRow[]>> | null
  duels: DuelRow[] | null
  /** Le curseur de la page suivante de l'historique, par mode (`next` du
   *  serveur) ; absent ou `null` : rien de plus à charger. */
  historyNext?: Partial<Record<ModeKey, number | null>>
  stats: Partial<Record<ModeKey, ModeStats>> | null
  challenges: Challenge[] | null
  /** De la plus récente à la plus ancienne ; `null` : pas encore de route. */
  seasons: Season[] | null
  /** `null` : pas encore de route. */
  battlePass: BattlePass | null
  /** Les victoires classées de la saison en cours, par mode : pour chacune,
   *  la division qu'on avait en la gagnant. `null` : pas encore de route. */
  seasonWins: Partial<Record<ModeKey, number[]>> | null
  /** Toutes les victoires classées de la saison, quand `seasonWins` n'en
   *  porte que les meilleures (le serveur en envoie 64 au plus). */
  seasonWinCount?: Partial<Record<ModeKey, number>>
}

// ── Les réponses du serveur (`/v1/ranked/…`, `ranked/history.rs`) ────────

export interface ApiDuel {
  id: number
  mode: ModeKey
  at: number
  result: 'win' | 'loss' | 'draw' | 'unknown' | null
  score: number | null
  rr: number | null
  placement: boolean
  duration_secs: number
  ping: number | null
  opponent: string | null
  opponent_uuid: string | null
  server: string | null
  before_division: number
  after_division: number
  after_points: number
}

export interface ApiHistoryPage {
  duels: ApiDuel[]
  next: number | null
}

export interface ApiModeStats {
  wins: number
  losses: number
  draws: number
  avg_score: number | null
  best_score: number | null
  best_streak: number
  current_streak: number
  rr_week: number
}

export interface ApiSummary {
  show_rank: boolean
  modes: {
    mode: ModeKey
    standing: { division: number; points: number; duels: number; placed: boolean }
    stats: ApiModeStats
    season_wins: number[]
    season_win_count: number
  }[]
  seasons: { id: number; number: number; name: string; start: number; end: number | null; peak_mace: number | null; peak_sword: number | null }[]
  current_season: number | null
}

export interface ApiBoardRow {
  position: number | null
  uuid: string
  name: string | null
  division: number
  points: number
  win_rate: number | null
}

export interface ApiBoard {
  rows: ApiBoardRow[]
  me: ApiBoardRow | null
  total: number
}

/** Tout l'écran compétitif en une requête (`/v1/ranked/overview`). Un bloc
 *  absent = sa lecture a échoué côté serveur. */
export interface ApiOverview {
  state: unknown
  summary: ApiSummary | null
  history: { mace: ApiHistoryPage; sword: ApiHistoryPage } | null
  leaderboard: { mace: ApiBoard; sword: ApiBoard } | null
  friends_leaderboard: { mace: ApiBoard; sword: ApiBoard } | null
}

/** La page compétitive d'un ami (`/v1/friends/{id}/competitive`). */
export interface ApiFriendCompetitive {
  account: { uuid: string; name: string; main: boolean } | null
  summary: ApiSummary | null
  recent: ApiDuel[]
}

/** Un duel du serveur, comme l'interface le lit. Un résultat inconnu se lit
 *  nul, une note absente zéro : un duel classé est toujours noté. */
export function readDuel(d: ApiDuel): DuelRow {
  return {
    id: String(d.id),
    mode: d.mode,
    at: d.at,
    opponent: d.opponent ?? '?',
    opponentUuid: d.opponent_uuid,
    result: d.result === 'win' || d.result === 'loss' ? d.result : 'draw',
    score: Math.round(d.score ?? 0),
    rr: d.rr,
    placement: d.placement,
    durationSecs: Math.round(d.duration_secs),
    server: duelServer(d.server ?? ''),
  }
}

/** Une seule écriture par serveur : sans le port par défaut ni majuscules.
 *  Le jeu l'envoie tel qu'il a été tapé — `eu.minemen.club` et
 *  `eu.minemen.club:25565` faisaient deux serveurs dans les statistiques. */
export function duelServer(raw: string): string {
  return withoutDefaultPort(raw.trim().toLowerCase())
}

export function readSummaryStandings(s: ApiSummary): Standing[] {
  return MODES.map((mode) => {
    const row = s.modes.find((m) => m.mode === mode)
    return row ? { mode, ...row.standing } : emptyStanding(mode)
  })
}

export function readStats(s: ApiSummary): Partial<Record<ModeKey, ModeStats>> {
  const out: Partial<Record<ModeKey, ModeStats>> = {}
  for (const m of s.modes) {
    out[m.mode] = {
      wins: m.stats.wins,
      losses: m.stats.losses,
      draws: m.stats.draws,
      avgScore: Math.round(m.stats.avg_score ?? 0),
      bestScore: Math.round(m.stats.best_score ?? 0),
      bestStreak: m.stats.best_streak,
      currentStreak: m.stats.current_streak,
      rrWeek: m.stats.rr_week,
    }
  }
  return out
}

/** Les saisons du serveur. Un rang par saison : le meilleur des deux modes. */
export function readSeasons(s: ApiSummary): Season[] {
  const peakOf = (a: number | null, b: number | null) => (a === null ? b : b === null ? a : Math.max(a, b))
  return s.seasons.map((season) => ({
    id: String(season.id),
    number: season.number,
    name: season.name,
    start: season.start,
    end: season.end,
    rewards: ['badge', 'badge', 'badge', 'badge', 'badge', 'badge', 'title'],
    peak: peakOf(season.peak_mace, season.peak_sword),
  }))
}

/** Le classement d'un mode : les premiers ; avec `pinMe` (entre amis), ma
 *  ligne derrière eux si je n'y suis pas. Le global ne montre que le haut. */
export function readBoard(board: ApiBoard, myUuid: string | null, pinMe = false): LeaderboardRow[] {
  const same = (a: string, b: string | null) => !!b && a.replace(/-/g, '') === b.replace(/-/g, '')
  const rows: LeaderboardRow[] = board.rows.map((r) => ({
    position: r.position ?? 0,
    name: r.name ?? '?',
    uuid: r.uuid,
    division: r.division,
    points: r.points,
    winRate: r.win_rate ?? 0,
    me: same(r.uuid, myUuid) || undefined,
  }))
  if (pinMe && board.me && board.me.position !== null && !rows.some((r) => r.me)) {
    rows.push({ position: board.me.position, name: board.me.name ?? '?', uuid: board.me.uuid, division: board.me.division, points: board.me.points, winRate: board.me.win_rate ?? 0, me: true })
  }
  return rows
}

/** Cases de la grille de saison (8 × 8) : les meilleures victoires seulement. */
export const SEASON_GRID = 8
/** Victoires par niveau de bordure de la grille. */
export const WINS_PER_BORDER = 25
/** Niveaux de bordure ; au-delà, la bordure ne change plus. */
export const BORDER_LEVELS = 5

/** Le niveau de bordure atteint avec `wins` victoires (1 dès la première). */
export function borderLevel(wins: number): number {
  return Math.min(BORDER_LEVELS, Math.floor(wins / WINS_PER_BORDER) + 1)
}

// ── Démonstration (développement seulement) ──────────────────────────────

const DEMO_NAMES = [
  'Technoblade_fan', 'xMaceGod', 'Nyrox', 'Kirbo', 'SwordSaint', 'Aelyx', 'PvPotato', 'Lunah',
  'Brakken', 'Zephyrion', 'Mistral', 'OakenShield', 'Vexa', 'Torvin', 'Pixelle', 'Draxon',
  'Elowen', 'Ravek', 'Sylith', 'Korrigan',
]

/** Un générateur toujours pareil : la démo ne change pas à chaque ouverture. */
function seeded(seed: number) {
  let s = seed
  return () => {
    s = (s * 1664525 + 1013904223) % 4294967296
    return s / 4294967296
  }
}

/** Un classement entre amis inventé : six amis autour de moi. */
function demoFriendsBoard(myName: string, me: Standing): LeaderboardRow[] {
  const friends: [string, number, number, number][] = [
    ['Technoblade', 17, 210, 71],
    ['Dream', 13, 80, 64],
    ['Grian', 11, 420, 55],
    ['Mumbo', 8, 300, 49],
    ['Philza', 6, 120, 47],
    ['Sapnap', 3, 260, 41],
  ]
  const rows: LeaderboardRow[] = friends.map(([name, division, points, winRate]) => ({ position: 0, name, division, points, winRate }))
  rows.push({ position: 0, name: myName, division: me.division, points: me.points, winRate: 58, me: true })
  return rows
    .sort((a, b) => b.division * DIVISION_POINTS + b.points - (a.division * DIVISION_POINTS + a.points))
    .map((r, i) => ({ ...r, position: i + 1 }))
}

function demoLeaderboard(myName: string, me: Standing, seed: number): LeaderboardRow[] {
  const rand = seeded(seed)
  // Du Champion vers le bas, un écart irrégulier entre deux joueurs.
  let total = CHAMPION * DIVISION_POINTS + 1400
  const rows: LeaderboardRow[] = DEMO_NAMES.map((name, i) => {
    total -= Math.round(120 + rand() * 520)
    const division = Math.min(CHAMPION, Math.floor(total / DIVISION_POINTS))
    const points = division >= CHAMPION ? total - CHAMPION * DIVISION_POINTS : total % DIVISION_POINTS
    return { position: i + 1, name, division, points, winRate: Math.round(72 - i * 1.3 - rand() * 4) }
  })
  const myTotal = me.division * DIVISION_POINTS + me.points
  rows.push({ position: 1284, name: myName, division: me.division, points: me.points, winRate: 58, me: true })
  // Le joueur est inséré à sa place s'il est dans le haut, sinon il reste en bas.
  const sorted = rows.sort((a, b) => b.division * DIVISION_POINTS + b.points - (a.division * DIVISION_POINTS + a.points))
  const at = sorted.findIndex((r) => r.me)
  if (at < DEMO_NAMES.length) sorted.forEach((r, i) => (r.position = i + 1))
  else sorted[at].position = Math.max(DEMO_NAMES.length + 1, 1284 - Math.round(myTotal / 20))
  return sorted
}

/** De vrais serveurs, pour que la démo montre leur vraie icône (récupérée
 *  par un ping). Adresses directes : le ping du launcher ne suit pas les
 *  enregistrements SRV (`cocoworld.fr` → `mc.cocoworld.fr`). Avec leur poids :
 *  le premier est le plus joué. */
const DEMO_SERVERS: [string, number][] = [
  ['mc.hypixel.net', 0.45],
  ['eu.minemen.club', 0.35],
  ['mc.cocoworld.fr', 0.2],
]

function pickServer(roll: number): string {
  let acc = 0
  for (const [name, weight] of DEMO_SERVERS) {
    acc += weight
    if (roll < acc) return name
  }
  return DEMO_SERVERS[0][0]
}

function demoChallenges(): Challenge[] {
  const now = new Date()
  const endOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).getTime()
  // Le lundi suivant, à minuit.
  const endOfWeek = new Date(now.getFullYear(), now.getMonth(), now.getDate() + ((8 - now.getDay()) % 7 || 7)).getTime()
  return [
    // Une mission d'hier encore ouverte, puis les trois du jour.
    { id: 'c1', kind: 'foes', period: 'daily', difficulty: 'medium', mode: null, target: 3, progress: 1, goal: 3, endsAt: endOfDay + 86_400_000, xp: 250, carried: true, rerollable: true },
    { id: 'd1', kind: 'winDuels', period: 'daily', difficulty: 'easy', mode: 'mace', target: 3, progress: 3, goal: 3, endsAt: endOfDay + 2 * 86_400_000, xp: 150 },
    { id: 'd2', kind: 'scoreAbove', period: 'daily', difficulty: 'medium', mode: null, target: 320, progress: 0, goal: 1, endsAt: endOfDay + 2 * 86_400_000, xp: 250, rerollable: true },
    { id: 'd3', kind: 'quickWin', period: 'daily', difficulty: 'hard', mode: 'sword', target: 45, progress: 0, goal: 1, endsAt: endOfDay + 2 * 86_400_000, xp: 400, rerollable: true },
    { id: 'w1', kind: 'streak', period: 'weekly', difficulty: 'medium', mode: null, target: 4, progress: 3, goal: 4, endsAt: endOfWeek, xp: 1000 },
    { id: 'w2', kind: 'scoresAbove', period: 'weekly', difficulty: 'medium', mode: null, target: 300, progress: 5, goal: 8, endsAt: endOfWeek, xp: 1000 },
    { id: 'w3', kind: 'rrGain', period: 'weekly', difficulty: 'hard', mode: 'mace', target: 100, progress: 42, goal: 100, endsAt: endOfWeek, xp: 1000 },
    { id: 'w4', kind: 'bothModes', period: 'weekly', difficulty: 'challenge', mode: null, target: 2, progress: 1, goal: 2, endsAt: endOfWeek, xp: 2500 },
  ]
}

function demoBattlePass(): BattlePass {
  const day = 86_400_000
  return {
    tier: 33,
    tiers: 50,
    xpInTier: 2112,
    xpPerTier: 3000,
    featured: { kind: 'accent', name: 'Turquoise', tier: 34 },
    checkpointXp: 1000,
    checkpointsDone: 2,
    checkpoints: 4,
    objectives: [
      { kind: 'unlock', reward: 'title', name: 'Le Rapide', xpLeft: 51_888, ratio: 0.66, endsAt: Date.now() + 26 * day },
      { kind: 'tiers', done: 33, total: 50 },
      { kind: 'rank', tier: 'diamond', reward: 'aura', ratio: 0.68 },
    ],
    rewards: demoPassRewards(),
  }
}

/** Un passe de 10 chapitres selon la règle du back-office : par chapitre une
 *  couleur gratuite, deux couleurs et deux bannières du passe ; le titre au
 *  dernier palier. */
function demoPassRewards(): PassReward[] {
  const colors: [string, string][] = [
    ['#4fd1c5', 'Turquoise'], ['#f97316', 'Orange'], ['#22c55e', 'Émeraude'], ['#ef4444', 'Rubis'], ['#facc15', 'Citron'],
    ['#06b6d4', 'Cyan'], ['#a855f7', 'Améthyste'], ['#ec4899', 'Fuchsia'], ['#84cc16', 'Lime'], ['#0ea5e9', 'Azur'],
  ]
  const banners: [string, string][] = [['sunset', 'Couchant'], ['nether', 'Nether'], ['end', 'End'], ['night', 'Nuit'], ['candy', 'Bonbon'], ['gold', 'Or']]
  return Array.from({ length: 50 }, (_, i): PassReward => {
    if (i === 49) return { kind: 'title', name: 'Le Rapide', code: 'pass-s1' }
    const slot = i % 5
    if (slot < 3) {
      const [code, name] = colors[(i * 7) % colors.length]
      return { kind: 'accent', name, code, free: slot === 0 }
    }
    const [code, name] = banners[(Math.floor(i / 5) * 2 + slot - 3) % banners.length]
    return { kind: 'banner', name, code }
  })
}

function demoDuels(): DuelRow[] {
  const rand = seeded(7)
  const now = Date.now()
  const duels: DuelRow[] = []
  let at = now - 25 * 60_000
  for (let i = 0; i < 40; i++) {
    const mode: ModeKey = rand() < 0.62 ? 'mace' : 'sword'
    const roll = rand()
    const result: DuelRow['result'] = roll < 0.58 ? 'win' : roll < 0.97 ? 'loss' : 'draw'
    const score = Math.round(result === 'win' ? 260 + rand() * 180 : result === 'loss' ? 120 + rand() * 150 : 240)
    const rr = result === 'win' ? Math.round(14 + rand() * 18) : result === 'loss' ? -Math.round(9 + rand() * 14) : Math.round(rand() * 6 - 3)
    // Les plus anciens duels à l'épée sont encore ceux du placement.
    const placement = mode === 'sword' && i >= 34
    duels.push({
      id: `demo-${i}`,
      mode,
      at,
      opponent: DEMO_NAMES[Math.floor(rand() * DEMO_NAMES.length)],
      result,
      score,
      rr: placement ? null : rr,
      placement,
      durationSecs: Math.round(18 + rand() * 70),
      server: pickServer(rand()),
    })
    at -= Math.round((8 + rand() * 50) * 60_000 + (rand() < 0.2 ? 20 * 3_600_000 : 0))
  }
  return duels
}

function statsOf(duels: DuelRow[], mode: ModeKey): ModeStats {
  const mine = duels.filter((d) => d.mode === mode)
  let best = 0
  let run = 0
  for (const d of [...mine].reverse()) {
    run = d.result === 'win' ? run + 1 : 0
    best = Math.max(best, run)
  }
  let current = 0
  for (const d of mine) {
    if (d.result !== 'win') break
    current++
  }
  const weekAgo = Date.now() - 7 * 86_400_000
  return {
    wins: mine.filter((d) => d.result === 'win').length,
    losses: mine.filter((d) => d.result === 'loss').length,
    draws: mine.filter((d) => d.result === 'draw').length,
    avgScore: mine.length ? Math.round(mine.reduce((a, d) => a + d.score, 0) / mine.length) : 0,
    bestScore: Math.max(0, ...mine.map((d) => d.score)),
    bestStreak: best,
    currentStreak: current,
    rrWeek: mine.filter((d) => d.at > weekAgo).reduce((a, d) => a + (d.rr ?? 0), 0),
  }
}

/** Tout l'onglet, inventé : rang à la masse, placement en cours à l'épée. */
export function demoData(myName: string): CompetitiveData {
  const standings: Standing[] = [
    { mode: 'mace', division: 10, points: 342, duels: 187, placed: true },
    { mode: 'sword', division: 0, points: 0, duels: 6, placed: false },
  ]
  const duels = demoDuels()
  return {
    standings,
    // Le global ne montre que les 15 premiers : on n'y est pas.
    leaderboard: {
      mace: demoLeaderboard(myName, standings[0], 11).filter((r) => !r.me),
      sword: demoLeaderboard(myName, standings[1], 23).filter((r) => !r.me),
    },
    // Entre amis : quelques amis et moi, à ma place.
    friendsLeaderboard: { mace: demoFriendsBoard(myName, standings[0]), sword: [] },
    duels,
    stats: { mace: statsOf(duels, 'mace'), sword: statsOf(duels, 'sword') },
    challenges: demoChallenges(),
    seasons: demoSeasons(standings[0]),
    battlePass: demoBattlePass(),
    // 58 victoires à la masse, de Fer 1 à Or 2 ; aucune à l'épée (placement en cours).
    seasonWins: {
      mace: [
        ...Array<number>(9).fill(6),
        ...Array<number>(10).fill(7),
        ...Array<number>(13).fill(8),
        ...Array<number>(14).fill(9),
        ...Array<number>(12).fill(10),
      ],
      sword: [],
    },
  }
}

/** Deux saisons inventées : celle en cours (le rang de la démo y est le
 *  meilleur atteint) et une saison terminée, finie en Fer 3. */
function demoSeasons(current: Standing): Season[] {
  const day = 86_400_000
  const now = Date.now()
  return [
    {
      id: 's1',
      number: 1,
      name: 'Impact',
      start: now - 46 * day,
      end: now + 38 * day,
      rewards: ['badge', 'badge', 'nametag', 'cape', 'aura', 'aura', 'title'],
      peak: current.placed ? current.division : null,
    },
    {
      id: 's0',
      number: 0,
      name: 'Genesis',
      start: now - 136 * day,
      end: now - 46 * day,
      rewards: ['badge', 'nametag', 'nametag', 'cape', 'cape', 'aura', 'title'],
      peak: 8,
    },
  ]
}
