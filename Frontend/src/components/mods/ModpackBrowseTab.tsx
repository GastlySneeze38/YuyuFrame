import type { ModpackHit } from '@/lib/modrinthModpacks'
import { formatDownloadCount } from '@/lib/format'
import { EmptyState } from '@/components/ui/EmptyState'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'

export function ModpackBrowseTab({ query, results, searching, installing, installProgress, onQueryChange, onInstall }: {
  query: string
  results: ModpackHit[]
  searching: boolean
  installing: string | null
  installProgress?: { percent: number; label: string } | null
  onQueryChange: (e: React.ChangeEvent<HTMLInputElement>) => void
  onInstall: (hit: ModpackHit) => void
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-3">
      <div className="relative">
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

      {!searching && results.length === 0 && (
        <EmptyState
          icon={<SearchIcon size={28} color="rgba(255,255,255,0.15)" />}
          title={t('mods.noResults')}
          subtitle={t('mods.tryAnotherSearchTerm')}
        />
      )}

      <div className="flex flex-col gap-2">
        {results.map((hit) => {
          const isInstallingThis = installing === hit.project_id
          return (
            <div key={hit.project_id} className="flex flex-col gap-2.5 rounded-2xl px-4 py-3 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)]">
              <div className="flex items-center gap-3">
                <div className="w-11 h-11 rounded-xl flex-shrink-0 overflow-hidden bg-[rgba(255,255,255,0.06)] flex items-center justify-center">
                  {hit.icon_url ? <img src={hit.icon_url} alt={hit.title} className="w-full h-full object-cover" /> : <PlugIcon size={20} color="rgba(255,255,255,0.2)" />}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold text-white text-[13px]">{hit.title}</p>
                  <p className="truncate text-[11px] text-[rgba(255,255,255,0.35)] mt-0.5">{hit.description}</p>
                  <p className="text-[10px] text-[rgba(255,255,255,0.2)] mt-[3px]">{t('mods.byAuthor', { author: hit.author })} · {formatDownloadCount(hit.downloads)} {t('mods.downloads')}</p>
                </div>
                <button
                  onClick={() => onInstall(hit)}
                  disabled={isInstallingThis}
                  className={`flex-shrink-0 flex items-center gap-1.5 rounded-xl font-semibold transition-all duration-150 active:scale-95 h-8 pl-[14px] pr-[14px] text-[12px] border border-[rgba(75,63,207,0.5)] text-[rgba(255,255,255,0.85)] ${
                    isInstallingThis ? 'bg-[rgba(40,38,65,0.7)] cursor-not-allowed' : 'bg-[rgba(75,63,207,0.3)] cursor-pointer'
                  }`}
                >
                  {isInstallingThis ? (
                    <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.15)" />
                  ) : t('mods.installButton')}
                </button>
              </div>

              {isInstallingThis && installProgress && (
                <div className="flex items-center gap-2 pl-[56px]">
                  <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
                    <div
                      className="h-full rounded-full bg-[#4B3FCF] transition-all duration-200"
                      style={{ width: `${installProgress.percent}%` }}
                    />
                  </div>
                  <span className="max-w-[140px] flex-shrink-0 truncate text-[10px] text-[rgba(255,255,255,0.35)]">
                    {installProgress.label}
                  </span>
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
