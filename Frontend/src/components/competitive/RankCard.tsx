import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { TopoBackdrop } from './TopoBackdrop'
import {
  CHAMPION,
  DIVISION_POINTS,
  PLACEMENT_DUELS,
  TIER_COLORS,
  TIER_TEXTURES,
  divisionNumber,
  tierOf,
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

/** Grain du fond : un bruit fractal en SVG, calculé par le navigateur. */
const NOISE = `url("data:image/svg+xml,${encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="180" height="180"><filter id="n"><feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="3" stitchTiles="stitch"/><feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.55 0"/></filter><rect width="100%" height="100%" filter="url(#n)"/></svg>',
)}")`

/** Le fond de la carte : les courbes de niveau d'un relief qui bouge, dans la
 *  couleur du rang, plus un grain léger. Les courbes s'estompent un peu au
 *  centre, derrière l'insigne et le nom, pour ne pas les brouiller. */
function RankBackdrop({ color }: { color: string }) {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0">
      <div
        className="absolute inset-0"
        style={{ maskImage: 'radial-gradient(ellipse 60% 45% at 50% 42%, rgb(0 0 0 / 0.45) 0%, black 100%)' }}
      >
        <TopoBackdrop color={color} />
      </div>
      <div className="absolute inset-0 opacity-[0.06] mix-blend-overlay" style={{ backgroundImage: NOISE }} />
    </div>
  )
}

/** La carte du rang dans le mode choisi, tout au centre : insigne, nom, duels,
 *  puis la barre de la division (ou les cases du placement). Elle prend
 *  toute la place que lui donne sa case. */
export function RankCard({ standing, onOpen }: { standing: Standing; onOpen?: () => void }) {
  const t = useT()
  const rankName = useRankName()
  const tier = tierOf(standing.division)
  const color = standing.placed ? TIER_COLORS[tier] : '160 150 190'
  const champion = standing.division >= CHAMPION
  const progress = champion ? 1 : Math.min(1, Math.max(0, standing.points / DIVISION_POINTS))

  return (
    <div
      // Cliquable quand on lui donne `onOpen` : la carte mène au hub.
      role={onOpen ? 'button' : undefined}
      tabIndex={onOpen ? 0 : undefined}
      onClick={onOpen}
      onKeyDown={onOpen ? (e) => (e.key === 'Enter' || e.key === ' ') && onOpen() : undefined}
      className={`group relative flex h-full min-h-[280px] flex-col items-center justify-evenly gap-6 overflow-hidden rounded-2xl border bg-surface-1 px-7 py-8 text-center transition-colors ${
        onOpen ? 'cursor-pointer border-line hover:border-white/20' : 'border-line'
      }`}
      style={{ backgroundImage: `radial-gradient(22rem 16rem at 50% 38%, rgb(${color} / 0.18), transparent 70%)` }}
    >
      <RankBackdrop color={color} />

      <motion.div
        className="relative"
        key={`${standing.mode}-${standing.division}-${standing.placed}`}
        initial={{ scale: 0.85, rotate: -6, opacity: 0 }}
        animate={{ scale: 1, rotate: 0, opacity: 1 }}
        transition={{ type: 'spring', stiffness: 260, damping: 18 }}
        style={{ filter: `drop-shadow(0 0 22px rgb(${color} / 0.5))` }}
      >
        <RankTile division={standing.division} placed={standing.placed} size={116} />
      </motion.div>

      <div className="relative">
        <p
          className="text-[34px] font-black leading-none tracking-[-0.02em]"
          style={{ color: `rgb(${color})`, textShadow: `0 0 24px rgb(${color} / 0.35)` }}
        >
          {rankName(standing.division, standing.placed)}
        </p>
        <p className="mt-3 text-[12px] text-txt-secondary">
          {standing.placed
            ? t('competitive.rank.duels', { count: standing.duels })
            : t('competitive.rank.placement', { done: standing.duels, total: PLACEMENT_DUELS })}
        </p>
      </div>

      {standing.placed ? (
        <div className="relative flex w-full max-w-[300px] flex-col gap-3">
          <div className="h-2.5 overflow-hidden rounded-full bg-white/[0.07]">
            <motion.div
              className="h-full rounded-full"
              style={{ background: `linear-gradient(90deg, rgb(${color} / 0.55), rgb(${color}))` }}
              initial={{ width: 0 }}
              animate={{ width: `${progress * 100}%` }}
              transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1] }}
            />
          </div>
          <span className="text-[11.5px] font-semibold tabular-nums text-txt-secondary">
            {champion
              ? t('competitive.rank.championPoints', { points: standing.points })
              : t('competitive.rank.points', { points: standing.points, max: DIVISION_POINTS })}
          </span>
        </div>
      ) : (
        <div className="relative flex w-full max-w-[300px] gap-1.5">
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
      )}

      {onOpen && (
        // Le geste n'est pas évident sur une carte : on le dit au survol.
        <span className="absolute bottom-3 left-0 right-0 flex items-center justify-center gap-1 text-[10.5px] font-semibold text-txt-muted opacity-0 transition-opacity group-hover:opacity-100">
          {t('competitive.tabs.openHub')}
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="currentColor"><path d="M10 6L8.6 7.4 13.2 12l-4.6 4.6L10 18l6-6z" /></svg>
        </span>
      )}
    </div>
  )
}
