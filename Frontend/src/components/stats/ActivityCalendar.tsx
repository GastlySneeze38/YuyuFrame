import { useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { SNAP } from '@/lib/motion'
import { formatDuration } from '@/lib/format'
import { useT } from '@/i18n'
import type { DayStat } from '@/types/stats'

/**
 * Calendrier d'activité.
 *
 * Remplace les quatorze barres d'avant, qui ne tenaient que deux semaines et
 * attribuaient une soirée entière au jour où elle avait commencé — une partie
 * de 23 h à 2 h comptait trois heures la veille. Le découpage à minuit est
 * fait côté Rust ; ici on ne fait que dessiner.
 *
 * Une case par jour, en colonnes de semaines : on y lit d'un coup d'œil les
 * habitudes (les week-ends, les séries, les trous) qu'une série de barres
 * chronologiques n'a jamais montrées.
 */

/** Cinq niveaux : rien, puis quatre intensités relatives au meilleur jour. */
const LEVELS = [
  'bg-[rgba(255,255,255,0.04)]',
  'bg-accent/25',
  'bg-accent/45',
  'bg-accent/70',
  'bg-accent-hover',
]

function levelOf(secs: number, max: number): number {
  if (secs <= 0) return 0
  // Échelle sur la racine plutôt que linéaire : sans ça, une seule très
  // grosse journée écrase toutes les autres au niveau 1 et le calendrier
  // devient illisible.
  const ratio = Math.sqrt(secs) / Math.sqrt(Math.max(max, 1))
  return Math.min(4, Math.max(1, Math.ceil(ratio * 4)))
}

/** Jour de la semaine, lundi = 0. */
function weekdayOf(iso: string): number {
  return (new Date(`${iso}T00:00:00`).getDay() + 6) % 7
}

export function ActivityCalendar({ daily }: { daily: DayStat[] }) {
  const t = useT()
  const [hover, setHover] = useState<DayStat | null>(null)

  const { weeks, max, months } = useMemo(() => {
    const max = daily.reduce((m, d) => Math.max(m, d.secs), 0)
    // Remplit le début de la première semaine pour que les lignes
    // correspondent toujours au même jour de la semaine.
    const pad = daily.length > 0 ? weekdayOf(daily[0].date) : 0
    const cells: (DayStat | null)[] = [...Array(pad).fill(null), ...daily]
    const weeks: (DayStat | null)[][] = []
    for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7))

    // Étiquette de mois posée sur la première semaine qui en contient le 1er
    // — poser une étiquette par semaine les rendrait illisibles.
    const months: { index: number; label: string }[] = []
    weeks.forEach((week, index) => {
      const first = week.find((d): d is DayStat => d !== null)
      if (!first) return
      const date = new Date(`${first.date}T00:00:00`)
      const label = date.toLocaleDateString(undefined, { month: 'short' })
      if (months.length === 0 || months[months.length - 1].label !== label) {
        months.push({ index, label })
      }
    })
    return { weeks, max, months }
  }, [daily])

  const shown = hover
  const weekdays = [0, 1, 2, 3, 4, 5, 6].map((i) => {
    // 2024-01-01 était un lundi : base fiable pour nommer les jours dans la
    // langue du système sans table en dur.
    const d = new Date(2024, 0, 1 + i)
    return d.toLocaleDateString(undefined, { weekday: 'short' }).slice(0, 2)
  })

  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('stats.calendar.title')}</span>
        <motion.span
          key={shown?.date ?? 'none'}
          initial={{ opacity: 0, y: -3 }}
          animate={{ opacity: 1, y: 0 }}
          transition={SNAP}
          className="text-[11px] text-txt-secondary"
        >
          {shown
            ? shown.secs > 0
              ? t('stats.calendar.dayDetail', {
                  date: new Date(`${shown.date}T00:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'long' }),
                  duration: formatDuration(shown.secs),
                })
              : t('stats.calendar.dayEmpty', {
                  date: new Date(`${shown.date}T00:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'long' }),
                })
            : t('stats.calendar.hint')}
        </motion.span>
      </div>

      <div className="flex gap-2 overflow-x-auto pb-1">
        <div className="flex shrink-0 flex-col gap-[3px] pt-[15px]">
          {weekdays.map((label, i) => (
            // Une étiquette sur deux : sept lignes de texte à cette taille
            // feraient plus de bruit que de repère.
            <span key={i} className="h-[11px] text-[8px] leading-[11px] text-txt-muted">
              {i % 2 === 1 ? label : ''}
            </span>
          ))}
        </div>

        <div className="flex flex-col gap-1">
          <div className="flex gap-[3px]">
            {weeks.map((_, i) => {
              const month = months.find((m) => m.index === i)
              return (
                <span key={i} className="w-[11px] text-[8px] leading-3 text-txt-muted">
                  {month?.label ?? ''}
                </span>
              )
            })}
          </div>
          <div className="flex gap-[3px]">
            {weeks.map((week, wi) => (
              <div key={wi} className="flex flex-col gap-[3px]">
                {week.map((day, di) => {
                  if (!day) return <span key={di} className="h-[11px] w-[11px]" />
                  return (
                    <motion.span
                      key={day.date}
                      onMouseEnter={() => setHover(day)}
                      onMouseLeave={() => setHover((d) => (d?.date === day.date ? null : d))}
                      whileHover={{ scale: 1.45 }}
                      transition={SNAP}
                      className={`h-[11px] w-[11px] cursor-default rounded-[3px] ${LEVELS[levelOf(day.secs, max)]}`}
                    />
                  )
                })}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="flex items-center gap-1.5 self-end">
        <span className="text-[9px] text-txt-muted">{t('stats.calendar.less')}</span>
        {LEVELS.map((c, i) => (
          <span key={i} className={`h-[9px] w-[9px] rounded-[2px] ${c}`} />
        ))}
        <span className="text-[9px] text-txt-muted">{t('stats.calendar.more')}</span>
      </div>
    </div>
  )
}
