import { Sparkline } from './Sparkline'

/** Grand graphe temporel plein cadre (style "Graphs" Hetzner) — valeur courante + min/max + aire. */
export function Chart({ title, data, color, unit }: { title: string; data: number[]; color: string; unit?: string }) {
  const current = data.at(-1) ?? 0
  const min = data.length ? Math.min(...data) : 0
  const max = data.length ? Math.max(...data) : 0
  return (
    <div style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)', borderRadius: 10, padding: 14, minWidth: 0 }}>
      <div className="flex items-baseline justify-between" style={{ marginBottom: 10 }}>
        <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.4)', textTransform: 'uppercase', letterSpacing: 0.8 }}>{title}</span>
        <span style={{ fontSize: 18, fontWeight: 700, color, fontFamily: 'monospace' }}>
          {current.toFixed(1)}{unit ? <span style={{ fontSize: 10, color: 'rgba(255,255,255,0.3)', marginLeft: 3 }}>{unit}</span> : null}
        </span>
      </div>
      <Sparkline data={data.length ? data : [0, 0]} color={color} height={90} />
      <div className="flex justify-between" style={{ marginTop: 6, fontSize: 9, color: 'rgba(255,255,255,0.25)', fontFamily: 'monospace' }}>
        <span>min {min.toFixed(1)}</span>
        <span>max {max.toFixed(1)}</span>
      </div>
    </div>
  )
}
