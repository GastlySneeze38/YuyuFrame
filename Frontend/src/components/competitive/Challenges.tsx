import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'
import { DIVISIONS_PER_TIER, TIER_COLORS, TIER_KEYS, type BattlePass, type Challenge, type PassObjective, type Season } from '@/lib/competitive'
import { CosmeticIcon } from './RewardsView'
import { RankTile } from './RankCard'
import { TopoBackdrop } from './TopoBackdrop'

/** Couleur du passe de saison : l'accent du launcher. */
const PASS_COLOR = '129 120 255'

/** L'heure, rafraîchie chaque seconde : pour les comptes à rebours. */
function useNow(): number {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  return now
}

/** « 15:34:29 » sous un jour, « 3:16:49:05 » (jours en tête) au-delà. */
function countdown(ms: number): string {
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
}: {
  challenges: Challenge[] | null
  pass: BattlePass | null
  season: Season | null
}) {
  return (
    <div className="grid h-full grid-cols-[360px_minmax(0,1fr)] gap-4">
      <Missions challenges={challenges} pass={pass} />
      <PassColumn pass={pass} season={season} />
    </div>
  )
}

// ── Missions ─────────────────────────────────────────────────────────────

function Missions({ challenges, pass }: { challenges: Challenge[] | null; pass: BattlePass | null }) {
  const t = useT()
  const now = useNow()
  if (challenges === null) {
    return (
      <div className="flex items-center justify-center rounded-2xl border border-line bg-surface-1 p-5">
        <p className="text-center text-[12.5px] text-txt-muted">{t('competitive.challenges.soon')}</p>
      </div>
    )
  }
  const daily = challenges.filter((c) => c.period === 'daily')
  const weekly = challenges.filter((c) => c.period === 'weekly')

  return (
    <div className="flex min-h-0 flex-col overflow-hidden rounded-2xl border border-line bg-surface-1">
      {/* Missions du jour */}
      <SectionHead title={t('competitive.challenges.daily')} refill={daily[0] ? countdown(daily[0].endsAt - now) : null} />
      <div className="flex flex-col gap-4 px-5 pb-5 pt-3">
        {pass && (
          <>
            <p className="text-[11px] text-txt-muted">{t('competitive.missions.perCheckpoint', { xp: pass.checkpointXp.toLocaleString() })}</p>
            <Checkpoints done={pass.checkpointsDone} total={pass.checkpoints} />
          </>
        )}
        <div className="flex flex-col gap-2">
          {daily.map((c, i) => (
            <MissionRow key={c.id} mission={c} index={i} />
          ))}
        </div>
      </div>

      {/* Missions de la semaine */}
      <SectionHead title={t('competitive.challenges.weekly')} refill={weekly[0] ? countdown(weekly[0].endsAt - now) : null} />
      <div className="-mx-0 flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto px-5 pb-5 pt-3">
        {weekly.every((c) => c.progress >= c.goal) ? (
          <p className="py-6 text-center text-[11.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('competitive.missions.allDone')}</p>
        ) : (
          weekly.map((c, i) => <MissionRow key={c.id} mission={c} index={i} />)
        )}
      </div>
    </div>
  )
}

function SectionHead({ title, refill }: { title: string; refill: string | null }) {
  const t = useT()
  return (
    <div className="flex shrink-0 items-baseline justify-between gap-3 border-y border-white/[0.05] bg-white/[0.03] px-5 py-3 first:border-t-0">
      <span className="text-[12.5px] font-black uppercase tracking-[0.04em]">{title}</span>
      {refill && <span className="text-[11px] tabular-nums text-txt-muted">{t('competitive.missions.refills', { time: refill })}</span>}
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
              className="relative flex h-11 w-11 shrink-0 items-center justify-center"
            >
              <span
                className={`absolute inset-[6px] rotate-45 rounded-[4px] border-2 ${
                  filled ? 'border-accent bg-accent/30 shadow-[0_0_16px_rgb(var(--accent)/0.5)]' : 'border-white/15 bg-white/[0.02]'
                }`}
              />
              <span className={`relative text-[15px] font-black ${filled ? 'text-white' : 'text-white/35'}`}>{i + 1}</span>
            </motion.div>
            {i < total - 1 && <span className={`mx-1 h-0.5 flex-1 rounded-full ${i < done - 1 ? 'bg-accent/70' : 'bg-white/[0.08]'}`} />}
          </div>
        )
      })}
    </div>
  )
}

