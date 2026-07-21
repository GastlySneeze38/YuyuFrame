export function HealthBadge({ label, ok }: { label: string; ok: boolean }) {
  return (
    <span
      className={
        ok
          ? 'text-[9px] font-semibold px-[7px] py-[2px] rounded whitespace-nowrap bg-[rgba(34,197,94,0.12)] text-[#22c55e] border border-[rgba(34,197,94,0.3)]'
          : 'text-[9px] font-semibold px-[7px] py-[2px] rounded whitespace-nowrap bg-[rgba(239,68,68,0.12)] text-[#ef4444] border border-[rgba(239,68,68,0.3)]'
      }
    >
      {label} {ok ? '✓' : '✗'}
    </span>
  )
}
