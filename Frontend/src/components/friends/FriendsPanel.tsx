import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { groupFriends, skinOf } from '@/lib/friends'
import { showError } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { Friend, FriendRequest } from '@/types/friends'
import { FriendAvatar, FriendsIcon, GameTile, PresenceLine } from './FriendBits'
import { FriendProfile } from './FriendProfile'
import { FriendSettingsView } from './FriendSettingsView'
import { MyStatusBar } from './MyStatusBar'

type View = { kind: 'list' } | { kind: 'settings' } | { kind: 'profile'; userId: number }
type Section = 'requests' | 'outgoing' | 'inGame' | 'online' | 'offline'

/**
 * Le panneau des amis, posé par-dessus la page (comme la liste d'amis de
 * Discord) : en jeu, en ligne, hors ligne, les demandes, son propre statut en
 * bas. Un clic sur un ami ouvre son profil dans le panneau ; l'engrenage, les
 * réglages. Échap ou un clic à côté referme.
 *
 * Rendu dans `document.body` : la bannière de l'accueil, d'où il s'ouvre, est
 * transformée par ses animations, et un `fixed` à l'intérieur s'y collerait.
 */
export function FriendsPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 flex justify-end bg-black/45 backdrop-blur-[2px]"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) onClose()
          }}
        >
          <motion.aside
            initial={{ x: 40, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            exit={{ x: 40, opacity: 0 }}
            transition={{ type: 'spring', stiffness: 380, damping: 36 }}
            className="m-2 flex w-[360px] max-w-[calc(100vw-16px)] flex-col overflow-hidden rounded-2xl border border-line bg-[#111116] text-txt-primary shadow-[0_24px_80px_rgba(0,0,0,0.7)]"
          >
            <PanelBody onClose={onClose} />
          </motion.aside>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  )
}

