import { useEffect } from 'react'
import { open } from '@tauri-apps/plugin-shell'
import { ModalShell } from '@/components/ui/ModalShell'
import { useT } from '@/i18n'
import { api } from '@/api/client'

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
export function OfflinePurchaseReminderModal({ onClose }: { onClose: () => void }) {
  const t = useT()

  useEffect(() => {
    api.analytics.track('offline_reminder_shown')
  }, [])

  return (
    <ModalShell title={t('account.reminderTitle')} onClose={onClose} maxWidth="max-w-sm">
      <div className="flex flex-col gap-4">
        <p className="text-[12px] leading-relaxed text-[rgba(255,255,255,0.6)]">
          {t('account.reminderText')}
        </p>

        <button
          onClick={() => { api.analytics.track('offline_reminder_purchase_clicked'); open(PURCHASE_URL) }}
          className="h-10 rounded-xl text-[13px] font-semibold text-white transition-colors bg-[#4B3FCF] hover:bg-[#6155e8]"
        >
          {t('account.buyGame')}
        </button>

        <button
          onClick={onClose}
          className="h-9 rounded-xl text-[12px] text-[rgba(255,255,255,0.4)] transition-colors hover:text-[rgba(255,255,255,0.7)]"
        >
          {t('account.continueWithoutBuying')}
        </button>
      </div>
    </ModalShell>
  )
}
