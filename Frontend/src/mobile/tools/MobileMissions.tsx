import { useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'
import { ScreenHeader } from '@/mobile/ui/ScreenHeader'
import { CARD, LIST, Note, RowChevron, Section } from '@/mobile/ui/kit'
import { RewardImage } from '@/components/competitive/RewardImage'
import { RankTile } from '@/components/competitive/RankCard'
import { TopoBackdrop } from '@/components/competitive/TopoBackdrop'
import { DIFFICULTY, FREE_COLOR, PASS_COLOR, countdown, missionText, nextUtcMidnight, useNow } from '@/components/competitive/Challenges'
import { usePass } from '@/lib/usePass'
import {
  DIVISIONS_PER_TIER,
  TIER_COLORS,
  TIER_KEYS,
  type BattlePass,
  type Challenge,
  type MissionsMeta,
  type PassObjective,
  type PassReward,
  type Season,
} from '@/lib/competitive'

/**
 * Missions et passe de saison sur téléphone : les mêmes données que le PC
 * (`usePass`), deux écrans.
 *
 * - **Missions** (`/missions`) : la carte du passe en tête — elle ouvre les
 *   paliers —, puis les missions du jour et de la semaine, puis les objectifs.
 *   Le PC les pose côte à côte ; ici, une colonne dans l'ordre de lecture.
 *   La relance d'une mission, au survol sur PC, est un bouton toujours visible.
 * - **Passe** (`/missions/pass`) : la récompense choisie en grand, les cinq
 *   paliers du chapitre sur toute la largeur, et les chapitres en flèches —
 *   on peut aussi glisser le doigt sur les paliers pour changer de chapitre.
 *
 * Pas d'animation en boucle : ni flottement de la récompense, ni bouton qui
 * respire.
 */

const ease = [0.23, 1, 0.32, 1] as const

function Bar({ ratio, color = '255 255 255', className = 'h-2' }: { ratio: number; color?: string; className?: string }) {
  const reduce = useReducedMotion()
  return (
    <div className={`overflow-hidden rounded-full bg-white/[0.08] ${className}`}>
      <motion.div
        className="h-full origin-left rounded-full"
        style={{ background: `rgb(${color})` }}
        initial={reduce ? false : { scaleX: 0 }}
        animate={{ scaleX: Math.min(1, Math.max(0, ratio)) }}
        transition={{ duration: 0.6, ease }}
      />
    </div>
  )
}

function LockIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="currentColor" aria-hidden="true">
      <path d="M12 2a5 5 0 00-5 5v3H6a2 2 0 00-2 2v8a2 2 0 002 2h12a2 2 0 002-2v-8a2 2 0 00-2-2h-1V7a5 5 0 00-5-5zm-3 8V7a3 3 0 016 0v3z" />
    </svg>
  )
}

function Clock({ time }: { time: string }) {
  return (
    <span className="flex shrink-0 items-center gap-1 text-[13px] tabular-nums text-white/55">
      <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm1 11h-5v-2h3V6h2z" /></svg>
      {time}
    </span>
  )
}

// ── Missions ─────────────────────────────────────────────────────────────

export function MobileMissions() {
  const t = useT()
  const { challenges, pass, season, meta, reroll } = usePass()
  return (
    <div className="flex h-full flex-col overflow-hidden bg-[rgb(var(--bg-primary))] text-txt-primary">
      <ScreenHeader title={t('features.missionsTitle')} fallback="/features" />
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(32px,env(safe-area-inset-bottom))] pt-4">
        <div className="flex flex-col gap-8">
          {pass ? <PassCard pass={pass} season={season} /> : <Note>{t('competitive.pass.soon')}</Note>}
          <MissionLists challenges={challenges} pass={pass} meta={meta} onReroll={reroll} />
          {pass && pass.objectives.length > 0 && (
            <Section title={t('competitive.pass.objectives')}>
              <div className={LIST}>
                {pass.objectives.map((o, i) => <ObjectiveRow key={i} objective={o} pass={pass} />)}
              </div>
            </Section>
          )}
        </div>
      </div>
    </div>
  )
}

