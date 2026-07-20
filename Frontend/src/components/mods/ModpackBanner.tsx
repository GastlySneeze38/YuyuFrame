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
    <div className="relative mb-4 rounded-2xl px-4 py-3.5" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.07)' }}>
      <div className="flex items-start gap-3">
        <div style={{ width: 48, height: 48, borderRadius: 12, flexShrink: 0, overflow: 'hidden', background: 'rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          {meta.icon_url ? <img src={meta.icon_url} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} /> : <PlugIcon size={22} color="rgba(255,255,255,0.2)" />}
        </div>
        <div className="min-w-0 flex-1">
          <p className="font-bold truncate" style={{ fontSize: 14, color: 'white' }}>{meta.name}</p>
          <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.35)', marginTop: 1 }}>
            {meta.author} · {meta.version_number} · {formatRelativeDate(meta.date_modified)}
          </p>
          <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.5)', marginTop: 6 }}>{meta.summary}</p>
          <div className="flex items-center gap-2 mt-2">
            <span className="flex items-center gap-1" style={{ fontSize: 11, color: 'rgba(255,255,255,0.3)' }}>
              <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
              {meta.downloads.toLocaleString('fr-FR')}
            </span>
            {meta.categories.slice(0, 3).map((c) => (
              <span key={c} className="rounded-full px-2 py-0.5" style={{ fontSize: 10, background: 'rgba(255,255,255,0.06)', color: 'rgba(255,255,255,0.4)' }}>{c}</span>
            ))}
          </div>
        </div>
        <div className="relative flex-shrink-0">
          <button
            onClick={onToggleMenu}
            className="flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-150"
            style={{ color: 'rgba(255,255,255,0.3)', background: 'rgba(255,255,255,0.05)' }}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}><path d="M12 8a2 2 0 100-4 2 2 0 000 4zm0 2a2 2 0 100 4 2 2 0 000-4zm0 8a2 2 0 100 4 2 2 0 000-4z" /></svg>
          </button>
          {menuOpen && (
            <div className="absolute right-0 top-9 z-20 flex flex-col gap-0.5 rounded-xl p-1" style={{ width: 190, background: '#191923', border: '1px solid rgba(255,255,255,0.1)', boxShadow: '0 12px 30px rgba(0,0,0,0.5)' }}>
              <button onClick={onToggleShowContent} className="rounded-lg px-3 py-2 text-left transition-all duration-150" style={{ fontSize: 12, color: 'rgba(255,255,255,0.8)' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,0.06)' }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent' }}>
                {showPackContent ? 'Masquer le contenu' : 'Afficher le contenu'}
              </button>
              {packUpdatesCount > 0 && (
                <button onClick={onUpdateAllPack} disabled={updatingPackAll} className="flex items-center justify-between rounded-lg px-3 py-2 text-left transition-all duration-150" style={{ fontSize: 12, color: updatingPackAll ? 'rgba(255,255,255,0.3)' : 'rgba(250,204,21,0.9)', cursor: updatingPackAll ? 'not-allowed' : 'pointer' }}
                  onMouseEnter={(e) => { if (!updatingPackAll) e.currentTarget.style.background = 'rgba(250,204,21,0.1)' }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent' }}>
                  <span>Mettre à jour le pack</span>
                  <span style={{ fontWeight: 700 }}>{updatingPackAll ? '...' : packUpdatesCount}</span>
                </button>
              )}
              <button onClick={onReplace} className="rounded-lg px-3 py-2 text-left transition-all duration-150" style={{ fontSize: 12, color: 'rgba(255,255,255,0.8)' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,0.06)' }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent' }}>
                Remplacer le modpack
              </button>
              <button onClick={onRemove} className="rounded-lg px-3 py-2 text-left transition-all duration-150" style={{ fontSize: 12, color: 'rgb(248,113,113)' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(200,50,50,0.12)' }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent' }}>
                Retirer le modpack (désinstalle ses mods)
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
