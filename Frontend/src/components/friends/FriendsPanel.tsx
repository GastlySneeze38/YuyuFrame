import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { groupFriends, skinOf } from '@/lib/friends'
import { friendLinkOf } from '@/lib/joinLink'
import { showError } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { Friend, FriendRequest, LookupResult } from '@/types/friends'
import { FriendAvatar, FriendsIcon, GameTile, PresenceLine } from './FriendBits'
import { FriendProfile } from './FriendProfile'
import { InviteList } from './InviteCards'
import { FriendSettingsView } from './FriendSettingsView'
import { prefetchFriendProfile } from '@/lib/friendProfileCache'
import { useProgressiveList } from '@/hooks/useProgressiveList'
import { SwipeTabs } from '@/mobile/ui/SwipeTabs'
import { MyStatusBar } from './MyStatusBar'
import { MyProfileView } from './MyProfileView'
import { AccountMenu } from '@/components/account/AccountMenu'
import { DrawerPanel } from '@/components/ui/DrawerPanel'
import { useIsMobile } from '@/mobile/useIsMobile'

type View = { kind: 'list' } | { kind: 'settings' } | { kind: 'me' } | { kind: 'profile'; userId: string }
type Section = 'invites' | 'requests' | 'outgoing' | 'inGame' | 'online' | 'offline'

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
  const t = useT()
  // Panneau à droite sur PC, feuille du bas sur téléphone (`DrawerPanel`).
  return (
    <DrawerPanel open={open} onClose={onClose} label={t('friends.title')}>
      <PanelBody onClose={onClose} />
    </DrawerPanel>
  )
}

