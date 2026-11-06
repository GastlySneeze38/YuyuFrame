import { api } from '@/api/client'
import { errorCode } from '@/lib/apiError'
import { readCache, writeCache } from '@/lib/persistentCache'
import {
  readBoard,
  readDuel,
  readSeasons,
  readStandings,
  readStats,
  readSummaryStandings,
  type ApiOverview,
  type ApiSummary,
  type CompetitiveData,
  type DuelRow,
} from '@/lib/competitive'

/**
 * La lecture des données de l'onglet Compétitif, partagée par l'écran
 * (`pages/Competitive.tsx`) et le préchargement du démarrage
 * (`lib/headPreload.ts`) — écrite deux fois, elle divergerait.
 */

/** Clé du cache des dernières données (préfixe `user:` : effacé à la déconnexion). */
export const competitiveCacheKey = (uuid: string) => `user:competitive:${uuid}`

/** Les dernières données gardées, si elles ont moins d'une semaine. */
export function cachedCompetitive(uuid: string): { value: CompetitiveData; at: number } | null {
  return readCache<CompetitiveData>(competitiveCacheKey(uuid), 7 * 24 * 3600_000)
}

/** Les données de l'onglet à partir du résumé du serveur (le mien ou celui d'un ami). */
export function fromSummary(s: ApiSummary, duels: DuelRow[] | null, leaderboard: CompetitiveData['leaderboard']): CompetitiveData {
  return {
    standings: readSummaryStandings(s),
    leaderboard,
    duels,
    stats: readStats(s),
    challenges: null,
    seasons: readSeasons(s),
    battlePass: null,
    seasonWins: Object.fromEntries(s.modes.map((m) => [m.mode, m.season_wins])),
    seasonWinCount: Object.fromEntries(s.modes.map((m) => [m.mode, m.season_win_count])),
  }
}

/** Une API qui ne connaît pas encore `/ranked/overview` : on le retient pour
 *  la session, plutôt que de refaire un aller-retour inutile à chaque ouverture. */
let overviewMissing = false

/**
 * Tout l'écran en une requête (`/ranked/overview`, 2026-11-06). Sur une API
 * d'avant cette route, les huit appels séparés d'avant — un bloc en échec
 * reste vide sans empêcher d'afficher le rang.
 */
async function loadOverview(uuid: string): Promise<ApiOverview> {
  if (!overviewMissing) {
    try {
      return await api.ranked.overview(uuid)
    } catch (e) {
      if (errorCode(e) !== 'ranked_unavailable') throw e
      overviewMissing = true
    }
  }
  const state = await api.ranked.state(uuid)
  const [summary, mace, sword, boardMace, boardSword, friendsMace, friendsSword] = await Promise.allSettled([
    api.ranked.summary(uuid),
    api.ranked.history(uuid, 'mace'),
    api.ranked.history(uuid, 'sword'),
    api.ranked.leaderboard('mace', uuid),
    api.ranked.leaderboard('sword', uuid),
    api.ranked.friendsLeaderboard('mace', uuid),
    api.ranked.friendsLeaderboard('sword', uuid),
  ])
  const both = <T,>(a: PromiseSettledResult<T>, b: PromiseSettledResult<T>) =>
    a.status === 'fulfilled' && b.status === 'fulfilled' ? { mace: a.value, sword: b.value } : null
  return {
    state,
    summary: summary.status === 'fulfilled' ? summary.value : null,
    history: both(mace, sword),
    leaderboard: both(boardMace, boardSword),
    friends_leaderboard: both(friendsMace, friendsSword),
  }
}

/** Mes données compétitives fraîches, gardées aussi dans le cache. Lève les
 *  erreurs de l'API (`ranked_unavailable`, `ranked_unproven`…). */
export async function fetchMyCompetitive(uuid: string): Promise<CompetitiveData> {
  const all = await loadOverview(uuid)
  const duels = all.history
    ? [...all.history.mace.duels, ...all.history.sword.duels].map(readDuel).sort((a, b) => b.at - a.at)
    : null
  const leaderboard = all.leaderboard
    ? { mace: readBoard(all.leaderboard.mace, uuid), sword: readBoard(all.leaderboard.sword, uuid) }
    : null
  const friendsLeaderboard = all.friends_leaderboard
    ? { mace: readBoard(all.friends_leaderboard.mace, uuid, true), sword: readBoard(all.friends_leaderboard.sword, uuid, true) }
    : null
  const base: CompetitiveData = all.summary
    ? fromSummary(all.summary, duels, leaderboard)
    : { standings: readStandings(all.state), leaderboard, duels, stats: null, challenges: null, seasons: null, battlePass: null, seasonWins: null }
  const fresh = { ...base, friendsLeaderboard }
  writeCache(competitiveCacheKey(uuid), fresh)
  return fresh
}
