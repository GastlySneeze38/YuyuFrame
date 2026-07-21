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
        <span className="text-[10px] font-bold text-[rgba(255,255,255,0.28)] tracking-[0.1em] uppercase">
          Contenu dans le cloud
        </span>
        <span className="text-[10px] text-[rgba(255,255,255,0.2)]">
          {formatDateTime(cloudEntry.updated_at)}
        </span>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {chips.map((chip) => (
          <div
            key={chip.label}
            className="flex items-center gap-1.5 rounded-lg px-2 py-1 bg-[rgba(255,255,255,0.05)] border border-[rgba(255,255,255,0.08)]"
          >
            <span className="text-[11px]">{chip.icon}</span>
            <span className="text-[11px] text-[rgba(255,255,255,0.55)] font-medium">{chip.label}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
