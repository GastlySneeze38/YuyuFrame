export function PlanBadge({ plan }: { plan: string }) {
  if (plan === 'ultimate') {
    return (
      <span className="rounded-full px-2 py-0.5 text-[10px] font-bold text-[#f59e0b] bg-[rgba(245,158,11,0.15)] tracking-[0.05em]">
        ULTIMATE
      </span>
    )
  }
  if (plan === 'premium') {
    return (
      <span className="rounded-full px-2 py-0.5 text-[10px] font-bold text-[#818cf8] bg-[rgba(75,63,207,0.2)] tracking-[0.05em]">
        PREMIUM
      </span>
    )
  }
  return (
    <span className="rounded-full px-2 py-0.5 text-[10px] font-bold text-[rgba(255,255,255,0.4)] bg-[rgba(255,255,255,0.07)] tracking-[0.05em]">
      FREE
    </span>
  )
}
