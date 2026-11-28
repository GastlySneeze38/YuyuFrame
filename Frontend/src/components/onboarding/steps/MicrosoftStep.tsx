import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { useStorePick } from '@/stores/useStore'
import { openExternal } from '@/lib/openExternal'
import { useT } from '@/i18n'
import type { Account } from '@/types'
import { EASE, Ghost, Item, Primary, Stagger, TextLink } from '../ui'

type Phase = 'idle' | 'starting' | 'waiting' | 'done' | 'error'

/**
 * Le compte Minecraft, par code d'appareil — le même chemin que l'écran
 * Comptes (`pages/Login.tsx`), sans la fenêtre réduite : ici le code reste
 * en grand au milieu de l'écran pendant qu'on le tape dans le navigateur.
 */
export function MicrosoftStep({ onDone, onSkip }: { onDone: () => void; onSkip: () => void }) {
  const t = useT()
  const { setAccounts, setUser } = useStorePick('setAccounts', 'setUser')
  const [phase, setPhase] = useState<Phase>('idle')
  const [code, setCode] = useState('')
  const [url, setUrl] = useState('')
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [account, setAccount] = useState<{ name: string; uuid: string } | null>(null)
  const poll = useRef<ReturnType<typeof setInterval> | null>(null)

  const stop = () => {
    if (poll.current) clearInterval(poll.current)
    poll.current = null
    api.auth.closeSignInPage().catch(() => {})
  }
  useEffect(() => stop, [])

  // La page où taper le code : dans l'appli sur téléphone (code déjà rempli),
  // dans le navigateur sur ordinateur, où l'appli répond `false`.
  const openPage = async (u: string, c: string) => {
    const inApp = await api.auth.openSignInPage(u, c, t('login.microsoftTitle'), t('common.close')).catch(() => false)
    if (!inApp) await openExternal(u)
  }

  const start = async () => {
    setPhase('starting')
    setError('')
    try {
      const resp = await api.auth.startDevice()
      setCode(resp.user_code)
      setUrl(resp.verification_uri)
      setPhase('waiting')
      void openPage(resp.verification_uri, resp.user_code)
      poll.current = setInterval(async () => {
        try {
          const r = await api.auth.poll()
          if (r.status === 'success' && r.username) {
            stop()
            const accs = await api.mc.accounts()
            setAccounts(accs.map((a): Account => ({ username: a.mc_username, uuid: a.mc_uuid, is_offline: a.is_offline })))
            const active = accs.find((a) => a.is_active)
            if (active) {
              setUser(active.mc_username, active.mc_uuid, active.is_offline)
              setAccount({ name: active.mc_username, uuid: active.mc_uuid })
            }
            setPhase('done')
            // Le serveur garde la liste des comptes liés.
            api.yuyu.syncMinecraftAccounts().catch(() => {})
          } else if (r.status === 'error') {
            stop()
            setError(r.error ?? t('login.unknownError'))
            setPhase('error')
          }
        } catch { /* on continue d'attendre */ }
      }, 4000)
    } catch (e) {
      setError(e instanceof Error ? e.message : typeof e === 'string' ? e : t('login.backendConnectionError'))
      setPhase('error')
    }
  }

  const copy = () => {
    void navigator.clipboard.writeText(code).then(() => {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    })
  }

  return (
    <AnimatePresence mode="popLayout" initial={false}>
      {phase === 'done' && account ? (
        <motion.div
          key="done"
          initial={{ opacity: 0, scale: 0.96 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.5, ease: EASE }}
          className="flex flex-col items-center gap-4 py-2 text-center"
        >
          <motion.div
            initial={{ scale: 0.6, rotate: -8, opacity: 0 }}
            animate={{ scale: 1, rotate: 0, opacity: 1 }}
            transition={{ type: 'spring', duration: 0.7, bounce: 0.35 }}
            className="relative"
          >
            <span className="absolute -inset-3 rounded-[28px] bg-[rgb(var(--accent)/0.35)] blur-xl" />
            <img
              src={`https://mc-heads.net/avatar/${account.uuid}/96`}
              alt=""
              width={88}
              height={88}
              className="relative rounded-2xl [image-rendering:pixelated]"
            />
          </motion.div>
          <div>
            <p className="text-[12px] uppercase tracking-[0.12em] text-white/45">{t('onboarding.microsoft.connectedAs')}</p>
            <p className="mt-1 text-[22px] font-black text-white">{account.name}</p>
          </div>
          <div className="w-full pt-2">
            <Primary onClick={onDone}>{t('onboarding.continue')}</Primary>
          </div>
        </motion.div>
      ) : phase === 'waiting' ? (
        <motion.div key="waiting" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.4, ease: EASE }}>
          <Stagger className="flex flex-col gap-4">
            <Item>
              <p className="text-[12.5px] leading-relaxed text-white/60 max-sm:text-[14px]">{t('onboarding.microsoft.codeHint')}</p>
            </Item>
            <Item>
              <button
                type="button"
                onClick={copy}
                className="ob-sheen relative flex w-full flex-col items-center gap-1 overflow-hidden rounded-2xl border border-[rgb(var(--accent)/0.45)] bg-black/40 py-5 transition-transform duration-150 active:scale-[0.98]"
              >
                <span className="text-[10.5px] font-semibold uppercase tracking-[0.14em] text-white/45">{t('onboarding.microsoft.codeLabel')}</span>
                <span className="font-mono text-[34px] font-black tracking-[0.18em] text-white max-sm:text-[30px]">{code}</span>
                <span className="text-[11.5px] font-semibold text-[rgb(var(--accent-text))]">
                  {copied ? t('onboarding.microsoft.copied') : t('onboarding.microsoft.copy')}
                </span>
              </button>
            </Item>
            <Item className="flex items-center justify-center gap-2.5 text-[12.5px] text-white/55">
              <span className="relative flex h-2.5 w-2.5">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[rgb(var(--accent))] opacity-60" />
                <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-[rgb(var(--accent))]" />
              </span>
              {t('onboarding.microsoft.waiting')}
            </Item>
            <Item className="flex items-center justify-between">
              <TextLink onClick={() => void openPage(url, code)}>{t('onboarding.microsoft.openPage')}</TextLink>
              <Ghost onClick={() => { stop(); setPhase('idle') }}>{t('common.cancel')}</Ghost>
            </Item>
          </Stagger>
        </motion.div>
      ) : (
        <motion.div key="idle" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.4, ease: EASE }}>
          <Stagger className="flex flex-col gap-4">
            <Item className="flex items-center gap-3 rounded-2xl border border-white/10 bg-black/30 p-4">
              <MicrosoftLogo />
              <p className="text-[12.5px] leading-relaxed text-white/65 max-sm:text-[14px]">{t('onboarding.microsoft.why')}</p>
            </Item>
            {phase === 'error' && (
              <Item>
                <p className="rounded-xl border border-red-400/25 bg-red-500/10 px-3 py-2 text-[12.5px] text-red-200">{error}</p>
              </Item>
            )}
            <Item>
              <Primary onClick={() => void start()} loading={phase === 'starting'}>
                {phase === 'error' ? t('onboarding.microsoft.retry') : t('onboarding.microsoft.button')}
              </Primary>
            </Item>
            <Item className="flex justify-center">
              <Ghost onClick={onSkip}>{t('onboarding.later')}</Ghost>
            </Item>
          </Stagger>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

function MicrosoftLogo() {
  return (
    <svg viewBox="0 0 24 24" width={30} height={30} className="shrink-0" aria-hidden>
      <rect x="1" y="1" width="10.5" height="10.5" fill="#f25022" />
      <rect x="12.5" y="1" width="10.5" height="10.5" fill="#7fba00" />
      <rect x="1" y="12.5" width="10.5" height="10.5" fill="#00a4ef" />
      <rect x="12.5" y="12.5" width="10.5" height="10.5" fill="#ffb900" />
    </svg>
  )
}
