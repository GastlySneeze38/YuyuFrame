import { formatDownloadCount } from '@/lib/format'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import type { ModrinthHit } from './modUtils'

export function ModrinthCard({ hit, installed, loading, onInstall, onOpenDetail }: {
  hit: ModrinthHit; installed: boolean; loading: boolean; onInstall: () => void; onOpenDetail: () => void
}) {
  return (
    <div
      onClick={onOpenDetail}
      className="flex cursor-pointer items-center gap-3 rounded-2xl px-4 py-3 transition-all duration-150"
      style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}
      onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,0.05)' }}
      onMouseLeave={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,0.03)' }}
    >
      <div style={{ width: 44, height: 44, borderRadius: 12, flexShrink: 0, overflow: 'hidden', background: 'rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {hit.icon_url ? (
          <img src={hit.icon_url} alt={hit.title} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
        ) : (
          <PlugIcon size={20} color="rgba(255,255,255,0.2)" />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate font-semibold text-white" style={{ fontSize: 13 }}>{hit.title}</p>
        <p className="truncate" style={{ fontSize: 11, color: 'rgba(255,255,255,0.35)', marginTop: 2 }}>{hit.description}</p>
        <p style={{ fontSize: 10, color: 'rgba(255,255,255,0.2)', marginTop: 3 }}>{formatDownloadCount(hit.downloads)} téléchargements</p>
      </div>
      <button
        onClick={(e) => { e.stopPropagation(); onInstall() }}
        disabled={installed || loading}
        className="flex-shrink-0 flex items-center gap-1.5 rounded-xl font-semibold transition-all duration-150 active:scale-95"
        style={{
          height: 32, paddingLeft: 14, paddingRight: 14, fontSize: 12,
          background: installed ? 'rgba(255,255,255,0.05)' : loading ? 'rgba(40,38,65,0.7)' : 'rgba(75,63,207,0.3)',
          border: `1px solid ${installed ? 'rgba(255,255,255,0.08)' : 'rgba(75,63,207,0.5)'}`,
          color: installed ? 'rgba(255,255,255,0.3)' : 'rgba(255,255,255,0.85)',
          cursor: installed || loading ? 'not-allowed' : 'pointer',
        }}
        onMouseEnter={(e) => { if (!installed && !loading) e.currentTarget.style.background = 'rgba(75,63,207,0.5)' }}
        onMouseLeave={(e) => { if (!installed && !loading) e.currentTarget.style.background = 'rgba(75,63,207,0.3)' }}
      >
        {loading ? (
          <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.15)" />
        ) : installed ? '✓ Installé' : 'Installer'}
      </button>
    </div>
  )
}
