import { useRef, useState, type ReactNode } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'
import { SkinFace } from '@/components/ui/SkinFace'
import { Toggle } from '@/components/ui/Toggle'
import { playerSkinSrc } from '@/lib/heads'
import { Sheet, SheetTitle } from '@/mobile/ui/Sheet'
import { RankTile, useRankName } from '@/components/competitive/RankCard'
import { TopoBackdrop } from '@/components/competitive/TopoBackdrop'
import { ServerAvatar } from '@/components/competitive/ServerStats'
import { formatLength } from '@/components/competitive/DuelHistory'
import { CHART_DUELS, DRAW, FORM_DUELS, LOSS, WIN, summarize } from '@/components/competitive/StatsView'
import { SeasonFrame, SeasonGrid, useSeasonOnProfile } from '@/components/competitive/SeasonRankView'
import { PODIUM } from '@/components/competitive/Leaderboard'
import {
  BORDER_LEVELS,
  CHAMPION,
  DIVISION_POINTS,
  PLACEMENT_DUELS,
  SEASON_GRID,
  TIER_COLORS,
  WINS_PER_BORDER,
  borderLevel,
  scoreColor,
  tierOf,
  type DuelRow,
  type LeaderboardRow,
  type ModeKey,
  type Season,
  type ServerStats,
  type Standing,
} from '@/lib/competitive'

/**
 * Les quatre écrans de l'onglet Compétitif, sur téléphone. Chacun est une
 * colonne qui défile : 32 px entre les sections, 12 px dedans, texte de 15 px
 * (13 px pour le secondaire), cibles de 44 px au moins.
 *
 * Mêmes calculs que le PC (`summarize`, les couleurs de note, la grille de la
 * saison, l'affichage au profil) : seules les mises en page sont à part.
 */

const CARD = 'rounded-2xl border border-white/[0.06] bg-white/[0.04]'
const LIST = `flex flex-col divide-y divide-white/[0.06] overflow-hidden ${CARD}`
/** Ouverture des barres : une fois, sans rebond. */
const GROW = { duration: 0.6, ease: [0.23, 1, 0.32, 1] } as const

