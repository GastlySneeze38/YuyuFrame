import { useState } from 'react'
import { api } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { errorMessage } from '@/lib/apiError'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

/**
 * Changement de mot de passe. En mode imposé (`forced`), la modale ne se
 * ferme pas : le support a donné un mot de passe provisoire, et le serveur
 * refuse tout le reste tant qu'il n'a pas été changé (code
 * `password_change_required`).
 *
 * Effet de bord voulu côté serveur : les autres appareils sont déconnectés.
 */
export function PasswordChangeModal({ forced, onClose }: { forced?: boolean; onClose: () => void }) {
  const t = useT()
  const setPasswordResetRequired = useStore((s) => s.setYuyuPasswordResetRequired)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    if (next !== confirm) {
      setError(t('password.mismatch'))
      return
    }
    if (next.length < 8) {
      setError(t('password.tooShort'))
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.yuyu.changePassword(current, next)
      setPasswordResetRequired(false)
      onClose()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const field = (value: string, set: (v: string) => void, placeholder: string) => (
    <input
      type="password"
      value={value}
      onChange={(e) => set(e.target.value)}
      placeholder={placeholder}
      className="rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.04)] px-3 py-2 text-[13px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.6)]"
    />
  )

  return (
    <ModalShell
      title={forced ? t('password.forcedTitle') : t('password.title')}
      onClose={forced ? () => {} : onClose}
      closeOnEscape={!forced}
    >
      {forced && <p className="text-[12px] text-txt-secondary">{t('password.forcedDescription')}</p>}
      <div className="flex flex-col gap-2">
        {field(current, setCurrent, t('password.current'))}
        {field(next, setNext, t('password.new'))}
        {field(confirm, setConfirm, t('password.confirm'))}
      </div>
      <p className="text-[11px] text-txt-secondary opacity-70">{t('password.otherDevicesNotice')}</p>
      {error && <p className="text-[12px] text-red-300">{error}</p>}
      <div className="flex justify-end gap-2">
        {!forced && (
          <button
            onClick={onClose}
            className="rounded-xl px-4 py-2 text-[13px] text-txt-secondary transition-colors duration-150 hover:text-white"
          >
            {t('common.cancel')}
          </button>
        )}
        <button
          onClick={submit}
          disabled={busy || !current || !next}
          className="flex items-center gap-2 rounded-xl border border-[rgba(75,63,207,0.35)] bg-[rgba(75,63,207,0.18)] px-5 py-2 text-[13px] font-semibold text-[rgba(180,170,255,0.9)] transition-all duration-150 hover:bg-[rgba(75,63,207,0.3)] disabled:cursor-not-allowed disabled:opacity-50"
        >
          {busy && <ButtonSpinner />}
          {t('password.submit')}
        </button>
      </div>
    </ModalShell>
  )
}
