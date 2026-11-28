import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { YuyuDevice, YuyuMfaResp } from '@/api/client'
import { useStore, useStorePick } from '@/stores/useStore'
import { Button } from '@/components/ui/Button'
import { Field } from '@/components/ui/Field'
import { MaskedEmail } from '@/components/ui/MaskedEmail'
import { PlanBadge } from '@/components/plans/PlanBadge'
import { PasswordChangeModal } from '@/components/account/PasswordChangeModal'
import { EmailVerifyModal } from '@/components/account/EmailVerifyModal'
import { TotpModal } from '@/components/account/TotpModal'
import { LinkDeviceModal } from '@/components/account/LinkDeviceModal'
import { StepUpField } from '@/components/account/StepUpField'
import { showApiError, showNotice } from '@/stores/useErrorToast'
import { errorMessage } from '@/lib/apiError'
import { fadeVariants, listItemVariants, listVariants, transition } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * Administration du compte YuyuFrame : abonnement, e-mail, mot de passe et
 * appareils connectés. Tout ce que le serveur expose sous `/v1/me`.
 *
 * Chaque bloc se déplie sur place plutôt que d'ouvrir une page : la gestion
 * d'un compte, c'est quelques actions rares, elles n'ont pas besoin d'un
 * écran chacune.
 *
 * Trois parties qu'on doit distinguer d'un coup d'œil (2026-11-28) : qui est
 * connecté et avec quel abonnement (en tête), la sécurité, les appareils —
 * chacune dans son cadre, sous un titre. Une ligne = une icône, un titre
 * lisible et une description qui passe à la ligne : la colonne fait 375 px
 * sur téléphone et ~420 sur PC, couper les textes les rendait illisibles. La
 * déconnexion est en bas, seule : en tête, elle se disputait la place avec le
 * pseudo.
 */
