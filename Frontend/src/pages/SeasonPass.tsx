import { useEffect, useState } from 'react'
import { clamp, useElementSize } from '@/lib/useElementSize'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { RewardImage } from '@/components/competitive/RewardImage'
import { TopoBackdrop } from '@/components/competitive/TopoBackdrop'
import { Missions, PASS_COLOR } from '@/components/competitive/Challenges'
import { type BattlePass, type Challenge, type PassReward } from '@/lib/competitive'
import { usePass } from '@/lib/usePass'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

/** Paliers par chapitre : une rangée à l'écran. */
const CHAPTER_SIZE = 5
/** Carte de progression et rangée du bas (`w-[240px]`, `gap-6`, `px-8`). */
const CARD_WIDTH = 240
const ROW_GAP = 24
const ROW_PADDING = 64
/** Un palier : sa largeur au plus et au moins, et l'écart entre deux. */
const TILE_MAX = 104
const TILE_MIN = 66
const TILE_GAP = 12
/** Le volet des missions, et la largeur de page sous laquelle il passe
 *  par-dessus le passe au lieu de le pousser (il ne resterait pas de quoi
 *  loger les cinq paliers à côté). */
const MISSIONS_WIDTH = 340
const MISSIONS_PUSH_MIN = 1120
/** La récompense en grand : bornes de sa taille, et ce qu'il faut garder à
 *  côté d'elle dans sa zone — son nom et sa ligne d'état (≈ 44 px), l'écart
 *  au-dessus d'eux (12), son flottement (6 vers le haut) et de la marge pour
 *  que rien ne touche les bords. */
const HERO_MAX = 200
const HERO_MIN = 64
const HERO_RESERVED = 100

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
  // Démonstration (atelier) ou `GET /v1/pass` ; sans passe réglé, « bientôt ».
  const { challenges, pass, season } = usePass()

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
        <PassBoard pass={pass} challenges={challenges} />
      )}
    </div>
  )
}

