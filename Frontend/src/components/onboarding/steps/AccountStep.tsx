import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api, type YuyuEmailResp } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showApiError, showNotice } from '@/stores/useErrorToast'
import { useYuyuAuth } from '@/components/account/useYuyuAuth'
import { PrivacyModal } from '@/components/legal/PrivacyModal'
import { useT } from '@/i18n'
import { EASE, Field, Ghost, Item, Primary, Stagger, TextLink } from '../ui'

/**
 * Le compte YuyuFrame : connexion ou inscription (même logique que `/yuyu`,
 * `useYuyuAuth`), puis la confirmation de l'e-mail sur place — la fenêtre
 * imposée d'App.tsx se tait tant que cet écran est là.
 */
export function AccountStep({ onDone, onSkip }: { onDone: () => void; onSkip: () => void }) {
  const t = useT()
  const [showPrivacy, setShowPrivacy] = useState(false)
  const needsEmail = useStore((s) => s.yuyuSignedIn && s.yuyuEmailVerificationRequired)
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const auth = useYuyuAuth({
    // L'accueil ouvre toujours sur la création de compte, l'onglet de gauche.
    asked: 'register',
    onSignedIn: (resp) => {
      if (!resp.email_verification_required) onDone()
    },
  })
  const { mode, isRegister, isForgot, isReset, isMfa, isRecovery } = auth

  if (signedIn && needsEmail) return <VerifyEmail onDone={onDone} />

  if (mode === 'checking') {
    return (
      <div className="flex h-40 items-center justify-center">
        <span className="h-7 w-7 animate-spin rounded-full border-2 border-white/10 border-t-[rgb(var(--accent))]" />
      </div>
    )
  }
  if (mode === 'error') {
    return (
      <Stagger className="flex flex-col items-center gap-4 py-6 text-center">
        <Item><p className="text-[13px] text-white/70">{t('yuyuLogin.cannotContactBackend')}</p></Item>
        <Item className="flex gap-2">
          <Ghost onClick={onSkip}>{t('onboarding.account.noAccount')}</Ghost>
          <TextLink onClick={auth.check}>{t('yuyuLogin.retry')}</TextLink>
        </Item>
      </Stagger>
    )
  }

  const title = isMfa ? t('mfa.loginTitle') : isForgot || isReset ? t('yuyuLogin.forgotTitle') : isRegister ? t('yuyuLogin.createAccount') : t('yuyuLogin.login')
  const submitLabel = isMfa ? t('mfa.verify') : isForgot ? t('yuyuLogin.forgotSend') : isReset ? t('yuyuLogin.resetSubmit') : isRegister ? t('yuyuLogin.createAccountButton') : t('yuyuLogin.signIn')

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); void auth.submit() }}
      className="flex flex-col gap-4"
    >
      {/* Connexion ↔ inscription : un onglet coulissant, le formulaire
          garde sa place et seuls les champs qui changent entrent/sortent. */}
      {!isRecovery ? (
        <div className="relative grid grid-cols-2 rounded-xl border border-white/10 bg-black/30 p-1">
          {(['register', 'login'] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => auth.switchMode(m)}
              className={`relative z-10 h-9 rounded-lg text-[13px] font-semibold transition-colors duration-200 max-sm:h-10 max-sm:text-[15px] ${mode === m ? 'text-white' : 'text-white/45 hover:text-white/75'}`}
            >
              {mode === m && (
                <motion.span
                  layoutId="ob-auth-tab"
                  className="absolute inset-0 -z-10 rounded-lg bg-[rgb(var(--accent)/0.85)] shadow-[0_4px_18px_rgb(var(--accent)/0.4)]"
                  transition={{ type: 'spring', duration: 0.45, bounce: 0.15 }}
                />
              )}
              {m === 'login' ? t('yuyuLogin.login') : t('yuyuLogin.createAccount')}
            </button>
          ))}
        </div>
      ) : (
        <div>
          <h3 className="text-[16px] font-bold text-white">{title}</h3>
          <p className="mt-1 text-[12.5px] leading-relaxed text-white/55">
            {isMfa
              ? auth.mfa?.byEmail ? t('mfa.loginEmail', { email: auth.mfa.emailHint ?? '' }) : t('mfa.loginTotp')
              : isForgot ? t('yuyuLogin.forgotDescription') : t('yuyuLogin.resetDescription')}
          </p>
        </div>
      )}

      <motion.div layout transition={{ duration: 0.35, ease: EASE }} className="flex flex-col gap-3">
        <AnimatePresence initial={false} mode="popLayout">
          {isMfa && (
            <Reveal key="mfa">
              <Field label={t('mfa.code')} value={auth.code} onChange={auth.setCode} placeholder="000000" autoFocus />
            </Reveal>
          )}
          {!isReset && !isMfa && (
            <Reveal key="user">
              <Field
                label={isRegister ? t('yuyuLogin.username') : t('yuyuLogin.loginField')}
                value={auth.username}
                onChange={auth.setUsername}
                placeholder={t('yuyuLogin.usernamePlaceholder')}
                autoFocus
              />
            </Reveal>
          )}
          {isRegister && (
            <Reveal key="email">
              <Field label={t('yuyuLogin.email')} type="email" inputMode="email" value={auth.email} onChange={auth.setEmail} placeholder={t('yuyuLogin.emailPlaceholder')} />
            </Reveal>
          )}
          {isReset && (
            <Reveal key="reset-code">
              <Field
                label={t('yuyuLogin.resetCode')}
                value={auth.code}
                onChange={(v) => auth.setCode(v.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric"
                placeholder="000000"
                autoFocus
              />
            </Reveal>
          )}
          {!isForgot && !isMfa && (
            <Reveal key="password">
              <Field label={isReset ? t('yuyuLogin.newPassword') : t('yuyuLogin.password')} type="password" value={auth.password} onChange={auth.setPassword} placeholder="••••••••" />
            </Reveal>
          )}
          {(isRegister || isReset) && (
            <Reveal key="confirm">
              <Field label={t('yuyuLogin.confirmPassword')} type="password" value={auth.confirm} onChange={auth.setConfirm} placeholder="••••••••" />
            </Reveal>
          )}
          {isRegister && (
            <Reveal key="privacy">
              <label className="flex cursor-pointer items-start gap-2.5 text-[12px] leading-relaxed text-white/60 max-sm:text-[14px]">
                <input
                  type="checkbox"
                  checked={auth.acceptPrivacy}
                  onChange={(e) => auth.setAcceptPrivacy(e.target.checked)}
                  className="mt-[3px] h-4 w-4 shrink-0 cursor-pointer accent-[rgb(var(--accent))] max-sm:h-5 max-sm:w-5"
                />
                <span>
                  {t('yuyuLogin.privacyAccept')}{' '}
                  <button
                    type="button"
                    onClick={(e) => { e.preventDefault(); setShowPrivacy(true) }}
                    className="text-[rgb(var(--accent-text))] underline underline-offset-2"
                  >
                    {t('yuyuLogin.privacyLink')}
                  </button>
                </span>
              </label>
            </Reveal>
          )}
        </AnimatePresence>
      </motion.div>

      <div className="flex items-center justify-between gap-2">
        {mode === 'login' && <TextLink onClick={() => auth.switchMode('forgot')}>{t('yuyuLogin.forgot')}</TextLink>}
        {isMfa && auth.mfa?.byEmail && <TextLink onClick={() => void auth.resendMfa()}>{t('mfa.resend')}</TextLink>}
        {isRecovery && <TextLink onClick={() => auth.switchMode('login')}>{t('yuyuLogin.backToLogin')}</TextLink>}
      </div>

      <Primary type="submit" disabled={!auth.canSubmit} loading={auth.loading}>
        {submitLabel}
      </Primary>

      <div className="flex justify-center">
        <Ghost onClick={onSkip}>{t('onboarding.account.noAccount')}</Ghost>
      </div>

      <PrivacyModal
        open={showPrivacy}
        onClose={() => setShowPrivacy(false)}
        onAccept={() => { auth.setAcceptPrivacy(true); setShowPrivacy(false) }}
      />
    </form>
  )
}