/** La carte du passe : la saison, la récompense suivante, la progression ; elle ouvre les paliers. */
function PassCard({ pass, season }: { pass: BattlePass; season: Season | null }) {
  const t = useT()
  const navigate = useNavigate()
  const owned = useStore((s) => s.yuyuPlan) !== 'free'
  const locked = !owned && !pass.rewards[pass.featured.tier - 1]?.free
  const daysLeft = season?.end ? Math.max(0, Math.ceil((season.end - Date.now()) / 86_400_000)) : null

  return (
    <div className="relative isolate overflow-hidden rounded-[24px] border border-white/[0.08]">
      <div aria-hidden className="absolute inset-0 -z-10">
        <TopoBackdrop color={PASS_COLOR} still />
        <div className="absolute inset-0" style={{ background: `radial-gradient(20rem 14rem at 80% 30%, rgb(${PASS_COLOR} / 0.25), transparent 70%)` }} />
      </div>
      <button onClick={() => navigate('/missions/pass')} className="press flex w-full flex-col gap-4 px-5 pb-4 pt-5 text-left">
        <div className="flex items-start gap-4">
          <div className="min-w-0 flex-1">
            <p className="text-[15px] font-black uppercase tracking-[0.03em] text-white">
              {season ? t('competitive.pass.season', { number: season.number, name: season.name }) : t('competitive.pass.title')}
            </p>
            {daysLeft !== null && (
              <p className="mt-0.5 text-[13px] font-semibold uppercase text-white/70">
                {t('competitive.pass.endsIn')} <span className="text-warning">{t('competitive.pass.days', { count: daysLeft })}</span>
              </p>
            )}
            <p className="mt-3 text-[20px] font-black uppercase leading-tight text-white">
              {t(`competitive.seasons.kinds.${pass.featured.kind}`, { rank: pass.featured.name })}
            </p>
            {locked && (
              <p className="mt-1.5 flex items-center gap-1.5 text-[13px] font-semibold text-white/70">
                <LockIcon className="h-4 w-4 shrink-0 text-warning" />
                {t('competitive.pass.requires')}
              </p>
            )}
          </div>
          <div className="shrink-0" style={{ filter: `drop-shadow(0 0 18px rgb(${PASS_COLOR} / 0.45))` }}>
            <RewardImage reward={pass.rewards[pass.featured.tier - 1] ?? pass.featured} color={PASS_COLOR} tierIndex={3} size={84} />
          </div>
        </div>
        <div className="flex flex-col gap-2">
          <div className="flex items-baseline justify-between gap-2 text-[13px] font-bold">
            <span className="text-white">{t('competitive.pass.tier', { tier: pass.tier, total: pass.tiers })}</span>
            <span className="text-success">{t('competitive.pass.xpToGo', { xp: (pass.xpPerTier - pass.xpInTier).toLocaleString() })}</span>
          </div>
          <Bar ratio={pass.xpInTier / pass.xpPerTier} />
        </div>
        <span className="flex h-11 items-center justify-between border-t border-white/[0.08] pt-1 text-[15px] font-semibold text-[rgb(var(--accent-text))]">
          {t('competitive.pass.seeTiers')}
          <RowChevron />
        </span>
      </button>
      <div className="px-5 pb-5">
        {owned ? (
          <span className="flex h-12 items-center justify-center rounded-2xl border border-success/40 bg-success/[0.1] text-[15px] font-bold uppercase tracking-[0.04em] text-success">
            {t('competitive.pass.owned')}
          </span>
        ) : (
          <button onClick={() => navigate('/plans')} className="press h-12 w-full rounded-2xl bg-white text-[15px] font-black uppercase tracking-[0.04em] text-black">
            {t('competitive.pass.unlock')}
          </button>
        )}
      </div>
    </div>
  )
}

