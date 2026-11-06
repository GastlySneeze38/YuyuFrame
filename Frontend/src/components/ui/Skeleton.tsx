import type { CSSProperties } from 'react'

/**
 * Formes grises en attendant le contenu.
 *
 * Elles ne servent pas à « faire patienter » : elles réservent la place que
 * le vrai contenu occupera, pour que la page arrive d'un bloc au lieu de
 * sauter quand les données tombent. Un rond de chargement au milieu du vide,
 * lui, ne dit rien de ce qui va apparaître et laisse l'écran bouger.
 *
 * L'onde est une animation CSS (`.skeleton` dans index.css, 2026-11-06) et
 * non plus un `motion.div` par bloc : une page de squelettes en lançait des
 * dizaines côté JavaScript. Le compositeur la joue seul, et « Animations
 * réduites » l'arrête comme le reste.
 */
export function Skeleton({ className = '', style }: { className?: string; style?: CSSProperties }) {
  return <div aria-hidden className={`skeleton rounded-md ${className}`} style={style} />
}

/** Silhouette d'une carte d'instance, aux mêmes dimensions que la vraie. */
export function InstanceCardSkeleton() {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-line-soft bg-white/[0.02] p-2.5">
      <Skeleton className="h-11 w-11 flex-none rounded-lg" />
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <Skeleton className="h-3 w-2/3" />
        <Skeleton className="h-2.5 w-2/5" />
      </div>
    </div>
  )
}

/** Une grille de cartes squelettes, pour les écrans en grille (catalogue,
 *  serveurs, skins). `count` cartes de la hauteur donnée. */
export function SkeletonGrid({ count, className = '', cardClassName = 'h-40' }: {
  count: number
  className?: string
  cardClassName?: string
}) {
  return (
    <div aria-busy className={className}>
      {Array.from({ length: count }, (_, i) => (
        <Skeleton key={i} className={`rounded-2xl ${cardClassName}`} />
      ))}
    </div>
  )
}

/** Des lignes squelettes (listes : tickets, notes, profils JVM…). */
export function SkeletonRows({ count, className = 'flex flex-col gap-2', rowClassName = 'h-14' }: {
  count: number
  className?: string
  rowClassName?: string
}) {
  return (
    <div aria-busy className={className}>
      {Array.from({ length: count }, (_, i) => (
        // Dernières lignes plus pâles : la liste « s'efface » au lieu de
        // s'arrêter net, sans promettre un nombre exact de lignes.
        <Skeleton key={i} className={`rounded-xl ${rowClassName}`} style={{ opacity: 1 - i * (0.6 / count) }} />
      ))}
    </div>
  )
}