function PanelBody({ onClose }: { onClose: () => void }) {
  const t = useT()
  const navigate = useNavigate()
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const list = useFriends((s) => s.list)
  const refresh = useFriends((s) => s.refresh)
  const markRequestsSeen = useFriends((s) => s.markRequestsSeen)
  const [view, setView] = useState<View>({ kind: 'list' })
  const [tool, setTool] = useState<'search' | 'add' | null>(null)
  const [search, setSearch] = useState('')
  const [collapsed, setCollapsed] = useState<Set<Section>>(new Set(['outgoing']))
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    markRequestsSeen()
    if (signedIn) {
      setLoading(true)
      void refresh().finally(() => setLoading(false))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Échap : revenir à la liste, puis fermer.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (view.kind !== 'list') setView({ kind: 'list' })
      else onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [view, onClose])

  const groups = useMemo(() => groupFriends(list?.friends ?? [], search), [list, search])
  const profile = view.kind === 'profile' ? list?.friends.find((f) => f.user_id === view.userId) : undefined
  const toggle = (s: Section) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(s)) next.delete(s)
      else next.add(s)
      return next
    })

  const title = view.kind === 'settings' ? t('friends.settings.title') : view.kind === 'profile' ? t('friends.profile.title') : t('friends.title')

  return (
    <>
      {/* En-tête */}
      <div className="flex shrink-0 items-center gap-1 border-b border-line-soft px-3 py-2.5">
        {view.kind !== 'list' ? (
          <IconButton label={t('friends.back')} onClick={() => setView({ kind: 'list' })} path="M15 6l-6 6 6 6" />
        ) : (
          <FriendsIcon className="ml-1 mr-1 h-[18px] w-[18px] text-txt-secondary" />
        )}
        <span className="flex-1 text-[15px] font-black">{title}</span>
        {view.kind === 'list' && signedIn && list && (
          <>
            <IconButton active={tool === 'search'} label={t('friends.search')} onClick={() => setTool(tool === 'search' ? null : 'search')} path="M11 18a7 7 0 100-14 7 7 0 000 14zM20 20l-4-4" />
            <IconButton active={tool === 'add'} label={t('friends.add')} onClick={() => setTool(tool === 'add' ? null : 'add')} path="M15 19v-1a4 4 0 00-4-4H6a4 4 0 00-4 4v1M8.5 10a3.5 3.5 0 100-7 3.5 3.5 0 000 7zM20 8v6M23 11h-6" />
            <IconButton label={t('friends.settings.title')} onClick={() => setView({ kind: 'settings' })} path="M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 01-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 010-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 014 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 010 4h-.1a1.7 1.7 0 00-1.5 1z" />
          </>
        )}
        <IconButton label={t('common.close')} onClick={onClose} path="M6 6l12 12M18 6L6 18" />
      </div>

      {/* Corps */}
      <div className="min-h-0 flex-1 overflow-y-auto">
        {!signedIn ? (
          <Empty text={t('friends.signIn')} action={t('friends.signInButton')} onAction={() => { onClose(); navigate('/yuyu') }} />
        ) : !list ? (
          loading ? <Empty text={t('common.loading')} /> : <Empty text={t('friends.unavailable')} action={t('friends.retry')} onAction={() => { setLoading(true); void refresh().finally(() => setLoading(false)) }} />
        ) : view.kind === 'settings' ? (
          <FriendSettingsView />
        ) : view.kind === 'profile' && profile ? (
          <FriendProfile friend={profile} onDone={() => setView({ kind: 'list' })} />
        ) : (
          <div className="flex flex-col pb-3">
            <AnimatePresence initial={false}>
              {tool === 'search' && (
                <ToolRow key="search">
                  <input
                    autoFocus
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    placeholder={t('friends.searchPlaceholder')}
                    className="w-full rounded-lg border border-line bg-black/30 px-3 py-2 text-[12.5px] outline-none placeholder:text-txt-muted focus:border-accent/60"
                  />
                </ToolRow>
              )}
              {tool === 'add' && (
                <ToolRow key="add">
                  <AddFriend />
                </ToolRow>
              )}
            </AnimatePresence>

            {list.incoming.length > 0 && (
              <Group label={t('friends.sections.requests')} count={list.incoming.length} collapsed={collapsed.has('requests')} onToggle={() => toggle('requests')} accent>
                {list.incoming.map((r) => (
                  <RequestRow key={r.id} request={r} incoming />
                ))}
              </Group>
            )}
            {groups.inGame.length > 0 && (
              <Group label={t('friends.sections.inGame')} count={groups.inGame.length} collapsed={collapsed.has('inGame')} onToggle={() => toggle('inGame')}>
                {groups.inGame.map((f) => (
                  <FriendRow key={f.user_id} friend={f} onOpen={() => setView({ kind: 'profile', userId: f.user_id })} />
                ))}
              </Group>
            )}
            {groups.online.length > 0 && (
              <Group label={t('friends.sections.online')} count={groups.online.length} collapsed={collapsed.has('online')} onToggle={() => toggle('online')}>
                {groups.online.map((f) => (
                  <FriendRow key={f.user_id} friend={f} onOpen={() => setView({ kind: 'profile', userId: f.user_id })} />
                ))}
              </Group>
            )}
            {groups.offline.length > 0 && (
              <Group label={t('friends.sections.offline')} count={groups.offline.length} collapsed={collapsed.has('offline')} onToggle={() => toggle('offline')}>
                {groups.offline.map((f) => (
                  <FriendRow key={f.user_id} friend={f} onOpen={() => setView({ kind: 'profile', userId: f.user_id })} dim />
                ))}
              </Group>
            )}
            {list.outgoing.length > 0 && (
              <Group label={t('friends.sections.outgoing')} count={list.outgoing.length} collapsed={collapsed.has('outgoing')} onToggle={() => toggle('outgoing')}>
                {list.outgoing.map((r) => (
                  <RequestRow key={r.id} request={r} />
                ))}
              </Group>
            )}
            {list.friends.length === 0 && list.incoming.length === 0 && tool !== 'add' && (
              <Empty text={t('friends.empty')} action={t('friends.add')} onAction={() => setTool('add')} />
            )}
          </div>
        )}
      </div>

      {signedIn && list && <MyStatusBar />}
    </>
  )
}

function FriendRow({ friend, onOpen, dim = false }: { friend: Friend; onOpen: () => void; dim?: boolean }) {
  const activity = friend.presence.activity
  return (
    <button
      onClick={onOpen}
      className={`group mx-2 flex items-center gap-3 rounded-xl px-2 py-2 text-left transition-colors hover:bg-white/[0.05] ${dim ? 'opacity-60 hover:opacity-100' : ''}`}
    >
      <FriendAvatar skin={skinOf(friend)} state={friend.presence.state} />
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-[13.5px] font-bold">{friend.username}</span>
        <PresenceLine presence={friend.presence} />
      </span>
      {activity?.kind === 'game' && <GameTile activity={activity} />}
    </button>
  )
}