/** Les missions du jour (avec leurs paliers et le bonus) et de la semaine. Partagé par les deux écrans. */
function MissionLists({ challenges, pass, meta, onReroll }: {
  challenges: Challenge[] | null
  pass: BattlePass | null
  meta: MissionsMeta | null
  onReroll: (id: string) => Promise<void>
}) {
  const t = useT()
  const now = useNow()
  if (challenges === null) return <Note>{t('competitive.challenges.soon')}</Note>
  const daily = challenges.filter((c) => c.period === 'daily').sort((a, b) => Number(!!b.carried) - Number(!!a.carried))
  const weekly = challenges.filter((c) => c.period === 'weekly')
  const today = daily.filter((c) => !c.carried)
  const canReroll = !!meta && meta.rerollsLeft > 0

  return (
    <>
      <Section title={t('competitive.challenges.daily')} aside={daily.length > 0 && <Clock time={countdown(nextUtcMidnight(now) - now)} />}>
        {pass && (
          <div className={`flex flex-col gap-2 px-4 py-3.5 ${CARD}`}>
            <Checkpoints done={pass.checkpointsDone} total={pass.checkpoints} />
            <p className="text-center text-[13px] text-white/55">{t('competitive.missions.perCheckpoint', { xp: pass.checkpointXp.toLocaleString() })}</p>
          </div>
        )}
        {meta && meta.dayBonusXp > 0 && today.length > 0 && (
          <p className={`px-1 text-[13px] font-bold uppercase tracking-[0.04em] ${meta.dayBonusDone ? 'text-success' : 'text-white/55'}`}>
            {meta.dayBonusDone
              ? t('competitive.missions.dayBonusDone', { xp: meta.dayBonusXp.toLocaleString() })
              : t('competitive.missions.dayBonus', { xp: meta.dayBonusXp.toLocaleString() })}
          </p>
        )}
        <div className={LIST}>
          {daily.map((c) => <MissionRow key={c.id} mission={c} now={now} onReroll={c.rerollable && canReroll ? onReroll : undefined} />)}
        </div>
      </Section>

      <Section title={t('competitive.challenges.weekly')} aside={weekly[0] && <Clock time={countdown(weekly[0].endsAt - now)} />}>
        {weekly.every((c) => c.progress >= c.goal) ? (
          <Note>{t('competitive.missions.allDone')}</Note>
        ) : (
          <div className={LIST}>
            {weekly.map((c) => <MissionRow key={c.id} mission={c} now={now} />)}
          </div>
        )}
      </Section>
    </>
  )
}

function Checkpoints({ done, total }: { done: number; total: number }) {
  return (
    <div className="flex items-center">
      {Array.from({ length: total }, (_, i) => {
        const filled = i < done
        return (
          <div key={i} className="flex flex-1 items-center last:flex-none">
            <div className="relative flex h-10 w-10 shrink-0 items-center justify-center">
              <span className={`absolute inset-[6px] rotate-45 rounded-[5px] border-2 ${filled ? 'border-accent bg-accent/30 shadow-[0_0_14px_rgb(var(--accent)/0.5)]' : 'border-white/15 bg-white/[0.02]'}`} />
              <span className={`relative text-[15px] font-black ${filled ? 'text-white' : 'text-white/40'}`}>{i + 1}</span>
            </div>
            {i < total - 1 && <span className={`mx-1 h-0.5 flex-1 rounded-full ${i < done - 1 ? 'bg-accent/70' : 'bg-white/[0.08]'}`} />}
          </div>
        )
      })}
    </div>
  )
}