function Section({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between gap-3 px-1">
        <h2 className="min-w-0 truncate text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

function Note({ children }: { children: ReactNode }) {
  return <p className={`${CARD} px-4 py-6 text-center text-[15px] leading-relaxed text-white/60`}>{children}</p>
}

/** Une jauge qui se remplit une fois, par `scaleX` (le compositeur, pas la mise en page). */
function Gauge({ value, color, className = 'h-2' }: { value: number; color: string; className?: string }) {
  const reduce = useReducedMotion()
  return (
    <div className={`overflow-hidden rounded-full bg-white/[0.08] ${className}`}>
      <motion.div
        className="h-full origin-left rounded-full"
        style={{ background: `linear-gradient(90deg, rgb(${color} / 0.55), rgb(${color}))` }}
        initial={reduce ? false : { scaleX: 0 }}
        animate={{ scaleX: Math.min(1, Math.max(0, value)) }}
        transition={GROW}
      />
    </div>
  )
}

function Chevron({ className = 'h-5 w-5' }: { className?: string }) {
  return <svg viewBox="0 0 24 24" fill="currentColor" className={`shrink-0 ${className}`}><path d="M9.3 6.7 10.7 5.3l6.7 6.7-6.7 6.7-1.4-1.4 5.3-5.3z" /></svg>
}

// ── Historique ───────────────────────────────────────────────────────────

/**
 * Le rang en tête, puis les trois meilleurs serveurs, puis les duels. Sur PC
 * ce sont trois colonnes côte à côte ; ici, une seule, dans l'ordre de
 * lecture. La carte du rang mène aux statistiques, et le dit : au doigt, pas
 * de survol pour le découvrir.
 */
export function MobileHistory({ standing, duels, servers, activeServer, onServer, onOpenStats }: {
  standing: Standing
  duels: DuelRow[] | null
  servers: ServerStats[] | null
  activeServer: string | null
  onServer: (server: string | null) => void
  onOpenStats: () => void
}) {
  const t = useT()
  // La feuille garde son serveur pendant qu'elle se referme.
  const [sheet, setSheet] = useState<{ open: boolean; stats: ServerStats | null }>({ open: false, stats: null })
  const best = servers ? [...servers].sort((a, b) => b.avgScore - a.avgScore).slice(0, 3) : null

  return (
    <div className="flex flex-col gap-8">
      <RankHero standing={standing} onOpen={onOpenStats} />

      <Section title={t('competitive.servers.title')} aside={best && best.length > 0 && <span className="shrink-0 text-[13px] text-white/55">{t('competitive.servers.avgScore')}</span>}>
        {best === null ? (
          <Note>{t('competitive.servers.soon')}</Note>
        ) : best.length === 0 ? (
          <Note>{t('competitive.history.empty')}</Note>
        ) : (
          <div className="grid grid-cols-3 gap-3">
            {best.map((s) => (
              <ServerTile
                key={s.server}
                stats={s}
                active={activeServer === s.server}
                dimmed={activeServer !== null && activeServer !== s.server}
                onClick={() => setSheet({ open: true, stats: s })}
              />
            ))}
          </div>
        )}
      </Section>

      <Section title={t('competitive.history.title')}>
        {activeServer && (
          <div className="flex items-center gap-3 rounded-2xl border border-accent/30 bg-accent/[0.08] py-1.5 pl-2.5 pr-1.5">
            <div className="h-8 w-8 shrink-0"><ServerAvatar server={activeServer} /></div>
            <span className="min-w-0 flex-1 truncate text-[15px] font-semibold text-white">{activeServer}</span>
            <button onClick={() => onServer(null)} className="press h-11 shrink-0 rounded-xl bg-white/[0.08] px-3 text-[14px] font-semibold text-white">
              {t('competitive.servers.all')}
            </button>
          </div>
        )}
        {duels === null ? (
          <Note>{t('competitive.history.soon')}</Note>
        ) : duels.length === 0 ? (
          <Note>{t('competitive.history.empty')}</Note>
        ) : (
          <DuelDays duels={duels} showServer={activeServer === null} />
        )}
      </Section>

      <ServerSheet
        open={sheet.open}
        stats={sheet.stats}
        filtered={sheet.stats !== null && activeServer === sheet.stats.server}
        onClose={() => setSheet((s) => ({ ...s, open: false }))}
        onFilter={() => {
          if (sheet.stats) onServer(activeServer === sheet.stats.server ? null : sheet.stats.server)
          setSheet((s) => ({ ...s, open: false }))
        }}
      />
    </div>
  )
}

/** La carte du rang : insigne, nom, duels, la jauge de la division (ou les cases du placement). */
function RankHero({ standing, onOpen }: { standing: Standing; onOpen: () => void }) {
  const t = useT()
  const rankName = useRankName()
  const reduce = useReducedMotion()
  const color = standing.placed ? TIER_COLORS[tierOf(standing.division)] : '160 150 190'
  const champion = standing.division >= CHAMPION

  return (
    <button
      onClick={onOpen}
      className="press relative isolate flex w-full flex-col items-center overflow-hidden rounded-[24px] border border-white/[0.08] bg-white/[0.03] px-5 pt-7 text-center"
      style={{ backgroundImage: `radial-gradient(18rem 12rem at 50% 30%, rgb(${color} / 0.2), transparent 70%)` }}
    >
      <div aria-hidden className="absolute inset-0 -z-10" style={{ maskImage: 'radial-gradient(ellipse 70% 50% at 50% 35%, rgb(0 0 0 / 0.4) 0%, black 100%)' }}>
        <TopoBackdrop color={color} still />
      </div>

      <motion.div
        key={`${standing.mode}-${standing.division}-${standing.placed}`}
        initial={reduce ? false : { scale: 0.9, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ type: 'spring', duration: 0.5, bounce: 0.2 }}
        style={{ filter: `drop-shadow(0 0 20px rgb(${color} / 0.5))` }}
      >
        <RankTile division={standing.division} placed={standing.placed} size={88} />
      </motion.div>

      <p className="mt-4 text-[30px] font-black leading-none tracking-[-0.02em]" style={{ color: `rgb(${color})`, textShadow: `0 0 22px rgb(${color} / 0.35)` }}>
        {rankName(standing.division, standing.placed)}
      </p>
      <p className="mt-2 text-[13px] text-white/60">
        {standing.placed
          ? t('competitive.rank.duels', { count: standing.duels })
          : t('competitive.rank.placement', { done: standing.duels, total: PLACEMENT_DUELS })}
      </p>

      <div className="mt-5 flex w-full max-w-[300px] flex-col gap-2">
        {standing.placed ? (
          <>
            <Gauge value={champion ? 1 : standing.points / DIVISION_POINTS} color={color} className="h-2.5" />
            <span className="text-[13px] font-semibold tabular-nums text-white/65">
              {champion
                ? t('competitive.rank.championPoints', { points: standing.points })
                : t('competitive.rank.points', { points: standing.points, max: DIVISION_POINTS })}
            </span>
          </>
        ) : (
          <div className="flex gap-1.5">
            {Array.from({ length: PLACEMENT_DUELS }, (_, i) => (
              <span key={i} className={`h-2.5 flex-1 rounded-full ${i < standing.duels ? 'bg-accent' : 'bg-white/[0.08]'}`} />
            ))}
          </div>
        )}
      </div>

      {/* Ce que fait la carte, dit en clair. */}
      <span className="mt-5 flex h-12 w-[calc(100%+40px)] items-center justify-center gap-1 border-t border-white/[0.06] text-[15px] font-semibold text-[#a29bfe]">
        {t('competitive.tabs.openHub')}
        <Chevron />
      </span>
    </button>
  )
}

function ServerTile({ stats: s, active, dimmed, onClick }: { stats: ServerStats; active: boolean; dimmed: boolean; onClick: () => void }) {
  const color = scoreColor(s.avgScore)
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={`press flex min-w-0 flex-col items-center gap-2.5 rounded-2xl border px-2 pb-3 pt-3.5 transition-opacity duration-150 ${
        active ? 'border-white/50 bg-white/[0.08]' : 'border-white/[0.06] bg-white/[0.04]'
      } ${dimmed ? 'opacity-40' : ''}`}
      style={{ boxShadow: `0 0 24px rgb(${color} / 0.12)` }}
    >
      <div className="aspect-square w-full max-w-[64px]"><ServerAvatar server={s.server} /></div>
      <span
        className="rounded-full border bg-[rgb(14_13_20)] px-2.5 py-0.5 text-[16px] font-black leading-tight tabular-nums"
        style={{ color: `rgb(${color})`, borderColor: `rgb(${color} / 0.45)`, textShadow: `0 0 10px rgb(${color} / 0.5)` }}
      >
        {s.avgScore}
      </span>
      <span className="w-full truncate text-[13px] text-white/60">{s.server}</span>
    </button>
  )
}

const RESULT_COLOR: Record<DuelRow['result'], string> = { win: WIN, loss: LOSS, draw: DRAW }

/**
 * Les duels groupés par jour : la date passe dans l'en-tête du groupe, et
 * chaque ligne ne garde que l'heure. Sur une ligne de téléphone, la date
 * entière poussait l'adresse du serveur hors de la largeur.
 */
function DuelDays({ duels, showServer }: { duels: DuelRow[]; showServer: boolean }) {
  const language = useStore((s) => s.language)
  const locale = language === 'sga' ? 'fr' : language
  const days: { key: string; label: string; duels: DuelRow[] }[] = []
  for (const d of duels) {
    const at = new Date(d.at)
    const key = at.toDateString()
    const last = days[days.length - 1]
    if (last?.key === key) last.duels.push(d)
    else days.push({ key, label: at.toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long' }), duels: [d] })
  }
  return (
    <div className="flex flex-col gap-4">
      {days.map((day) => (
        <div key={day.key} className="flex flex-col gap-2">
          <p className="px-1 text-[13px] font-semibold text-white/70 first-letter:uppercase">{day.label}</p>
          <div className={LIST}>
            {day.duels.map((d) => <DuelLine key={d.id} duel={d} locale={locale} showServer={showServer} />)}
          </div>
        </div>
      ))}
    </div>
  )
}

/** Un duel : l'adversaire, le résultat, l'heure et le serveur à gauche ; les RR, la note et la durée à droite. */
function DuelLine({ duel: d, locale, showServer }: { duel: DuelRow; locale: string; showServer: boolean }) {
  const t = useT()
  const rrColor = d.rr === null ? 'text-white/45' : d.rr > 0 ? 'text-success' : d.rr < 0 ? 'text-danger' : 'text-white/65'
  return (
    <div className="flex min-h-[68px] items-center gap-3 px-4 py-3">
      <SkinFace dataUri={playerSkinSrc(d.opponentUuid, d.opponent)} size={40} className="shrink-0 rounded-md" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-[15px] font-semibold text-white">{t('competitive.history.vs', { name: d.opponent })}</p>
        <p className="truncate text-[13px] text-white/55">
          <span className="font-semibold" style={{ color: `rgb(${RESULT_COLOR[d.result]})` }}>{t(`competitive.history.${d.result}`)}</span>
          {' · '}
          {new Date(d.at).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })}
        </p>
        {/* Le serveur sur sa ligne : une adresse est longue, et sur la ligne
            du résultat elle était coupée presque partout. Inutile quand la
            liste est déjà filtrée sur un serveur. */}
        {showServer && <p className="truncate text-[13px] text-white/45">{d.server}</p>}
      </div>
      <div className="shrink-0 text-right">
        {d.placement ? (
          <p className="text-[13px] font-bold uppercase tracking-[0.04em] text-[#a29bfe]">{t('competitive.history.placement')}</p>
        ) : (
          <p className={`text-[15px] font-black tabular-nums ${rrColor}`}>{d.rr === null ? '—' : `${d.rr > 0 ? '+' : ''}${d.rr} RR`}</p>
        )}
        <p className="text-[13px] tabular-nums text-white/55">{d.score} · {formatLength(d.durationSecs)}</p>
      </div>
    </div>
  )
}

/** Le détail d'un serveur, en feuille : la note moyenne en grand, trois chiffres, et le filtre. */
function ServerSheet({ open, stats: s, filtered, onClose, onFilter }: {
  open: boolean
  stats: ServerStats | null
  filtered: boolean
  onClose: () => void
  onFilter: () => void
}) {
  const t = useT()
  const language = useStore((st) => st.language)
  if (!s) return null
  const played = s.wins + s.losses
  const winRate = played ? Math.round((s.wins / played) * 100) : 0
  const color = scoreColor(s.avgScore)
  const last = new Date(s.lastPlayed).toLocaleString(language === 'sga' ? 'fr' : language, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
  const rrTone = s.rr > 0 ? 'rgb(var(--success))' : s.rr < 0 ? 'rgb(var(--danger))' : undefined

  return (
    <Sheet
      open={open}
      onClose={onClose}
      label={s.server}
      header={
        <div className="flex items-center gap-3 px-5 pb-3">
          <div className="h-12 w-12 shrink-0"><ServerAvatar server={s.server} /></div>
          <div className="min-w-0">
            <p className="truncate text-[17px] font-bold text-white">{s.server}</p>
            <p className="truncate text-[13px] text-white/55">{t('competitive.servers.duels', { count: s.duels })} · {t('competitive.servers.last')} {last}</p>
          </div>
        </div>
      }
      footer={
        <button onClick={onFilter} className="press h-12 w-full rounded-2xl bg-[#4B3FCF] text-[16px] font-semibold text-white">
          {filtered ? t('competitive.servers.all') : t('competitive.servers.filter')}
        </button>
      }
      bodyClassName="flex flex-col gap-6 px-5 pb-5"
    >
      <div className="flex flex-col items-center gap-3 pt-2">
        <span className="text-[13px] font-semibold uppercase tracking-[0.08em] text-white/55">{t('competitive.servers.avgScore')}</span>
        <div className="flex items-baseline gap-1.5">
          <span className="text-[60px] font-black leading-none tabular-nums" style={{ color: `rgb(${color})`, textShadow: `0 0 26px rgb(${color} / 0.55)` }}>
            {s.avgScore}
          </span>
          <span className="text-[17px] font-bold text-white/45">/ 500</span>
        </div>
        <Gauge value={s.avgScore / 500} color={color} className="h-2 w-full max-w-[280px]" />
      </div>
      <div className="grid grid-cols-3 gap-2">
        <Figure label={t('competitive.servers.winRate')} value={`${winRate} %`}>
          <div className="mt-2 flex h-1.5 w-full overflow-hidden rounded-full bg-white/[0.08]">
            <div className="h-full bg-success" style={{ width: `${played ? (s.wins / played) * 100 : 0}%` }} />
            <div className="h-full bg-danger" style={{ width: `${played ? (s.losses / played) * 100 : 0}%` }} />
          </div>
        </Figure>
        <Figure label={t('competitive.servers.record')} value={t('competitive.stats.record', { wins: s.wins, losses: s.losses })} />
        <Figure label="RR" value={`${s.rr > 0 ? '+' : ''}${s.rr}`} tone={rrTone} />
      </div>
    </Sheet>
  )
}

function Figure({ label, value, tone, children }: { label: string; value: string; tone?: string; children?: ReactNode }) {
  return (
    <div className={`flex min-w-0 flex-col items-center justify-center px-2 py-3.5 text-center ${CARD}`}>
      <span className="max-w-full truncate text-[13px] font-semibold text-white/55">{label}</span>
      <span className="mt-1.5 max-w-full truncate text-[20px] font-black leading-none tabular-nums" style={tone ? { color: tone } : undefined}>{value}</span>
      {children}
    </div>
  )
}

// ── Statistiques ─────────────────────────────────────────────────────────

/**
 * Les chiffres du mode : taux de victoire en tête, puis note moyenne et série
 * côte à côte, le meilleur serveur, les notes des derniers duels et le résumé.
 * Sur le graphique, on glisse le doigt d'une barre à l'autre pour lire chaque
 * duel : c'est le survol du PC.
 */
export function MobileStats({ duels }: { duels: DuelRow[] | null }) {
  const t = useT()
  if (!duels || duels.length === 0) return <Note>{!duels ? t('competitive.stats.soon') : t('competitive.history.empty')}</Note>

  const s = summarize(duels)
  const avgColor = scoreColor(s.avgScore)

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-col gap-3">
        <div className={`flex items-center gap-5 px-5 py-5 ${CARD}`}>
          <Ring value={s.winRate / 100} />
          <div className="flex min-w-0 flex-col gap-1.5">
            <span className="text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('competitive.stats.winRate')}</span>
            <span className="text-[34px] font-black leading-none tabular-nums">{s.winRate} %</span>
            <span className="text-[13px] tabular-nums text-white/55">
              <span style={{ color: `rgb(${WIN})` }}>{s.wins} {t('competitive.stats.winShort')}</span>
              {' · '}
              <span style={{ color: `rgb(${LOSS})` }}>{s.losses} {t('competitive.stats.lossShort')}</span>
              {s.draws > 0 && ` · ${s.draws} ${t('competitive.stats.drawShort')}`}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <Tile label={t('competitive.stats.avgScore')} topo={avgColor}>
            <div className="flex items-baseline gap-1">
              <span className="text-[32px] font-black leading-none tabular-nums" style={{ color: `rgb(${avgColor})`, textShadow: `0 0 16px rgb(${avgColor} / 0.45)` }}>{s.avgScore}</span>
              <span className="text-[13px] font-bold text-white/45">/ 500</span>
            </div>
            <Gauge value={s.avgScore / 500} color={avgColor} className="h-1.5" />
          </Tile>
          <Tile label={t('competitive.stats.currentStreak')} topo={s.currentStreak > 0 ? WIN : DRAW}>
            <div className="flex items-baseline gap-1.5">
              <span className="text-[32px] font-black leading-none tabular-nums" style={s.currentStreak > 0 ? { color: `rgb(${WIN})` } : undefined}>{s.currentStreak}</span>
              <span className="truncate text-[13px] text-white/55">{t('competitive.stats.wins')}</span>
            </div>
            <span className="text-[13px] text-white/55">{t('competitive.stats.bestStreakSub', { count: s.bestStreak })}</span>
          </Tile>
        </div>

        {/* Le meilleur serveur, sur toute la largeur : une adresse est longue. */}
        <div className={`flex items-center gap-3.5 px-4 py-3.5 ${CARD}`}>
          {s.bestServer ? (
            (() => {
              const c = scoreColor(s.bestServer.avgScore)
              return (
                <>
                  <div className="h-14 w-14 shrink-0 rounded-[22%] border p-1" style={{ borderColor: `rgb(${c} / 0.35)`, background: `rgb(${c} / 0.06)` }}>
                    <ServerAvatar server={s.bestServer.server} />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('competitive.stats.bestServer')}</p>
                    <p className="mt-0.5 truncate text-[15px] font-semibold text-white">{s.bestServer.server}</p>
                    <p className="text-[13px] text-white/55">{t('competitive.servers.duels', { count: s.bestServer.duels })}</p>
                  </div>
                  <span className="shrink-0 text-[26px] font-black tabular-nums" style={{ color: `rgb(${c})`, textShadow: `0 0 14px rgb(${c} / 0.4)` }}>{s.bestServer.avgScore}</span>
                </>
              )
            })()
          ) : (
            <div className="min-w-0">
              <p className="text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('competitive.stats.bestServer')}</p>
              <p className="mt-1 text-[15px] text-white/60">{t('competitive.stats.noServer')}</p>
            </div>
          )}
        </div>
      </div>

      <ScoreChart duels={duels} avg={s.avgScore} />

      <Section title={t('competitive.stats.summary')}>
        <div className={LIST}>
          <Row label={t('competitive.stats.bestScore')}>
            <span style={{ color: `rgb(${scoreColor(s.bestScore)})` }}>{s.bestScore}</span>
            <span className="ml-1 text-[13px] font-bold text-white/45">/ 500</span>
          </Row>
          <Row label={t('competitive.stats.played')}>{s.played}</Row>
          <Row label={t('competitive.stats.servers')}>{s.serverCount}</Row>
          <Row label={t('competitive.stats.avgDuration')}>{formatLength(s.avgDuration)}</Row>
          <Row label={t('competitive.stats.fastestWin')}>{s.fastestWin === null ? '—' : formatLength(s.fastestWin)}</Row>
          <Row label={t('competitive.stats.rival')}>
            {s.rival ? (
              <span className="flex min-w-0 items-baseline gap-1.5">
                <span className="min-w-0 truncate">{s.rival.name}</span>
                <span className="shrink-0 text-[13px] font-semibold text-white/55">{t('competitive.servers.duels', { count: s.rival.count })}</span>
              </span>
            ) : '—'}
          </Row>
        </div>
      </Section>
    </div>
  )
}

