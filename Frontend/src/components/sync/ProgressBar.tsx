import type { SyncProgress } from '@/types'

export function ProgressBar({ progress }: { progress: SyncProgress }) {
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <span className="text-[11px] text-[rgba(255,255,255,0.5)] font-medium">
          {progress.label}
        </span>
        <span className="text-[11px] text-[rgba(255,255,255,0.35)] font-semibold tabular-nums">
          {progress.percent}%
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
        <div
          className={`h-full rounded-full transition-all duration-300 ${progress.phase === 'done' ? 'bg-[#4ade80]' : 'bg-[#4B3FCF]'}`}
          style={{ width: `${progress.percent}%` }}
        />
      </div>
    </div>
  )
}
