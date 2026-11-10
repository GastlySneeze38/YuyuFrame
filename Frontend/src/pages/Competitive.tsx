import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useFriends } from '@/stores/useFriends'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Skeleton, SkeletonRows } from '@/components/ui/Skeleton'
import { Button } from '@/components/ui/Button'
import { RankCard, RankTile, useRankName } from '@/components/competitive/RankCard'
import { Leaderboard } from '@/components/competitive/Leaderboard'
import { DuelHistory } from '@/components/competitive/DuelHistory'
import { SeasonRankView } from '@/components/competitive/SeasonRankView'
import { ServerStats } from '@/components/competitive/ServerStats'
import { StatsView } from '@/components/competitive/StatsView'
import { api } from '@/api/client'
import { errorCode, errorMessage } from '@/lib/apiError'
import { useStore } from '@/stores/useStore'
import { useDemo } from '@/stores/useDevFlags'
import { SNAP } from '@/lib/motion'
import { useT } from '@/i18n'
import { useIsMobile } from '@/mobile/useIsMobile'
import { MobileCompetitive } from '@/mobile/competitive/MobileCompetitive'
import { playerSkinSrc, preloadHeads } from '@/lib/heads'
import { cachedCompetitive, fetchMyCompetitive, fromSummary, loadMoreDuels } from '@/lib/competitiveLoad'
import {
  MODES,
  bestMode,
  demoData,
  emptyStanding,
  readBoard,
  readDuel,
  serverStats,
  type CompetitiveData,
  type ModeKey,
  type Standing,
} from '@/lib/competitive'

/**
 * Onglet Compétitif, un écran par chose, choisis dans l'en-tête avec le mode :
 * - **Historique** (ouvert d'abord) : le rang, les duels, les meilleurs
 *   serveurs (un serveur cliqué ouvre son détail et peut filtrer la liste) ;
 * - **Statistiques** : le rang, les chiffres du mode, la courbe des RR ;
 * - **Défis** : du jour et de la semaine ;
 * - **Classement** : le podium et la liste ;
 * - **Récompenses** : ce que chaque rang débloque.
 *
 * Le rang vient de LauncherAPI (`GET /v1/ranked/state/{uuid}`, le classement
 * vérifié que le jeu fait avancer). Le reste n'a pas encore de route : hors
 * développement, ces cartes disent « bientôt ». En développement, tout peut
 * être rempli par des données de démonstration (`demoData`) : interrupteur
 * dans l'atelier de développement (Ctrl+Maj+M, `stores/useDevFlags.ts`).
 *
 * `?friend=<identifiant public>` ouvre le même onglet sur un ami (bouton
 * « Compétitif » de sa fiche) : mêmes écrans, ses données
 * (`GET /v1/friends/{id}/competitive`), refusé s'il ne les montre pas.
 */

// Récompenses (`RewardsView`, saisons et cosmétiques) : retiré de la
// navigation le 2026-10-30, pour une mise à jour suivante. L'écran reste écrit.
// Missions : déplacées dans Outils (`pages/Missions.tsx`) le 2026-10-30, elles
// ne sont pas propres au PvP. À leur place, la grille de la saison.
const VIEWS = ['history', 'stats', 'season', 'leaderboard'] as const
type View = (typeof VIEWS)[number]

type Status =
  | { kind: 'loading' }
  | { kind: 'ready'; data: CompetitiveData }
  | { kind: 'signedOut' }
  | { kind: 'noAccount' }
  | { kind: 'offline' }
  | { kind: 'unavailable' }
  | { kind: 'unproven' }
  | { kind: 'friendHidden' }
  | { kind: 'friendNoAccount' }
  | { kind: 'error'; message: string }

