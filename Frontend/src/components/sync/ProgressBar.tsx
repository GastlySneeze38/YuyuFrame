import type { SyncProgress } from '@/types'

export function ProgressBar({ progress }: { progress: SyncProgress }) {
  const color = progress.phase === 'done' ? '#4ade80' : '#4B3FCF'
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.5)', fontWeight: 500 }}>
          {progress.label}
        </span>
        <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.35)', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
          {progress.percent}%
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full" style={{ background: 'rgba(255,255,255,0.08)' }}>
        <div
          className="h-full rounded-full transition-all duration-300"
          style={{ width: `${progress.percent}%`, background: color }}
        />
      </div>
    </div>
  )
}