function Tile({ label, topo, children }: { label: string; topo: string; children: ReactNode }) {
  return (
    <div className="relative isolate flex min-w-0 flex-col gap-3 overflow-hidden rounded-2xl border bg-white/[0.04] px-4 py-4" style={{ borderColor: `rgb(${topo} / 0.3)` }}>
      <div aria-hidden className="absolute inset-0 -z-10">
        <TopoBackdrop color={topo} still />
        <div className="absolute inset-0" style={{ background: `radial-gradient(12rem 8rem at 30% 80%, rgb(${topo} / 0.16), transparent 70%)` }} />
      </div>
      {/* Sur deux lignes s'il le faut (« Durchschnittsnote ») : une demi-largeur est étroite. */}
      <span className="break-words text-[13px] font-semibold uppercase leading-tight tracking-[0.06em] text-white/55">{label}</span>
      {children}
    </div>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-h-[52px] items-center justify-between gap-4 px-4 py-2.5">
      <span className="min-w-0 text-[15px] text-white/75">{label}</span>
      <span className="flex min-w-0 shrink-0 items-baseline justify-end text-[15px] font-bold tabular-nums text-white [max-width:55%]">{children}</span>
    </div>
  )
}

function Ring({ value }: { value: number }) {
  const reduce = useReducedMotion()
  const r = 22
  const c = 2 * Math.PI * r
  return (
    <svg viewBox="0 0 56 56" className="h-[72px] w-[72px] shrink-0 -rotate-90">
      <circle cx="28" cy="28" r={r} fill="none" stroke={`rgb(${LOSS} / 0.35)`} strokeWidth="6" />
      <motion.circle
        cx="28" cy="28" r={r} fill="none" stroke={`rgb(${WIN})`} strokeWidth="6" strokeLinecap="round" strokeDasharray={c}
        initial={reduce ? false : { strokeDashoffset: c }}
        animate={{ strokeDashoffset: c * (1 - Math.min(1, value)) }}
        transition={GROW}
      />
    </svg>
  )
}

