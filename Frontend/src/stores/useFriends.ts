import { create } from 'zustand'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import { showError, showNotice, type ToastAction } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { t } from '@/i18n'
import { demoFriends } from '@/lib/friendsDemo'
import { clearFriendProfiles } from '@/lib/friendProfileCache'
import { isDemo, useDevFlags } from '@/stores/useDevFlags'
import { useSupportWatch } from '@/stores/useSupportWatch'
import { useCrashWatch } from '@/stores/useCrashWatch'
import type { DiscordLink, FriendEvent, FriendList, FriendPresence, ReceivedInvite } from '@/types/friends'

/** Durée de vie d'une invitation côté serveur (`INVITE_LIFETIME`). */
export const INVITE_LIFETIME_MS = 10 * 60 * 1000

/**
 * Les amis, tenus à jour en direct.
 *
 * La liste vient de `friends_list` ; ensuite, le launcher relaie le flux
 * temps réel du serveur (`friends_event`, Backend `friends/stream.rs`) :
 * présence d'un ami, demande reçue, nouvel ami, liste à recharger. Le premier
 * événement d'une connexion, `ready`, fait recharger la liste — on ne rate
 * donc rien de ce qui s'est passé pendant une coupure.
 *
 * Pas encore d'écran Amis : ce magasin sert déjà à prévenir (demande reçue,
 * nouvel ami, ami qui se connecte), et l'écran n'aura qu'à le lire.
 */

interface FriendsStore {
  /** `null` : pas encore chargée (ou pas de compte). */
  list: FriendList | null
  /** Demandes reçues arrivées depuis la dernière visite (pastille de l'écran Amis à venir). */
  unseenRequests: number
  refresh: () => Promise<void>
  apply: (event: FriendEvent) => void
  markRequestsSeen: () => void
  /** Invitations reçues encore valables, la plus récente d'abord. */
  invites: ReceivedInvite[]
  /** Retire une invitation (acceptée, refusée, ignorée ou expirée). */
  dropInvite: (invite: string) => void
  /** Mon compte Discord lié ; `null` : pas encore lu. */
  discord: DiscordLink | null
  refreshDiscord: () => Promise<void>
  /** Un lien `yuyuframe://friend/…` cliqué : le panneau s'ouvre sur l'ajout
   *  de ce code. `seq` change à chaque demande, même pour le même code. */
  addRequest: { code: string; seq: number } | null
  askAdd: (code: string) => void
  /** Le panneau a pris la demande en charge. */
  takeAddRequest: () => string | null
  /** À la déconnexion : la liste ne parle plus de personne. */
  clear: () => void
}

/** Un ami vient-il d'arriver en ligne ? Seule la transition compte, pas un
 *  changement de statut ou d'activité d'un ami déjà là. */
export function cameOnline(before: FriendPresence | undefined, after: FriendPresence): boolean {
  return before?.state === 'offline' && after.state !== 'offline'
}

/** Revenu en ligne moins de 10 minutes après l'avoir quitté (coupure réseau) :
 *  on ne reprévient pas. Même règle que les notifications Windows
 *  (`Backend/src/friends/notify.rs`, `FLAP_WINDOW`). */
export const FLAP_WINDOW_MS = 10 * 60 * 1000

export function justLeft(before: FriendPresence | undefined, now = Date.now()): boolean {
  const seen = before?.last_seen ? Date.parse(before.last_seen) : NaN
  return Number.isFinite(seen) && now - seen < FLAP_WINDOW_MS
}

/** Une notice d'ami, sauf en « Ne pas déranger » (la pastille, elle, reste). */
function tell(message: string, action?: ToastAction) {
  if (useFriends.getState().list?.me.status === 'dnd') return
  showNotice(message, action)
}

/**
 * « Lui montrer » : mon serveur devient visible pour cet ami, sans toucher à
 * ce que voient les autres. Mon réglage était « personne » : il passe à
 * « certains amis », avec lui seul dedans ; « tous mes amis » : il le voit déjà.
 */
async function showServerTo(id: string, name: string) {
  const list = useFriends.getState().list
  if (!list) return
  try {
    const visibility = list.settings.server_visibility
    if (isDemo()) {
      useFriends.setState({
        list: {
          ...list,
          settings: visibility === 'nobody' ? { ...list.settings, server_visibility: 'some' } : list.settings,
          friends: list.friends.map((f) => (f.id === id ? { ...f, server_shared: true } : f)),
        },
      })
    } else {
      if (visibility === 'nobody') await api.friends.setSettings({ ...list.settings, server_visibility: 'some' })
      if (visibility !== 'friends') await api.friends.setServerShare(id, true)
      await useFriends.getState().refresh()
    }
    showNotice(t('friends.notify.serverShown', { name }))
  } catch (e) {
    showError(e)
  }
}

