import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { MY_STATUSES, STATUS_COLORS, liveCustom, minutesUntilMidnight } from '@/lib/friends'
import { showError } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { MyStatusKind } from '@/types/friends'
import { FriendAvatar } from './FriendBits'

type Duration = 'm30' | 'h4' | 'today' | 'never'

function minutesOf(d: Duration): number | null {
  if (d === 'm30') return 30
  if (d === 'h4') return 240
  if (d === 'today') return minutesUntilMidnight()
  return null
}

/**
 * Moi, en bas du panneau : ma tête, mon pseudo, mon statut. Un clic ouvre le
 * menu au-dessus — les quatre statuts, et le statut personnalisé avec sa durée.
 */
export function MyStatusBar() {
  const t = useT()
  const me = useFriends((s) => s.list?.me)
  const refresh = useFriends((s) => s.refresh)
  const username = useStore((s) => s.yuyuUsername)
  const uuid = useStore((s) => s.uuid)
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  const [duration, setDuration] = useState<Duration>('today')
  const [saving, setSaving] = useState(false)

  const custom = me ? liveCustom(me) : null
  useEffect(() => {
    if (open) setText(custom ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  if (!me) return null

  async function apply(status: MyStatusKind, customText: string | null, minutes: number | null) {
    setSaving(true)
    try {
      await api.friends.setStatus(status, customText, minutes)
      await refresh()
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  const skin = `https://mc-heads.net/skin/${uuid ?? 'MHF_Steve'}`

  return (
    <div className="relative shrink-0 border-t border-line-soft bg-black/30 p-2">
      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: 8, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 8, scale: 0.98 }}
            transition={{ duration: 0.16 }}
            className="absolute inset-x-2 bottom-full mb-2 flex flex-col gap-1 rounded-2xl border border-line bg-[#18181f] p-2 shadow-[0_18px_50px_rgba(0,0,0,0.6)]"
          >
            {MY_STATUSES.map((s) => (
              <button
                key={s}
                disabled={saving}
                onClick={() => {
                  // Le statut personnalisé reste, avec ce qui lui restait à vivre.
                  const end = me.custom?.expires_at ? Date.parse(me.custom.expires_at) : null
                  const left = custom && end ? Math.max(1, Math.ceil((end - Date.now()) / 60000)) : null
                  void apply(s, custom, left)
                  setOpen(false)
                }}
                className={`flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition-colors hover:bg-white/[0.06] ${me.status === s ? 'bg-white/[0.05]' : ''}`}
              >
                <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: STATUS_COLORS[s] }} />
                <span className="flex min-w-0 flex-col">
                  <span className="text-[12.5px] font-semibold">{t(`friends.status.${s}`)}</span>
                  {s === 'dnd' || s === 'invisible' ? <span className="text-[11px] text-txt-muted">{t(`friends.statusHint.${s}`)}</span> : null}
                </span>
              </button>
            ))}

            <div className="mt-1 flex flex-col gap-2 border-t border-line-soft px-1 pt-2.5">
              <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('friends.custom.title')}</span>
              <input
                value={text}
                maxLength={60}
                onChange={(e) => setText(e.target.value)}
                placeholder={t('friends.custom.placeholder')}
                className="rounded-lg border border-line bg-black/30 px-2.5 py-2 text-[12.5px] outline-none placeholder:text-txt-muted focus:border-accent/60"
              />
              <div className="flex items-center gap-2">
                <span className="text-[11px] text-txt-muted">{t('friends.custom.clearAfter')}</span>
                <select
                  value={duration}
                  onChange={(e) => setDuration(e.target.value as Duration)}
                  className="flex-1 rounded-lg border border-line bg-black/30 px-2 py-1.5 text-[11.5px] outline-none"
                >
                  {(['m30', 'h4', 'today', 'never'] as Duration[]).map((d) => (
                    <option key={d} value={d} className="bg-[#18181f]">
                      {t(`friends.custom.durations.${d}`)}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex justify-end gap-2">
                {custom && (
                  <button
                    disabled={saving}
                    onClick={() => {
                      void apply(me.status, null, null)
                      setOpen(false)
                    }}
                    className="rounded-lg px-2.5 py-1.5 text-[11.5px] font-semibold text-txt-muted hover:text-white"
                  >
                    {t('friends.custom.clear')}
                  </button>
                )}
                <button
                  disabled={saving || !text.trim()}
                  onClick={() => {
                    void apply(me.status, text.trim(), minutesOf(duration))
                    setOpen(false)
                  }}
                  className="rounded-lg bg-accent px-3 py-1.5 text-[11.5px] font-bold text-white disabled:opacity-40"
                >
                  {t('friends.custom.save')}
                </button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <button onClick={() => setOpen((v) => !v)} className="flex w-full items-center gap-2.5 rounded-xl px-2 py-1.5 text-left transition-colors hover:bg-white/[0.05]">
        <FriendAvatar skin={skin} state={me.status} size={32} ring="#0d0d12" />
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-[13px] font-bold">{username ?? '—'}</span>
          <span className="truncate text-[11px] text-txt-muted">{custom ?? t(`friends.status.${me.status}`)}</span>
        </span>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" className={`h-4 w-4 text-txt-muted transition-transform ${open ? 'rotate-180' : ''}`}>
          <path d="M6 15l6-6 6 6" />
        </svg>
      </button>
    </div>
  )
}
