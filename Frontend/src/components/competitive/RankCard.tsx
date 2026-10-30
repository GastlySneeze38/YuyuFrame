import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import {
  CHAMPION,
  DIVISION_POINTS,
  PLACEMENT_DUELS,
  TIER_COLORS,
  TIER_TEXTURES,
  divisionNumber,
  tierOf,
  winsToNext,
  type Standing,
} from '@/lib/competitive'

/**
 * L'insigne d'un rang : la texture du bloc de son matériau (celle que le jeu
 * pose sur ses tuiles 3D), en pixels nets, avec la division en bas à droite
 * comme le nombre d'une pile d'objets. Non classé : une tuile sombre et un « ? ».
 */
export function RankTile({ division, placed, size }: { division: number; placed: boolean; size: number }) {
  const tier = tierOf(division)
  const number = placed ? divisionNumber(division) : null
  return (
    <div
      className="relative shrink-0 overflow-hidden rounded-[22%]"
      style={{
        width: size,
        height: size,
        // Biseau : un liseré clair en haut à gauche, sombre en bas à droite.
        boxShadow: `inset ${size * 0.05}px ${size * 0.05}px 0 rgb(255 255 255 / 0.28), inset -${size * 0.06}px -${size * 0.06}px 0 rgb(0 0 0 / 0.38)`,
      }}
    >
      {placed ? (
        <img
          src={TIER_TEXTURES[tier]}
          alt=""
          draggable={false}
          className="absolute inset-0 h-full w-full [image-rendering:pixelated]"
        />
      ) : (
        <div className="absolute inset-0 flex items-center justify-center bg-[rgb(40_36_52)]">
          <span className="font-black text-white/50" style={{ fontSize: size * 0.48 }}>?</span>
        </div>
      )}
      {number !== null && (
        <span
          className="absolute bottom-[4%] right-[10%] font-black leading-none text-white"
          style={{ fontSize: size * 0.32, textShadow: `${size * 0.03}px ${size * 0.03}px 0 rgb(0 0 0 / 0.7)` }}
        >
          {number}
        </span>
      )}
    </div>
  )
}

/** « Or 2 », « Champion », « Non classé ». */
export function useRankName() {
  const t = useT()
  return (division: number, placed: boolean) => {
    if (!placed) return t('competitive.rank.unranked')
    const name = t(`competitive.tiers.${tierOf(division)}`)
    const number = divisionNumber(division)
    return number === null ? name : `${name} ${number}`
  }
}

/** La carte du rang dans le mode choisi : insigne, nom, barre de la division
 *  ou cases du placement, objectif suivant. */
export function RankCard({ standing }: { standing: Standing }) {
  const t = useT()
  const rankName = useRankName()
  const tier = tierOf(standing.division)
  const color = standing.placed ? TIER_COLORS[tier] : '160 150 190'
  const champion = standing.division >= CHAMPION
  const progress = champion ? 1 : Math.min(1, Math.max(0, standing.points / DIVISION_POINTS))
  const wins = winsToNext(standing)
  const next = Math.min(CHAMPION, standing.division + 1)

  return (
    <div
      className="relative flex flex-col gap-5 overflow-hidden rounded-2xl border border-line bg-surface-1 p-5"
      style={{ backgroundImage: `radial-gradient(28rem 16rem at 0% 0%, rgb(${color} / 0.16), transparent 70%)` }}
    >
      <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.rank.label')}</span>

      <div className="flex items-center gap-5">
        <motion.div
          key={`${standing.mode}-${standing.division}-${standing.placed}`}
          initial={{ scale: 0.85, rotate: -6, opacity: 0 }}
          animate={{ scale: 1, rotate: 0, opacity: 1 }}
          transition={{ type: 'spring', stiffness: 260, damping: 18 }}
          style={{ filter: `drop-shadow(0 0 18px rgb(${color} / 0.45))` }}
        >
          <RankTile division={standing.division} placed={standing.placed} size={88} />
        </motion.div>
        <div className="min-w-0">
          <p className="text-[28px] font-black leading-none tracking-[-0.02em]" style={{ color: `rgb(${color})` }}>
            {rankName(standing.division, standing.placed)}
          </p>
          <p className="mt-2 text-[12px] text-txt-secondary">
            {standing.placed
              ? t('competitive.rank.duels', { count: standing.duels })
              : t('competitive.rank.placement', { done: standing.duels, total: PLACEMENT_DUELS })}
          </p>
        </div>
      </div>

      {standing.placed ? (
        <div className="flex flex-col gap-2">
          <div className="flex items-baseline justify-between text-[11.5px]">
            <span className="font-semibold text-txt-primary">
              {champion
                ? t('competitive.rank.championPoints', { points: standing.points })
                : t('competitive.rank.points', { points: standing.points, max: DIVISION_POINTS })}
            </span>
            <span className="text-txt-muted">
              {champion
                ? t('competitive.rank.top')
                : t('competitive.rank.nextWins', { count: wins ?? 0, rank: rankName(next, true) })}
            </span>
          </div>
          <div className="h-2.5 overflow-hidden rounded-full bg-white/[0.06]">
            <motion.div
              className="h-full rounded-full"
              style={{ background: `linear-gradient(90deg, rgb(${color} / 0.55), rgb(${color}))` }}
              initial={{ width: 0 }}
              animate={{ width: `${progress * 100}%` }}
              transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1] }}
            />
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          <div className="flex gap-1.5">
            {Array.from({ length: PLACEMENT_DUELS }, (_, i) => (
              <motion.div
                key={i}
                initial={{ opacity: 0, y: 4 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: i * 0.03 }}
                className={`h-2.5 flex-1 rounded-full ${i < standing.duels ? 'bg-accent' : 'bg-white/[0.06]'}`}
              />
            ))}
          </div>
          <p className="text-[11.5px] text-txt-muted">
            {t('competitive.rank.placementHint', { left: PLACEMENT_DUELS - standing.duels })}
          </p>
        </div>
      )}
    </div>
  )
}
