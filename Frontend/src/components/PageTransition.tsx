import { AnimatePresence, motion } from 'framer-motion'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { pageVariants } from '@/lib/motion'

/**
 * Transition entre les écrans : l'ancien s'efface vers le haut pendant que le
 * nouveau monte. `mode="wait"` évite de superposer deux pages, ce qui
 * provoquerait un saut de la barre de défilement.
 *
 * La clé est le chemin : naviguer vers la même page (changement de paramètre
 * d'URL) ne rejoue donc pas l'animation inutilement.
 */
export function PageTransition({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return (
    <AnimatePresence mode="wait" initial={false}>
      <motion.div
        key={pathname}
        variants={pageVariants}
        initial="initial"
        animate="animate"
        exit="exit"
        className="h-full w-full"
      >
        {children}
      </motion.div>
    </AnimatePresence>
  )
}
