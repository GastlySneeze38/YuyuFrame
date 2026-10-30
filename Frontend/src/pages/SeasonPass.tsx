import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { CosmeticIcon } from '@/components/competitive/RewardsView'
import { TopoBackdrop } from '@/components/competitive/TopoBackdrop'
import { Missions, PASS_COLOR } from '@/components/competitive/Challenges'
import { demoData, type BattlePass, type Challenge, type PassReward } from '@/lib/competitive'
import { useStore } from '@/stores/useStore'
import { useDemo } from '@/stores/useDevFlags'
import { useT } from '@/i18n'

/** Paliers par chapitre : une rangée à l'écran. */
const CHAPTER_SIZE = 5
/** Carte de progression et rangée du bas (`w-[240px]`, `gap-6`, `px-8`). */
const CARD_WIDTH = 240
const ROW_GAP = 24
const ROW_PADDING = 64

type TierState = 'obtained' | 'current' | 'upcoming'

/**
 * Le passe de saison en page entière, ouvert depuis sa carte des Missions.
 *
 * La récompense choisie en grand au centre, les paliers par chapitres de cinq
 * en dessous, et une frise de chapitres pour aller de l'un à l'autre. Sans le
 * passe (plan gratuit), tout reste visible, cadenassé : on voit ce qu'il
 * donnerait.
 */
export default function SeasonPass() {
  const t = useT()
  const username = useStore((s) => s.username)
  // Données de démonstration : interrupteur de l'atelier de développement.
  const demoOn = useDemo()
  const demo = useMemo(() => (demoOn ? demoData(username ?? 'Steve') : null), [username, demoOn])
  const pass = demo?.battlePass ?? null
  const season = demo?.seasons?.[0] ?? null

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageHeader backTo="/missions">
        <PageHeaderSeparator />
        <div className="min-w-0 flex-1">
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em]">{t('competitive.pass.tiersTitle')}</h1>
          <p className="mt-px text-[10px] text-txt-muted">
            {season ? t('competitive.pass.season', { number: season.number, name: season.name }) : t('competitive.pass.title')}
          </p>
        </div>
      </PageHeader>

      {pass === null ? (
        <div className="flex flex-1 items-center justify-center">
          <p className="text-[12.5px] text-txt-muted">{t('competitive.pass.soon')}</p>
        </div>
      ) : (
        <PassBoard pass={pass} challenges={demo?.challenges ?? null} />
      )}
    </div>
  )
}