/**
 * Les notes des derniers duels. Le PC montre chaque duel au survol ; au doigt,
 * on pose le doigt sur le graphique et on glisse : la barre sous le doigt
 * s'allume et la ligne du dessus dit sa note et l'adversaire. `pan-y` laisse
 * le défilement vertical au navigateur.
 */
function ScoreChart({ duels, avg }: { duels: DuelRow[]; avg: number }) {
  const t = useT()
  const reduce = useReducedMotion()
  const recent = duels.slice(0, CHART_DUELS).reverse()
  const form = duels.slice(0, FORM_DUELS).reverse()
  const [picked, setPicked] = useState<number | null>(null)
  const area = useRef<HTMLDivElement>(null)
  const sel = picked !== null ? recent[picked] : undefined

  const pick = (clientX: number) => {
    const box = area.current?.getBoundingClientRect()
    if (!box || recent.length === 0) return
    const i = Math.floor(((clientX - box.left) / box.width) * recent.length)
    setPicked(Math.max(0, Math.min(recent.length - 1, i)))
  }

  return (
    <Section title={t('competitive.stats.chartTitle')}>
      <div className={`flex flex-col gap-4 px-4 py-4 ${CARD}`}>
        {/* La ligne de lecture : la consigne, ou le duel sous le doigt. Une
            hauteur fixe de deux lignes (la consigne en prend deux en
            allemand, polonais et russe), pour que le graphique ne saute pas. */}
        <p className="line-clamp-2 h-[36px] text-[13px] leading-[18px] text-white/55">
          {sel ? (
            <>
              <span className="font-black tabular-nums" style={{ color: `rgb(${scoreColor(sel.score)})` }}>{sel.score}</span>
              {' · '}
              <span style={{ color: `rgb(${RESULT_COLOR[sel.result]})` }}>{t(`competitive.history.${sel.result}`)}</span>
              {' · '}
              {t('competitive.history.vs', { name: sel.opponent })}
            </>
          ) : (
            t('competitive.stats.chartTouch')
          )}
        </p>

        <div
          ref={area}
          className="relative h-[160px] touch-pan-y select-none"
          onPointerDown={(e) => pick(e.clientX)}
          onPointerMove={(e) => {
            if (e.pointerType === 'mouse' && e.buttons === 0) return
            pick(e.clientX)
          }}
        >
          <div className="pointer-events-none absolute inset-x-0 z-10 border-t border-dashed border-white/30" style={{ bottom: `${(avg / 500) * 100}%` }} />
          <div className="absolute inset-0 flex items-end gap-[3px]">
            {recent.map((d, i) => {
              const c = scoreColor(d.score)
              const on = picked === i
              return (
                <div key={d.id} className="flex h-full min-w-0 flex-1 items-end">
                  <motion.div
                    className="w-full origin-bottom rounded-t-[3px] transition-opacity duration-100"
                    style={{
                      height: `${(d.score / 500) * 100}%`,
                      background: `linear-gradient(180deg, rgb(${c}), rgb(${c} / 0.35))`,
                      opacity: picked !== null ? (on ? 1 : 0.3) : d.result === 'win' ? 1 : 0.55,
                      boxShadow: on ? `0 0 12px rgb(${c} / 0.7)` : undefined,
                    }}
                    initial={reduce ? false : { scaleY: 0 }}
                    animate={{ scaleY: 1 }}
                    transition={GROW}
                  />
                </div>
              )
            })}
          </div>
        </div>

        <div className="flex flex-col gap-2 border-t border-white/[0.06] pt-3">
          <span className="text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('competitive.stats.form')}</span>
          <div className="grid grid-cols-10 gap-1">
            {form.map((d) => {
              const c = RESULT_COLOR[d.result]
              return (
                <span key={d.id} className="flex h-7 items-center justify-center rounded-md text-[13px] font-black" style={{ background: `rgb(${c} / 0.16)`, color: `rgb(${c})` }}>
                  {t(d.result === 'win' ? 'competitive.stats.winShort' : d.result === 'loss' ? 'competitive.stats.lossShort' : 'competitive.stats.drawShort')}
                </span>
              )
            })}
          </div>
        </div>
      </div>
    </Section>
  )
}

