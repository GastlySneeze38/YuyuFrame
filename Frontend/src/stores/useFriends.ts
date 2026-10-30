import { create } from 'zustand'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import { showNotice } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { t } from '@/i18n'
import { demoFriends } from '@/lib/friendsDemo'
import { isDemo, useDevFlags } from '@/stores/useDevFlags'
import type { FriendEvent, FriendList, FriendPresence } from '@/types/friends'

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
  /** À la déconnexion : la liste ne parle plus de personne. */
  clear: () => void
}

/** Un ami vient-il d'arriver en ligne ? Seule la transition compte, pas un
 *  changement de statut ou d'activité d'un ami déjà là. */
export function cameOnline(before: FriendPresence | undefined, after: FriendPresence): boolean {
  return before?.state === 'offline' && after.state !== 'offline'
}

export const useFriends = create<FriendsStore>((set, get) => ({
  list: null,
  unseenRequests: 0,

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
        showNotice(t('friends.notify.added', { name: event.username }))
        void get().refresh()
        return
      case 'request_received': {
        const list = get().list
        if (list && !list.incoming.some((r) => r.id === event.request.id)) {
          set({ list: { ...list, incoming: [event.request, ...list.incoming] } })
        }
        set((s) => ({ unseenRequests: s.unseenRequests + 1 }))
        showNotice(t('friends.notify.request', { name: event.request.username }))
        return
      }
      case 'presence': {
        const list = get().list
        if (!list) return
        const friend = list.friends.find((f) => f.user_id === event.user_id)
        if (!friend) {
          // Un ami qu'on ne connaît pas encore : la liste a changé sans nous.
          void get().refresh()
          return
        }
        if (cameOnline(friend.presence, event.presence) && useStore.getState().friendOnlineNotify) {
          showNotice(t('friends.notify.online', { name: friend.username }))
        }
        set({
          list: {
            ...list,
            friends: list.friends.map((f) => (f.user_id === event.user_id ? { ...f, presence: event.presence } : f)),
          },
        })
        return
      }
    }
  },

  markRequestsSeen: () => set({ unseenRequests: 0 }),
  clear: () => set({ list: null, unseenRequests: 0 }),
}))

// L'interrupteur de démo de l'atelier a bougé : on repart de zéro, pour ne pas
// garder la liste de démonstration une fois éteint.
if (import.meta.env.DEV) {
  useDevFlags.subscribe((now, before) => {
    if (now.demoData === before.demoData) return
    useFriends.setState({ list: null })
    void useFriends.getState().refresh()
  })
}

/** Écoute le flux relayé par le launcher. Rend de quoi arrêter. */
export function listenFriendEvents(): Promise<() => void> {
  return listen<FriendEvent>('friends_event', (e) => useFriends.getState().apply(e.payload))
}
