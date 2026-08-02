import { useEffect, useState } from 'react'
import { EmptyState } from '@/components/ui/EmptyState'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'
import type { CurseforgeHit, CurseforgeSearchFilters, CurseforgeCategory } from './curseforgeUtils'
import { fetchCurseforgeCategories } from './curseforgeUtils'
import { CurseforgeCard } from './CurseforgeCard'

const SORT_OPTIONS = ['relevance', 'downloads', 'updated', 'name'] as const

const EMPTY_FILTERS: CurseforgeSearchFilters = {}

function isFiltersActive(f: CurseforgeSearchFilters): boolean {
  return !!(f.categoryId || (f.sort && f.sort !== 'relevance'))
}

/** Panneau de recherche CurseForge — séparé de BrowseTab (Modrinth) : sources
 * distinctes non fusionnées dans une UI unique (prévu une fois le flux
 * CurseForge validé bout en bout), pas de preview des mods avant recherche. */
export function CurseforgeBrowseTab({
  query, results, searching, installing, installProgress, isInstalled, filters,
  onQueryChange, onInstall, onFiltersChange,
}: {
  query: string
  results: CurseforgeHit[]
  searching: boolean
  installing: number | null
  installProgress?: { percent: number; label: string } | null
  isInstalled: (hit: CurseforgeHit) => boolean
  filters: CurseforgeSearchFilters
  onQueryChange: (e: React.ChangeEvent<HTMLInputElement>) => void
  onInstall: (hit: CurseforgeHit) => void
  onFiltersChange: (f: CurseforgeSearchFilters) => void
}) {
  const t = useT()
  const [showFilters, setShowFilters] = useState(false)
  const [categories, setCategories] = useState<CurseforgeCategory[]>([])

  useEffect(() => {
    fetchCurseforgeCategories().then(setCategories)
  }, [])

  const chipClass = (active: boolean) =>
    `rounded-lg text-[11px] font-semibold transition-all duration-150 h-7 px-2.5 border ${
      active
        ? 'bg-[rgba(75,63,207,0.35)] border-[rgba(75,63,207,0.7)] text-[rgba(255,255,255,0.95)]'
        : 'bg-[rgba(0,0,0,0.3)] border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.4)]'
    }`

  return (
    <div className="flex flex-col gap-3">
      <div className="flex gap-2">
        <div className="relative flex-1">
          <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}
            className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(255,255,255,0.3)] pointer-events-none">
            <path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
          </svg>
          <input
            type="text"
            placeholder={t('mods.searchModPlaceholder')}
            value={query}
            onChange={onQueryChange}
            className="w-full rounded-xl pl-9 pr-4 text-sm text-white outline-none h-10 bg-[rgba(255,255,255,0.05)] border border-[rgba(255,255,255,0.08)] focus:border-[rgba(75,63,207,0.6)]"
          />
          {searching && (
            <ButtonSpinner size={16} color="rgba(75,63,207,0.8)" trackColor="rgba(255,255,255,0.1)" className="absolute right-3 top-1/2 -translate-y-1/2" />
          )}
        </div>
        <button
          onClick={() => setShowFilters((v) => !v)}
          className={`flex flex-shrink-0 items-center gap-1.5 rounded-xl px-3.5 text-[12px] font-semibold h-10 border transition-colors ${
            showFilters || isFiltersActive(filters)
              ? 'bg-[rgba(75,63,207,0.25)] border-[rgba(75,63,207,0.6)] text-white'
              : 'bg-[rgba(255,255,255,0.04)] border-[rgba(255,255,255,0.1)] text-[rgba(255,255,255,0.6)] hover:border-[rgba(75,63,207,0.5)]'
          }`}
        >
          <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}>
            <path d="M4 6h16v2H4zm3 5h10v2H7zm4 5h2v2h-2z" />
          </svg>
          {t('mods.filtersToggle')}
        </button>
      </div>

      {showFilters && (
        <div className="flex flex-col gap-3 rounded-2xl p-3.5 bg-[rgba(255,255,255,0.025)] border border-[rgba(255,255,255,0.07)]">
          {categories.length > 0 && (
            <div className="flex flex-col gap-1.5">
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                {t('mods.filtersCategories')}
              </label>
              <div className="flex flex-wrap gap-1.5">
                {categories.map((c) => (
                  <button
                    key={c.id}
                    onClick={() => onFiltersChange({ ...filters, categoryId: filters.categoryId === c.id ? undefined : c.id })}
                    className={chipClass(filters.categoryId === c.id)}
                  >
                    {c.name}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                {t('mods.filtersSort')}
              </label>
              <select
                value={filters.sort ?? 'relevance'}
                onChange={(e) => onFiltersChange({ ...filters, sort: e.target.value as CurseforgeSearchFilters['sort'] })}
                className="rounded-lg px-2 text-[11px] font-medium text-white outline-none h-7 bg-[rgba(0,0,0,0.35)] border border-[rgba(255,255,255,0.08)]"
              >
                {SORT_OPTIONS.map((s) => (
                  <option key={s} value={s} className="bg-[#111118]">{t(`mods.sort${s.charAt(0).toUpperCase()}${s.slice(1)}`)}</option>
                ))}
              </select>
            </div>

            {isFiltersActive(filters) && (
              <button
                onClick={() => onFiltersChange(EMPTY_FILTERS)}
                className="text-[11px] font-semibold text-[rgba(179,163,255,0.9)] h-7"
              >
                {t('mods.filtersReset')}
              </button>
            )}
          </div>
        </div>
      )}

      {!searching && query.trim() && results.length === 0 && (
        <EmptyState
          icon={<SearchIcon size={28} color="rgba(255,255,255,0.15)" />}
          title={t('mods.noResults')}
          subtitle={t('mods.tryAnotherTermOrVersion')}
        />
      )}

      <div className="flex flex-col gap-2">
        {results.map((hit) => (
          <CurseforgeCard
            key={hit.id}
            hit={hit}
            installed={isInstalled(hit)}
            loading={installing === hit.id}
            progress={installing === hit.id ? installProgress : null}
            onInstall={() => onInstall(hit)}
          />
        ))}
      </div>
    </div>
  )
}
