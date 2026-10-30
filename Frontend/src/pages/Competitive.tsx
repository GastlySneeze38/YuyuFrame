import { useCallback, useEffect, useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { Button } from '@/components/ui/Button'
import { RankCard } from '@/components/competitive/RankCard'
import { Leaderboard } from '@/components/competitive/Leaderboard'
import { DuelHistory } from '@/components/competitive/DuelHistory'
import { api } from '@/api/client'
import { errorCode, errorMessage } from '@/lib/apiError'
import { useStore } from '@/stores/useStore'
import { SNAP } from '@/lib/motion'
import { useT } from '@/i18n'
import {
  MODES,
  bestMode,
  demoData,
  emptyStanding,
  readStandings,
  type CompetitiveData,
  type ModeKey,
  type ModeStats,
} from '@/lib/competitive'

/**
 * Onglet Compétitif : le rang du joueur par mode, le classement entre
 * joueurs, ses statistiques et l'historique de ses duels.
 *
 * Le rang vient de LauncherAPI (`GET /v1/ranked/state/{uuid}`, le classement
 * vérifié que le jeu fait avancer). Le reste n'a pas encore de route : hors
 * développement, ces cartes disent « bientôt ». En développement, tout est
 * rempli par des données de démonstration (`demoData`), avec un bouton pour
 * essayer la vraie route.
 */

type Status =
  | { kind: 'loading' }
  | { kind: 'ready'; data: CompetitiveData }
  | { kind: 'signedOut' }
  | { kind: 'noAccount' }
  | { kind: 'offline' }
  | { kind: 'unavailable' }
  | { kind: 'unproven' }
  | { kind: 'error'; message: string }

export default function Competitive() {
  const t = useT()
  const navigate = useNavigate()
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const uuid = useStore((s) => s.uuid)
  const username = useStore((s) => s.username)
  const isOffline = useStore((s) => s.isOffline)

  const [demo, setDemo] = useState<boolean>(import.meta.env.DEV)
  const [status, setStatus] = useState<Status>({ kind: 'loading' })
  const [mode, setMode] = useState<ModeKey | null>(null)

  const load = useCallback(async () => {
    if (demo) {
      setStatus({ kind: 'ready', data: demoData(username ?? 'Steve') })
      return
    }
    if (!signedIn) return setStatus({ kind: 'signedOut' })
    if (!uuid) return setStatus({ kind: 'noAccount' })
    if (isOffline) return setStatus({ kind: 'offline' })
    setStatus({ kind: 'loading' })
    try {
      const answer = await api.ranked.state(uuid)
      setStatus({ kind: 'ready', data: { standings: readStandings(answer), leaderboard: null, duels: null, stats: null } })
    } catch (e) {
      const code = errorCode(e)
      if (code === 'ranked_unavailable') setStatus({ kind: 'unavailable' })
      else if (code === 'ranked_unproven') setStatus({ kind: 'unproven' })
      else setStatus({ kind: 'error', message: errorMessage(e) })
    }
  }, [demo, signedIn, uuid, isOffline, username])

  useEffect(() => {
    void load()
  }, [load])

  const data = status.kind === 'ready' ? status.data : null
  // Le mode montré d'abord : celui où l'on est le plus avancé.
  const shown: ModeKey = mode ?? (data ? bestMode(data.standings) : 'mace')
  const standing = data?.standings.find((s) => s.mode === shown) ?? emptyStanding(shown)
  const duels = useMemo(() => data?.duels?.filter((d) => d.mode === shown) ?? null, [data, shown])

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div className="min-w-0 flex-1">
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em]">{t('competitive.title')}</h1>
          <p className="mt-px text-[10px] text-txt-muted">{t('competitive.subtitle')}</p>
        </div>
        {import.meta.env.DEV && (
          // Développement seulement : passer des données de démo à la vraie route.
          <button
            onClick={() => setDemo((v) => !v)}
            className={`rounded-full px-3 py-1 text-[10.5px] font-bold transition-colors ${
              demo ? 'bg-warning/[0.16] text-warning' : 'bg-white/[0.05] text-txt-muted hover:text-txt-secondary'
            }`}
          >
            {demo ? 'Données de démo' : 'Vraie route (dev)'}
          </button>
        )}
        <ModeSwitch value={shown} onChange={setMode} />
      </PageHeader>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-6 py-6">
          {status.kind === 'loading' ? (
            <div className="flex items-center justify-center py-24">
              <ButtonSpinner size={32} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
            </div>
          ) : !data ? (
            <StatusCard status={status} onRetry={load} onSignIn={() => navigate('/yuyu?mode=login')} onAccounts={() => navigate('/login')} />
          ) : (
            <motion.div key={shown} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={SNAP} className="flex flex-col gap-5">
              <div className="grid grid-cols-[1.1fr_1fr] gap-5">
                <RankCard standing={standing} />
                <StatsGrid stats={data.stats?.[shown] ?? null} />
              </div>
              <div className="grid grid-cols-[1fr_1.35fr] items-start gap-5">
                <Leaderboard rows={data.leaderboard ? data.leaderboard[shown] ?? [] : null} placed={standing.placed} />
                <DuelHistory duels={duels} />
              </div>
              <p className="pb-2 text-center text-[11px] text-txt-muted">{t('competitive.how')}</p>
            </motion.div>
          )}
        </div>
      </div>
    </div>
  )
}

