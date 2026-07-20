import type { SyncInstance } from '@/types'
import { formatDateTime } from '@/lib/format'

export function CloudContentSummary({ cloudEntry }: { cloudEntry: SyncInstance }) {
  const chips = [
    { label: 'mods/', icon: '📦' },
    { label: 'config/', icon: '⚙️' },
    ...cloudEntry.save_names.map((n) => ({ label: n, icon: '💾' })),
  ]

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span style={{ fontSize: 10, fontWeight: 700, color: 'rgba(255,255,255,0.28)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>
          Contenu dans le cloud
        </span>
        <span style={{ fontSize: 10, color: 'rgba(255,255,255,0.2)' }}>
          {formatDateTime(cloudEntry.updated_at)}
        </span>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {chips.map((chip) => (
          <div
            key={chip.label}
            className="flex items-center gap-1.5 rounded-lg px-2 py-1"
            style={{ background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
          >
            <span style={{ fontSize: 11 }}>{chip.icon}</span>
            <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', fontWeight: 500 }}>{chip.label}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
