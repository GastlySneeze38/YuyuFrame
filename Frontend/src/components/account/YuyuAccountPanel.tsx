import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { YuyuDevice } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { Button } from '@/components/ui/Button'
import { Field } from '@/components/ui/Field'
import { PlanBadge } from '@/components/plans/PlanBadge'
import { PasswordChangeModal } from '@/components/account/PasswordChangeModal'
import { EmailVerifyModal } from '@/components/account/EmailVerifyModal'
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
  } = useStore()
  const [section, setSection] = useState<'email' | 'devices' | null>(null)
  const [showPassword, setShowPassword] = useState(false)
  const [showVerify, setShowVerify] = useState(false)
  const verificationRequired = useStore((s) => s.yuyuEmailVerificationRequired)

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

  return (
    <>
      <motion.section
        variants={listVariants}
        initial="initial"
        animate="animate"
        className="flex flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-4"
      >
        {/* En-tête : qui est connecté, et avec quel abonnement */}
        <motion.div variants={listItemVariants} className="flex items-center gap-3">
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-accent/25 text-[15px] font-black text-white">
            {(yuyuUsername ?? '?')[0].toUpperCase()}
          </div>
          <div className="flex min-w-0 flex-1 flex-col">
            <div className="flex items-center gap-2">
              <p className="truncate text-[13px] font-semibold text-txt-primary">{yuyuUsername}</p>
              <PlanBadge plan={yuyuPlan} />
            </div>
            <p className="truncate text-[11px] text-txt-muted">{yuyuEmail ?? t('account.noEmail')}</p>
          </div>
          <Button variant="ghost" size="sm" onClick={handleLogout}>
            {t('account.logout')}
          </Button>
        </motion.div>

        {/* Abonnement */}
        <motion.div variants={listItemVariants}>
          <Row
            label={t('account.subscription')}
            value={
              yuyuPlan === 'free'
                ? t('account.freePlan')
                : yuyuPlanExpiresAt
                  ? t('account.until', { date: new Date(yuyuPlanExpiresAt * 1000).toLocaleDateString() })
                  : t('account.noEnd')
            }
            action={
              <Button size="sm" variant={yuyuPlan === 'free' ? 'primary' : 'secondary'} onClick={() => navigate('/plans')}>
                {yuyuPlan === 'free' ? t('account.seePlans') : t('account.manage')}
              </Button>
            }
          />
        </motion.div>

        {/* Licence hors ligne épuisée : le joueur doit savoir pourquoi son
            abonnement va retomber en gratuit. */}
        <AnimatePresence>
          {yuyuLicenseState === 'grace' && (
            <motion.p
              variants={fadeVariants}
              initial="initial"
              animate="animate"
              exit="exit"
              className="rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-[11px] text-warning"
            >
              {t('fleet.licenseGrace')}
            </motion.p>
          )}
        </AnimatePresence>

        {/* E-mail */}
        <motion.div variants={listItemVariants}>
          <Row
            label={t('account.email')}
            value={yuyuEmail ?? t('account.noEmail')}
            action={
              <Button size="sm" onClick={() => toggle('email')}>
                {yuyuEmail ? t('account.change') : t('account.add')}
              </Button>
            }
          />
          <Expand open={section === 'email'}>
            <EmailForm onDone={() => setSection(null)} onPending={() => setShowVerify(true)} />
          </Expand>
        </motion.div>

        {/* Mot de passe */}
        <motion.div variants={listItemVariants}>
          <Row
            label={t('account.password')}
            value="••••••••"
            action={
              <Button size="sm" onClick={() => setShowPassword(true)}>
                {t('account.change')}
              </Button>
            }
          />
        </motion.div>

        {/* Appareils connectés */}
        <motion.div variants={listItemVariants}>
          <Row
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
        </motion.div>
      </motion.section>

      <AnimatePresence>
        {showPassword && <PasswordChangeModal onClose={() => setShowPassword(false)} />}
        {/* Changement d'adresse en cours. Si le compte n'avait pas d'adresse
            confirmée, c'est App.tsx qui impose déjà la même fenêtre. */}
        {showVerify && !verificationRequired && <EmailVerifyModal onClose={() => setShowVerify(false)} />}
      </AnimatePresence>
    </>
  )
}

// ── Briques ──────────────────────────────────────────────────────────────────

function Row({ label, value, action }: { label: string; value: string; action?: React.ReactNode }) {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2.5">
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="text-[11px] text-txt-muted">{label}</span>
        <span className="truncate text-[12px] text-txt-primary">{value}</span>
      </div>
      {action}
    </div>
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
          <div className="pt-2">{children}</div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

function EmailForm({ onDone, onPending }: { onDone: () => void; onPending: () => void }) {
  const t = useT()
  const current = useStore((s) => s.yuyuEmail)
  const [value, setValue] = useState(current ?? '')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      // L'adresse du compte ne change pas encore : un code part à la
      // nouvelle, et c'est sa saisie qui la pose (fenêtre ouverte par
      // `onPending`).
      const resp = await api.yuyu.setEmail(value.trim(), password)
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
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button size="sm" variant="primary" loading={busy} disabled={!value.trim() || !password} onClick={save}>
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
    return <p className="px-3 py-2 text-[11px] text-txt-muted">{t('common.loading')}</p>
  }
  if (!devices.length) {
    return <p className="px-3 py-2 text-[11px] text-txt-muted">{t('account.noDevice')}</p>
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
            className="flex items-center gap-3 rounded-xl border border-line bg-surface-2 px-3 py-2"
          >
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-[12px] text-txt-primary">
                {d.device_name ?? t('account.unknownDevice')}
                {d.current && <span className="ml-2 text-[10px] text-success">{t('account.thisDevice')}</span>}
              </span>
              <span className="truncate text-[10px] text-txt-muted">
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
        <p className="text-[13px] font-semibold text-txt-primary">{t('account.signedOutTitle')}</p>
        <p className="text-[11px] text-txt-secondary">{t('account.signedOutDescription')}</p>
      </div>
      <Button variant="primary" size="sm" onClick={onSignIn}>
        {t('account.signIn')}
      </Button>
    </motion.section>
  )
}
