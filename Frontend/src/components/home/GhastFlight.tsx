import { useMemo } from 'react'
import { motion } from 'framer-motion'

/**
 * Easter egg : un ghast traverse le ciel de la bannière d'accueil.
 *
 * Déclenché par un Maj + clic sur le titre « YuyuFrame » (voir `Home.tsx`),
 * il passe une fois et s'en va de lui-même. Rien n'est gardé, rien n'est
 * annoncé — règles et raison d'être dans `docs/product/easter-eggs.md`.
 *
 * Dessiné en blocs, comme la ligne d'arbres (`Skyline.tsx`) : aucune image à
 * embarquer, et le pixel reste net à toute taille de fenêtre.
 */

const BODY = '#f2f2f4'
const SHADE = '#d6d6dc'
const DARK = '#3a3a44'
const TEAR = '#9a9aa6'

/** Tentacules vus de face : abscisse, longueur, décalage du balancement. */
const TENTACLES: Array<[number, number, number]> = [
  [1, 7, 0],
  [4, 10, 0.35],
  [7, 6, 0.7],
  [10, 9, 0.2],
  [13, 7, 0.55],
]

/** Durée de la traversée, en secondes. */
const FLIGHT_SECONDS = 9

export function GhastFlight({ onDone }: { onDone: () => void }) {
  // Tirés une fois par passage : le sens et l'altitude changent d'un vol à
  // l'autre, sans quoi le deuxième n'aurait plus rien à montrer.
  const { fromLeft, top } = useMemo(
    () => ({ fromLeft: Math.random() < 0.5, top: 6 + Math.random() * 16 }),
    [],
  )
  const path = fromLeft ? ['-14%', '104%'] : ['104%', '-14%']

  return (
    <motion.div
      initial={{ left: path[0] }}
      animate={{ left: path[1] }}
      transition={{ duration: FLIGHT_SECONDS, ease: 'linear' }}
      onAnimationComplete={onDone}
      className="pointer-events-none absolute h-[clamp(44px,13vh,96px)]"
      style={{ top: `${top}%` }}
    >
      {/* Le ghast flotte : il monte et redescend pendant qu'il avance. */}
      <motion.svg
        animate={{ y: [0, -10, 0, 8, 0] }}
        transition={{ duration: 3.2, repeat: Infinity, ease: 'easeInOut' }}
        viewBox="0 0 16 27"
        shapeRendering="crispEdges"
        className="h-full w-auto drop-shadow-[0_0_14px_rgba(160,150,255,0.35)]"
      >
        {TENTACLES.map(([x, length, delay]) => (
          <motion.rect
            key={x}
            x={x}
            y={16}
            width={2}
            height={length}
            fill={SHADE}
            animate={{ scaleY: [1, 0.72, 1] }}
            transition={{ duration: 1.6, repeat: Infinity, ease: 'easeInOut', delay }}
            style={{ transformBox: 'fill-box', originY: 0 }}
          />
        ))}

        <rect x={0} y={0} width={16} height={16} fill={BODY} />
        {/* Quelques taches, sans quoi le corps n'est qu'un carré blanc. */}
        <rect x={1} y={1} width={3} height={2} fill={SHADE} />
        <rect x={11} y={2} width={3} height={1} fill={SHADE} />
        <rect x={6} y={13} width={2} height={2} fill={SHADE} />
        <rect x={13} y={11} width={2} height={3} fill={SHADE} />
        <rect x={0} y={12} width={2} height={2} fill={SHADE} />

        {/* Yeux clos et leurs larmes. */}
        <rect x={2} y={6} width={4} height={1} fill={DARK} />
        <rect x={10} y={6} width={4} height={1} fill={DARK} />
        <rect x={3} y={7} width={1} height={3} fill={TEAR} />
        <rect x={5} y={7} width={1} height={2} fill={TEAR} />
        <rect x={10} y={7} width={1} height={2} fill={TEAR} />
        <rect x={12} y={7} width={1} height={3} fill={TEAR} />

        {/* Bouche. */}
        <rect x={6} y={10} width={4} height={1} fill={DARK} />
      </motion.svg>
    </motion.div>
  )
}
