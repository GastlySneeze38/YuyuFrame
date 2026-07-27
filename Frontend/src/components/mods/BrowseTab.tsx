import { EmptyState } from '@/components/ui/EmptyState'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'
import type { ModrinthHit } from './modUtils'
import { ModrinthCard } from './ModrinthCard'

export function BrowseTab({
  query, results, searching, installing, installProgress, isInstalled, isPlugin,
  onQueryChange, onInstall, onOpenDetail,
}: {
  query: string
  results: ModrinthHit[]
  searching: boolean
  installing: string | null
  installProgress?: { percent: number; label: string } | null
  isInstalled: (hit: ModrinthHit) => boolean
  isPlugin: boolean
  onQueryChange: (e: React.ChangeEvent<HTMLInputElement>) => void
  onInstall: (hit: ModrinthHit) => void
  onOpenDetail: (hit: ModrinthHit) => void
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
          placeholder={isPlugin ? t('mods.searchPluginPlaceholder') : t('mods.searchModPlaceholder')}
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
