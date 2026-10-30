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

export interface Friend {
  user_id: number
  username: string
  since: string
  minecraft: { uuid: string; name: string }[]
  presence: FriendPresence
  /** Je lui montre mon serveur (compte quand ma visibilité vaut `some`). */
  server_shared: boolean
}

export interface FriendRequest {
  id: number
  /** L'autre joueur. */
  user_id: number
  username: string
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

export interface FriendList {
  me: MyStatus
  friends: Friend[]
  incoming: FriendRequest[]
  outgoing: FriendRequest[]
  blocked: { user_id: number; username: string }[]
  settings: FriendSettings
}

export interface RequestSent {
  status: 'pending' | 'friends'
  user_id: number
  username: string
}

/** Événement `friends_event` relayé par le launcher (flux temps réel). */
export type FriendEvent =
  | { type: 'ready' }
  | { type: 'resync' }
  | { type: 'friends_changed' }
  | { type: 'presence'; user_id: number; presence: FriendPresence }
  | { type: 'request_received'; request: FriendRequest }
  | { type: 'friend_added'; user_id: number; username: string }