function PanelBody({ onClose }: { onClose: () => void }) {
  const t = useT()
  const mobile = useIsMobile()
  const navigate = useNavigate()
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const list = useFriends((s) => s.list)
  const refresh = useFriends((s) => s.refresh)
  const markRequestsSeen = useFriends((s) => s.markRequestsSeen)
  const [view, setView] = useState<View>({ kind: 'list' })
  /** Les amis d'un côté, les demandes de l'autre : on ne mélange pas les
   *  gens qu'on connaît et ceux qui frappent à la porte. */
  const invites = useFriends((s) => s.invites)
  // Une invitation en attente : le panneau s'ouvre là où on y répond.
  const [tab, setTab] = useState<'friends' | 'requests'>(() => (useFriends.getState().invites.length > 0 ? 'requests' : 'friends'))
  const [searching, setSearching] = useState(false)
  /** Incrémenté pour mettre le curseur dans la barre d'ajout, en bas des demandes. */
  const [addFocus, setAddFocus] = useState(0)
  const openTab = (next: 'friends' | 'requests') => {
    if (next !== tab) setSearch('')
    setTab(next)
  }
  /** Ajouter un ami se fait depuis l'onglet des demandes, par sa barre du bas. */
  const goAdd = () => {
    setView({ kind: 'list' })
    openTab('requests')
    setAddFocus((n) => n + 1)
  }
  const [search, setSearch] = useState('')
  const [collapsed, setCollapsed] = useState<Set<Section>>(new Set())
  const [loading, setLoading] = useState(false)
  /** Un code venu d'un lien d'ami : la barre d'ajout le cherche aussitôt. */
  const [prefill, setPrefill] = useState<{ code: string; n: number } | null>(null)
  const addSeq = useFriends((s) => s.addRequest?.seq ?? 0)
  useEffect(() => {
    const code = useFriends.getState().takeAddRequest()
    if (!code) return
    setView({ kind: 'list' })
    setSearch('')
    setTab('requests')
    setPrefill({ code, n: addSeq })
  }, [addSeq])

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
  // La recherche vaut pour l'onglet ouvert : dans les demandes, elle les filtre.
  const needle = search.trim().toLowerCase()
  const matches = (r: FriendRequest) => !needle || r.username.toLowerCase().includes(needle)
  const incoming = (list?.incoming ?? []).filter(matches)
  const outgoing = (list?.outgoing ?? []).filter(matches)
  const profile = view.kind === 'profile' ? list?.friends.find((f) => f.id === view.userId) : undefined
  const toggle = (s: Section) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(s)) next.delete(s)
      else next.add(s)
      return next
    })

  const title =
    view.kind === 'settings'
      ? t('friends.settings.title')
      : view.kind === 'profile'
        ? t('friends.profile.title')
        : view.kind === 'me'
          ? t('friends.me.title')
          : t('friends.title')

  return (
    <>
      {/* En-tête. Au-dessus du corps (`z-30`) : le menu du compte s'ouvre
          vers le bas, et l'en-tête d'une fiche (`z-20` dans `ProfileLayout`)
          passait par-dessus. */}
      <div className="relative z-30 flex shrink-0 items-center gap-1 border-b border-line-soft px-3 py-2.5">
        {view.kind !== 'list' ? (
          <IconButton label={t('friends.back')} onClick={() => setView({ kind: 'list' })} path="M15 6l-6 6 6 6" />
        ) : (
          <FriendsIcon className="ml-1 mr-1 h-[18px] w-[18px] text-txt-secondary" />
        )}
        <span className="flex-1 text-[15px] font-black">{title}</span>
        {view.kind === 'list' && signedIn && list && (
          <>
            <IconButton
              active={searching}
              label={t(tab === 'requests' ? 'friends.searchRequests' : 'friends.search')}
              onClick={() => {
                if (searching) setSearch('')
                setSearching(!searching)
              }}
              path="M11 18a7 7 0 100-14 7 7 0 000 14zM20 20l-4-4"
            />
            <IconButton label={t('friends.add')} onClick={goAdd} path="M15 19v-1a4 4 0 00-4-4H6a4 4 0 00-4 4v1M8.5 10a3.5 3.5 0 100-7 3.5 3.5 0 000 7zM20 8v6M23 11h-6" />
            <IconButton label={t('friends.settings.title')} onClick={() => setView({ kind: 'settings' })} path="M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 01-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 010-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 014 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 010 4h-.1a1.7 1.7 0 00-1.5 1z" />
          </>
        )}
        {/* Mon compte YuyuFrame : l'initiale sur la couleur du plan. */}
        <span className="mx-1">
          <AccountMenu />
        </span>
        {/* Pas de croix sur téléphone : la poignée et le glissé ferment la feuille. */}
        {!mobile && <IconButton label={t('common.close')} onClick={onClose} path="M6 6l12 12M18 6L6 18" />}
      </div>

      {view.kind === 'list' && signedIn && list && (
        <div className="flex shrink-0 gap-1 border-b border-line-soft px-3 py-2">
          {(['friends', 'requests'] as const).map((key) => {
            const count = key === 'friends' ? list.friends.length : list.incoming.length + invites.length
            return (
              <button
                key={key}
                onClick={() => openTab(key)}
                className={`relative flex flex-1 items-center justify-center gap-1.5 rounded-lg px-2 py-1.5 text-[12.5px] font-semibold transition-colors ${
                  tab === key ? 'text-white' : 'text-txt-muted hover:text-txt-secondary'
                }`}
              >
                {tab === key && <motion.span layoutId="friends-tab" className="absolute inset-0 rounded-lg bg-white/[0.07]" transition={{ type: 'spring', stiffness: 500, damping: 38 }} />}
                <span className="relative">{t(`friends.tabs.${key}`)}</span>
                {count > 0 && (
                  <span
                    className={`relative flex h-[18px] min-w-[18px] items-center justify-center rounded-full px-1 text-[10.5px] font-black ${
                      key === 'requests' ? 'bg-danger text-white' : 'bg-white/10 text-txt-secondary'
                    }`}
                  >
                    {count}
                  </span>
                )}
              </button>
            )
          })}
        </div>
      )}

      {/* Corps. Au doigt, balayer la liste passe d'Amis à Demandes
          (`SwipeTabs`) ; hors de la liste (fiche, réglages), rien. */}
      <SwipeTabs tabs={view.kind === 'list' && signedIn && list ? (['friends', 'requests'] as const) : []} value={tab} onChange={openTab}>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {!signedIn ? (
          <Empty text={t('friends.signIn')} action={t('friends.signInButton')} onAction={() => { onClose(); navigate('/yuyu') }} />
        ) : !list ? (
          loading ? <Empty text={t('common.loading')} /> : <Empty text={t('friends.unavailable')} action={t('friends.retry')} onAction={() => { setLoading(true); void refresh().finally(() => setLoading(false)) }} />
        ) : view.kind === 'settings' ? (
          <FriendSettingsView />
        ) : view.kind === 'me' ? (
          <MyProfileView />
        ) : view.kind === 'profile' && profile ? (
          <FriendProfile friend={profile} onDone={() => setView({ kind: 'list' })} />
        ) : (
          <div className="flex flex-col pb-3">
            <AnimatePresence initial={false}>
              {searching && (
                <ToolRow key="search">
                  <input
                    autoFocus
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    placeholder={t(tab === 'requests' ? 'friends.searchRequestsPlaceholder' : 'friends.searchPlaceholder')}
                    className="w-full rounded-lg border border-line bg-black/30 px-3 py-2 text-[12.5px] outline-none placeholder:text-txt-muted focus:border-accent/60"
                  />
                </ToolRow>
              )}
            </AnimatePresence>

            {tab === 'requests' ? (
              <>
                {invites.length > 0 && (
                  <Group label={t('friends.invites.title')} count={invites.length} collapsed={collapsed.has('invites')} onToggle={() => toggle('invites')} accent>
                    <InviteList />
                  </Group>
                )}
                {incoming.length > 0 && (
                  <Group label={t('friends.sections.requests')} count={incoming.length} collapsed={collapsed.has('requests')} onToggle={() => toggle('requests')} accent>
                    {incoming.map((r) => (
                      <RequestRow key={r.id} request={r} incoming />
                    ))}
                  </Group>
                )}
                {outgoing.length > 0 && (
                  <Group label={t('friends.sections.outgoing')} count={outgoing.length} collapsed={collapsed.has('outgoing')} onToggle={() => toggle('outgoing')}>
                    {outgoing.map((r) => (
                      <RequestRow key={r.id} request={r} />
                    ))}
                  </Group>
                )}
                {invites.length === 0 && incoming.length === 0 && outgoing.length === 0 && (
                  <Empty text={t(search.trim() ? 'friends.noMatch' : 'friends.noRequests')} />
                )}
              </>
            ) : (
              <>
            {groups.inGame.length > 0 && (
              <Group label={t('friends.sections.inGame')} count={groups.inGame.length} collapsed={collapsed.has('inGame')} onToggle={() => toggle('inGame')}>
                <FriendRows friends={groups.inGame} onOpen={(f) => setView({ kind: 'profile', userId: f.id })} />
              </Group>
            )}
            {groups.online.length > 0 && (
              <Group label={t('friends.sections.online')} count={groups.online.length} collapsed={collapsed.has('online')} onToggle={() => toggle('online')}>
                <FriendRows friends={groups.online} onOpen={(f) => setView({ kind: 'profile', userId: f.id })} />
              </Group>
            )}
            {groups.offline.length > 0 && (
              <Group label={t('friends.sections.offline')} count={groups.offline.length} collapsed={collapsed.has('offline')} onToggle={() => toggle('offline')}>
                <FriendRows friends={groups.offline} onOpen={(f) => setView({ kind: 'profile', userId: f.id })} dim />
              </Group>
            )}
            {list.friends.length === 0 && <Empty text={t('friends.empty')} action={t('friends.add')} onAction={goAdd} />}
            {list.friends.length > 0 && search.trim() && groups.inGame.length + groups.online.length + groups.offline.length === 0 && (
              <Empty text={t('friends.noMatch')} />
            )}
              </>
            )}
          </div>
        )}
      </div>
      </SwipeTabs>

      {view.kind === 'list' && signedIn && list && tab === 'requests' && <AddFriendBar focusSignal={addFocus} prefill={prefill} />}
      {signedIn && list && <MyStatusBar onOpenProfile={() => setView({ kind: 'me' })} />}
    </>
  )
}

