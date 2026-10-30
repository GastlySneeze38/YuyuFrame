import { useState } from 'react'
import { motion } from 'framer-motion'
import { api } from '@/api/client'
import { Toggle } from '@/components/ui/Toggle'
import { showError } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { FriendSettings, ServerVisibility } from '@/types/friends'

/**
 * Qui voit quoi, et ce dont on est prévenu. Tout part au serveur dès qu'on
 * le touche, sauf « prévenir quand un ami se connecte », qui est propre à ce
 * launcher.
 */
export function FriendSettingsView() {
  const t = useT()
  const list = useFriends((s) => s.list)
  const refresh = useFriends((s) => s.refresh)
  const notifyOnline = useStore((s) => s.friendOnlineNotify)
  const setNotifyOnline = useStore((s) => s.setFriendOnlineNotify)
  const osNotifications = useStore((s) => s.osNotifications)
  const setOsNotifications = useStore((s) => s.setOsNotifications)
  const [saving, setSaving] = useState(false)
  if (!list) return null
  const settings = list.settings

  async function save(next: Partial<FriendSettings>) {
    setSaving(true)
    try {
      await api.friends.setSettings({ ...settings, ...next })
      await refresh()
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  async function unblock(id: string) {
    try {
      await api.friends.unblock(id)
      await refresh()
    } catch (e) {
      showError(e)
    }
  }

  return (
    <motion.div initial={{ opacity: 0, x: 16 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 16 }} className="flex flex-col gap-4 p-4">
      <Section title={t('friends.settings.privacy')}>
        <Row label={t('friends.settings.acceptRequests')}>
          <Toggle checked={settings.accept_requests} onChange={() => !saving && void save({ accept_requests: !settings.accept_requests })} />
        </Row>
        <Row label={t('friends.settings.activity')} hint={t('friends.settings.activityHint')}>
          <Toggle
            checked={settings.activity_visibility === 'friends'}
            onChange={() => !saving && void save({ activity_visibility: settings.activity_visibility === 'friends' ? 'nobody' : 'friends' })}
          />
        </Row>
        <Row label={t('friends.settings.stats')}>
          <Toggle
            checked={settings.stats_visibility === 'friends'}
            onChange={() => !saving && void save({ stats_visibility: settings.stats_visibility === 'friends' ? 'nobody' : 'friends' })}
          />
        </Row>
        <div className="flex flex-col gap-2 py-2">
          <span className="text-[12.5px] font-medium">{t('friends.settings.server')}</span>
          <div className="grid grid-cols-3 gap-1 rounded-xl bg-black/30 p-1">
            {(['nobody', 'friends', 'some'] as ServerVisibility[]).map((v) => (
              <button
                key={v}
                disabled={saving}
                onClick={() => v !== settings.server_visibility && void save({ server_visibility: v })}
                className={`rounded-lg px-2 py-1.5 text-[11.5px] font-semibold transition-colors ${
                  settings.server_visibility === v ? 'bg-accent/35 text-white' : 'text-txt-muted hover:text-txt-secondary'
                }`}
              >
                {t(`friends.settings.server_${v}`)}
              </button>
            ))}
          </div>
          <p className="text-[11px] leading-relaxed text-txt-muted">{t(`friends.settings.serverHint_${settings.server_visibility}`)}</p>
        </div>
      </Section>

      <Section title={t('friends.settings.notifications')}>
        <Row label={t('friends.settings.notifyOnline')}>
          <Toggle checked={notifyOnline} onChange={() => setNotifyOnline(!notifyOnline)} />
        </Row>
        <Row label={t('friends.settings.osNotifications')} hint={t('friends.settings.osNotificationsHint')}>
          <Toggle checked={osNotifications} onChange={() => setOsNotifications(!osNotifications)} />
        </Row>
      </Section>

      <Section title={t('friends.settings.blocked')}>
        {list.blocked.length === 0 ? (
          <p className="py-1 text-[12px] text-txt-muted">{t('friends.settings.noBlocked')}</p>
        ) : (
          list.blocked.map((b) => (
            <Row key={b.id} label={b.username}>
              <button onClick={() => void unblock(b.id)} className="rounded-lg border border-line px-2.5 py-1 text-[11.5px] font-semibold text-txt-secondary hover:text-white">
                {t('friends.settings.unblock')}
              </button>
            </Row>
          ))
        )}
      </Section>
    </motion.div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col">
      <p className="mb-1 text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{title}</p>
      <div className="flex flex-col divide-y divide-white/[0.05] rounded-xl border border-line bg-surface-1 px-3">{children}</div>
    </div>
  )
}

function Row({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3 py-2.5">
      <div className="flex min-w-0 flex-col">
        <span className="truncate text-[12.5px] font-medium">{label}</span>
        {hint && <span className="text-[11px] text-txt-muted">{hint}</span>}
      </div>
      {children}
    </div>
  )
}
