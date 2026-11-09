import { useEffect, useRef, useState, type ReactNode } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'
import { Skeleton, SkeletonRows } from '@/components/ui/Skeleton'
import { RankTile, useRankName } from '@/components/competitive/RankCard'
import { MenuButton } from '@/mobile/ui/NavDrawer'
import { Sheet, SheetTitle } from '@/mobile/ui/Sheet'
import { bindTouchDrag, isTouchControl, rubber } from '@/mobile/ui/gesture'
import { MODES, TIER_COLORS, emptyStanding, tierOf, type CompetitiveData, type DuelRow, type ModeKey, type ServerStats, type Standing } from '@/lib/competitive'
import { MobileHistory, MobileLeaderboard, MobileSeason, MobileStats } from './views'

export const COMPETITIVE_VIEWS = ['history', 'stats', 'season', 'leaderboard'] as const
export type CompetitiveView = (typeof COMPETITIVE_VIEWS)[number]

/**
 * L'onglet Compétitif sur téléphone. Mêmes écrans que le PC, dans le même
 * ordre, et le même mode de jeu qui les pilote tous ; seule la mise en page
 * change :
 * - en-tête : ☰ (ou ‹ chez un ami), le titre, et le mode en pastille — elle
 *   ouvre une feuille avec chaque mode et son rang. Une feuille plutôt que
 *   deux boutons : d'autres modes viendront ;
 * - dessous, les quatre écrans en commutateur à cases égales, libellés courts
 *   (`navShort`) pour que tout tienne dans la largeur ;
 * - chaque écran est une colonne qui défile, là où le PC tient en une page
 *   sans défilement avec des listes qui défilent dans leur carte.
 *
 * Les données, leur chargement et le serveur filtré restent dans
 * `pages/Competitive.tsx` : un seul endroit pour les deux mises en page.
 *
 * Pas d'animation en boucle : les fonds topographiques sont figés (`still`).
 * Changer d'écran ou de mode ne fait qu'un fondu court, sans cascade de
 * lignes — c'est un geste qu'on refait souvent.
 */
