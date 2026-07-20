export function PlanBadge({ plan }: { plan: string }) {
  if (plan === 'ultimate') {
    return (
      <span
        className="rounded-full px-2 py-0.5"
        style={{ fontSize: 10, fontWeight: 700, color: '#f59e0b', background: 'rgba(245,158,11,0.15)', letterSpacing: '0.05em' }}
      >
        ULTIMATE
      </span>
    )
  }
  if (plan === 'premium') {
    return (
      <span
        className="rounded-full px-2 py-0.5"
        style={{ fontSize: 10, fontWeight: 700, color: '#818cf8', background: 'rgba(75,63,207,0.2)', letterSpacing: '0.05em' }}
      >
        PREMIUM
      </span>
    )
  }
  return (
    <span
      className="rounded-full px-2 py-0.5"
      style={{ fontSize: 10, fontWeight: 700, color: 'rgba(255,255,255,0.4)', background: 'rgba(255,255,255,0.07)', letterSpacing: '0.05em' }}
    >
      FREE
    </span>
  )
}