function MissionRow({ mission: c, index }: { mission: Challenge; index: number }) {
  const t = useT()
  const done = c.progress >= c.goal
  return (
    <motion.div
      initial={{ opacity: 0, x: -6 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ delay: index * 0.04 }}
      className={`flex shrink-0 flex-col gap-2 rounded-xl border px-3.5 py-2.5 ${done ? 'border-success/30 bg-success/[0.07]' : 'border-white/[0.06] bg-white/[0.02]'}`}
    >
      <div className="flex items-center gap-2">
        <span className={`min-w-0 flex-1 truncate text-[12.5px] font-bold ${done ? 'text-success' : ''}`}>
          {t(`competitive.challenges.kinds.${c.kind}`, { count: c.target, score: c.target })}
        </span>
        {c.mode && <span className="shrink-0 rounded bg-white/[0.06] px-1.5 py-0.5 text-[9.5px] font-bold text-txt-secondary">{t(`competitive.modes.${c.mode}`)}</span>}
        <span className="shrink-0 text-[10.5px] font-bold text-accent-hover">{t('competitive.missions.xp', { xp: c.xp.toLocaleString() })}</span>
      </div>
      <div className="flex items-center gap-2.5">
        <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-white/[0.06]">
          <motion.div
            className={`h-full rounded-full ${done ? 'bg-success' : 'bg-accent'}`}
            initial={{ width: 0 }}
            animate={{ width: `${Math.min(1, c.progress / c.goal) * 100}%` }}
            transition={{ duration: 0.7, ease: [0.16, 1, 0.3, 1], delay: 0.1 + index * 0.04 }}
          />
        </div>
        <span className="w-9 shrink-0 text-right text-[10.5px] font-bold tabular-nums text-txt-muted">
          {c.progress}/{c.goal}
        </span>
      </div>
    </motion.div>
  )
}

// ── Passe de saison ──────────────────────────────────────────────────────

