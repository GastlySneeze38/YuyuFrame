import { useEffect } from 'react'
import { AnimatePresence } from 'framer-motion'
import { useModalQueue, type ModalKind } from '@/stores/useModalQueue'
import { useStore } from '@/stores/useStore'
import { PatchNotesModal } from '@/components/PatchNotesModal'
import { ReconnectModal } from '@/components/account/ReconnectModal'
import { OfflinePurchaseReminderModal } from '@/components/account/OfflinePurchaseReminderModal'

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
/** Les genres que cet hôte sait rendre aujourd'hui. */
const HANDLED: ModalKind[] = ['patchNotes', 'reconnect', 'offlineReminder']

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
  const setPendingPatchNotes = useStore((s) => s.setPendingPatchNotes)

  // Les notes de patch restent « en attente » dans le magasin persisté tant
  // qu'on ne les a pas montrées : c'est ce drapeau qui les redépose au
  // démarrage suivant. Il s'efface donc quand elles sont vues, pas avant.
  useEffect(() => {
    if (current?.kind === 'patchNotes') setPendingPatchNotes(null)
  }, [current, setPendingPatchNotes])

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
          version={current.data.version}
          notes={current.data.notes}
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
      {/* Les genres `crash`, `support` et `review` sont déjà déclarés dans la
          file et ont leur rang : il ne leur manque que leur modale. Rien ne
          les dépose encore, donc rien ne peut bloquer ici — et le jour où on
          les écrit, il n'y a que ce bloc à compléter. */}
    </AnimatePresence>
  )
}