function RequestRow({ request, incoming = false }: { request: FriendRequest; incoming?: boolean }) {
  const t = useT()
  const language = useStore((s) => s.language)
  const refresh = useFriends((s) => s.refresh)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    try {
      await action()
      await refresh()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mx-2 flex items-center gap-3 rounded-xl px-2 py-2">
      <FriendAvatar skin={`https://mc-heads.net/skin/MHF_Steve`} state={null} />
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-[13.5px] font-bold">{request.username}</span>
        <span className="truncate text-[11.5px] text-txt-muted">
          {incoming ? t('friends.requestIncoming') : t('friends.requestOutgoing')} · {new Date(request.created_at).toLocaleDateString(language === 'sga' ? 'fr' : language)}
        </span>
      </span>
      {incoming ? (
        <>
          <RoundButton disabled={busy} tone="ok" label={t('friends.accept')} onClick={() => void run(() => api.friends.accept(request.id))} path="M5 12.5l4.5 4.5L19 7.5" />
          <RoundButton disabled={busy} tone="no" label={t('friends.decline')} onClick={() => void run(() => api.friends.decline(request.id))} path="M6 6l12 12M18 6L6 18" />
        </>
      ) : (
        <RoundButton disabled={busy} tone="no" label={t('friends.cancel')} onClick={() => void run(() => api.friends.cancel(request.id))} path="M6 6l12 12M18 6L6 18" />
      )}
    </div>
  )
}

function AddFriend() {
  const t = useT()
  const refresh = useFriends((s) => s.refresh)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null)

  async function send() {
    const value = name.trim()
    if (!value) return
    setBusy(true)
    setResult(null)
    try {
      const sent = await api.friends.request(value)
      setResult({ ok: true, text: sent.status === 'friends' ? t('friends.nowFriends', { name: sent.username }) : t('friends.sent', { name: sent.username }) })
      setName('')
      await refresh()
    } catch (e) {
      setResult({ ok: false, text: errorMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex gap-2">
        <input
          autoFocus
          value={name}
          maxLength={32}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && void send()}
          placeholder={t('friends.addPlaceholder')}
          className="min-w-0 flex-1 rounded-lg border border-line bg-black/30 px-3 py-2 text-[12.5px] outline-none placeholder:text-txt-muted focus:border-accent/60"
        />
        <button disabled={busy || !name.trim()} onClick={() => void send()} className="rounded-lg bg-accent px-3 text-[12px] font-bold text-white disabled:opacity-40">
          {t('friends.send')}
        </button>
      </div>
      {result && <p className={`text-[11.5px] ${result.ok ? 'text-[#5fd38d]' : 'text-danger'}`}>{result.text}</p>}
    </div>
  )
}

function Group({ label, count, collapsed, onToggle, accent = false, children }: {
  label: string
  count: number
  collapsed: boolean
  onToggle: () => void
  accent?: boolean
  children: React.ReactNode
}) {
  return (
    <div className="mt-3 flex flex-col">
      <button onClick={onToggle} className="mx-4 mb-1 flex items-center gap-1.5 text-[11.5px] font-bold text-txt-muted hover:text-txt-secondary">
        <span className={accent ? 'text-accent-hover' : ''}>
          {label} · {count}
        </span>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" className={`h-3 w-3 transition-transform ${collapsed ? '-rotate-90' : ''}`}>
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
      <AnimatePresence initial={false}>
        {!collapsed && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="flex flex-col overflow-hidden">
            {children}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

function ToolRow({ children }: { children: React.ReactNode }) {
  return (
    <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
      <div className="px-3 pt-3">{children}</div>
    </motion.div>
  )
}

function Empty({ text, action, onAction }: { text: string; action?: string; onAction?: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 px-8 py-12 text-center">
      <FriendsIcon className="h-9 w-9 text-txt-muted/60" />
      <p className="text-[12.5px] leading-relaxed text-txt-secondary">{text}</p>
      {action && onAction && (
        <button onClick={onAction} className="rounded-lg bg-accent px-3.5 py-2 text-[12px] font-bold text-white">
          {action}
        </button>
      )}
    </div>
  )
}

function IconButton({ label, onClick, path, active = false }: { label: string; onClick: () => void; path: string; active?: boolean }) {
  return (
    <button
      onClick={onClick}
      title={label}
      aria-label={label}
      className={`flex h-8 w-8 items-center justify-center rounded-lg transition-colors ${active ? 'bg-white/[0.08] text-white' : 'text-txt-muted hover:bg-white/[0.06] hover:text-white'}`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-[17px] w-[17px]">
        <path d={path} />
      </svg>
    </button>
  )
}

function RoundButton({ label, onClick, path, tone, disabled }: { label: string; onClick: () => void; path: string; tone: 'ok' | 'no'; disabled: boolean }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      aria-label={label}
      className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-white/[0.06] transition-colors disabled:opacity-40 ${
        tone === 'ok' ? 'text-[#5fd38d] hover:bg-[#3ba55d]/25' : 'text-txt-muted hover:bg-danger/20 hover:text-danger'
      }`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.6} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
        <path d={path} />
      </svg>
    </button>
  )
}
