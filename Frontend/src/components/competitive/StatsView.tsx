import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { SNAP } from '@/lib/motion'
import { TIER_COLORS, tierOf, type DuelRow, type ModeStats, type Standing } from '@/lib/competitive'
import { RankCard } from './RankCard'

/** Duels montrés par la courbe des RR. */
const CHART_DUELS = 30

/**
 * Écran Statistiques : le rang à gauche ; à droite les chiffres du mode, puis
 * la courbe des RR gagnés et perdus sur les derniers duels.
 *
 * `stats` / `duels` à `null` : pas encore de route pour ça.
 */
export function StatsView({ standing, stats, duels }: { standing: Standing; stats: ModeStats | null; duels: DuelRow[] | null }) {
  const t = useT()
  const color = standing.placed ? TIER_COLORS[tierOf(standing.division)] : '160 150 190'

  return (
    <div className="grid h-full grid-cols-[300px_minmax(0,1fr)] gap-4">
      <RankCard standing={standing} />
      {!stats ? (
        <div className="flex items-center justify-center rounded-2xl border border-line bg-surface-1 p-5">
          <p className="max-w-xs text-center text-[12px] leading-relaxed text-txt-muted">{t('competitive.stats.soon')}</p>
        </div>
      ) : (
        <div className="grid min-h-0 grid-rows-[auto_minmax(0,1fr)] gap-4">
          <Tiles stats={stats} />
          <RrChart duels={duels} color={color} />
        </div>
      )}
    </div>
  )
}

function Tiles({ stats }: { stats: ModeStats }) {
  const t = useT()
  const played = stats.wins + stats.losses + stats.draws
  const winRate = played ? Math.round((stats.wins / played) * 100) : 0
  const tiles: { label: string; value: string; sub?: string; tone?: string; bar?: number }[] = [
    { label: t('competitive.stats.winRate'), value: `${winRate} %`, sub: t('competitive.stats.record', { wins: stats.wins, losses: stats.losses }), bar: winRate / 100 },
    { label: t('competitive.stats.avgScore'), value: String(stats.avgScore), sub: '/ 500', bar: stats.avgScore / 500 },
    { label: t('competitive.stats.bestScore'), value: String(stats.bestScore), sub: '/ 500', bar: stats.bestScore / 500 },
    { label: t('competitive.stats.bestStreak'), value: String(stats.bestStreak), sub: t('competitive.stats.wins') },
    { label: t('competitive.stats.currentStreak'), value: String(stats.currentStreak), sub: t('competitive.stats.wins') },
    {
      label: t('competitive.stats.rrWeek'),
      value: `${stats.rrWeek > 0 ? '+' : ''}${stats.rrWeek}`,
      sub: 'RR',
      tone: stats.rrWeek > 0 ? 'text-success' : stats.rrWeek < 0 ? 'text-danger' : undefined,
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
          className="flex flex-col gap-3 rounded-2xl border border-line bg-surface-1 px-5 py-4"
        >
          <span className="text-[10.5px] font-bold uppercase tracking-[0.07em] text-txt-muted">{tile.label}</span>
          <div className="flex items-baseline gap-2">
            <span className={`text-[32px] font-black leading-none tabular-nums ${tile.tone ?? 'text-txt-primary'}`}>{tile.value}</span>
            {tile.sub && <span className="truncate text-[11px] text-txt-muted">{tile.sub}</span>}
          </div>
          {tile.bar !== undefined && (
            <div className="h-1.5 overflow-hidden rounded-full bg-white/[0.06]">
              <motion.div
                className="h-full rounded-full bg-accent"
                initial={{ width: 0 }}
                animate={{ width: `${Math.min(1, tile.bar) * 100}%` }}
                transition={{ duration: 0.8, ease: [0.16, 1, 0.3, 1], delay: 0.1 + i * 0.03 }}
              />
            </div>
          )}
        </motion.div>
      ))}
    </div>
  )
}

/** La somme des RR duel après duel, sur les derniers duels classés. */
function RrChart({ duels, color }: { duels: DuelRow[] | null; color: string }) {
  const t = useT()
  // L'historique va du plus récent au plus ancien ; la courbe, dans l'autre sens.
  const recent = (duels ?? []).filter((d) => d.rr !== null).slice(0, CHART_DUELS).reverse()
  const points: number[] = [0]
  for (const d of recent) points.push(points[points.length - 1] + (d.rr ?? 0))
  const min = Math.min(...points)
  const max = Math.max(...points)
  const span = Math.max(1, max - min)
  const W = 600
  const H = 200
  const pad = 12
  const x = (i: number) => (i / Math.max(1, points.length - 1)) * W
  const y = (v: number) => pad + (1 - (v - min) / span) * (H - pad * 2)
  const line = points.map((v, i) => `${i === 0 ? 'M' : 'L'} ${x(i)} ${y(v)}`).join(' ')
  const area = `${line} L ${W} ${H} L 0 ${H} Z`
  const total = points[points.length - 1]

  return (
    <div className="flex min-h-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.stats.chartTitle')}</span>
        {recent.length > 0 && (
          <span className="text-[11px] text-txt-muted">
            {t('competitive.stats.chartHint', { count: recent.length })} ·{' '}
            <span className={`font-bold ${total > 0 ? 'text-success' : total < 0 ? 'text-danger' : ''}`}>
              {total > 0 ? '+' : ''}
              {total} RR
            </span>
          </span>
        )}
      </div>
      {recent.length < 2 ? (
        <p className="flex flex-1 items-center justify-center text-[12px] text-txt-muted">{t('competitive.history.empty')}</p>
      ) : (
        <div className="relative min-h-0 flex-1">
          <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="absolute inset-0 h-full w-full overflow-visible">
            <defs>
              <linearGradient id="rr-area" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={`rgb(${color})`} stopOpacity="0.32" />
                <stop offset="100%" stopColor={`rgb(${color})`} stopOpacity="0" />
              </linearGradient>
            </defs>
            {/* La ligne du zéro : au-dessus on a gagné des RR, en dessous perdu. */}
            {min < 0 && max > 0 && (
              <line x1="0" x2={W} y1={y(0)} y2={y(0)} stroke="rgb(255 255 255 / 0.12)" strokeDasharray="4 4" vectorEffect="non-scaling-stroke" />
            )}
            <motion.path d={area} fill="url(#rr-area)" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.6, delay: 0.3 }} />
            <motion.path
              d={line}
              fill="none"
              stroke={`rgb(${color})`}
              strokeWidth={2.5}
              strokeLinejoin="round"
              vectorEffect="non-scaling-stroke"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.5 }}
            />
          </svg>
        </div>
      )}
    </div>
  )
}
