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
      className="flex cursor-pointer items-center gap-3 rounded-2xl px-4 py-3 transition-all duration-150 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)] hover:bg-[rgba(255,255,255,0.05)]"
    >
      <div className="w-11 h-11 rounded-xl flex-shrink-0 overflow-hidden bg-[rgba(255,255,255,0.06)] flex items-center justify-center">
        {hit.icon_url ? (
          <img src={hit.icon_url} alt={hit.title} className="w-full h-full object-cover" />
        ) : (
          <PlugIcon size={20} color="rgba(255,255,255,0.2)" />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate font-semibold text-white text-[13px]">{hit.title}</p>
        <p className="truncate text-[11px] text-[rgba(255,255,255,0.35)] mt-0.5">{hit.description}</p>
        <p className="text-[10px] text-[rgba(255,255,255,0.2)] mt-[3px]">{formatDownloadCount(hit.downloads)} téléchargements</p>
      </div>
      <button
        onClick={(e) => { e.stopPropagation(); onInstall() }}
        disabled={installed || loading}
        className={`flex-shrink-0 flex items-center gap-1.5 rounded-xl font-semibold transition-all duration-150 active:scale-95 h-8 pl-[14px] pr-[14px] text-[12px] border ${
          installed
            ? 'bg-[rgba(255,255,255,0.05)] border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.3)] cursor-not-allowed'
            : loading
              ? 'bg-[rgba(40,38,65,0.7)] border-[rgba(75,63,207,0.5)] text-[rgba(255,255,255,0.85)] cursor-not-allowed'
              : 'bg-[rgba(75,63,207,0.3)] border-[rgba(75,63,207,0.5)] text-[rgba(255,255,255,0.85)] cursor-pointer hover:bg-[rgba(75,63,207,0.5)]'
        }`}
      >
        {loading ? (
          <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.15)" />
        ) : installed ? '✓ Installé' : 'Installer'}
      </button>
    </div>
  )
}
