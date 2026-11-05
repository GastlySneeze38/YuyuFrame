import { useMemo, useRef, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { ScreenHeader } from '@/mobile/ui/ScreenHeader'
import { PushedMoreButton } from '@/mobile/ui/PushedScreen'
import { ActionSheet, SHEET_ICONS } from '@/mobile/ui/ActionSheet'
import { Sheet, SheetTitle } from '@/mobile/ui/Sheet'
import { CARD, CheckRow, LIST, Note, Section, Segmented } from '@/mobile/ui/kit'
import { RANGES, EMPTY_FILTERS, type StatsFilters } from '@/components/stats/StatsToolbar'
import { build, TONES } from '@/components/stats/StatCards'
import { LEVELS, levelOf, weekdayOf } from '@/components/stats/ActivityCalendar'
import { SORTS, sortBy } from '@/components/stats/InstanceRanking'
import { dayOf, groupByDay } from '@/components/stats/SessionList'
import { VERSION_COLORS } from '@/components/stats/Breakdown'
import { formatDuration, formatTime } from '@/lib/format'
import { loaderColor } from '@/lib/loader'
import { STAT_CARD_IDS, type StatCardId, type StatsSort } from '@/stores/useStore'
import type { DayStat, InstanceStat, SessionEntry, StatBucket, StatsData } from '@/types/stats'

/**
 * Les statistiques de jeu sur téléphone. Mêmes données et mêmes réglages que
 * le PC (période, filtres, cartes choisies, tri, effacement) ; la page de
 * `pages/Stats.tsx` garde l'état et passe tout ici.
 *
 * - la période en commutateur, les filtres dans une feuille (bouton
 *   « Filtres » avec le nombre de filtres actifs) ;
 * - les cartes deux par deux ; on les choisit dans une feuille, ouverte par
 *   « … » en haut à droite, qui porte aussi l'effacement de l'historique ;
 * - le calendrier montre les dernières semaines qui tiennent dans la largeur
 *   (pas de défilement de côté) ; les graphiques se lisent au doigt.
 *
 * Pas d'animation en boucle : le point « en cours » est fixe.
 */
export function MobileStats({ data, loading, rangeDays, onRange, filters, onFilters, cards, onToggleCard, sort, onSort, onClear }: {
  data: StatsData | null
  loading: boolean
  rangeDays: number
  onRange: (d: number) => void
  filters: StatsFilters
  onFilters: (f: StatsFilters) => void
  cards: StatCardId[]
  onToggleCard: (id: StatCardId) => void
  sort: StatsSort
  onSort: (s: StatsSort) => void
  onClear: () => void
}) {
  const t = useT()
  const [menu, setMenu] = useState(false)
  const [picking, setPicking] = useState(false)
  const [filtering, setFiltering] = useState(false)
  const active = [filters.instanceId, filters.loader, filters.mcVersion].filter(Boolean).length
  const empty = data !== null && data.totals.sessions === 0 && data.known_instances.length === 0

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D] text-txt-primary">
      <ScreenHeader title={t('stats.title')} subtitle={t('stats.subtitle')} fallback="/features" right={data && <PushedMoreButton label={t('stats.mobile.more')} onClick={() => setMenu(true)} />} />

      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(32px,env(safe-area-inset-bottom))] pt-4">
        <div className="flex flex-col gap-3">
          <Segmented
            value={rangeDays}
            onChange={onRange}
            options={RANGES.map((d) => ({ value: d as number, label: d === 0 ? t('stats.range.all') : t('stats.range.days', { count: d }) }))}
          />
          <div className="flex gap-2">
            <button
              onClick={() => setFiltering(true)}
              className={`press flex h-11 min-w-0 flex-1 items-center justify-center gap-2 rounded-xl border text-[15px] font-semibold ${
                active ? 'border-accent/45 bg-accent/[0.12] text-white' : 'border-white/[0.08] bg-white/[0.04] text-white/80'
              }`}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" className="h-[18px] w-[18px] shrink-0"><path d="M4 6h16M7 12h10M10 18h4" /></svg>
              <span className="truncate">{t('stats.mobile.filters')}</span>
              {active > 0 && <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-accent px-1.5 text-[12px] font-bold tabular-nums text-white">{active}</span>}
            </button>
            {active > 0 && (
              <button onClick={() => onFilters(EMPTY_FILTERS)} className="press h-11 shrink-0 rounded-xl border border-white/[0.08] px-4 text-[15px] font-semibold text-white/70">
                {t('stats.filter.reset')}
              </button>
            )}
          </div>
        </div>

        <div className="mt-8">
          {loading && data === null ? (
            <div className="flex justify-center py-16"><ButtonSpinner size={28} color="#818cf8" trackColor="rgba(255,255,255,0.08)" /></div>
          ) : !data ? (
            <Note>{t('stats.cannotLoadStats')}</Note>
          ) : empty ? (
            <div className={`${CARD} flex flex-col gap-1.5 px-5 py-8 text-center`}>
              <p className="text-[16px] font-semibold text-white">{t('stats.emptyTitle')}</p>
              <p className="text-[15px] leading-relaxed text-white/60">{t('stats.emptyText')}</p>
            </div>
          ) : (
            <div className="flex flex-col gap-8">
              {data.running.length > 0 && (
                <div className="flex items-center gap-3 rounded-2xl border border-success/30 bg-success/[0.07] px-4 py-3">
                  <span className="h-2 w-2 shrink-0 rounded-full bg-success" />
                  <span className="min-w-0 text-[15px] font-semibold text-success">
                    {t('stats.liveNow', { names: data.running.map((r) => r.instance_name).join(', ') })}
                  </span>
                </div>
              )}

              <div className="grid grid-cols-2 gap-3">
                {cards.map((id) => {
                  const card = build(id, data, t)
                  return (
                    <div key={id} className={`relative flex min-w-0 flex-col gap-1.5 overflow-hidden px-4 py-3.5 ${CARD}`}>
                      <span className={`pointer-events-none absolute -right-8 -top-10 h-24 w-24 rounded-full bg-gradient-to-br ${TONES[card.tone].glow} to-transparent blur-2xl`} />
                      <span className="text-[13px] font-semibold leading-tight text-white/55">{card.label}</span>
                      <span className={`truncate text-[24px] font-black leading-none tracking-[-0.02em] ${TONES[card.tone].text}`}>{card.value}</span>
                      <span className="line-clamp-2 text-[13px] leading-snug text-white/50">{card.sub}</span>
                    </div>
                  )
                })}
              </div>

              <Calendar daily={data.daily} />
              <Ranking
                instances={data.per_instance}
                sort={sort}
                onSort={onSort}
                picked={filters.instanceId}
                onPick={(id) => onFilters({ ...filters, instanceId: filters.instanceId === id ? '' : id })}
              />
              <Hourly hourly={data.hourly} />
              <Breakdown loaders={data.per_loader} versions={data.per_version} />
              <Sessions sessions={data.recent} />
            </div>
          )}
        </div>
      </div>

      <ActionSheet
        open={menu}
        onClose={() => setMenu(false)}
        header={<span className="truncate text-[17px] font-bold text-white">{t('stats.title')}</span>}
        actions={[
          { id: 'cards', label: t('stats.customize'), icon: SHEET_ICONS.settings, onSelect: () => setPicking(true) },
          { id: 'clear', label: t('stats.clear.action'), icon: SHEET_ICONS.delete, danger: true, confirm: t('stats.clear.text'), onSelect: onClear },
        ]}
      />

      {data && (
        <Sheet open={picking} onClose={() => setPicking(false)} label={t('stats.customize')} header={<SheetTitle>{t('stats.mobile.cardsTitle')}</SheetTitle>} bodyClassName="flex flex-col gap-1 px-2 pb-3">
          {STAT_CARD_IDS.map((id) => (
            <CheckRow key={id} label={build(id, data, t).label} checked={cards.includes(id)} onClick={() => onToggleCard(id)} />
          ))}
        </Sheet>
      )}

      {data && (
        <Sheet
          open={filtering}
          onClose={() => setFiltering(false)}
          label={t('stats.mobile.filters')}
          header={<SheetTitle action={active > 0 && <button onClick={() => onFilters(EMPTY_FILTERS)} className="press h-11 shrink-0 px-2 text-[15px] font-semibold text-[#a29bfe]">{t('stats.filter.reset')}</button>}>{t('stats.mobile.filters')}</SheetTitle>}
          bodyClassName="flex flex-col gap-6 px-2 pb-3"
        >
          <FilterGroup
            title={t('stats.mobile.instance')}
            all={t('stats.filter.allInstances')}
            value={filters.instanceId}
            options={data.known_instances.map((i) => ({ value: i.id, label: i.name }))}
            onChange={(instanceId) => onFilters({ ...filters, instanceId })}
          />
          <FilterGroup
            title={t('stats.mobile.loader')}
            all={t('stats.filter.allLoaders')}
            value={filters.loader}
            options={data.known_loaders.map((l) => ({ value: l, label: l }))}
            onChange={(loader) => onFilters({ ...filters, loader })}
          />
          <FilterGroup
            title={t('stats.mobile.version')}
            all={t('stats.filter.allVersions')}
            value={filters.mcVersion}
            options={data.known_versions.map((v) => ({ value: v, label: v }))}
            onChange={(mcVersion) => onFilters({ ...filters, mcVersion })}
          />
        </Sheet>
      )}
    </div>
  )
}