/** Une mission : l'anneau (ou la coche), la phrase sur deux lignes au besoin, la difficulté, l'XP ; la relance si elle est possible. */
function MissionRow({ mission: c, now, onReroll }: { mission: Challenge; now: number; onReroll?: (id: string) => Promise<void> }) {
  const t = useT()
  const reduce = useReducedMotion()
  const [busy, setBusy] = useState(false)
  const done = c.progress >= c.goal
  const ratio = Math.min(1, c.progress / c.goal)
  const R = 11
  const C = 2 * Math.PI * R
  const level = DIFFICULTY[c.difficulty]
  const daysLeft = Math.max(0, Math.ceil((c.endsAt - now) / 86_400_000))

  return (
    <div className="flex min-h-[68px] items-center gap-3 py-3 pl-4 pr-2">
      {done ? (
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-success/20 text-success">
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor"><path d="M9 16.2l-3.5-3.5L4 14.2 9 19.2 20 8.2l-1.5-1.5z" /></svg>
        </span>
      ) : (
        <svg viewBox="0 0 28 28" className="h-7 w-7 shrink-0 -rotate-90">
          <circle cx="14" cy="14" r={R} fill="none" stroke="rgb(255 255 255 / 0.1)" strokeWidth="3" />
          <motion.circle
            cx="14" cy="14" r={R} fill="none" stroke="rgb(var(--accent))" strokeWidth="3" strokeLinecap="round" strokeDasharray={C}
            initial={reduce ? false : { strokeDashoffset: C }}
            animate={{ strokeDashoffset: C * (1 - ratio) }}
            transition={{ duration: 0.6, ease }}
          />
        </svg>
      )}
      <div className="min-w-0 flex-1">
        <p className={`text-[15px] font-semibold leading-snug ${done ? 'text-white/45 line-through decoration-white/25' : 'text-white'}`}>{missionText(t, c)}</p>
        <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 text-[13px] tabular-nums text-white/55">
          <span aria-label={t(`competitive.missions.difficulty.${c.difficulty}`)} className="flex items-center gap-[2px]">
            {Array.from({ length: 3 }, (_, i) => (
              <span key={i} className="h-2 w-[3px] rounded-full" style={{ background: i < level.pips ? `rgb(${level.color})` : 'rgb(255 255 255 / 0.12)' }} />
            ))}
          </span>
          <span>{c.progress} / {c.goal}</span>
          {c.mode && <span>· {t(`competitive.modes.${c.mode}`)}</span>}
          {c.carried && !done && <span className="font-semibold text-warning">· {t('competitive.missions.carried', { count: daysLeft })}</span>}
        </p>
      </div>
      <span className={`shrink-0 text-[14px] font-bold tabular-nums ${done ? 'text-success' : 'text-accent-hover'}`}>{t('competitive.missions.xp', { xp: c.xp.toLocaleString() })}</span>
      {onReroll && !done ? (
        <button
          disabled={busy}
          onClick={async () => { setBusy(true); try { await onReroll(c.id) } finally { setBusy(false) } }}
          aria-label={t('competitive.missions.reroll')}
          className="press flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-white/60 disabled:opacity-40"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className={`h-5 w-5 ${busy ? 'animate-spin' : ''}`}><path d="M21 12a9 9 0 11-2.64-6.36M21 4v5h-5" /></svg>
        </button>
      ) : (
        <span className="w-2 shrink-0" />
      )}
    </div>
  )
}

