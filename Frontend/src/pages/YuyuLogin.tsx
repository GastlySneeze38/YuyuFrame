import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useT } from '@/i18n'
import { PrivacyModal } from '@/components/legal/PrivacyModal'
import { useYuyuAuth } from '@/components/account/useYuyuAuth'

export default function YuyuLogin() {
  const t = useT()
  const navigate = useNavigate()
  const [showPrivacy, setShowPrivacy] = useState(false)

  // `?mode=login|register` : la fenêtre de reconnexion (YuyuReconnectModal)
  // mène ici en ayant déjà demandé lequel des deux. Sans elle, on devine
  // d'après ce que ce PC connaît. Toute la logique vit dans `useYuyuAuth`,
  // partagée avec l'écran d'accueil du premier démarrage.
  const [params] = useSearchParams()
  const auth = useYuyuAuth({ asked: params.get('mode'), onSignedIn: () => navigate('/home', { replace: true }) })
  const {
    mode, username, setUsername, password, setPassword, confirm, setConfirm, email, setEmail,
    code, setCode, mfa, loading, acceptPrivacy, setAcceptPrivacy, resendMfa,
    isRegister, isForgot, isReset, isMfa, isRecovery, canSubmit,
  } = auth

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    void auth.submit()
  }

  if (mode === 'checking') {
    return (
      <div className="relative flex h-full items-center justify-center bg-[rgb(var(--bg-primary))]">
        <BackButton onClick={() => navigate('/home')} />
        <div
          className="h-8 w-8 animate-spin-slow rounded-full border-2 border-[rgba(255,255,255,0.1)] border-t-[rgb(var(--accent))]"
        />
      </div>
    )
  }

  if (mode === 'error') {
    return (
      <div className="relative flex h-full items-center justify-center bg-[rgb(var(--bg-primary))]">
        <BackButton onClick={() => navigate('/home')} />
        <div className="flex flex-col items-center gap-4">
          <p className="text-[rgba(255,100,100,0.8)] text-[13px]">
            {t('yuyuLogin.cannotContactBackend')}
          </p>
          <button
            onClick={auth.check}
            className="rounded-xl px-4 py-2 text-sm text-white transition-all bg-[rgb(var(--accent)/0.2)] border border-[rgb(var(--accent)/0.4)]"
          >
            {t('yuyuLogin.retry')}
          </button>
        </div>
      </div>
    )
  }

  const title = isMfa ? t('mfa.loginTitle') : isForgot || isReset ? t('yuyuLogin.forgotTitle') : isRegister ? t('yuyuLogin.createAccount') : t('yuyuLogin.login')
  const subtitle = isMfa
    ? mfa?.byEmail
      ? t('mfa.loginEmail', { email: mfa.emailHint ?? '' })
      : t('mfa.loginTotp')
    : isForgot
      ? t('yuyuLogin.forgotDescription')
      : isReset
        ? t('yuyuLogin.resetDescription')
        : isRegister
          ? t('yuyuLogin.registerEncrypted')
          : t('yuyuLogin.loginEncrypted')
  const submitLabel = isMfa
    ? t('mfa.verify')
    : isForgot
      ? t('yuyuLogin.forgotSend')
      : isReset
        ? t('yuyuLogin.resetSubmit')
        : isRegister
          ? t('yuyuLogin.createAccountButton')
          : t('yuyuLogin.signIn')
  return (
    // Téléphone : une colonne qui défile au lieu d'un bloc centré — le clavier
    // ouvert prend la moitié de l'écran, le formulaire doit rester atteignable.
    <div className="relative flex h-full flex-col items-center justify-center overflow-hidden bg-[rgb(var(--bg-primary))] max-sm:justify-start max-sm:overflow-y-auto max-sm:overscroll-contain max-sm:pb-[max(32px,env(safe-area-inset-bottom))] max-sm:pt-[max(64px,calc(env(safe-area-inset-top)+56px))]">
      <BackButton onClick={() => navigate('/home')} />

      {/* Background glow */}
      <div
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_50%_60%,rgb(var(--accent)/0.07)_0%,transparent_65%)]"
      />

      <div className="relative z-10 flex w-full max-w-sm flex-col gap-6 px-6 max-sm:px-4">

        {/* Branding */}
        <div className="flex flex-col items-center gap-2">
          <div className="flex items-center gap-2.5">
            <div className="h-5 w-5 rounded-md bg-[rgb(var(--accent))] shadow-[0_0_20px_rgb(var(--accent)/0.5)]" />
            <span className="font-black text-white text-[24px] tracking-[-0.02em]">
              YuyuFrame
            </span>
          </div>
          <p className="text-[11px] text-[rgba(255,255,255,0.3)] text-center max-sm:text-[15px] max-sm:text-white/60">
            {isRegister
              ? t('yuyuLogin.registerTagline')
              : t('yuyuLogin.loginTagline')}
          </p>
        </div>

        {/* Card */}
        <div
          className="flex flex-col gap-5 rounded-2xl p-6 bg-[rgba(255,255,255,0.025)] border border-[rgba(255,255,255,0.07)] max-sm:p-5"
        >
          <div>
            <h2 className="font-bold text-white text-[15px] max-sm:text-[18px]">{title}</h2>
            <p className="text-[11px] text-[rgba(255,255,255,0.3)] mt-0.5 max-sm:text-[13px] max-sm:text-white/55">{subtitle}</p>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-3">
            {/* En réinitialisation, le compte visé est déjà saisi : le
                redemander ouvrirait la porte à une faute de frappe entre la
                demande du code et son usage. */}
            {isMfa && (
              <>
                {/* Pas de filtre sur les chiffres : un code de secours
                    contient des lettres. */}
                <YuyuInput label={t('mfa.code')} type="text" value={code} onChange={setCode} placeholder="000000" autoFocus />
                {mfa?.byEmail && (
                  <button
                    type="button"
                    onClick={resendMfa}
                    className="self-end text-[11px] text-[rgb(var(--accent-hover))] bg-transparent border-0 cursor-pointer p-0 max-sm:min-h-[44px] max-sm:text-[15px]"
                  >
                    {t('mfa.resend')}
                  </button>
                )}
              </>
            )}
            {!isReset && !isMfa && (
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
            {!isForgot && !isMfa && (
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
            {isRegister && (
              <label className="flex cursor-pointer items-start gap-2.5 text-[11.5px] leading-relaxed text-[rgba(255,255,255,0.55)] max-sm:gap-3 max-sm:py-1 max-sm:text-[15px] max-sm:text-white/70">
                <input
                  type="checkbox"
                  checked={acceptPrivacy}
                  onChange={(e) => setAcceptPrivacy(e.target.checked)}
                  className="mt-[3px] h-3.5 w-3.5 shrink-0 cursor-pointer accent-[rgb(var(--accent))] max-sm:h-5 max-sm:w-5"
                />
                <span>
                  {t('yuyuLogin.privacyAccept')}{' '}
                  {/* Un bouton et non un lien vers la page : y naviguer
                      viderait le formulaire. `preventDefault` : sans lui, le
                      clic remonterait au label et cocherait la case. */}
                  <button
                    type="button"
                    onClick={(e) => { e.preventDefault(); setShowPrivacy(true) }}
                    className="cursor-pointer border-0 bg-transparent p-0 text-[rgb(var(--accent-hover))] underline underline-offset-2"
                  >
                    {t('yuyuLogin.privacyLink')}
                  </button>
                </span>
              </label>
            )}
            {mode === 'login' && (
              <button
                type="button"
                onClick={() => auth.switchMode('forgot')}
                className="self-end text-[11px] text-[rgb(var(--accent-hover))] bg-transparent border-0 cursor-pointer p-0 max-sm:min-h-[44px] max-sm:text-[15px]"
              >
                {t('yuyuLogin.forgot')}
              </button>
            )}

            <button
              type="submit"
              disabled={loading || !canSubmit}
              className="mt-1 w-full rounded-xl py-3 font-bold text-white transition-all duration-150 active:scale-95 text-[14px] max-sm:h-12 max-sm:rounded-2xl max-sm:py-0 max-sm:text-[16px] bg-[rgb(var(--accent))] shadow-[0_4px_24px_rgb(var(--accent)/0.38)] cursor-pointer enabled:hover:bg-[rgb(var(--accent-hover))] disabled:bg-[rgba(40,38,65,0.7)] disabled:shadow-none disabled:cursor-not-allowed"
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
        <div className="flex flex-wrap items-center justify-center gap-x-1.5">
          {!isRecovery && (
            <span className="text-[11px] text-[rgba(255,255,255,0.3)] max-sm:text-[15px] max-sm:text-white/55">
              {isRegister ? t('yuyuLogin.alreadyHaveAccount') : t('yuyuLogin.noAccountYet')}
            </span>
          )}
          <button
            type="button"
            onClick={() => auth.switchMode(isRegister || isRecovery ? 'login' : 'register')}
            className="text-[11px] text-[rgb(var(--accent-hover))] bg-transparent border-0 cursor-pointer p-0 max-sm:min-h-[44px] max-sm:text-[15px] max-sm:font-semibold"
          >
            {isRecovery ? t('yuyuLogin.backToLogin') : isRegister ? t('yuyuLogin.signIn') : t('yuyuLogin.createAccount')}
          </button>
        </div>

        <PrivacyModal
          open={showPrivacy}
          onClose={() => setShowPrivacy(false)}
          onAccept={() => { setAcceptPrivacy(true); setShowPrivacy(false) }}
        />

        {/* Lock icon + security note */}
        <div className="flex items-center justify-center gap-2">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-[13px] h-[13px] text-[rgba(255,255,255,0.18)]">
            <rect x="3" y="11" width="18" height="11" rx="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
          <span className="text-[10px] text-[rgba(255,255,255,0.18)] max-sm:text-[13px] max-sm:text-white/45">
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
      // Téléphone : sous la barre d'état, 44 px de haut, la couleur des retours des autres écrans.
      className="absolute left-5 top-5 z-20 flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[11px] font-semibold text-[rgba(255,255,255,0.4)] transition-all duration-150 hover:bg-[rgba(255,255,255,0.05)] hover:text-white max-sm:left-2 max-sm:top-[max(8px,env(safe-area-inset-top))] max-sm:h-11 max-sm:text-[15px] max-sm:text-[rgb(var(--accent-text))] max-sm:[&>svg]:h-5 max-sm:[&>svg]:w-5"
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
        className="mb-1.5 block text-[10px] text-[rgba(255,255,255,0.4)] font-semibold tracking-[0.08em] uppercase max-sm:text-[13px] max-sm:tracking-[0.05em] max-sm:text-white/55"
      >
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoFocus={autoFocus}
        // 16 px sur téléphone : en dessous, iOS agrandit la page à la saisie.
        autoCapitalize={type === 'text' || type === 'email' ? 'none' : undefined}
        autoCorrect="off"
        className="w-full rounded-xl px-4 py-3 text-sm text-white outline-none transition-all duration-150 bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.08)] focus:border-[rgb(var(--accent)/0.55)] max-sm:h-12 max-sm:py-0 max-sm:text-[16px]"
      />
    </div>
  )
}