function FilterGroup({ title, all, value, options, onChange }: {
  title: string
  all: string
  value: string
  options: { value: string; label: string }[]
  onChange: (v: string) => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <p className="px-3 pb-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{title}</p>
      <CheckRow label={all} checked={value === ''} onClick={() => onChange('')} />
      {options.map((o) => <CheckRow key={o.value} label={o.label} checked={value === o.value} onClick={() => onChange(o.value)} />)}
    </div>
  )
}

/** Combien de semaines le calendrier montre : ce qui tient dans un téléphone à cases lisibles. */
const WEEKS = 15

function shortDate(iso: string) {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'long' })
}

/**
 * Le calendrier : les quinze dernières semaines, une colonne chacune, sur
 * toute la largeur. Le PC montre toute la période et défile de côté quand
 * elle est longue ; ici, rien ne doit se découvrir de côté, donc on garde la
 * fin de la période. Toucher une case dit son jour et son temps de jeu.
 */
function Calendar({ daily }: { daily: DayStat[] }) {
  const t = useT()
  const [picked, setPicked] = useState<DayStat | null>(null)

  const { weeks, max, months } = useMemo(() => {
    const max = daily.reduce((m, d) => Math.max(m, d.secs), 0)
    const pad = daily.length > 0 ? weekdayOf(daily[0].date) : 0
    const cells: (DayStat | null)[] = [...Array(pad).fill(null), ...daily]
    const all: (DayStat | null)[][] = []
    for (let i = 0; i < cells.length; i += 7) all.push(cells.slice(i, i + 7))
    const weeks = all.slice(-WEEKS)
    const months: (string | null)[] = []
    let last = ''
    for (const week of weeks) {
      const first = week.find((d): d is DayStat => d !== null)
      const label = first ? new Date(`${first.date}T00:00:00`).toLocaleDateString(undefined, { month: 'short' }) : ''
      months.push(label && label !== last ? label : null)
      if (label) last = label
    }
    return { weeks, max, months }
  }, [daily])

  const byDate = useMemo(() => new Map(daily.map((d) => [d.date, d])), [daily])
  const pickAt = (x: number, y: number) => {
    const date = (document.elementFromPoint(x, y) as HTMLElement | null)?.dataset.day
    const day = date ? byDate.get(date) : undefined
    if (day) setPicked(day)
  }

  return (
    <Section title={t('stats.calendar.title')}>
      <div className={`flex flex-col gap-3 px-4 py-4 ${CARD}`}>
        <p className="min-h-[18px] text-[13px] text-white/60">
          {picked
            ? picked.secs > 0
              ? t('stats.calendar.dayDetail', { date: shortDate(picked.date), duration: formatDuration(picked.secs) })
              : t('stats.calendar.dayEmpty', { date: shortDate(picked.date) })
            : t('stats.mobile.calendarHint')}
        </p>
        {/* Une case fait une vingtaine de pixels : trop peu pour viser. On
            pose le doigt n'importe où sur la grille et on glisse — la case
            sous le doigt se lit, comme sur les graphiques. */}
        <div
          className="grid touch-pan-y select-none gap-[4px]"
          style={{ gridTemplateColumns: `repeat(${WEEKS}, minmax(0, 1fr))` }}
          onPointerDown={(e) => pickAt(e.clientX, e.clientY)}
          onPointerMove={(e) => { if (!(e.pointerType === 'mouse' && e.buttons === 0)) pickAt(e.clientX, e.clientY) }}
        >
          {Array.from({ length: WEEKS }, (_, i) => (
            // Le nom du mois déborde sur les colonnes suivantes (il n'y en a
            // qu'un toutes les quatre ou cinq), jamais sur les deux dernières :
            // il sortirait de la carte.
            <span key={`m${i}`} className="h-[18px] overflow-visible whitespace-nowrap text-[13px] leading-[18px] text-white/50">{i < WEEKS - 2 ? months[i] ?? '' : ''}</span>
          ))}
          {Array.from({ length: 7 }, (_, row) =>
            Array.from({ length: WEEKS }, (_, col) => {
              const day = weeks[col]?.[row] ?? null
              if (!day) return <span key={`${row}-${col}`} className="aspect-square" />
              const on = picked?.date === day.date
              return (
                <span
                  key={day.date}
                  data-day={day.date}
                  className={`aspect-square rounded-[4px] ${LEVELS[levelOf(day.secs, max)]} ${on ? 'ring-2 ring-white' : ''}`}
                />
              )
            }),
          )}
        </div>
        <div className="flex items-center gap-1.5 self-end">
          <span className="text-[13px] text-white/45">{t('stats.calendar.less')}</span>
          {LEVELS.map((c, i) => <span key={i} className={`h-3 w-3 rounded-[3px] ${c}`} />)}
          <span className="text-[13px] text-white/45">{t('stats.calendar.more')}</span>
        </div>
      </div>
    </Section>
  )
}

