import { useRef, useState, type ReactNode } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'
import { Skeleton, SkeletonRows } from '@/components/ui/Skeleton'
import { RankTile, useRankName } from '@/components/competitive/RankCard'
import { MenuButton } from '@/mobile/ui/NavDrawer'
import { Sheet, SheetTitle } from '@/mobile/ui/Sheet'
import { SwipeTabs } from '@/mobile/ui/SwipeTabs'
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
  onMoreDuels,
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
  /** La page suivante de l'historique ; absent quand tout est là. */
  onMoreDuels?: () => void
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
        {data && <ModePill mode={mode} open={picking} onClick={() => setPicking(true)} />}
      </header>

      {data && (
        <div className="shrink-0 px-4 pb-3">
          {/* Largeur selon le texte, pas quatre parts égales : en parts
              égales « Classement » était coupé pendant que « Duels » et
              « Stats » gardaient de la place en trop. La place restante se
              répartit ensuite également (flex-grow 1 sur une base `auto`). */}
          <div className="flex gap-1 rounded-2xl bg-white/[0.04] p-1" role="tablist">
            {COMPETITIVE_VIEWS.map((v) => (
              <button
                key={v}
                role="tab"
                aria-selected={view === v}
                aria-label={t(`competitive.nav.${v}`)}
                onClick={() => onView(v)}
                className={`h-10 min-w-0 flex-[1_1_auto] whitespace-nowrap rounded-xl px-2 text-[14px] font-semibold transition-colors duration-150 max-[374px]:px-1 max-[374px]:text-[13px] ${
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
        <SwipeTabs tabs={COMPETITIVE_VIEWS} value={view} onChange={onView} animate={false}>
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
              onMore={onMoreDuels}
              resetKey={`${mode}|${activeServer}`}
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
        </SwipeTabs>
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

/** L'icône d'un mode : la masse (manche et tête à pointes) ou l'épée. */
function ModeGlyph({ mode, className }: { mode: ModeKey; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden>
      {mode === 'mace' ? (
        <>
          <path d="M4 20l7.5-7.5" />
          <circle cx="15" cy="9" r="4" />
          <path d="M15 2.5v1.5M15 14v1.5M21.5 9H20M10 9H8.5M19.6 4.4l-1 1M19.6 13.6l-1-1" />
        </>
      ) : (
        <>
          <path d="M14.5 17.5L3 6V3h3l11.5 11.5" />
          <path d="M13 19l6-6M16 16l4 4M19 21l2-2" />
        </>
      )}
    </svg>
  )
}

/**
 * Le mode montré, en pastille d'en-tête (refaite le 2026-11-10 : un ovale
 * gris de la couleur du fond, sans contour, avec un chevron qui flottait —
 * on ne voyait pas où finissait le bouton). Un contour fin le délimite,
 * l'icône du mode dans un disque d'accent dit ce qu'on choisit, et le
 * chevron se retourne tant que la feuille des modes est ouverte.
 */
function ModePill({ mode, open, onClick }: { mode: ModeKey; open: boolean; onClick: () => void }) {
  const t = useT()
  return (
    <button
      onClick={onClick}
      aria-haspopup="dialog"
      aria-expanded={open}
      aria-label={t('competitive.modePicker')}
      data-mode-pill
      className="press flex h-11 min-w-0 max-w-[52%] shrink-0 items-center gap-2 rounded-full border border-white/[0.1] bg-white/[0.05] pl-1.5 pr-3 shadow-[inset_0_1px_0_rgba(255,255,255,0.06)]"
    >
      {/* Nouveau mode : l'icône arrive en fondu (`key`). */}
      <span key={mode} className="banner-in flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[rgb(var(--accent)/0.22)] text-[rgb(var(--accent-text))]">
        <ModeGlyph mode={mode} className="h-[18px] w-[18px]" />
      </span>
      <span className="min-w-0 truncate text-[15px] font-semibold text-white">{t(`competitive.modes.${mode}`)}</span>
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={2.4}
        strokeLinecap="round"
        strokeLinejoin="round"
        className={`h-4 w-4 shrink-0 text-white/45 transition-transform duration-200 ease-[cubic-bezier(0.23,1,0.32,1)] ${open ? 'rotate-180' : ''}`}
      >
        <path d="M6 9l6 6 6-6" />
      </svg>
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
