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
        <span className="text-[12px] text-[rgba(255,255,255,0.25)]">{title}</span>
        {subtitle && <span className="text-[11px] text-[rgba(255,255,255,0.15)]">{subtitle}</span>}
      </div>
    )
  }

  return (
    <div className="flex h-48 flex-col items-center justify-center gap-4">
      <div className="w-[56px] h-[56px] rounded-2xl bg-[rgba(255,255,255,0.04)] flex items-center justify-center">
        {icon}
      </div>
      <div className="text-center">
        <p className="font-semibold text-[14px] text-[rgba(255,255,255,0.5)]">{title}</p>
        {subtitle && <p className="text-[12px] text-[rgba(255,255,255,0.2)] mt-1">{subtitle}</p>}
      </div>
    </div>
  )
}