/** Le classement des instances : le tri en commutateur, puis une ligne par instance (toucher = filtrer dessus). */
function Ranking({ instances, sort, onSort, picked, onPick }: {
  instances: InstanceStat[]
  sort: StatsSort
  onSort: (s: StatsSort) => void
  picked: string
  onPick: (id: string) => void
}) {
  const t = useT()
  const reduce = useReducedMotion()
  const [expanded, setExpanded] = useState(false)
  const ranked = sortBy(instances, sort)
  const max = Math.max(...ranked.map((i) => i.total_secs), 1)
  const shown = expanded ? ranked : ranked.slice(0, 6)

  return (
    <Section title={t('stats.ranking.title')}>
      <Segmented size="sm" value={sort} onChange={onSort} options={SORTS.map((s) => ({ value: s, label: t(`stats.ranking.sort.${s}`) }))} />
      {ranked.length === 0 ? (
        <Note>{t('stats.ranking.empty')}</Note>
      ) : (
        <div className={LIST}>
          {shown.map((inst, i) => (
            <button
              key={inst.instance_id}
              onClick={() => onPick(inst.instance_id)}
              aria-pressed={picked === inst.instance_id}
              className={`relative flex min-h-[60px] w-full items-center gap-3 overflow-hidden px-4 py-2.5 text-left active:bg-white/[0.04] ${picked === inst.instance_id ? 'bg-accent/[0.12]' : ''}`}
            >
              <motion.span
                aria-hidden
                className="absolute inset-y-0 left-0 origin-left bg-gradient-to-r from-accent/25 to-accent/5"
                style={{ width: `${Math.max(1.5, (inst.total_secs / max) * 100)}%` }}
                initial={reduce ? false : { scaleX: 0 }}
                animate={{ scaleX: 1 }}
                transition={{ duration: 0.5, ease: [0.23, 1, 0.32, 1] }}
              />
              <span className="relative w-5 shrink-0 text-[13px] font-bold tabular-nums text-white/45">{i + 1}</span>
              <span className="relative flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[15px] font-semibold text-white">{inst.instance_name}</span>
                <span className="truncate text-[13px] text-white/55">
                  <span className="font-semibold" style={{ color: loaderColor(inst.loader) }}>{inst.loader}</span> · {inst.mc_version}
                  {inst.crashed > 0 && <span className="text-danger"> · {t('stats.ranking.crashes', { count: inst.crashed })}</span>}
                </span>
              </span>
              <span className="relative shrink-0 text-[15px] font-bold tabular-nums text-accent-hover">{formatDuration(inst.total_secs)}</span>
            </button>
          ))}
        </div>
      )}
      {ranked.length > 6 && (
        <button onClick={() => setExpanded((v) => !v)} className="press h-11 self-center px-4 text-[15px] font-semibold text-[#a29bfe]">
          {expanded ? t('stats.ranking.showLess') : t('stats.ranking.showAll', { count: ranked.length - 6 })}
        </button>
      )}
    </Section>
  )
}

