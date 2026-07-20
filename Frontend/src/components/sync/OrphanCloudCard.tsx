import { useState } from 'react'
import type { SyncInstance } from '@/types'
import { loaderColor } from '@/lib/loader'
import { formatDateTime } from '@/lib/format'
import { CloudContentSummary } from './CloudContentSummary'

export function OrphanCloudCard({ ci, onRestore, onDelete }: {
  ci: SyncInstance
  onRestore: (ci: SyncInstance) => Promise<void>
  onDelete: (id: number) => Promise<void>
}) {
  const [expanded, setExpanded] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const busy = restoring || deleting

  return (
    <div
      className="rounded-2xl overflow-hidden"
      style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.06)' }}
    >
      <button
        className="flex items-center gap-3 w-full px-4 py-3 text-left"
        onClick={() => setExpanded((v) => !v)}
        style={{ cursor: 'pointer' }}
      >
        <div
          className="flex items-center justify-center rounded-xl flex-shrink-0"
          style={{ width: 36, height: 36, background: 'rgba(255,255,255,0.04)', fontSize: 15, opacity: 0.7 }}
        >
          ☁️
        </div>
        <div className="flex-1 min-w-0">
          <p className="font-semibold truncate" style={{ fontSize: 13, color: 'rgba(255,255,255,0.5)' }}>
            {ci.instance_name}
          </p>
          <div className="flex items-center gap-2 mt-0.5">
            <span style={{ fontSize: 11, color: loaderColor(ci.loader), fontWeight: 600 }}>{ci.loader}</span>
            <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.2)' }}>{ci.mc_version}</span>
            <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.15)' }}>{formatDateTime(ci.updated_at)}</span>
          </div>
        </div>
        <div
          className="flex items-center justify-center flex-shrink-0 rounded-lg transition-all duration-200"
          style={{
            width: 28, height: 28,
            background: 'rgba(255,255,255,0.04)',
            transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)',
          }}
        >
          <svg viewBox="0 0 24 24" fill="rgba(255,255,255,0.25)" width={13} height={13}>
            <path d="M7 10l5 5 5-5z" />
          </svg>
        </div>
      </button>

      {expanded && (
        <div
          className="flex flex-col gap-3 px-4 pb-4"
          style={{ borderTop: '1px solid rgba(255,255,255,0.05)', paddingTop: 14 }}
        >
          <CloudContentSummary cloudEntry={ci} />

          <div className="flex gap-2">
            {ci.has_data && (
              <button
                onClick={async () => { setRestoring(true); await onRestore(ci); setRestoring(false) }}
                disabled={busy}
                className="flex items-center justify-center gap-1.5 font-semibold transition-all duration-150"
                style={{
                  flex: 2, height: 36, borderRadius: 10, fontSize: 12,
                  background: 'rgba(74,222,128,0.1)',
                  color: busy ? 'rgba(255,255,255,0.2)' : 'rgba(74,222,128,0.85)',
                  border: '1px solid rgba(74,222,128,0.18)',
                  cursor: busy ? 'not-allowed' : 'pointer',
                }}
                onMouseEnter={(e) => { if (!busy) e.currentTarget.style.background = 'rgba(74,222,128,0.18)' }}
                onMouseLeave={(e) => { if (!busy) e.currentTarget.style.background = 'rgba(74,222,128,0.1)' }}
              >
                {restoring
                  ? <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 flex-shrink-0" style={{ borderColor: 'rgba(255,255,255,0.2)', borderTopColor: 'white' }} />
                  : <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12} style={{ transform: 'rotate(180deg)', flexShrink: 0 }}><path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z" /></svg>
                }
                Télécharger l'instance
              </button>
            )}
            <button
              onClick={async () => { setDeleting(true); await onDelete(ci.id); setDeleting(false) }}
              disabled={busy}
              className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl transition-all duration-150"
              style={{ color: 'rgba(255,255,255,0.18)', background: 'rgba(255,255,255,0.04)', cursor: busy ? 'not-allowed' : 'pointer' }}
              onMouseEnter={(e) => { if (!busy) { e.currentTarget.style.color = 'rgb(248,113,113)'; e.currentTarget.style.background = 'rgba(200,50,50,0.12)' } }}
              onMouseLeave={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.18)'; e.currentTarget.style.background = 'rgba(255,255,255,0.04)' }}
            >
              {deleting
                ? <span className="h-3.5 w-3.5 animate-spin rounded-full border-2" style={{ borderColor: 'rgba(255,255,255,0.15)', borderTopColor: 'rgb(248,113,113)' }} />
                : <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" /></svg>
              }
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
