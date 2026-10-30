import bois from '@/assets/ranks/bois.png'
import pierre from '@/assets/ranks/pierre.png'
import fer from '@/assets/ranks/fer.png'
import or from '@/assets/ranks/or.png'
import diamant from '@/assets/ranks/diamant.png'
import netherite from '@/assets/ranks/netherite.png'
import champion from '@/assets/ranks/champion.png'

/**
 * Le classement compétitif, côté launcher (onglet Compétitif).
 *
 * Les règles sont celles de docs/scoring/classement.md, calculées par le jeu
 * et vérifiées par LauncherAPI (`content-core/src/combat/ranked.rs`). Ici on
 * ne fait que LIRE : les constantes sont une copie de celles de `ranked.rs`
 * et de `PvpRank.java`, à faire bouger ensemble.
 *
 * Ce que le serveur sait rendre aujourd'hui : la position du joueur par mode
 * (`GET /v1/ranked/state/{uuid}`). Le classement entre joueurs, l'historique
 * des duels et leurs statistiques n'ont pas encore de route : en
 * développement, l'onglet les montre avec des données de démonstration.
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
  for (const d of duels) by.set(d.server, [...(by.get(d.server) ?? []), d])
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
export type ChallengeKind = 'winDuels' | 'scoreAbove' | 'streak' | 'servers' | 'beatHigher' | 'playDuels'

export interface Challenge {
  id: string
  kind: ChallengeKind
  period: 'daily' | 'weekly'
  /** `null` : n'importe quel mode. */
  mode: ModeKey | null
  /** La valeur à atteindre (nombre de duels, note…). */
  target: number
  /** Où l'on en est, sur `goal`. */
  progress: number
  goal: number
  /** Fin du défi, en millisecondes. */
  endsAt: number
  /** XP du passe de saison que rapporte la mission. */
  xp: number
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
export type CosmeticKind = 'badge' | 'nametag' | 'cape' | 'aura' | 'title'

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
  duels: DuelRow[] | null
  stats: Partial<Record<ModeKey, ModeStats>> | null
  challenges: Challenge[] | null
  /** De la plus récente à la plus ancienne ; `null` : pas encore de route. */
  seasons: Season[] | null
  /** `null` : pas encore de route. */
  battlePass: BattlePass | null
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
    { id: 'd1', kind: 'winDuels', period: 'daily', mode: 'mace', target: 5, progress: 3, goal: 5, endsAt: endOfDay, xp: 1000 },
    { id: 'd2', kind: 'scoreAbove', period: 'daily', mode: null, target: 350, progress: 1, goal: 1, endsAt: endOfDay, xp: 1000 },
    { id: 'd3', kind: 'playDuels', period: 'daily', mode: 'sword', target: 3, progress: 0, goal: 3, endsAt: endOfDay, xp: 1000 },
    { id: 'd4', kind: 'servers', period: 'daily', mode: null, target: 2, progress: 2, goal: 2, endsAt: endOfDay, xp: 1000 },
    { id: 'w1', kind: 'streak', period: 'weekly', mode: null, target: 5, progress: 3, goal: 5, endsAt: endOfWeek, xp: 4500 },
    { id: 'w2', kind: 'winDuels', period: 'weekly', mode: null, target: 25, progress: 17, goal: 25, endsAt: endOfWeek, xp: 4500 },
    { id: 'w3', kind: 'beatHigher', period: 'weekly', mode: 'mace', target: 10, progress: 4, goal: 10, endsAt: endOfWeek, xp: 6000 },
  ]
}

function demoBattlePass(): BattlePass {
  const day = 86_400_000
  return {
    tier: 33,
    tiers: 50,
    xpInTier: 2112,
    xpPerTier: 3000,
    featured: { kind: 'cape', name: 'Impact', tier: 34 },
    checkpointXp: 1000,
    checkpointsDone: 2,
    checkpoints: 4,
    objectives: [
      { kind: 'unlock', reward: 'aura', name: 'Impact', xpLeft: 165_300, ratio: 0.18, endsAt: Date.now() + 26 * day },
      { kind: 'tiers', done: 33, total: 50 },
      { kind: 'rank', tier: 'diamond', reward: 'aura', ratio: 0.68 },
    ],
  }
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
    leaderboard: {
      mace: demoLeaderboard(myName, standings[0], 11),
      // Pendant le placement on n'a pas de place : le classement s'affiche sans nous.
      sword: demoLeaderboard(myName, standings[1], 23).filter((r) => !r.me),
    },
    duels,
    stats: { mace: statsOf(duels, 'mace'), sword: statsOf(duels, 'sword') },
    challenges: demoChallenges(),
    seasons: demoSeasons(standings[0]),
    battlePass: demoBattlePass(),
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
