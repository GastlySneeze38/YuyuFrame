import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'
import {
  DIVISIONS_PER_TIER,
  TIER_COLORS,
  TIER_KEYS,
  type BattlePass,
  type Challenge,
  type ChallengeDifficulty,
  type MissionsMeta,
  type PassObjective,
  type Season,
} from '@/lib/competitive'
import { RewardImage } from './RewardImage'
import { RankTile } from './RankCard'
import { TopoBackdrop } from './TopoBackdrop'
import { clamp, useElementSize } from '@/lib/useElementSize'

/** Couleur du passe de saison : l'accent du launcher. */
export const PASS_COLOR = '129 120 255'
/** Couleur des paliers gratuits du passe (repris par l'aperçu du back-office). */
export const FREE_COLOR = '52 211 153'

/** L'heure, rafraîchie chaque seconde : pour les comptes à rebours. */
/**
 * L'heure, remise à jour toutes les `everyMs` (1 s par défaut). Ce qui ne
 * montre que des jours en demande une par minute (2026-11-10) : la seconde
 * redessinait toute la liste des missions pour changer un seul chiffre. Les
 * comptes à rebours à la seconde ont leur propre composant (`LiveCountdown`),
 * seul à se redessiner.
 */
export function useNow(everyMs = 1000): number {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), everyMs)
    return () => clearInterval(timer)
  }, [everyMs])
  return now
}

/** Un compte à rebours à la seconde, qui seul se redessine. `until` : une
 *  échéance, ou de quoi la calculer depuis l'heure (minuit UTC suivant). */
export function LiveCountdown({ until, title }: { until: number | ((now: number) => number); title?: (time: string) => string }) {
  const now = useNow()
  const time = countdown((typeof until === 'function' ? until(now) : until) - now)
  return title ? <span title={title(time)}>{time}</span> : <>{time}</>
}

/** « 15:34:29 » sous un jour, « 3:16:49:05 » (jours en tête) au-delà. */
export function countdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const d = Math.floor(total / 86400)
  const pad = (n: number) => String(n).padStart(2, '0')
  const hms = `${pad(Math.floor((total % 86400) / 3600))}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`
  return d > 0 ? `${d}:${hms}` : hms
}

/**
 * Écran Missions : à gauche les missions du jour (et leurs paliers d'XP) et
 * de la semaine ; à droite le passe de saison et ses objectifs.
 *
 * Tout est en données de démonstration : ni les missions ni le passe n'ont
 * encore de route serveur (`null` → « bientôt »).
 */
export function ChallengesView({
  challenges,
  pass,
  season,
  meta,
  onReroll,
}: {
  challenges: Challenge[] | null
  pass: BattlePass | null
  season: Season | null
  meta?: MissionsMeta | null
  onReroll?: (id: string) => void
}) {
  return (
    // Les missions prennent une colonne qui s'élargit avec la fenêtre ; le
    // passe a le reste.
    <div className="grid h-full grid-cols-[290px_minmax(0,1fr)] gap-3 lg:grid-cols-[330px_minmax(0,1fr)] lg:gap-4 xl:grid-cols-[360px_minmax(0,1fr)]">
      <Missions challenges={challenges} pass={pass} meta={meta} onReroll={onReroll} />
      <PassColumn pass={pass} season={season} />
    </div>
  )
}

/** Prochain minuit UTC : l'heure du tirage des missions du jour. */
export function nextUtcMidnight(now: number): number {
  const d = new Date(now)
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1)
}

/** La couleur et le nombre de crans d'une difficulté. */
export const DIFFICULTY: Record<ChallengeDifficulty, { pips: number; color: string }> = {
  easy: { pips: 1, color: '94 214 140' },
  medium: { pips: 2, color: '120 170 255' },
  hard: { pips: 3, color: '255 170 80' },
  challenge: { pips: 3, color: '255 96 120' },
}

