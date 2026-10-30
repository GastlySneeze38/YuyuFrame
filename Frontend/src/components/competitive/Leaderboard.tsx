import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { SkinFace } from '@/components/ui/SkinFace'
import { TIER_COLORS, tierOf, type LeaderboardRow } from '@/lib/competitive'
import { RankTile, useRankName } from './RankCard'

/** Couleur des trois premières places. */
const PODIUM = ['255 206 72', '210 214 222', '214 150 96']
/** Lignes montrées au plus dans la liste (au-delà, seule la nôtre est épinglée). */
const TOP = 20

const skinOf = (name: string) => `https://mc-heads.net/skin/${encodeURIComponent(name)}`

/**
 * Écran Classement : le podium des trois premiers, puis la suite en liste. Le
 * joueur est mis en avant ; hors du haut du tableau, sa ligne est épinglée
 * sous la liste.
 *
 * `rows === null` : le serveur n'a pas encore de route pour ça.
 */
export function Leaderboard({ rows, placed }: { rows: LeaderboardRow[] | null; placed: boolean }) {
  const t = useT()

  if (rows === null) {
    return (
      <div className="flex h-full items-center justify-center rounded-2xl border border-line bg-surface-1">
        <p className="text-[12.5px] text-txt-muted">{t('competitive.leaderboard.soon')}</p>
      </div>
    )
  }

  const podium = rows.filter((r) => r.position <= 3).sort((a, b) => a.position - b.position)
  const rest = rows.filter((r) => r.position > 3 && r.position <= TOP)
  const me = rows.find((r) => r.me)
  const pinned = me && me.position > TOP ? me : null

  return (
    <div className="grid h-full grid-cols-[minmax(0,1fr)_minmax(0,1.25fr)] gap-4">
      <Podium rows={podium} />

      <div className="flex min-h-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
        <div className="flex items-baseline justify-between px-2.5">
          <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.leaderboard.title')}</span>
          <span className="text-[10.5px] text-txt-muted">{t('competitive.leaderboard.winRate')}</span>
        </div>
        <div className="-mx-1 flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto px-1">
          {rest.map((r, i) => (
            <Line key={`${r.position}-${r.name}`} row={r} index={i} />
          ))}
        </div>
        {pinned && (
          <div className="flex flex-col gap-1 border-t border-white/[0.06] pt-2">
            <Line row={pinned} index={0} />
          </div>
        )}
        {!placed && <p className="px-2.5 text-[11px] text-txt-muted">{t('competitive.leaderboard.notPlaced')}</p>}
      </div>
    </div>
  )
}

/** Les trois premiers : la 1re place au centre et plus haute. */
function Podium({ rows }: { rows: LeaderboardRow[] }) {
  const rankName = useRankName()
  // Ordre d'affichage : 2e, 1re, 3e.
  const order = [rows[1], rows[0], rows[2]].filter(Boolean)
  const height = (position: number) => (position === 1 ? '62%' : position === 2 ? '46%' : '36%')

  return (
    <div className="relative flex min-h-0 items-end justify-center gap-3 overflow-hidden rounded-2xl border border-line bg-surface-1 px-5 pb-0 pt-6">
      {order.map((r, i) => {
        const medal = PODIUM[r.position - 1]
        const color = TIER_COLORS[tierOf(r.division)]
        return (
          <motion.div
            key={r.name}
            initial={{ opacity: 0, y: 24 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.1 + i * 0.08, type: 'spring', stiffness: 220, damping: 22 }}
            className="flex h-full min-w-0 flex-1 flex-col items-center justify-end"
          >
            <SkinFace dataUri={skinOf(r.name)} size={r.position === 1 ? 64 : 48} className="rounded-lg" />
            <p className={`mt-2.5 max-w-full truncate font-black ${r.position === 1 ? 'text-[15px]' : 'text-[13px]'} ${r.me ? 'text-accent-hover' : ''}`}>
              {r.name}
            </p>
            <div className="mt-1 flex items-center gap-1.5">
              <RankTile division={r.division} placed size={16} />
              <span className="text-[11px] font-bold" style={{ color: `rgb(${color})` }}>
                {rankName(r.division, true)}
              </span>
            </div>
            <div
              className="mt-3 flex w-full flex-col items-center rounded-t-xl pt-3"
              style={{
                height: height(r.position),
                background: `linear-gradient(180deg, rgb(${medal} / 0.28), rgb(${medal} / 0.04))`,
                borderTop: `2px solid rgb(${medal} / 0.7)`,
              }}
            >
              <span className="text-[30px] font-black leading-none" style={{ color: `rgb(${medal})`, textShadow: `0 0 18px rgb(${medal} / 0.5)` }}>
                {r.position}
              </span>
              <span className="mt-1.5 text-[11px] tabular-nums text-txt-secondary">{r.winRate} %</span>
            </div>
          </motion.div>
        )
      })}
    </div>
  )
}

function Line({ row: r, index }: { row: LeaderboardRow; index: number }) {
  const t = useT()
  const rankName = useRankName()
  const color = TIER_COLORS[tierOf(r.division)]
  return (
    <motion.div
      initial={{ opacity: 0, x: -6 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ delay: Math.min(index, 12) * 0.025 }}
      className={`flex shrink-0 items-center gap-3 rounded-xl px-2.5 py-2 ${r.me ? 'bg-accent/[0.14] ring-1 ring-accent/40' : 'hover:bg-white/[0.03]'}`}
    >
      <span className="w-12 text-right text-[12.5px] font-black tabular-nums text-white/35">{r.position}</span>
      <SkinFace dataUri={skinOf(r.name)} size={24} className="rounded-[4px]" />
      <span className="min-w-0 flex-1 truncate text-[13px] font-semibold">
        {r.name}
        {r.me && <span className="ml-2 text-[10px] font-bold uppercase tracking-[0.06em] text-accent-hover">{t('competitive.leaderboard.you')}</span>}
      </span>
      <span className="w-14 text-right text-[11.5px] tabular-nums text-txt-muted">{r.winRate} %</span>
      <div className="flex w-[130px] items-center justify-end gap-2">
        <span className="truncate text-[12px] font-bold" style={{ color: `rgb(${color})` }}>
          {rankName(r.division, true)}
        </span>
        <RankTile division={r.division} placed size={22} />
      </div>
    </motion.div>
  )
}
