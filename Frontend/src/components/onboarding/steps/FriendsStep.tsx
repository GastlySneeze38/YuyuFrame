import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { useFriends } from '@/stores/useFriends'
import { showApiError } from '@/stores/useErrorToast'
import { errorMessage } from '@/lib/apiError'
import { bannerBackground } from '@/lib/profileBanners'
import { useT } from '@/i18n'
import type { LookupResult } from '@/types/friends'
import { EASE, Field, Ghost, Item, Primary, Stagger } from '../ui'

/**
 * Les amis, facultatif : mon code à donner, et l'ajout par code ami
 * (« pseudo#1234 », la seule façon d'ajouter quelqu'un). Un aperçu de la
 * personne avant d'envoyer, comme dans le panneau des amis.
 */
export function FriendsStep({ onNext }: { onNext: () => void }) {
  const t = useT()
  const myCode = useFriends((s) => s.list?.me.code ?? null)
  const [copied, setCopied] = useState(false)
  const [code, setCode] = useState('')
  const [found, setFound] = useState<LookupResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [sent, setSent] = useState<string[]>([])

  // La liste arrive d'elle-même par le flux des amis ; on la demande au cas où.
  useEffect(() => {
    if (!myCode) void useFriends.getState().refresh()
  }, [myCode])

  const copy = () => {
    if (!myCode) return
    void navigator.clipboard.writeText(myCode).then(() => {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    })
  }

  // Même forme que le panneau des amis (`FriendsPanel`) : « pseudo#1234 ».
  const cleaned = code.trim().replace(/^#/, '')
  const valid = /^[A-Za-z0-9_.-]{3,32}#\d{1,4}$/.test(cleaned)

  const search = async () => {
    if (!valid) return
    setBusy(true)
    setNote(null)
    setFound(null)
    try {
      const r = await api.friends.lookup(cleaned)
      // Ce qui empêche d'envoyer se dit à la place de la fiche.
      if (r.relation === 'none' || r.relation === 'incoming') setFound(r)
      else setNote(t(`friends.lookup.${r.relation}`))
    } catch (e) {
      setNote(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const send = async () => {
    if (!found) return
    setBusy(true)
    try {
      const r = await api.friends.request({ id: found.id })
      setSent((s) => [...s, r.username])
      setFound(null)
      setCode('')
      void useFriends.getState().refresh()
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Stagger className="flex flex-col gap-4">
      <Item>
        <span className="mb-1.5 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-white/45 max-sm:text-[12px]">{t('onboarding.friends.yourCode')}</span>
        <button
          type="button"
          onClick={copy}
          disabled={!myCode}
          className="flex h-12 w-full items-center justify-between rounded-xl border border-white/10 bg-black/35 px-4 transition-transform duration-150 active:scale-[0.98]"
        >
          {myCode ? (
            <span className="font-mono text-[16px] font-bold text-white">{myCode}</span>
          ) : (
            <span className="h-4 w-32 animate-pulse rounded bg-white/10" />
          )}
          <span className="text-[12px] font-semibold text-[rgb(var(--accent-text))]">
            {copied ? t('onboarding.microsoft.copied') : t('onboarding.microsoft.copy')}
          </span>
        </button>
        <p className="mt-1.5 text-[11.5px] text-white/45 max-sm:text-[13px]">{t('onboarding.friends.codeHint')}</p>
      </Item>

      <Item>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label={t('onboarding.friends.addLabel')} value={code} onChange={(v) => { setCode(v); setNote(null) }} placeholder="pseudo#1234" onEnter={() => void search()} />
          </div>
          <button
            type="button"
            onClick={() => void search()}
            disabled={!valid || busy}
            className="h-11 shrink-0 rounded-xl border border-white/10 bg-white/10 px-4 text-[13px] font-semibold text-white transition-[transform,opacity] duration-150 active:scale-[0.97] disabled:opacity-40 max-sm:h-12 max-sm:text-[15px]"
          >
            {t('onboarding.friends.search')}
          </button>
        </div>
      </Item>

      <AnimatePresence mode="popLayout" initial={false}>
        {found && (
          <motion.div
            key={found.id}
            initial={{ opacity: 0, y: 10, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, scale: 0.98, transition: { duration: 0.15 } }}
            transition={{ duration: 0.4, ease: EASE }}
            className="overflow-hidden rounded-2xl border border-white/10 bg-black/40"
          >
            <div className="h-12" style={{ background: bannerBackground(found.profile.banner, found.profile.accent ?? '#7b72e9') }} />
            <div className="relative -mt-6 flex items-end gap-3 px-4 pb-4">
              <img
                src={`https://mc-heads.net/avatar/${found.minecraft[0]?.uuid ?? found.username}/56`}
                alt=""
                width={48}
                height={48}
                className="rounded-xl border-2 border-black/60 [image-rendering:pixelated]"
              />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[15px] font-bold text-white">{found.username}</p>
                {found.profile.bio && <p className="truncate text-[12px] text-white/50">{found.profile.bio}</p>}
              </div>
              <button
                type="button"
                onClick={() => void send()}
                disabled={busy}
                className="h-9 shrink-0 rounded-lg bg-[rgb(var(--accent))] px-3.5 text-[12.5px] font-bold text-white transition-transform duration-150 active:scale-[0.96] disabled:opacity-50 max-sm:h-11 max-sm:text-[14px]"
              >
                {t('friends.send')}
              </button>
            </div>
          </motion.div>
        )}
        {note && (
          <motion.p key="note" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="text-[12.5px] text-white/60">
            {note}
          </motion.p>
        )}
      </AnimatePresence>

      {sent.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {sent.map((n) => (
            <motion.span
              key={n}
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ type: 'spring', duration: 0.4, bounce: 0.3 }}
              className="rounded-full border border-emerald-400/30 bg-emerald-500/15 px-2.5 py-1 text-[11.5px] font-semibold text-emerald-200"
            >
              ✓ {t('friends.sent', { name: n })}
            </motion.span>
          ))}
        </div>
      )}

      <Item className="flex items-center gap-2 pt-1">
        <div className="flex-1">
          <Primary onClick={onNext}>{t('onboarding.continue')}</Primary>
        </div>
        {sent.length === 0 && <Ghost onClick={onNext}>{t('onboarding.skip')}</Ghost>}
      </Item>
    </Stagger>
  )
}
