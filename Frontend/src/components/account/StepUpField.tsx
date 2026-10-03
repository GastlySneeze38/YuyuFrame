import { useEffect, useState } from 'react'
import { api, type YuyuSecondFactor } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { showNotice } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * Second facteur redemandé avant une action sensible (changer l'e-mail, le
 * mot de passe, activer l'application).
 *
 * Le champ se règle sur le compte : un compte à application saisit le code
 * de son application (ou un code de secours), les autres demandent d'abord un
 * code par e-mail. Sans second facteur disponible, il n'y a rien à afficher —
 * et le formulaire ne doit pas l'attendre : `onFactor` dit au parent s'il
 * faut un code.
 */
export function StepUpField({
  value,
  onChange,
  onFactor,
}: {
  value: string
  onChange: (code: string) => void
  onFactor: (factor: YuyuSecondFactor) => void
}) {
  const t = useT()
  const [factor, setFactor] = useState<YuyuSecondFactor | null>(null)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.yuyu
      .mfaStatus()
      .then((s) => {
        setFactor(s.second_factor)
        onFactor(s.second_factor)
      })
      // Serveur injoignable : on laisse le formulaire partir, c'est lui qui
      // dira ce qui manque.
      .catch(() => {
        setFactor('none')
        onFactor('none')
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!factor || factor === 'none') return null

  const send = async () => {
    setSending(true)
    setError(null)
    try {
      await api.yuyu.mfaStepUp()
      showNotice(t('mfa.stepUpSent'))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="flex flex-col gap-1">
      <div className="flex gap-2">
        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          autoComplete="one-time-code"
          placeholder={factor === 'totp' ? t('mfa.stepUpTotp') : t('mfa.stepUpEmail')}
          className="min-w-0 flex-1 rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.04)] px-3 py-2 text-[13px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.6)]"
        />
        {factor === 'email' && (
          <button
            type="button"
            onClick={send}
            disabled={sending}
            className="flex-shrink-0 rounded-xl border border-[rgba(255,255,255,0.08)] px-3 py-2 text-[12px] text-txt-secondary transition-colors duration-150 hover:text-white disabled:opacity-50"
          >
            {t('mfa.stepUpSend')}
          </button>
        )}
      </div>
      {error && <p className="text-[12px] text-red-300">{error}</p>}
    </div>
  )
}