/** Un champ qui entre ou sort quand on passe de connexion à inscription. */
function Reveal({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: -6, filter: 'blur(3px)' }}
      animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
      exit={{ opacity: 0, y: -4, filter: 'blur(3px)', transition: { duration: 0.16 } }}
      transition={{ duration: 0.35, ease: EASE }}
    >
      {children}
    </motion.div>
  )
}

/** Le code reçu par e-mail, juste après l'inscription. */
function VerifyEmail({ onDone }: { onDone: () => void }) {
  const t = useT()
  const pending = useStore((s) => s.yuyuPendingEmail)
  const email = useStore((s) => s.yuyuEmail)
  const setVerification = useStore((s) => s.setYuyuEmailVerification)
  const clearYuyuSession = useStore((s) => s.clearYuyuSession)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)

  const apply = (resp: YuyuEmailResp) => {
    useStore.setState({ yuyuEmail: resp.email })
    setVerification(resp.verification_required, resp.pending_email)
    return resp
  }

  const verify = async () => {
    setBusy(true)
    try {
      const resp = apply(await api.yuyu.verifyEmail(code.trim()))
      if (!resp.verification_required) {
        showNotice(t('emailVerify.verified'))
        onDone()
      }
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      setBusy(false)
    }
  }

  const resend = async () => {
    try {
      await api.yuyu.resendEmailCode()
      showNotice(t('emailVerify.resent'))
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    }
  }

  const logout = async () => {
    await api.yuyu.logout().catch(() => {})
    clearYuyuSession()
  }

  return (
    <Stagger className="flex flex-col gap-4">
      <Item>
        <h3 className="text-[16px] font-bold text-white">{t('onboarding.account.verifyTitle')}</h3>
        <p className="mt-1 text-[12.5px] leading-relaxed text-white/60 max-sm:text-[14px]">
          {t('onboarding.account.verifyText', { email: pending ?? email ?? '' })}
        </p>
      </Item>
      <Item>
        <Field
          label={t('mfa.code')}
          value={code}
          onChange={(v) => setCode(v.replace(/\D/g, '').slice(0, 6))}
          inputMode="numeric"
          placeholder="000000"
          autoFocus
          onEnter={() => { if (code.length === 6) void verify() }}
        />
      </Item>
      <Item>
        <Primary onClick={() => void verify()} disabled={code.length !== 6} loading={busy}>
          {t('onboarding.account.verify')}
        </Primary>
      </Item>
      <Item className="flex items-center justify-between">
        <TextLink onClick={() => void resend()}>{t('onboarding.account.resend')}</TextLink>
        <Ghost onClick={() => void logout()}>{t('onboarding.account.wrongAccount')}</Ghost>
      </Item>
    </Stagger>
  )
}
