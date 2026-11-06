import { motion } from 'framer-motion'

/**
 * Halo qui enfle et retombe en boucle, posé dans un parent `relative`.
 *
 * Remplace les `animate={{ boxShadow: [...] }}` en boucle (audit de
 * performance, 2026-11-06) : animer une ombre redessine l'élément à chaque
 * image, tant que l'écran est ouvert. Ici l'ombre est fixe, sur un calque
 * vide, et seule son opacité varie — le compositeur s'en charge sans rien
 * redessiner. Même rendu à l'œil.
 *
 * `radius` reprend l'arrondi du parent (classe Tailwind), sinon le halo
 * aurait des coins carrés.
 */
export function GlowPulse({ shadow, duration, radius }: {
  /** L'ombre au plus fort de la pulsation, ex. `0 0 22px rgb(var(--accent-hover)/0.18)`. */
  shadow: string
  duration: number
  radius: string
}) {
  return (
    <motion.span
      aria-hidden
      className={`pointer-events-none absolute inset-0 ${radius}`}
      style={{ boxShadow: shadow }}
      initial={{ opacity: 0 }}
      animate={{ opacity: [0, 1, 0] }}
      transition={{ duration, repeat: Infinity, ease: 'easeInOut' }}
    />
  )
}