function PassBoard({ pass, challenges }: { pass: BattlePass; challenges: Challenge[] | null }) {
  const t = useT()
  const navigate = useNavigate()
  const owned = useStore((s) => s.yuyuPlan) !== 'free'
  const chapters = Math.ceil(pass.tiers / CHAPTER_SIZE)
  // Le volet des missions : replié d'office, la page s'ouvre sur le passe.
  const [missionsOpen, setMissionsOpen] = useState(false)

  // On ouvre sur le palier en cours : c'est celui qu'on vient voir.
  const nextTier = Math.min(pass.tier + 1, pass.tiers)
  const [selected, setSelected] = useState(nextTier)
  const [chapter, setChapter] = useState(Math.floor((nextTier - 1) / CHAPTER_SIZE))

  const stateOf = (tier: number): TierState =>
    tier <= pass.tier && owned ? 'obtained' : tier === pass.tier + 1 ? 'current' : 'upcoming'

  const goChapter = (next: number) => {
    const c = Math.max(0, Math.min(chapters - 1, next))
    setChapter(c)
    // Le premier palier du chapitre devient la récompense montrée, sauf si
    // le palier en cours y est : c'est lui qu'on veut voir.
    const first = c * CHAPTER_SIZE + 1
    const last = Math.min(pass.tiers, first + CHAPTER_SIZE - 1)
    setSelected(nextTier >= first && nextTier <= last ? nextTier : first)
  }

  // Flèches du clavier : chapitre précédent / suivant.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      if (e.key === 'ArrowLeft') goChapter(chapter - 1)
      if (e.key === 'ArrowRight') goChapter(chapter + 1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chapter])

  const reward: PassReward = pass.rewards[selected - 1] ?? pass.rewards[0]
  const state = stateOf(selected)
  const firstOfChapter = chapter * CHAPTER_SIZE + 1
  const chapterTiers = Array.from(
    { length: Math.min(CHAPTER_SIZE, pass.tiers - firstOfChapter + 1) },
    (_, i) => firstOfChapter + i,
  )

  const xpLeft = pass.xpPerTier - pass.xpInTier

  // La carte va sous les paliers, à droite, tant que la rangée la loge sans
  // la tasser : les deux colonnes latérales sont égales, il faut donc la
  // place de la carte des deux côtés du bloc central (taille fixe).
  const mainRef = useRef<HTMLDivElement>(null)
  const centerRef = useRef<HTMLDivElement>(null)
  const [cardBelow, setCardBelow] = useState(true)
  useLayoutEffect(() => {
    const main = mainRef.current
    const center = centerRef.current
    if (!main || !center) return
    const check = () => {
      const needed = center.offsetWidth + 2 * (CARD_WIDTH + ROW_GAP) + ROW_PADDING
      setCardBelow(main.clientWidth >= needed)
    }
    check()
    const observer = new ResizeObserver(check)
    observer.observe(main)
    return () => observer.disconnect()
  }, [])

  const progressCard = (
    <div className="flex w-[240px] flex-col gap-3 rounded-2xl border border-line bg-surface-1/85 p-4 backdrop-blur">
      <div className="flex items-baseline justify-between">
        <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('competitive.pass.progress')}</span>
        <span className="text-[15px] font-black tabular-nums">
          {pass.tier}<span className="text-[11px] font-bold text-txt-muted"> / {pass.tiers}</span>
        </span>
      </div>
      <div className="flex flex-col gap-1.5">
        <div className="h-2 overflow-hidden rounded-full bg-white/[0.08]">
          <motion.div
            className="h-full rounded-full bg-white"
            initial={{ width: 0 }}
            animate={{ width: `${(pass.xpInTier / pass.xpPerTier) * 100}%` }}
            transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1] }}
          />
        </div>
        <div className="flex justify-between text-[10.5px] font-semibold">
          <span className="tabular-nums text-txt-muted">
            {t('competitive.pass.xpProgress', { xp: pass.xpInTier.toLocaleString(), total: pass.xpPerTier.toLocaleString() })}
          </span>
          {pass.tier < pass.tiers && (
            <span className="text-success">{t('competitive.pass.xpToTier', { xp: xpLeft.toLocaleString(), tier: pass.tier + 1 })}</span>
          )}
        </div>
      </div>
      {owned ? (
        <span className="rounded-xl border border-success/40 bg-success/[0.1] py-2.5 text-center text-[11.5px] font-black uppercase tracking-[0.05em] text-success">
          {t('competitive.pass.owned')}
        </span>
      ) : (
        <button
          onClick={() => navigate('/plans')}
          className="rounded-xl bg-white py-2.5 text-[11.5px] font-black uppercase tracking-[0.05em] text-black transition-transform hover:scale-[1.02] active:scale-[0.98]"
        >
          {t('competitive.pass.unlock')}
        </button>
      )}
    </div>
  )

  return (
    <div className="relative isolate flex min-h-0 flex-1">
      {/* Fond : la topographie du passe et un halo derrière la récompense. */}
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        <TopoBackdrop color={PASS_COLOR} />
        <div className="absolute inset-0" style={{ background: `radial-gradient(34rem 26rem at 55% 38%, rgb(${PASS_COLOR} / 0.2), transparent 70%)` }} />
      </div>

      {/* Volet des missions, à gauche : d'où vient l'XP qui fait avancer le
          passe. Replié, il ne laisse que son bouton. */}
      <motion.aside
        initial={false}
        animate={{ width: missionsOpen ? 360 : 0 }}
        transition={{ type: 'spring', stiffness: 320, damping: 34 }}
        className="relative shrink-0 overflow-hidden"
      >
        {/* Le panneau des missions remplit la hauteur du volet. */}
        <div className="flex h-full w-[360px] flex-col py-5 pl-5 [&>*]:min-h-0 [&>*]:flex-1">
          <Missions challenges={challenges} pass={pass} />
        </div>
      </motion.aside>

      <div ref={mainRef} className="relative flex min-w-0 flex-1 flex-col">
      <button
        onClick={() => setMissionsOpen((v) => !v)}
        title={missionsOpen ? t('competitive.pass.hideMissions') : t('competitive.pass.showMissions')}
        aria-expanded={missionsOpen}
        className="absolute left-4 top-5 z-10 flex h-9 items-center gap-2 rounded-lg border border-line-strong bg-surface-1/85 px-2.5 text-[11px] font-bold uppercase tracking-[0.04em] text-txt-secondary backdrop-blur transition-colors hover:border-accent/50 hover:text-white"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
          <rect x="3" y="4" width="18" height="16" rx="2.5" />
          <path d="M9 4v16" />
          <motion.path
            d="M15 9.5l-2.5 2.5 2.5 2.5"
            animate={{ rotate: missionsOpen ? 0 : 180 }}
            style={{ transformOrigin: '13.75px 12px' }}
          />
        </svg>
        {!missionsOpen && t('competitive.pass.showMissions')}
      </button>

      {/* La récompense choisie, en grand. */}
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-5 px-8 pt-6">
        <AnimatePresence mode="wait">
          <motion.div
            key={selected}
            initial={{ opacity: 0, scale: 0.85, y: 10 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.92, y: -6 }}
            transition={{ type: 'spring', stiffness: 260, damping: 22 }}
            style={{ filter: `drop-shadow(0 0 40px rgb(${PASS_COLOR} / 0.45))` }}
          >
            <motion.div animate={{ y: [0, -8, 0] }} transition={{ duration: 4, repeat: Infinity, ease: 'easeInOut' }}>
              <CosmeticIcon kind={reward.kind} color={PASS_COLOR} tierIndex={3} size={200} />
            </motion.div>
          </motion.div>
        </AnimatePresence>

        <div className="text-center">
          <p className="text-[20px] font-black uppercase tracking-[0.02em]">
            {t(`competitive.seasons.kinds.${reward.kind}`, { rank: reward.name })}
          </p>
          <p className={`mt-1 text-[12.5px] font-semibold ${state === 'obtained' ? 'text-success' : 'text-txt-secondary'}`}>
            {state === 'obtained'
              ? t('competitive.pass.obtained')
              : state === 'current'
                ? t('competitive.pass.xpProgress', { xp: pass.xpInTier.toLocaleString(), total: pass.xpPerTier.toLocaleString() })
                : t('competitive.pass.tierLabel', { tier: selected })}
          </p>
        </div>
      </div>

      {/* Carte de progression déplacée en haut à droite quand elle ne tient
          plus à côté des paliers (volet des missions ouvert, fenêtre étroite). */}
      {!cardBelow && <div className="absolute right-8 top-5 z-10">{progressCard}</div>}

      {/* Les paliers du chapitre, puis la frise des chapitres. La carte de
          progression occupe la colonne de droite, la gauche l'équilibre. */}
      <div className="grid shrink-0 grid-cols-[1fr_auto_1fr] items-center gap-6 px-8 pb-6 pt-4">
        <div />
        <div ref={centerRef} className="flex flex-col items-center gap-5">
          <div className="flex gap-3">
            {chapterTiers.map((tier, i) => (
              <TierTile
                key={tier}
                tier={tier}
                reward={pass.rewards[tier - 1]}
                state={stateOf(tier)}
                owned={owned}
                selected={tier === selected}
                ratio={pass.xpInTier / pass.xpPerTier}
                index={i}
                onPick={() => setSelected(tier)}
              />
            ))}
          </div>
          <ChapterTrack
            chapters={chapters}
            chapter={chapter}
            doneChapters={owned ? Math.floor(pass.tier / CHAPTER_SIZE) : 0}
            onPick={goChapter}
          />
        </div>
        {/* La progression et le passe, ensemble, à hauteur des paliers :
            on lit d'un coup où l'on en est et ce qu'on en tire. */}
        <div className="flex justify-end">{cardBelow && progressCard}</div>
      </div>
      </div>
    </div>
  )
}