/** Masse / Épée : un rang par mode. */
function ModeSwitch({ value, onChange }: { value: ModeKey; onChange: (m: ModeKey) => void }) {
  const t = useT()
  return (
    <div className="flex rounded-xl bg-white/[0.04] p-1">
      {MODES.map((m) => (
        <button
          key={m}
          onClick={() => onChange(m)}
          className={`relative rounded-lg px-3.5 py-1.5 text-[11.5px] font-bold transition-colors ${
            value === m ? 'text-white' : 'text-txt-muted hover:text-txt-secondary'
          }`}
        >
          {value === m && (
            <motion.span layoutId="competitive-mode" transition={SNAP} className="absolute inset-0 rounded-lg bg-accent/80" />
          )}
          <span className="relative">{t(`competitive.modes.${m}`)}</span>
        </button>
      ))}
    </div>
  )
}

/** Les chiffres du mode. `null` : pas encore de route pour ça. */
function StatsGrid({ stats }: { stats: ModeStats | null }) {
  const t = useT()
  if (!stats) {
    return (
      <div className="flex items-center justify-center rounded-2xl border border-line bg-surface-1 p-5">
        <p className="max-w-xs text-center text-[12px] leading-relaxed text-txt-muted">{t('competitive.stats.soon')}</p>
      </div>
    )
  }
  const played = stats.wins + stats.losses + stats.draws
  const winRate = played ? Math.round((stats.wins / played) * 100) : 0
  const tiles: { label: string; value: string; sub?: string; tone?: 'up' | 'down' }[] = [
    { label: t('competitive.stats.winRate'), value: `${winRate} %`, sub: t('competitive.stats.record', { wins: stats.wins, losses: stats.losses }) },
    { label: t('competitive.stats.avgScore'), value: String(stats.avgScore), sub: '/ 500' },
    { label: t('competitive.stats.bestScore'), value: String(stats.bestScore), sub: '/ 500' },
    { label: t('competitive.stats.bestStreak'), value: String(stats.bestStreak), sub: t('competitive.stats.wins') },
    { label: t('competitive.stats.currentStreak'), value: String(stats.currentStreak), sub: t('competitive.stats.wins') },
    {
      label: t('competitive.stats.rrWeek'),
      value: `${stats.rrWeek > 0 ? '+' : ''}${stats.rrWeek}`,
      sub: 'RR',
      tone: stats.rrWeek > 0 ? 'up' : stats.rrWeek < 0 ? 'down' : undefined,
    },
  ]
  return (
    <div className="grid grid-cols-3 gap-3">
      {tiles.map((tile, i) => (
        <motion.div
          key={tile.label}
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ ...SNAP, delay: i * 0.03 }}
          className="flex flex-col justify-between gap-2 rounded-2xl border border-line bg-surface-1 p-4"
        >
          <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{tile.label}</span>
          <div className="flex items-baseline gap-1.5">
            <span
              className={`text-[24px] font-black leading-none tabular-nums ${
                tile.tone === 'up' ? 'text-success' : tile.tone === 'down' ? 'text-danger' : 'text-txt-primary'
              }`}
            >
              {tile.value}
            </span>
            {tile.sub && <span className="truncate text-[10.5px] text-txt-muted">{tile.sub}</span>}
          </div>
        </motion.div>
      ))}
    </div>
  )
}

/** Pourquoi il n'y a rien à montrer, et quoi faire. */
function StatusCard({
  status,
  onRetry,
  onSignIn,
  onAccounts,
}: {
  status: Status
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
              : status.kind === 'error'
                ? status.message
                : ''
  return (
    <div className="mx-auto flex max-w-md flex-col items-center gap-4 rounded-2xl border border-line bg-surface-1 px-6 py-10 text-center">
      <p className="text-[13px] leading-relaxed text-txt-secondary">{text}</p>
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
