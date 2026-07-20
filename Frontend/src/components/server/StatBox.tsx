export function StatBox({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ background: 'rgba(255,255,255,0.04)', borderRadius: 6, padding: '8px 10px', flex: '1 1 130px', minWidth: 0 }}>
      <div style={{ color: 'rgba(255,255,255,0.3)', marginBottom: 6, fontSize: 10, fontFamily: 'monospace', textTransform: 'uppercase', letterSpacing: 1 }}>{title}</div>
      <div className="flex flex-col gap-1">{children}</div>
    </div>
  )
}
