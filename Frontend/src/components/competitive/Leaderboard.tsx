import { useState } from 'react'
import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { SkinFace } from '@/components/ui/SkinFace'
import { TIER_COLORS, tierOf, type LeaderboardRow } from '@/lib/competitive'
import { RankTile, useRankName } from './RankCard'

/** Couleur des trois premières places. */
const PODIUM = ['255 206 72', '210 214 222', '214 150 96']
/** Les 15 premiers seulement : la liste tient sans défilement (entre amis, la
 *  ligne du joueur est épinglée en bas s'il est plus loin). */
const TOP = 15

const skinOf = (name: string) => `https://mc-heads.net/skin/${encodeURIComponent(name)}`

/**
 * Écran Classement, en deux portées :
 * - **Global** : les 15 meilleurs joueurs YuyuFrame, rien d'autre — on n'y
 *   figure qu'en en faisant partie ;
 * - **Amis** : moi et mes amis qui montrent leurs pages compétitives, avec ma
 *   ligne épinglée sous la liste si je suis plus bas.
 * Le podium montre les trois premiers de la portée choisie.
 *
 * `rows === null` : le serveur n'a pas encore de route pour ça.
 * `subject` : le pseudo de l'ami dont on regarde l'onglet ; sa ligne (`me`)
 * est marquée « Ami » au lieu de « Toi », et la portée Amis (les miens) n'a
 * pas de sens chez lui.
 */
export function Leaderboard({
  rows: globalRows,
  friendRows = null,
  placed,
  subject,
}: {
  rows: LeaderboardRow[] | null
  friendRows?: LeaderboardRow[] | null
  placed: boolean
  subject?: string
}) {
  const t = useT()
  const [scope, setScope] = useState<'global' | 'friends'>('global')
  const friends = scope === 'friends' && !subject
  const rows = friends ? friendRows : globalRows

  if (rows === null) {
    return (
      <div className="flex h-full items-center justify-center rounded-2xl border border-line bg-surface-1">
        <p className="text-[12.5px] text-txt-muted">{t('competitive.leaderboard.soon')}</p>
      </div>
    )
  }

  const podium = rows.filter((r) => r.position <= 3).sort((a, b) => a.position - b.position)
  // La liste reprend aussi le podium : elle se lit d'un bout à l'autre.
  const rest = rows.filter((r) => r.position <= TOP)
  const me = rows.find((r) => r.me)
  // Ma ligne épinglée : entre amis seulement — le global ne montre que le haut.
  const pinned = friends && me && me.position > TOP ? me : null
  const mark = subject ? t('competitive.leaderboard.friend') : t('competitive.leaderboard.you')

  return (
    <div className="grid h-full grid-cols-[minmax(0,1fr)_minmax(0,1.25fr)] gap-4">
      <Podium rows={podium} />

      <div className="flex min-h-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
        <div className="flex items-center justify-between gap-3 px-2.5">
          {subject ? (
            <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.leaderboard.title')}</span>
          ) : (
            <div className="flex rounded-lg bg-white/[0.04] p-0.5">
              {(['global', 'friends'] as const).map((s) => (
                <button
                  key={s}
                  onClick={() => setScope(s)}
                  className={`rounded-md px-2.5 py-1 text-[11px] font-bold transition-colors ${
                    scope === s ? 'bg-accent/80 text-white' : 'text-txt-muted hover:text-txt-secondary'
                  }`}
                >
                  {t(`competitive.leaderboard.scope.${s}`)}
                </button>
              ))}
            </div>
          )}
          <span className="text-[10.5px] text-txt-muted">{t('competitive.leaderboard.winRate')}</span>
        </div>
        {friends && rows.length === 0 && <p className="px-2.5 text-[12px] text-txt-muted">{t('competitive.leaderboard.noFriends')}</p>}
        <div className="-mx-1 flex min-h-0 flex-1 flex-col gap-0.5 overflow-hidden px-1">
          {/* Les lignes se partagent toute la hauteur de la carte. */}
          {rest.map((r, i) => (
            <Line key={`${r.position}-${r.name}`} row={r} index={i} fill mark={mark} />
          ))}
        </div>
        {pinned && (
          <div className="flex flex-col gap-1 border-t border-white/[0.06] pt-2">
            <Line row={pinned} index={0} mark={mark} />
          </div>
        )}
        {!placed && (
          <p className="px-2.5 text-[11px] text-txt-muted">
            {subject ? t('competitive.leaderboard.notPlacedFriend', { name: subject }) : t('competitive.leaderboard.notPlaced')}
          </p>
        )}
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

function Line({ row: r, index, fill = false, mark }: { row: LeaderboardRow; index: number; fill?: boolean; mark: string }) {
  const t = useT()
  const rankName = useRankName()
  const color = TIER_COLORS[tierOf(r.division)]
  return (
    <motion.div
      initial={{ opacity: 0, x: -6 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ delay: Math.min(index, 12) * 0.025 }}
      className={`flex items-center gap-3 rounded-xl px-2.5 py-1 ${fill ? 'min-h-0 flex-1' : 'shrink-0'} ${r.me ? 'bg-accent/[0.14] ring-1 ring-accent/40' : 'hover:bg-white/[0.03]'}`}
    >
      <span
        className="w-12 text-right text-[12.5px] font-black tabular-nums"
        style={{ color: r.position <= 3 ? `rgb(${PODIUM[r.position - 1]})` : 'rgb(255 255 255 / 0.35)' }}
      >
        {r.position}
      </span>
      <SkinFace dataUri={skinOf(r.name)} size={24} className="rounded-[4px]" />
      <span className="min-w-0 flex-1 truncate text-[13px] font-semibold">
        {r.name}
        {r.me && <span className="ml-2 text-[10px] font-bold uppercase tracking-[0.06em] text-accent-hover">{mark}</span>}
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
