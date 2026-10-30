import { useId, useState, type ReactNode } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useT } from '@/i18n'
import { Toggle } from '@/components/ui/Toggle'
import { ModalShell } from '@/components/ui/ModalShell'
import {
  BORDER_LEVELS,
  CHAMPION,
  SEASON_GRID,
  TIER_COLORS,
  TIER_TEXTURES,
  WINS_PER_BORDER,
  borderLevel,
  tierOf,
  type Season,
} from '@/lib/competitive'
import { useRankName } from './RankCard'
import { TopoBackdrop } from './TopoBackdrop'

const CELLS = SEASON_GRID * SEASON_GRID

/**
 * Écran Saison : chaque victoire classée de la saison remplit une case de la
 * grille, avec la texture du rang qu'on avait en la gagnant. Les meilleures
 * victoires montent en haut ; la grille garde les 64 meilleures. Toutes les
 * 25 victoires, la bordure gagne des formes autour de la grille.
 *
 * Lecture, de gauche à droite : la grille ; puis le rang de la saison (ce
 * qu'on regarde d'abord) ; puis un seul bloc pour la bordure ; puis
 * l'interrupteur, en dernier.
 *
 * Le « i » ouvre ce qu'on peut obtenir au mieux. L'affichage de la grille à
 * côté du profil n'est pas fait : l'interrupteur n'est que l'interface.
 *
 * `wins === null` : pas encore de route pour ça.
 */