export function MobileCompetitive({
  title,
  friend,
  loading,
  data,
  empty,
  view,
  onView,
  mode,
  onMode,
  standing,
  modeDuels,
  duels,
  servers,
  activeServer,
  onServer,
  friendName,
}: {
  title: string
  /** L'onglet d'un ami : retour au lieu du menu, saison en lecture seule. */
  friend: boolean
  loading: boolean
  data: CompetitiveData | null
  /** Ce qu'on montre quand il n'y a pas de données (connexion, compte…). */
  empty: ReactNode
  view: CompetitiveView
  onView: (v: CompetitiveView) => void
  mode: ModeKey
  onMode: (m: ModeKey) => void
  standing: Standing
  /** Tous les duels du mode (statistiques). */
  modeDuels: DuelRow[] | null
  /** Les duels du mode, filtrés sur le serveur choisi (historique). */
  duels: DuelRow[] | null
  servers: ServerStats[] | null
  activeServer: string | null
  onServer: (server: string | null) => void
  friendName: string | null
}) {
  const t = useT()
  const navigate = useNavigate()
  const reduce = useReducedMotion()
  const [picking, setPicking] = useState(false)
  // Le sens du dernier changement d'écran : le suivant arrive de la droite,
  // le précédent de la gauche.
  const lastView = useRef(view)
  const direction = useRef(0)
  if (lastView.current !== view) {
    direction.current = Math.sign(COMPETITIVE_VIEWS.indexOf(view) - COMPETITIVE_VIEWS.indexOf(lastView.current))
    lastView.current = view
  }
  const enterFrom = direction.current

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-[rgb(var(--bg-primary))] text-txt-primary">
      <header className="flex shrink-0 items-center gap-1 pb-2 pl-1.5 pr-4 pt-[max(12px,env(safe-area-inset-top))]">
        {friend ? (
          <button onClick={() => navigate(-1)} aria-label={t('competitive.back')} className="press flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-[rgb(var(--accent-text))]">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6"><path d="M15 6l-6 6 6 6" /></svg>
          </button>
        ) : (
          <MenuButton />
        )}
        {/* 24 px sous 375 px de large : le titre et la pastille du mode
            doivent tenir ensemble (« Rankingowy » + « Buzdygan »). */}
        <h1 className={`min-w-0 flex-1 truncate font-bold tracking-tight text-white ${friend ? 'text-[22px]' : 'text-[28px] max-[374px]:text-[24px]'}`}>{title}</h1>
        {data && <ModePill mode={mode} onClick={() => setPicking(true)} />}
      </header>

      {data && (
        <div className="shrink-0 px-4 pb-3">
          <div className="grid grid-cols-4 gap-1 rounded-2xl bg-white/[0.04] p-1" role="tablist">
            {COMPETITIVE_VIEWS.map((v) => (
              <button
                key={v}
                role="tab"
                aria-selected={view === v}
                aria-label={t(`competitive.nav.${v}`)}
                onClick={() => onView(v)}
                // Sous 375 px, 13 px et presque sans marge : « Classement »
                // et « Temporada » tiennent encore sur 360 px.
                className={`h-10 min-w-0 rounded-xl px-1 text-[14px] font-semibold transition-colors duration-150 max-[374px]:px-0 max-[374px]:text-[13px] ${
                  view === v ? 'bg-[rgb(var(--accent)/0.35)] text-white' : 'text-white/60'
                }`}
              >
                <span className="block truncate">{t(`competitive.navShort.${v}`)}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {loading ? (
        // Silhouette de l'historique : carte du rang, trois serveurs, duels.
        <div aria-busy className="flex flex-col gap-3 px-4 pt-2">
          <Skeleton className="h-40 rounded-2xl" />
          <div className="grid grid-cols-3 gap-2.5">
            <Skeleton className="h-24 rounded-2xl" />
            <Skeleton className="h-24 rounded-2xl" />
            <Skeleton className="h-24 rounded-2xl" />
          </div>
          <SkeletonRows count={4} rowClassName="h-16 rounded-2xl" />
        </div>
      ) : !data ? (
        <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-6">{empty}</div>
      ) : (
        <SwipeViews view={view} onView={onView}>
        <motion.div
          // Une colonne par écran et par mode : chacun repart en haut. Il
          // arrive du côté vers lequel on va (24 px), en fondu court.
          key={`${view}-${mode}`}
          initial={reduce ? false : { opacity: 0, transform: `translateX(${enterFrom * 24}px)` }}
          animate={{ opacity: 1, transform: 'translateX(0px)', transitionEnd: { transform: 'none' } }}
          transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
          className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(32px,env(safe-area-inset-bottom))] pt-1"
        >
          {view === 'history' ? (
            <MobileHistory
              standing={standing}
              duels={duels}
              servers={servers}
              activeServer={activeServer}
              onServer={onServer}
              onOpenStats={() => onView('stats')}
            />
          ) : view === 'stats' ? (
            <MobileStats duels={modeDuels} />
          ) : view === 'season' ? (
            <MobileSeason
              wins={data.seasonWins ? data.seasonWins[mode] ?? [] : null}
              count={data.seasonWinCount?.[mode]}
              season={data.seasons?.[0] ?? null}
              current={standing}
              mode={mode}
              readOnly={friend}
            />
          ) : (
            <MobileLeaderboard
              rows={data.leaderboard ? data.leaderboard[mode] ?? [] : null}
              friendRows={data.friendsLeaderboard ? data.friendsLeaderboard[mode] ?? [] : null}
              placed={standing.placed}
              subject={friend ? friendName ?? '…' : undefined}
            />
          )}
        </motion.div>
        </SwipeViews>
      )}

      {data && (
        <ModeSheet
          open={picking}
          onClose={() => setPicking(false)}
          value={mode}
          standings={data.standings}
          onPick={(m) => {
            setPicking(false)
            onMode(m)
          }}
        />
      )}
    </div>
  )
}

/** Distance (px) ou vitesse (px/s) d'un balayage qui change d'écran. */
const SWIPE_OFFSET = 60
const SWIPE_VELOCITY = 400

/**
 * Balayer l'écran vers la gauche ou la droite passe à l'écran voisin
 * (Duels ↔ Stats ↔ Saison ↔ Classement), comme les onglets d'Android
 * (2026-11-09). Pendant le geste, la colonne suit un peu le doigt en
 * résistant ; au premier et au dernier écran, elle résiste davantage et
 * revient. Un geste plutôt vertical reste un défilement, et un contenu qui
 * défile lui-même horizontalement garde son geste.
 */
function SwipeViews({ view, onView, children }: { view: CompetitiveView; onView: (v: CompetitiveView) => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  const latest = useRef({ view, onView })
  latest.current = { view, onView }

  useEffect(() => {
    const el = ref.current!
    const column = () => el.firstElementChild as HTMLElement | null
    const neighbour = (d: number) => {
      const i = COMPETITIVE_VIEWS.indexOf(latest.current.view) + (d < 0 ? 1 : -1)
      return COMPETITIVE_VIEWS[i] ?? null
    }
    return bindTouchDrag(el, {
      axis: 'x',
      accept: (dx, dy, target) =>
        Math.abs(dx) > Math.abs(dy) * 1.5 && !isTouchControl(target) && !canScrollHorizontally(target, el, dx),
      onStart: () => {
        const c = column()
        if (c) c.style.transition = 'none'
      },
      onMove: (d) => {
        const c = column()
        // Le doigt « tire » l'écran : plus fort quand il n'y a rien derrière.
        if (c) c.style.transform = `translateX(${rubber(d, neighbour(d) ? 90 : 40)}px)`
      },
      onEnd: (d, velocity) => {
        const next = neighbour(d)
        const c = column()
        if (next && (Math.abs(d) > SWIPE_OFFSET || Math.abs(velocity) > SWIPE_VELOCITY) && Math.sign(velocity || d) === Math.sign(d)) {
          latest.current.onView(next)
          return
        }
        if (c) {
          c.style.transition = 'transform 260ms cubic-bezier(0.2, 0.9, 0.25, 1)'
          c.style.transform = ''
          window.setTimeout(() => { c.style.transition = '' }, 300)
        }
      },
    })
  }, [])

  return <div ref={ref} className="flex min-h-0 flex-1 flex-col">{children}</div>
}

/** Un ancêtre de `el` (jusqu'à `root` exclu) qui peut encore défiler dans le sens du geste. */
function canScrollHorizontally(el: Element, root: Element, dx: number): boolean {
  for (let n: Element | null = el; n && n !== root; n = n.parentElement) {
    if (!(n instanceof HTMLElement) || n.scrollWidth <= n.clientWidth + 1) continue
    const ox = getComputedStyle(n).overflowX
    if (ox !== 'auto' && ox !== 'scroll') continue
    if (dx > 0 ? n.scrollLeft > 0 : n.scrollLeft + n.clientWidth < n.scrollWidth - 1) return true
  }
  return false
}

/** Le mode montré, en pastille d'en-tête : son insigne et son nom. */
function ModePill({ mode, onClick }: { mode: ModeKey; onClick: () => void }) {
  const t = useT()
  return (
    <button
      onClick={onClick}
      aria-haspopup="dialog"
      aria-label={t('competitive.modePicker')}
      data-mode-pill
      // Le mode seul, en texte : l'insigne de rang y était trop petit pour
      // lire sa division, et son carré jurait dans une forme ronde. Le rang
      // reste sur la carte du rang et dans la feuille des modes.
      className="press flex h-11 min-w-0 max-w-[48%] shrink-0 items-center gap-1 rounded-full bg-white/[0.07] pl-4 pr-3"
    >
      <span className="min-w-0 truncate text-[15px] font-semibold text-white">{t(`competitive.modes.${mode}`)}</span>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-[18px] w-[18px] shrink-0 text-white/60"><path d="M6 9l6 6 6-6" /></svg>
    </button>
  )
}

/** La feuille des modes : une ligne par mode, avec le rang qu'on y a. */
function ModeSheet({ open, onClose, value, standings, onPick }: {
  open: boolean
  onClose: () => void
  value: ModeKey
  standings: Standing[]
  onPick: (m: ModeKey) => void
}) {
  const t = useT()
  const rankName = useRankName()
  return (
    <Sheet open={open} onClose={onClose} label={t('competitive.modePicker')} header={<SheetTitle>{t('competitive.modePicker')}</SheetTitle>} bodyClassName="flex flex-col gap-1 px-3 pb-3">
      {MODES.map((m) => {
        const s = standings.find((x) => x.mode === m) ?? emptyStanding(m)
        const active = m === value
        return (
          <button
            key={m}
            onClick={() => onPick(m)}
            aria-pressed={active}
            className={`press flex min-h-[64px] items-center gap-3.5 rounded-2xl px-3 text-left ${active ? 'bg-accent/[0.14]' : 'active:bg-white/[0.04]'}`}
          >
            <RankTile division={s.division} placed={s.placed} size={40} />
            <span className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-[16px] font-semibold text-white">{t(`competitive.modes.${m}`)}</span>
              <span className="truncate text-[13px] font-semibold" style={{ color: s.placed ? `rgb(${TIER_COLORS[tierOf(s.division)]})` : 'rgb(255 255 255 / 0.55)' }}>
                {rankName(s.division, s.placed)}
              </span>
            </span>
            {active && (
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.6} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6 shrink-0 text-[rgb(var(--accent-text))]"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
            )}
          </button>
        )
      })}
    </Sheet>
  )
}
