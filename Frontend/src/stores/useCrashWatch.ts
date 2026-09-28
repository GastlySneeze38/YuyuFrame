import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { api } from '@/api/client'
import { useModalQueue } from '@/stores/useModalQueue'

/**
 * Surveillance des rapports de plantage envoyés : l'équipe leur donne un
 * statut, et personne ne le savait.
 *
 * Envoyer un rapport, c'est confier son journal à quelqu'un. Le minimum est
 * de dire ce qu'il en advient — « en cours d'analyse », « corrigé », « vient
 * d'un mod ». Jusqu'ici ce statut n'existait qu'au fond de l'onglet Support,
 * et il fallait penser à aller le regarder.
 *
 * ── Pourquoi une mémoire locale, contrairement au support ─────────────────
 * Un ticket porte `has_unread`, calculé par le serveur : la pastille suit
 * l'état réel et ne retombe qu'à la lecture. Un rapport de plantage n'a pas
 * cet indicateur — l'équipe change un statut, elle n'écrit pas dans un fil.
 * La lecture se retient donc ici, en comparant le dernier statut **acquitté**
 * (`acknowledged`, écrit quand on ouvre le rapport dans l'onglet Support) au
 * statut courant du serveur.
 *
 * Deux mémoires, et ce n'est pas un doublon :
 *  - `known` sert à ne pas rouvrir deux fois la même modale ;
 *  - `acknowledged` sert à éteindre la pastille, et seule l'ouverture du
 *    rapport la met à jour. Fermer la modale ne l'éteint donc pas — sans
 *    quoi un « corrigé » disparaîtrait des radars d'un clic sur la croix.
 */

/** Même rythme que le support : assez lent pour ne rien coûter, assez rapide
 *  pour qu'un statut changé pendant une partie soit là au retour. */
export const POLL_MS = 3 * 60 * 1000

/** `new` = reçu, personne ne l'a encore regardé : ce n'est pas une nouvelle,
 *  c'est l'état de départ de tout rapport envoyé. Tout le reste en est une. */
const SILENT = 'new'

/** Ce qui identifie un état : le statut **et** sa date. Deux passages
 *  successifs en « connu » (avec une note corrigée entre les deux) sont deux
 *  nouvelles, et le statut seul ne saurait pas les distinguer. */
const signatureOf = (c: { status: string; status_updated_at: string | null }) =>
  `${c.status}|${c.status_updated_at ?? ''}`

interface CrashWatchStore {
  /** Rapports dont le statut courant n'a pas encore été ouvert. 0 = pas de
   *  pastille. */
  updates: number
  /** Dernier état vu par le launcher, par rapport. Sert à la modale. */
  known: Record<string, string>
  /** Dernier état réellement ouvert par l'utilisateur. Sert à la pastille. */
  acknowledged: Record<string, string>
  refresh: () => Promise<void>
  /** Le rapport vient d'être ouvert : son état courant est lu. */
  acknowledge: (id: string, status: string, statusUpdatedAt: string | null) => void
  /** À la déconnexion : la pastille ne parle plus de personne. */
  clear: () => void
}

export const useCrashWatch = create<CrashWatchStore>()(
  persist(
    (set, get) => ({
      updates: 0,
      known: {},
      acknowledged: {},

      refresh: async () => {
        let reports
        try {
          reports = await api.crashes.remote()
        } catch {
          // Réseau coupé, session expirée : on garde le dernier compte connu
          // plutôt que d'effacer une pastille encore valable.
          return
        }

        const { known, acknowledged } = get()
        const seenBefore = Object.keys(known).length > 0
        const nextKnown: Record<string, string> = {}
        const nextAck: Record<string, string> = {}
        let updates = 0

        for (const report of reports) {
          const signature = signatureOf(report)
          nextKnown[report.id] = signature
          // On ne garde l'acquittement que des rapports encore présents :
          // un rapport retiré de sa liste ne doit pas laisser une entrée
          // derrière lui pour toujours.
          if (acknowledged[report.id]) nextAck[report.id] = acknowledged[report.id]

          if (report.status === SILENT) continue
          if (acknowledged[report.id] !== signature) updates += 1

          // Premier passage : on enregistre sans rien annoncer. Sinon, une
          // installation sur un deuxième PC ouvrirait une modale par rapport
          // de tout l'historique.
          if (!seenBefore) continue
          if (known[report.id] === signature) continue

          useModalQueue.getState().push({
            kind: 'crashStatus',
            // La date dans la clé : la prochaine étape du même rapport est
            // une autre nouvelle, pas un doublon de celle-ci.
            key: `crash-status-${report.id}-${signature}`,
            data: {
              reportId: report.id,
              publicId: report.public_id,
              title: report.title,
              status: report.status,
              note: report.status_note ?? null,
            },
          })
        }

        // Premier passage : tout ce qui existe déjà est considéré comme lu.
        // On annonce ce qui bouge à partir de maintenant, pas l'historique.
        if (!seenBefore) {
          set({ known: nextKnown, acknowledged: { ...nextKnown }, updates: 0 })
          return
        }

        set({ known: nextKnown, acknowledged: nextAck, updates })
      },

      acknowledge: (id, status, statusUpdatedAt) =>
        set((s) => {
          const acknowledged = { ...s.acknowledged, [id]: signatureOf({ status, status_updated_at: statusUpdatedAt }) }
          // Le compte se recalcule sur place : attendre le prochain sondage
          // laisserait la pastille allumée trois minutes après la lecture.
          const updates = Object.entries(s.known).filter(
            ([key, sig]) => !sig.startsWith(`${SILENT}|`) && acknowledged[key] !== sig,
          ).length
          return { acknowledged, updates }
        }),

      clear: () => set({ updates: 0 }),
    }),
    {
      name: 'yuyu-crash-watch',
      partialize: (s) => ({ known: s.known, acknowledged: s.acknowledged }),
    },
  ),
)
