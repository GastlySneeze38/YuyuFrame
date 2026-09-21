import { AnimatePresence, motion } from 'framer-motion'
import { SNAP, press } from '@/lib/motion'
import { formatDuration } from '@/lib/format'
import { STAT_CARD_IDS, type StatCardId } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { StatsData } from '@/types/stats'

/**
 * Les chiffres du haut, choisis par la personne.
 *
 * Avant, trois cartes décidées une fois pour toutes, les mêmes pour tout le
 * monde. Quelqu'un qui joue tous les soirs veut sa série ; quelqu'un qui
 * teste des modpacks veut son taux de plantage. Ce ne sont pas les mêmes
 * chiffres, et il n'y avait aucune raison de trancher à leur place.
 */

interface Card {
  label: string
  value: string
  sub: string
  tone: 'accent' | 'warm' | 'danger' | 'success'
}

const TONES = {
  accent: { text: 'text-accent-hover', glow: 'from-accent/20' },
  warm: { text: 'text-warning', glow: 'from-warning/20' },
  danger: { text: 'text-danger', glow: 'from-danger/20' },
  success: { text: 'text-success', glow: 'from-success/20' },
} as const

function build(id: StatCardId, data: StatsData, t: (k: string, v?: Record<string, string | number>) => string): Card {
  const { totals } = data
  switch (id) {
    case 'time':
      return {
        label: t('stats.card.time'),
        // `formatDuration(0)` rend « < 1 min », ce qui laisserait croire
        // qu'on a joué un peu alors qu'on n'a pas joué du tout.
        value: totals.secs > 0 ? formatDuration(totals.secs) : '—',
        sub: t('stats.card.timeSub', { count: totals.sessions }),
        tone: 'accent',
      }
    case 'sessions':
      return {
        label: t('stats.card.sessions'),
        value: String(totals.sessions),
        sub: totals.crashed > 0 ? t('stats.card.sessionsCrashed', { count: totals.crashed }) : t('stats.card.sessionsClean'),
        tone: 'accent',
      }
    case 'average':
      return {
        label: t('stats.card.average'),
        value: totals.sessions > 0 ? formatDuration(totals.avg_secs) : '—',
        sub: t('stats.card.averageSub'),
        tone: 'accent',
      }
    case 'longest':
      return {
        label: t('stats.card.longest'),
        value: totals.longest_secs > 0 ? formatDuration(totals.longest_secs) : '—',
        sub: t('stats.card.longestSub'),
        tone: 'warm',
      }
    case 'streak':
      return {
        label: t('stats.card.streak'),
        value: t('stats.card.streakValue', { count: totals.current_streak }),
        sub: t('stats.card.streakSub', { count: totals.longest_streak }),
        tone: 'warm',
      }
    case 'activeDays':
      return {
        label: t('stats.card.activeDays'),
        value: String(totals.active_days),
        sub: t('stats.card.activeDaysSub', { count: data.daily.length }),
        tone: 'success',
      }
    case 'crashRate': {
      const rate = totals.sessions > 0 ? Math.round((totals.crashed / totals.sessions) * 100) : 0
      return {
        label: t('stats.card.crashRate'),
        value: totals.sessions > 0 ? `${rate} %` : '—',
        sub: t('stats.card.crashRateSub', { count: totals.crashed }),
        tone: rate > 15 ? 'danger' : 'success',
      }
    }
    case 'favorite': {
      const top = data.per_instance[0]
      return {
        label: t('stats.card.favorite'),
        value: top?.instance_name ?? '—',
        sub: top ? formatDuration(top.total_secs) : t('stats.card.noData'),
        tone: 'warm',
      }
    }
  }
}

export function StatCards({
  data,
  selected,
  customizing,
  onToggle,
}: {
  data: StatsData
  selected: StatCardId[]
  customizing: boolean
  onToggle: (id: StatCardId) => void
}) {
  const t = useT()

  return (
    <div className="flex flex-col gap-3">
      <motion.div layout className="grid grid-cols-4 gap-3">
        <AnimatePresence mode="popLayout" initial={false}>
          {selected.map((id) => {
            const card = build(id, data, t)
            const tone = TONES[card.tone]
            return (
              <motion.div
                key={id}
                layout
                initial={{ opacity: 0, scale: 0.94 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.94 }}
                transition={SNAP}
                whileHover={{ y: -2 }}
                className="group relative flex min-w-0 flex-col gap-1.5 overflow-hidden rounded-2xl border border-line bg-surface-1 p-4"
              >
                {/* Lueur discrète : elle donne sa couleur à la carte sans
                    peindre un bloc entier, qui écraserait le chiffre. */}
                <span className={`pointer-events-none absolute -right-8 -top-10 h-24 w-24 rounded-full bg-gradient-to-br ${tone.glow} to-transparent blur-2xl`} />
                <span className="truncate text-[10px] font-bold uppercase tracking-[0.08em] text-txt-muted">{card.label}</span>
                <span className={`block truncate text-[26px] font-black leading-none tracking-[-0.02em] ${tone.text}`} title={card.value}>
                  {card.value}
                </span>
                <span className="truncate text-[11px] text-txt-muted">{card.sub}</span>
                {customizing && (
                  <motion.button
                    {...press}
                    onClick={() => onToggle(id)}
                    title={t('stats.card.remove')}
                    className="absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded-lg bg-surface-3 text-txt-muted transition-colors duration-150 hover:bg-danger/20 hover:text-danger"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" className="h-3 w-3">
                      <path d="M6 6l12 12M18 6L6 18" />
                    </svg>
                  </motion.button>
                )}
              </motion.div>
            )
          })}
        </AnimatePresence>
      </motion.div>

      <AnimatePresence initial={false}>
        {customizing && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            <div className="flex flex-wrap items-center gap-1.5 rounded-xl border border-line bg-surface-1 px-3 py-2.5">
              <span className="mr-1 text-[11px] font-semibold text-txt-muted">{t('stats.card.add')}</span>
              {STAT_CARD_IDS.filter((id) => !selected.includes(id)).map((id) => (
                <motion.button
                  key={id}
                  {...press}
                  onClick={() => onToggle(id)}
                  className="rounded-lg border border-line px-2.5 py-1 text-[11px] font-semibold text-txt-secondary transition-colors duration-150 hover:border-accent/45 hover:text-txt-primary"
                >
                  + {build(id, data, t).label}
                </motion.button>
              ))}
              {selected.length === STAT_CARD_IDS.length && (
                <span className="text-[11px] text-txt-muted">{t('stats.card.allShown')}</span>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
