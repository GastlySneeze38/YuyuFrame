import { create } from 'zustand'
import { api } from '@/api/client'

/**
 * Surveillance des tickets de support, pour la pastille de la barre de
 * navigation.
 *
 * ── Pourquoi elle ne passe pas par la file de modales ─────────────────────
 * Une modale se ferme, et la file retient qu'elle a été vue : c'est ce qu'on
 * veut pour une annonce, jamais pour un message qui attend une lecture. Si la
 * pastille suivait la file, lire la modale « l'équipe vous a répondu »
 * l'éteindrait — alors que la réponse, elle, n'a toujours pas été lue.
 *
 * La pastille suit donc l'état réel du serveur, pas ce qu'on a affiché :
 * `has_unread` ne retombe que lorsque le ticket est réellement ouvert
 * (`support_get` marque la lecture côté serveur, voir `pages/Support.tsx`).
 * Les deux mécanismes cohabitent sans se parler — la modale prévient une
 * fois, la pastille reste tant que rien n'est lu.
 */

/** Relecture périodique. Assez lent pour ne rien coûter, assez rapide pour
 *  qu'une réponse arrivée pendant une partie soit visible au retour. */
export const POLL_MS = 3 * 60 * 1000

interface SupportWatchStore {
  /** Nombre de tickets avec une réponse non lue. 0 = pas de pastille. */
  unread: number
  refresh: () => Promise<void>
  /** À la déconnexion : la pastille ne parle plus de personne. */
  clear: () => void
}

export const useSupportWatch = create<SupportWatchStore>((set) => ({
  unread: 0,

  refresh: async () => {
    try {
      const tickets = await api.support.list()
      set({ unread: tickets.filter((t) => t.has_unread).length })
    } catch {
      // Réseau coupé, session expirée : on garde le dernier compte connu
      // plutôt que d'effacer une pastille encore valable.
    }
  },

  clear: () => set({ unread: 0 }),
}))
