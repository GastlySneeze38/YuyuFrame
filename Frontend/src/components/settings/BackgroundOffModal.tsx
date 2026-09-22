import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { useT } from '@/i18n'

/**
 * Confirmation avant de couper l'arrière-plan.
 *
 * Ce réglage ne change pas un détail de confort : il débranche trois choses
 * qui dépendent du launcher pour fonctionner. Les énumérer avant le clic vaut
 * mieux que de les voir manquer plus tard sans comprendre pourquoi — un
 * temps de jeu qui ne monte plus, un plantage sans rapport, et personne pour
 * faire le lien avec un interrupteur coché des semaines plus tôt.
 */

const BROKEN = ['stats', 'crash', 'session'] as const

export function BackgroundOffModal({ onConfirm, onCancel }: { onConfirm: () => void; onCancel: () => void }) {
  const t = useT()

  return (
    <ModalShell onClose={onCancel} title={t('settings.lancement.backgroundWarnTitle')}>
      <div className="flex flex-col gap-4">
        <p className="text-[12px] leading-relaxed text-txt-secondary">{t('settings.lancement.backgroundWarnIntro')}</p>

        <ul className="flex flex-col gap-2 rounded-xl border border-warning/30 bg-warning/[0.07] px-3.5 py-3">
          {BROKEN.map((key) => (
            <li key={key} className="flex gap-2.5">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" className="mt-px h-3.5 w-3.5 shrink-0 text-warning">
                <path d="M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" />
              </svg>
              <span className="text-[11.5px] leading-relaxed text-txt-secondary">
                {t(`settings.lancement.backgroundWarn.${key}`)}
              </span>
            </li>
          ))}
        </ul>

        <p className="text-[11.5px] leading-relaxed text-txt-muted">{t('settings.lancement.backgroundWarnLaunch')}</p>

        <div className="flex justify-end gap-2">
          <Button size="sm" variant="secondary" onClick={onCancel}>
            {t('settings.lancement.backgroundWarnKeep')}
          </Button>
          <Button size="sm" variant="danger" onClick={onConfirm}>
            {t('settings.lancement.backgroundWarnConfirm')}
          </Button>
        </div>
      </div>
    </ModalShell>
  )
}