function PassBoard({ pass, challenges }: { pass: BattlePass; challenges: Challenge[] | null }) {
  const t = useT()
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

  // Flèches du clavier : chapitre précédent / suivant ; Échap referme le volet.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      if (e.key === 'ArrowLeft') goChapter(chapter - 1)
      if (e.key === 'ArrowRight') goChapter(chapter + 1)
      if (e.key === 'Escape') setMissionsOpen(false)
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

  // Tout se dimensionne d'après la place réelle : la page (le volet des
  // missions pousse-t-il le passe ou passe-t-il par-dessus ?), la colonne
  // principale (largeur des paliers, frise, place de la carte de
  // progression) et la zone de la récompense (sa taille en grand).
  const [pageRef, page] = useElementSize<HTMLDivElement>()
  const [mainRef, main] = useElementSize<HTMLDivElement>()
  const [heroRef, hero] = useElementSize<HTMLDivElement>()
  const missionsOverlay = page.width > 0 && page.width < MISSIONS_PUSH_MIN

  // La carte de progression : à droite des paliers à pleine taille (une
  // colonne vide à gauche pour l'équilibre) quand la colonne loge les trois ;
  // sinon en haut à droite, à sa taille de carte — jamais étirée — et les
  // paliers prennent toute la largeur.
  const rowAtFull = CHAPTER_SIZE * TILE_MAX + (CHAPTER_SIZE - 1) * TILE_GAP
  const cardBelow = main.width === 0 || main.width >= rowAtFull + 2 * (CARD_WIDTH + ROW_GAP) + ROW_PADDING
  // Les paliers prennent la largeur qu'on leur laisse, à leur taille au plus
  // et à un plancher au moins.
  const rowWidth = cardBelow ? rowAtFull : Math.max(0, main.width - 32)
  const tileWidth = Math.floor(clamp((rowWidth - (CHAPTER_SIZE - 1) * TILE_GAP) / CHAPTER_SIZE, TILE_MIN, TILE_MAX))
  // La récompense en grand : ce que sa zone laisse après son nom.
  // La carte de progression en haut à droite gêne-t-elle la récompense ?
  // Celle-ci est centrée : il lui reste, entre la carte et son reflet à
  // gauche, `free` de large. Assez : l'image se loge entre les deux, sur
  // toute la hauteur. Pas assez : la zone commence sous la carte.
  const [cardRef, card] = useElementSize<HTMLDivElement>()
  const free = main.width - 2 * (CARD_WIDTH + 32)
  const cardInTheWay = !cardBelow && free < HERO_MIN + 40
  const heroTop = cardInTheWay ? card.height + 24 : 16
  const heroRoom = hero.height === 0 ? HERO_MAX : hero.height - heroTop - 8 - HERO_RESERVED
  const heroSize = Math.floor(clamp(!cardBelow && !cardInTheWay ? Math.min(heroRoom, free) : heroRoom, HERO_MIN, HERO_MAX))

  return (
    <div ref={pageRef} className="relative isolate flex min-h-0 flex-1">
      {/* Fond : la topographie du passe et un halo derrière la récompense. */}
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        <TopoBackdrop color={PASS_COLOR} />
        <div className="absolute inset-0" style={{ background: `radial-gradient(34rem 26rem at 55% 38%, rgb(${PASS_COLOR} / 0.2), transparent 70%)` }} />
      </div>

      {/* Volet des missions, à gauche : d'où vient l'XP qui fait avancer le
          passe. Sur une grande fenêtre il pousse le passe ; sur une petite il
          passe par-dessus (les paliers n'auraient plus de place à côté), et
          un clic à côté le referme. */}
      {missionsOverlay && (
        <AnimatePresence>
          {missionsOpen && (
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setMissionsOpen(false)}
              className="absolute inset-0 z-20 bg-black/50"
            />
          )}
        </AnimatePresence>
      )}
      <motion.aside
        initial={false}
        animate={{ width: missionsOpen ? MISSIONS_WIDTH : 0 }}
        transition={{ type: 'spring', stiffness: 320, damping: 34 }}
        className={`shrink-0 overflow-hidden ${missionsOverlay ? 'absolute inset-y-0 left-0 z-30 bg-bg-primary/95 shadow-[12px_0_40px_rgba(0,0,0,0.5)] backdrop-blur' : 'relative'}`}
      >
        {/* Le panneau des missions remplit la hauteur du volet. */}
        <div className="flex h-full flex-col py-4 pl-4 pr-1 [&>*]:min-h-0 [&>*]:flex-1" style={{ width: MISSIONS_WIDTH }}>
          <Missions challenges={challenges} pass={pass} />
        </div>
      </motion.aside>

      <div ref={mainRef} className="relative flex min-w-0 flex-1 flex-col">
      {/* En haut, dans les coins : le bouton du volet à gauche, et la carte
          de progression à droite quand elle ne tient plus à côté des
          paliers. Posés par-dessus la zone de la récompense, qui garde ainsi
          toute la hauteur ; c'est elle qui s'écarte d'eux (voir `heroSize`). */}
      <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-start justify-between gap-3 px-4 pt-4 [&>*]:pointer-events-auto">
        <button
          onClick={() => setMissionsOpen((v) => !v)}
          title={missionsOpen ? t('competitive.pass.hideMissions') : t('competitive.pass.showMissions')}
          aria-expanded={missionsOpen}
          className="relative z-10 flex h-9 shrink-0 items-center gap-2 rounded-lg border border-line-strong bg-surface-1/85 px-2.5 text-[11px] font-bold uppercase tracking-[0.04em] text-txt-secondary backdrop-blur transition-colors hover:border-accent/50 hover:text-white"
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
        {!cardBelow && (
          <div ref={cardRef}>
            <ProgressCard pass={pass} owned={owned} />
          </div>
        )}
      </div>

      {/* La récompense choisie, en grand, sur toute la hauteur libre. Pas de
          `overflow-hidden` : il rognait le halo de l'image et son flottement ;
          la taille calculée garde assez de place pour que rien ne déborde. */}
      <div
        ref={heroRef}
        className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 px-6 pb-2"
        style={{ paddingTop: heroTop }}
      >
        <AnimatePresence mode="wait">
          <motion.div
            key={selected}
            initial={{ opacity: 0, scale: 0.85, y: 10 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.92, y: -6 }}
            transition={{ type: 'spring', stiffness: 260, damping: 22 }}
            style={{ filter: `drop-shadow(0 0 40px rgb(${PASS_COLOR} / 0.45))` }}
          >
            <motion.div animate={{ y: [0, -6, 0] }} transition={{ duration: 4, repeat: Infinity, ease: 'easeInOut' }}>
              <RewardImage reward={reward} color={PASS_COLOR} tierIndex={3} size={heroSize} />
            </motion.div>
          </motion.div>
        </AnimatePresence>

        {/* Le nom reste entre la carte et son reflet quand la carte est en haut. */}
        <div className="max-w-full text-center" style={!cardBelow && !cardInTheWay ? { maxWidth: free } : undefined}>
          <p className="truncate text-[clamp(15px,2.6vh,20px)] font-black uppercase tracking-[0.02em]">
            {t(`competitive.seasons.kinds.${reward.kind}`, { rank: reward.name })}
          </p>
          <p className={`mt-1 truncate text-[12px] font-semibold ${state === 'obtained' ? 'text-success' : 'text-txt-secondary'}`}>
            {state === 'obtained'
              ? t('competitive.pass.obtained')
              : state === 'current'
                ? t('competitive.pass.xpProgress', { xp: pass.xpInTier.toLocaleString(), total: pass.xpPerTier.toLocaleString() })
                : t('competitive.pass.tierLabel', { tier: selected })}
          </p>
        </div>
      </div>

      {/* Les paliers du chapitre, puis la frise des chapitres. Sur une grande
          colonne, la carte de progression occupe la droite et la gauche
          l'équilibre ; sinon les paliers prennent toute la largeur. */}
      <div
        className={`shrink-0 items-center px-4 pb-[clamp(10px,2.2vh,24px)] pt-[clamp(6px,1.4vh,16px)] ${
          cardBelow ? 'grid grid-cols-[1fr_auto_1fr] gap-6 px-8' : 'flex justify-center'
        }`}
      >
        {cardBelow && <div />}
        <div className="flex min-w-0 flex-col items-center gap-[clamp(8px,1.8vh,20px)]">
          <div className="flex" style={{ gap: TILE_GAP }}>
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
                width={tileWidth}
                onPick={() => setSelected(tier)}
              />
            ))}
          </div>
          <ChapterTrack
            chapters={chapters}
            chapter={chapter}
            doneChapters={owned ? Math.floor(pass.tier / CHAPTER_SIZE) : 0}
            width={rowWidth}
            onPick={goChapter}
          />
        </div>
        {/* La progression et le passe, ensemble, à hauteur des paliers :
            on lit d'un coup où l'on en est et ce qu'on en tire. */}
        {cardBelow && (
          <div className="flex justify-end">
            <ProgressCard pass={pass} owned={owned} />
          </div>
        )}
      </div>
      </div>
    </div>
  )
}

