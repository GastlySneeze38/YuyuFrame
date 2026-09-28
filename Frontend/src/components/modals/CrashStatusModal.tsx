import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import type { QueuedModalProps } from '@/stores/useModalQueue'
import { useT } from '@/i18n'

/**
 * « Ton rapport a bougé » — l'équipe a donné un statut à un plantage envoyé.
 *
 * Envoyer un rapport, c'est confier son journal à quelqu'un : le minimum est
 * de dire ce qu'il en advient. Le statut existait déjà côté serveur, mais il
 * fallait penser à aller le chercher au fond de l'onglet Support.
 *
 * Elle prévient une fois. Ce qui *reste* affiché tant que le rapport n'a pas
 * été ouvert, c'est la pastille de la barre de navigation, qui vit à part
 * (voir `stores/useCrashWatch.ts`) : fermer cette modale ne l'éteint pas.
 */
export function CrashStatusModal({
  reportId,
  publicId,
  title,
  status,
  note,
  onClose,
  counter,
}: QueuedModalProps & {
  reportId: string
  publicId: string
  title: string
  status: string
  note: string | null
}) {
  const t = useT()
  const navigate = useNavigate()

  // Un statut ajouté côté serveur plus tard retombe sur son propre nom plutôt
  // que d'afficher une clé de traduction.
  const known = ['investigating', 'known', 'fixed', 'wont_fix', 'duplicate', 'not_a_bug'].includes(status)
  const label = known ? t(`crash.status.${status}`) : status

  function read() {
    onClose()
    navigate(`/support?tab=crashes&crash=${encodeURIComponent(reportId)}`)
  }

  return (
    <ModalShell title={t('crash.update.title')} onClose={onClose} counter={counter}>
      <div className="flex flex-col gap-5">
        <p className="text-[13px] leading-relaxed text-txt-secondary">
          {t('crash.update.line', { title, status: label })}
        </p>

        {/* Ce que le statut veut dire, dans les mots déjà employés par la
            liste des rapports — deux formulations pour la même chose
            obligeraient à les tenir en phase. */}
        {known && (
          <p className="text-[12px] leading-relaxed text-txt-muted">{t(`crash.statusHint.${status}`)}</p>
        )}

        {/* La réponse écrite par l'équipe, quand il y en a une : c'est la
            seule partie que personne d'autre ne peut deviner. */}
        {note && (
          <div className="rounded-xl border border-line bg-surface-1 p-3">
            <p className="mb-1 text-[11px] font-bold uppercase tracking-[0.12em] text-txt-muted">
              {t('crash.update.team')}
            </p>
            <p className="text-[13px] leading-relaxed text-txt-secondary">{note}</p>
          </div>
        )}

        <p className="text-[12px] text-txt-muted">{t('crash.update.ref', { ref: publicId })}</p>

        <div className="flex items-center gap-2">
          <Button variant="primary" onClick={read} className="flex-1">
            {t('crash.update.read')}
          </Button>
          <Button variant="ghost" onClick={onClose}>
            {t('crash.update.later')}
          </Button>
        </div>

        <p className="text-[11.5px] text-txt-muted">{t('crash.update.badgeStays')}</p>
      </div>
    </ModalShell>
  )
}
