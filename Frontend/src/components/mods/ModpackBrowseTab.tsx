import { useRef, useState } from 'react'
import { Pagination } from '@/components/ui/Pagination'
import { press, pressIf } from '@/lib/motion'
import { motion } from 'framer-motion'
import type { ModpackHit, ModrinthSearchFilters } from '@/lib/modrinthModpacks'
import type { CurseforgeModpackHit } from '@/lib/curseforgeModpacks'
import { formatDownloadCount } from '@/lib/format'
import { EmptyState } from '@/components/ui/EmptyState'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'

/// Résultat de recherche modpack fusionné Modrinth + CurseForge — même principe
/// que `MergedHit` dans BrowseTab.tsx (mods), pas de dédoublonnage ici : les
/// modpacks n'ont pas de nom canonique fiable à comparer entre les deux sources.
export type MergedModpackHit =
  | { source: 'modrinth'; hit: ModpackHit }
  | { source: 'curseforge'; hit: CurseforgeModpackHit }

// Tags de modpack réels côté Modrinth (distincts des tags de mod).
const MODPACK_CATEGORIES = [
  'adventure', 'challenging', 'combat', 'kitchen-sink', 'lightweight',
  'magic', 'multiplayer', 'optimization', 'quests', 'rpg', 'technology', 'vanilla-like',
]

const SORT_OPTIONS = ['relevance', 'downloads', 'follows', 'newest', 'updated'] as const

const EMPTY_FILTERS: ModrinthSearchFilters = {}

function isFiltersActive(f: ModrinthSearchFilters): boolean {
  return !!(f.categories?.length || f.environment || f.license || f.openSourceOnly || (f.sort && f.sort !== 'relevance'))
}