/** Les heures de jeu : on glisse le doigt sur les barres pour lire chaque heure. */
function Hourly({ hourly }: { hourly: number[] }) {
  const t = useT()
  const reduce = useReducedMotion()
  const [picked, setPicked] = useState<number | null>(null)
  const area = useRef<HTMLDivElement>(null)
  const max = Math.max(...hourly, 1)
  const total = hourly.reduce((a, b) => a + b, 0)
  const pick = (x: number) => {
    const box = area.current?.getBoundingClientRect()
    if (!box) return
    setPicked(Math.max(0, Math.min(23, Math.floor(((x - box.left) / box.width) * 24))))
  }

  return (
    <Section title={t('stats.hourly.title')}>
      <div className={`flex flex-col gap-3 px-4 py-4 ${CARD}`}>
        {total === 0 ? (
          <p className="py-4 text-center text-[15px] text-white/55">{t('stats.hourly.empty')}</p>
        ) : (
          <>
            <p className="line-clamp-2 min-h-[36px] text-[13px] leading-[18px] text-white/60">
              {picked !== null
                ? t('stats.hourly.detail', { hour: String(picked).padStart(2, '0'), duration: formatDuration(hourly[picked]) })
                : t('stats.mobile.hourlyHint')}
            </p>
            <div
              ref={area}
              className="flex h-28 touch-pan-y select-none items-end gap-[3px]"
              onPointerDown={(e) => pick(e.clientX)}
              onPointerMove={(e) => { if (!(e.pointerType === 'mouse' && e.buttons === 0)) pick(e.clientX) }}
            >
              {hourly.map((secs, hour) => (
                <motion.div
                  key={hour}
                  className={`flex-1 origin-bottom rounded-[3px] ${picked === hour ? 'bg-accent-hover' : secs > 0 ? 'bg-accent/45' : 'bg-white/[0.05]'}`}
                  style={{ height: `${Math.max(secs > 0 ? 6 : 2, (secs / max) * 100)}%` }}
                  initial={reduce ? false : { scaleY: 0 }}
                  animate={{ scaleY: 1 }}
                  transition={{ duration: 0.45, ease: [0.23, 1, 0.32, 1] }}
                />
              ))}
            </div>
            <div className="flex justify-between text-[13px] tabular-nums text-white/45">
              {[0, 6, 12, 18, 23].map((h) => <span key={h}>{h} h</span>)}
            </div>
          </>
        )}
      </div>
    </Section>
  )
}

