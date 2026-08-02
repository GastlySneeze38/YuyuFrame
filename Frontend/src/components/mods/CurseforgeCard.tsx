import { formatDownloadCount } from '@/lib/format'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'
import type { CurseforgeHit } from './curseforgeUtils'

export function CurseforgeCard({ hit, installed, loading, progress, onInstall }: {
  hit: CurseforgeHit
  installed: boolean
  loading: boolean
  progress?: { percent: number; label: string } | null
  onInstall: () => void
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-2.5 rounded-2xl px-4 py-3 transition-all duration-150 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)]">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-xl flex-shrink-0 overflow-hidden bg-[rgba(255,255,255,0.06)] flex items-center justify-center">
          {hit.logoUrl ? (
            <img src={hit.logoUrl} alt={hit.name} className="w-full h-full object-cover" />
          ) : (
            <PlugIcon size={20} color="rgba(255,255,255,0.2)" />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-white text-[13px]">{hit.name}</p>
          <p className="truncate text-[11px] text-[rgba(255,255,255,0.35)] mt-0.5">{hit.summary}</p>
          <p className="text-[10px] text-[rgba(255,255,255,0.2)] mt-[3px]">{formatDownloadCount(hit.downloadCount)} {t('mods.downloads')}</p>
        </div>
        <button
          onClick={onInstall}
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
          ) : installed ? t('mods.installedButton') : t('mods.installButton')}
        </button>
      </div>

      {loading && progress && (
        <div className="flex items-center gap-2 pl-[56px]">
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
            <div
              className="h-full rounded-full bg-[#4B3FCF] transition-all duration-200"
              style={{ width: `${progress.percent}%` }}
            />
          </div>
          <span className="w-[90px] flex-shrink-0 text-[10px] text-[rgba(255,255,255,0.35)] tabular-nums">
            {progress.label}
          </span>
        </div>
      )}
    </div>
  )
}
