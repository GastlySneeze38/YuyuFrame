import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import type { QueuedModalProps } from '@/stores/useModalQueue'
import { useT } from '@/i18n'

/** Affichée une fois, au premier démarrage d'une version qui monte
 * YUYU_AUTH_VERSION (config/authVersion.ts) : le serveur a fermé toutes les
 * sessions YuyuFrame, et `App.tsx` a déjà oublié celle de ce launcher avant
 * de déposer la fenêtre. Elle ne déconnecte donc rien elle-même : elle dit
 * pourquoi, et mène à l'écran de connexion — dans le bon mode, pour que
 * « créer un compte » n'atterrisse pas sur le formulaire de connexion.
 *
 * « Plus tard » reste possible : le launcher se joue sans compte YuyuFrame,
 * seuls l'abonnement et la synchronisation l'attendent. */
export function YuyuReconnectModal({ onClose, counter }: QueuedModalProps) {
  const t = useT()
  const navigate = useNavigate()

  const go = (mode: 'login' | 'register') => {
    onClose()
    navigate(`/yuyu?mode=${mode}`)
  }

  return (
    <ModalShell title={t('yuyuReconnect.title')} onClose={onClose} counter={counter}>
      <div className="flex flex-col gap-5">
        <p className="text-[13px] leading-relaxed text-txt-secondary">{t('yuyuReconnect.description')}</p>
        <div className="flex flex-col gap-2.5">
          <Button variant="primary" onClick={() => go('login')}>
            {t('yuyuReconnect.signIn')}
          </Button>
          <div className="flex items-center gap-2.5">
            <Button variant="ghost" onClick={() => go('register')} className="flex-1">
              {t('yuyuReconnect.register')}
            </Button>
            <Button variant="ghost" onClick={onClose}>
              {t('yuyuReconnect.later')}
            </Button>
          </div>
        </div>
      </div>
    </ModalShell>
  )
}
