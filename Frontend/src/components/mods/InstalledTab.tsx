import type { Mod, ModpackMeta } from '@/types'
import { Spinner } from '@/components/ui/Spinner'
import { ErrorState } from '@/components/ui/ErrorState'
import { EmptyState } from '@/components/ui/EmptyState'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { displayName, baseFilename, type ModrinthInfo, type ModUpdate } from './modUtils'
import { ModRow } from './ModRow'

export function InstalledTab({
  mods, modpackMeta, showPackContent, loading, error, isPlugin, modSearch, onModSearch, logoCache, versionMap,
  updates, updatingMods, updatingAll, onReload, onToggle, onDelete, onUpdateMod, onBrowseExtra, onUploadExtra,
}: {
  mods: Mod[]
  modpackMeta: ModpackMeta | null
  showPackContent: boolean
  loading: boolean
  error: string
  isPlugin: boolean
  modSearch: string
  onModSearch: (v: string) => void
  logoCache: Record<string, string | null>
  versionMap: Record<string, ModrinthInfo>
  updates: ModUpdate[]
  updatingMods: Set<string>
  updatingAll: boolean
  onReload: () => void
  onToggle: (mod: Mod) => void
  onDelete: (name: string) => void
  onUpdateMod: (u: ModUpdate) => void
  onBrowseExtra: () => void
  onUploadExtra: () => void
}) {
  if (loading) return <Spinner />
  if (error) return <ErrorState message={error} onRetry={onReload} />

  const filtered = mods.filter((m) =>
    displayName(m.name).toLowerCase().includes(modSearch.toLowerCase()),
  )

  const hdrClass = 'text-[10px] text-[rgba(255,255,255,0.28)] uppercase tracking-[0.09em] font-semibold'

  const renderHeader = () => (
    <div
      className="flex items-center rounded-2xl px-4 py-1.5 sticky -top-3 z-10 mb-2 bg-[#09090D] border border-[rgba(255,255,255,0.08)]"
    >
      <div className="flex items-center gap-3 min-w-0 flex-1">
        <div className="w-9 flex-shrink-0" />
        <div className="min-w-0 flex-1"><span className={hdrClass}>Mod</span></div>
      </div>
      <div className="flex-shrink-0 w-[110px] text-center px-2">
        <span className={hdrClass}>Version</span>
      </div>
      <div className="flex items-center justify-end gap-2 flex-1">
        <span className={hdrClass}>Action</span>
      </div>
    </div>
  )

  const renderRow = (mod: Mod) => {
    const update = updates.find((u) => u.mod.sha1 === mod.sha1) ?? null
    return (
      <ModRow
        key={mod.name}
        mod={mod}
        version={versionMap[mod.sha1]?.version ?? null}
        modrinthName={versionMap[mod.sha1]?.modrinthName || null}
        update={update}
        updating={updatingMods.has(mod.sha1) || updatingAll}
        logoUrl={logoCache[displayName(mod.name)] ?? null}
        onToggle={onToggle}
        onDelete={onDelete}
        onUpdate={onUpdateMod}
      />
    )
  }

  const searchBar = mods.length > 0 && (
    <div className="relative mb-3">
      <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}
        className="absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none text-[rgba(255,255,255,0.3)]">
        <path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
      </svg>
      <input
        type="text"
        placeholder="Filtrer les mods..."
        value={modSearch}
        onChange={(e) => onModSearch(e.target.value)}
        className="w-full rounded-xl pl-8 pr-4 text-sm text-white outline-none h-9 bg-[rgba(255,255,255,0.05)] border border-[rgba(255,255,255,0.08)] focus:border-[rgba(75,63,207,0.6)]"
      />
    </div>
  )

  if (modpackMeta) {
    const packFiles = new Set(modpackMeta.mod_files.map((f) => f.toLowerCase()))
    const packMods = filtered.filter((m) => packFiles.has(baseFilename(m.name).toLowerCase()))
    const extraMods = filtered.filter((m) => !packFiles.has(baseFilename(m.name).toLowerCase()))
    const sectionHdrClass = 'text-[11px] font-bold text-[rgba(255,255,255,0.4)] uppercase tracking-[0.06em] mb-2'

    return (
      <div>
        {searchBar}

        {showPackContent && packMods.length > 0 && (
          <div className="mb-5">
            <p className={sectionHdrClass}>Contenu du modpack ({packMods.length})</p>
            {renderHeader()}
            <div className="flex flex-col gap-2">{packMods.map(renderRow)}</div>
          </div>
        )}

        <div>
          <p className={sectionHdrClass}>Contenu supplémentaire ({extraMods.length})</p>
          {extraMods.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-4 rounded-2xl py-12 bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.05)]">
              <div className="w-14 h-14 rounded-2xl bg-[rgba(255,255,255,0.04)] flex items-center justify-center">
                <PlugIcon size={26} color="rgba(255,255,255,0.15)" />
              </div>
              <div className="text-center">
                <p className="font-semibold text-[rgba(255,255,255,0.5)] text-[14px]">Aucun contenu supplémentaire</p>
                <p className="text-[rgba(255,255,255,0.2)] text-[12px] mt-1">Ajoutez du contenu en plus de ce modpack</p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={onUploadExtra}
                  className="flex items-center gap-1.5 rounded-xl font-semibold transition-all duration-150 h-[34px] px-[14px] text-[12px] bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.7)] border border-[rgba(255,255,255,0.1)]"
                >
                  <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
                  Importer un fichier
                </button>
                <button
                  onClick={onBrowseExtra}
                  className="flex items-center gap-1.5 rounded-xl font-semibold transition-all duration-150 h-[34px] px-[14px] text-[12px] bg-[rgba(75,63,207,0.3)] text-white border border-[rgba(75,63,207,0.5)]"
                >
                  <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" /></svg>
                  Parcourir le contenu
                </button>
              </div>
            </div>
          ) : (
            <>
              {renderHeader()}
              <div className="flex flex-col gap-2">{extraMods.map(renderRow)}</div>
            </>
          )}
        </div>
      </div>
    )
  }

  return (
    <div>
      {searchBar}

      {/* États vides */}
      {filtered.length === 0 && mods.length === 0 && (
        <EmptyState
          icon={<PlugIcon size={28} color="rgba(75,63,207,0.55)" />}
          title={isPlugin ? 'Aucun plugin installé' : 'Aucun mod installé'}
          subtitle={isPlugin
            ? 'Importez un .jar ou parcourez les plugins'
            : 'Cliquez sur "Importer un mod" ou parcourez Modrinth'}
        />
      )}
      {filtered.length === 0 && mods.length > 0 && (
        <p className="text-[13px] text-[rgba(255,255,255,0.3)] text-center mt-8">
          Aucun mod ne correspond à « {modSearch} »
        </p>
      )}

      {filtered.length > 0 && renderHeader()}

      <div className="flex flex-col gap-2">{filtered.map(renderRow)}</div>
    </div>
  )
}
