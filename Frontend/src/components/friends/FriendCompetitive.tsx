import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { DuelHistory } from '@/components/competitive/DuelHistory'
import { RankCard, RankTile } from '@/components/competitive/RankCard'
import { errorCode, errorMessage } from '@/lib/apiError'
import {
  MODES,
  bestMode,
  demoData,
  emptyStanding,
  readDuel,
  readStats,
  readSummaryStandings,
  type DuelRow,
  type ModeKey,
  type ModeStats,
  type Standing,
} from '@/lib/competitive'
import { isDemo } from '@/stores/useDevFlags'
import { useT } from '@/i18n'
import type { Friend } from '@/types/friends'

type Loaded =
  | { kind: 'loading' }
  | { kind: 'hidden' }
  | { kind: 'noAccount' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; standings: Standing[]; stats: Partial<Record<ModeKey, ModeStats>>; duels: DuelRow[] }

/**
 * La page compétitive d'un ami, en fenêtre par-dessus le launcher : son rang
 * par mode, quelques chiffres et ses derniers duels. Le serveur ne la donne
 * que s'il la montre à ses amis (réglage « Montrer mes pages compétitives »).
 */
export function FriendCompetitiveModal({ friend, onClose }: { friend: Friend; onClose: () => void }) {
  const t = useT()
  const [state, setState] = useState<Loaded>({ kind: 'loading' })
  const [mode, setMode] = useState<ModeKey | null>(null)

  useEffect(() => {
    let alive = true
    if (isDemo()) {
      const demo = demoData(friend.username)
      setState({ kind: 'ready', standings: demo.standings, stats: demo.stats ?? {}, duels: (demo.duels ?? []).slice(0, 30) })
      return
    }
    api.friends
      .competitive(friend.id)
      .then((answer) => {
        if (!alive) return
        if (!answer.account || !answer.summary) return setState({ kind: 'noAccount' })
        setState({
          kind: 'ready',
          standings: readSummaryStandings(answer.summary),
          stats: readStats(answer.summary),
          duels: answer.recent.map(readDuel),
        })
      })
      .catch((e) => {
        if (!alive) return
        if (errorCode(e) === 'forbidden') setState({ kind: 'hidden' })
        else setState({ kind: 'error', message: errorMessage(e) })
      })
    return () => {
      alive = false
    }
  }, [friend.id, friend.username])

  const ready = state.kind === 'ready' ? state : null
  const shown: ModeKey = mode ?? (ready ? bestMode(ready.standings) : 'mace')
  const standing = ready?.standings.find((s) => s.mode === shown) ?? emptyStanding(shown)
  const stats = ready?.stats[shown]
  const duels = useMemo(() => ready?.duels.filter((d) => d.mode === shown) ?? null, [ready, shown])

  return (
    <ModalShell title={t('friends.profile.competitiveTitle', { name: friend.username })} onClose={onClose} maxWidth="max-w-4xl" closeOnBackdrop>
      {state.kind === 'loading' ? (
        <div className="flex items-center justify-center py-20">
          <ButtonSpinner size={28} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
        </div>
      ) : !ready ? (
        <p className="py-14 text-center text-[13px] text-txt-secondary">
          {state.kind === 'hidden'
            ? t('friends.profile.competitiveHidden', { name: friend.username })
            : state.kind === 'noAccount'
              ? t('friends.profile.competitiveNoAccount', { name: friend.username })
              : state.kind === 'error'
                ? state.message
                : ''}
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          {/* Le mode : un bouton par mode, chacun avec son rang. */}
          <div className="flex gap-2">
            {MODES.map((m) => {
              const s = ready.standings.find((x) => x.mode === m) ?? emptyStanding(m)
              return (
                <button
                  key={m}
                  onClick={() => setMode(m)}
                  className={`flex items-center gap-2 rounded-xl border px-3 py-1.5 text-[12.5px] font-bold transition-colors ${
                    m === shown ? 'border-accent/50 bg-accent/[0.16] text-white' : 'border-line bg-white/[0.03] text-txt-muted hover:text-txt-secondary'
                  }`}
                >
                  <RankTile division={s.division} placed={s.placed} size={18} />
                  {t(`competitive.modes.${m}`)}
                </button>
              )
            })}
          </div>

          <div className="grid h-[440px] grid-cols-[250px_minmax(0,1fr)] gap-3">
            <div className="flex min-h-0 flex-col gap-3">
              <div className="min-h-0 flex-1">
                <RankCard standing={standing} />
              </div>
              {stats && <StatsLines stats={stats} />}
            </div>
            <DuelHistory duels={duels} />
          </div>
        </div>
      )}
    </ModalShell>
  )
}

/** Les quelques chiffres sous le rang. */
function StatsLines({ stats }: { stats: ModeStats }) {
  const t = useT()
  const played = stats.wins + stats.losses + stats.draws
  const rows: [string, string][] = [
    [t('competitive.stats.winRate'), played ? `${Math.round((stats.wins / played) * 100)} %` : '—'],
    [t('competitive.stats.avgScore'), stats.avgScore ? String(stats.avgScore) : '—'],
    [t('competitive.stats.currentStreak'), `${stats.currentStreak} · ${t('competitive.stats.bestStreakSub', { count: stats.bestStreak })}`],
  ]
  return (
    <div className="flex shrink-0 flex-col gap-1.5 rounded-2xl border border-line bg-surface-1 px-4 py-3">
      <p className="text-[11px] font-bold text-txt-muted">{t('competitive.stats.record', { wins: stats.wins, losses: stats.losses })}</p>
      {rows.map(([label, value]) => (
        <div key={label} className="flex items-baseline justify-between gap-3">
          <span className="text-[12px] text-txt-secondary">{label}</span>
          <span className="text-[12.5px] font-bold tabular-nums">{value}</span>
        </div>
      ))}
    </div>
  )
}
