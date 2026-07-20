/** Ligne label/valeur atténuée — la valeur n'est plus en blanc pur pour rester discrète face au titre. */
export function StatRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-2" style={{ fontSize: 11 }}>
      <span style={{ color: 'rgba(255,255,255,0.4)' }}>{label}</span>
      <span style={{ color: 'rgba(255,255,255,0.78)', fontFamily: 'monospace', fontSize: 13 }}>{value}</span>
    </div>
  )
}
