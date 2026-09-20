import { motion } from 'framer-motion'
import type { ReactNode } from 'react'
import { DURATION, EASE_OUT } from '@/lib/motion'

/**
 * Apparition au défilement, façon page web : le bloc monte et se révèle
 * quand il entre dans l'écran. Même esprit que le `FadeIn` du site
 * (Server/Website/Frontend/src/components/FadeIn.tsx).
 *
 * L'animation se **rejoue à chaque passage** : on sort du bloc, on y revient,
 * il réapparaît. Même choix que sur le site. `once` permet de la figer au
 * premier passage pour un cas particulier.
 */
export function Reveal({
  children,
  delay = 0,
  y = 16,
  once = false,
  className,
}: {
  children: ReactNode
  delay?: number
  /** Distance du glissement, en pixels. 0 = simple fondu. */
  y?: number
  once?: boolean
  className?: string
}) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y }}
      whileInView={{ opacity: 1, y: 0 }}
      // La marge basse déclenche l'animation un peu avant que le bloc soit
      // complètement visible : il finit d'arriver pendant le défilement.
      viewport={{ once, margin: '0px 0px -12% 0px' }}
      transition={{ duration: DURATION.slow, ease: EASE_OUT, delay }}
    >
      {children}
    </motion.div>
  )
}