/**
 * Les lignes d'un groupe d'amis, dessinées 30 par 30 en approchant du bas
 * (`useProgressiveList`) : jusqu'à 200 amis, chacun avec sa tête de joueur,
 * ne se dessinent plus d'un coup. La liste vient toujours en une réponse du
 * serveur : elle est triée en groupes (En jeu / En ligne / Hors ligne) et
 * tenue à jour ami par ami par le flux en direct, ce qu'une liste coupée en
 * pages ne permettrait pas.
 */
function FriendRows({ friends, onOpen, dim = false }: { friends: Friend[]; onOpen: (f: Friend) => void; dim?: boolean }) {
  const { visible, sentinel } = useProgressiveList(friends.length, { step: 30 })
  return (
    <>
      {friends.slice(0, visible).map((f) => (
        <FriendRow key={f.id} friend={f} onOpen={() => onOpen(f)} dim={dim} />
      ))}
      {visible < friends.length && <div ref={sentinel} aria-hidden className="h-px" />}
    </>
  )
}

function FriendRow({ friend, onOpen, dim = false }: { friend: Friend; onOpen: () => void; dim?: boolean }) {
  const activity = friend.presence.activity
  return (
    <button
      onClick={onOpen}
      // Dès l'appui, la fiche commence à charger : elle a le temps de son
      // entrée pour arriver (lib/friendProfileCache.ts).
      onPointerDown={() => prefetchFriendProfile(friend.id)}
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
      <FriendAvatar skin={skinOf(request)} state={null} />
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

/** Un code ami bien formé : « Ghasty#4821 ». */
const CODE_PATTERN = /^[A-Za-z0-9_.-]{3,32}#\d{1,4}$/

/**
 * Ajouter un ami, en bas de l'onglet des demandes, par son **code ami**
 * (« Ghasty#4821 ») — le pseudo seul ne suffit pas, exprès. On cherche
 * d'abord : l'aperçu montre à qui on s'adresse (sa tête, ce qu'il montre à
 * tout le monde de son profil), puis on envoie. Au-dessus, mon propre code, à
 * copier pour le donner.
 */
function AddFriendBar({ focusSignal, prefill }: { focusSignal: number; prefill: { code: string; n: number } | null }) {
  const t = useT()
  const reveal = useReveal()
  const refresh = useFriends((s) => s.refresh)
  const myCode = useFriends((s) => s.list?.me.code ?? null)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [found, setFound] = useState<LookupResult | null>(null)
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null)
  const [copied, setCopied] = useState<'code' | 'link' | null>(null)
  const input = useRef<HTMLInputElement>(null)

  // Le bouton « ajouter » de l'en-tête amène ici : le curseur suit.
  useEffect(() => {
    if (focusSignal > 0) input.current?.focus()
  }, [focusSignal])

  // Un lien d'ami cliqué : son code est cherché tout de suite.
  useEffect(() => {
    if (!prefill) return
    setCode(prefill.code)
    void search(prefill.code)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefill])

  const valid = CODE_PATTERN.test(code.trim().replace(/^#/, ''))

  async function search(value = code) {
    const cleaned = value.trim().replace(/^#/, '')
    if (!CODE_PATTERN.test(cleaned)) return
    setBusy(true)
    setResult(null)
    setFound(null)
    try {
      setFound(await api.friends.lookup(cleaned))
    } catch (e) {
      setResult({ ok: false, text: errorMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  async function send(target: LookupResult) {
    setBusy(true)
    try {
      const sent = await api.friends.request({ id: target.id })
      setResult({ ok: true, text: sent.status === 'friends' ? t('friends.nowFriends', { name: sent.username }) : t('friends.sent', { name: sent.username }) })
      setFound(null)
      setCode('')
      await refresh()
    } catch (e) {
      setResult({ ok: false, text: errorMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  async function copyMine(what: 'code' | 'link') {
    if (!myCode) return
    try {
      await navigator.clipboard.writeText(what === 'code' ? myCode : friendLinkOf(myCode))
      setCopied(what)
      setTimeout(() => setCopied(null), 1500)
    } catch {
      // Presse-papiers refusé : le code reste lisible à l'écran.
    }
  }

  return (
    <div className="shrink-0 border-t border-line-soft px-3 pb-3 pt-2.5">
      <div className="mb-2 flex items-center justify-between gap-2">
        <p className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('friends.add')}</p>
        {myCode && (
          <div className="flex min-w-0 items-center gap-1">
            <button onClick={() => void copyMine('code')} title={t('friends.copyMyCode')} className="flex min-w-0 items-center gap-1.5 text-[11px] text-txt-muted hover:text-white">
              <span className="shrink-0">{t('friends.myCode')}</span>
              <span className="truncate font-mono font-bold text-accent-hover">{copied === 'code' ? t('friends.profile.copied') : myCode}</span>
            </button>
            {/* Le lien à coller sur Discord : un clic ouvre YuyuFrame sur l'ajout. */}
            <button
              onClick={() => void copyMine('link')}
              title={`${t('friends.discord.copyLink')} — ${t('friends.discord.linkHint')}`}
              aria-label={t('friends.discord.copyLink')}
              className={`shrink-0 rounded-md p-1 transition-colors hover:bg-white/[0.06] ${copied === 'link' ? 'text-[#5fd38d]' : 'text-txt-muted hover:text-white'}`}
            >
              {copied === 'link' ? (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.6} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
                  <path d="M5 12l5 5 9-10" />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
                  <path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1" />
                  <path d="M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1" />
                </svg>
              )}
            </button>
          </div>
        )}
      </div>

      <AnimatePresence initial={false}>
        {found && (
          <motion.div {...reveal} className="overflow-hidden">
            <LookupCard found={found} busy={busy} onSend={() => void send(found)} onClose={() => setFound(null)} />
          </motion.div>
        )}
      </AnimatePresence>

      <div className="flex gap-2">
        <label className="flex min-w-0 flex-1 items-center rounded-lg border border-line bg-black/30 transition-colors focus-within:border-accent/60">
          <span className="select-none pl-3 pr-1 text-[14px] font-black text-accent-hover">#</span>
          <input
            ref={input}
            value={code}
            maxLength={40}
            onChange={(e) => {
              setCode(e.target.value)
              setFound(null)
            }}
            onKeyDown={(e) => e.key === 'Enter' && void search()}
            placeholder={t('friends.addPlaceholder')}
            className="min-w-0 flex-1 bg-transparent py-2 pr-3 text-[12.5px] outline-none placeholder:text-txt-muted"
          />
        </label>
        <button disabled={busy || !valid} onClick={() => void search()} className="shrink-0 rounded-lg bg-accent px-3 text-[12px] font-bold text-white disabled:opacity-40">
          {t('friends.search')}
        </button>
      </div>
      {result && <p className={`mt-1.5 text-[11.5px] ${result.ok ? 'text-[#5fd38d]' : 'text-danger'}`}>{result.text}</p>}
    </div>
  )
}

/** L'aperçu d'un code ami avant d'envoyer la demande. */
function LookupCard({ found, busy, onSend, onClose }: { found: LookupResult; busy: boolean; onSend: () => void; onClose: () => void }) {
  const t = useT()
  // Ce qui empêche (ou rend inutile) d'envoyer une demande.
  const blocker = found.relation === 'none' || found.relation === 'incoming' ? null : t(`friends.lookup.${found.relation}`)
  return (
    <div className="mb-2.5 flex items-center gap-3 rounded-xl border border-line bg-surface-1 p-2.5">
      <FriendAvatar skin={skinOf(found)} state={null} size={40} />
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-[13.5px] font-bold">{found.username}</span>
        <span className="truncate text-[11.5px] text-txt-muted">
          {blocker ?? found.profile.bio ?? (found.minecraft[0]?.name ? found.minecraft[0].name : t('friends.lookup.noPublic'))}
        </span>
      </div>
      {!blocker && (
        <button disabled={busy} onClick={onSend} className="shrink-0 rounded-lg bg-[#3ba55d] px-3 py-1.5 text-[12px] font-bold text-white disabled:opacity-50">
          {found.relation === 'incoming' ? t('friends.accept') : t('friends.send')}
        </button>
      )}
      <button onClick={onClose} aria-label={t('common.close')} className="shrink-0 rounded-lg p-1.5 text-txt-muted hover:bg-white/[0.06] hover:text-white">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" className="h-3.5 w-3.5">
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
      </button>
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
  const reveal = useReveal()
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
          <motion.div {...reveal} className="flex flex-col overflow-hidden">
            {children}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

function ToolRow({ children }: { children: React.ReactNode }) {
  const reveal = useReveal()
  return (
    <motion.div {...reveal} className="overflow-hidden">
      <div className="px-3 pt-3">{children}</div>
    </motion.div>
  )
}

/** Ouvrir ou fermer un bloc. Sur PC il se déplie (hauteur animée) ; sur
 *  téléphone il apparaît en fondu : animer `height` recalcule la mise en page
 *  de toute la liste à chaque image, ce qui saccade sur un petit appareil. */
const REVEAL_HEIGHT = {
  initial: { height: 0, opacity: 0 },
  animate: { height: 'auto', opacity: 1 },
  exit: { height: 0, opacity: 0 },
}
const REVEAL_FADE = {
  initial: { opacity: 0 },
  animate: { opacity: 1 },
  exit: { opacity: 0 },
  transition: { duration: 0.12 },
}
function useReveal() {
  return useIsMobile() ? REVEAL_FADE : REVEAL_HEIGHT
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
      className={`flex h-8 w-8 items-center justify-center rounded-lg transition-colors max-sm:h-11 max-sm:w-11 max-sm:rounded-xl ${active ? 'bg-white/[0.08] text-white' : 'text-txt-muted hover:bg-white/[0.06] hover:text-white'}`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-[17px] w-[17px] max-sm:h-5 max-sm:w-5">
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
