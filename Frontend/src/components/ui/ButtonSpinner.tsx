// Petit rond qui tourne (état "action en cours" dans un bouton, un champ de
// recherche...) — avant ce fichier, réimplémenté ~20 fois avec des
// tailles/couleurs légèrement différentes au lieu d'être paramétré.
export function ButtonSpinner({
  size = 14,
  color = 'white',
  trackColor = 'rgba(255,255,255,0.2)',
  className = '',
}: {
  size?: number
  color?: string
  trackColor?: string
  className?: string
}) {
  return (
    <span
      className={`animate-spin rounded-full border-2 flex-shrink-0 ${className}`.trim()}
      style={{ width: size, height: size, borderColor: trackColor, borderTopColor: color }}
    />
  )
}
