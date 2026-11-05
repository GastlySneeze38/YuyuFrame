import { useEffect, useId, useState, type ReactNode } from 'react'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { isDemo } from '@/stores/useDevFlags'
import type { ModeKey } from '@/lib/competitive'
import { SHOWCASE_MAX } from '@/lib/friends'
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
 * « Afficher à côté de mon profil » : la grille de ce mode dans la vitrine de
 * mon profil (panneau des amis). L'état vient de mon profil ; le changer
 * l'enregistre aussitôt. `null` : on ne sait pas (pas de compte, pas de
 * route) — l'interrupteur se grise.
 */
export function useSeasonOnProfile(mode: ModeKey) {
  const t = useT()
  const [on, setOn] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    if (isDemo()) {
      setOn(false)
      return
    }
    api.friends
      .myProfile()
      .then((p) => alive && setOn(p.showcase.some((s) => s.kind === 'season' && s.mode === mode)))
      .catch(() => alive && setOn(null))
    return () => {
      alive = false
    }
  }, [mode])

  async function toggle() {
    if (on === null || busy) return
    if (isDemo()) return setOn(!on)
    setBusy(true)
    try {
      const profile = await api.friends.myProfile()
      const without = profile.showcase.filter((s) => !(s.kind === 'season' && s.mode === mode))
      if (!on && without.length >= SHOWCASE_MAX) {
        showError(t('competitive.season.showcaseFull'))
        return
      }
      const showcase = on ? without : [...without, { kind: 'season' as const, mode }]
      const saved = await api.friends.setProfile({ ...profile, showcase })
      setOn(saved.showcase.some((s) => s.kind === 'season' && s.mode === mode))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return { on, busy, toggle }
}

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
 * Le « i » ouvre ce qu'on peut obtenir au mieux. L'interrupteur met la grille
 * de ce mode dans la vitrine de mon profil (panneau des amis), ou l'en retire.
 *
 * `wins === null` : pas encore de route pour ça. `count` : toutes les
 * victoires de la saison, quand le serveur n'envoie que les meilleures.
 * `readOnly` : la saison d'un ami, sans le réglage d'affichage au profil.
 */
export function SeasonRankView({
  wins,
  count,
  season,
  current,
  mode,
  readOnly = false,
}: {
  wins: number[] | null
  count?: number
  season: Season | null
  /** Le rang actuel dans ce mode. Le titre ne descend jamais sous lui : les
   *  duels de placement ne remplissent pas la grille, donc juste après le
   *  placement il n'y a encore aucune victoire classée, et le titre disait
   *  « Non classé » à un joueur déjà classé. */
  current?: { division: number; placed: boolean }
  /** Le mode montré : c'est sa grille qui va (ou non) dans la vitrine. */
  mode: ModeKey
  readOnly?: boolean
}) {
  const t = useT()
  const rankName = useRankName()
  const onProfile = useSeasonOnProfile(mode)
  const [showMax, setShowMax] = useState(false)

  if (wins === null) {
    return (
      <div className="flex h-full items-center justify-center rounded-2xl border border-line bg-surface-1">
        <p className="text-[12.5px] text-txt-muted">{t('competitive.season.soon')}</p>
      </div>
    )
  }

  const best = [...wins].sort((a, b) => b - a).slice(0, CELLS)
  const bestWin = best[0] ?? null
  const top = current?.placed ? Math.max(bestWin ?? 0, current.division) : bestWin
  const color = top !== null ? TIER_COLORS[tierOf(top)] : '160 150 190'
  const total = Math.max(count ?? 0, wins.length)
  const level = borderLevel(total)
  const maxed = level >= BORDER_LEVELS
  const nextAt = level * WINS_PER_BORDER
  const levelProgress = maxed ? 1 : (total - (level - 1) * WINS_PER_BORDER) / WINS_PER_BORDER
  const daysLeft = season?.end ? Math.max(0, Math.ceil((season.end - Date.now()) / 86_400_000)) : null

  return (
    <div className="relative grid h-full grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)] overflow-hidden rounded-2xl border border-line bg-surface-1" style={{ isolation: 'isolate' }}>
      <div aria-hidden="true" className="absolute inset-0 -z-10 opacity-60">
        <TopoBackdrop color={color} />
      </div>

      {/* La grille, dans sa bordure. */}
      {/* La grille suit la plus petite des deux dimensions : sur une fenêtre
          étroite, c'est la largeur de sa colonne qui la borne. */}
      <div className="flex min-w-0 items-center justify-center p-4 lg:p-6">
        <SeasonFrame level={level} accent={color} size="min(64vh, 480px, 44vw)">
          <SeasonGrid wins={best} />
        </SeasonFrame>
      </div>

      <div className="flex min-h-0 min-w-0 flex-col gap-5 overflow-y-auto [justify-content:safe_center] py-6 pl-1 pr-5 lg:gap-8 lg:py-8 lg:pl-2 lg:pr-10">
        {/* 1. Le rang de la saison : ce qu'on lit en premier. */}
        <div>
          {season && (
            <p className="text-[11px] font-bold uppercase tracking-[0.16em] text-txt-muted">
              {t('competitive.pass.season', { number: season.number, name: season.name })}
              {daysLeft !== null && <span className="text-txt-secondary"> · {t('competitive.season.daysLeft', { count: daysLeft })}</span>}
            </p>
          )}
          <div className="mt-3 flex items-center gap-3">
            <p className="text-[clamp(36px,5vw,60px)] font-black uppercase leading-none tracking-[-0.01em]" style={{ color: `rgb(${color})`, textShadow: `0 0 30px rgb(${color} / 0.35)` }}>
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
        </div>

        {/* 2. La bordure : tout ce qui la concerne dans un seul bloc. */}
        <div className="flex flex-col gap-4 rounded-2xl border border-white/[0.07] bg-black/30 p-4 backdrop-blur-sm lg:p-5">
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
              {maxed ? t('competitive.season.maxed') : t('competitive.season.progress', { wins: total, next: nextAt })}
            </p>
          </div>

          {/* Les cinq bordures : la sienne en clair, les suivantes en retrait.
              Une case par niveau, toutes de même largeur : la bordure s'inscrit
              dans la sienne, donc rien ne déborde sur une colonne étroite. */}
          <div className="grid grid-cols-5 items-end gap-1">
            {Array.from({ length: BORDER_LEVELS }, (_, i) => {
              const l = i + 1
              return (
                <div key={l} className={`flex min-w-0 flex-col items-center gap-1 ${l <= level ? '' : 'opacity-30'}`}>
                  <SeasonFrame level={l} accent={color} size="clamp(34px, 4.4vw, 58px)" still>
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

        {/* 3. L'affichage au profil, en dernier — seulement sur ma propre saison. */}
        {!readOnly && (
          <label className="flex cursor-pointer items-center justify-between gap-3 rounded-2xl border border-white/[0.07] bg-black/30 px-5 py-3.5 backdrop-blur-sm">
            <span className="text-[12px] text-txt-secondary">{t('competitive.season.showOnProfile')}</span>
            {/* Sans profil lisible (pas de compte, pas de route), le geste ne fait rien. */}
            <span className={onProfile.on === null ? 'pointer-events-none opacity-40' : ''}>
              <Toggle checked={onProfile.on === true} onChange={() => void onProfile.toggle()} size="sm" />
            </span>
          </label>
        )}
      </div>

      <AnimatePresence>{showMax && <MaxModal onClose={() => setShowMax(false)} />}</AnimatePresence>
    </div>
  )
}

// ── La grille ────────────────────────────────────────────────────────────

/**
 * Les cases : les victoires (déjà triées) d'abord, puis le vide.
 *
 * Au survol d'une case, elle grossit et luit dans la couleur de son rang ; les
 * victoires des autres rangs s'estompent, et une bulle dit le rang et combien
 * de victoires la grille en compte.
 */
export function SeasonGrid({ wins, instant = false }: { wins: number[]; instant?: boolean }) {
  const t = useT()
  const rankName = useRankName()
  const [hover, setHover] = useState<number | null>(null)
  // Dès le premier survol, plus de délai d'apparition : une case qui
  // reprend sa taille doit le faire tout de suite.
  const [touched, setTouched] = useState(false)
  const hovered = hover !== null ? wins[hover] : undefined
  const sameRank = hovered !== undefined ? wins.filter((w) => w === hovered).length : 0

  // La bulle a une place fixe, centrée sous la bordure : elle ne cache aucune
  // case et ne saute pas d'un bord à l'autre en suivant la souris. La grille
  // occupe la bordure de INNER % à (100 − INNER) % ; le bas de la bordure est
  // donc à (100 − INNER) / (100 − 2 × INNER) de la hauteur de la grille.
  const frameBottom = ((100 - INNER) / (100 - 2 * INNER)) * 100

  return (
    <div className="relative h-full w-full" onMouseLeave={() => setHover(null)}>
      <div className="grid h-full w-full gap-[3px] bg-[rgb(10_10_16)] p-[4px]" style={{ gridTemplateColumns: `repeat(${SEASON_GRID}, 1fr)` }}>
        {Array.from({ length: CELLS }, (_, i) => {
          const win = wins[i]
          if (win === undefined) return <div key={i} className="rounded-[2px] border border-white/[0.05] bg-white/[0.02]" />
          const c = TIER_COLORS[tierOf(win)]
          const isHover = hover === i
          const dimmed = hovered !== undefined && win !== hovered
          return (
            <motion.div
              key={i}
              onMouseEnter={() => {
                setHover(i)
                setTouched(true)
              }}
              // Au doigt, pas de survol : toucher une case la montre, la
              // toucher encore la relâche. (Le navigateur simule ensuite un
              // survol sur la même case, qui ne change rien.)
              onPointerDown={(e) => {
                if (e.pointerType === 'mouse') return
                setHover(isHover ? null : i)
                setTouched(true)
              }}
              // Jamais depuis rien : une case qui part de zéro semble sortir de nulle part.
              initial={{ scale: 0.4, opacity: 0 }}
              animate={{ scale: isHover ? 1.28 : 1, opacity: 1 }}
              // L'apparition a son délai ; le survol, non : il doit répondre tout de suite.
              transition={
                touched
                  ? { type: 'spring', stiffness: 500, damping: 26 }
                  : { delay: instant ? i * 0.006 : 0.15 + i * 0.012, type: 'spring', stiffness: 400, damping: 22 }
              }
              className="relative cursor-pointer overflow-hidden rounded-[2px] transition-[filter,box-shadow] duration-150"
              style={{
                zIndex: isHover ? 10 : 0,
                filter: dimmed ? 'brightness(0.35) saturate(0.5)' : 'none',
                boxShadow: isHover
                  ? `0 0 0 1.5px rgb(255 255 255 / 0.9), 0 0 18px 2px rgb(${c} / 0.8)`
                  : 'inset 1px 1px 0 rgb(255 255 255 / 0.3), inset -1px -1px 0 rgb(0 0 0 / 0.4)',
              }}
            >
              <img src={TIER_TEXTURES[tierOf(win)]} alt="" draggable={false} className="absolute inset-0 h-full w-full [image-rendering:pixelated]" />
            </motion.div>
          )
        })}
      </div>

      {/* La place de la bulle en CSS ; son apparition par Motion, à l'intérieur
          (Motion écraserait un `transform` posé sur l'élément qu'il anime). */}
      {hovered !== undefined && (
        <div
          className="pointer-events-none absolute z-20 whitespace-nowrap"
          style={{
            left: '50%',
            top: `${frameBottom}%`,
            transform: 'translate(-50%, 12px)',
          }}
        >
          <motion.div
            // Une nouvelle apparition seulement quand le rang survolé change.
            key={hovered}
            initial={{ opacity: 0, y: -4, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ duration: 0.12 }}
          >
            <div
              className="flex items-center gap-2.5 rounded-xl border bg-[rgb(14_13_20/0.96)] px-3 py-2 shadow-[0_10px_30px_rgba(0,0,0,0.6)] backdrop-blur-md"
              style={{ borderColor: `rgb(${TIER_COLORS[tierOf(hovered)]} / 0.5)` }}
            >
              <img
                src={TIER_TEXTURES[tierOf(hovered)]}
                alt=""
                className="h-7 w-7 rounded-[5px] [image-rendering:pixelated]"
                style={{ boxShadow: 'inset 1px 1px 0 rgb(255 255 255 / 0.35), inset -1px -1px 0 rgb(0 0 0 / 0.45)' }}
              />
              <div>
                <p className="text-[13px] font-black leading-tight" style={{ color: `rgb(${TIER_COLORS[tierOf(hovered)]})` }}>
                  {rankName(hovered, true)}
                </p>
                <p className="text-[10.5px] leading-tight text-txt-muted">{t('competitive.season.cellWins', { count: sameRank })}</p>
              </div>
            </div>
          </motion.div>
        </div>
      )}
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
export function MaxModal({ onClose }: { onClose: () => void }) {
  const t = useT()
  const rankName = useRankName()
  const champion = TIER_COLORS.champion
  return (
    <ModalShell onClose={onClose} title={t('competitive.season.maxTitle')} maxWidth="max-w-[540px]">
      <div className="flex flex-col items-center gap-5">
        {/* Bornée par la largeur : sur téléphone, la fenêtre est une feuille de toute la largeur. */}
        <SeasonFrame level={BORDER_LEVELS} accent={champion} size="min(340px, calc(100vw - 72px))">
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