export function YuyuAccountPanel() {
  const t = useT()
  const navigate = useNavigate()
  const {
    yuyuSignedIn,
    yuyuUsername,
    yuyuEmail,
    yuyuPlan,
    yuyuPlanExpiresAt,
    yuyuLicenseState,
    clearYuyuSession,
  } = useStorePick('yuyuSignedIn', 'yuyuUsername', 'yuyuEmail', 'yuyuPlan', 'yuyuPlanExpiresAt', 'yuyuLicenseState', 'clearYuyuSession')
  const [section, setSection] = useState<'email' | 'devices' | null>(null)
  const [showPassword, setShowPassword] = useState(false)
  // Un code (QR et texte) pour connecter un autre appareil sans mot de passe.
  const [showLink, setShowLink] = useState(false)
  const [showVerify, setShowVerify] = useState(false)
  const verificationRequired = useStore((s) => s.yuyuEmailVerificationRequired)
  const passwordProvisional = useStore((s) => s.yuyuPasswordResetRequired)
  const [mfa, setMfa] = useState<YuyuMfaResp | null>(null)
  const [totpMode, setTotpMode] = useState<'setup' | 'regen' | null>(null)

  // Silencieux : sans réponse, la ligne ne s'affiche pas, c'est tout.
  const loadMfa = () => {
    api.yuyu.mfaStatus().then(setMfa).catch(() => setMfa(null))
  }
  // Relu quand l'e-mail vient d'être confirmé : le second facteur en dépend.
  useEffect(() => {
    if (yuyuSignedIn && !verificationRequired) loadMfa()
    else setMfa(null)
  }, [yuyuSignedIn, verificationRequired])

  if (!yuyuSignedIn) return <SignedOutCard onSignIn={() => navigate('/yuyu')} />

  const toggle = (next: 'email' | 'devices') => setSection((s) => (s === next ? null : next))

  const handleLogout = async () => {
    try {
      await api.yuyu.logout()
    } catch {
      // Déconnexion locale quoi qu'il arrive.
    }
    clearYuyuSession()
    showNotice(t('account.loggedOut'))
  }

  const planValue =
    yuyuPlan === 'free'
      ? t('account.freePlan')
      : yuyuPlanExpiresAt
        ? t('account.until', { date: new Date(yuyuPlanExpiresAt * 1000).toLocaleDateString() })
        : t('account.noEnd')

  return (
    <>
      <motion.div variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-5">
        {/* ── Qui est connecté, et avec quel abonnement ── */}
        <motion.section variants={listItemVariants} className="overflow-hidden rounded-2xl border border-line bg-surface-1">
          <div className="flex items-center gap-3 p-4">
            <div className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl bg-[linear-gradient(135deg,rgb(var(--accent)/0.6),rgb(var(--accent)/0.2))] text-[17px] font-black text-white max-sm:h-12 max-sm:w-12 max-sm:text-[19px]">
              {(yuyuUsername ?? '?')[0].toUpperCase()}
            </div>
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <div className="flex min-w-0 items-center gap-2">
                <p className="truncate text-[15px] font-bold text-txt-primary max-sm:text-[17px]">{yuyuUsername}</p>
                <span className="shrink-0">
                  <PlanBadge plan={yuyuPlan} />
                </span>
              </div>
              <p className="truncate text-[11.5px] text-txt-muted max-sm:text-[13px]">{t('account.yuyuAccount')}</p>
            </div>
          </div>
          <div className="flex items-center gap-3 border-t border-line-soft bg-white/[0.02] px-4 py-3">
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="text-[10.5px] font-semibold uppercase tracking-[0.08em] text-txt-muted max-sm:text-[11.5px]">
                {t('account.subscription')}
              </span>
              <span className="text-[13px] font-medium leading-snug text-txt-primary max-sm:text-[15px]">{planValue}</span>
            </div>
            <Button size="sm" variant={yuyuPlan === 'free' ? 'primary' : 'secondary'} className="shrink-0" onClick={() => navigate('/plans')}>
              {yuyuPlan === 'free' ? t('account.seePlans') : t('account.manage')}
            </Button>
          </div>
        </motion.section>

        {/* Licence hors ligne épuisée : le joueur doit savoir pourquoi son
            abonnement va retomber en gratuit. */}
        <AnimatePresence>
          {yuyuLicenseState === 'grace' && (
            <motion.p
              variants={fadeVariants}
              initial="initial"
              animate="animate"
              exit="exit"
              className="rounded-xl border border-warning/40 bg-warning/10 px-3.5 py-2.5 text-[11.5px] leading-relaxed text-warning max-sm:px-4 max-sm:py-3 max-sm:text-[14px]"
            >
              {t('fleet.licenseGrace')}
            </motion.p>
          )}
        </AnimatePresence>

        {/* Mot de passe provisoire (donné par le support) : le dire, et de
            quoi le changer tout de suite — la fenêtre d'office a pu être
            remise à plus tard. */}
        <AnimatePresence>
          {passwordProvisional && (
            <Warning text={t('password.accountWarning')} action={t('password.changeProvisional')} onAction={() => setShowPassword(true)} />
          )}
        </AnimatePresence>

        {/* E-mail jamais confirmé : le serveur supprime le compte un jour
            après sa création. Le dire ici, avec de quoi y remédier — la
            fenêtre de confirmation peut avoir été remise à plus tard. */}
        <AnimatePresence>
          {verificationRequired && (
            <Warning text={t('account.unverifiedWarning')} action={t('account.verifyEmail')} onAction={() => setShowVerify(true)} />
          )}
        </AnimatePresence>

        {/* ── Sécurité ── */}
        <Group title={t('account.groupSecurity')}>
          <div>
            <Row
              icon={<MailIcon />}
              label={t('account.email')}
              value={yuyuEmail ? <MaskedEmail email={yuyuEmail} /> : t('account.noEmail')}
              action={
                <Button size="sm" onClick={() => toggle('email')}>
                  {yuyuEmail ? t('account.change') : t('account.add')}
                </Button>
              }
            />
            <Expand open={section === 'email'}>
              <EmailForm onDone={() => setSection(null)} onPending={() => setShowVerify(true)} />
            </Expand>
          </div>
          <Row
            icon={<LockIcon />}
            label={t('account.password')}
            value={passwordProvisional ? t('password.provisionalValue') : '••••••••'}
            action={
              <Button size="sm" onClick={() => setShowPassword(true)}>
                {t('account.change')}
              </Button>
            }
          />
          {/* Double authentification : obligatoire, donc rien à désactiver —
              seulement passer du code par e-mail à une application, ou refaire
              ses codes de secours. */}
          {mfa && mfa.second_factor !== 'none' && (
            <Row
              icon={<ShieldIcon />}
              label={t('mfa.row')}
              value={mfa.second_factor === 'totp' ? t('mfa.rowTotp', { count: mfa.backup_codes_left }) : t('mfa.rowEmail')}
              action={
                <Button size="sm" onClick={() => setTotpMode(mfa.second_factor === 'totp' ? 'regen' : 'setup')}>
                  {mfa.second_factor === 'totp' ? t('mfa.newBackupCodes') : t('mfa.enableApp')}
                </Button>
              }
            />
          )}
        </Group>

        {/* ── Appareils ── */}
        <Group title={t('account.groupDevices')}>
          {/* Connecter un autre appareil : QR code pour le téléphone, code à
              taper sur un PC (écran d'accueil, étape du compte). */}
          <Row
            icon={<QrIcon />}
            label={t('linkDevice.row')}
            value={t('linkDevice.rowHint')}
            action={
              <Button size="sm" variant="primary" onClick={() => setShowLink(true)}>
                {t('linkDevice.show')}
              </Button>
            }
          />
          <div>
            <Row
              icon={<DevicesIcon />}
              label={t('account.devices')}
              value={t('account.devicesHint')}
              action={
                <Button size="sm" onClick={() => toggle('devices')}>
                  {section === 'devices' ? t('account.hide') : t('account.show')}
                </Button>
              }
            />
            <Expand open={section === 'devices'}>
              <DeviceList />
            </Expand>
          </div>
        </Group>

        <motion.button
          variants={listItemVariants}
          type="button"
          onClick={handleLogout}
          className="flex h-11 items-center justify-center gap-2 rounded-2xl border border-line bg-surface-1 text-[13px] font-semibold text-danger transition-[background-color,transform] duration-150 ease-out hover:bg-danger/10 active:scale-[0.98] max-sm:h-12 max-sm:text-[15px]"
        >
          <LogoutIcon />
          {t('account.logout')}
        </motion.button>
      </motion.div>

      <AnimatePresence>
        {showPassword && <PasswordChangeModal onClose={() => setShowPassword(false)} />}
        {showLink && <LinkDeviceModal onClose={() => setShowLink(false)} />}
        {/* Changement d'adresse en cours, ou confirmation demandée depuis
            l'avertissement ci-dessus. Tant que le compte n'a aucune adresse
            confirmée, la fenêtre garde ses sorties du mode imposé (changer
            d'adresse, se déconnecter). */}
        {showVerify && <EmailVerifyModal forced={verificationRequired} onClose={() => setShowVerify(false)} />}
        {totpMode && (
          <TotpModal
            mode={totpMode}
            onClose={() => {
              setTotpMode(null)
              loadMfa()
            }}
          />
        )}
      </AnimatePresence>
    </>
  )
}