/**
 * Où l'on en est dans le passe, et de quoi le débloquer. Toujours à sa
 * taille de carte : à côté des paliers sur une grande colonne, en haut à
 * droite sinon.
 */
function ProgressCard({ pass, owned }: { pass: BattlePass; owned: boolean }) {
  const t = useT()
  const navigate = useNavigate()
  const xpLeft = pass.xpPerTier - pass.xpInTier
  const bar = (
    <div className="h-2 overflow-hidden rounded-full bg-white/[0.08]">
      <motion.div
        className="h-full rounded-full bg-white"
        initial={{ width: 0 }}
        animate={{ width: `${(pass.xpInTier / pass.xpPerTier) * 100}%` }}
        transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1] }}
      />
    </div>
  )
  const action = owned ? (
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
  )

  return (
    <div className="flex shrink-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1/85 p-4 backdrop-blur" style={{ width: CARD_WIDTH }}>
      <div className="flex items-baseline justify-between">
        <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('competitive.pass.progress')}</span>
        <span className="text-[15px] font-black tabular-nums">
          {pass.tier}<span className="text-[11px] font-bold text-txt-muted"> / {pass.tiers}</span>
        </span>
      </div>
      <div className="flex flex-col gap-1.5">
        {bar}
        <div className="flex justify-between gap-2 text-[10.5px] font-semibold">
          <span className="tabular-nums text-txt-muted">
            {t('competitive.pass.xpProgress', { xp: pass.xpInTier.toLocaleString(), total: pass.xpPerTier.toLocaleString() })}
          </span>
          {pass.tier < pass.tiers && (
            <span className="text-success">{t('competitive.pass.xpToTier', { xp: xpLeft.toLocaleString(), tier: pass.tier + 1 })}</span>
          )}
        </div>
      </div>
      {action}
    </div>
  )
}

