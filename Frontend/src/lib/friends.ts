// Règles d'affichage des amis, partagées par le bouton de l'accueil et le
// panneau (`components/friends/`). Les données viennent de `stores/useFriends`.

import type { Friend, FriendPresence, MyStatusKind, PresenceState } from '@/types/friends'

/** Pastille de statut — les couleurs que tout le monde reconnaît. */
export const STATUS_COLORS: Record<PresenceState | 'invisible', string> = {
  online: '#3ba55d',
  away: '#faa61a',
  dnd: '#ed4245',
  offline: '#80848e',
  invisible: '#80848e',
}

export const MY_STATUSES: MyStatusKind[] = ['online', 'away', 'dnd', 'invisible']

/** La tête affichée pour un ami : son premier compte Minecraft vérifié, sinon Steve. */
export function skinOf(friend: Pick<Friend, 'minecraft'>): string {
  const account = friend.minecraft[0]
  return `https://mc-heads.net/skin/${account ? account.uuid : 'MHF_Steve'}`
}

export function isOnline(p: FriendPresence): boolean {
  return p.state !== 'offline'
}

export function inGame(p: FriendPresence): boolean {
  return isOnline(p) && p.activity?.kind === 'game'
}

export interface FriendGroups {
  inGame: Friend[]
  online: Friend[]
  offline: Friend[]
}

/** En jeu, en ligne, hors ligne ; par nom dans chaque groupe, et les plus
 *  récemment vus d'abord parmi les hors ligne. */
export function groupFriends(friends: Friend[], filter = ''): FriendGroups {
  const needle = filter.trim().toLowerCase()
  const shown = needle ? friends.filter((f) => f.username.toLowerCase().includes(needle) || f.minecraft.some((m) => m.name.toLowerCase().includes(needle))) : friends
  const byName = (a: Friend, b: Friend) => a.username.localeCompare(b.username, undefined, { sensitivity: 'base' })
  const seen = (f: Friend) => (f.presence.last_seen ? Date.parse(f.presence.last_seen) : 0)
  return {
    inGame: shown.filter((f) => inGame(f.presence)).sort(byName),
    online: shown.filter((f) => isOnline(f.presence) && !inGame(f.presence)).sort(byName),
    offline: shown.filter((f) => !isOnline(f.presence)).sort((a, b) => seen(b) - seen(a) || byName(a, b)),
  }
}

/** Langue pour les dates : celle de l'interface (l'écriture de la table
 *  d'enchantement n'en est pas une pour `Intl`). */
export function dateLocale(language: string): string {
  if (language === 'sga') return 'fr'
  if (language === 'pt') return 'pt-BR'
  return language
}

/** « il y a 2 heures », « hier »… à partir d'une date ISO. */
export function relativeTime(iso: string, language: string, now = Date.now()): string {
  const seconds = Math.round((Date.parse(iso) - now) / 1000)
  const format = new Intl.RelativeTimeFormat(dateLocale(language), { numeric: 'auto' })
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ['second', 60],
    ['minute', 60],
    ['hour', 24],
    ['day', 30],
    ['month', 12],
    ['year', Infinity],
  ]
  let value = seconds
  for (const [unit, size] of steps) {
    if (Math.abs(value) < size) return format.format(Math.round(value), unit)
    value /= size
  }
  return format.format(Math.round(value), 'year')
}

/** Un statut personnalisé encore valable. */
export function liveCustom(p: { custom: { text: string; expires_at: string | null } | null }, now = Date.now()): string | null {
  if (!p.custom) return null
  if (p.custom.expires_at && Date.parse(p.custom.expires_at) <= now) return null
  return p.custom.text
}

/** Minutes jusqu'à minuit : la durée « aujourd'hui » d'un statut personnalisé. */
export function minutesUntilMidnight(now = new Date()): number {
  const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  return Math.max(1, Math.ceil((midnight.getTime() - now.getTime()) / 60000))
}