// ── Missions ─────────────────────────────────────────────────────────────

/** Missions du jour et de la semaine : aussi le volet de la page du passe.
 *  Les missions du jour sont tirées chaque jour à minuit UTC ; celles des
 *  jours d'avant pas encore finies restent en tête de liste, marquées
 *  « reportée », jusqu'à leur fin. */
export function Missions({
  challenges,
  pass,
  meta,
  onReroll,
}: {
  challenges: Challenge[] | null
  pass: BattlePass | null
  meta?: MissionsMeta | null
  onReroll?: (id: string) => void
}) {
  const t = useT()
  const now = useNow(60_000)
  if (challenges === null) {
    return (
      <div className="flex items-center justify-center rounded-2xl border border-line bg-surface-1 p-5">
        <p className="text-center text-[12.5px] text-txt-muted">{t('competitive.challenges.soon')}</p>
      </div>
    )
  }
  // Les reportées d'abord : ce sont elles qui finissent le plus tôt.
  const daily = challenges.filter((c) => c.period === 'daily').sort((a, b) => Number(!!b.carried) - Number(!!a.carried))
  const weekly = challenges.filter((c) => c.period === 'weekly')
  const today = daily.filter((c) => !c.carried)
  const reroll = meta && meta.rerollsLeft > 0 ? onReroll : undefined

  // Une ligne par mission, sans cadre ni barre ; les lignes et les écarts se
  // resserrent avec la hauteur de la fenêtre. Les deux sections se partagent
  // la hauteur, et si elle manque malgré tout la colonne défile plutôt que de
  // couper la semaine.
  return (
    <div className="flex min-h-0 flex-col justify-between gap-[clamp(10px,2.2vh,20px)] overflow-y-auto rounded-2xl border border-line bg-surface-1 px-4 py-[clamp(12px,2.2vh,20px)] lg:px-5">
      <section className="flex flex-col gap-[clamp(6px,1.3vh,12px)]">
        <SectionHead title={t('competitive.challenges.daily')} refill={daily.length ? nextUtcMidnight : null} />
        {pass && (
          <div title={t('competitive.missions.perCheckpoint', { xp: pass.checkpointXp.toLocaleString() })}>
            <Checkpoints done={pass.checkpointsDone} total={pass.checkpoints} />
          </div>
        )}
        {/* Le bonus de la journée complète : ce qui donne envie de finir les trois. */}
        {meta && meta.dayBonusXp > 0 && today.length > 0 && (
          <p className={`text-center text-[10.5px] font-bold uppercase tracking-[0.05em] ${meta.dayBonusDone ? 'text-success' : 'text-txt-muted'}`}>
            {meta.dayBonusDone
              ? t('competitive.missions.dayBonusDone', { xp: meta.dayBonusXp.toLocaleString() })
              : t('competitive.missions.dayBonus', { xp: meta.dayBonusXp.toLocaleString() })}
          </p>
        )}
        <div className="flex flex-col">
          {daily.map((c, i) => (
            <MissionRow key={c.id} mission={c} index={i} now={now} onReroll={c.rerollable ? reroll : undefined} />
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-[clamp(6px,1.3vh,12px)]">
        <SectionHead title={t('competitive.challenges.weekly')} refill={weekly[0] ? weekly[0].endsAt : null} />
        {weekly.every((c) => c.progress >= c.goal) ? (
          <p className="py-4 text-center text-[11px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('competitive.missions.allDone')}</p>
        ) : (
          <div className="flex flex-col">
            {weekly.map((c, i) => (
              <MissionRow key={c.id} mission={c} index={i} now={now} />
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

/** Titre d'une section et son compte à rebours (horloge), sur une ligne. */
function SectionHead({ title, refill }: { title: string; refill: number | ((now: number) => number) | null }) {
  const t = useT()
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="truncate text-[12.5px] font-black uppercase tracking-[0.04em]">{title}</span>
      {refill !== null && (
        <span className="flex shrink-0 items-center gap-1 text-[11px] tabular-nums text-txt-muted">
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="currentColor"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm1 11h-5v-2h3V6h2z" /></svg>
          <LiveCountdown until={refill} title={(time) => t('competitive.missions.refills', { time })} />
        </span>
      )}
    </div>
  )
}

/** Les paliers du jour : un losange par mission terminée, reliés par un trait. */
function Checkpoints({ done, total }: { done: number; total: number }) {
  return (
    <div className="flex items-center justify-between px-1">
      {Array.from({ length: total }, (_, i) => {
        const filled = i < done
        return (
          <div key={i} className="flex flex-1 items-center last:flex-none">
            <motion.div
              initial={{ scale: 0.6, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ delay: i * 0.06, type: 'spring', stiffness: 300, damping: 18 }}
              className="relative flex h-[clamp(28px,4.6vh,36px)] w-[clamp(28px,4.6vh,36px)] shrink-0 items-center justify-center"
            >
              <span
                className={`absolute inset-[5px] rotate-45 rounded-[4px] border-2 ${
                  filled ? 'border-accent bg-accent/30 shadow-[0_0_14px_rgb(var(--accent)/0.5)]' : 'border-white/15 bg-white/[0.02]'
                }`}
              />
              <span className={`relative text-[13px] font-black ${filled ? 'text-white' : 'text-white/35'}`}>{i + 1}</span>
            </motion.div>
            {i < total - 1 && <span className={`mx-1 h-0.5 flex-1 rounded-full ${i < done - 1 ? 'bg-accent/70' : 'bg-white/[0.08]'}`} />}
          </div>
        )
      })}
    </div>
  )
}

/** La phrase d'une mission : `count` est le nombre à atteindre, les autres
 *  la valeur de la phrase (note, secondes, RR). */
export function missionText(t: ReturnType<typeof useT>, c: Challenge): string {
  return t(`competitive.challenges.kinds.${c.kind}`, { count: c.goal, score: c.target, seconds: c.target, rr: c.target })
}

/** Une mission sur une ligne : un anneau de progression (une coche quand
 *  c'est fait), la phrase, la difficulté en crans, le mode en petit, où l'on
 *  en est, l'XP ; au survol, la relance quand elle est possible. */
function MissionRow({ mission: c, index, now, onReroll }: { mission: Challenge; index: number; now: number; onReroll?: (id: string) => void }) {
  const t = useT()
  const [busy, setBusy] = useState(false)
  const done = c.progress >= c.goal
  const ratio = Math.min(1, c.progress / c.goal)
  const R = 8
  const C = 2 * Math.PI * R
  const level = DIFFICULTY[c.difficulty]
  const daysLeft = Math.max(0, Math.ceil((c.endsAt - now) / 86_400_000))
  return (
    <motion.div
      initial={{ opacity: 0, x: -6 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ delay: index * 0.04 }}
      className="group flex items-center gap-3 border-b border-white/[0.05] py-[clamp(5px,1.2vh,10px)] last:border-b-0"
    >
      {done ? (
        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-success/20 text-success">
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="currentColor"><path d="M9 16.2l-3.5-3.5L4 14.2 9 19.2 20 8.2l-1.5-1.5z" /></svg>
        </span>
      ) : (
        <svg viewBox="0 0 20 20" className="h-5 w-5 shrink-0 -rotate-90">
          <circle cx="10" cy="10" r={R} fill="none" stroke="rgb(255 255 255 / 0.1)" strokeWidth="2.5" />
          <motion.circle
            cx="10"
            cy="10"
            r={R}
            fill="none"
            stroke="rgb(var(--accent))"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeDasharray={C}
            initial={{ strokeDashoffset: C }}
            animate={{ strokeDashoffset: C * (1 - ratio) }}
            transition={{ duration: 0.7, ease: [0.16, 1, 0.3, 1], delay: 0.1 + index * 0.04 }}
          />
        </svg>
      )}
      <div className="min-w-0 flex-1">
        <p title={missionText(t, c)} className={`truncate text-[12.5px] font-semibold ${done ? 'text-txt-muted line-through decoration-white/20' : ''}`}>
          {missionText(t, c)}
        </p>
        <p className="flex items-center gap-1.5 text-[10.5px] tabular-nums text-txt-muted">
          {/* La difficulté en crans : un, deux, trois ; le défi en rouge. */}
          <span title={t(`competitive.missions.difficulty.${c.difficulty}`)} className="flex items-center gap-[2px]">
            {Array.from({ length: 3 }, (_, i) => (
              <span
                key={i}
                className="h-[7px] w-[3px] rounded-full"
                style={{ background: i < level.pips ? `rgb(${level.color})` : 'rgb(255 255 255 / 0.12)' }}
              />
            ))}
          </span>
          <span>
            {c.progress} / {c.goal}
          </span>
          {c.mode && <span>· {t(`competitive.modes.${c.mode}`)}</span>}
          {c.carried && !done && (
            <span className="font-semibold text-warning">· {t('competitive.missions.carried', { count: daysLeft })}</span>
          )}
        </p>
      </div>
      {onReroll && !done && (
        <button
          type="button"
          disabled={busy}
          onClick={async () => {
            setBusy(true)
            try {
              await onReroll(c.id)
            } finally {
              setBusy(false)
            }
          }}
          title={t('competitive.missions.reroll')}
          aria-label={t('competitive.missions.reroll')}
          className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-txt-muted opacity-0 transition-opacity hover:bg-white/[0.06] hover:text-white focus-visible:opacity-100 group-hover:opacity-100 disabled:opacity-40"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className={`h-3.5 w-3.5 ${busy ? 'animate-spin' : ''}`}>
            <path d="M21 12a9 9 0 11-2.64-6.36M21 4v5h-5" />
          </svg>
        </button>
      )}
      <span className={`shrink-0 text-[11px] font-bold tabular-nums ${done ? 'text-success' : 'text-accent-hover'}`}>
        {t('competitive.missions.xp', { xp: c.xp.toLocaleString() })}
      </span>
    </motion.div>
  )
}

// ── Passe de saison ──────────────────────────────────────────────────────

function PassColumn({ pass, season }: { pass: BattlePass | null; season: Season | null }) {
  const t = useT()
  // Les objectifs se règlent sur la case qu'ils ont : l'image prend ce que
  // le texte laisse ; trop bas, la carte passe à l'horizontale (image à
  // gauche, texte à droite).
  const [gridRef, grid] = useElementSize<HTMLDivElement>()
  const cardWidth = grid.width === 0 ? 220 : (grid.width - 2 * 12) / 3
  const horizontal = grid.height > 0 && grid.height < 150
  const imageSize = horizontal
    ? Math.floor(clamp(grid.height - 40, 30, 56))
    : grid.height === 0
      ? 88
      : Math.floor(clamp(Math.min(grid.height - 118, cardWidth - 48), 36, 88))
  if (pass === null) {
    return (
      <div className="flex items-center justify-center rounded-2xl border border-line bg-surface-1 p-5">
        <p className="text-center text-[12.5px] text-txt-muted">{t('competitive.pass.soon')}</p>
      </div>
    )
  }
  return (
    // Le passe en haut, les objectifs en dessous : la hauteur est partagée
    // presque à égalité pour que les objectifs aient de la place.
    <div className="grid min-h-0 grid-rows-[minmax(0,1.1fr)_minmax(0,1fr)] gap-[clamp(10px,1.8vh,16px)]">
      <PassHero pass={pass} season={season} />
      <div className="flex min-h-0 flex-col gap-2">
        <span className="text-[12.5px] font-black uppercase tracking-[0.04em]">{t('competitive.pass.objectives')}</span>
        <div ref={gridRef} className="grid min-h-0 flex-1 grid-cols-3 gap-3">
          {pass.objectives.map((o, i) => (
            <Objective key={i} objective={o} pass={pass} index={i} imageSize={imageSize} horizontal={horizontal} />
          ))}
        </div>
      </div>
    </div>
  )
}

function PassHero({ pass, season }: { pass: BattlePass; season: Season | null }) {
  const t = useT()
  const navigate = useNavigate()
  const plan = useStore((s) => s.yuyuPlan)
  const owned = plan !== 'free'
  // La récompense suivante n'est réservée au passe que si son palier n'est
  // pas gratuit.
  const locked = !owned && !pass.rewards[pass.featured.tier - 1]?.free
  const daysLeft = season?.end ? Math.max(0, Math.ceil((season.end - Date.now()) / 86_400_000)) : null
  const xpLeft = pass.xpPerTier - pass.xpInTier
  const openPass = () => navigate('/missions/pass')
  // La carte se règle sur sa propre taille : l'image suit sa hauteur, la
  // colonne de droite sa largeur ; les textes passent par ses requêtes de
  // conteneur (cw-*).
  const [boxRef, box] = useElementSize<HTMLDivElement>()
  const imageSize = box.height === 0 ? 116 : Math.floor(clamp(box.height * 0.42, 52, 116))
  const sideWidth = box.width === 0 ? 260 : Math.floor(clamp(box.width * 0.34, 140, 260))

  return (
    <div
      ref={boxRef}
      role="button"
      tabIndex={0}
      onClick={openPass}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPass() } }}
      title={t('competitive.pass.seeTiers')}
      className="group relative flex min-h-0 cursor-pointer overflow-hidden rounded-2xl border border-line bg-surface-1 transition-colors duration-200 [container-type:inline-size] hover:border-accent/40"
      style={{ isolation: 'isolate' }}
    >
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        <TopoBackdrop color={PASS_COLOR} />
        <div className="absolute inset-0" style={{ background: `radial-gradient(28rem 18rem at 72% 45%, rgb(${PASS_COLOR} / 0.22), transparent 70%)` }} />
      </div>

      {/* À gauche : la saison, la récompense suivante, la progression. */}
      <div className="flex min-w-0 flex-1 flex-col justify-between gap-2 p-[clamp(12px,2.2vh,20px)]">
        <div className="min-w-0">
          <p className="truncate text-[13px] font-black uppercase tracking-[0.03em] cw-md:text-[15px]">
            {season ? t('competitive.pass.season', { number: season.number, name: season.name }) : t('competitive.pass.title')}
          </p>
          {daysLeft !== null && (
            <p className="mt-0.5 truncate text-[11.5px] font-bold uppercase cw-md:text-[13px]">
              {t('competitive.pass.endsIn')} <span className="text-warning">{t('competitive.pass.days', { count: daysLeft })}</span>
            </p>
          )}
        </div>

        <div className="flex min-w-0 flex-col gap-[clamp(6px,1.3vh,12px)]">
          {locked && (
            <span className="flex min-w-0 items-center gap-1.5 text-[12px] font-semibold text-txt-secondary">
              <svg viewBox="0 0 24 24" className="h-4 w-4 shrink-0 text-warning" fill="currentColor"><path d="M12 2a5 5 0 00-5 5v3H6a2 2 0 00-2 2v8a2 2 0 002 2h12a2 2 0 002-2v-8a2 2 0 00-2-2h-1V7a5 5 0 00-5-5zm-3 8V7a3 3 0 016 0v3z" /></svg>
              <span className="truncate">{t('competitive.pass.requires')}</span>
            </span>
          )}
          <p className="truncate text-[17px] font-black uppercase leading-none tracking-[-0.01em] cw-md:text-[22px]">
            {t(`competitive.seasons.kinds.${pass.featured.kind}`, { rank: pass.featured.name })}
          </p>
          <div className="flex max-w-[440px] flex-col gap-1.5">
            <div className="flex items-baseline justify-between gap-2 whitespace-nowrap text-[11px] font-bold cw-md:text-[11.5px]">
              <span className="flex min-w-0 items-center gap-2">
                {t('competitive.pass.tier', { tier: pass.tier, total: pass.tiers })}
                {/* « Voir les paliers » : seulement quand il y a la place — la carte entière y mène. */}
                <span className="hidden items-center gap-0.5 text-[10.5px] font-semibold text-txt-muted transition-colors group-hover:text-accent-hover cw-lg:flex">
                  {t('competitive.pass.seeTiers')}
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" className="h-3 w-3 transition-transform group-hover:translate-x-0.5">
                    <path d="M9 6l6 6-6 6" />
                  </svg>
                </span>
              </span>
              <span className="shrink-0 text-success">{t('competitive.pass.xpToGo', { xp: xpLeft.toLocaleString() })}</span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-white/[0.08]">
              <motion.div
                className="h-full rounded-full bg-white"
                initial={{ width: 0 }}
                animate={{ width: `${(pass.xpInTier / pass.xpPerTier) * 100}%` }}
                transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1] }}
              />
            </div>
          </div>
        </div>
      </div>

      {/* À droite : la récompense en grand, et de quoi débloquer le passe. */}
      <div className="flex shrink-0 flex-col items-center justify-center gap-[clamp(8px,1.6vh,16px)] p-[clamp(10px,2vh,20px)]" style={{ width: sideWidth }}>
        <motion.div
          initial={{ scale: 0.8, opacity: 0, rotate: -6 }}
          animate={{ scale: 1, opacity: 1, rotate: 0, y: [0, -6, 0] }}
          transition={{ scale: { type: 'spring', stiffness: 220, damping: 18 }, y: { duration: 4, repeat: Infinity, ease: 'easeInOut' } }}
        >
          <RewardImage reward={pass.rewards[pass.featured.tier - 1] ?? pass.featured} color={PASS_COLOR} tierIndex={3} size={imageSize} />
        </motion.div>
        {owned ? (
          <span className="whitespace-nowrap rounded-xl border border-success/40 bg-success/[0.1] px-3 py-2 text-[11px] font-black uppercase tracking-[0.05em] text-success cw-md:px-5 cw-md:py-2.5 cw-md:text-[12px]">
            {t('competitive.pass.owned')}
          </span>
        ) : (
          <button
            onClick={(e) => {
              // La carte entière ouvre les paliers : ce bouton-là mène ailleurs.
              e.stopPropagation()
              navigate('/plans')
            }}
            className="whitespace-nowrap rounded-xl bg-white px-3 py-2 text-[11px] font-black uppercase tracking-[0.05em] text-black transition-transform hover:scale-[1.03] active:scale-[0.98] cw-md:px-6 cw-md:py-2.5 cw-md:text-[12px]"
          >
            {t('competitive.pass.unlock')}
          </button>
        )}
      </div>
    </div>
  )
}

function Objective({ objective: o, pass, index, imageSize, horizontal }: {
  objective: PassObjective
  pass: BattlePass
  index: number
  /** Taille de l'image, calculée d'après la case (voir `PassColumn`). */
  imageSize: number
  /** Case trop basse : image à gauche, texte à droite. */
  horizontal: boolean
}) {
  const t = useT()
  const now = useNow(60_000)
  const color = o.kind === 'rank' ? TIER_COLORS[o.tier] : PASS_COLOR

  const title =
    o.kind === 'unlock'
      ? t(`competitive.seasons.kinds.${o.reward}`, { rank: o.name })
      : o.kind === 'tiers'
        ? t('competitive.pass.title')
        : t('competitive.pass.reachRank', { rank: t(`competitive.tiers.${o.tier}`) })
  const sub =
    o.kind === 'unlock'
      ? t('competitive.pass.xpToGo', { xp: o.xpLeft.toLocaleString() })
      : o.kind === 'tiers'
        ? t('competitive.pass.tier', { tier: o.done, total: o.total })
        : t(`competitive.seasons.kinds.${o.reward}`, { rank: t(`competitive.tiers.${o.tier}`) })

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.15 + index * 0.06 }}
      // La carte remplit sa case : l'image en grand au centre, puis le texte
      // et la progression en bas.
      className="relative flex min-h-0 flex-col overflow-hidden rounded-2xl border border-line bg-surface-1"
      style={{ backgroundImage: `radial-gradient(16rem 12rem at 50% 32%, rgb(${color} / 0.2), transparent 70%)` }}
    >
      <div
        className={`flex min-h-0 flex-1 items-center ${
          horizontal ? 'gap-2.5 px-3 py-2 text-left' : 'flex-col justify-center gap-[clamp(6px,1.4vh,16px)] px-3 py-[clamp(8px,1.8vh,20px)] text-center lg:px-5'
        }`}
      >
        <div className="shrink-0" style={{ filter: `drop-shadow(0 0 16px rgb(${color} / 0.4))` }}>
          {o.kind === 'rank' ? (
            <RankTile division={TIER_KEYS.indexOf(o.tier) * DIVISIONS_PER_TIER} placed size={imageSize} />
          ) : (
            <RewardImage
              reward={
                (o.kind === 'unlock' ? pass.rewards.find((r) => r.kind === o.reward && r.name === o.name) : pass.rewards[pass.featured.tier - 1]) ??
                { kind: o.kind === 'unlock' ? o.reward : pass.featured.kind, name: '' }
              }
              color={color}
              tierIndex={3}
              size={imageSize}
            />
          )}
        </div>
        <div className={horizontal ? 'flex min-w-0 flex-1 flex-col justify-center' : 'w-full min-w-0'}>
          {/* Le titre entier dans la bulle quand il est coupé. */}
          <p title={title} className="truncate text-[clamp(12px,1.9vh,15px)] font-black uppercase leading-tight">
            {title}
          </p>
          <p className="mt-0.5 truncate text-[11px] font-semibold lg:text-[12px]" style={{ color: `rgb(${color})` }}>
            {sub}
          </p>
          {o.kind === 'tiers' ? (
            // Les paliers du passe, en segments.
            <div className="mt-[clamp(6px,1.2vh,12px)] flex gap-1">
              {Array.from({ length: 5 }, (_, i) => (
                <span key={i} className={`h-2 flex-1 rounded-full ${i < Math.round((o.done / o.total) * 5) ? 'bg-white/80' : 'bg-white/[0.1]'}`} />
              ))}
            </div>
          ) : (
            <div className="mt-[clamp(6px,1.2vh,12px)] h-2 overflow-hidden rounded-full bg-white/[0.08]">
              <motion.div
                className="h-full rounded-full"
                style={{ background: `rgb(${color})` }}
                initial={{ width: 0 }}
                animate={{ width: `${o.ratio * 100}%` }}
                transition={{ duration: 0.8, ease: [0.16, 1, 0.3, 1], delay: 0.2 }}
              />
            </div>
          )}
        </div>
      </div>
      {o.kind === 'unlock' && (
        <div className="flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap bg-danger/[0.85] px-2 py-1 text-[9.5px] font-black uppercase tracking-[0.04em] text-white lg:px-4 lg:py-1.5 lg:text-[10.5px]">
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 shrink-0" fill="currentColor"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm1 11h-5v-2h3V6h2z" /></svg>
          <span className="truncate">
            {t('competitive.pass.endsIn')} {t('competitive.pass.days', { count: Math.max(0, Math.ceil((o.endsAt - now) / 86_400_000)) })}
          </span>
        </div>
      )}
    </motion.div>
  )
}
