import { useEffect, useState } from 'react'
import { api, type YuyuEmailResp } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { errorMessage } from '@/lib/apiError'
import { showNotice } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

/**
 * Confirmation de l'e-mail par le code reçu.
 *
 * En mode imposé (`forced`), la fenêtre ne se ferme pas : le serveur refuse
 * tout le reste tant que l'adresse n'est pas confirmée (code
 * `email_verification_required`). Deux sorties restent quand même, sans quoi
 * une faute de frappe à l'inscription enfermerait le joueur : changer
 * d'adresse, et se déconnecter.
 *
 * Un compte d'avant l'e-mail obligatoire n'a pas d'adresse du tout : la
 * fenêtre s'ouvre alors directement sur la saisie de l'adresse.
 */
export function EmailVerifyModal({ forced, onClose }: { forced?: boolean; onClose: () => void }) {
  const t = useT()
  const pendingEmail = useStore((s) => s.yuyuPendingEmail)
  const accountEmail = useStore((s) => s.yuyuEmail)
  const setVerification = useStore((s) => s.setYuyuEmailVerification)
  const clearYuyuSession = useStore((s) => s.clearYuyuSession)

  const target = pendingEmail ?? (forced ? accountEmail : null)
  const [loaded, setLoaded] = useState(!forced)
  const [editing, setEditing] = useState(false)
  const [code, setCode] = useState('')
  const [address, setAddress] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /** Range ce que le serveur vient de dire ; ferme si tout est confirmé. */
  const apply = (resp: YuyuEmailResp) => {
    useStore.setState({ yuyuEmail: resp.email })
    setVerification(resp.verification_required, resp.pending_email)
    return resp
  }

  // Ouverte sur une session déjà là (redémarrage, erreur d'un appel) : on ne
  // sait pas encore à quelle adresse le code est parti, ni même s'il reste
  // quelque chose à confirmer.
  useEffect(() => {
    if (!forced) return
    api.yuyu
      .emailStatus()
      .then(apply)
      .catch(() => {})
      .finally(() => setLoaded(true))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const verify = () =>
    run(async () => {
      const resp = apply(await api.yuyu.verifyEmail(code.trim()))
      if (!resp.pending_email) {
        showNotice(t('emailVerify.verified'))
        onClose()
      }
    })

  const resend = () =>
    run(async () => {
      await api.yuyu.resendEmailCode()
      showNotice(t('emailVerify.resent'))
    })

  const changeAddress = () =>
    run(async () => {
      apply(await api.yuyu.setEmail(address.trim(), password))
      setEditing(false)
      setPassword('')
      setCode('')
    })

  const logout = async () => {
    try {
      await api.yuyu.logout()
    } catch {
      // Déconnexion locale quoi qu'il arrive.
    }
    clearYuyuSession()
  }

  const input = 'rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.04)] px-3 py-2 text-[13px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.6)]'
  const primary =
    'flex items-center gap-2 rounded-xl border border-[rgba(75,63,207,0.35)] bg-[rgba(75,63,207,0.18)] px-5 py-2 text-[13px] font-semibold text-[rgba(180,170,255,0.9)] transition-all duration-150 hover:bg-[rgba(75,63,207,0.3)] disabled:cursor-not-allowed disabled:opacity-50'
  const link = 'text-[12px] text-txt-secondary transition-colors duration-150 hover:text-white disabled:opacity-50'

  // Sans adresse connue, il n'y a aucun code à saisir : on demande l'adresse.
  const askAddress = editing || (loaded && !target)

  return (
    <ModalShell title={t('emailVerify.title')} onClose={forced ? () => {} : onClose} closeOnEscape={!forced}>
      {!loaded ? (
        <p className="text-[12px] text-txt-secondary">{t('common.loading')}</p>
      ) : askAddress ? (
        <>
          <p className="text-[12px] text-txt-secondary">{target ? t('emailVerify.changeDescription') : t('emailVerify.needAddress')}</p>
          <div className="flex flex-col gap-2">
            <input type="email" value={address} onChange={(e) => setAddress(e.target.value)} placeholder={t('emailVerify.newAddress')} className={input} autoFocus />
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t('emailVerify.passwordField')} className={input} />
          </div>
          {error && <p className="text-[12px] text-red-300">{error}</p>}
          <div className="flex items-center justify-between gap-2">
            {target ? (
              <button onClick={() => { setEditing(false); setError(null) }} className={link}>
                {t('common.cancel')}
              </button>
            ) : (
              <button onClick={logout} className={link}>
                {t('emailVerify.logout')}
              </button>
            )}
            <button onClick={changeAddress} disabled={busy || !address.trim() || !password} className={primary}>
              {busy && <ButtonSpinner />}
              {t('emailVerify.sendCode')}
            </button>
          </div>
        </>
      ) : (
        <>
          <p className="text-[12px] text-txt-secondary">{t('emailVerify.description', { email: target ?? '' })}</p>
          <input
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            onKeyDown={(e) => { if (e.key === 'Enter' && code.length === 6 && !busy) verify() }}
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder={t('emailVerify.code')}
            className={`${input} text-center text-[18px] tracking-[0.4em]`}
            autoFocus
          />
          {error && <p className="text-[12px] text-red-300">{error}</p>}
          <div className="flex items-center justify-between gap-3">
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
              <button onClick={resend} disabled={busy} className={link}>
                {t('emailVerify.resend')}
              </button>
              {/* Hors mode imposé, l'adresse se change depuis le compte : le
                  serveur y redemande le second facteur, que cette fenêtre ne
                  sait pas saisir. */}
              {forced && (
                <button onClick={() => { setEditing(true); setError(null) }} disabled={busy} className={link}>
                  {t('emailVerify.changeAddress')}
                </button>
              )}
              {forced ? (
                <button onClick={logout} disabled={busy} className={link}>
                  {t('emailVerify.logout')}
                </button>
              ) : (
                <button onClick={onClose} disabled={busy} className={link}>
                  {t('common.cancel')}
                </button>
              )}
            </div>
            <button onClick={verify} disabled={busy || code.length !== 6} className={primary}>
              {busy && <ButtonSpinner />}
              {t('emailVerify.submit')}
            </button>
          </div>
        </>
      )}
    </ModalShell>
  )
}
