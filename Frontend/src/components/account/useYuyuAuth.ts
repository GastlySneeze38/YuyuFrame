import { useEffect, useRef, useState } from 'react'
import { api, type YuyuSessionResp } from '@/api/client'
import { useStorePick, type YuyuPlan } from '@/stores/useStore'
import { showError, showApiError, showNotice } from '@/stores/useErrorToast'
import { parseApiError } from '@/lib/apiError'
import { useT } from '@/i18n'
import { rememberProvisionalPassword } from '@/components/account/PasswordChangeModal'
import type { Account } from '@/types'

// forgot : demander un code de réinitialisation ; reset : le saisir avec le
// nouveau mot de passe ; mfa : second facteur, après un mot de passe correct.
export type YuyuAuthMode = 'checking' | 'login' | 'register' | 'forgot' | 'reset' | 'mfa' | 'error'

/**
 * Tout ce que fait le formulaire de connexion YuyuFrame, sans son écran : la
 * page `/yuyu` et l'écran d'accueil du premier démarrage (`onboarding/`) en
 * sont deux habillages. Une règle de connexion se change ici, une seule fois.
 *
 * `onSignedIn` part quand la session est ouverte et rangée dans le magasin.
 */
export function useYuyuAuth({ asked, onSignedIn }: { asked?: string | null; onSignedIn: (resp: YuyuSessionResp) => void }) {
  const t = useT()
  // Des actions seulement : stables, donc aucun redessin dû au magasin.
  const { setYuyuSession, setAccounts, setUser } = useStorePick('setYuyuSession', 'setAccounts', 'setUser')

  const [mode, setMode] = useState<YuyuAuthMode>('checking')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  // Entre le mot de passe et le second facteur : le jeton rendu par le
  // serveur, et le moyen attendu.
  const [mfa, setMfa] = useState<{ token: string; byEmail: boolean; emailHint: string | null } | null>(null)
  const [loading, setLoading] = useState(false)
  const loginPasswordRef = useRef('')
  // Inscription : pas de compte sans avoir accepté la politique de
  // confidentialité (le serveur le refuse aussi). Décochée par défaut — un
  // accord ne se présente pas déjà donné.
  const [acceptPrivacy, setAcceptPrivacy] = useState(false)

  /** Connexion ou inscription, d'après ce que ce PC connaît (ou ce qu'on a demandé). */
  const check = () => {
    setMode('checking')
    api.yuyu.status()
      .then((s) => setMode(asked === 'login' || asked === 'register' ? asked : s.has_account ? 'login' : 'register'))
      .catch(() => setMode('error'))
  }

  useEffect(() => {
    check()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Session ouverte (inscription, ou connexion une fois le second facteur passé). */
  const finish = (resp: YuyuSessionResp) => {
    // En mémoire seulement, jamais sur le disque (`PasswordChangeModal`).
    rememberProvisionalPassword(resp.password_reset_required ? loginPasswordRef.current : null)
    loginPasswordRef.current = ''
    // Les jetons restent côté Rust : ici, seulement de quoi afficher.
    setYuyuSession({
      username: resp.username,
      email: resp.email,
      plan: (resp.plan ?? 'free') as YuyuPlan,
      planExpiresAt: resp.plan_expires_at ?? null,
      licenseState: resp.license_state,
      passwordResetRequired: resp.password_reset_required,
      // L'e-mail à confirmer : App.tsx ouvre la fenêtre du code, l'écran
      // d'accueil le demande à sa place.
      emailVerificationRequired: resp.email_verification_required,
      pendingEmail: resp.pending_email,
    })

    const accs: Account[] = resp.accounts.map((a) => ({
      username: a.mc_username,
      uuid: a.mc_uuid,
      is_offline: a.is_offline,
    }))
    setAccounts(accs)
    const active = resp.accounts.find((a) => a.is_active)
    if (active) setUser(active.mc_username, active.mc_uuid, active.is_offline)

    onSignedIn(resp)
  }

  const submit = async () => {
    if (mode === 'mfa') {
      if (!mfa) return
      setLoading(true)
      try {
        finish(await api.yuyu.mfaVerify(mfa.token, code.trim()))
      } catch (err) {
        // Jeton « MFA en attente » périmé (10 minutes) : on repart du mot de
        // passe plutôt que de laisser saisir des codes qui ne passeront plus.
        if (parseApiError(err).code === 'unauthorized') {
          setMfa(null)
          setCode('')
          setMode('login')
        }
        showApiError(err, t('common.serverUnreachable'))
      } finally {
        setLoading(false)
      }
      return
    }

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

    // Seulement quand un mot de passe est CHOISI : à la connexion, un mot de
    // passe plus court datant d'avant la règle doit encore passer.
    if ((mode === 'register' || mode === 'reset') && password.length < 10) {
      showError(t('yuyuLogin.passwordTooShort'))
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

    setLoading(true)
    // Gardé le temps de la connexion (le champ est vidé avant le second
    // facteur) : si c'est un mot de passe provisoire, la fenêtre qui le fait
    // changer l'a déjà, on ne le redemande pas.
    if (mode === 'login') loginPasswordRef.current = password
    try {
      finish(
        mode === 'register'
          ? await api.yuyu.register(username, password, email.trim(), acceptPrivacy)
          : await api.yuyu.login(username, password),
      )
    } catch (err) {
      // Mot de passe correct : le serveur attend le second facteur. Ce n'est
      // pas une erreur à afficher, c'est la suite de la connexion.
      const parsed = parseApiError(err)
      if (parsed.code === 'mfa_required' && typeof parsed.extra.mfa_token === 'string') {
        const methods = Array.isArray(parsed.extra.methods) ? parsed.extra.methods : []
        setMfa({
          token: parsed.extra.mfa_token,
          byEmail: methods.includes('email'),
          emailHint: typeof parsed.extra.email_hint === 'string' ? parsed.extra.email_hint : null,
        })
        setCode('')
        setPassword('')
        setMode('mfa')
      } else {
        showApiError(err, t('common.serverUnreachable'))
      }
    } finally {
      setLoading(false)
    }
  }

  const resendMfa = async () => {
    if (!mfa) return
    try {
      await api.yuyu.mfaResend(mfa.token)
      showNotice(t('mfa.resent'))
    } catch (err) {
      showApiError(err, t('common.serverUnreachable'))
    }
  }

  /** Change d'écran en vidant ce qui ne doit pas suivre. */
  const switchMode = (next: YuyuAuthMode) => {
    setMode(next)
    setConfirm('')
    setPassword('')
    setCode('')
    if (next !== 'mfa') setMfa(null)
  }

  const isRegister = mode === 'register'
  const isForgot = mode === 'forgot'
  const isReset = mode === 'reset'
  const isMfa = mode === 'mfa'
  // Les écrans qui ne sont ni la connexion ni l'inscription.
  const isRecovery = isForgot || isReset || isMfa

  const canSubmit = isMfa
    ? !!code.trim()
    : isForgot
      ? !!username.trim()
      : isReset
        ? code.length === 6 && !!password
        : isRegister
          ? !!username && !!password && !!email.trim() && acceptPrivacy
          : !!username && !!password

  return {
    mode, setMode, switchMode, check,
    username, setUsername, password, setPassword, confirm, setConfirm,
    email, setEmail, code, setCode, mfa, loading,
    acceptPrivacy, setAcceptPrivacy,
    submit, resendMfa,
    isRegister, isForgot, isReset, isMfa, isRecovery, canSubmit,
  }
}
