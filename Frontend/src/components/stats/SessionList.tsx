import { useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { SNAP, press } from '@/lib/motion'
import { formatDuration, formatTime } from '@/lib/format'
import { loaderColor } from '@/lib/loader'
import { useT } from '@/i18n'
import type { SessionEntry } from '@/types/stats'

/**
 * Les dernières parties, groupées par jour.
 *
 * Chaque partie tenait dans une carte bordée de sa propre hauteur, et chacune
 * réaffichait sa date complète : huit lignes remplissaient l'écran pour dire
 * « PVP, 1.8.9 » quatre fois de suite. En groupant par jour, la date se dit
 * une fois en tête de groupe et chaque partie se réduit à ce qui la
 * distingue — l'heure, l'instance, la durée.
 *
 * Trois états que l'ancienne liste ne pouvait pas montrer, parce que le
 * backend ne les connaissait pas :
 *
 * - **en cours** — la durée monte pendant qu'on regarde ;
 * - **plantée** — avec un lien direct vers le rapport ;
 * - **rattrapée** — le launcher n'était pas là à la fin, la durée affichée
 *   est un minimum. Le dire vaut mieux que présenter une estimation comme
 *   une mesure.
 */

/** Parties montrées avant de demander à voir le reste. */
const COLLAPSED = 12

/** Clé de jour locale, et son libellé. */
function dayOf(ts: number) {
  const d = new Date(ts * 1000)
  d.setHours(0, 0, 0, 0)
  return d
}

function groupByDay(sessions: SessionEntry[]) {
  const groups: { key: number; date: Date; secs: number; items: SessionEntry[] }[] = []
  for (const s of sessions) {
    const date = dayOf(s.started_at)
    const key = date.getTime()
    const last = groups[groups.length - 1]
    if (last && last.key === key) {
      last.items.push(s)
      last.secs += s.duration_secs
    } else {
      groups.push({ key, date, secs: s.duration_secs, items: [s] })
    }
  }
  return groups
}

function Row({ session }: { session: SessionEntry }) {
  const t = useT()
  const navigate = useNavigate()

  return (
    <motion.div
      whileHover={{ x: 2 }}
      transition={SNAP}
      className={`flex items-center gap-2.5 rounded-lg px-2.5 py-1.5 transition-colors duration-150 ${
        session.running ? 'bg-success/[0.07]' : 'hover:bg-surface-2'
      }`}
    >
      <span className="w-9 shrink-0 text-[10px] tabular-nums text-txt-muted">{formatTime(session.started_at)}</span>
      <span className="min-w-0 flex-1 truncate text-[12px] font-semibold text-txt-primary" title={session.instance_name}>
        {session.instance_name}
      </span>

      <span className="shrink-0 text-[9px] font-bold" style={{ color: loaderColor(session.loader) }}>
        {session.loader}
      </span>
      <span className="shrink-0 text-[9px] text-txt-muted">{session.mc_version}</span>

      {session.running && (
        <span className="flex shrink-0 items-center gap-1 rounded bg-success/15 px-1.5 text-[9px] font-bold uppercase tracking-wide text-success">
          <motion.span className="h-1 w-1 rounded-full bg-success" animate={{ opacity: [1, 0.3, 1] }} transition={{ duration: 1.6, repeat: Infinity }} />
          {t('stats.sessions.live')}
        </span>
      )}
      {session.crashed && (
        <motion.button
          {...press}
          onClick={() => navigate('/support')}
          title={t('stats.sessions.openReport')}
          className="shrink-0 rounded bg-danger/15 px-1.5 text-[9px] font-bold uppercase tracking-wide text-danger transition-colors duration-150 hover:bg-danger/30"
        >
          {t('stats.sessions.crashed')}
        </motion.button>
      )}
      {session.recovered && !session.crashed && (
        <span
          title={t('stats.sessions.recoveredHint')}
          className="shrink-0 rounded bg-surface-3 px-1.5 text-[9px] font-bold uppercase tracking-wide text-txt-muted"
        >
          {t('stats.sessions.recovered')}
        </span>
      )}

      <span className={`w-14 shrink-0 text-right text-[11px] font-bold tabular-nums ${session.running ? 'text-success' : 'text-accent-hover'}`}>
        {session.recovered && !session.crashed ? '≥ ' : ''}
        {formatDuration(session.duration_secs)}
      </span>
    </motion.div>
  )
}

export function SessionList({ sessions }: { sessions: SessionEntry[] }) {
  const t = useT()
  const [expanded, setExpanded] = useState(false)
  const shown = expanded ? sessions : sessions.slice(0, COLLAPSED)
  const groups = useMemo(() => groupByDay(shown), [shown])

  const today = dayOf(Date.now() / 1000).getTime()
  const yesterday = today - 86_400_000
  const labelOf = (date: Date) => {
    const key = date.getTime()
    if (key === today) return t('stats.sessions.today')
    if (key === yesterday) return t('stats.sessions.yesterday')
    return date.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })
  }

  return (
    <div className="flex min-w-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
      <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('stats.sessions.title')}</span>

      {sessions.length === 0 ? (
        <p className="py-8 text-center text-[11.5px] text-txt-muted">{t('stats.sessions.empty')}</p>
      ) : (
        <div className="flex flex-col gap-3">
          {groups.map((group) => (
            <div key={group.key} className="flex flex-col gap-0.5">
              <div className="flex items-baseline justify-between gap-3 px-2.5 pb-1">
                <span className="text-[10px] font-semibold capitalize text-txt-secondary">{labelOf(group.date)}</span>
                <span className="text-[10px] tabular-nums text-txt-muted">
                  {t('stats.sessions.dayTotal', { count: group.items.length, duration: formatDuration(group.secs) })}
                </span>
              </div>
              {group.items.map((s) => (
                <Row key={s.id} session={s} />
              ))}
            </div>
          ))}

          {sessions.length > COLLAPSED && (
            <motion.button
              {...press}
              onClick={() => setExpanded((v) => !v)}
              className="self-center text-[10.5px] font-semibold text-txt-muted transition-colors duration-150 hover:text-accent-hover"
            >
              {expanded ? t('stats.sessions.showLess') : t('stats.sessions.showAll', { count: sessions.length - COLLAPSED })}
            </motion.button>
          )}
        </div>
      )}
    </div>
  )
}