function PassColumn({ pass, season }: { pass: BattlePass | null; season: Season | null }) {
  const t = useT()
  if (pass === null) {
    return (
      <div className="flex items-center justify-center rounded-2xl border border-line bg-surface-1 p-5">
        <p className="text-center text-[12.5px] text-txt-muted">{t('competitive.pass.soon')}</p>
      </div>
    )
  }
  return (
    <div className="grid min-h-0 grid-rows-[minmax(0,1fr)_auto] gap-4">
      <PassHero pass={pass} season={season} />
      <div className="flex flex-col gap-2">
        <span className="text-[12.5px] font-black uppercase tracking-[0.04em]">{t('competitive.pass.objectives')}</span>
        <div className="grid grid-cols-3 gap-3">
          {pass.objectives.map((o, i) => (
            <Objective key={i} objective={o} pass={pass} index={i} />
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
  // Le passe entier est réservé aux plans payants.
  const locked = !owned
  const daysLeft = season?.end ? Math.max(0, Math.ceil((season.end - Date.now()) / 86_400_000)) : null
  const xpLeft = pass.xpPerTier - pass.xpInTier

  return (
    <div className="relative flex min-h-0 overflow-hidden rounded-2xl border border-line bg-surface-1" style={{ isolation: 'isolate' }}>
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        <TopoBackdrop color={PASS_COLOR} />
        <div className="absolute inset-0" style={{ background: `radial-gradient(28rem 18rem at 72% 45%, rgb(${PASS_COLOR} / 0.22), transparent 70%)` }} />
      </div>

      {/* À gauche : la saison, la récompense suivante, la progression. */}
      <div className="flex min-w-0 flex-1 flex-col justify-between p-6">
        <div>
          <p className="text-[15px] font-black uppercase tracking-[0.03em]">
            {season ? t('competitive.pass.season', { number: season.number, name: season.name }) : t('competitive.pass.title')}
          </p>
          {daysLeft !== null && (
            <p className="mt-0.5 text-[13px] font-bold uppercase">
              {t('competitive.pass.endsIn')} <span className="text-warning">{t('competitive.pass.days', { count: daysLeft })}</span>
            </p>
          )}
        </div>

        <div className="flex flex-col gap-3">
          {locked && (
            <span className="flex items-center gap-1.5 text-[12px] font-semibold text-txt-secondary">
              <svg viewBox="0 0 24 24" className="h-4 w-4 text-warning" fill="currentColor"><path d="M12 2a5 5 0 00-5 5v3H6a2 2 0 00-2 2v8a2 2 0 002 2h12a2 2 0 002-2v-8a2 2 0 00-2-2h-1V7a5 5 0 00-5-5zm-3 8V7a3 3 0 016 0v3z" /></svg>
              {t('competitive.pass.requires')}
            </span>
          )}
          <p className="text-[26px] font-black uppercase leading-none tracking-[-0.01em]">
            {t(`competitive.seasons.kinds.${pass.featured.kind}`, { rank: pass.featured.name })}
          </p>
          <div className="flex max-w-[440px] flex-col gap-1.5">
            <div className="flex items-baseline justify-between text-[11.5px] font-bold">
              <span>{t('competitive.pass.tier', { tier: pass.tier, total: pass.tiers })}</span>
              <span className="text-success">{t('competitive.pass.xpToGo', { xp: xpLeft.toLocaleString() })}</span>
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
      <div className="flex w-[300px] shrink-0 flex-col items-center justify-center gap-6 p-6">
        <motion.div
          initial={{ scale: 0.8, opacity: 0, rotate: -6 }}
          animate={{ scale: 1, opacity: 1, rotate: 0, y: [0, -6, 0] }}
          transition={{ scale: { type: 'spring', stiffness: 220, damping: 18 }, y: { duration: 4, repeat: Infinity, ease: 'easeInOut' } }}
        >
          <CosmeticIcon kind={pass.featured.kind} color={PASS_COLOR} tierIndex={3} size={150} />
        </motion.div>
        {owned ? (
          <span className="rounded-xl border border-success/40 bg-success/[0.1] px-5 py-2.5 text-[12px] font-black uppercase tracking-[0.05em] text-success">
            {t('competitive.pass.owned')}
          </span>
        ) : (
          <button
            onClick={() => navigate('/plans')}
            className="rounded-xl bg-white px-6 py-2.5 text-[12px] font-black uppercase tracking-[0.05em] text-black transition-transform hover:scale-[1.03] active:scale-[0.98]"
          >
            {t('competitive.pass.unlock')}
          </button>
        )}
      </div>
    </div>
  )
}

function Objective({ objective: o, pass, index }: { objective: PassObjective; pass: BattlePass; index: number }) {
  const t = useT()
  const now = useNow()
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
      className="relative flex flex-col overflow-hidden rounded-2xl border border-line bg-surface-1"
      style={{ backgroundImage: `radial-gradient(14rem 8rem at 85% 50%, rgb(${color} / 0.16), transparent 70%)` }}
    >
      <div className="flex items-center gap-3 p-4">
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13.5px] font-black uppercase">{title}</p>
          <p className="mt-1 truncate text-[11px] font-semibold" style={{ color: `rgb(${color})` }}>
            {sub}
          </p>
          {o.kind === 'tiers' ? (
            // Les paliers du passe, en segments.
            <div className="mt-2.5 flex gap-1">
              {Array.from({ length: 5 }, (_, i) => (
                <span key={i} className={`h-1.5 flex-1 rounded-full ${i < Math.round((o.done / o.total) * 5) ? 'bg-white/80' : 'bg-white/[0.1]'}`} />
              ))}
            </div>
          ) : (
            <div className="mt-2.5 h-1.5 overflow-hidden rounded-full bg-white/[0.08]">
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
        {o.kind === 'rank' ? (
          <RankTile division={TIER_KEYS.indexOf(o.tier) * DIVISIONS_PER_TIER} placed size={52} />
        ) : (
          <CosmeticIcon kind={o.kind === 'unlock' ? o.reward : pass.featured.kind} color={color} tierIndex={3} size={56} />
        )}
      </div>
      {o.kind === 'unlock' && (
        <div className="flex items-center gap-1.5 bg-danger/[0.85] px-4 py-1 text-[10.5px] font-black uppercase tracking-[0.04em] text-white">
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="currentColor"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm1 11h-5v-2h3V6h2z" /></svg>
          {t('competitive.pass.endsIn')} {t('competitive.pass.days', { count: Math.max(0, Math.ceil((o.endsAt - now) / 86_400_000)) })}
        </div>
      )}
    </motion.div>
  )
}
