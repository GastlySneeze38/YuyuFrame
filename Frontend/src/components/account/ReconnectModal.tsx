import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { QueuedModalProps } from '@/stores/useModalQueue'
import { useT } from '@/i18n'

/** Affichée au démarrage quand authSystemVersion (persisté) est en retard sur
 * AUTH_SYSTEM_VERSION (config/authVersion.ts) — voir App.tsx. Le compte
 * Microsoft actif est délogué côté backend (session locale invalidée par le
 * changement) et l'utilisateur est renvoyé sur /login pour le reconnecter
 * via le flow device code déjà en place, plutôt que de dupliquer l'UI ici. */
export function ReconnectModal({ onClose, counter }: QueuedModalProps) {
  const t = useT()
  const navigate = useNavigate()
  const clearUser = useStore((s) => s.clearUser)
  const [reconnecting, setReconnecting] = useState(false)

  const handleReconnect = async () => {
    setReconnecting(true)
    try { await api.auth.logout() } catch { /* best-effort — on continue même si le logout backend échoue */ }
    clearUser()
    onClose()
    navigate('/login')
  }

  return (
    <ModalShell title={t('reconnect.title')} onClose={onClose} counter={counter}>
      <div className="flex flex-col gap-5">
        <p className="text-[13px] leading-relaxed text-txt-secondary">
          {t('reconnect.description')}
        </p>

        {/* Les deux boutons passent par `Button` : l'action principale y est
            un aplat plein, le refus un bouton bordé. Écrits à la main, ils
            étaient deux nuances de sombre qu'on ne distinguait pas. */}
        <div className="flex items-center gap-2.5">
          <Button variant="primary" onClick={handleReconnect} loading={reconnecting} className="flex-1">
            {reconnecting ? t('reconnect.reconnecting') : t('reconnect.button')}
          </Button>
          <Button variant="ghost" onClick={onClose}>
            {t('reconnect.later')}
          </Button>
        </div>
      </div>
    </ModalShell>
  )
}
