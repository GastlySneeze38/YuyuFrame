import { useEffect } from 'react'
import { open } from '@tauri-apps/plugin-shell'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { useT } from '@/i18n'
import { api } from '@/api/client'
import type { QueuedModalProps } from '@/stores/useModalQueue'

const PURCHASE_URL = 'https://www.minecraft.net/fr-fr/store/minecraft-java-bedrock-edition-pc'

/** Rappel affiché au lancement de l'app quand le compte actif est un compte
 * hors ligne (voir isOfflineAccount) — n'empêche rien, juste un rappel
 * respectueux du travail de Mojang, affiché une fois par lancement (voir
 * App.tsx, useEffect sans dépendances).
 *
 * Le clic sur "Acheter" ouvre le store Mojang externe — pas de webhook
 * possible pour savoir si l'achat a abouti. Le proxy de conversion se
 * construit côté PostHog : `offline_reminder_purchase_clicked` puis, plus
 * tard, `microsoft_account_added` sur le même distinct_id (device) = ce
 * poste crack a fini par ajouter un vrai compte Microsoft. */
export function OfflinePurchaseReminderModal({ onClose, counter }: QueuedModalProps) {
  const t = useT()

  useEffect(() => {
    api.analytics.track('offline_reminder_shown')
  }, [])

  return (
    <ModalShell title={t('account.reminderTitle')} onClose={onClose} counter={counter}>
      <div className="flex flex-col gap-5">
        <p className="text-[13px] leading-relaxed text-txt-secondary">
          {t('account.reminderText')}
        </p>

        <div className="flex items-center gap-2.5">
          <Button
            variant="primary"
            onClick={() => { api.analytics.track('offline_reminder_purchase_clicked'); open(PURCHASE_URL) }}
            className="flex-1"
          >
            {t('account.buyGame')}
          </Button>
          <Button variant="ghost" onClick={onClose}>
            {t('account.continueWithoutBuying')}
          </Button>
        </div>
      </div>
    </ModalShell>
  )
}
