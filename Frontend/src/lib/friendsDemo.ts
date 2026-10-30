// Des amis de démonstration, pour voir le panneau en développement quand la
// LauncherAPI ne connaît pas encore les amis (même principe que
// `lib/competitive.ts` `demoData`). Jamais en production.

import type { Friend, FriendList } from '@/types/friends'

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()

/** Un identifiant public de démonstration, stable d'un affichage à l'autre. */
const demoId = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`

function friend(id: number, username: string, mc: string | null, presence: Friend['presence'], shared = false): Friend {
  return {
    id: demoId(id),
    username,
    since: ago(60 * 24 * (30 + id * 11)),
    // Des UUID de vrais joueurs pour la plupart : mc-heads rend leur skin
    // (un UUID inconnu donne Steve).
    minecraft: mc ? [{ uuid: mc, name: username, main: true }] : [],
    presence,
    server_shared: shared,
  }
}

export function demoFriends(): FriendList {
  return {
    me: { id: demoId(99), code: 'Ghasty#4821', status: 'online', custom: { text: 'Tryhard masse 🔨', expires_at: null } },
    friends: [
      friend(1, 'Technoblade', 'b876ec32e396476ba1158438d83c67d4', {
        state: 'online',
        custom: null,
        activity: { kind: 'game', mc_version: '1.21.11', loader: 'fabric', duel: { ranked: true, mode: 'mace' }, server: 'eu.minemen.club:25565' },
        last_seen: null,
      }, true),
      friend(2, 'Dream', 'ec70bcaf702f4bb8b48d276fa52a780c', {
        state: 'dnd',
        custom: { text: 'Speedrun en cours', expires_at: null },
        activity: { kind: 'game', mc_version: '26.1.2', loader: 'vanilla', duel: null, server: null },
        last_seen: null,
      }),
      friend(3, 'Grian', '5f8eb73b25be4c5aa50fd27d65e30ca0', {
        state: 'online',
        custom: { text: 'Je construis un truc énorme', expires_at: null },
        activity: { kind: 'launcher' },
        last_seen: null,
      }),
      friend(4, 'Mumbo', 'c7da90d56a054217b94a7d427cbbcad8', { state: 'away', custom: null, activity: { kind: 'launcher' }, last_seen: null }),
      friend(5, 'Philza', '0d3a1b54d6f14e2cb64d4d5e8f6b0f00', { state: 'offline', custom: { text: 'En vacances 🏖️', expires_at: null }, activity: null, last_seen: ago(95) }),
      friend(6, 'Sapnap', null, { state: 'offline', custom: null, activity: null, last_seen: ago(60 * 26) }),
      friend(7, 'Tubbo', null, { state: 'offline', custom: null, activity: null, last_seen: ago(60 * 24 * 9) }),
    ],
    incoming: [{ id: 11, user: demoId(21), username: 'Ranboo', minecraft: [], created_at: ago(40) }],
    outgoing: [{ id: 12, user: demoId(22), username: 'Quackity', minecraft: [], created_at: ago(60 * 5) }],
    blocked: [],
    settings: { accept_requests: true, stats_visibility: 'friends', activity_visibility: 'friends', server_visibility: 'some' },
  }
}
