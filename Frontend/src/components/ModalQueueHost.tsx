import { useEffect } from 'react'
import { AnimatePresence } from 'framer-motion'
import { useModalQueue, type ModalKind } from '@/stores/useModalQueue'
import { PatchNotesModal } from '@/components/PatchNotesModal'
import { ReconnectModal } from '@/components/account/ReconnectModal'
import { OfflinePurchaseReminderModal } from '@/components/account/OfflinePurchaseReminderModal'
import { CrashReportModal } from '@/components/modals/CrashReportModal'
import { CrashStatusModal } from '@/components/modals/CrashStatusModal'
import { SupportUpdateModal } from '@/components/modals/SupportUpdateModal'
import { ReviewPromptModal } from '@/components/modals/ReviewPromptModal'

/**
 * Rend la modale en tête de file, et elle seule.
 *
 * Monté une fois dans `App.tsx`. Les sources ne montent plus leurs modales
 * elles-mêmes : elles déposent une demande dans `useModalQueue`, et tout
 * l'affichage se décide ici.
 *
 * `AnimatePresence mode="wait"` fait deux choses d'un coup : il laisse la
 * modale sortante jouer son animation de sortie — elles étaient jusqu'ici
 * démontées d'un coup, leur `exit` ne servait à rien — et il impose un temps
 * mort avant la suivante. Sans ce temps mort, fermer une modale en ferait
 * apparaître une autre sous le curseur, exactement là où on venait de
 * cliquer : le deuxième clic partirait avant d'avoir rien lu.
 */
/** Les genres que cet hôte sait rendre. Le garde-fou plus bas s'en sert pour
 *  retirer une demande orpheline au lieu de geler la file. */
const HANDLED: ModalKind[] = [
  'patchNotes',
  'reconnect',
  'crash',
  'crashStatus',
  'support',
  'offlineReminder',
  'review',
]

export function ModalQueueHost() {
  const current = useModalQueue((s) => s.current)
  const close = useModalQueue((s) => s.close)
  // Lu en deux morceaux, et non par une fonction du magasin : un sélecteur
  // qui rend une fonction ne redéclenche jamais le rendu, donc le compteur
  // serait resté sur son total d'origine quand une demande arrive pendant
  // qu'une modale est déjà à l'écran.
  const waiting = useModalQueue((s) => s.pending.length)
  const done = useModalQueue((s) => s.done)
  const counter = { index: done + 1, total: done + 1 + waiting }

  // Garde-fou : une demande d'un genre qu'on ne sait pas rendre resterait en
  // tête sans rien afficher — et sans rien pour la fermer, elle bloquerait
  // toutes les suivantes. On la retire plutôt que de geler la file.
  useEffect(() => {
    if (current && !HANDLED.includes(current.kind)) {
      console.warn(`[ModalQueue] genre sans modale : ${current.kind}`)
      close()
    }
  }, [current, close])

  return (
    <AnimatePresence mode="wait">
      {current?.kind === 'patchNotes' && (
        <PatchNotesModal
          key={current.key}
          title={current.data.title}
          kicker={current.data.kicker}
          body={current.data.body}
          counter={counter}
          onClose={close}
        />
      )}
      {current?.kind === 'reconnect' && (
        <ReconnectModal key={current.key} counter={counter} onClose={close} />
      )}
      {current?.kind === 'offlineReminder' && (
        <OfflinePurchaseReminderModal key={current.key} counter={counter} onClose={close} />
      )}
      {current?.kind === 'crash' && (
        <CrashReportModal
          key={current.key}
          reportId={current.data.reportId}
          instanceId={current.data.instanceId}
          title={current.data.title}
          cause={current.data.cause}
          counter={counter}
          onClose={close}
        />
      )}
      {current?.kind === 'crashStatus' && (
        <CrashStatusModal
          key={current.key}
          reportId={current.data.reportId}
          publicId={current.data.publicId}
          title={current.data.title}
          status={current.data.status}
          note={current.data.note}
          counter={counter}
          onClose={close}
        />
      )}
      {current?.kind === 'support' && (
        <SupportUpdateModal
          key={current.key}
          ticketId={current.data.ticketId}
          publicId={current.data.publicId}
          subject={current.data.subject}
          status={current.data.status}
          counter={counter}
          onClose={close}
        />
      )}
      {current?.kind === 'review' && (
        <ReviewPromptModal
          key={current.key}
          sessions={current.data.sessions}
          counter={counter}
          onClose={close}
        />
      )}
    </AnimatePresence>
  )
}