// ── Saison ───────────────────────────────────────────────────────────────

const CELLS = SEASON_GRID * SEASON_GRID

/**
 * La grille de la saison : le rang de la saison en tête, la grille dans sa
 * bordure sur toute la largeur, puis le niveau de bordure et l'affichage au
 * profil. Une case se lit en la touchant (le PC la lit au survol).
 */
export function MobileSeason({ wins, count, season, current, mode, readOnly }: {
  wins: number[] | null
  count?: number
  season: Season | null
  current?: { division: number; placed: boolean }
  mode: ModeKey
  readOnly: boolean
}) {
  const t = useT()
  const rankName = useRankName()
  const onProfile = useSeasonOnProfile(mode)
  const [showMax, setShowMax] = useState(false)

  if (wins === null) return <Note>{t('competitive.season.soon')}</Note>

  const best = [...wins].sort((a, b) => b - a).slice(0, CELLS)
  const bestWin = best[0] ?? null
  const top = current?.placed ? Math.max(bestWin ?? 0, current.division) : bestWin
  const color = top !== null ? TIER_COLORS[tierOf(top)] : '160 150 190'
  const total = Math.max(count ?? 0, wins.length)
  const level = borderLevel(total)
  const maxed = level >= BORDER_LEVELS
  const levelProgress = maxed ? 1 : (total - (level - 1) * WINS_PER_BORDER) / WINS_PER_BORDER
  const daysLeft = season?.end ? Math.max(0, Math.ceil((season.end - Date.now()) / 86_400_000)) : null

  return (
    <div className="flex flex-col gap-8">
      {/* Pas d'`overflow-hidden` sur la carte : la bulle d'une case touchée
          se pose sous la grille et doit pouvoir en sortir. Le fond, lui, est
          rogné dans son propre cadre. */}
      <div className="relative isolate flex flex-col items-center rounded-[24px] border border-white/[0.08] px-4 pb-6 pt-5 text-center">
        <div aria-hidden className="absolute inset-0 -z-10 overflow-hidden rounded-[24px] bg-white/[0.03] opacity-70">
          <TopoBackdrop color={color} still />
        </div>

        {season && (
          <p className="text-[13px] font-semibold uppercase tracking-[0.1em] text-white/55">
            {t('competitive.pass.season', { number: season.number, name: season.name })}
            {daysLeft !== null && <span className="text-white/75"> · {t('competitive.season.daysLeft', { count: daysLeft })}</span>}
          </p>
        )}
        <div className="mt-2 flex items-center gap-1">
          <p className="text-[32px] font-black uppercase leading-none tracking-[-0.01em]" style={{ color: `rgb(${color})`, textShadow: `0 0 26px rgb(${color} / 0.35)` }}>
            {top !== null ? rankName(top, true) : t('competitive.rank.unranked')}
          </p>
          <button onClick={() => setShowMax(true)} aria-label={t('competitive.season.maxTitle')} className="press flex h-11 w-11 shrink-0 items-center justify-center">
            <span className="flex h-7 w-7 items-center justify-center rounded-full border border-white/35 text-[14px] font-bold text-white/80">i</span>
          </button>
        </div>

        <div className="mt-3">
          <SeasonFrame level={level} accent={color} size="min(calc(100vw - 64px), 420px)">
            <SeasonGrid wins={best} />
          </SeasonFrame>
        </div>
        <p className="mt-4 text-[13px] text-white/55">{t('competitive.season.touchHint')}</p>
      </div>

      <Section title={t('competitive.season.borderTitle')} aside={<span className="shrink-0 text-[13px] font-semibold tabular-nums text-white/75">{t('competitive.season.levelOf', { level, max: BORDER_LEVELS })}</span>}>
        <div className={`flex flex-col gap-4 px-4 py-4 ${CARD}`}>
          <div className="flex flex-col gap-2">
            <Gauge value={levelProgress} color="255 255 255" className="h-2" />
            <p className="text-[13px] tabular-nums text-white/60">
              {maxed ? t('competitive.season.maxed') : t('competitive.season.progress', { wins: total, next: level * WINS_PER_BORDER })}
            </p>
          </div>
          <div className="grid grid-cols-5 items-end gap-1">
            {Array.from({ length: BORDER_LEVELS }, (_, i) => {
              const l = i + 1
              return (
                <div key={l} className={`flex min-w-0 flex-col items-center gap-1.5 ${l <= level ? '' : 'opacity-30'}`}>
                  <SeasonFrame level={l} accent={color} size="min(52px, calc((100vw - 96px) / 5 - 6px))" still>
                    <div className="h-full w-full rounded-[2px] bg-[rgb(10_10_16)]" />
                  </SeasonFrame>
                  <span className={`text-[13px] font-semibold tabular-nums ${l === level ? 'text-white' : 'text-white/55'}`}>
                    {t('competitive.season.levelAt', { count: i * WINS_PER_BORDER })}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      </Section>

      {!readOnly && (
        // Toucher la ligne bascule l'interrupteur : le libellé vise le bouton.
        <label className={`flex min-h-[60px] cursor-pointer items-center justify-between gap-4 px-4 py-3 ${CARD}`}>
          <span className="text-[15px] leading-snug text-white/85">{t('competitive.season.showOnProfile')}</span>
          <span className={`shrink-0 ${onProfile.on === null ? 'pointer-events-none opacity-40' : ''}`}>
            <Toggle checked={onProfile.on === true} onChange={() => void onProfile.toggle()} />
          </span>
        </label>
      )}

      <Sheet open={showMax} onClose={() => setShowMax(false)} label={t('competitive.season.maxTitle')} header={<SheetTitle>{t('competitive.season.maxTitle')}</SheetTitle>} bodyClassName="flex flex-col items-center gap-5 px-5 pb-5">
        <SeasonFrame level={BORDER_LEVELS} accent={TIER_COLORS.champion} size="min(300px, calc(100vw - 80px))">
          <SeasonGrid wins={Array<number>(CELLS).fill(CHAMPION)} instant />
        </SeasonFrame>
        <div className="text-center">
          <p className="text-[28px] font-black uppercase leading-none" style={{ color: `rgb(${TIER_COLORS.champion})`, textShadow: `0 0 24px rgb(${TIER_COLORS.champion} / 0.45)` }}>
            {rankName(CHAMPION, true)}
          </p>
          <p className="mt-2 text-[13px] font-semibold uppercase tracking-[0.05em] text-white/65">
            {t('competitive.season.levelOf', { level: BORDER_LEVELS, max: BORDER_LEVELS })} · {t('competitive.season.levelAt', { count: (BORDER_LEVELS - 1) * WINS_PER_BORDER })}
          </p>
        </div>
        <p className="text-center text-[15px] leading-relaxed text-white/80">{t('competitive.season.maxText')}</p>
        <p className="text-center text-[13px] leading-relaxed text-white/55">{t('competitive.season.explain')}</p>
      </Sheet>
    </div>
  )
}

// ── Classement ───────────────────────────────────────────────────────────

/** Le classement : la portée (Top 15 / Amis), le podium, puis la liste. */
export function MobileLeaderboard({ rows: globalRows, friendRows, placed, subject }: {
  rows: LeaderboardRow[] | null
  friendRows: LeaderboardRow[] | null
  placed: boolean
  subject?: string
}) {
  const t = useT()
  const [scope, setScope] = useState<'global' | 'friends'>('global')
  const friends = scope === 'friends' && !subject
  const rows = friends ? friendRows : globalRows
  const mark = subject ? t('competitive.leaderboard.friend') : t('competitive.leaderboard.you')

  const top = rows?.filter((r) => r.position <= 15) ?? []
  const me = rows?.find((r) => r.me)
  const pinned = friends && me && me.position > 15 ? me : null

  return (
    <div className="flex flex-col gap-6">
      {!subject && (
        <div className="grid grid-cols-2 gap-1 rounded-2xl bg-white/[0.04] p-1">
          {(['global', 'friends'] as const).map((s) => (
            <button
              key={s}
              onClick={() => setScope(s)}
              aria-pressed={scope === s}
              className={`h-10 rounded-xl text-[15px] font-semibold transition-colors duration-150 ${scope === s ? 'bg-[rgba(75,63,207,0.35)] text-white' : 'text-white/60'}`}
            >
              {t(`competitive.leaderboard.scope.${s}`)}
            </button>
          ))}
        </div>
      )}

      {rows === null ? (
        <Note>{t('competitive.leaderboard.soon')}</Note>
      ) : (
        <>
          <Podium key={scope} rows={rows.filter((r) => r.position <= 3)} />

          <div className="flex flex-col gap-3">
            {friends && rows.length === 0 ? (
              <Note>{t('competitive.leaderboard.noFriends')}</Note>
            ) : (
              <div className={LIST}>
                {top.map((r) => <BoardLine key={`${r.position}-${r.name}`} row={r} mark={mark} />)}
              </div>
            )}
            {pinned && (
              <div className={LIST}>
                <BoardLine row={pinned} mark={mark} />
              </div>
            )}
            {!placed && (
              <p className="px-1 text-[13px] leading-relaxed text-white/55">
                {subject ? t('competitive.leaderboard.notPlacedFriend', { name: subject }) : t('competitive.leaderboard.notPlaced')}
              </p>
            )}
          </div>
        </>
      )}
    </div>
  )
}

/** Les trois premiers, 2e · 1re · 3e ; une place libre garde sa marche avec un « ? ». */
function Podium({ rows }: { rows: LeaderboardRow[] }) {
  const rankName = useRankName()
  const reduce = useReducedMotion()
  const step = (p: number) => (p === 1 ? 92 : p === 2 ? 72 : 58)

  return (
    <div className={`flex items-end gap-2 px-3 pt-5 ${CARD}`}>
      {[2, 1, 3].map((position) => {
        const medal = PODIUM[position - 1]
        const r = rows.find((row) => row.position === position)
        const face = position === 1 ? 56 : 44
        return (
          <div key={position} className="flex min-w-0 flex-1 flex-col items-center">
            {r ? (
              <>
                <SkinFace dataUri={playerSkinSrc(r.uuid, r.name)} size={face} className="rounded-lg" />
                <p className={`mt-2 max-w-full truncate font-bold ${position === 1 ? 'text-[15px]' : 'text-[14px]'} ${r.me ? 'text-[#a29bfe]' : 'text-white'}`}>{r.name}</p>
                <span className="mt-0.5 max-w-full truncate text-[13px] font-semibold" style={{ color: `rgb(${TIER_COLORS[tierOf(r.division)]})` }}>
                  {rankName(r.division, true)}
                </span>
              </>
            ) : (
              <>
                <div className="flex items-center justify-center rounded-lg border border-dashed border-white/15 bg-white/[0.03] font-black text-white/30" style={{ width: face, height: face, fontSize: face * 0.42 }}>?</div>
                <p className="mt-2 text-[14px] font-bold text-white/25">?</p>
                <span className="mt-0.5 h-[18px]" />
              </>
            )}
            <motion.div
              className="mt-3 flex w-full origin-bottom flex-col items-center rounded-t-xl pt-2"
              style={{
                height: step(position),
                background: `linear-gradient(180deg, rgb(${medal} / ${r ? 0.28 : 0.1}), rgb(${medal} / 0.03))`,
                borderTop: `2px ${r ? 'solid' : 'dashed'} rgb(${medal} / ${r ? 0.7 : 0.3})`,
              }}
              initial={reduce ? false : { scaleY: 0.4, opacity: 0 }}
              animate={{ scaleY: 1, opacity: 1 }}
              transition={{ duration: 0.45, ease: [0.23, 1, 0.32, 1] }}
            >
              <span className="text-[26px] font-black leading-none" style={{ color: `rgb(${medal} / ${r ? 1 : 0.35})`, textShadow: r ? `0 0 16px rgb(${medal} / 0.5)` : undefined }}>{position}</span>
              {r && <span className="mt-1 text-[13px] tabular-nums text-white/65">{r.winRate} %</span>}
            </motion.div>
          </div>
        )
      })}
    </div>
  )
}

function BoardLine({ row: r, mark }: { row: LeaderboardRow; mark: string }) {
  const rankName = useRankName()
  return (
    <div className={`flex min-h-[64px] items-center gap-3 px-4 py-2.5 ${r.me ? 'bg-accent/[0.14]' : ''}`}>
      <span className="min-w-[28px] shrink-0 text-right text-[15px] font-black tabular-nums" style={{ color: r.position <= 3 ? `rgb(${PODIUM[r.position - 1]})` : 'rgb(255 255 255 / 0.45)' }}>
        {r.position}
      </span>
      <SkinFace dataUri={playerSkinSrc(r.uuid, r.name)} size={36} className="shrink-0 rounded-md" />
      <div className="min-w-0 flex-1">
        <p className="flex min-w-0 items-baseline gap-2">
          <span className="truncate text-[15px] font-semibold text-white">{r.name}</span>
          {r.me && <span className="shrink-0 text-[13px] font-bold uppercase tracking-[0.04em] text-[#a29bfe]">{mark}</span>}
        </p>
        <p className="truncate text-[13px]">
          <span className="font-semibold" style={{ color: `rgb(${TIER_COLORS[tierOf(r.division)]})` }}>{rankName(r.division, true)}</span>
          <span className="text-white/55"> · {r.winRate} %</span>
        </p>
      </div>
      <RankTile division={r.division} placed size={30} />
    </div>
  )
}
