import type { ModpackMeta } from '@/types'
import { formatRelativeDate } from '@/lib/modrinthModpacks'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'

export function ModpackBanner({
  meta, menuOpen, showPackContent, packUpdatesCount, updatingPackAll,
  onToggleMenu, onReplace, onRemove, onToggleShowContent, onUpdateAllPack,
}: {
  meta: ModpackMeta
  menuOpen: boolean
  showPackContent: boolean
  packUpdatesCount: number
  updatingPackAll: boolean
  onToggleMenu: () => void
  onReplace: () => void
  onRemove: () => void
  onToggleShowContent: () => void
  onUpdateAllPack: () => void
}) {
  return (
    <div className="relative mb-4 rounded-2xl px-4 py-3.5 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]">
      <div className="flex items-start gap-3">
        <div className="w-12 h-12 rounded-xl flex-shrink-0 overflow-hidden bg-[rgba(255,255,255,0.06)] flex items-center justify-center">
          {meta.icon_url ? <img src={meta.icon_url} alt="" className="w-full h-full object-cover" /> : <PlugIcon size={22} color="rgba(255,255,255,0.2)" />}
        </div>
        <div className="min-w-0 flex-1">
          <p className="font-bold truncate text-[14px] text-white">{meta.name}</p>
          <p className="text-[11px] text-[rgba(255,255,255,0.35)] mt-px">
            {meta.author} · {meta.version_number} · {formatRelativeDate(meta.date_modified)}
          </p>
          <p className="text-[12px] text-[rgba(255,255,255,0.5)] mt-1.5">{meta.summary}</p>
          <div className="flex items-center gap-2 mt-2">
            <span className="flex items-center gap-1 text-[11px] text-[rgba(255,255,255,0.3)]">
              <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
              {meta.downloads.toLocaleString('fr-FR')}
            </span>
            {meta.categories.slice(0, 3).map((c) => (
              <span key={c} className="rounded-full px-2 py-0.5 text-[10px] bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.4)]">{c}</span>
            ))}
          </div>
        </div>
        <div className="relative flex-shrink-0">
          <button
            onClick={onToggleMenu}
            className="flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-150 text-[rgba(255,255,255,0.3)] bg-[rgba(255,255,255,0.05)]"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}><path d="M12 8a2 2 0 100-4 2 2 0 000 4zm0 2a2 2 0 100 4 2 2 0 000-4zm0 8a2 2 0 100 4 2 2 0 000-4z" /></svg>
          </button>
          {menuOpen && (
            <div className="absolute right-0 top-9 z-20 flex flex-col gap-0.5 rounded-xl p-1 w-[190px] bg-[#191923] border border-[rgba(255,255,255,0.1)] shadow-[0_12px_30px_rgba(0,0,0,0.5)]">
              <button onClick={onToggleShowContent} className="rounded-lg px-3 py-2 text-left transition-all duration-150 text-[12px] text-[rgba(255,255,255,0.8)] hover:bg-[rgba(255,255,255,0.06)]">
                {showPackContent ? 'Masquer le contenu' : 'Afficher le contenu'}
              </button>
              {packUpdatesCount > 0 && (
                <button onClick={onUpdateAllPack} disabled={updatingPackAll} className={`flex items-center justify-between rounded-lg px-3 py-2 text-left transition-all duration-150 text-[12px] ${
                  updatingPackAll
                    ? 'text-[rgba(255,255,255,0.3)] cursor-not-allowed'
                    : 'text-[rgba(250,204,21,0.9)] cursor-pointer hover:bg-[rgba(250,204,21,0.1)]'
                }`}>
                  <span>Mettre à jour le pack</span>
                  <span className="font-bold">{updatingPackAll ? '...' : packUpdatesCount}</span>
                </button>
              )}
              <button onClick={onReplace} className="rounded-lg px-3 py-2 text-left transition-all duration-150 text-[12px] text-[rgba(255,255,255,0.8)] hover:bg-[rgba(255,255,255,0.06)]">
                Remplacer le modpack
              </button>
              <button onClick={onRemove} className="rounded-lg px-3 py-2 text-left transition-all duration-150 text-[12px] text-[rgb(248,113,113)] hover:bg-[rgba(200,50,50,0.12)]">
                Retirer le modpack (désinstalle ses mods)
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