// ── Briques ──────────────────────────────────────────────────────────────────

/** Une partie du compte : son titre, puis ses lignes dans un même cadre,
 *  séparées par un filet. */
function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <motion.section variants={listItemVariants} className="flex flex-col gap-2">
      <h3 className="px-1 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-txt-muted max-sm:text-[12px]">{title}</h3>
      <div className="flex flex-col divide-y divide-line-soft overflow-hidden rounded-2xl border border-line bg-surface-1">{children}</div>
    </motion.section>
  )
}

/**
 * Une ligne : icône, titre, description, geste. Le titre est ce qu'on lit en
 * premier, la description passe à la ligne (jamais coupée) et le bouton ne
 * rétrécit pas.
 */
function Row({ icon, label, value, action }: { icon: React.ReactNode; label: string; value: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="flex items-center gap-3 px-3.5 py-3 max-sm:min-h-[68px] max-sm:px-4">
      <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-white/[0.05] text-txt-secondary max-sm:h-9 max-sm:w-9">
        {icon}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-[13px] font-semibold leading-snug text-txt-primary max-sm:text-[15px]">{label}</span>
        <span className="min-w-0 break-words text-[11.5px] leading-snug text-txt-muted max-sm:text-[13px]">{value}</span>
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  )
}

/** Un avertissement qui demande un geste (e-mail à confirmer, mot de passe provisoire). */
function Warning({ text, action, onAction }: { text: string; action: string; onAction: () => void }) {
  return (
    <motion.div
      variants={fadeVariants}
      initial="initial"
      animate="animate"
      exit="exit"
      className="flex flex-col gap-2.5 rounded-xl border border-warning/40 bg-warning/10 px-3.5 py-3 max-sm:p-4"
    >
      <p className="text-[11.5px] leading-relaxed text-warning max-sm:text-[14px]">{text}</p>
      <Button size="sm" variant="primary" className="self-start max-sm:self-stretch" onClick={onAction}>
        {action}
      </Button>
    </motion.div>
  )
}

