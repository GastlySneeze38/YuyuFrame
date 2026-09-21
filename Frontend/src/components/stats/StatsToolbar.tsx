import { motion } from 'framer-motion'
import { SNAP, press } from '@/lib/motion'
import { useT } from '@/i18n'
import type { StatsData, StatsQuery } from '@/types/stats'

/**
 * Choisir ce qu'on regarde.
 *
 * L'ancienne page n'offrait rien : quatorze jours, toutes instances
 * confondues, pour tout le monde. Une période et trois filtres suffisent à
 * répondre à la vraie question — « combien de temps sur CE modpack ce
 * mois-ci ? » — qu'aucune carte fixe ne pouvait traiter.
 */

/** Périodes proposées. `0` = tout l'historique. */
export const RANGES = [7, 30, 90, 365, 0] as const

export interface StatsFilters {
  instanceId: string
  loader: string
  mcVersion: string
}

export const EMPTY_FILTERS: StatsFilters = { instanceId: '', loader: '', mcVersion: '' }

/** Traduit période + filtres en ce que la commande Tauri attend. */
export function toQuery(rangeDays: number, filters: StatsFilters, firstSessionAt: number | null): StatsQuery {
    const now = Math.floor(Date.now() / 1000)
    return {
      // « Tout » part de la première session connue, pas de l'époque Unix :
      // sinon le calendrier proposerait cinquante ans de cases vides.
      from: rangeDays === 0 ? (firstSessionAt ?? now - 30 * 86400) : now - rangeDays * 86400,
      to: now,
      instanceId: filters.instanceId || undefined,
      loader: filters.loader || undefined,
      mcVersion: filters.mcVersion || undefined,
    }
}

function Select({
  value,
  onChange,
  options,
  placeholder,
}: {
  value: string
  onChange: (v: string) => void
  options: { value: string; label: string }[]
  placeholder: string
}) {
  const active = value !== ''
  return (
    <div className="relative">
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={placeholder}
        className={`h-8 cursor-pointer appearance-none rounded-lg border bg-surface-1 py-0 pl-3 pr-7 text-[11.5px] font-semibold outline-none transition-colors duration-150 ${
          active ? 'border-accent/45 text-txt-primary' : 'border-line text-txt-secondary hover:border-line-strong hover:text-txt-primary'
        }`}
      >
        <option value="">{placeholder}</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={2.2}
        strokeLinecap="round"
        className="pointer-events-none absolute right-2 top-1/2 h-2.5 w-2.5 -translate-y-1/2 text-txt-muted"
      >
        <path d="M6 9l6 6 6-6" />
      </svg>
    </div>
  )
}

export function StatsToolbar({
  rangeDays,
  onRange,
  filters,
  onFilters,
  data,
  onCustomize,
  customizing,
}: {
  rangeDays: number
  onRange: (d: number) => void
  filters: StatsFilters
  onFilters: (f: StatsFilters) => void
  data: StatsData | null
  onCustomize: () => void
  customizing: boolean
}) {
  const t = useT()
  const filtered = filters.instanceId !== '' || filters.loader !== '' || filters.mcVersion !== ''

  return (
    <div className="flex flex-wrap items-center gap-2">
      {/* Périodes : un segment glissant plutôt que quatre boutons qui
          s'allument, pour qu'on voie le changement et pas seulement l'état. */}
      <div className="relative flex items-center gap-0.5 rounded-xl border border-line bg-surface-1 p-0.5">
        {RANGES.map((d) => (
          <button
            key={d}
            onClick={() => onRange(d)}
            className={`relative z-10 rounded-lg px-2.5 py-1.5 text-[11.5px] font-semibold transition-colors duration-150 ${
              rangeDays === d ? 'text-txt-primary' : 'text-txt-muted hover:text-txt-secondary'
            }`}
          >
            {rangeDays === d && (
              <motion.span layoutId="stats-range" transition={SNAP} className="absolute inset-0 -z-10 rounded-lg bg-accent/20 ring-1 ring-accent/35" />
            )}
            {d === 0 ? t('stats.range.all') : t('stats.range.days', { count: d })}
          </button>
        ))}
      </div>

      <Select
        value={filters.instanceId}
        onChange={(instanceId) => onFilters({ ...filters, instanceId })}
        placeholder={t('stats.filter.allInstances')}
        options={(data?.known_instances ?? []).map((i) => ({ value: i.id, label: i.name }))}
      />
      <Select
        value={filters.loader}
        onChange={(loader) => onFilters({ ...filters, loader })}
        placeholder={t('stats.filter.allLoaders')}
        options={(data?.known_loaders ?? []).map((l) => ({ value: l, label: l }))}
      />
      <Select
        value={filters.mcVersion}
        onChange={(mcVersion) => onFilters({ ...filters, mcVersion })}
        placeholder={t('stats.filter.allVersions')}
        options={(data?.known_versions ?? []).map((v) => ({ value: v, label: v }))}
      />

      {filtered && (
        <motion.button
          {...press}
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          onClick={() => onFilters(EMPTY_FILTERS)}
          className="h-8 rounded-lg border border-line px-2.5 text-[11.5px] font-semibold text-txt-secondary transition-colors duration-150 hover:border-danger/40 hover:text-danger"
        >
          {t('stats.filter.reset')}
        </motion.button>
      )}

      <motion.button
        {...press}
        onClick={onCustomize}
        title={t('stats.customize')}
        className={`ml-auto flex h-8 items-center gap-1.5 rounded-lg border px-2.5 text-[11.5px] font-semibold transition-colors duration-150 ${
          customizing ? 'border-accent/45 bg-accent/15 text-txt-primary' : 'border-line text-txt-secondary hover:text-txt-primary'
        }`}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" className="h-3.5 w-3.5">
          <path d="M4 6h16M4 12h10M4 18h7" />
        </svg>
        {t('stats.customize')}
      </motion.button>
    </div>
  )
}
