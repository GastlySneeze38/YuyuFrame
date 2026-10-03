import { useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { api, type YuyuTotpSetupResp } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { StepUpField } from '@/components/account/StepUpField'
import { errorMessage } from '@/lib/apiError'
import { showNotice } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * Application d'authentification du compte.
 *
 * `setup` : l'activer — mot de passe (et second facteur actuel), QR code,
 * premier code, puis les codes de secours. `regen` : refaire les codes de
 * secours contre un code de l'application.
 *
 * Les codes de secours ne sont montrés qu'ici, une fois : le serveur n'en
 * garde que l'empreinte. D'où la dernière étape, qu'on ne peut quitter que
 * par son bouton — fermer par mégarde les perdrait.
 */
export function TotpModal({ mode, onClose }: { mode: 'setup' | 'regen'; onClose: () => void }) {
  const t = useT()
  const [password, setPassword] = useState('')
  const [mfaCode, setMfaCode] = useState('')
  const [needsCode, setNeedsCode] = useState(false)
  const [enrollment, setEnrollment] = useState<YuyuTotpSetupResp | null>(null)
  const [code, setCode] = useState('')
  const [backup, setBackup] = useState<string[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

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

  const begin = () => run(async () => setEnrollment(await api.yuyu.totpSetup(password, mfaCode.trim())))
  const enable = () => run(async () => setBackup(await api.yuyu.totpEnable(code.trim())))
  const regenerate = () => run(async () => setBackup(await api.yuyu.backupCodes(code.trim())))

  const copy = async () => {
    try {
      await navigator.clipboard.writeText((backup ?? []).join('\n'))
      showNotice(t('mfa.backupCopied'))
    } catch {
      // Presse-papiers refusé : les codes restent affichés, à recopier.
    }
  }

  const input = 'rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.04)] px-3 py-2 text-[13px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.6)]'
  const primary =
    'flex items-center gap-2 rounded-xl border border-[rgba(75,63,207,0.35)] bg-[rgba(75,63,207,0.18)] px-5 py-2 text-[13px] font-semibold text-[rgba(180,170,255,0.9)] transition-all duration-150 hover:bg-[rgba(75,63,207,0.3)] disabled:cursor-not-allowed disabled:opacity-50'
  const ghost = 'rounded-xl px-4 py-2 text-[13px] text-txt-secondary transition-colors duration-150 hover:text-white'

  const codeInput = (placeholder: string, onEnter: () => void) => (
    <input
      value={code}
      onChange={(e) => setCode(e.target.value)}
      onKeyDown={(e) => { if (e.key === 'Enter' && code.trim() && !busy) onEnter() }}
      autoComplete="one-time-code"
      placeholder={placeholder}
      className={`${input} text-center text-[16px] tracking-[0.2em]`}
      autoFocus
    />
  )

  // Dernière étape : les codes de secours.
  if (backup) {
    return (
      <ModalShell title={t('mfa.backupTitle')} onClose={() => {}} closeOnEscape={false}>
        <p className="text-[12px] text-txt-secondary">{t('mfa.backupIntro')}</p>
        <div className="grid select-all grid-cols-2 gap-x-6 gap-y-1.5 rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.3)] px-4 py-3 font-mono text-[14px] text-white">
          {backup.map((c) => (
            <span key={c}>{c}</span>
          ))}
        </div>
        <div className="flex justify-end gap-2">
          <button onClick={copy} className={ghost}>
            {t('mfa.backupCopy')}
          </button>
          <button onClick={onClose} className={primary}>
            {t('mfa.backupDone')}
          </button>
        </div>
      </ModalShell>
    )
  }

  if (mode === 'regen') {
    return (
      <ModalShell title={t('mfa.backupTitle')} onClose={onClose}>
        <p className="text-[12px] text-txt-secondary">{t('mfa.backupRegenIntro')}</p>
        {codeInput(t('mfa.stepUpTotp'), regenerate)}
        {error && <p className="text-[12px] text-red-300">{error}</p>}
        <div className="flex justify-end gap-2">
          <button onClick={onClose} className={ghost}>
            {t('common.cancel')}
          </button>
          <button onClick={regenerate} disabled={busy || !code.trim()} className={primary}>
            {busy && <ButtonSpinner />}
            {t('mfa.setupContinue')}
          </button>
        </div>
      </ModalShell>
    )
  }

  return (
    <ModalShell title={t('mfa.setupTitle')} onClose={onClose}>
      {!enrollment ? (
        <>
          <p className="text-[12px] text-txt-secondary">{t('mfa.setupIntro')}</p>
          <div className="flex flex-col gap-2">
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t('emailVerify.passwordField')} className={input} autoFocus />
            <StepUpField value={mfaCode} onChange={setMfaCode} onFactor={(f) => setNeedsCode(f !== 'none')} />
          </div>
          {error && <p className="text-[12px] text-red-300">{error}</p>}
          <div className="flex justify-end gap-2">
            <button onClick={onClose} className={ghost}>
              {t('common.cancel')}
            </button>
            <button onClick={begin} disabled={busy || !password || (needsCode && !mfaCode.trim())} className={primary}>
              {busy && <ButtonSpinner />}
              {t('mfa.setupContinue')}
            </button>
          </div>
        </>
      ) : (
        <>
          <p className="text-[12px] text-txt-secondary">{t('mfa.setupScan')}</p>
          {/* Fond blanc et marge : un QR code posé sur du sombre ne se lit
              pas avec tous les téléphones. */}
          <div className="self-center rounded-xl bg-white p-3">
            <QRCodeSVG value={enrollment.otpauth_url} size={168} />
          </div>
          <code className="select-all break-all rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.3)] px-3 py-2 text-center text-[12px] tracking-wider text-white">
            {enrollment.secret}
          </code>
          {codeInput(t('mfa.setupCode'), enable)}
          {error && <p className="text-[12px] text-red-300">{error}</p>}
          <div className="flex justify-end gap-2">
            <button onClick={onClose} className={ghost}>
              {t('common.cancel')}
            </button>
            <button onClick={enable} disabled={busy || !code.trim()} className={primary}>
              {busy && <ButtonSpinner />}
              {t('mfa.setupEnable')}
            </button>
          </div>
        </>
      )}
    </ModalShell>
  )
}