export function SeasonRankView({ wins, season }: { wins: number[] | null; season: Season | null }) {
  const t = useT()
  const rankName = useRankName()
  const [showOnProfile, setShowOnProfile] = useState(true)
  const [showMax, setShowMax] = useState(false)

  if (wins === null) {
    return (
      <div className="flex h-full items-center justify-center rounded-2xl border border-line bg-surface-1">
        <p className="text-[12.5px] text-txt-muted">{t('competitive.season.soon')}</p>
      </div>
    )
  }

  const best = [...wins].sort((a, b) => b - a).slice(0, CELLS)
  const top = best[0] ?? null
  const color = top !== null ? TIER_COLORS[tierOf(top)] : '160 150 190'
  const level = borderLevel(wins.length)
  const maxed = level >= BORDER_LEVELS
  const nextAt = level * WINS_PER_BORDER
  const levelProgress = maxed ? 1 : (wins.length - (level - 1) * WINS_PER_BORDER) / WINS_PER_BORDER
  const daysLeft = season?.end ? Math.max(0, Math.ceil((season.end - Date.now()) / 86_400_000)) : null

  return (
    <div className="relative grid h-full grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)] overflow-hidden rounded-2xl border border-line bg-surface-1" style={{ isolation: 'isolate' }}>
      <div aria-hidden="true" className="absolute inset-0 -z-10 opacity-60">
        <TopoBackdrop color={color} />
      </div>

      {/* La grille, dans sa bordure. */}
      <div className="flex items-center justify-center p-6">
        <SeasonFrame level={level} accent={color} size="min(64vh, 480px)">
          <SeasonGrid wins={best} />
        </SeasonFrame>
      </div>

      <div className="flex min-h-0 flex-col justify-center gap-8 py-8 pl-2 pr-10">
        {/* 1. Le rang de la saison : ce qu'on lit en premier. */}
        <div>
          {season && (
            <p className="text-[11px] font-bold uppercase tracking-[0.16em] text-txt-muted">
              {t('competitive.pass.season', { number: season.number, name: season.name })}
              {daysLeft !== null && <span className="text-txt-secondary"> · {t('competitive.season.daysLeft', { count: daysLeft })}</span>}
            </p>
          )}
          <div className="mt-3 flex items-center gap-3">
            <p className="text-[44px] font-black uppercase leading-none tracking-[-0.01em]" style={{ color: `rgb(${color})`, textShadow: `0 0 26px rgb(${color} / 0.35)` }}>
              {top !== null ? rankName(top, true) : t('competitive.rank.unranked')}
            </p>
            <button
              onClick={() => setShowMax(true)}
              title={t('competitive.season.maxTitle')}
              className="flex h-6 w-6 items-center justify-center rounded-full border border-white/30 text-[12px] font-bold text-white/70 transition-colors hover:border-white/60 hover:bg-white/10 hover:text-white"
            >
              i
            </button>
          </div>
          <p className="mt-2 text-[12px] text-txt-muted">{t('competitive.season.rankLabel')}</p>
        </div>

        {/* 2. La bordure : tout ce qui la concerne dans un seul bloc. */}
        <div className="flex flex-col gap-4 rounded-2xl border border-white/[0.07] bg-black/30 p-5 backdrop-blur-sm">
          <div className="flex items-baseline justify-between gap-3">
            <p className="text-[13px] font-black uppercase tracking-[0.05em]">{t('competitive.season.borderTitle')}</p>
            <p className="text-[12px] font-bold tabular-nums text-txt-secondary">
              {t('competitive.season.levelOf', { level, max: BORDER_LEVELS })}
            </p>
          </div>

          <div className="flex flex-col gap-1.5">
            <div className="h-1.5 overflow-hidden rounded-full bg-white/[0.08]">
              <motion.div
                className="h-full rounded-full bg-white/85"
                initial={{ width: 0 }}
                animate={{ width: `${levelProgress * 100}%` }}
                transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1], delay: 0.3 }}
              />
            </div>
            <p className="text-[11px] tabular-nums text-txt-muted">
              {maxed ? t('competitive.season.maxed') : t('competitive.season.progress', { wins: wins.length, next: nextAt })}
            </p>
          </div>

          {/* Les cinq bordures : la sienne en clair, les suivantes en retrait. */}
          <div className="flex items-end justify-between">
            {Array.from({ length: BORDER_LEVELS }, (_, i) => {
              const l = i + 1
              return (
                <div key={l} className={`flex flex-col items-center gap-1 ${l <= level ? '' : 'opacity-30'}`}>
                  <SeasonFrame level={l} accent={color} size="58px" still>
                    <div className="h-full w-full rounded-[2px] bg-[rgb(10_10_16)]" />
                  </SeasonFrame>
                  <span className={`text-[9.5px] font-bold tabular-nums ${l === level ? 'text-white' : 'text-txt-muted'}`}>
                    {t('competitive.season.levelAt', { count: i * WINS_PER_BORDER })}
                  </span>
                </div>
              )
            })}
          </div>
        </div>

        {/* 3. L'affichage au profil, en dernier. */}
        <label className="flex cursor-pointer items-center justify-between gap-3 px-1">
          <span className="text-[12px] text-txt-secondary">{t('competitive.season.showOnProfile')}</span>
          <Toggle checked={showOnProfile} onChange={() => setShowOnProfile((v) => !v)} size="sm" />
        </label>
      </div>

      <AnimatePresence>{showMax && <MaxModal onClose={() => setShowMax(false)} />}</AnimatePresence>
    </div>
  )
}

// ── La grille ────────────────────────────────────────────────────────────

/** Les cases : les victoires (déjà triées) d'abord, puis le vide. */
function SeasonGrid({ wins, instant = false }: { wins: number[]; instant?: boolean }) {
  const rankName = useRankName()
  return (
    <div className="grid h-full w-full gap-[3px] bg-[rgb(10_10_16)] p-[4px]" style={{ gridTemplateColumns: `repeat(${SEASON_GRID}, 1fr)` }}>
      {Array.from({ length: CELLS }, (_, i) => {
        const win = wins[i]
        if (win === undefined) return <div key={i} className="rounded-[2px] border border-white/[0.05] bg-white/[0.02]" />
        return (
          <motion.div
            key={i}
            title={rankName(win, true)}
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={{ delay: instant ? i * 0.006 : 0.15 + i * 0.012, type: 'spring', stiffness: 400, damping: 22 }}
            className="relative overflow-hidden rounded-[2px]"
            style={{ boxShadow: 'inset 1px 1px 0 rgb(255 255 255 / 0.3), inset -1px -1px 0 rgb(0 0 0 / 0.4)' }}
          >
            <img src={TIER_TEXTURES[tierOf(win)]} alt="" draggable={false} className="absolute inset-0 h-full w-full [image-rendering:pixelated]" />
          </motion.div>
        )
      })}
    </div>
  )
}

