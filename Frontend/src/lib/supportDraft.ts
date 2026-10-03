import { setDraft } from '@/stores/useDrafts'

/** Brouillon du formulaire de demande au support (`pages/Support.tsx`). */
export const SUPPORT_DRAFT_KEY = 'support-ticket'

/** Adresse qui ouvre l'écran Support sur le formulaire « poser une question ». */
export const SUPPORT_NEW_QUESTION = '/support?new=question'

/** Le formulaire refuse au-delà (voir `maxLength` dans `RequestModal`). */
const SUBJECT_MAX = 120
const MESSAGE_MAX = 4000

/**
 * Prépare une question technique pour le support depuis un autre écran : le
 * formulaire s'ouvre déjà rempli, et la personne relit, complète et envoie.
 *
 * Rien ne part tout seul. Ce qu'on écrit ici est un brouillon comme un autre —
 * il remplace celui qui traînait, ce qui est le but : on arrive avec un
 * problème précis.
 */
export function prefillSupportQuestion({ subject, message }: { subject: string; message: string }) {
  setDraft(SUPPORT_DRAFT_KEY, {
    category: 'technical',
    'question.subject': subject.slice(0, SUBJECT_MAX),
    'question.message': message.slice(0, MESSAGE_MAX),
  })
}