export const useFriends = create<FriendsStore>((set, get) => ({
  list: null,
  unseenRequests: 0,
  invites: [],

  refresh: async () => {
    // Données de démonstration demandées dans l'atelier de développement :
    // elles remplacent la vraie route (les gestes, eux, échoueront).
    if (isDemo()) {
      set({ list: demoFriends() })
      return
    }
    try {
      const list = await api.friends.list()
      set({ list })
    } catch {
      // API sans la route, réseau coupé : on garde ce qu'on a, le flux réessaiera.
    }
  },

  apply: (event) => {
    switch (event.type) {
      case 'ready':
      case 'resync':
      case 'friends_changed':
        void get().refresh()
        return
      case 'friend_added':
        // C'est moi qui viens d'accepter : je le sais déjà.
        if (!event.by_you) tell(t('friends.notify.added', { name: event.username }))
        void get().refresh()
        return
      case 'request_received': {
        const list = get().list
        if (list && !list.incoming.some((r) => r.id === event.request.id)) {
          set({ list: { ...list, incoming: [event.request, ...list.incoming] } })
        }
        set((s) => ({ unseenRequests: s.unseenRequests + 1 }))
        tell(t('friends.notify.request', { name: event.request.username }))
        return
      }
      case 'my_status': {
        const list = get().list
        if (list) set({ list: { ...list, me: { ...list.me, status: event.status, shown: event.shown, custom: event.custom } } })
        return
      }
      case 'server_requested':
        tell(t('friends.notify.serverRequested', { name: event.username }), {
          label: t('friends.notify.showServer'),
          run: () => showServerTo(event.id, event.username),
        })
        return
      case 'invited': {
        // Le même ami qui réinvite remplace son invitation précédente.
        const invite: ReceivedInvite = {
          invite: event.invite,
          id: event.id,
          username: event.username,
          server: event.server,
          mc_version: event.mc_version,
          loader: event.loader,
          expiresAt: Date.now() + INVITE_LIFETIME_MS,
        }
        set((s) => ({ invites: [invite, ...s.invites.filter((i) => i.id !== event.id && i.expiresAt > Date.now())] }))
        return
      }
      case 'invite_declined':
        tell(t('friends.invites.declinedBy', { name: event.username }))
        return
      case 'discord_changed':
        void get().refreshDiscord()
        return
      case 'support_changed':
        // Réponse de l'équipe ou rapport de plantage qui bouge : la pastille
        // se met à jour tout de suite (les modales suivent Ne pas déranger).
        void useSupportWatch.getState().refresh()
        void useCrashWatch.getState().refresh()
        return
      case 'presence': {
        const list = get().list
        if (!list) return
        const friend = list.friends.find((f) => f.id === event.id)
        if (!friend) {
          // Un ami qu'on ne connaît pas encore : la liste a changé sans nous.
          void get().refresh()
          return
        }
        if (cameOnline(friend.presence, event.presence) && !justLeft(friend.presence) && useStore.getState().friendOnlineNotify) {
          tell(t('friends.notify.online', { name: friend.username }))
        }
        set({
          list: {
            ...list,
            friends: list.friends.map((f) => (f.id === event.id ? { ...f, presence: event.presence } : f)),
          },
        })
        return
      }
    }
  },

  markRequestsSeen: () => set({ unseenRequests: 0 }),
  dropInvite: (invite) => set((s) => ({ invites: s.invites.filter((i) => i.invite !== invite) })),

  discord: null,
  refreshDiscord: async () => {
    // Démo, ou API sans la route : la carte reste visible et dit que la
    // liaison n'est pas encore ouverte, plutôt que de disparaître.
    const closed: DiscordLink = { available: false, dm_available: false, linked: null }
    if (isDemo()) {
      set({ discord: closed })
      return
    }
    try {
      set({ discord: await api.discord.get() })
    } catch {
      set({ discord: closed })
    }
  },

  addRequest: null,
  askAdd: (code) => set((s) => ({ addRequest: { code, seq: (s.addRequest?.seq ?? 0) + 1 } })),
  takeAddRequest: () => {
    const code = get().addRequest?.code ?? null
    if (code) set((s) => ({ addRequest: s.addRequest && { ...s.addRequest, code: '' } }))
    return code || null
  },

  clear: () => {
    clearFriendProfiles()
    set({ list: null, unseenRequests: 0, invites: [], discord: null })
  },
}))

// L'interrupteur de démo de l'atelier a bougé : on repart de zéro, pour ne pas
// garder la liste de démonstration une fois éteint.
if (import.meta.env.DEV) {
  useDevFlags.subscribe((now, before) => {
    if (now.demoData === before.demoData) return
    clearFriendProfiles()
    useFriends.setState({ list: null })
    void useFriends.getState().refresh()
  })
}

// Le statut Discord ne montre le serveur que si mon réglage le permet
// (`server_visibility` différent de « personne ») : le Rust le suit.
let showServerInDiscord: boolean | null = null
useFriends.subscribe((s) => {
  const show = !!s.list && s.list.settings.server_visibility !== 'nobody'
  if (show === showServerInDiscord) return
  showServerInDiscord = show
  void api.discord.configurePresence(show).catch(() => {})
})

/** Écoute le flux relayé par le launcher. Rend de quoi arrêter. */
export function listenFriendEvents(): Promise<() => void> {
  return listen<FriendEvent>('friends_event', (e) => useFriends.getState().apply(e.payload))
}
