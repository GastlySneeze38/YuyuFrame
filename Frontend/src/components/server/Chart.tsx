import { Sparkline } from './Sparkline'

/** Grand graphe temporel plein cadre (style "Graphs" Hetzner) — valeur courante + min/max + aire. */
export function Chart({ title, data, color, unit }: { title: string; data: number[]; color: string; unit?: string }) {
  const current = data.at(-1) ?? 0
  const min = data.length ? Math.min(...data) : 0
  const max = data.length ? Math.max(...data) : 0
  return (
    <div className="bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)] rounded-[10px] p-[14px] min-w-0">
      <div className="flex items-baseline justify-between mb-[10px]">
        <span className="text-[11px] text-[rgba(255,255,255,0.4)] uppercase tracking-[0.8px]">{title}</span>
        <span className="text-[18px] font-bold font-mono" style={{ color }}>
          {current.toFixed(1)}{unit ? <span className="text-[10px] text-[rgba(255,255,255,0.3)] ml-[3px]">{unit}</span> : null}
        </span>
      </div>
      <Sparkline data={data.length ? data : [0, 0]} color={color} height={90} />
      <div className="flex justify-between mt-[6px] text-[9px] text-[rgba(255,255,255,0.25)] font-mono">
        <span>min {min.toFixed(1)}</span>
        <span>max {max.toFixed(1)}</span>
      </div>
    </div>
  )
}
