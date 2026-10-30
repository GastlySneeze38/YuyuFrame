// Les amis, tels que la LauncherAPI les rend (`/v1/friends`,
// Server/LauncherAPI/src/launcher/friends/). Les noms de champs sont ceux du
// serveur : ne pas les renommer ici.

export type PresenceState = 'online' | 'away' | 'dnd' | 'offline'
/** Ce que je peux choisir ; `invisible` = vu hors ligne par tous. */
export type MyStatusKind = 'online' | 'away' | 'dnd' | 'invisible'
export type Visibility = 'friends' | 'nobody'
/** Qui voit mon serveur : personne (défaut), mes amis, ou ceux que je choisis. */
export type ServerVisibility = 'nobody' | 'friends' | 'some'

export interface CustomStatus {
  text: string
  /** ISO ; `null` = sans fin. */
  expires_at: string | null
}

export type Activity =
  | { kind: 'launcher' }
  | {
      kind: 'game'
      mc_version: string
      loader: string
      duel: { ranked: boolean; mode: 'mace' | 'sword' | 'other' } | null
      /** « hôte:port », seulement si l'ami me le montre. */
      server: string | null
    }

export interface FriendPresence {
  state: PresenceState
  custom: CustomStatus | null
  /** `null` hors ligne, ou si l'ami ne montre pas ce qu'il fait. */
  activity: Activity | null
  /** Hors ligne seulement (ISO). */
  last_seen: string | null
}

/** Un compte Minecraft Microsoft prouvé ; le principal représente le joueur. */
export interface MinecraftAccount {
  uuid: string
  name: string
  main: boolean
}

export interface Friend {
  /** Identifiant public (jamais l'identifiant interne du serveur). */
  id: string
  username: string
  since: string
  /** Le principal en tête. */
  minecraft: MinecraftAccount[]
  presence: FriendPresence
  /** Je lui montre mon serveur (compte quand ma visibilité vaut `some`). */
  server_shared: boolean
}

export interface FriendRequest {
  /** La demande elle-même. */
  id: number
  /** L'autre joueur (identifiant public). */
  user: string
  username: string
  minecraft: MinecraftAccount[]
  created_at: string
}

export interface FriendSettings {
  accept_requests: boolean
  stats_visibility: Visibility
  activity_visibility: Visibility
  server_visibility: ServerVisibility
}

export interface MyStatus {
  status: MyStatusKind
  custom: CustomStatus | null
}

export interface Me extends MyStatus {
  id: string
  /** Mon code ami, à donner pour qu'on m'ajoute : « Ghasty#4821 ». */
  code: string
}

export interface FriendList {
  me: Me
  friends: Friend[]
  incoming: FriendRequest[]
  outgoing: FriendRequest[]
  blocked: { id: string; username: string }[]
  settings: FriendSettings
}

export interface RequestSent {
  status: 'pending' | 'friends'
  id: string
  username: string
}

// ── Profil ────────────────────────────────────────────────────────────────

export type Visibility3 = 'everyone' | 'friends' | 'me'

export type ShowcaseItem = { kind: 'rank'; mode: 'mace' | 'sword' } | { kind: 'title' }

/** Un élément de vitrine tel qu'on le montre. `state` : l'état du classement,
 *  à lire avec `readStandings` (`lib/competitive.ts`). */
export type ShowcaseView = { kind: 'rank'; mode: 'mace' | 'sword'; state: unknown } | { kind: 'title'; code: string; label: string }

export interface PublicProfile {
  banner: string | null
  accent: string | null
  bio: string | null
  showcase: ShowcaseView[]
}

export interface MyProfile {
  main_mc_uuid: string | null
  banner: string | null
  accent: string | null
  bio: string | null
  showcase: ShowcaseItem[]
  banner_visibility: Visibility3
  bio_visibility: Visibility3
  showcase_visibility: Visibility3
  /** Bio masquée par la modération (lecture seule). */
  bio_hidden: boolean
  /** Mes comptes Minecraft prouvés, parmi lesquels choisir le principal (lecture seule). */
  accounts: MinecraftAccount[]
}

export interface LookupResult {
  id: string
  username: string
  minecraft: MinecraftAccount[]
  profile: PublicProfile
  relation: 'none' | 'friends' | 'outgoing' | 'incoming' | 'blocked' | 'self'
}

export type ReportReason = 'username' | 'bio' | 'status' | 'other'

/** Événement `friends_event` relayé par le launcher (flux temps réel). */
export type FriendEvent =
  | { type: 'ready' }
  | { type: 'resync' }
  | { type: 'friends_changed' }
  | { type: 'presence'; id: string; presence: FriendPresence }
  | { type: 'request_received'; request: FriendRequest }
  /** `by_you` : c'est moi qui viens d'accepter — rien à annoncer. */
  | { type: 'friend_added'; id: string; username: string; by_you: boolean }
