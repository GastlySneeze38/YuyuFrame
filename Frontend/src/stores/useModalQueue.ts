import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * File d'attente des modales de communication.
 *
 * ── Pourquoi une file ─────────────────────────────────────────────────────
 * Avant, chaque modale de démarrage était une condition posée dans `App.tsx`,
 * et l'ordre entre elles s'écrivait à la main : « si les notes de patch sont
 * là, les afficher, sinon la reconnexion, sinon le rappel hors ligne », avec
 * la suite de la chaîne recopiée dans chaque `onClose`. À trois modales
 * c'était déjà pénible ; à sept — plantage, support, avis compris — chaque
 * ajout aurait obligé à relire toutes les autres.
 *
 * Ici, une source ne décide plus de s'afficher : elle DÉPOSE une demande. La
 * file décide seule de l'ordre et n'en montre qu'une à la fois. Ajouter une
 * modale, c'est ajouter une ligne dans `PRIORITY` et un cas dans l'hôte.
 *
 * ── Ce qui est persisté ───────────────────────────────────────────────────
 * Uniquement les clés déjà vues. Une demande vue une fois ne doit jamais
 * revenir : c'est ce que l'empilement de conditions ne savait pas faire, et
 * la raison pour laquelle certaines modales se répétaient à chaque
 * démarrage. La file elle-même ne survit pas à la fermeture : ce qui n'a pas
 * été vu sera redéposé par sa source au prochain lancement.
 */

export type ModalKind =
  | 'patchNotes'
  | 'reconnect'
  | 'crash'
  | 'support'
  | 'offlineReminder'
  | 'review'

/**
 * Ordre d'affichage, du plus prioritaire au moins.
 *
 * Les notes de patch passent avant tout : elles expliquent ce qui vient de
 * changer, donc elles éclairent tout ce qui suit. La reconnexion vient
 * ensuite parce que sans compte valide, rien d'autre ne se joue. Le plantage
 * précède le support — on rend compte de ce qui vient de casser avant de
 * parler de ce qu'on a déjà signalé. Le rappel d'achat et la demande d'avis
 * ferment la marche : ce sont les deux seules qui demandent quelque chose au
 * lieu d'apporter une information.
 */
const PRIORITY: ModalKind[] = [
  'patchNotes',
  'reconnect',
  'crash',
  'support',
  'offlineReminder',
  'review',
]

/** Les données dont chaque modale a besoin pour se rendre. */
type ModalPayload =
  | { kind: 'patchNotes'; data: { title: string; kicker: string | null; body: string } }
  | { kind: 'reconnect'; data: null }
  | { kind: 'crash'; data: { reportId: string; instanceId: string; title: string; cause: string } }
  | { kind: 'support'; data: { ticketId: string; publicId: string; subject: string; status: string } }
  | { kind: 'offlineReminder'; data: null }
  | { kind: 'review'; data: { sessions: number } }

export type QueuedModal = ModalPayload & {
  /** Identité de la demande. Deux dépôts de même clé ne font qu'une modale. */
  key: string
  /**
   * `false` pour un rappel qui doit revenir à chaque démarrage — le rappel
   * d'achat, par exemple, qui n'est pas une nouvelle à annoncer une fois mais
   * un état à signaler tant qu'il dure. Oublié à la fermeture du launcher au
   * lieu d'être retenu pour toujours.
   */
  remember?: boolean
}

/**
 * Ce que l'hôte passe à toute modale de la file : de quoi se fermer, et son
 * rang dans la série. Les modales qui existaient avant la file gardent la
 * même forme — `counter` est facultatif, elles restent affichables seules.
 */
export interface QueuedModalProps {
  onClose: () => void
  counter?: { index: number; total: number }
}

function rank(kind: ModalKind) {
  const i = PRIORITY.indexOf(kind)
  // Un genre inconnu passe en dernier plutôt qu'en premier : `indexOf`
  // rend -1, qui trierait avant tout le reste.
  return i === -1 ? PRIORITY.length : i
}

interface ModalQueueStore {
  /** Ce qui est à l'écran. Jamais remplacé avant d'avoir été fermé. */
  current: QueuedModal | null
  /** La suite, triée par priorité. */
  pending: QueuedModal[]
  /** Combien ont déjà été vues dans cette série — sert au compteur. */
  done: number
  /** Clés déjà vues, persistées. */
  seen: string[]
  /** Idem, mais pour la session en cours seulement (`remember: false`). */
  seenNow: string[]
  push: (item: QueuedModal) => void
  close: () => void
  /** Remet la mémoire à zéro (réglages → « revoir les messages »). */
  forgetAll: () => void
}

export const useModalQueue = create<ModalQueueStore>()(
  persist(
    (set, get) => ({
      current: null,
      pending: [],
      done: 0,
      seen: [],
      seenNow: [],

      push: (item) => {
        const { current, pending, seen, seenNow } = get()
        // Trois refus possibles, tous silencieux : déjà vue, déjà à l'écran,
        // déjà en attente. Une source peut donc redéposer sa demande autant
        // de fois qu'elle veut — au montage, après un rechargement, à chaque
        // sondage — sans avoir à savoir ce que les autres ont fait.
        if (seen.includes(item.key) || seenNow.includes(item.key)) return
        if (current?.key === item.key) return
        if (pending.some((p) => p.key === item.key)) return

        // Rien à l'écran : la demande y va directement.
        if (!current) {
          set({ current: item })
          return
        }

        // Sinon elle prend sa place dans la suite. Le tri est stable : à
        // priorité égale, c'est l'ordre d'arrivée qui tranche.
        const next = [...pending, item].sort((a, b) => rank(a.kind) - rank(b.kind))
        set({ pending: next })
      },

      close: () => {
        const { current, pending, seen, seenNow, done } = get()
        if (!current) return
        const [next, ...rest] = pending
        const lasting = current.remember !== false
        set({
          seen: lasting && !seen.includes(current.key) ? [...seen, current.key] : seen,
          seenNow: !lasting && !seenNow.includes(current.key) ? [...seenNow, current.key] : seenNow,
          current: next ?? null,
          pending: rest,
          // Le compteur ne se remet à zéro qu'une fois la série finie :
          // une nouvelle demande arrivée entre-temps ouvre une série neuve
          // plutôt que de prolonger « 4 sur 3 ».
          done: next ? done + 1 : 0,
        })
      },

      forgetAll: () => set({ seen: [], seenNow: [] }),
    }),
    {
      name: 'yuyu-modal-queue',
      // La file en cours ne se persiste pas : au redémarrage, chaque source
      // redépose ce qui est encore d'actualité. Persister l'attente ferait
      // ressortir des demandes devenues sans objet — un plantage vieux de
      // trois semaines, un ticket déjà clos.
      partialize: (s) => ({ seen: s.seen }),
    },
  ),
)
