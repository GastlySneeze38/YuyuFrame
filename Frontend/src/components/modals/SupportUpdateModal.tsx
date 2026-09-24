import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import type { QueuedModalProps } from '@/stores/useModalQueue'
import { useT } from '@/i18n'

/**
 * « Ta demande a bougé » — l'équipe a répondu, clos, ou attend une réponse.
 *
 * Elle prévient une fois, à l'ouverture du launcher. Ce qui *reste* affiché
 * tant que le message n'est pas lu, c'est la pastille de la barre de
 * navigation, qui vit à part (voir `stores/useSupportWatch.ts`) : fermer
 * cette modale ne l'éteint pas, sans quoi une réponse jamais lue
 * disparaîtrait des radars d'un clic sur la croix.
 */
export function SupportUpdateModal({
  ticketId,
  publicId,
  subject,
  status,
  onClose,
  counter,
}: QueuedModalProps & {
  ticketId: string
  publicId: string
  subject: string
  status: string
}) {
  const t = useT()
  const navigate = useNavigate()

  // Un statut inconnu — ajouté côté serveur plus tard — retombe sur la
  // formule neutre plutôt que d'afficher une clé de traduction.
  const line =
    status === 'answered' || status === 'closed' || status === 'waiting'
      ? t(`support.update.${status}`, { subject })
      : t('support.update.changed', { subject })

  // Le fil concerné est ouvert directement : arriver sur la liste obligerait
  // à retrouver soi-même celui dont on vient de parler.
  function read() {
    onClose()
    navigate(`/support?ticket=${encodeURIComponent(ticketId)}`)
  }

  return (
    <ModalShell title={t('support.update.title')} onClose={onClose} maxWidth="max-w-sm" counter={counter}>
      <div className="flex flex-col gap-4">
        <p className="text-[12px] leading-relaxed text-txt-secondary">{line}</p>
        <p className="text-[11px] text-txt-muted">{t('support.update.ref', { ref: publicId })}</p>

        <div className="flex items-center gap-2">
          <Button variant="primary" onClick={read} className="flex-1">
            {t('support.update.read')}
          </Button>
          <Button variant="ghost" onClick={onClose}>
            {t('support.update.later')}
          </Button>
        </div>

        {/* La pastille reste : le dire évite de croire qu'on vient de perdre
            le fil en fermant la fenêtre. */}
        <p className="text-[10px] text-txt-muted">{t('support.update.badgeStays')}</p>
      </div>
    </ModalShell>
  )
}
