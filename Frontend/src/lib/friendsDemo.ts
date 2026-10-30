// Des amis de démonstration, pour voir le panneau en développement quand la
// LauncherAPI ne connaît pas encore les amis (même principe que
// `lib/competitive.ts` `demoData`). Jamais en production.

import type { Friend, FriendList, MyProfile, PublicProfile, ShowcaseItem, ShowcaseView } from '@/types/friends'

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

/**
 * Un état de classement de démonstration, de la forme que lit `readStandings`
 * (`state.ladder.standings`, une ligne par mode : 1 masse, 2 épée). Division
 * 0 = Bois 1 … 18 = Champion ; 10 duels au moins = placement terminé.
 */
function demoLadder(mace: number, sword: number): unknown {
  return {
    ladder: {
      standings: [
        { mode: 1, division: mace, points: 240, duels: 64 },
        { mode: 2, division: sword, points: 120, duels: 31 },
      ],
    },
  }
}

const DEMO_TITLE: ShowcaseView = { kind: 'title', code: 'choc', label: 'Champion du Choc' }

/** Le profil d'un ami de démonstration, vitrine comprise. */
export function demoProfile(id: string): PublicProfile {
  const n = Number(id.slice(-2))
  const looks: PublicProfile[] = [
    {
      banner: 'nether',
      accent: '#ff6b8b',
      bio: 'Main masse depuis la 1.21.\nToujours partant pour un duel.',
      showcase: [{ kind: 'rank', mode: 'mace', state: demoLadder(17, 12) }, { kind: 'rank', mode: 'sword', state: demoLadder(17, 12) }, DEMO_TITLE],
    },
    {
      banner: 'aurora',
      accent: '#5ad1ff',
      bio: 'Speedrun, et parfois du PvP.',
      showcase: [{ kind: 'rank', mode: 'sword', state: demoLadder(6, 13) }],
    },
    {
      banner: 'forest',
      accent: '#3ba55d',
      bio: 'Je construis des trucs énormes.',
      showcase: [{ kind: 'rank', mode: 'mace', state: demoLadder(4, 2) }, { kind: 'title', code: 'builder', label: 'Bâtisseur de la saison 1' }],
    },
    { banner: null, accent: null, bio: null, showcase: [] },
  ]
  return looks[n % looks.length]
}

/** Ma vitrine de démonstration, telle que la verront mes amis : suit ce que je coche. */
export function demoMyShowcase(items: ShowcaseItem[]): ShowcaseView[] {
  const state = demoLadder(10, 7)
  return items.map((item) => (item.kind === 'rank' ? { kind: 'rank', mode: item.mode, state } : DEMO_TITLE))
}

/** Mon profil de démonstration, à modifier. */
export function demoMyProfile(): MyProfile {
  return {
    main_mc_uuid: null,
    banner: 'end',
    accent: '#7b72e9',
    bio: 'Je fais le launcher.',
    showcase: [{ kind: 'rank', mode: 'mace' }, { kind: 'title' }],
    banner_visibility: 'friends',
    bio_visibility: 'everyone',
    showcase_visibility: 'friends',
    bio_hidden: false,
    accounts: [{ uuid: 'b876ec32e396476ba1158438d83c67d4', name: 'Ghasty', main: true }],
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
