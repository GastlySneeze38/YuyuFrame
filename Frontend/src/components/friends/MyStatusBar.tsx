import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { MY_STATUSES, STATUS_COLORS, liveCustom } from '@/lib/friends'
import { showError } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { MyStatusKind } from '@/types/friends'
import { FriendAvatar } from './FriendBits'

/**
 * Moi, en bas du panneau : ma tête, mon pseudo, mon statut. Un clic ouvre le
 * menu au-dessus — les quatre statuts, et de quoi ouvrir ma fiche.
 */
export function MyStatusBar({ onOpenProfile }: { onOpenProfile: () => void }) {
  const t = useT()
  const me = useFriends((s) => s.list?.me)
  const refresh = useFriends((s) => s.refresh)
  const username = useStore((s) => s.yuyuUsername)
  const uuid = useStore((s) => s.uuid)
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)

  const custom = me ? liveCustom(me) : null

  if (!me) return null
  // « En ligne » choisi, mais inactif depuis 10 minutes : mes amis me voient
  // absent, je le vois aussi (ça repasse tout seul quand je reviens).
  const autoAway = me.status === 'online' && me.shown === 'away'

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
            className="absolute inset-x-2 bottom-full mb-2 flex flex-col gap-1 rounded-2xl border border-line bg-[rgb(var(--bg-raised))] p-2 shadow-[0_18px_50px_rgba(0,0,0,0.6)]"
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

            {/* Statut personnalisé, bannière, bio, vitrine : tout se règle
                dans ma fiche, ouverte dans le panneau. */}
            <button
              onClick={() => {
                setOpen(false)
                onOpenProfile()
              }}
              className="mt-1 flex items-center gap-2.5 rounded-lg border-t border-line-soft px-2.5 pb-2 pt-2.5 text-left transition-colors hover:bg-white/[0.06]"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4 shrink-0 text-accent-hover">
                <path d="M12 12a4 4 0 100-8 4 4 0 000 8zM4 20a8 8 0 0116 0" />
              </svg>
              <span className="flex min-w-0 flex-col">
                <span className="text-[12.5px] font-semibold">{t('friends.me.open')}</span>
                <span className="text-[11px] text-txt-muted">{t('friends.me.openHint')}</span>
              </span>
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      <button onClick={() => setOpen((v) => !v)} className="flex w-full items-center gap-2.5 rounded-xl px-2 py-1.5 text-left transition-colors hover:bg-white/[0.05]">
        <FriendAvatar skin={skin} state={autoAway ? 'away' : me.status} size={32} ring="rgb(var(--bg-primary))" />
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-[13px] font-bold">{username ?? '—'}</span>
          <span className="truncate text-[11px] text-txt-muted">{custom ?? (autoAway ? t('friends.status.autoAway') : t(`friends.status.${me.status}`))}</span>
        </span>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" className={`h-4 w-4 text-txt-muted transition-transform ${open ? 'rotate-180' : ''}`}>
          <path d="M6 15l6-6 6 6" />
        </svg>
      </button>
    </div>
  )
}
