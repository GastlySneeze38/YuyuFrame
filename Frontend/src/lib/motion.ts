import type { Transition, Variants } from 'framer-motion'

/**
 * Vocabulaire de mouvement du launcher.
 *
 * Tout le monde part d'ici plutôt que d'inventer ses durées : une interface
 * cohérente tient autant au rythme qu'aux couleurs. Trois durées seulement,
 * les mêmes que les jetons CSS de `index.css`.
 *
 * Framer Motion respecte de lui-même « animations réduites » du système si
 * on lui passe `MotionConfig reducedMotion="user"` (voir App.tsx) : les
 * opacités restent, les déplacements sont supprimés.
 */

export const DURATION = {
  fast: 0.14,
  base: 0.22,
  slow: 0.38,
} as const

/** Décélération franche : l'élément arrive vite puis se pose. */
export const EASE_OUT = [0.16, 1, 0.3, 1] as const

export const transition: Transition = { duration: DURATION.base, ease: EASE_OUT }
export const fastTransition: Transition = { duration: DURATION.fast, ease: EASE_OUT }

/** Changement de page : léger glissement vers le haut. */
export const pageVariants: Variants = {
  initial: { opacity: 0, y: 8 },
  animate: { opacity: 1, y: 0, transition },
  exit: { opacity: 0, y: -6, transition: fastTransition },
}

/** Modale : la carte monte pendant que le fond s'assombrit. */
export const modalVariants: Variants = {
  initial: { opacity: 0, y: 12, scale: 0.98 },
  animate: { opacity: 1, y: 0, scale: 1, transition },
  exit: { opacity: 0, y: 8, scale: 0.99, transition: fastTransition },
}

export const backdropVariants: Variants = {
  initial: { opacity: 0 },
  animate: { opacity: 1, transition: fastTransition },
  exit: { opacity: 0, transition: fastTransition },
}

/**
 * Liste qui apparaît en cascade. `stagger` reste court : au-delà d'une
 * poignée d'éléments, une cascade trop lente donne l'impression que
 * l'application rame.
 */
export const listVariants: Variants = {
  initial: {},
  animate: { transition: { staggerChildren: 0.035, delayChildren: 0.02 } },
}

export const listItemVariants: Variants = {
  initial: { opacity: 0, y: 6 },
  animate: { opacity: 1, y: 0, transition },
}

/** Apparition simple, pour un bloc isolé (bandeau, message d'erreur). */
export const fadeVariants: Variants = {
  initial: { opacity: 0, y: -4 },
  animate: { opacity: 1, y: 0, transition: fastTransition },
  exit: { opacity: 0, y: -4, transition: fastTransition },
}

/** Réaction au clic, commune aux boutons et aux cartes cliquables. */
export const pressable = {
  whileHover: { y: -1 },
  whileTap: { scale: 0.98 },
  transition: fastTransition,
} as const