function ObjectiveRow({ objective: o, pass }: { objective: PassObjective; pass: BattlePass }) {
  const t = useT()
  const now = useNow()
  const color = o.kind === 'rank' ? TIER_COLORS[o.tier] : PASS_COLOR
  const title =
    o.kind === 'unlock' ? t(`competitive.seasons.kinds.${o.reward}`, { rank: o.name })
      : o.kind === 'tiers' ? t('competitive.pass.title')
        : t('competitive.pass.reachRank', { rank: t(`competitive.tiers.${o.tier}`) })
  const sub =
    o.kind === 'unlock' ? t('competitive.pass.xpToGo', { xp: o.xpLeft.toLocaleString() })
      : o.kind === 'tiers' ? t('competitive.pass.tier', { tier: o.done, total: o.total })
        : t(`competitive.seasons.kinds.${o.reward}`, { rank: t(`competitive.tiers.${o.tier}`) })
  return (
    <div className="flex items-center gap-3.5 px-4 py-3.5">
      <div className="shrink-0" style={{ filter: `drop-shadow(0 0 12px rgb(${color} / 0.4))` }}>
        {o.kind === 'rank' ? (
          <RankTile division={TIER_KEYS.indexOf(o.tier) * DIVISIONS_PER_TIER} placed size={48} />
        ) : (
          <RewardImage
            reward={(o.kind === 'unlock' ? pass.rewards.find((r) => r.kind === o.reward && r.name === o.name) : pass.rewards[pass.featured.tier - 1]) ?? { kind: o.kind === 'unlock' ? o.reward : pass.featured.kind, name: '' }}
            color={color}
            tierIndex={3}
            size={48}
          />
        )}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="truncate text-[15px] font-bold uppercase text-white">{title}</p>
        <p className="truncate text-[13px] font-semibold" style={{ color: `rgb(${color})` }}>{sub}</p>
        <Bar ratio={o.kind === 'tiers' ? o.done / o.total : o.ratio} color={color} className="h-1.5" />
        {o.kind === 'unlock' && (
          <p className="text-[13px] font-semibold text-danger">
            {t('competitive.pass.endsIn')} {t('competitive.pass.days', { count: Math.max(0, Math.ceil((o.endsAt - now) / 86_400_000)) })}
          </p>
        )}
      </div>
    </div>
  )
}

// ── Passe de saison ──────────────────────────────────────────────────────

const CHAPTER_SIZE = 5
type TierState = 'obtained' | 'waiting' | 'current' | 'upcoming'
const toneOf = (r: PassReward | undefined) => (r?.free ? FREE_COLOR : PASS_COLOR)

export function MobileSeasonPass() {
  const t = useT()
  const { pass, season } = usePass()
  return (
    <div className="flex h-full flex-col overflow-hidden bg-[rgb(var(--bg-primary))] text-txt-primary">
      <ScreenHeader
        title={t('competitive.pass.tiersTitle')}
        subtitle={season ? t('competitive.pass.season', { number: season.number, name: season.name }) : undefined}
        fallback="/missions"
      />
      {pass === null ? (
        <div className="px-4 pt-6"><Note>{t('competitive.pass.soon')}</Note></div>
      ) : (
        <PassBoard pass={pass} />
      )}
    </div>
  )
}

