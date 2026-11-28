import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { QRCodeSVG } from 'qrcode.react'
import { api, type LinkCode, type LinkStatus } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { formatCode, linkUrl } from '@/lib/deviceLink'
import { errorMessage } from '@/lib/apiError'
import { showNotice } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

const EASE = [0.16, 1, 0.3, 1] as const

/**
 * Connecter un autre appareil à ce compte, sans mot de passe : un code de
 * 5 minutes en QR code (à scanner avec le téléphone, sur l'écran d'accueil)
 * et en texte (à taper sur un PC). Le code est suivi : dès qu'un appareil l'a
 * pris, la fenêtre le nomme et propose de le déconnecter si ce n'était pas
 * toi. Un code échu est remplacé tout seul ; fermer annule celui en cours.
 */
export function LinkDeviceModal({ onClose }: { onClose: () => void }) {
  const t = useT()
  const [link, setLink] = useState<LinkCode | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [used, setUsed] = useState<LinkStatus | null>(null)
  const [copied, setCopied] = useState(false)
  const [revoked, setRevoked] = useState(false)
  // Le code en cours, pour l'annuler en fermant (lu dans un nettoyage).
  const current = useRef<LinkCode | null>(null)
  const done = useRef(false)

  const create = () => {
    setError(null)
    api.yuyu.linkCreate()
      .then((l) => {
        current.current = l
        setLink(l)
      })
      .catch((e) => setError(errorMessage(e)))
  }

  useEffect(() => {
    create()
    return () => {
      const l = current.current
      if (l && !done.current) api.yuyu.linkCancel(l.id).catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Le code a-t-il servi ? Toutes les 2 secondes tant que la fenêtre attend.
  useEffect(() => {
    if (!link || used) return
    const timer = window.setInterval(() => {
      api.yuyu.linkStatus(link.id)
        .then((s) => {
          if (s.status === 'used') {
            done.current = true
            setUsed(s)
          } else if (s.status === 'expired') create()
        })
        .catch(() => {})
    }, 2000)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [link, used])

  const copy = () => {
    if (!link) return
    void navigator.clipboard.writeText(link.code).then(() => {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    })
  }

  const revoke = async () => {
    if (!used?.session_id) return
    try {
      await api.yuyu.revokeDevice(used.session_id)
      setRevoked(true)
      showNotice(t('linkDevice.revoked'))
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <ModalShell title={t('linkDevice.title')} onClose={onClose} maxWidth="max-w-md">
      <AnimatePresence mode="wait" initial={false}>
        {used ? (
          <motion.div
            key="used"
            initial={{ opacity: 0, scale: 0.97 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.35, ease: EASE }}
            className="flex flex-col items-center gap-3 py-4 text-center"
          >
            <motion.span
              initial={{ scale: 0.6, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ type: 'spring', duration: 0.5, bounce: 0.35 }}
              className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500/20 text-emerald-300"
            >
              <svg viewBox="0 0 24 24" className="h-7 w-7" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
            </motion.span>
            <p className="text-[15px] font-bold text-white">{t('linkDevice.connected')}</p>
            <p className="text-[13px] text-txt-secondary">
              {[used.device_name, used.os].filter(Boolean).join(' · ') || t('linkDevice.unknownDevice')}
            </p>
            <div className="mt-2 flex w-full flex-col gap-2">
              <button
                type="button"
                onClick={onClose}
                className="h-11 rounded-xl bg-[rgb(var(--accent))] text-[14px] font-bold text-white transition-transform duration-150 active:scale-[0.97]"
              >
                {t('common.close')}
              </button>
              {!revoked && used.session_id && (
                <button
                  type="button"
                  onClick={() => void revoke()}
                  className="h-10 rounded-xl text-[12.5px] font-semibold text-red-300/80 transition-colors duration-150 hover:text-red-300"
                >
                  {t('linkDevice.notMe')}
                </button>
              )}
            </div>
          </motion.div>
        ) : (
          <motion.div key="code" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col items-center gap-4">
            <p className="text-center text-[12.5px] leading-relaxed text-txt-secondary max-sm:text-[14px]">{t('linkDevice.explain')}</p>

            {/* Le QR code se recompose en fondu quand un code en remplace un autre. */}
            <div className="relative flex h-[212px] w-[212px] items-center justify-center rounded-2xl bg-white p-3 shadow-[0_10px_40px_rgb(var(--accent)/0.25)]">
              <AnimatePresence mode="popLayout" initial={false}>
                {link ? (
                  <motion.div
                    key={link.code}
                    initial={{ opacity: 0, scale: 0.94, filter: 'blur(4px)' }}
                    animate={{ opacity: 1, scale: 1, filter: 'blur(0px)' }}
                    exit={{ opacity: 0, scale: 1.04, filter: 'blur(4px)' }}
                    transition={{ duration: 0.4, ease: EASE }}
                  >
                    <QRCodeSVG value={linkUrl(link.code)} size={188} level="M" />
                  </motion.div>
                ) : (
                  <span key="wait" className="h-7 w-7 animate-spin rounded-full border-2 border-black/10 border-t-black/60" />
                )}
              </AnimatePresence>
            </div>

            <button
              type="button"
              onClick={copy}
              disabled={!link}
              className="flex flex-col items-center gap-0.5 rounded-xl px-4 py-1.5 transition-transform duration-150 active:scale-[0.97]"
            >
              <span className="font-mono text-[28px] font-black tracking-[0.14em] text-white">{link ? formatCode(link.code) : '••••-••••'}</span>
              <span className="text-[11.5px] font-semibold text-[rgb(var(--accent-text))]">{copied ? t('linkDevice.copied') : t('linkDevice.copy')}</span>
            </button>

            {/* Le temps qui reste : la barre se vide en 5 minutes, puis un code neuf. */}
            {link && (
              <div className="h-1 w-48 overflow-hidden rounded-full bg-white/10">
                <div
                  key={link.code}
                  className="h-full w-full origin-left rounded-full bg-[rgb(var(--accent))]"
                  style={{ animation: `link-countdown ${link.expires_in}s linear forwards` }}
                />
              </div>
            )}

            <ul className="w-full space-y-1.5 text-[12px] text-txt-secondary max-sm:text-[13.5px]">
              <li className="flex gap-2"><span className="text-[rgb(var(--accent-text))]">1.</span>{t('linkDevice.stepPhone')}</li>
              <li className="flex gap-2"><span className="text-[rgb(var(--accent-text))]">2.</span>{t('linkDevice.stepPc')}</li>
            </ul>
            {error && <p className="text-center text-[12px] text-red-300">{error}</p>}
          </motion.div>
        )}
      </AnimatePresence>
    </ModalShell>
  )
}