function TierTile({ tier, reward, state, owned, selected, ratio, index, onPick }: {
  tier: number
  reward: PassReward
  state: TierState
  owned: boolean
  selected: boolean
  ratio: number
  index: number
  onPick: () => void
}) {
  const t = useT()
  return (
    <motion.button
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.04 }}
      whileHover={{ y: -3 }}
      onClick={onPick}
      title={t(`competitive.seasons.kinds.${reward.kind}`, { rank: reward.name })}
      className={`relative flex w-[104px] flex-col overflow-hidden rounded-xl border-2 bg-surface-1/80 backdrop-blur transition-colors ${
        selected ? 'border-white' : state === 'current' ? 'border-accent/60' : 'border-line hover:border-line-strong'
      }`}
    >
      <div className={`flex h-[92px] items-center justify-center ${state === 'upcoming' && !selected ? 'opacity-55' : ''}`}>
        <CosmeticIcon kind={reward.kind} color={PASS_COLOR} tierIndex={3} size={60} />
      </div>
      {state === 'obtained' && (
        <span className="absolute right-1.5 top-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-success text-black">
          <svg viewBox="0 0 24 24" className="h-2.5 w-2.5" fill="none" stroke="currentColor" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
        </span>
      )}
      {!owned && (
        <svg viewBox="0 0 24 24" className="absolute bottom-9 left-2 h-3.5 w-3.5 text-warning" fill="currentColor">
          <path d="M12 2a5 5 0 00-5 5v3H6a2 2 0 00-2 2v8a2 2 0 002 2h12a2 2 0 002-2v-8a2 2 0 00-2-2h-1V7a5 5 0 00-5-5zm-3 8V7a3 3 0 016 0v3z" />
        </svg>
      )}
      <span
        className={`relative py-1.5 text-center text-[10.5px] font-bold uppercase tracking-[0.04em] ${
          selected ? 'bg-white text-black' : 'bg-white/[0.06] text-txt-secondary'
        }`}
      >
        {/* Le palier en cours se remplit sous son libellé. */}
        {state === 'current' && !selected && (
          <span className="absolute inset-y-0 left-0 bg-accent/30" style={{ width: `${ratio * 100}%` }} />
        )}
        <span className="relative">{t('competitive.pass.tierLabel', { tier })}</span>
      </span>
    </motion.button>
  )
}