export function ModpackBrowseTab({
  query, results, searching, installing, installProgress, cfInstalling, cfInstallProgress,
  filters, onQueryChange, onInstall, onInstallCurseforge, onFiltersChange, onOpenDetail,
  page, pageCount, onPageChange,
}: {
  page: number
  pageCount: number
  onPageChange: (page: number) => void
  query: string
  results: MergedModpackHit[]
  searching: boolean
  installing: string | null
  installProgress?: { percent: number; label: string } | null
  cfInstalling: number | null
  cfInstallProgress?: { percent: number; label: string } | null
  filters: ModrinthSearchFilters
  onQueryChange: (e: React.ChangeEvent<HTMLInputElement>) => void
  onInstall: (hit: ModpackHit) => void
  onInstallCurseforge: (hit: CurseforgeModpackHit) => void
  onFiltersChange: (f: ModrinthSearchFilters) => void
  onOpenDetail: (hit: MergedModpackHit) => void
}) {
  const t = useT()
  const [showFilters, setShowFilters] = useState(false)
  const topRef = useRef<HTMLDivElement>(null)

  // Même geste que dans BrowseTab : la pagination est en bas, la page
  // suivante se lit depuis le haut.
  const changePage = (p: number) => {
    onPageChange(p)
    topRef.current?.scrollIntoView({ block: 'start' })
  }

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
    <div ref={topRef} className="flex flex-col gap-3">
      <div className="flex gap-2">
        <div className="relative flex-1">
          <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}
            className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(255,255,255,0.3)] pointer-events-none">
            <path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
          </svg>
          <input
            type="text"
            placeholder={t('mods.searchModpackPlaceholder')}
            value={query}
            onChange={onQueryChange}
            className="w-full rounded-xl pl-9 pr-4 text-sm text-white outline-none h-10 bg-[rgba(255,255,255,0.05)] border border-[rgba(255,255,255,0.08)] focus:border-[rgba(75,63,207,0.6)]"
          />
          {searching && (
            <ButtonSpinner size={16} color="rgba(75,63,207,0.8)" trackColor="rgba(255,255,255,0.1)" className="absolute right-3 top-1/2 -translate-y-1/2" />
          )}
        </div>
        <motion.button {...press}
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
        </motion.button>
      </div>

      {showFilters && (
        <div className="flex flex-col gap-3 rounded-2xl p-3.5 bg-[rgba(255,255,255,0.025)] border border-[rgba(255,255,255,0.07)]">
          <div className="flex flex-col gap-1.5">
            <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
              {t('mods.filtersCategories')}
            </label>
            <div className="flex flex-wrap gap-1.5">
              {MODPACK_CATEGORIES.map((c) => (
                <motion.button {...press} key={c} onClick={() => toggleCategory(c)} className={chipClass((filters.categories ?? []).includes(c))}>
                  {c}
                </motion.button>
              ))}
            </div>
          </div>

          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                {t('mods.filtersEnvironment')}
              </label>
              <div className="flex gap-1.5">
                {([undefined, 'client', 'server'] as const).map((env) => (
                  <motion.button {...press}
                    key={env ?? 'any'}
                    onClick={() => onFiltersChange({ ...filters, environment: env })}
                    className={chipClass(filters.environment === env)}
                  >
                    {env === 'client' ? t('mods.filtersEnvironmentClient') : env === 'server' ? t('mods.filtersEnvironmentServer') : t('mods.filtersEnvironmentAny')}
                  </motion.button>
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
              <motion.button {...press}
                onClick={() => onFiltersChange(EMPTY_FILTERS)}
                className="text-[11px] font-semibold text-[rgba(179,163,255,0.9)] h-7"
              >
                {t('mods.filtersReset')}
              </motion.button>
            )}
          </div>
        </div>
      )}

      {!searching && results.length === 0 && (
        <EmptyState
          icon={<SearchIcon size={28} color="rgba(255,255,255,0.15)" />}
          title={t('mods.noResults')}
          subtitle={t('mods.tryAnotherSearchTerm')}
        />
      )}

      <div className="flex flex-col gap-2">
        {results.map((r) => {
          const isModrinth = r.source === 'modrinth'
          const key = isModrinth ? `mr-${r.hit.project_id}` : `cf-${r.hit.id}`
          const isInstallingThis = isModrinth ? installing === r.hit.project_id : cfInstalling === r.hit.id
          const progress = isModrinth ? installProgress : cfInstallProgress
          const title = isModrinth ? r.hit.title : r.hit.name
          const description = isModrinth ? r.hit.description : r.hit.summary
          const author = isModrinth ? r.hit.author : r.hit.author
          const downloads = isModrinth ? r.hit.downloads : r.hit.downloadCount
          const iconUrl = isModrinth ? r.hit.icon_url : r.hit.logoUrl
          const handleInstall = () => isModrinth ? onInstall(r.hit) : onInstallCurseforge(r.hit)

          return (
            <div
              key={key}
              onClick={() => onOpenDetail(r)}
              className="flex flex-col gap-2.5 rounded-2xl px-4 py-3 cursor-pointer transition-colors bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)] hover:border-[rgba(75,63,207,0.35)]"
            >
              <div className="flex items-center gap-3">
                <div className="w-11 h-11 rounded-xl flex-shrink-0 overflow-hidden bg-[rgba(255,255,255,0.06)] flex items-center justify-center">
                  {iconUrl ? <img src={iconUrl} alt={title} className="w-full h-full object-cover" /> : <PlugIcon size={20} color="rgba(255,255,255,0.2)" />}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <p className="truncate font-semibold text-white text-[13px]">{title}</p>
                    {isModrinth ? (
                      <span className="flex-shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-[0.03em] bg-[rgba(30,209,102,0.15)] text-[rgba(94,224,152,0.9)]">
                        {t('mods.modrinthBadge')}
                      </span>
                    ) : (
                      <span className="flex-shrink-0 rounded-md px-1.5 py-[1px] text-[9px] font-bold text-[#f16436] bg-[rgba(241,100,54,0.15)] border border-[rgba(241,100,54,0.35)]">
                        CURSEFORGE
                      </span>
                    )}
                  </div>
                  <p className="truncate text-[11px] text-[rgba(255,255,255,0.35)] mt-0.5">{description}</p>
                  <p className="text-[10px] text-[rgba(255,255,255,0.2)] mt-[3px]">{t('mods.byAuthor', { author })} · {formatDownloadCount(downloads)} {t('mods.downloads')}</p>
                </div>
                <motion.button {...pressIf(!(isInstallingThis))}
                  onClick={(e) => { e.stopPropagation(); handleInstall() }}
                  disabled={isInstallingThis}
                  className={`flex-shrink-0 flex items-center gap-1.5 rounded-xl font-semibold transition-all duration-150 active:scale-95 h-8 pl-[14px] pr-[14px] text-[12px] border border-[rgba(75,63,207,0.5)] text-[rgba(255,255,255,0.85)] ${
                    isInstallingThis ? 'bg-[rgba(40,38,65,0.7)] cursor-not-allowed' : 'bg-[rgba(75,63,207,0.3)] cursor-pointer'
                  }`}
                >
                  {isInstallingThis ? (
                    <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.15)" />
                  ) : t('mods.installButton')}
                </motion.button>
              </div>

              {isInstallingThis && progress && (
                <div className="flex items-center gap-2 pl-[56px]">
                  <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
                    <div
                      className="h-full rounded-full bg-[#4B3FCF] transition-all duration-200"
                      style={{ width: `${progress.percent}%` }}
                    />
                  </div>
                  <span className="max-w-[140px] flex-shrink-0 truncate text-[10px] text-[rgba(255,255,255,0.35)]">
                    {progress.label}
                  </span>
                </div>
              )}
            </div>
          )
        })}
      </div>

      <Pagination page={page} pageCount={pageCount} onChange={changePage} disabled={searching} />
    </div>
  )
}
