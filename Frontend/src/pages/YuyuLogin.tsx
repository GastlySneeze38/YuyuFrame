import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError, showApiError, showNotice } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import type { Account } from '@/types'

// forgot : demander un code de réinitialisation ; reset : le saisir avec le
// nouveau mot de passe.
type Mode = 'checking' | 'login' | 'register' | 'forgot' | 'reset' | 'error'

export default function YuyuLogin() {
  const t = useT()
  const navigate = useNavigate()
  const { setYuyuSession, setAccounts, setUser } = useStore()

  const [mode, setMode] = useState<Mode>('checking')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    api.yuyu.status()
      .then((s) => setMode(s.has_account ? 'login' : 'register'))
      .catch(() => setMode('error'))
  }, [])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()

    if (mode === 'forgot') {
      setLoading(true)
      try {
        await api.yuyu.forgotPassword(username.trim())
        setPassword('')
        setConfirm('')
        setCode('')
        setMode('reset')
      } catch (err) {
        showApiError(err, t('common.serverUnreachable'))
      } finally {
        setLoading(false)
      }
      return
    }

    if ((mode === 'register' || mode === 'reset') && password !== confirm) {
      showError(t('yuyuLogin.passwordsDontMatch'))
      return
    }

    if (mode === 'reset') {
      setLoading(true)
      try {
        await api.yuyu.resetPassword(username.trim(), code.trim(), password)
        showNotice(t('yuyuLogin.resetDone'))
        setPassword('')
        setConfirm('')
        setCode('')
        setMode('login')
      } catch (err) {
        showApiError(err, t('common.serverUnreachable'))
      } finally {
        setLoading(false)
      }
      return
    }

    if (mode === 'register' && password !== confirm) {
      showError(t('yuyuLogin.passwordsDontMatch'))
      return
    }
    if (password.length < 4) {
      showError(t('yuyuLogin.passwordTooShort'))
      return
    }

    setLoading(true)
    try {
      const resp = mode === 'register'
        ? await api.yuyu.register(username, password, email.trim())
        : await api.yuyu.login(username, password)

      // Les jetons restent côté Rust : ici, seulement de quoi afficher.
      setYuyuSession({
        username: resp.username,
        email: resp.email,
        plan: (resp.plan ?? 'free') as import('@/stores/useStore').YuyuPlan,
        planExpiresAt: resp.plan_expires_at ?? null,
        licenseState: resp.license_state,
        passwordResetRequired: resp.password_reset_required,
        // App.tsx ouvre la fenêtre du code tant que l'e-mail n'est pas confirmé.
        emailVerificationRequired: resp.email_verification_required,
        pendingEmail: resp.pending_email,
      })

      // Populate MC accounts from backend response
      const accs: Account[] = resp.accounts.map((a) => ({
        username: a.mc_username,
        uuid: a.mc_uuid,
        is_offline: a.is_offline,
      }))
      setAccounts(accs)

      // Set active account if one exists
      const active = resp.accounts.find((a) => a.is_active)
      if (active) setUser(active.mc_username, active.mc_uuid, active.is_offline)

      navigate('/home', { replace: true })
    } catch (err) {
      showApiError(err, t('common.serverUnreachable'))
    } finally {
      setLoading(false)
    }
  }

  if (mode === 'checking') {
    return (
      <div className="relative flex h-full items-center justify-center bg-[#09090D]">
        <BackButton onClick={() => navigate('/home')} />
        <div
          className="h-8 w-8 animate-spin-slow rounded-full border-2 border-[rgba(255,255,255,0.1)] border-t-[#4B3FCF]"
        />
      </div>
    )
  }

  if (mode === 'error') {
    return (
      <div className="relative flex h-full items-center justify-center bg-[#09090D]">
        <BackButton onClick={() => navigate('/home')} />
        <div className="flex flex-col items-center gap-4">
          <p className="text-[rgba(255,100,100,0.8)] text-[13px]">
            {t('yuyuLogin.cannotContactBackend')}
          </p>
          <button
            onClick={() => { setMode('checking'); api.yuyu.status().then((s) => setMode(s.has_account ? 'login' : 'register')).catch(() => setMode('error')) }}
            className="rounded-xl px-4 py-2 text-sm text-white transition-all bg-[rgba(75,63,207,0.2)] border border-[rgba(75,63,207,0.4)]"
          >
            {t('yuyuLogin.retry')}
          </button>
        </div>
      </div>
    )
  }

  const isRegister = mode === 'register'
  const isForgot = mode === 'forgot'
  const isReset = mode === 'reset'
  const isRecovery = isForgot || isReset

  const title = isForgot || isReset ? t('yuyuLogin.forgotTitle') : isRegister ? t('yuyuLogin.createAccount') : t('yuyuLogin.login')
  const subtitle = isForgot
    ? t('yuyuLogin.forgotDescription')
    : isReset
      ? t('yuyuLogin.resetDescription')
      : isRegister
        ? t('yuyuLogin.registerEncrypted')
        : t('yuyuLogin.loginEncrypted')
  const submitLabel = isForgot
    ? t('yuyuLogin.forgotSend')
    : isReset
      ? t('yuyuLogin.resetSubmit')
      : isRegister
        ? t('yuyuLogin.createAccountButton')
        : t('yuyuLogin.signIn')
  const canSubmit = isForgot
    ? !!username.trim()
    : isReset
      ? code.length === 6 && !!password
      : isRegister
        ? !!username && !!password && !!email.trim()
        : !!username && !!password

  return (
    <div className="relative flex h-full flex-col items-center justify-center overflow-hidden bg-[#09090D]">
      <BackButton onClick={() => navigate('/home')} />

      {/* Background glow */}
      <div
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_50%_60%,rgba(75,63,207,0.07)_0%,transparent_65%)]"
      />

      <div className="relative z-10 flex w-full max-w-sm flex-col gap-6 px-6">

        {/* Branding */}
        <div className="flex flex-col items-center gap-2">
          <div className="flex items-center gap-2.5">
            <div className="h-5 w-5 rounded-md bg-[#4B3FCF] shadow-[0_0_20px_rgba(75,63,207,0.5)]" />
            <span className="font-black text-white text-[24px] tracking-[-0.02em]">
              YuyuFrame
            </span>
          </div>
          <p className="text-[11px] text-[rgba(255,255,255,0.3)] text-center">
            {isRegister
              ? t('yuyuLogin.registerTagline')
              : t('yuyuLogin.loginTagline')}
          </p>
        </div>

        {/* Card */}
        <div
          className="flex flex-col gap-5 rounded-2xl p-6 bg-[rgba(255,255,255,0.025)] border border-[rgba(255,255,255,0.07)]"
        >
          <div>
            <h2 className="font-bold text-white text-[15px]">{title}</h2>
            <p className="text-[11px] text-[rgba(255,255,255,0.3)] mt-0.5">{subtitle}</p>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-3">
            {/* En réinitialisation, le compte visé est déjà saisi : le
                redemander ouvrirait la porte à une faute de frappe entre la
                demande du code et son usage. */}
            {!isReset && (
              <YuyuInput
                label={isRegister ? t('yuyuLogin.username') : t('yuyuLogin.loginField')}
                type="text"
                value={username}
                onChange={setUsername}
                placeholder={t('yuyuLogin.usernamePlaceholder')}
                autoFocus
              />
            )}
            {isRegister && (
              <YuyuInput
                label={t('yuyuLogin.email')}
                type="email"
                value={email}
                onChange={setEmail}
                placeholder={t('yuyuLogin.emailPlaceholder')}
              />
            )}
            {isReset && (
              <YuyuInput
                label={t('yuyuLogin.resetCode')}
                type="text"
                value={code}
                onChange={(v) => setCode(v.replace(/\D/g, '').slice(0, 6))}
                placeholder="000000"
                autoFocus
              />
            )}
            {!isForgot && (
              <YuyuInput
                label={isReset ? t('yuyuLogin.newPassword') : t('yuyuLogin.password')}
                type="password"
                value={password}
                onChange={setPassword}
                placeholder="••••••••"
              />
            )}
            {(isRegister || isReset) && (
              <YuyuInput
                label={t('yuyuLogin.confirmPassword')}
                type="password"
                value={confirm}
                onChange={setConfirm}
                placeholder="••••••••"
              />
            )}
            {mode === 'login' && (
              <button
                type="button"
                onClick={() => { setMode('forgot'); setPassword('') }}
                className="self-end text-[11px] text-[#7B6EE8] bg-transparent border-0 cursor-pointer p-0"
              >
                {t('yuyuLogin.forgot')}
              </button>
            )}

            <button
              type="submit"
              disabled={loading || !canSubmit}
              className="mt-1 w-full rounded-xl py-3 font-bold text-white transition-all duration-150 active:scale-95 text-[14px] bg-[#4B3FCF] shadow-[0_4px_24px_rgba(75,63,207,0.38)] cursor-pointer enabled:hover:bg-[#6155e8] disabled:bg-[rgba(40,38,65,0.7)] disabled:shadow-none disabled:cursor-not-allowed"
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <span
                    className="h-4 w-4 animate-spin-slow rounded-full border-2 border-[rgba(255,255,255,0.2)] border-t-white"
                  />
                  {isRecovery ? submitLabel : isRegister ? t('yuyuLogin.creating') : t('yuyuLogin.connecting')}
                </span>
              ) : (
                submitLabel
              )}
            </button>
          </form>
        </div>

        {/* Toggle login / register — ou retour, depuis le mot de passe oublié */}
        <div className="flex items-center justify-center gap-1.5">
          {!isRecovery && (
            <span className="text-[11px] text-[rgba(255,255,255,0.3)]">
              {isRegister ? t('yuyuLogin.alreadyHaveAccount') : t('yuyuLogin.noAccountYet')}
            </span>
          )}
          <button
            type="button"
            onClick={() => { setMode(isRegister || isRecovery ? 'login' : 'register'); setConfirm(''); setPassword(''); setCode('') }}
            className="text-[11px] text-[#7B6EE8] bg-transparent border-0 cursor-pointer p-0"
          >
            {isRecovery ? t('yuyuLogin.backToLogin') : isRegister ? t('yuyuLogin.signIn') : t('yuyuLogin.createAccount')}
          </button>
        </div>

        {/* Lock icon + security note */}
        <div className="flex items-center justify-center gap-2">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-[13px] h-[13px] text-[rgba(255,255,255,0.18)]">
            <rect x="3" y="11" width="18" height="11" rx="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
          <span className="text-[10px] text-[rgba(255,255,255,0.18)]">
            {t('yuyuLogin.securityNote')}
          </span>
        </div>
      </div>
    </div>
  )
}

function BackButton({ onClick }: { onClick: () => void }) {
  const t = useT()
  return (
    <button
      onClick={onClick}
      className="absolute left-5 top-5 flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[11px] font-semibold text-[rgba(255,255,255,0.4)] transition-all duration-150 hover:bg-[rgba(255,255,255,0.05)] hover:text-white"
    >
      <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" /></svg>
      {t('yuyuLogin.back')}
    </button>
  )
}

function YuyuInput({
  label, type, value, onChange, placeholder, autoFocus,
}: {
  label: string
  type: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  autoFocus?: boolean
}) {
  return (
    <div>
      <label
        className="mb-1.5 block text-[10px] text-[rgba(255,255,255,0.4)] font-semibold tracking-[0.08em] uppercase"
      >
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoFocus={autoFocus}
        className="w-full rounded-xl px-4 py-3 text-sm text-white outline-none transition-all duration-150 bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.08)] focus:border-[rgba(75,63,207,0.55)]"
      />
    </div>
  )
}
