import { useState } from 'react'
import { motion } from 'framer-motion'
import { api } from '@/api/client'
import { SkinFace } from '@/components/ui/SkinFace'
import { Toggle } from '@/components/ui/Toggle'
import { STATUS_COLORS, dateLocale, liveCustom, skinOf } from '@/lib/friends'
import { showError } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { Friend } from '@/types/friends'
import { FriendAvatar, GameTile, PresenceLine, activityText } from './FriendBits'

/**
 * Le profil d'un ami, dans le panneau : qui il est, ce qu'il fait, ses
 * comptes Minecraft, et les gestes qui le concernent. Les deux gestes
 * définitifs (retirer, bloquer) se confirment sur le bouton lui-même.
 */
export function FriendProfile({ friend, onDone }: { friend: Friend; onDone: () => void }) {
  const t = useT()
  const language = useStore((s) => s.language)
  const serverVisibility = useFriends((s) => s.list?.settings.server_visibility)
  const refresh = useFriends((s) => s.refresh)
  const [confirm, setConfirm] = useState<'remove' | 'block' | null>(null)
  const [busy, setBusy] = useState(false)
  const p = friend.presence
  const custom = liveCustom(p)
  const color = STATUS_COLORS[p.state]

  async function act(run: () => Promise<unknown>, leave: boolean) {
    setBusy(true)
    try {
      await run()
      await refresh()
      if (leave) onDone()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
      setConfirm(null)
    }
  }

  return (
    <motion.div initial={{ opacity: 0, x: 16 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 16 }} className="flex flex-col">
      {/* Bandeau à la couleur du statut, la tête à cheval dessus. */}
      <div className="relative h-24 shrink-0 overflow-hidden" style={{ background: `linear-gradient(135deg, ${color}55, ${color}10 70%)` }}>
        <div className="absolute inset-0 bg-[radial-gradient(60%_90%_at_80%_0%,rgba(255,255,255,0.12),transparent)]" />
      </div>
      <div className="-mt-10 px-4">
        <div className="w-fit rounded-[28%] bg-[#111116] p-1.5">
          <FriendAvatar skin={skinOf(friend)} state={p.state} size={72} />
        </div>
        <p className="mt-2 text-[18px] font-black leading-tight">{friend.username}</p>
        <p className="text-[12px] font-semibold" style={{ color }}>{t(`friends.status.${p.state}`)}</p>
        {custom && <p className="mt-1.5 text-[12.5px] text-txt-secondary">{custom}</p>}
      </div>

      <div className="mt-4 flex flex-col gap-3 px-4 pb-4">
        {p.activity?.kind === 'game' && (
          <Card title={t('friends.profile.activity')}>
            <div className="flex items-center gap-3">
              <GameTile activity={p.activity} size={44} />
              <div className="flex min-w-0 flex-col">
                <span className="truncate text-[13px] font-bold">{activityText(p.activity, t)}</span>
                {p.activity.duel && <span className="text-[11.5px] text-txt-muted">{t('friends.activity.game', { version: p.activity.mc_version })}</span>}
                {p.activity.server && <span className="truncate font-mono text-[11.5px] text-txt-secondary">{p.activity.server}</span>}
              </div>
            </div>
          </Card>
        )}
        {/* Dans le launcher, ou « vu il y a… » hors ligne. */}
        {p.activity?.kind !== 'game' && (p.activity || p.last_seen) && (
          <Card title={t('friends.profile.activity')}>
            <PresenceLine presence={{ ...p, custom: null }} />
          </Card>
        )}

        <Card title={t('friends.profile.minecraft')}>
          {friend.minecraft.length === 0 ? (
            <p className="text-[12px] text-txt-muted">{t('friends.profile.noMinecraft')}</p>
          ) : (
            <div className="flex flex-col gap-2">
              {friend.minecraft.map((m) => (
                <div key={m.uuid} className="flex items-center gap-2.5">
                  <SkinFace dataUri={`https://mc-heads.net/skin/${m.uuid}`} size={22} className="rounded-[5px]" />
                  <span className="truncate text-[12.5px] font-semibold">{m.name}</span>
                </div>
              ))}
            </div>
          )}
        </Card>

        <p className="px-1 text-[11px] text-txt-muted">
          {t('friends.profile.friendsSince', { date: new Date(friend.since).toLocaleDateString(dateLocale(language), { day: 'numeric', month: 'long', year: 'numeric' }) })}
        </p>

        {/* Montrer mon serveur à CET ami : seulement quand je le choisis ami par ami. */}
        {serverVisibility === 'some' && (
          <div className="flex items-center justify-between gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2.5">
            <span className="text-[12.5px] font-medium">{t('friends.profile.shareServer')}</span>
            <Toggle checked={friend.server_shared} onChange={() => void act(() => api.friends.setServerShare(friend.user_id, !friend.server_shared), false)} />
          </div>
        )}

        <div className="mt-1 flex flex-col gap-2">
          <DangerButton
            label={confirm === 'remove' ? t('friends.profile.confirmRemove') : t('friends.profile.remove')}
            armed={confirm === 'remove'}
            disabled={busy}
            onClick={() => (confirm === 'remove' ? void act(() => api.friends.remove(friend.user_id), true) : setConfirm('remove'))}
          />
          <DangerButton
            label={confirm === 'block' ? t('friends.profile.confirmBlock') : t('friends.profile.block')}
            armed={confirm === 'block'}
            disabled={busy}
            onClick={() => (confirm === 'block' ? void act(() => api.friends.block(friend.user_id), true) : setConfirm('block'))}
          />
        </div>
      </div>
    </motion.div>
  )
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-line bg-surface-1 px-3 py-2.5">
      <p className="mb-2 text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{title}</p>
      {children}
    </div>
  )
}

function DangerButton({ label, armed, disabled, onClick }: { label: string; armed: boolean; disabled: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`rounded-xl border px-3 py-2 text-[12.5px] font-semibold transition-colors disabled:opacity-50 ${
        armed ? 'border-danger/60 bg-danger/20 text-danger' : 'border-line text-txt-secondary hover:border-danger/40 hover:text-danger'
      }`}
    >
      {label}
    </button>
  )
}
