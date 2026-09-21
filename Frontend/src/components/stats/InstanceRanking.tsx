import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { SNAP, press } from '@/lib/motion'
import { formatDuration, formatRelativeTime } from '@/lib/format'
import { loaderColor } from '@/lib/loader'
import type { StatsSort } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { InstanceStat } from '@/types/stats'

/**
 * Classement des instances.
 *
 * La barre n'est plus un élément à part sous la ligne : elle EST la ligne,
 * remplie par la gauche. Avec 49 h sur une instance et 2 min sur la suivante,
 * une barre séparée donnait huit rangées de rail vide qui prenaient autant de
 * place que la première — beaucoup de hauteur pour ne rien dire. En fond de
 * ligne, une part minuscule reste lisible et la liste tient deux fois moins
 * haut.
 *
 * Elle est aussi repliée : une longue traîne d'instances jouées une minute
 * n'apprend rien tant qu'on ne va pas la chercher.
 */

const SORTS: StatsSort[] = ['time', 'sessions', 'recent', 'crashes']

/** Rangées montrées avant de demander à voir le reste. */
const COLLAPSED = 6

function sortBy(list: InstanceStat[], sort: StatsSort): InstanceStat[] {
  const copy = [...list]
  switch (sort) {
    case 'sessions':
      return copy.sort((a, b) => b.sessions - a.sessions)
    case 'recent':
      return copy.sort((a, b) => b.last_played_at - a.last_played_at)
    case 'crashes':
      // À nombre de plantages égal, le plus joué d'abord : un plantage sur
      // deux sessions n'a pas le même poids qu'un plantage sur cinquante.
      return copy.sort((a, b) => b.crashed - a.crashed || b.total_secs - a.total_secs)
    default:
      return copy.sort((a, b) => b.total_secs - a.total_secs)
  }
}

export function InstanceRanking({
  instances,
  sort,
  onSort,
  onPick,
}: {
  instances: InstanceStat[]
  sort: StatsSort
  onSort: (s: StatsSort) => void
  onPick: (instanceId: string) => void
}) {
  const t = useT()
  const [expanded, setExpanded] = useState(false)
  const ranked = sortBy(instances, sort)
  const max = Math.max(...ranked.map((i) => i.total_secs), 1)
  const shown = expanded ? ranked : ranked.slice(0, COLLAPSED)

  return (
    <div className="flex min-w-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('stats.ranking.title')}</span>
        <div className="flex items-center gap-0.5 rounded-lg border border-line p-0.5">
          {SORTS.map((s) => (
            <button
              key={s}
              onClick={() => onSort(s)}
              className={`relative z-10 rounded-md px-2 py-1 text-[10px] font-semibold transition-colors duration-150 ${
                sort === s ? 'text-txt-primary' : 'text-txt-muted hover:text-txt-secondary'
              }`}
            >
              {sort === s && <motion.span layoutId="stats-sort" transition={SNAP} className="absolute inset-0 -z-10 rounded-md bg-surface-3" />}
              {t(`stats.ranking.sort.${s}`)}
            </button>
          ))}
        </div>
      </div>

      {ranked.length === 0 ? (
        <p className="py-8 text-center text-[11.5px] text-txt-muted">{t('stats.ranking.empty')}</p>
      ) : (
        <div className="flex flex-col gap-1">
          <AnimatePresence initial={false}>
            {shown.map((inst, i) => (
              <motion.button
                key={inst.instance_id}
                layout
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, height: 0 }}
                transition={SNAP}
                {...press}
                onClick={() => onPick(inst.instance_id)}
                title={t('stats.ranking.filterBy')}
                className="group relative flex w-full items-center gap-2.5 overflow-hidden rounded-lg px-2.5 py-2 text-left"
              >
                {/* Le remplissage EST la ligne. `min-w` pour qu'une part
                    infime reste visible plutôt que de disparaître. */}
                <motion.span
                  initial={{ scaleX: 0 }}
                  animate={{ scaleX: 1 }}
                  transition={{ delay: i * 0.03, duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
                  style={{ width: `${Math.max(1.5, (inst.total_secs / max) * 100)}%` }}
                  className="absolute inset-y-0 left-0 origin-left rounded-lg bg-gradient-to-r from-accent/30 to-accent/10 transition-colors duration-150 group-hover:from-accent/45 group-hover:to-accent/15"
                />

                <span className="relative w-3 shrink-0 text-[10px] font-bold tabular-nums text-txt-muted">{i + 1}</span>
                <span className="relative min-w-0 flex-1 truncate text-[12px] font-semibold text-txt-primary" title={inst.instance_name}>
                  {inst.instance_name}
                </span>
                <span className="relative shrink-0 text-[9px] font-bold" style={{ color: loaderColor(inst.loader) }}>
                  {inst.loader}
                </span>
                <span className="relative shrink-0 text-[9px] text-txt-muted">{inst.mc_version}</span>
                {inst.crashed > 0 && (
                  <span
                    title={t('stats.ranking.crashes', { count: inst.crashed })}
                    className="relative shrink-0 rounded bg-danger/15 px-1 text-[9px] font-bold tabular-nums text-danger"
                  >
                    {inst.crashed} ⚠
                  </span>
                )}
                {/* Détail secondaire : présent, mais il ne prend une ligne à
                    lui seul que sur la rangée survolée. */}
                <span className="relative hidden shrink-0 text-[9.5px] tabular-nums text-txt-muted xl:inline">
                  {t('stats.ranking.line', {
                    sessions: inst.sessions,
                    average: formatDuration(inst.avg_secs),
                    last: formatRelativeTime(inst.last_played_at),
                  })}
                </span>
                <span className="relative shrink-0 text-[11px] font-bold tabular-nums text-accent-hover">{formatDuration(inst.total_secs)}</span>
              </motion.button>
            ))}
          </AnimatePresence>

          {ranked.length > COLLAPSED && (
            <motion.button
              {...press}
              onClick={() => setExpanded((v) => !v)}
              className="mt-1 self-center text-[10.5px] font-semibold text-txt-muted transition-colors duration-150 hover:text-accent-hover"
            >
              {expanded ? t('stats.ranking.showLess') : t('stats.ranking.showAll', { count: ranked.length - COLLAPSED })}
            </motion.button>
          )}
        </div>
      )}
    </div>
  )
}