/** Dépliage en hauteur, sans saut : la hauteur est animée vers `auto`. */
function Expand({ open, children }: { open: boolean; children: React.ReactNode }) {
  return (
    <AnimatePresence initial={false}>
      {open && (
        <motion.div
          initial={{ height: 0, opacity: 0 }}
          animate={{ height: 'auto', opacity: 1, transition }}
          exit={{ height: 0, opacity: 0, transition }}
          className="overflow-hidden"
        >
          <div className="px-3.5 pb-3.5 max-sm:px-4">{children}</div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

const ICON = 'h-[17px] w-[17px]'
const ICON_PROPS = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.9, strokeLinecap: 'round', strokeLinejoin: 'round', className: ICON } as const

function MailIcon() {
  return (
    <svg {...ICON_PROPS}>
      <rect x="3" y="5" width="18" height="14" rx="2.5" />
      <path d="M4 7l8 6 8-6" />
    </svg>
  )
}

function LockIcon() {
  return (
    <svg {...ICON_PROPS}>
      <rect x="4.5" y="10.5" width="15" height="10" rx="2.5" />
      <path d="M8 10.5V7.5a4 4 0 018 0v3" />
    </svg>
  )
}

function ShieldIcon() {
  return (
    <svg {...ICON_PROPS}>
      <path d="M12 3l7.5 3v5.5c0 4.5-3.2 8.2-7.5 9.5-4.3-1.3-7.5-5-7.5-9.5V6L12 3z" />
      <path d="M9 12l2 2 4-4" />
    </svg>
  )
}

function QrIcon() {
  return (
    <svg {...ICON_PROPS}>
      <path d="M4 8V5a1 1 0 011-1h3M16 4h3a1 1 0 011 1v3M20 16v3a1 1 0 01-1 1h-3M8 20H5a1 1 0 01-1-1v-3" />
      <rect x="8" y="8" width="3" height="3" />
      <rect x="13" y="8" width="3" height="3" />
      <rect x="8" y="13" width="3" height="3" />
      <path d="M13 13h3v3" />
    </svg>
  )
}

function DevicesIcon() {
  return (
    <svg {...ICON_PROPS}>
      <rect x="2.5" y="5" width="13" height="10" rx="1.5" />
      <path d="M6 19h6" />
      <rect x="17" y="9" width="4.5" height="10" rx="1.2" />
    </svg>
  )
}

function LogoutIcon() {
  return (
    <svg {...ICON_PROPS}>
      <path d="M15 4h3a2 2 0 012 2v12a2 2 0 01-2 2h-3" />
      <path d="M10 16l-4-4 4-4M6 12h10" />
    </svg>
  )
}

function EmailForm({ onDone, onPending }: { onDone: () => void; onPending: () => void }) {
  const t = useT()
  const current = useStore((s) => s.yuyuEmail)
  const [value, setValue] = useState(current ?? '')
  const [password, setPassword] = useState('')
  const [mfaCode, setMfaCode] = useState('')
  const [needsCode, setNeedsCode] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      // L'adresse du compte ne change pas encore : un code part à la
      // nouvelle, et c'est sa saisie qui la pose (fenêtre ouverte par
      // `onPending`).
      const resp = await api.yuyu.setEmail(value.trim(), password, mfaCode.trim())
      useStore.setState({ yuyuEmail: resp.email })
      useStore.getState().setYuyuEmailVerification(resp.verification_required, resp.pending_email)
      if (resp.pending_email) onPending()
      else showNotice(t('account.emailSaved'))
      onDone()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-2 rounded-xl border border-line bg-surface-2 p-3">
      <Field
        type="email"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder="toi@exemple.fr"
        hint={t('account.emailHint')}
      />
      <Field
        type="password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        placeholder={t('emailVerify.passwordField')}
        error={error}
      />
      <StepUpField value={mfaCode} onChange={setMfaCode} onFactor={(f) => setNeedsCode(f !== 'none')} />
      <div className="flex justify-end gap-2 max-sm:[&>button]:flex-1">
        <Button size="sm" variant="ghost" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button size="sm" variant="primary" loading={busy} disabled={!value.trim() || !password || (needsCode && !mfaCode.trim())} onClick={save}>
          {t('common.save')}
        </Button>
      </div>
    </div>
  )
}

function DeviceList() {
  const t = useT()
  const [devices, setDevices] = useState<YuyuDevice[] | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const load = () => {
    api.yuyu
      .listDevices()
      .then(setDevices)
      .catch((e) => {
        setDevices([])
        showApiError(e, t('common.serverUnreachable'))
      })
  }

  useEffect(load, [])

  const revoke = async (id: string) => {
    setBusy(id)
    try {
      await api.yuyu.revokeDevice(id)
      setDevices((d) => d?.filter((x) => x.id !== id) ?? null)
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      setBusy(null)
    }
  }

  if (devices === null) {
    return <p className="px-3 py-2 text-[11px] text-txt-muted max-sm:text-[15px]">{t('common.loading')}</p>
  }
  if (!devices.length) {
    return <p className="px-3 py-2 text-[11px] text-txt-muted max-sm:text-[15px]">{t('account.noDevice')}</p>
  }

  return (
    <motion.ul variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-1.5">
      <AnimatePresence initial={false}>
        {devices.map((d) => (
          <motion.li
            key={d.id}
            layout
            variants={listItemVariants}
            exit={{ opacity: 0, x: -8, transition }}
            className="flex items-center gap-3 rounded-xl border border-line bg-surface-2 px-3 py-2 max-sm:min-h-[64px] max-sm:px-4"
          >
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-[12px] text-txt-primary max-sm:text-[15px]">
                {d.device_name ?? t('account.unknownDevice')}
                {d.current && <span className="ml-2 text-[10px] text-success max-sm:text-[13px]">{t('account.thisDevice')}</span>}
              </span>
              <span className="truncate text-[10px] text-txt-muted max-sm:text-[13px]">
                {[d.os, d.launcher_version && `v${d.launcher_version}`, d.last_used_at && new Date(d.last_used_at).toLocaleDateString()]
                  .filter(Boolean)
                  .join(' · ')}
              </span>
            </div>
            {!d.current && (
              <Button size="sm" variant="danger" loading={busy === d.id} onClick={() => revoke(d.id)}>
                {t('account.revoke')}
              </Button>
            )}
          </motion.li>
        ))}
      </AnimatePresence>
    </motion.ul>
  )
}

function SignedOutCard({ onSignIn }: { onSignIn: () => void }) {
  const t = useT()
  return (
    <motion.section
      variants={listItemVariants}
      initial="initial"
      animate="animate"
      className="flex items-center gap-3 rounded-2xl border border-dashed border-line bg-surface-1 p-4"
    >
      <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-surface-3 text-txt-muted">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" className="h-5 w-5">
          <path d="M12 12a4 4 0 100-8 4 4 0 000 8zM4 20a8 8 0 0116 0" />
        </svg>
      </div>
      <div className="flex min-w-0 flex-1 flex-col">
        <p className="text-[13px] font-semibold text-txt-primary max-sm:text-[15px]">{t('account.signedOutTitle')}</p>
        <p className="text-[11px] text-txt-secondary max-sm:text-[13px]">{t('account.signedOutDescription')}</p>
      </div>
      <Button variant="primary" size="sm" onClick={onSignIn}>
        {t('account.signIn')}
      </Button>
    </motion.section>
  )
}
