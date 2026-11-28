import { useEffect, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { parseLinkCode, scanLinkCode } from '@/lib/deviceLink'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import { EASE, Ghost, Item, Primary, Stagger } from '../ui'

const LEN = 8

/**
 * Les deux portes rapides vers le compte, au-dessus du formulaire : scanner
 * le QR code d'un appareil déjà connecté (téléphone seulement), ou taper son
 * code. Un seul geste de plus que rien.
 */
export function QuickLinkButtons({ mobile, onCode, onScanned }: { mobile: boolean; onCode: () => void; onScanned: (code: string) => void }) {
  const t = useT()
  const [scanning, setScanning] = useState(false)

  const scan = async () => {
    setScanning(true)
    try {
      const code = await scanLinkCode()
      if (code) onScanned(code)
      else showError(t('onboarding.link.notACode'))
    } catch (e) {
      // Caméra refusée : on le dit ; une annulation ne dit rien.
      if (e instanceof Error && e.message === 'camera') showError(t('onboarding.link.cameraDenied'))
    } finally {
      setScanning(false)
    }
  }

  return (
    <div className={`grid gap-2 ${mobile ? 'grid-cols-2' : 'grid-cols-1'}`}>
      {mobile && (
        <QuickButton onClick={() => void scan()} busy={scanning} icon={<QrIcon />} label={t('onboarding.link.scan')} />
      )}
      <QuickButton
        onClick={onCode}
        icon={<KeyIcon />}
        label={mobile ? t('onboarding.link.enterCode') : t('onboarding.link.fromPhone')}
        hint={mobile ? undefined : t('onboarding.link.fromPhoneHint')}
      />
    </div>
  )
}

function QuickButton({ onClick, icon, label, hint, busy }: { onClick: () => void; icon: React.ReactNode; label: string; hint?: string; busy?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      className="group flex items-center gap-3 rounded-xl border border-white/10 bg-white/[0.05] px-3.5 py-3 text-left transition-[background-color,border-color,transform] duration-150 hover:border-[rgb(var(--accent)/0.45)] hover:bg-[rgb(var(--accent)/0.1)] active:scale-[0.98] disabled:opacity-60 max-sm:min-h-[56px]"
    >
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-[rgb(var(--accent)/0.2)] text-[rgb(var(--accent-text))]">
        {busy ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/20 border-t-white" /> : icon}
      </span>
      <span className="min-w-0">
        <span className="block text-[13px] font-semibold text-white max-sm:text-[14px]">{label}</span>
        {hint && <span className="block text-[11.5px] text-white/50">{hint}</span>}
      </span>
    </button>
  )
}

/**
 * Le code de l'autre appareil, en huit cases. Un seul vrai champ derrière
 * (le collage, la saisie semi-automatique, le clavier du téléphone marchent
 * comme d'habitude), les cases ne font que le montrer. Le huitième caractère
 * envoie tout seul.
 */
export function CodeEntry({ busy, onSubmit, onBack }: { busy: boolean; onSubmit: (code: string) => Promise<boolean>; onBack: () => void }) {
  const t = useT()
  const [value, setValue] = useState('')
  const [shake, setShake] = useState(0)
  const input = useRef<HTMLInputElement>(null)

  useEffect(() => {
    input.current?.focus()
  }, [])

  const change = (raw: string) => {
    // Un lien entier collé (yuyuframe://link/…) donne directement son code.
    const parsed = parseLinkCode(raw)
    const next = parsed ?? raw.replace(/[^A-Za-z0-9]/g, '').toUpperCase().slice(0, LEN)
    setValue(next)
    if (next.length === LEN) void send(next)
  }

  const send = async (code: string) => {
    const ok = await onSubmit(code)
    if (!ok) {
      setShake((n) => n + 1)
      setValue('')
      input.current?.focus()
    }
  }

  return (
    <Stagger className="flex flex-col gap-4">
      <Item>
        <h3 className="text-[16px] font-bold text-white">{t('onboarding.link.codeTitle')}</h3>
        <p className="mt-1 text-[12.5px] leading-relaxed text-white/60 max-sm:text-[14px]">{t('onboarding.link.codeHint')}</p>
      </Item>
      <Item>
        <motion.label
          key={shake}
          animate={shake ? { x: [0, -8, 7, -5, 3, 0] } : undefined}
          transition={{ duration: 0.4, ease: 'easeOut' }}
          className="relative flex cursor-text items-center justify-center gap-1.5"
        >
          <input
            ref={input}
            value={value}
            onChange={(e) => change(e.target.value)}
            autoCapitalize="characters"
            autoCorrect="off"
            autoComplete="one-time-code"
            spellCheck={false}
            disabled={busy}
            aria-label={t('onboarding.link.codeTitle')}
            className="absolute inset-0 h-full w-full cursor-text opacity-0"
          />
          {Array.from({ length: LEN }, (_, i) => {
            const char = value[i] ?? ''
            const active = i === value.length && !busy
            return (
              <span key={i} className="flex items-center">
                {i === 4 && <span className="mx-1 h-0.5 w-2.5 rounded-full bg-white/30" />}
                <span
                  className={`relative flex h-12 w-9 items-center justify-center rounded-lg border font-mono text-[20px] font-black text-white transition-[border-color,background-color] duration-150 max-sm:h-12 max-sm:w-[34px] ${
                    active ? 'border-[rgb(var(--accent))] bg-[rgb(var(--accent)/0.12)]' : char ? 'border-white/25 bg-black/40' : 'border-white/10 bg-black/30'
                  }`}
                >
                  {char && (
                    <motion.span initial={{ y: 6, opacity: 0 }} animate={{ y: 0, opacity: 1 }} transition={{ duration: 0.2, ease: EASE }}>
                      {char}
                    </motion.span>
                  )}
                  {active && <span className="absolute bottom-2 h-0.5 w-3.5 animate-pulse rounded-full bg-[rgb(var(--accent))]" />}
                </span>
              </span>
            )
          })}
        </motion.label>
      </Item>
      <Item>
        <Primary onClick={() => void send(value)} disabled={value.length !== LEN} loading={busy}>
          {t('onboarding.link.connect')}
        </Primary>
      </Item>
      <Item className="flex justify-center">
        <Ghost onClick={onBack}>{t('onboarding.link.usePassword')}</Ghost>
      </Item>
    </Stagger>
  )
}

function QrIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3" />
      <rect x="8" y="8" width="3" height="3" />
      <rect x="13" y="8" width="3" height="3" />
      <rect x="8" y="13" width="3" height="3" />
      <path d="M13 13h3v3" />
    </svg>
  )
}

function KeyIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="6" width="18" height="12" rx="2.5" />
      <path d="M7 10h.01M10.5 10h.01M14 10h.01M17 10h.01M8 14h8" />
    </svg>
  )
}