function Breakdown({ loaders, versions }: { loaders: StatBucket[]; versions: StatBucket[] }) {
  const t = useT()
  if (loaders.length === 0 && versions.length === 0) return null
  return (
    <Section title={t('stats.breakdown.title')}>
      <div className={`flex flex-col gap-5 px-4 py-4 ${CARD}`}>
        <Bar buckets={loaders} label={t('stats.breakdown.loaders')} colorOf={(key) => loaderColor(key)} />
        <Bar buckets={versions} label={t('stats.breakdown.versions')} colorOf={(_, i) => VERSION_COLORS[i % VERSION_COLORS.length]} />
      </div>
    </Section>
  )
}

function Bar({ buckets, label, colorOf }: { buckets: StatBucket[]; label: string; colorOf: (key: string, i: number) => string }) {
  const t = useT()
  const total = buckets.reduce((s, b) => s + b.secs, 0)
  if (total === 0) return null
  const top = buckets.slice(0, 5)
  const rest = buckets.slice(5).reduce((s, b) => s + b.secs, 0)
  const parts = rest > 0 ? [...top, { key: t('stats.breakdown.others'), secs: rest, sessions: 0 }] : top
  return (
    <div className="flex flex-col gap-2.5">
      <span className="text-[13px] font-semibold text-white/70">{label}</span>
      <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-white/[0.06]">
        {parts.map((b, i) => <span key={b.key} style={{ width: `${(b.secs / total) * 100}%`, backgroundColor: colorOf(b.key, i) }} className="h-full" />)}
      </div>
      {/* Une ligne par part : à 13 px, une légende en ligne ne tiendrait pas. */}
      <div className="flex flex-col gap-1.5">
        {parts.map((b, i) => (
          <span key={b.key} className="flex items-center gap-2 text-[13px]">
            <span className="h-2.5 w-2.5 shrink-0 rounded-[3px]" style={{ backgroundColor: colorOf(b.key, i) }} />
            <span className="min-w-0 flex-1 truncate text-white/80">{b.key}</span>
            <span className="shrink-0 tabular-nums text-white/50">{formatDuration(b.secs)}</span>
          </span>
        ))}
      </div>
    </div>
  )
}

