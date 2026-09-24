import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { api } from '@/api/client'
import { useModalQueue } from '@/stores/useModalQueue'

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

/** Les changements qui méritent qu'on interrompe quelqu'un. Un ticket qui
 *  passe de « ouvert » à « ouvert » n'est pas une nouvelle. */
const WORTH_TELLING = ['answered', 'closed', 'waiting']

interface SupportWatchStore {
  /** Nombre de tickets avec une réponse non lue. 0 = pas de pastille. */
  unread: number
  /** Dernier statut connu par ticket, persisté : c'est la comparaison avec
   *  lui qui fait la nouvelle. Sans mémoire, tout serait neuf à chaque
   *  démarrage et la modale reviendrait sans fin. */
  known: Record<string, string>
  refresh: () => Promise<void>
  /** À la déconnexion : la pastille ne parle plus de personne. */
  clear: () => void
}

export const useSupportWatch = create<SupportWatchStore>()(
  persist(
    (set, get) => ({
      unread: 0,
      known: {},

      refresh: async () => {
        let tickets
        try {
          tickets = await api.support.list()
        } catch {
          // Réseau coupé, session expirée : on garde le dernier compte connu
          // plutôt que d'effacer une pastille encore valable.
          return
        }

        const known = get().known
        const seenBefore = Object.keys(known).length > 0
        const next: Record<string, string> = {}

        for (const ticket of tickets) {
          next[ticket.id] = ticket.status
          const before = known[ticket.id]
          // Premier passage : on enregistre sans rien annoncer. Sinon, une
          // installation sur un deuxième PC ouvrirait une modale par ticket
          // de tout l'historique.
          if (!seenBefore) continue
          if (before === ticket.status) continue
          if (!WORTH_TELLING.includes(ticket.status)) continue

          useModalQueue.getState().push({
            kind: 'support',
            // La clé porte le statut : la prochaine étape du même ticket
            // sera une autre nouvelle, pas un doublon de celle-ci.
            key: `support-${ticket.id}-${ticket.status}`,
            data: {
              ticketId: ticket.id,
              publicId: ticket.public_id,
              subject: ticket.subject,
              status: ticket.status,
            },
          })
        }

        set({ unread: tickets.filter((t) => t.has_unread).length, known: next })
      },

      clear: () => set({ unread: 0 }),
    }),
    {
      name: 'yuyu-support-watch',
      // Le compte de non-lus se redemande au serveur en une requête ; l'état
      // connu des tickets, lui, ne se retrouve nulle part.
      partialize: (s) => ({ known: s.known }),
    },
  ),
)
