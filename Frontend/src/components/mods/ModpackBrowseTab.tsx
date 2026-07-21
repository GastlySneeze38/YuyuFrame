import type { ModpackHit } from '@/lib/modrinthModpacks'
import { formatDownloadCount } from '@/lib/format'
import { EmptyState } from '@/components/ui/EmptyState'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'

export function ModpackBrowseTab({ query, results, searching, installing, onQueryChange, onInstall }: {
  query: string
  results: ModpackHit[]
  searching: boolean
  installing: string | null
  onQueryChange: (e: React.ChangeEvent<HTMLInputElement>) => void
  onInstall: (hit: ModpackHit) => void
}) {
  return (
    <div className="flex flex-col gap-3">
      <div className="relative">
        <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}
          className="absolute left-3 top-1/2 -translate-y-1/2"
          style={{ color: 'rgba(255,255,255,0.3)', pointerEvents: 'none' }}>
          <path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
        </svg>
        <input
          type="text"
          placeholder="Rechercher un modpack..."
          value={query}
          onChange={onQueryChange}
          className="w-full rounded-xl pl-9 pr-4 text-sm text-white outline-none"
          style={{ height: 40, background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
          onFocus={(e) => { e.currentTarget.style.borderColor = 'rgba(75,63,207,0.6)' }}
          onBlur={(e) => { e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)' }}
        />
        {searching && (
          <ButtonSpinner size={16} color="rgba(75,63,207,0.8)" trackColor="rgba(255,255,255,0.1)" className="absolute right-3 top-1/2 -translate-y-1/2" />
        )}
      </div>

      {!searching && results.length === 0 && (
        <EmptyState
          icon={<SearchIcon size={28} color="rgba(255,255,255,0.15)" />}
          title="Aucun résultat"
          subtitle="Essayez un autre terme de recherche"
        />
      )}

      <div className="flex flex-col gap-2">
        {results.map((hit) => (
          <div key={hit.project_id} className="flex items-center gap-3 rounded-2xl px-4 py-3" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <div style={{ width: 44, height: 44, borderRadius: 12, flexShrink: 0, overflow: 'hidden', background: 'rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              {hit.icon_url ? <img src={hit.icon_url} alt={hit.title} style={{ width: '100%', height: '100%', objectFit: 'cover' }} /> : <PlugIcon size={20} color="rgba(255,255,255,0.2)" />}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold text-white" style={{ fontSize: 13 }}>{hit.title}</p>
              <p className="truncate" style={{ fontSize: 11, color: 'rgba(255,255,255,0.35)', marginTop: 2 }}>{hit.description}</p>
              <p style={{ fontSize: 10, color: 'rgba(255,255,255,0.2)', marginTop: 3 }}>par {hit.author} · {formatDownloadCount(hit.downloads)} téléchargements</p>
            </div>
            <button
              onClick={() => onInstall(hit)}
              disabled={installing === hit.project_id}
              className="flex-shrink-0 flex items-center gap-1.5 rounded-xl font-semibold transition-all duration-150 active:scale-95"
              style={{
                height: 32, paddingLeft: 14, paddingRight: 14, fontSize: 12,
                background: installing === hit.project_id ? 'rgba(40,38,65,0.7)' : 'rgba(75,63,207,0.3)',
                border: '1px solid rgba(75,63,207,0.5)',
                color: 'rgba(255,255,255,0.85)',
                cursor: installing === hit.project_id ? 'not-allowed' : 'pointer',
              }}
            >
              {installing === hit.project_id ? (
                <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.15)" />
              ) : 'Installer'}
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}