function PassBoard({ pass }: { pass: BattlePass }) {
  const t = useT()
  const navigate = useNavigate()
  const reduce = useReducedMotion()
  const owned = useStore((s) => s.yuyuPlan) !== 'free'
  const chapters = Math.ceil(pass.tiers / CHAPTER_SIZE)
  const nextTier = Math.min(pass.tier + 1, pass.tiers)
  const [selected, setSelected] = useState(nextTier)
  const [chapter, setChapter] = useState(Math.floor((nextTier - 1) / CHAPTER_SIZE))
  // Sens du dernier changement de chapitre : les paliers arrivent du côté où l'on va.
  const [dir, setDir] = useState(0)
  const swipe = useRef<{ x: number; y: number } | null>(null)

  const stateOf = (tier: number): TierState =>
    tier <= pass.tier ? (owned || pass.rewards[tier - 1]?.free ? 'obtained' : 'waiting') : tier === pass.tier + 1 ? 'current' : 'upcoming'

  const goChapter = (next: number) => {
    const c = Math.max(0, Math.min(chapters - 1, next))
    if (c === chapter) return
    setDir(c > chapter ? 1 : -1)
    setChapter(c)
    const first = c * CHAPTER_SIZE + 1
    const last = Math.min(pass.tiers, first + CHAPTER_SIZE - 1)
    setSelected(nextTier >= first && nextTier <= last ? nextTier : first)
  }

  const reward = pass.rewards[selected - 1] ?? pass.rewards[0]
  const state = stateOf(selected)
  const tone = toneOf(reward)
  const first = chapter * CHAPTER_SIZE + 1
  const tiers = Array.from({ length: Math.min(CHAPTER_SIZE, pass.tiers - first + 1) }, (_, i) => first + i)
  const paid = pass.rewards.filter((r) => !r.free)
  const waiting = pass.rewards.filter((r, i) => !r.free && i < pass.tier).length
  const freeTotal = pass.rewards.length - paid.length
  const freeDone = pass.rewards.filter((r, i) => r.free && i < pass.tier).length

  return (
    <div className="relative isolate min-h-0 flex-1 overflow-y-auto overscroll-contain">
      <div aria-hidden className="pointer-events-none absolute inset-x-0 top-0 -z-10 h-[420px]">
        <TopoBackdrop color={PASS_COLOR} still />
        <div className="absolute inset-0" style={{ background: `radial-gradient(22rem 16rem at 50% 40%, rgb(${tone} / 0.22), transparent 70%)` }} />
      </div>

      <div className="flex flex-col gap-8 px-4 pb-[max(32px,env(safe-area-inset-bottom))] pt-6">
        {/* La récompense choisie : son image, gratuit ou du passe, son nom et où l'on en est. */}
        <div className="flex flex-col items-center gap-3 text-center">
          <AnimatePresence mode="popLayout" initial={false}>
            <motion.div
              key={selected}
              initial={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.92 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, transition: { duration: 0.12 } }}
              transition={{ duration: 0.22, ease }}
              style={{ filter: `drop-shadow(0 0 32px rgb(${tone} / 0.45))` }}
            >
              <RewardImage reward={reward} color={tone} tierIndex={3} size={150} />
            </motion.div>
          </AnimatePresence>
          <span className="flex items-center gap-1 rounded-full px-3 py-1 text-[13px] font-black uppercase tracking-[0.06em]" style={{ background: `rgb(${tone} / 0.16)`, color: `rgb(${tone})` }}>
            {!reward.free && !owned && <LockIcon className="h-3.5 w-3.5" />}
            {reward.free ? t('competitive.pass.freeTier') : t('competitive.pass.passTier')}
          </span>
          <p className="max-w-full text-[20px] font-black uppercase leading-tight tracking-[0.01em] text-white">
            {t(`competitive.seasons.kinds.${reward.kind}`, { rank: reward.name })}
          </p>
          <p
            className={`text-[15px] font-semibold ${state === 'obtained' ? 'text-success' : state === 'waiting' ? '' : 'text-white/65'}`}
            style={state === 'waiting' ? { color: `rgb(${PASS_COLOR})` } : undefined}
          >
            {state === 'obtained' ? t('competitive.pass.obtained')
              : state === 'waiting' ? t('competitive.pass.waiting')
                : state === 'current' ? t('competitive.pass.xpProgress', { xp: pass.xpInTier.toLocaleString(), total: pass.xpPerTier.toLocaleString() })
                  : t('competitive.pass.tierLabel', { tier: selected })}
          </p>
        </div>

        {/* Les paliers du chapitre, puis les chapitres. Glisser le doigt de côté sur les paliers change de chapitre. */}
        <div className="flex flex-col gap-3">
          <div
            className="touch-pan-y overflow-hidden"
            onPointerDown={(e) => { swipe.current = { x: e.clientX, y: e.clientY } }}
            onPointerUp={(e) => {
              const s = swipe.current
              swipe.current = null
              if (!s) return
              const dx = e.clientX - s.x
              if (Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(e.clientY - s.y)) goChapter(chapter + (dx < 0 ? 1 : -1))
            }}
            onPointerCancel={() => { swipe.current = null }}
          >
            <AnimatePresence mode="popLayout" initial={false} custom={dir}>
              <motion.div
                key={chapter}
                custom={dir}
                initial={reduce ? { opacity: 0 } : { opacity: 0, transform: `translateX(${dir * 24}px)` }}
                animate={{ opacity: 1, transform: 'translateX(0px)' }}
                exit={reduce ? { opacity: 0 } : { opacity: 0, transform: `translateX(${dir * -24}px)`, transition: { duration: 0.12 } }}
                transition={{ duration: 0.22, ease }}
                className="grid grid-cols-5 gap-2"
              >
                {tiers.map((tier) => (
                  <TierTile key={tier} tier={tier} reward={pass.rewards[tier - 1]} state={stateOf(tier)} owned={owned} selected={tier === selected} ratio={pass.xpInTier / pass.xpPerTier} onPick={() => setSelected(tier)} />
                ))}
              </motion.div>
            </AnimatePresence>
          </div>
          <div className="flex items-center gap-2">
            <ChapterArrow dir="left" disabled={chapter === 0} label={t('competitive.pass.prevChapter')} onClick={() => goChapter(chapter - 1)} />
            <div className="flex min-w-0 flex-1 flex-col items-center gap-1.5">
              <span className="text-[15px] font-bold text-white">
                {t('competitive.pass.chapter', { number: chapter + 1 })}
                <span className="font-semibold text-white/45"> / {chapters}</span>
              </span>
              {/* Un repère par chapitre : ceux qui sont faits pleins, l'affiché plus large. */}
              <div className="flex items-center gap-1">
                {Array.from({ length: chapters }, (_, c) => (
                  <span
                    key={c}
                    className={`h-1.5 rounded-full transition-[width,background-color] duration-200 ${c === chapter ? 'w-4 bg-white' : c < Math.floor(pass.tier / CHAPTER_SIZE) ? 'w-1.5 bg-white/60' : 'w-1.5 bg-white/20'}`}
                  />
                ))}
              </div>
            </div>
            <ChapterArrow dir="right" disabled={chapter === chapters - 1} label={t('competitive.pass.nextChapter')} onClick={() => goChapter(chapter + 1)} />
          </div>
        </div>

        {/* Où l'on en est, et de quoi débloquer le passe. */}
        <div className={`flex flex-col gap-4 px-4 py-4 ${CARD}`}>
          <div className="flex items-baseline justify-between">
            <span className="text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('competitive.pass.progress')}</span>
            <span className="text-[17px] font-black tabular-nums text-white">{pass.tier}<span className="text-[13px] font-bold text-white/45"> / {pass.tiers}</span></span>
          </div>
          <div className="flex flex-col gap-2">
            <Bar ratio={pass.xpInTier / pass.xpPerTier} />
            <div className="flex flex-wrap justify-between gap-x-3 gap-y-1 text-[13px] font-semibold">
              <span className="tabular-nums text-white/55">{t('competitive.pass.xpProgress', { xp: pass.xpInTier.toLocaleString(), total: pass.xpPerTier.toLocaleString() })}</span>
              {pass.tier < pass.tiers && <span className="text-success">{t('competitive.pass.xpToTier', { xp: (pass.xpPerTier - pass.xpInTier).toLocaleString(), tier: pass.tier + 1 })}</span>}
            </div>
          </div>
          {owned ? (
            <span className="flex h-12 items-center justify-center rounded-2xl border border-success/40 bg-success/[0.1] text-[15px] font-bold uppercase tracking-[0.04em] text-success">{t('competitive.pass.owned')}</span>
          ) : (
            <div className="flex flex-col gap-2.5">
              {freeTotal > 0 && <span className="text-[13px] font-semibold" style={{ color: `rgb(${FREE_COLOR})` }}>{t('competitive.pass.freeTiers', { done: freeDone, total: freeTotal })}</span>}
              {paid.length > 0 && (
                <span className="flex items-center gap-2 rounded-xl px-3 py-2.5 text-[13px] font-bold" style={{ background: `rgb(${PASS_COLOR} / 0.14)`, color: `rgb(${PASS_COLOR})` }}>
                  <LockIcon className="h-4 w-4 shrink-0" />
                  {waiting > 0 ? t('competitive.pass.waitingCount', { count: waiting }) : t('competitive.pass.passAdds', { count: paid.length })}
                </span>
              )}
              {/* Une récompense attend : une lueur fixe le dit, sans respirer en boucle. */}
              <button
                onClick={() => navigate('/plans')}
                className="press h-12 rounded-2xl bg-white text-[15px] font-black uppercase tracking-[0.04em] text-black"
                style={waiting > 0 ? { boxShadow: `0 0 22px rgb(${PASS_COLOR} / 0.55)` } : undefined}
              >
                {t('competitive.pass.unlock')}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function TierTile({ tier, reward, state, owned, selected, ratio, onPick }: {
  tier: number
  reward: PassReward
  state: TierState
  owned: boolean
  selected: boolean
  ratio: number
  onPick: () => void
}) {
  const t = useT()
  const tone = toneOf(reward)
  const locked = !reward.free && !owned
  return (
    <button
      onClick={onPick}
      aria-pressed={selected}
      aria-label={`${t('competitive.pass.tierLabel', { tier })} · ${reward.free ? t('competitive.pass.freeTier') : t('competitive.pass.passTier')}`}
      className={`press relative flex min-w-0 flex-col overflow-hidden rounded-xl border-2 bg-[rgb(var(--bg-card))] ${selected ? 'border-white' : ''}`}
      style={{
        borderColor: selected ? undefined : `rgb(${tone} / ${state === 'current' || state === 'waiting' ? 0.8 : reward.free ? 0.5 : 0.25})`,
        backgroundImage: `linear-gradient(180deg, rgb(${tone} / 0.14), transparent 70%)`,
        boxShadow: state === 'waiting' ? `0 0 14px rgb(${PASS_COLOR} / 0.45)` : undefined,
      }}
    >
      {/* Gratuit ou du passe : une bande de couleur en tête (et un cadenas), le libellé est dans l'aperçu. */}
      <span className="flex h-5 items-center justify-center" style={{ background: `rgb(${tone} / 0.22)`, color: `rgb(${tone})` }}>
        {locked && <LockIcon className="h-3 w-3" />}
      </span>
      <div className={`flex aspect-square items-center justify-center ${state === 'upcoming' && !selected ? 'opacity-55' : ''}`}>
        <RewardImage reward={reward} color={tone} tierIndex={3} size={40} />
      </div>
      {state === 'obtained' && (
        <span className="absolute right-1 top-6 flex h-4 w-4 items-center justify-center rounded-full bg-success text-black">
          <svg viewBox="0 0 24 24" className="h-2.5 w-2.5" fill="none" stroke="currentColor" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
        </span>
      )}
      <span className={`relative py-1 text-center text-[13px] font-black tabular-nums ${selected ? 'bg-white text-black' : 'bg-white/[0.06] text-white/75'}`}>
        {state === 'current' && !selected && <span className="absolute inset-y-0 left-0 bg-accent/35" style={{ width: `${ratio * 100}%` }} />}
        <span className="relative">{tier}</span>
      </span>
    </button>
  )
}

function ChapterArrow({ dir, disabled, label, onClick }: { dir: 'left' | 'right'; disabled: boolean; label: string; onClick: () => void }) {
  return (
    <button onClick={onClick} disabled={disabled} aria-label={label} className="press flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-white/[0.1] bg-white/[0.04] text-white disabled:opacity-30">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5"><path d={dir === 'left' ? 'M15 6l-6 6 6 6' : 'M9 6l6 6-6 6'} /></svg>
    </button>
  )
}
