import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { openExternal as open } from '@/lib/openExternal'
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

      <DiscordSection />

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

/**
 * Le compte Discord lié : le lier (dans le navigateur, la suite arrive par le
 * flux), ce que mes amis en voient, et les invitations en message privé.
 */
function DiscordSection() {
  const t = useT()
  const discord = useFriends((s) => s.discord)
  const refreshDiscord = useFriends((s) => s.refreshDiscord)
  const [waiting, setWaiting] = useState(false)
  const [confirmUnlink, setConfirmUnlink] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    void refreshDiscord()
  }, [refreshDiscord])
  // Le compte est lié : l'attente s'arrête.
  useEffect(() => {
    if (discord?.linked) setWaiting(false)
  }, [discord?.linked])

  // Pas encore de réponse : la carte s'affiche déjà, fermée.
  const linked = discord?.linked ?? null
  const available = discord?.available ?? false

  async function start() {
    try {
      const { url } = await api.discord.start()
      await open(url)
      setWaiting(true)
    } catch (e) {
      showError(e)
    }
  }

  async function update(settings: { show_to_friends?: boolean; dm_invites?: boolean }) {
    setSaving(true)
    try {
      await api.discord.update(settings)
      await refreshDiscord()
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  async function unlink() {
    try {
      await api.discord.unlink()
      setConfirmUnlink(false)
      await refreshDiscord()
    } catch (e) {
      showError(e)
    }
  }

  return (
    <Section title={t('friends.discord.title')}>
      {linked ? (
        <>
          <div className="flex items-center gap-3 py-2.5">
            <DiscordMark className="h-5 w-5 shrink-0 text-[#5865F2]" />
            <span className="min-w-0 flex-1 truncate text-[12.5px] font-medium">{t('friends.discord.linkedAs', { name: linked.global_name ?? linked.username })}</span>
            <button
              onClick={() => (confirmUnlink ? void unlink() : setConfirmUnlink(true))}
              onMouseLeave={() => setConfirmUnlink(false)}
              className={`shrink-0 rounded-lg border px-2.5 py-1 text-[11.5px] font-semibold transition-colors ${
                confirmUnlink ? 'border-danger/60 bg-danger/15 text-danger' : 'border-line text-txt-secondary hover:text-white'
              }`}
            >
              {confirmUnlink ? t('friends.discord.unlinkConfirm') : t('friends.discord.unlink')}
            </button>
          </div>
          <Row label={t('friends.discord.showToFriends')}>
            <Toggle checked={linked.show_to_friends} onChange={() => !saving && void update({ show_to_friends: !linked.show_to_friends })} />
          </Row>
          <Row label={t('friends.discord.dmInvites')} hint={t('friends.discord.dmNote')}>
            <Toggle checked={linked.dm_invites} onChange={() => !saving && void update({ dm_invites: !linked.dm_invites })} />
          </Row>
        </>
      ) : (
        <div className="flex flex-col gap-2.5 py-3">
          <p className="text-[11.5px] leading-relaxed text-txt-muted">{available ? t('friends.discord.hint') : t('friends.discord.unavailable')}</p>
          {available && (
            <button
              onClick={() => void start()}
              className="flex items-center justify-center gap-2 rounded-lg bg-[#5865F2] px-3 py-2 text-[12px] font-bold text-white transition-colors hover:bg-[#4752c4]"
            >
              <DiscordMark className="h-4 w-4" />
              {waiting ? t('friends.discord.waiting') : t('friends.discord.link')}
            </button>
          )}
        </div>
      )}
    </Section>
  )
}

/** Le logo de Discord, d'une couleur (celle du texte). */
export function DiscordMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className} aria-hidden>
      <path d="M20.32 4.37a19.8 19.8 0 0 0-4.89-1.52.07.07 0 0 0-.08.04c-.21.38-.44.87-.61 1.25a18.27 18.27 0 0 0-5.49 0 12.64 12.64 0 0 0-.62-1.25.08.08 0 0 0-.08-.04 19.74 19.74 0 0 0-4.89 1.52.07.07 0 0 0-.03.03C.53 9.05-.32 13.58.1 18.06a.08.08 0 0 0 .03.06 19.9 19.9 0 0 0 5.99 3.03.08.08 0 0 0 .08-.03c.46-.63.87-1.3 1.23-1.99a.08.08 0 0 0-.04-.11 13.1 13.1 0 0 1-1.87-.89.08.08 0 0 1-.01-.13l.37-.29a.07.07 0 0 1 .08-.01c3.93 1.79 8.18 1.79 12.06 0a.07.07 0 0 1 .08.01l.37.29a.08.08 0 0 1-.01.13c-.6.35-1.22.65-1.87.89a.08.08 0 0 0-.04.11c.36.7.77 1.36 1.23 1.99a.08.08 0 0 0 .08.03 19.84 19.84 0 0 0 6-3.03.08.08 0 0 0 .03-.05c.5-5.18-.84-9.67-3.55-13.66a.06.06 0 0 0-.03-.03zM8.02 15.33c-1.18 0-2.16-1.08-2.16-2.42 0-1.33.96-2.42 2.16-2.42 1.21 0 2.18 1.1 2.16 2.42 0 1.34-.96 2.42-2.16 2.42zm7.97 0c-1.18 0-2.15-1.08-2.15-2.42 0-1.33.95-2.42 2.15-2.42 1.21 0 2.18 1.1 2.16 2.42 0 1.34-.94 2.42-2.16 2.42z" />
    </svg>
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
