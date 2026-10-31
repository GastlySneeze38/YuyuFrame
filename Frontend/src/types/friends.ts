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
  /** Le statut choisi. */
  status: MyStatusKind
  /** Celui que voient mes amis : le choisi, ou `away` quand je suis « en
   *  ligne » mais inactif depuis 10 minutes (absent automatique). Absent
   *  d'une API plus ancienne. */
  shown?: PresenceState | 'invisible'
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

/** Un élément de vitrine choisi : un objet précis (tel rang, tel titre…).
 *  Un titre sans code vient d'un profil d'avant la bibliothèque. */
export type ShowcaseItem = { kind: 'rank'; mode: 'mace' | 'sword' } | { kind: 'title'; code?: string } | { kind: 'season'; mode: 'mace' | 'sword' }

/** Un objet de la bibliothèque (bannière, couleur) : possédé, ou où le débloquer. */
export interface LibraryItem {
  /** La bannière (`aurora`…) ou la couleur (`#rrggbb`). */
  code: string
  /** Son nom, réglé dans le back-office ; absent d'une API plus ancienne. */
  label?: string
  owned: boolean
  /** Palier du passe de la saison en cours qui le débloque ; absent : pas de moyen pour l'instant. */
  unlock: { season: number; tier: number } | null
}

export interface ProfileLibrary {
  banners: LibraryItem[]
  accents: LibraryItem[]
  /** Mes titres en cours. */
  titles: { code: string; label: string; asset: string | null }[]
}

/** Un élément de vitrine tel qu'on le montre. `state` : l'état du classement,
 *  à lire avec `readStandings` (`lib/competitive.ts`). */
export type ShowcaseView =
  | { kind: 'rank'; mode: 'mace' | 'sword'; state: unknown }
  /** `asset` : l'image du titre réglée dans le back-office (absente d'une API plus ancienne). */
  | { kind: 'title'; code: string; label: string; asset?: string | null }
  /** La grille de la saison : les meilleures victoires (division en gagnant) et leur nombre. */
  | { kind: 'season'; mode: 'mace' | 'sword'; wins: number[]; count: number }

export interface PublicProfile {
  banner: string | null
  accent: string | null
  bio: string | null
  showcase: ShowcaseView[]
  /** Il a lié un compte Discord (vu de ses amis seulement) ; `username` s'il
   *  le montre. Absent d'une API plus ancienne. */
  discord?: { username: string | null } | null
}

/** Une invitation relue sur le serveur (`GET /friends/invites/{invite}`). */
export interface ApiReceivedInvite {
  invite: string
  id: string
  username: string
  server: string
  mc_version: string
  loader: string
  /** Secondes avant son échéance. */
  expires_in: number
}

/** Mon compte Discord lié (`GET /discord/link`). */
export interface DiscordLink {
  /** La liaison est ouverte sur le serveur. */
  available: boolean
  /** Les messages privés d'invitation peuvent partir. */
  dm_available: boolean
  linked: {
    username: string
    global_name: string | null
    /** Mes amis voient mon pseudo Discord. */
    show_to_friends: boolean
    /** Les invitations de mes amis m'arrivent aussi en message privé. */
    dm_invites: boolean
    linked_at: string
  } | null
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
  /** Ma bibliothèque (lecture seule) ; absente d'une API plus ancienne. */
  library?: ProfileLibrary
}

export interface LookupResult {
  id: string
  username: string
  minecraft: MinecraftAccount[]
  profile: PublicProfile
  relation: 'none' | 'friends' | 'outgoing' | 'incoming' | 'blocked' | 'self'
}

export type ReportReason = 'username' | 'bio' | 'status' | 'other'

/** Une invitation reçue, gardée jusqu'à sa réponse ou son expiration. */
export interface ReceivedInvite {
  /** Son identifiant, pour la refuser. */
  invite: string
  /** L'ami qui invite (identifiant public). */
  id: string
  username: string
  /** « hôte:port ». */
  server: string
  mc_version: string
  loader: string
  /** Horodatage (ms) au-delà duquel le serveur l'a oubliée. */
  expiresAt: number
}

/** Événement `friends_event` relayé par le launcher (flux temps réel). */
export type FriendEvent =
  | { type: 'ready' }
  | { type: 'resync' }
  | { type: 'friends_changed' }
  | { type: 'presence'; id: string; presence: FriendPresence }
  | { type: 'request_received'; request: FriendRequest }
  /** `by_you` : c'est moi qui viens d'accepter — rien à annoncer. */
  | { type: 'friend_added'; id: string; username: string; by_you: boolean }
  /** Mon statut a changé ailleurs (un autre de mes launchers, mon inactivité). */
  | { type: 'my_status'; status: MyStatusKind; shown: PresenceState | 'invisible'; custom: CustomStatus | null }
  /** Un ami aimerait voir mon serveur. */
  | { type: 'server_requested'; id: string; username: string }
  /** Un ami m'invite sur son serveur (valable 10 min). */
  | { type: 'invited'; invite: string; id: string; username: string; server: string; mc_version: string; loader: string }
  /** L'ami que j'ai invité a refusé. */
  | { type: 'invite_declined'; invite: string; id: string; username: string }
  /** Mes tickets ou mes rapports de plantage ont bougé : relire. */
  | { type: 'support_changed' }
  /** Mon compte Discord a été lié, délié ou réglé : relire. */
  | { type: 'discord_changed' }