// ── La bordure ───────────────────────────────────────────────────────────

/** La grille occupe le centre de la bordure, de 18 % à 82 % du côté. */
const INNER = 18

/**
 * La bordure de la grille : un cadre d'acier, et à chaque niveau des formes
 * qui s'ajoutent autour, sans changer de couleur (seul le joyau du dernier
 * niveau prend celle du rang) :
 * 1. le cadre ;
 * 2. + des équerres aux quatre coins ;
 * 3. + des ailes sur les côtés, accrochées au cadre par des crans ;
 * 4. + un fronton au-dessus et un socle en dessous ;
 * 5. + une enceinte extérieure à pans coupés, avec ses circuits, et un
 *    joyau au sommet.
 *
 * Tout est dessiné dans un carré de 100 × 100 : la même bordure sert en
 * grand et dans l'échelle des niveaux.
 */
export function SeasonFrame({
  level,
  accent,
  size,
  children,
  still = false,
}: {
  level: number
  /** Couleur du rang : seulement pour le joyau du dernier niveau. */
  accent: string
  size: string
  children: ReactNode
  /** Sans animation d'apparition (l'échelle des niveaux). */
  still?: boolean
}) {
  const id = useId().replace(/:/g, '')
  const steel = `steel-${id}`
  const line = 'rgb(226 230 238 / 0.85)'
  const dim = 'rgb(226 230 238 / 0.35)'
  // Chaque forme apparaît l'une après l'autre, à l'ouverture.
  const appear = (step: number) =>
    still ? {} : { initial: { opacity: 0, scale: 0.96 }, animate: { opacity: 1, scale: 1 }, transition: { delay: 0.2 + step * 0.12, duration: 0.4 } }

  return (
    <div className="relative aspect-square shrink-0" style={{ height: size }}>
      <div className="absolute" style={{ left: `${INNER}%`, top: `${INNER}%`, right: `${INNER}%`, bottom: `${INNER}%` }}>
        {children}
      </div>

      <svg viewBox="0 0 100 100" className="pointer-events-none absolute inset-0 h-full w-full overflow-visible">
        <defs>
          <linearGradient id={steel} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="rgb(240 242 246)" />
            <stop offset="55%" stopColor="rgb(150 156 168)" />
            <stop offset="100%" stopColor="rgb(96 102 114)" />
          </linearGradient>
        </defs>

        {/* 1. Le cadre : un anneau d'acier biseauté. */}
        <motion.g {...appear(0)}>
          <path
            fillRule="evenodd"
            fill={`url(#${steel})`}
            d={`M${INNER - 3.5} ${INNER - 3.5} H${103.5 - INNER} V${103.5 - INNER} H${INNER - 3.5} Z M${INNER} ${INNER} V${100 - INNER} H${100 - INNER} V${INNER} Z`}
          />
          <rect x={INNER - 0.4} y={INNER - 0.4} width={100 - 2 * INNER + 0.8} height={100 - 2 * INNER + 0.8} fill="none" stroke="rgb(0 0 0 / 0.55)" strokeWidth="0.8" />
        </motion.g>

        {/* 2. Les équerres des coins. */}
        {level >= 2 && (
          <motion.g {...appear(1)} fill="none" stroke={line} strokeWidth="1.4" strokeLinecap="square">
            <path d="M10 22 V10 H22" />
            <path d="M78 10 H90 V22" />
            <path d="M90 78 V90 H78" />
            <path d="M22 90 H10 V78" />
          </motion.g>
        )}

        {/* 3. Les ailes des côtés, et leurs crans vers le cadre. */}
        {level >= 3 && (
          <motion.g {...appear(2)}>
            <g fill={`url(#${steel})`}>
              <path d="M4 30 L7 27 V73 L4 70 Z" />
              <path d="M96 30 L93 27 V73 L96 70 Z" />
            </g>
            <g fill="none" stroke={line} strokeWidth="1" strokeLinecap="round">
              <path d="M8.5 44 L11.5 47 V53 L8.5 56" />
              <path d="M91.5 44 L88.5 47 V53 L91.5 56" />
              <path d="M8.5 36 H12" stroke={dim} />
              <path d="M8.5 64 H12" stroke={dim} />
              <path d="M91.5 36 H88" stroke={dim} />
              <path d="M91.5 64 H88" stroke={dim} />
            </g>
          </motion.g>
        )}

        {/* 4. Le fronton et le socle. */}
        {level >= 4 && (
          <motion.g {...appear(3)}>
            <g fill="none" stroke={line} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
              <path d="M38 11 L50 5 L62 11" />
              <path d="M44 11.5 L50 8.5 L56 11.5" stroke={dim} />
              <path d="M46 88.5 L50 86 L54 88.5" />
            </g>
            <g fill={`url(#${steel})`}>
              <rect x="30" y="89.5" width="40" height="2.2" rx="1" />
              <rect x="38" y="93.2" width="24" height="1.6" rx="0.8" />
            </g>
          </motion.g>
        )}

        {/* 5. L'enceinte extérieure, ses circuits, et le joyau. */}
        {level >= 5 && (
          <motion.g {...appear(4)}>
            <path d="M16 1 H84 L99 16 V84 L84 99 H16 L1 84 V16 Z" fill="none" stroke={dim} strokeWidth="0.8" />
            <g fill="none" stroke={dim} strokeWidth="0.6" strokeLinecap="round">
              <path d="M1 30 H3 M1 70 H3 M99 30 H97 M99 70 H97" />
              <path d="M16 1 L20 5 H30 M84 1 L80 5 H70 M16 99 L20 95 H28 M84 99 L80 95 H72" />
              <path d="M3 44 L1 46 M3 56 L1 54 M97 44 L99 46 M97 56 L99 54" />
            </g>
            <motion.path
              d="M50 0.5 L53 4 L50 7.5 L47 4 Z"
              fill={`rgb(${accent})`}
              style={{ filter: `drop-shadow(0 0 2px rgb(${accent}))` }}
              animate={still ? undefined : { opacity: [0.75, 1, 0.75] }}
              transition={{ duration: 2.4, repeat: Infinity, ease: 'easeInOut' }}
            />
          </motion.g>
        )}
      </svg>
    </div>
  )
}

