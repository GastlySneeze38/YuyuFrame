import { motion } from 'framer-motion'

/**
 * Formes grises en attendant le contenu.
 *
 * Elles ne servent pas à « faire patienter » : elles réservent la place que
 * le vrai contenu occupera, pour que la page arrive d'un bloc au lieu de
 * sauter quand les données tombent. Un rond de chargement au milieu du vide,
 * lui, ne dit rien de ce qui va apparaître et laisse l'écran bouger.
 *
 * L'onde qui traverse est un dégradé déplacé : pas d'opacité clignotante,
 * qui attire l'œil sur une zone dont il n'y a justement rien à lire.
 */
export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div className={`relative overflow-hidden rounded-md bg-white/[0.06] ${className}`}>
      <motion.div
        initial={{ x: '-100%' }}
        animate={{ x: '100%' }}
        transition={{ duration: 1.4, repeat: Infinity, ease: 'easeInOut', repeatDelay: 0.3 }}
        className="absolute inset-y-0 w-full bg-[linear-gradient(90deg,transparent,rgba(255,255,255,0.07),transparent)]"
      />
    </div>
  )
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
