/** Petit graphe SVG fait main (polyline + aire translucide), sans dépendance externe. Étiré en 100% via viewBox. */
export function Sparkline({ data, color, height = 26 }: { data: number[]; color: string; height?: number }) {
  const VW = 240
  if (data.length < 2) {
    return <svg width="100%" height={height} className="block" />
  }
  const min = Math.min(...data)
  const max = Math.max(...data)
  const range = max - min || 1
  const stepX = VW / (data.length - 1)
  const linePoints = data.map((v, i) => {
    const x = i * stepX
    const y = height - ((v - min) / range) * height
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const areaPoints = `0,${height} ${linePoints.join(' ')} ${VW},${height}`
  return (
    <svg viewBox={`0 0 ${VW} ${height}`} preserveAspectRatio="none" width="100%" height={height} className="block">
      <polyline points={areaPoints} fill={`${color}22`} stroke="none" />
      <polyline points={linePoints.join(' ')} fill="none" stroke={color} strokeWidth={1.5} vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  )
}