// ── Le maximum ───────────────────────────────────────────────────────────

/** Ce qu'on peut obtenir au mieux dans une saison. */
function MaxModal({ onClose }: { onClose: () => void }) {
  const t = useT()
  const rankName = useRankName()
  const champion = TIER_COLORS.champion
  return (
    <ModalShell onClose={onClose} title={t('competitive.season.maxTitle')} maxWidth="max-w-[540px]">
      <div className="flex flex-col items-center gap-5">
        <SeasonFrame level={BORDER_LEVELS} accent={champion} size="340px">
          <SeasonGrid wins={Array<number>(CELLS).fill(CHAMPION)} instant />
        </SeasonFrame>
        <div className="text-center">
          <p className="text-[32px] font-black uppercase leading-none" style={{ color: `rgb(${champion})`, textShadow: `0 0 26px rgb(${champion} / 0.45)` }}>
            {rankName(CHAMPION, true)}
          </p>
          <p className="mt-2 text-[12px] font-bold uppercase tracking-[0.05em] text-txt-secondary">
            {t('competitive.season.levelOf', { level: BORDER_LEVELS, max: BORDER_LEVELS })} · {t('competitive.season.levelAt', { count: (BORDER_LEVELS - 1) * WINS_PER_BORDER })}
          </p>
        </div>
        <p className="text-center text-[12px] leading-relaxed text-txt-secondary">{t('competitive.season.maxText')}</p>
        <p className="text-center text-[11.5px] leading-relaxed text-txt-muted">{t('competitive.season.explain')}</p>
      </div>
    </ModalShell>
  )
}