/** La frise des chapitres : flèches, un losange par chapitre, une étoile au
 *  dernier. Le chapitre affiché porte son numéro. */
function ChapterTrack({ chapters, chapter, doneChapters, onPick }: {
  chapters: number
  chapter: number
  doneChapters: number
  onPick: (chapter: number) => void
}) {
  const t = useT()
  return (
    <div className="flex items-center gap-4">
      <ArrowButton dir="left" disabled={chapter === 0} onClick={() => onPick(chapter - 1)} label={t('competitive.pass.prevChapter')} />
      <div className="flex items-center">
        {Array.from({ length: chapters }, (_, c) => {
          const active = c === chapter
          const last = c === chapters - 1
          const done = c < doneChapters
          return (
            <div key={c} className="flex items-center">
              {c > 0 && <span className={`h-px w-9 ${c <= doneChapters ? 'bg-white/60' : 'bg-white/20'}`} />}
              <button
                onClick={() => onPick(c)}
                title={t('competitive.pass.chapter', { number: c + 1 })}
                className="flex h-8 w-8 items-center justify-center"
              >
                {last ? (
                  <span className={`flex h-7 w-7 items-center justify-center rounded-full text-[12px] ${active ? 'bg-white text-black' : 'bg-white/20 text-white'}`}>★</span>
                ) : (
                  <motion.span
                    animate={{ scale: active ? 1 : 0.55 }}
                    transition={{ type: 'spring', stiffness: 400, damping: 24 }}
                    className={`flex h-6 w-6 rotate-45 items-center justify-center border-2 ${
                      active ? 'border-white bg-surface-1' : done ? 'border-white bg-white' : 'border-white/60 bg-transparent hover:border-white'
                    }`}
                  >
                    {active && <span className="-rotate-45 text-[10px] font-black">{c + 1}</span>}
                  </motion.span>
                )}
              </button>
            </div>
          )
        })}
      </div>
      <ArrowButton dir="right" disabled={chapter === chapters - 1} onClick={() => onPick(chapter + 1)} label={t('competitive.pass.nextChapter')} />
    </div>
  )
}

function ArrowButton({ dir, disabled, onClick, label }: { dir: 'left' | 'right'; disabled: boolean; onClick: () => void; label: string }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      className="flex h-9 w-9 items-center justify-center rounded-lg border border-line-strong bg-surface-1/80 text-white transition-colors hover:border-white/50 disabled:cursor-not-allowed disabled:opacity-30"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
        <path d={dir === 'left' ? 'M15 6l-6 6 6 6' : 'M9 6l6 6-6 6'} />
      </svg>
    </button>
  )
}