/** Les dernières parties, groupées par jour, comme sur PC. */
function Sessions({ sessions }: { sessions: SessionEntry[] }) {
  const t = useT()
  const navigate = useNavigate()
  const [expanded, setExpanded] = useState(false)
  const shown = expanded ? sessions : sessions.slice(0, 12)
  const groups = useMemo(() => groupByDay(shown), [shown])
  const today = dayOf(Date.now() / 1000).getTime()
  const labelOf = (date: Date) => {
    const key = date.getTime()
    if (key === today) return t('stats.sessions.today')
    if (key === today - 86_400_000) return t('stats.sessions.yesterday')
    return date.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })
  }

  return (
    <Section title={t('stats.sessions.title')}>
      {sessions.length === 0 ? (
        <Note>{t('stats.sessions.empty')}</Note>
      ) : (
        <div className="flex flex-col gap-4">
          {groups.map((g) => (
            <div key={g.key} className="flex flex-col gap-2">
              <div className="flex items-baseline justify-between gap-3 px-1">
                <span className="truncate text-[13px] font-semibold text-white/70 first-letter:uppercase">{labelOf(g.date)}</span>
                <span className="shrink-0 text-[13px] tabular-nums text-white/45">{t('stats.sessions.dayTotal', { count: g.items.length, duration: formatDuration(g.secs) })}</span>
              </div>
              <div className={LIST}>
                {g.items.map((s) => (
                  <div key={s.id} className={`flex min-h-[60px] items-center gap-3 px-4 py-2.5 ${s.running ? 'bg-success/[0.07]' : ''}`}>
                    <span className="w-11 shrink-0 text-[13px] tabular-nums text-white/50">{formatTime(s.started_at)}</span>
                    <span className="flex min-w-0 flex-1 flex-col">
                      <span className="truncate text-[15px] font-semibold text-white">{s.instance_name}</span>
                      <span className="truncate text-[13px] text-white/55">
                        <span className="font-semibold" style={{ color: loaderColor(s.loader) }}>{s.loader}</span> · {s.mc_version}
                        {s.running && <span className="font-semibold text-success"> · {t('stats.sessions.live')}</span>}
                        {s.recovered && !s.crashed && <span> · {t('stats.sessions.recovered')}</span>}
                      </span>
                    </span>
                    {s.crashed ? (
                      <button onClick={() => navigate('/support')} className="press flex h-11 shrink-0 flex-col items-end justify-center">
                        <span className="text-[15px] font-bold tabular-nums text-accent-hover">{formatDuration(s.duration_secs)}</span>
                        <span className="text-[13px] font-semibold text-danger">{t('stats.sessions.crashed')} ›</span>
                      </button>
                    ) : (
                      <span className={`shrink-0 text-[15px] font-bold tabular-nums ${s.running ? 'text-success' : 'text-accent-hover'}`}>
                        {s.recovered ? '≥ ' : ''}{formatDuration(s.duration_secs)}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
          {sessions.length > 12 && (
            <button onClick={() => setExpanded((v) => !v)} className="press h-11 self-center px-4 text-[15px] font-semibold text-[#a29bfe]">
              {expanded ? t('stats.sessions.showLess') : t('stats.sessions.showAll', { count: sessions.length - 12 })}
            </button>
          )}
        </div>
      )}
    </Section>
  )
}
