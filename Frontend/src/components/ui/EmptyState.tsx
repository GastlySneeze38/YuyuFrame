// Avant ce fichier, ce composant existait en 2 versions différentes
// (Mods.tsx EmptyState — icône encadrée, grande — et Stats.tsx EmptyState —
// icône nue, compacte) ; la variante `compact` couvre le 2e cas.

export function EmptyState({
  icon,
  title,
  subtitle,
  compact = false,
}: {
  icon: React.ReactNode
  title: string
  subtitle?: string
  compact?: boolean
}) {
  if (compact) {
    return (
      <div className="flex flex-col items-center justify-center py-8 gap-2">
        {icon}
        <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.25)' }}>{title}</span>
        {subtitle && <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.15)' }}>{subtitle}</span>}
      </div>
    )
  }

  return (
    <div className="flex h-48 flex-col items-center justify-center gap-4">
      <div style={{ width: 56, height: 56, borderRadius: 16, background: 'rgba(255,255,255,0.04)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {icon}
      </div>
      <div className="text-center">
        <p className="font-semibold" style={{ color: 'rgba(255,255,255,0.5)', fontSize: 14 }}>{title}</p>
        {subtitle && <p style={{ color: 'rgba(255,255,255,0.2)', fontSize: 12, marginTop: 4 }}>{subtitle}</p>}
      </div>
    </div>
  )
}
