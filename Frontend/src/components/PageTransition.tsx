import { motion } from 'framer-motion'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { pageVariants } from '@/lib/motion'

/**
 * Transition entre les écrans : le nouveau monte en apparaissant.
 *
 * Il n'y a volontairement PAS d'`AnimatePresence` ici. La version précédente
 * en utilisait une en `mode="wait"`, qui garde l'ancienne page montée le
 * temps de sa sortie avant de monter la nouvelle. En changeant vite d'écran,
 * cette attente se bloquait : la page suivante était montée mais restait à
 * son état de départ — `opacity: 0` — donc cliquable et invisible. On avait
 * les zones sensibles sans le rendu, et seul un rechargement s'en sortait.
 *
 * Un simple élément animé, recréé à chaque chemin, n'a pas d'état à perdre :
 * `initial` puis `animate` se rejouent forcément au montage, donc une page
 * affichée est toujours visible. Le prix est l'animation de sortie, que
 * personne ne voyait vraiment puisqu'elle durait 120 ms derrière l'entrée de
 * la suivante.
 */
export function PageTransition({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return (
    <motion.div
      key={pathname}
      variants={pageVariants}
      initial="initial"
      animate="animate"
      className="h-full w-full"
    >
      {children}
    </motion.div>
  )
}
