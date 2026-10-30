import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { SkinFace } from '@/components/ui/SkinFace'
import { TIER_COLORS, tierOf, type LeaderboardRow } from '@/lib/competitive'
import { RankTile, useRankName } from './RankCard'

/** Couleur des trois premières places. */
const PODIUM = ['255 206 72', '210 214 222', '214 150 96']

/**
 * Le classement entre joueurs d'un mode. Le joueur est mis en avant ; s'il
 * n'est pas dans le haut du tableau, sa ligne est épinglée en bas, après
 * une séparation.
 *
 * `rows === null` : le serveur n'a pas encore de route pour ça.
 */
export function Leaderboard({ rows, placed }: { rows: LeaderboardRow[] | null; placed: boolean }) {
  const t = useT()
  const rankName = useRankName()
  const top = rows?.filter((r) => r.position <= 20) ?? []
  const me = rows?.find((r) => r.me)
  const pinned = me && me.position > 20 ? me : null

  const line = (r: LeaderboardRow, i: number) => {
    const color = TIER_COLORS[tierOf(r.division)]
    const podium = r.position <= 3 ? PODIUM[r.position - 1] : null
    return (
      <motion.div
        key={`${r.position}-${r.name}`}
        initial={{ opacity: 0, x: -6 }}
        animate={{ opacity: 1, x: 0 }}
        transition={{ delay: Math.min(i, 12) * 0.025 }}
        className={`flex items-center gap-3 rounded-xl px-2.5 py-1.5 ${
          r.me ? 'bg-accent/[0.14] ring-1 ring-accent/40' : 'hover:bg-white/[0.03]'
        }`}
      >
        <span
          className="w-9 text-right text-[12px] font-black tabular-nums"
          style={{ color: podium ? `rgb(${podium})` : 'rgb(255 255 255 / 0.35)' }}
        >
          {r.position}
        </span>
        <SkinFace dataUri={`https://mc-heads.net/skin/${encodeURIComponent(r.name)}`} size={22} className="rounded-[4px]" />
        <span className="min-w-0 flex-1 truncate text-[12.5px] font-semibold">
          {r.name}
          {r.me && <span className="ml-2 text-[10px] font-bold uppercase tracking-[0.06em] text-accent-hover">{t('competitive.leaderboard.you')}</span>}
        </span>
        <span className="hidden w-14 text-right text-[11px] tabular-nums text-txt-muted sm:block">{r.winRate} %</span>
        <div className="flex w-[124px] items-center justify-end gap-2">
          <span className="truncate text-[11.5px] font-bold" style={{ color: `rgb(${color})` }}>
            {rankName(r.division, true)}
          </span>
          <RankTile division={r.division} placed size={20} />
        </div>
      </motion.div>
    )
  }

  return (
    <div className="flex min-w-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
      <div className="flex items-baseline justify-between">
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.leaderboard.title')}</span>
        {rows && (
          <span className="text-[10.5px] text-txt-muted">{t('competitive.leaderboard.winRate')}</span>
        )}
      </div>

      {rows === null ? (
        <p className="py-10 text-center text-[12px] leading-relaxed text-txt-muted">{t('competitive.leaderboard.soon')}</p>
      ) : (
        <div className="flex flex-col gap-0.5">
          {top.map(line)}
          {pinned && (
            <>
              <div className="my-1 flex items-center gap-2 px-2.5 text-txt-muted">
                <span className="h-px flex-1 bg-white/[0.06]" />
                <span className="text-[10px]">•••</span>
                <span className="h-px flex-1 bg-white/[0.06]" />
              </div>
              {line(pinned, top.length)}
            </>
          )}
          {!placed && (
            <p className="mt-2 px-2.5 text-[11px] text-txt-muted">{t('competitive.leaderboard.notPlaced')}</p>
          )}
        </div>
      )}
    </div>
  )
}
