import { useState } from 'react'
import { EmptyState } from '@/components/ui/EmptyState'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'
import type { ModrinthHit, ModrinthSearchFilters } from './modUtils'
import { ModrinthCard } from './ModrinthCard'

// Tags de contenu Modrinth réels (distincts du loader, déjà géré séparément) —
// vocabulaire Modrinth lui-même, affiché tel quel (pas de traduction par tag).
const CONTENT_CATEGORIES = [
  'adventure', 'cursed', 'decoration', 'economy', 'equipment', 'food',
  'game-mechanics', 'library', 'magic', 'management', 'minigame', 'mobs',
  'optimization', 'social', 'storage', 'technology', 'transportation',
  'utility', 'worldgen',
]

const SORT_OPTIONS = ['relevance', 'downloads', 'follows', 'newest', 'updated'] as const

const EMPTY_FILTERS: ModrinthSearchFilters = {}

function isFiltersActive(f: ModrinthSearchFilters): boolean {
  return !!(f.categories?.length || f.environment || f.license || f.openSourceOnly || (f.sort && f.sort !== 'relevance'))
}

export function BrowseTab({
  query, results, searching, installing, installProgress, isInstalled, isPlugin,
  filters, onQueryChange, onInstall, onOpenDetail, onFiltersChange,
}: {
  query: string
  results: ModrinthHit[]
  searching: boolean
  installing: string | null
  installProgress?: { percent: number; label: string } | null
  isInstalled: (hit: ModrinthHit) => boolean
  isPlugin: boolean
  filters: ModrinthSearchFilters
  onQueryChange: (e: React.ChangeEvent<HTMLInputElement>) => void
  onInstall: (hit: ModrinthHit) => void
  onOpenDetail: (hit: ModrinthHit) => void
  onFiltersChange: (f: ModrinthSearchFilters) => void
}) {
  const t = useT()
  const [showFilters, setShowFilters] = useState(false)

  const toggleCategory = (c: string) => {
    const current = filters.categories ?? []
    const next = current.includes(c) ? current.filter((x) => x !== c) : [...current, c]
    onFiltersChange({ ...filters, categories: next })
  }

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
            placeholder={isPlugin ? t('mods.searchPluginPlaceholder') : t('mods.searchModPlaceholder')}
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
          {!isPlugin && (
            <div className="flex flex-col gap-1.5">
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                {t('mods.filtersCategories')}
              </label>
              <div className="flex flex-wrap gap-1.5">
                {CONTENT_CATEGORIES.map((c) => (
                  <button key={c} onClick={() => toggleCategory(c)} className={chipClass((filters.categories ?? []).includes(c))}>
                    {c}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                {t('mods.filtersEnvironment')}
              </label>
              <div className="flex gap-1.5">
                {([undefined, 'client', 'server'] as const).map((env) => (
                  <button
                    key={env ?? 'any'}
                    onClick={() => onFiltersChange({ ...filters, environment: env })}
                    className={chipClass(filters.environment === env)}
                  >
                    {env === 'client' ? t('mods.filtersEnvironmentClient') : env === 'server' ? t('mods.filtersEnvironmentServer') : t('mods.filtersEnvironmentAny')}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                {t('mods.filtersSort')}
              </label>
              <select
                value={filters.sort ?? 'relevance'}
                onChange={(e) => onFiltersChange({ ...filters, sort: e.target.value as ModrinthSearchFilters['sort'] })}
                className="rounded-lg px-2 text-[11px] font-medium text-white outline-none h-7 bg-[rgba(0,0,0,0.35)] border border-[rgba(255,255,255,0.08)]"
              >
                {SORT_OPTIONS.map((s) => (
                  <option key={s} value={s} className="bg-[#111118]">{t(`mods.sort${s.charAt(0).toUpperCase()}${s.slice(1)}`)}</option>
                ))}
              </select>
            </div>

            <div className="flex flex-col gap-1.5 min-w-[160px] flex-1">
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                {t('mods.filtersLicense')}
              </label>
              <input
                type="text"
                value={filters.license ?? ''}
                onChange={(e) => onFiltersChange({ ...filters, license: e.target.value || undefined })}
                placeholder={t('mods.filtersLicensePlaceholder')}
                className="rounded-lg px-2.5 text-[11px] font-medium text-white outline-none h-7 bg-[rgba(0,0,0,0.35)] border border-[rgba(255,255,255,0.08)]"
              />
            </div>

            <label className="flex items-center gap-1.5 h-7 cursor-pointer">
              <input
                type="checkbox"
                checked={!!filters.openSourceOnly}
                onChange={(e) => onFiltersChange({ ...filters, openSourceOnly: e.target.checked || undefined })}
              />
              <span className="text-[11px] font-medium text-[rgba(255,255,255,0.6)]">{t('mods.filtersOpenSourceOnly')}</span>
            </label>

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

      {!searching && results.length === 0 && (
        <EmptyState
          icon={<SearchIcon size={28} color="rgba(255,255,255,0.15)" />}
          title={t('mods.noResults')}
          subtitle={isPlugin
            ? t('mods.tryAnotherSearchTerm')
            : t('mods.tryAnotherTermOrVersion')}
        />
      )}

      <div className="flex flex-col gap-2">
        {results.map((hit) => (
          <ModrinthCard
            key={hit.project_id}
            hit={hit}
            installed={isInstalled(hit)}
            loading={installing === hit.project_id}
            progress={installing === hit.project_id ? installProgress : null}
            onInstall={() => onInstall(hit)}
            onOpenDetail={() => onOpenDetail(hit)}
          />
        ))}
      </div>
    </div>
  )
}
