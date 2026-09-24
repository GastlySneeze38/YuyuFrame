import { useEffect, useMemo, useRef, useState } from 'react'
import { motion, type Variants } from 'framer-motion'

/**
 * Comète qui parcourt le bouton de lancement au survol, en suivant un tracé
 * dessiné : une longue courbe presque plate, une boucle au centre, puis la
 * sortie par la droite.
 *
 * ── Pourquoi un vrai chemin et pas des images clés ────────────────────────
 * La version précédente empilait un déplacement horizontal et une ondulation
 * verticale. Deux courbes superposées ne font jamais une boucle : pour que le
 * trajet se recroise, il faut que l'abscisse REVIENNE en arrière pendant que
 * l'ordonnée continue. C'est exactement ce qu'un chemin SVG décrit, et rien
 * d'autre ne le décrit simplement.
 *
 * La comète est donc posée sur ce chemin (`offset-path`) et on n'anime qu'une
 * seule valeur : la distance parcourue dessus. La forme du mouvement vient du
 * tracé, sa vitesse de l'interpolation — les deux ne se gênent plus.
 *
 * ── Pourquoi on mesure le bouton ──────────────────────────────────────────
 * `offset-path: path(…)` ne connaît que les pixels : pas de pourcentage, pas
 * de `viewBox` qui remettrait le tracé à l'échelle. Le chemin est donc
 * recalculé à la taille réelle du bouton, qui dépend du nom de l'instance et
 * de la fenêtre. Les coordonnées ci-dessous sont des fractions de cette
 * taille, ce qui garde le dessin identique quelle que soit la largeur.
 */

/** Un passage complet, en secondes, et l'attente avant le suivant. */
const TRAVEL = 1.6
const PAUSE = 0.25

/**
 * Le tracé, en fractions de la boîte. Il déborde des deux côtés (-5 % et
 * 105 %) pour que la comète entre et sorte par les bords au lieu d'apparaître
 * et de s'évanouir dans le bouton.
 */
function trajectory(w: number, h: number) {
  const x = (p: number) => (p * w).toFixed(2)
  const y = (p: number) => (p * h).toFixed(2)
  return [
    `M ${x(-0.05)} ${y(0.68)}`,
    // Longue approche, à peine montante.
    `C ${x(0.10)} ${y(0.62)}, ${x(0.26)} ${y(0.60)}, ${x(0.40)} ${y(0.56)}`,
    // Montée dans la boucle, côté gauche.
    `C ${x(0.44)} ${y(0.44)}, ${x(0.42)} ${y(0.22)}, ${x(0.50)} ${y(0.20)}`,
    // Sommet puis redescente, côté droit.
    `C ${x(0.59)} ${y(0.18)}, ${x(0.59)} ${y(0.40)}, ${x(0.52)} ${y(0.50)}`,
    // Croisement : on repasse sous la ligne d'approche — c'est ce retour en
    // arrière sur l'axe X qui ferme la boucle.
    `C ${x(0.475)} ${y(0.57)}, ${x(0.44)} ${y(0.66)}, ${x(0.47)} ${y(0.72)}`,
    // Sortie par la droite, en se recouchant.
    `C ${x(0.53)} ${y(0.82)}, ${x(0.72) } ${y(0.74)}, ${x(1.05)} ${y(0.70)}`,
  ].join(' ')
}

/**
 * Vitesse : lancée sur l'approche, retenue dans la boucle, relancée à la
 * sortie. Les repères sont posés sur la DISTANCE, pas sur le temps — la
 * boucle occupe une petite part du tracé mais presque la moitié de la durée,
 * donc on a le temps de la voir se faire.
 */
const COMET: Variants = {
  rest: { opacity: 0, offsetDistance: '0%', transition: { duration: 0.2 } },
  hover: {
    opacity: [0, 1, 1, 0],
    offsetDistance: ['0%', '34%', '62%', '100%'],
    transition: {
      offsetDistance: {
        duration: TRAVEL,
        times: [0, 0.22, 0.66, 1],
        ease: 'easeInOut',
        repeat: Infinity,
        repeatDelay: PAUSE,
      },
      opacity: {
        duration: TRAVEL,
        times: [0, 0.12, 0.82, 1],
        repeat: Infinity,
        repeatDelay: PAUSE,
      },
    },
  },
}

/**
 * La traînée : le tracé lui-même, révélé par un segment qui glisse dessus.
 * `pathLength` donne sa longueur, `pathOffset` sa position — la tête reste
 * calée sur la comète et la queue la rattrape à la fin.
 */
const TRAIL: Variants = {
  rest: { opacity: 0, pathLength: 0, pathOffset: 0 },
  hover: {
    opacity: [0, 0.9, 0.9, 0],
    pathLength: [0, 0.26, 0.26, 0],
    pathOffset: [0, 0, 0.74, 1],
    transition: {
      duration: TRAVEL,
      times: [0, 0.22, 0.66, 1],
      ease: 'easeInOut',
      repeat: Infinity,
      repeatDelay: PAUSE,
    },
  },
}

export function LaunchSweep({ active }: { active: boolean }) {
  const boxRef = useRef<HTMLSpanElement>(null)
  const [box, setBox] = useState({ w: 0, h: 0 })

  useEffect(() => {
    const el = boxRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    // `offsetWidth`, pas `getBoundingClientRect` : le bouton grandit de 3 % au
    // survol, et le rectangle mesuré inclurait cette transformation — le
    // chemin se remettrait à l'échelle au moment même où la comète le parcourt.
    const read = () => setBox({ w: el.offsetWidth, h: el.offsetHeight })
    read()
    const ro = new ResizeObserver(read)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const path = useMemo(() => (box.w > 0 ? trajectory(box.w, box.h) : ''), [box.w, box.h])
  const state = active && path ? 'hover' : 'rest'

  return (
    <span ref={boxRef} aria-hidden className="pointer-events-none absolute inset-0 z-[5]">
      {path && (
        <>
          <svg
            className="absolute inset-0 h-full w-full overflow-visible"
            viewBox={`0 0 ${box.w} ${box.h}`}
            fill="none"
          >
            <motion.path
              d={path}
              variants={TRAIL}
              initial="rest"
              animate={state}
              stroke="rgba(207,216,255,0.85)"
              strokeWidth={1.5}
              strokeLinecap="round"
            />
          </svg>

          <motion.span
            variants={COMET}
            initial="rest"
            animate={state}
            style={{
              // `offset-path` cale le point d'ancrage de l'élément — son
              // centre par défaut — sur le tracé, quelle que soit sa position
              // de départ : `left`/`top` n'ont donc rien à compenser ici.
              offsetPath: `path("${path}")`,
              offsetRotate: '0deg',
            }}
            className="absolute left-0 top-0 h-[14px] w-[14px] rounded-full bg-[radial-gradient(circle,rgba(255,255,255,0.95)_0%,rgba(198,210,255,0.5)_40%,transparent_70%)] blur-[2px]"
          />
        </>
      )}
    </span>
  )
}
