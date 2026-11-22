import { useState } from 'react'
import { api } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { StepUpField } from '@/components/account/StepUpField'
import { errorMessage } from '@/lib/apiError'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

/**
 * Le mot de passe provisoire tapé à la connexion, gardé **en mémoire
 * seulement** (jamais dans le magasin persisté ni sur le disque) : la fenêtre
 * qui le fait changer n'a pas à le redemander. Effacé dès qu'il a servi, à la
 * déconnexion de la webview, ou à la connexion suivante.
 */
let provisionalPassword: string | null = null
export function rememberProvisionalPassword(password: string | null) {
  provisionalPassword = password || null
}

/**
 * Changement de mot de passe.
 *
 * Avec un **mot de passe provisoire** (donné par le support, drapeau
 * `yuyuPasswordResetRequired`), le premier champ s'appelle « Mot de passe
 * provisoire » — c'est lui que le serveur attend comme mot de passe actuel —
 * et il est déjà rempli quand on vient de se connecter avec. `forced` : la
 * fenêtre ouverte d'office après cette connexion ; elle ne se ferme que par
 * « Plus tard » (`onLater`), la bannière d'accueil et l'écran Compte
 * permettant d'y revenir.
 *
 * Effet de bord voulu côté serveur : les autres appareils sont déconnectés.
 */
export function PasswordChangeModal({ forced, onClose, onLater }: {
  forced?: boolean
  onClose: () => void
  /** Mode imposé : remettre à plus tard. */
  onLater?: () => void
}) {
  const t = useT()
  const provisional = useStore((s) => s.yuyuPasswordResetRequired)
  const setPasswordResetRequired = useStore((s) => s.setYuyuPasswordResetRequired)
  // Lu une fois : le mot de passe provisoire connu, s'il y en a un.
  const [known] = useState(() => (provisional ? provisionalPassword : null))
  const [current, setCurrent] = useState(known ?? '')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  // Second facteur redemandé par le serveur — sauf pour un mot de passe
  // provisoire : le joueur vient de le passer à la connexion.
  const [mfaCode, setMfaCode] = useState('')
  const [needsCode, setNeedsCode] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    if (next !== confirm) {
      setError(t('password.mismatch'))
      return
    }
    if (next.length < 10) {
      setError(t('password.tooShort'))
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.yuyu.changePassword(current, next, mfaCode.trim())
      setPasswordResetRequired(false)
      rememberProvisionalPassword(null)
      onClose()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const field = (value: string, set: (v: string) => void, placeholder: string, autoFocus?: boolean) => (
    <input
      type="password"
      value={value}
      autoFocus={autoFocus}
      onChange={(e) => set(e.target.value)}
      onKeyDown={(e) => { if (e.key === 'Enter') void submit() }}
      placeholder={placeholder}
      className="rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.04)] px-3 py-2 text-[13px] text-white outline-none transition-colors duration-150 focus:border-[rgb(var(--accent)/0.6)] max-sm:h-12 max-sm:text-[16px]"
    />
  )

  const close = forced ? (onLater ?? (() => {})) : onClose

  return (
    <ModalShell
      title={provisional ? t('password.forcedTitle') : t('password.title')}
      onClose={close}
      closeOnEscape={!forced || !!onLater}
    >
      {provisional && (
        <p className="text-[12px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{t('password.forcedDescription')}</p>
      )}
      <div className="flex flex-col gap-2">
        {known ? (
          // Déjà connu : on ne le montre ni ne le redemande.
          <p className="flex items-center gap-2 rounded-xl border border-[rgba(74,222,128,0.25)] bg-[rgba(74,222,128,0.06)] px-3 py-2 text-[12px] text-[rgb(134,239,172)] max-sm:text-[14px]">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4 flex-shrink-0"><path d="M20 6L9 17l-5-5" /></svg>
            {t('password.provisionalKnown')}
          </p>
        ) : (
          field(current, setCurrent, provisional ? t('password.provisionalCurrent') : t('password.current'), true)
        )}
        {field(next, setNext, t('password.new'), !!known)}
        {field(confirm, setConfirm, t('password.confirm'))}
        {!provisional && <StepUpField value={mfaCode} onChange={setMfaCode} onFactor={(f) => setNeedsCode(f !== 'none')} />}
      </div>
      <p className="text-[11px] text-txt-secondary opacity-70 max-sm:text-[13px]">{t('password.otherDevicesNotice')}</p>
      {error && <p className="text-[12px] text-red-300 max-sm:text-[14px]">{error}</p>}
      <div className="flex justify-end gap-2 max-sm:flex-col-reverse">
        {(!forced || onLater) && (
          <button
            onClick={close}
            className="rounded-xl px-4 py-2 text-[13px] text-txt-secondary transition-colors duration-150 hover:text-white max-sm:h-12 max-sm:text-[15px]"
          >
            {forced ? t('password.later') : t('common.cancel')}
          </button>
        )}
        <button
          onClick={submit}
          disabled={busy || !current || !next || (needsCode && !mfaCode.trim())}
          className="flex items-center justify-center gap-2 rounded-xl border border-[rgb(var(--accent)/0.35)] bg-[rgb(var(--accent)/0.18)] px-5 py-2 text-[13px] font-semibold text-[rgb(var(--accent-text)/0.9)] transition-all duration-150 hover:bg-[rgb(var(--accent)/0.3)] disabled:cursor-not-allowed disabled:opacity-50 max-sm:h-12 max-sm:text-[15px]"
        >
          {busy && <ButtonSpinner />}
          {t('password.submit')}
        </button>
      </div>
    </ModalShell>
  )
}