/** Un palier : sa largeur vient de la place de la rangée (`width`), sa
 *  hauteur et son image en découlent. */
function TierTile({ tier, reward, state, owned, selected, ratio, index, width, onPick }: {
  tier: number
  reward: PassReward
  state: TierState
  owned: boolean
  selected: boolean
  ratio: number
  index: number
  width: number
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
      className={`relative flex shrink-0 flex-col overflow-hidden rounded-xl border-2 bg-surface-1/80 backdrop-blur transition-colors ${
        selected ? 'border-white' : state === 'current' ? 'border-accent/60' : 'border-line hover:border-line-strong'
      }`}
      style={{ width }}
    >
      <div
        className={`flex items-center justify-center ${state === 'upcoming' && !selected ? 'opacity-55' : ''}`}
        style={{ height: Math.round(width * 0.86) }}
      >
        <RewardImage reward={reward} color={PASS_COLOR} tierIndex={3} size={Math.round(width * 0.58)} />
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
        className={`relative truncate px-1 py-1.5 text-center font-bold uppercase tracking-[0.04em] ${width < 90 ? 'text-[9.5px]' : 'text-[10.5px]'} ${
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

/** La frise : un repère de chapitre (32 px), le trait entre deux (de 8 à
 *  36 px), et ce que prennent les deux flèches avec leurs écarts. */
const MARK = 32
const LINE_MIN = 8
const LINE_MAX = 36
const ARROWS = 2 * 36 + 2 * 12

/** La frise des chapitres : flèches, un losange par chapitre, une étoile au
 *  dernier. Le chapitre affiché porte son numéro.
 *
 *  Elle tient toujours dans `width` : les traits raccourcissent d'abord, et
 *  s'il y a trop de chapitres même ainsi, elle n'en montre qu'une fenêtre
 *  autour du chapitre affiché (les flèches font avancer la fenêtre). */
function ChapterTrack({ chapters, chapter, doneChapters, width, onPick }: {
  chapters: number
  chapter: number
  doneChapters: number
  width: number
  onPick: (chapter: number) => void
}) {
  const t = useT()
  const room = Math.max(MARK, width - ARROWS)
  const fit = Math.max(1, Math.floor((room + LINE_MIN) / (MARK + LINE_MIN)))
  const shown = Math.min(chapters, fit)
  const start = clamp(chapter - Math.floor(shown / 2), 0, chapters - shown)
  const line = shown > 1 ? Math.floor(clamp((room - shown * MARK) / (shown - 1), LINE_MIN, LINE_MAX)) : 0
  const visible = Array.from({ length: shown }, (_, i) => start + i)
  return (
    <div className="flex items-center gap-3">
      <ArrowButton dir="left" disabled={chapter === 0} onClick={() => onPick(chapter - 1)} label={t('competitive.pass.prevChapter')} />
      <div className="flex items-center">
        {visible.map((c, i) => {
          const active = c === chapter
          const last = c === chapters - 1
          const done = c < doneChapters
          // Une frise coupée s'estompe à ses bouts : il y a des chapitres au-delà.
          const cutLeft = i === 0 && start > 0
          const cutRight = i === shown - 1 && start + shown < chapters
          return (
            <div key={c} className={`flex items-center ${cutLeft || cutRight ? 'opacity-40' : ''}`}>
              {i > 0 && <span className={`h-px ${c <= doneChapters ? 'bg-white/60' : 'bg-white/20'}`} style={{ width: line }} />}
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
