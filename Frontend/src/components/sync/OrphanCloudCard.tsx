import { useState } from 'react'
import type { SyncInstance } from '@/types'
import { loaderColor } from '@/lib/loader'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { CloudContentSummary } from './CloudContentSummary'
import { useT } from '@/i18n'

export function OrphanCloudCard({ ci, onRestore, onDelete }: {
  ci: SyncInstance
  onRestore: (ci: SyncInstance) => Promise<void>
  onDelete: (id: number) => Promise<void>
}) {
  const t = useT()
  const [expanded, setExpanded] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const busy = restoring || deleting

  return (
    <div
      className="rounded-2xl overflow-hidden bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.06)]"
    >
      <button
        className="flex items-center gap-3 w-full px-4 py-3 text-left cursor-pointer"
        onClick={() => setExpanded((v) => !v)}
      >
        <div
          className="flex items-center justify-center rounded-xl flex-shrink-0 w-9 h-9 text-[15px] opacity-70 bg-[rgba(255,255,255,0.04)]"
        >
          ☁️
        </div>
        <div className="flex-1 min-w-0">
          <p className="font-semibold truncate text-[13px] text-[rgba(255,255,255,0.5)]">
            {ci.instance_name}
          </p>
          <div className="flex items-center gap-2 mt-0.5">
            <span className="text-[11px] font-semibold" style={{ color: loaderColor(ci.loader) }}>{ci.loader}</span>
            <span className="text-[11px] text-[rgba(255,255,255,0.2)]">{ci.mc_version}</span>
            <span className="text-[11px] text-[rgba(255,255,255,0.15)]">{new Date(ci.updated_at).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span>
          </div>
        </div>
        <div
          className={`flex items-center justify-center flex-shrink-0 rounded-lg transition-all duration-200 w-7 h-7 bg-[rgba(255,255,255,0.04)] ${expanded ? 'rotate-180' : 'rotate-0'}`}
        >
          <svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.25)" width={13} height={13}>
            <path d="M7 10l5 5 5-5z" />
          </svg>
        </div>
      </button>

      {expanded && (
        <div
          className="flex flex-col gap-3 px-4 pb-4 border-t border-[rgba(255,255,255,0.05)] pt-3.5"
        >
          <CloudContentSummary cloudEntry={ci} />

          <div className="flex gap-2">
            {ci.file_count > 0 && (
              <button
                onClick={async () => { setRestoring(true); await onRestore(ci); setRestoring(false) }}
                disabled={busy}
                className={`flex-[2] flex items-center justify-center gap-1.5 font-semibold transition-all duration-150 h-9 rounded-[10px] text-[12px] bg-[rgba(74,222,128,0.1)] border border-[rgba(74,222,128,0.18)] ${busy ? 'text-[rgba(255,255,255,0.2)] cursor-not-allowed' : 'text-[rgba(74,222,128,0.85)] cursor-pointer hover:bg-[rgba(74,222,128,0.18)]'}`}
              >
                {restoring
                  ? <ButtonSpinner size={14} />
                  : <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12} className="rotate-180 flex-shrink-0"><path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z" /></svg>
                }
                {t('sync.downloadInstance')}
              </button>
            )}
            <button
              onClick={async () => { setDeleting(true); await onDelete(ci.id); setDeleting(false) }}
              disabled={busy}
              className={`flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl transition-all duration-150 bg-[rgba(255,255,255,0.04)] ${busy ? 'text-[rgba(255,255,255,0.18)] cursor-not-allowed' : 'text-[rgba(255,255,255,0.18)] cursor-pointer hover:text-[rgb(248,113,113)] hover:bg-[rgba(200,50,50,0.12)]'}`}
            >
              {deleting
                ? <ButtonSpinner size={14} color="rgb(248,113,113)" trackColor="rgba(255,255,255,0.15)" />
                : <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" /></svg>
              }
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