export default function Competitive() {
  const t = useT()
  const navigate = useNavigate()
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const uuid = useStore((s) => s.uuid)
  const username = useStore((s) => s.username)
  const isOffline = useStore((s) => s.isOffline)

  // Données de démonstration : interrupteur de l'atelier de développement.
  const demo = useDemo()
  const [status, setStatus] = useState<Status>({ kind: 'loading' })
  const [mode, setMode] = useState<ModeKey | null>(null)
  // L'onglet ouvert sur un ami (bouton « Compétitif » de sa fiche), ou sur
  // un écran précis (`?view=season` : raccourci Saison de l'accueil).
  const [params] = useSearchParams()
  // L'historique d'abord ; la carte du rang y mène aux statistiques.
  const [view, setView] = useState<View>(() => {
    const asked = params.get('view')
    return VIEWS.includes(asked as View) ? (asked as View) : 'history'
  })
  const [server, setServer] = useState<string | null>(null)

  const friendId = params.get('friend')
  const friendName = useFriends((s) => s.list?.friends.find((f) => f.id === friendId)?.username ?? null)

  const load = useCallback(async () => {
    if (demo) {
      setStatus({ kind: 'ready', data: demoData(friendId ? friendName ?? 'Steve' : username ?? 'Steve') })
      return
    }
    if (!signedIn) return setStatus({ kind: 'signedOut' })
    if (isOffline) return setStatus({ kind: 'offline' })
    if (friendId) {
      setStatus({ kind: 'loading' })
      try {
        const [answer, boardMace, boardSword] = await Promise.allSettled([
          api.friends.competitive(friendId),
          api.ranked.leaderboard('mace'),
          api.ranked.leaderboard('sword'),
        ])
        if (answer.status === 'rejected') throw answer.reason
        const { account, summary, recent } = answer.value
        if (!summary || !account) return setStatus({ kind: 'friendNoAccount' })
        // Sa ligne est marquée (« Ami ») s'il est dans les premiers.
        const leaderboard =
          boardMace.status === 'fulfilled' && boardSword.status === 'fulfilled'
            ? { mace: readBoard(boardMace.value, account.uuid), sword: readBoard(boardSword.value, account.uuid) }
            : null
        setStatus({ kind: 'ready', data: fromSummary(summary, recent.map(readDuel), leaderboard) })
      } catch (e) {
        if (errorCode(e) === 'forbidden') setStatus({ kind: 'friendHidden' })
        else setStatus({ kind: 'error', message: errorMessage(e) })
      }
      return
    }
    if (!uuid) return setStatus({ kind: 'noAccount' })
    // Les dernières données connues d'abord (2026-11-06) : l'onglet s'ouvre
    // rempli, puis se met à jour — au lieu d'un écran de chargement à chaque
    // lancement. Préfixe `user:` : effacé à la déconnexion.
    const cached = cachedCompetitive(uuid)?.value
    setStatus(cached ? { kind: 'ready', data: cached } : { kind: 'loading' })
    try {
      setStatus({ kind: 'ready', data: await fetchMyCompetitive(uuid) })
    } catch (e) {
      const code = errorCode(e)
      if (code === 'ranked_unavailable') setStatus({ kind: 'unavailable' })
      else if (code === 'ranked_unproven') setStatus({ kind: 'unproven' })
      // Réseau en panne : les dernières données valent mieux qu'une erreur.
      else if (!cached) setStatus({ kind: 'error', message: errorMessage(e) })
    }
  }, [demo, signedIn, uuid, isOffline, username, friendId, friendName])

  useEffect(() => {
    void load()
  }, [load])

  const data = status.kind === 'ready' ? status.data : null
  // Les têtes des autres vues (l'autre mode, le classement, les adversaires)
  // se résolvent en arrière-plan : changer d'écran ne montre plus de carrés vides.
  useEffect(() => {
    if (!data) return
    const rows = [
      ...Object.values(data.leaderboard ?? {}).flat(),
      ...Object.values(data.friendsLeaderboard ?? {}).flat(),
    ]
    preloadHeads([
      ...rows.map((r) => playerSkinSrc(r.uuid, r.name)),
      ...(data.duels ?? []).slice(0, 60).map((d) => playerSkinSrc(d.opponentUuid, d.opponent)),
    ])
  }, [data])
  // Le mode montré d'abord : celui où l'on est le plus avancé.
  const shown: ModeKey = mode ?? (data ? bestMode(data.standings) : 'mace')
  const standing = data?.standings.find((s) => s.mode === shown) ?? emptyStanding(shown)
  const modeDuels = useMemo(() => data?.duels?.filter((d) => d.mode === shown) ?? null, [data, shown])
  const servers = useMemo(() => (modeDuels ? serverStats(modeDuels) : null), [modeDuels])
  // Un serveur choisi qui n'existe plus dans ce mode ne filtre rien.
  const activeServer = server && servers?.some((s) => s.server === server) ? server : null
  const duels = useMemo(
    () => (modeDuels && activeServer ? modeDuels.filter((d) => d.server === activeServer) : modeDuels),
    [modeDuels, activeServer],
  )

  // La suite de l'historique du mode, page par page, quand on arrive au bout
  // de ce qui est chargé (`useProgressiveList`). Pas chez un ami ni en démo :
  // leur historique arrive en une fois.
  const fetchingMore = useRef(false)
  const canLoadMore = !!data && !friendId && !demo && !!uuid && data.historyNext?.[shown] != null
  const onMoreDuels = useCallback(() => {
    if (!data || !uuid || fetchingMore.current) return
    fetchingMore.current = true
    loadMoreDuels(uuid, data, shown)
      .then((next) => setStatus((s) => (s.kind === 'ready' ? { kind: 'ready', data: next } : s)))
      .catch(() => {})
      .finally(() => { fetchingMore.current = false })
  }, [data, uuid, shown])
  const moreDuels = canLoadMore ? onMoreDuels : undefined

  const mobile = useIsMobile()
  const title = friendId ? t('friends.profile.competitiveTitle', { name: friendName ?? '…' }) : t('competitive.title')
  const statusCard = (
    <StatusCard status={status} friendName={friendName ?? '…'} onRetry={load} onSignIn={() => navigate('/yuyu?mode=login')} onAccounts={() => navigate('/login')} />
  )

  // Téléphone : mêmes écrans et mêmes données, une mise en page à part.
  if (mobile) {
    return (
      <MobileCompetitive
        title={title}
        friend={!!friendId}
        loading={status.kind === 'loading'}
        data={data}
        empty={statusCard}
        view={view}
        onView={setView}
        mode={shown}
        onMode={setMode}
        standing={standing}
        modeDuels={modeDuels}
        duels={duels}
        onMoreDuels={moreDuels}
        servers={servers}
        activeServer={activeServer}
        onServer={setServer}
        friendName={friendName}
      />
    )
  }

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      {/* Tout ce qui pilote la page vit dans l'en-tête : les écrans et le
          mode. Le contenu n'a ainsi plus de bande au-dessus de lui. */}
      <PageHeader>
        <PageHeaderSeparator />
        <h1 className="shrink-0 text-[16px] font-black leading-[1.2] tracking-[-0.01em]">
          {title}
        </h1>
        {/* Centré tant qu'il y a la place ; sinon calé à gauche, jamais
            par-dessus le titre (`safe`). */}
        <nav className="flex min-w-0 flex-1 [justify-content:safe_center]">
          {data && (
            <div className="flex shrink-0 rounded-xl bg-white/[0.04] p-1">
              {VIEWS.map((v) => (
                <button
                  key={v}
                  onClick={() => setView(v)}
                  className={`relative whitespace-nowrap rounded-lg px-2.5 py-1.5 text-[12px] font-bold transition-colors lg:px-3.5 ${
                    view === v ? 'text-white' : 'text-txt-muted hover:text-txt-secondary'
                  }`}
                >
                  {view === v && <motion.span layoutId="competitive-view" transition={SNAP} className="absolute inset-0 rounded-lg bg-accent/80" />}
                  <span className="relative">{t(`competitive.nav.${v}`)}</span>
                </button>
              ))}
            </div>
          )}
        </nav>
        {data && (
          <div className="w-[170px] shrink-0 lg:w-[230px]">
            <ModeDropdown value={shown} onChange={setMode} standings={data.standings} />
          </div>
        )}
      </PageHeader>

      {/* La page ne défile jamais : elle tient dans la fenêtre, et ce sont les
          listes (classement, défis, historique, serveurs) qui défilent dans
          leur carte. */}
      <div className="min-h-0 flex-1 overflow-hidden">
        <div className="mx-auto flex h-full w-full max-w-6xl flex-col gap-4 px-6 py-5">
          {status.kind === 'loading' ? (
            // Silhouette de l'historique : carte du rang, trois serveurs, duels.
            <div aria-busy className="flex flex-col gap-4">
              <Skeleton className="h-44 rounded-2xl" />
              <div className="grid grid-cols-3 gap-3">
                <Skeleton className="h-20 rounded-2xl" />
                <Skeleton className="h-20 rounded-2xl" />
                <Skeleton className="h-20 rounded-2xl" />
              </div>
              <SkeletonRows count={5} rowClassName="h-14" />
            </div>
          ) : !data ? (
            statusCard
          ) : (
            <>
              <motion.div
                key={`${view}-${shown}`}
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={SNAP}
                className="min-h-0 flex-1"
              >
                {view === 'stats' ? (
                  <StatsView duels={modeDuels} />
                ) : view === 'season' ? (
                  <SeasonRankView
                    wins={data.seasonWins ? data.seasonWins[shown] ?? [] : null}
                    count={data.seasonWinCount?.[shown]}
                    season={data.seasons?.[0] ?? null}
                    current={standing}
                    mode={shown}
                    readOnly={!!friendId}
                  />
                ) : view === 'leaderboard' ? (
                  <Leaderboard
                    rows={data.leaderboard ? data.leaderboard[shown] ?? [] : null}
                    friendRows={data.friendsLeaderboard ? data.friendsLeaderboard[shown] ?? [] : null}
                    placed={standing.placed}
                    subject={friendId ? friendName ?? '…' : undefined}
                  />
                ) : (
                  // Le rang à gauche, l'historique au milieu, les serveurs à
                  // droite : trois colonnes de toute la hauteur.
                  <div className="grid h-full grid-cols-[230px_minmax(0,1fr)_164px] gap-3 lg:grid-cols-[270px_minmax(0,1fr)_174px] lg:gap-4">
                    {/* Le rang mène aux statistiques. */}
                    <RankCard standing={standing} onOpen={() => setView('stats')} />
                    <DuelHistory duels={duels} onMore={moreDuels} resetKey={`${shown}|${activeServer}`} />
                    {/* Un écart plus large que celui de la grille : les icônes
                        n'ont pas de cadre, elles se colleraient à l'historique. */}
                    <div className="h-full min-h-0 pl-3 lg:pl-6">
                      <ServerStats servers={servers} selected={activeServer} onSelect={setServer} />
                    </div>
                  </div>
                )}
              </motion.div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * Le mode de jeu montré, en liste déroulante : d'autres modes viendront
 * s'ajouter à la masse et à l'épée, et des boutons côte à côte ne tiendraient
 * plus. Chaque mode y porte le rang qu'on y a.
 */
function ModeDropdown({
  value,
  onChange,
  standings,
}: {
  value: ModeKey
  onChange: (m: ModeKey) => void
  standings: Standing[]
}) {
  const t = useT()
  const rankName = useRankName()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  // Un clic ailleurs ou Échap referme la liste.
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  const standingOf = (m: ModeKey) => standings.find((s) => s.mode === m) ?? emptyStanding(m)
  const current = standingOf(value)

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        className={`flex w-full items-center gap-2.5 rounded-xl border px-3 py-2 text-left transition-colors ${
          open ? 'border-accent/50 bg-white/[0.06]' : 'border-line bg-white/[0.04] hover:bg-white/[0.06]'
        }`}
      >
        <RankTile division={current.division} placed={current.placed} size={22} />
        <span className="flex-1 text-[12.5px] font-bold">{t(`competitive.modes.${value}`)}</span>
        <span className="text-[11px] text-txt-muted">{rankName(current.division, current.placed)}</span>
        <motion.svg
          viewBox="0 0 24 24"
          className="h-4 w-4 text-txt-muted"
          fill="currentColor"
          animate={{ rotate: open ? 180 : 0 }}
          transition={SNAP}
        >
          <path d="M7 10l5 5 5-5z" />
        </motion.svg>
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={SNAP}
            className="absolute right-0 top-[calc(100%+6px)] z-30 flex w-full flex-col gap-0.5 rounded-xl border border-line bg-bg-card p-1 shadow-[0_16px_40px_rgba(0,0,0,0.5)]"
          >
            {MODES.map((m) => {
              const s = standingOf(m)
              return (
                <button
                  key={m}
                  onClick={() => {
                    onChange(m)
                    setOpen(false)
                  }}
                  className={`flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition-colors ${
                    m === value ? 'bg-accent/[0.16]' : 'hover:bg-white/[0.05]'
                  }`}
                >
                  <RankTile division={s.division} placed={s.placed} size={22} />
                  <span className="flex-1 text-[12.5px] font-semibold">{t(`competitive.modes.${m}`)}</span>
                  <span className="text-[11px] text-txt-muted">{rankName(s.division, s.placed)}</span>
                </button>
              )
            })}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

/** Pourquoi il n'y a rien à montrer, et quoi faire. */
function StatusCard({
  status,
  friendName,
  onRetry,
  onSignIn,
  onAccounts,
}: {
  status: Status
  friendName: string
  onRetry: () => void
  onSignIn: () => void
  onAccounts: () => void
}) {
  const t = useT()
  const text =
    status.kind === 'signedOut'
      ? t('competitive.status.notSignedIn')
      : status.kind === 'noAccount'
        ? t('competitive.status.noAccount')
        : status.kind === 'offline'
          ? t('competitive.status.offline')
          : status.kind === 'unavailable'
            ? t('competitive.status.unavailable')
            : status.kind === 'unproven'
              ? t('competitive.status.unproven')
              : status.kind === 'friendHidden'
                ? t('friends.profile.competitiveHidden', { name: friendName })
                : status.kind === 'friendNoAccount'
                  ? t('friends.profile.competitiveNoAccount', { name: friendName })
                  : status.kind === 'error'
                ? status.message
                : ''
  return (
    // Sur téléphone : texte de 15 px et bouton de toute la largeur, 48 px.
    <div className="mx-auto flex max-w-md flex-col items-center gap-4 rounded-2xl border border-line bg-surface-1 px-6 py-10 text-center max-sm:px-5 max-sm:py-8 max-sm:[&_button]:h-12 max-sm:[&_button]:w-full max-sm:[&_button]:text-[15px]">
      <p className="text-[13px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{text}</p>
      {status.kind === 'signedOut' && (
        <Button size="sm" variant="primary" onClick={onSignIn}>
          {t('competitive.status.signIn')}
        </Button>
      )}
      {(status.kind === 'noAccount' || status.kind === 'offline') && (
        <Button size="sm" onClick={onAccounts}>
          {t('competitive.status.accounts')}
        </Button>
      )}
      {(status.kind === 'error' || status.kind === 'unavailable' || status.kind === 'unproven') && (
        <Button size="sm" variant="ghost" onClick={onRetry}>
          {t('competitive.status.retry')}
        </Button>
      )}
    </div>
  )
}
