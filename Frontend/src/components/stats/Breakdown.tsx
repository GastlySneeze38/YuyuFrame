import { motion } from 'framer-motion'
import { SNAP } from '@/lib/motion'
import { formatDuration } from '@/lib/format'
import { loaderColor } from '@/lib/loader'
import { useT } from '@/i18n'
import type { StatBucket } from '@/types/stats'

/**
 * Répartition par loader et par version, en une barre empilée chacune.
 *
 * Deux listes de plus auraient noyé la page ; une barre dit la même chose en
 * une ligne, et c'est bien une proportion qu'on cherche ici, pas un chiffre
 * exact.
 */

/** Palette des versions — le loader a déjà sa couleur (`loaderColor`). */
const VERSION_COLORS = ['#818cf8', '#f59e0b', '#34d399', '#f472b6', '#60a5fa', '#a78bfa']

function Bar({ buckets, colorOf, label }: { buckets: StatBucket[]; colorOf: (key: string, i: number) => string; label: string }) {
  const t = useT()
  const total = buckets.reduce((sum, b) => sum + b.secs, 0)
  if (total === 0) return null

  // Au-delà de cinq parts, la barre devient un dégradé illisible : le reste
  // est regroupé.
  const top = buckets.slice(0, 5)
  const rest = buckets.slice(5).reduce((sum, b) => sum + b.secs, 0)
  const parts = rest > 0 ? [...top, { key: t('stats.breakdown.others'), secs: rest, sessions: 0 }] : top

  return (
    <div className="flex flex-col gap-2">
      <span className="text-[10px] font-semibold uppercase tracking-[0.06em] text-txt-muted">{label}</span>
      <div className="flex h-2 w-full overflow-hidden rounded-full bg-[rgba(255,255,255,0.06)]">
        {parts.map((b, i) => (
          <motion.span
            key={b.key}
            initial={{ scaleX: 0 }}
            animate={{ scaleX: 1 }}
            transition={{ delay: i * 0.05, duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
            style={{ width: `${(b.secs / total) * 100}%`, backgroundColor: colorOf(b.key, i) }}
            className="h-full origin-left"
          />
        ))}
      </div>
      <div className="flex flex-wrap gap-x-3 gap-y-1">
        {parts.map((b, i) => (
          <motion.span key={b.key} whileHover={{ y: -1 }} transition={SNAP} className="flex items-center gap-1.5 text-[10px] text-txt-secondary">
            <span className="h-2 w-2 rounded-[2px]" style={{ backgroundColor: colorOf(b.key, i) }} />
            {b.key}
            <span className="text-txt-muted">{formatDuration(b.secs)}</span>
          </motion.span>
        ))}
      </div>
    </div>
  )
}

export function Breakdown({ loaders, versions }: { loaders: StatBucket[]; versions: StatBucket[] }) {
  const t = useT()
  if (loaders.length === 0 && versions.length === 0) return null

  return (
    <div className="flex flex-col gap-4 rounded-2xl border border-line bg-surface-1 p-5">
      <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('stats.breakdown.title')}</span>
      <Bar buckets={loaders} label={t('stats.breakdown.loaders')} colorOf={(key) => loaderColor(key)} />
      <Bar buckets={versions} label={t('stats.breakdown.versions')} colorOf={(_, i) => VERSION_COLORS[i % VERSION_COLORS.length]} />
    </div>
  )
}
