export function HealthBadge({ label, ok }: { label: string; ok: boolean }) {
  return (
    <span style={{
      fontSize: 9, fontWeight: 600, padding: '2px 7px', borderRadius: 4, whiteSpace: 'nowrap',
      background: ok ? 'rgba(34,197,94,0.12)' : 'rgba(239,68,68,0.12)',
      color: ok ? '#22c55e' : '#ef4444', border: `1px solid ${ok ? 'rgba(34,197,94,0.3)' : 'rgba(239,68,68,0.3)'}`,
    }}>
      {label} {ok ? '✓' : '✗'}
    </span>
  )
}
