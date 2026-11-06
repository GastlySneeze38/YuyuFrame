import { useStore } from '@/stores/useStore'
import { useFriends } from '@/stores/useFriends'
import { skinOf } from '@/lib/friends'
import { playerSkinSrc, preloadHeads } from '@/lib/heads'
import { background } from '@/lib/netQueue'
import { cachedCompetitive, fetchMyCompetitive } from '@/lib/competitiveLoad'
import type { CompetitiveData } from '@/lib/competitive'

/**
 * Les têtes de toute l'interface, préparées dès le lancement (2026-11-06).
 *
 * Ouvrir un écran à avatars (amis, classement, historique des duels, comptes)
 * montrait des carrés gris le temps que chaque tête soit lue chez Mojang. Ici
 * on rassemble au démarrage tout ce que ces écrans afficheront et on le
 * confie à la file paresseuse de `lib/heads.ts` (petits lots, navigateur au
 * repos, rien pendant le démarrage ni pendant une partie). Les têtes restent
 * en mémoire pour la session.
 *
 * Sources : les comptes Minecraft du PC, la liste d'amis (et chaque fois
 * qu'elle change), et l'onglet Compétitif — ses dernières données gardées
 * tout de suite, puis une lecture fraîche en arrière-plan (une requête,
 * `/ranked/overview`) si elles ont plus de `COMPETITIVE_STALE_MS` : l'onglet
 * s'ouvre alors à jour, têtes comprises.
 */

const COMPETITIVE_STALE_MS = 10 * 60_000
/** Adversaires de l'historique pris en compte : la première page de chaque mode. */
const OPPONENTS = 60

let started = false

function competitiveHeads(data: CompetitiveData): string[] {
  const rows = [...Object.values(data.leaderboard ?? {}).flat(), ...Object.values(data.friendsLeaderboard ?? {}).flat()]
  return [
    ...rows.map((r) => playerSkinSrc(r.uuid, r.name)),
    ...(data.duels ?? []).slice(0, OPPONENTS).map((d) => playerSkinSrc(d.opponentUuid, d.opponent)),
  ]
}

function preloadAccounts() {
  const { accounts, uuid } = useStore.getState()
  const ids = accounts.map((a) => a.uuid)
  if (uuid) ids.push(uuid)
  preloadHeads(ids.map((id) => `https://mc-heads.net/skin/${id}`))
}

function preloadFriends() {
  const list = useFriends.getState().list
  if (!list) return
  preloadHeads([...list.friends, ...list.incoming, ...list.outgoing].map(skinOf))
}

function preloadCompetitive() {
  const { uuid, yuyuSignedIn, isOffline } = useStore.getState()
  if (!uuid || !yuyuSignedIn) return
  const cached = cachedCompetitive(uuid)
  if (cached) preloadHeads(competitiveHeads(cached.value))
  if (isOffline || (cached && Date.now() - cached.at < COMPETITIVE_STALE_MS)) return
  background(async () => {
    try {
      preloadHeads(competitiveHeads(await fetchMyCompetitive(uuid)))
    } catch {
      // Classement absent, compte non prouvé, réseau coupé : l'écran le dira à l'ouverture.
    }
  })
}

/** À appeler une fois au démarrage de l'interface. */
export function startHeadPreload(): void {
  if (started) return
  started = true
  preloadAccounts()
  preloadFriends()
  preloadCompetitive()
  useStore.subscribe((s, prev) => {
    if (s.accounts !== prev.accounts || s.uuid !== prev.uuid) preloadAccounts()
    // Compte actif changé, ou session YuyuFrame ouverte après le démarrage.
    if (s.uuid !== prev.uuid || (s.yuyuSignedIn && !prev.yuyuSignedIn)) preloadCompetitive()
  })
  useFriends.subscribe((s, prev) => {
    if (s.list !== prev.list) preloadFriends()
  })
}
