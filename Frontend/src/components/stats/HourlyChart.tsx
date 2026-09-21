import { useState } from 'react'
import { motion } from 'framer-motion'
import { SNAP } from '@/lib/motion'
import { formatDuration } from '@/lib/format'
import { useT } from '@/i18n'

/**
 * À quelles heures on joue.
 *
 * Possible seulement depuis que le découpage du temps est fait côté Rust :
 * une session de 22 h à 1 h remplit trois barres, pas une. C'est le genre de
 * chiffre qu'on ne cherche pas mais qu'on regarde une fois qu'il est là.
 */
export function HourlyChart({ hourly }: { hourly: number[] }) {
  const t = useT()
  const [hover, setHover] = useState<number | null>(null)
  const max = Math.max(...hourly, 1)
  const total = hourly.reduce((a, b) => a + b, 0)

  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('stats.hourly.title')}</span>
        <motion.span
          key={hover ?? 'none'}
          initial={{ opacity: 0, y: -3 }}
          animate={{ opacity: 1, y: 0 }}
          transition={SNAP}
          className="text-[11px] text-txt-secondary"
        >
          {hover !== null
            ? t('stats.hourly.detail', { hour: String(hover).padStart(2, '0'), duration: formatDuration(hourly[hover]) })
            : t('stats.hourly.hint')}
        </motion.span>
      </div>

      {total === 0 ? (
        <p className="py-6 text-center text-[11.5px] text-txt-muted">{t('stats.hourly.empty')}</p>
      ) : (
        <div className="flex h-24 items-end gap-[3px]">
          {hourly.map((secs, hour) => (
            <div
              key={hour}
              onMouseEnter={() => setHover(hour)}
              onMouseLeave={() => setHover((h) => (h === hour ? null : h))}
              className="group flex h-full flex-1 cursor-default flex-col justify-end gap-1"
            >
              <motion.div
                initial={{ scaleY: 0 }}
                animate={{ scaleY: 1 }}
                transition={{ delay: hour * 0.012, duration: 0.3, ease: [0.16, 1, 0.3, 1] }}
                style={{ height: `${Math.max(secs > 0 ? 6 : 2, (secs / max) * 100)}%` }}
                className={`origin-bottom rounded-[3px] transition-colors duration-150 ${
                  secs > 0 ? 'bg-accent/45 group-hover:bg-accent-hover' : 'bg-[rgba(255,255,255,0.05)]'
                }`}
              />
              {/* Une graduation toutes les six heures : de quoi se repérer
                  sans aligner vingt-quatre nombres illisibles. */}
              <span className={`text-center text-[8px] leading-none ${hover === hour ? 'text-accent-hover' : 'text-txt-muted'}`}>
                {hour % 6 === 0 ? hour : ''}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
