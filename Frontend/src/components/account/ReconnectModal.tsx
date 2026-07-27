import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

/** Affichée au démarrage quand authSystemVersion (persisté) est en retard sur
 * AUTH_SYSTEM_VERSION (config/authVersion.ts) — voir App.tsx. Le compte
 * Microsoft actif est délogué côté backend (session locale invalidée par le
 * changement) et l'utilisateur est renvoyé sur /login pour le reconnecter
 * via le flow device code déjà en place, plutôt que de dupliquer l'UI ici. */
export function ReconnectModal({ onClose }: { onClose: () => void }) {
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
    <ModalShell title={t('reconnect.title')} onClose={onClose} maxWidth="max-w-sm">
      <div className="flex flex-col gap-4">
        <p className="text-[12px] leading-relaxed text-[rgba(255,255,255,0.6)]">
          {t('reconnect.description')}
        </p>

        <button
          onClick={handleReconnect}
          disabled={reconnecting}
          className="h-10 rounded-xl text-[13px] font-semibold text-white transition-colors bg-[#4B3FCF] hover:bg-[#6155e8] disabled:cursor-not-allowed disabled:opacity-60"
        >
          {reconnecting ? t('reconnect.reconnecting') : t('reconnect.button')}
        </button>

        <button
          onClick={onClose}
          className="h-9 rounded-xl text-[12px] text-[rgba(255,255,255,0.4)] transition-colors hover:text-[rgba(255,255,255,0.7)]"
        >
          {t('reconnect.later')}
        </button>
      </div>
    </ModalShell>
  )
}
