import { useEffect, useMemo } from 'react'
import { AnimatePresence, motion, useMotionValue, useSpring, useTransform } from 'framer-motion'
import overworld from '@/assets/onboarding/overworld.webp'
import nether from '@/assets/onboarding/nether.webp'
import redstone from '@/assets/onboarding/redstone.webp'

export type Scene = 'overworld' | 'nether' | 'redstone'

const IMAGES: Record<Scene, string> = { overworld, nether, redstone }

/** La couleur des particules et de la lueur, prise dans chaque capture. */
const TINT: Record<Scene, { particle: string; glow: string }> = {
  overworld: { particle: '255 214 170', glow: '255 170 120' },
  nether: { particle: '255 150 60', glow: '255 90 40' },
  redstone: { particle: '255 70 70', glow: '230 60 60' },
}

/** Décodées d'avance : une capture qui arrive à moitié peinte se verrait. */
export function preloadScenes() {
  for (const src of Object.values(IMAGES)) {
    const img = new Image()
    img.src = src
    void img.decode?.().catch(() => {})
  }
}

/**
 * Le fond de l'écran d'accueil : une capture du jeu par étape.
 *
 * Trois mouvements superposés, tous sur `transform`/`opacity` :
 * - la dérive lente de la capture (CSS, `ob-drift`), qui ne s'arrête jamais ;
 * - le changement de capture : la nouvelle arrive par-dessus en se posant
 *   (zoom 1.14 → 1), l'ancienne reste dessous le temps du fondu, pour qu'on
 *   ne voie jamais le noir entre les deux ;
 * - sur ordinateur, un léger décalage qui suit la souris, amorti par un
 *   ressort (la profondeur sans effort).
 */
export function Backdrop({ scene, mobile, leaving }: { scene: Scene; mobile: boolean; leaving: boolean }) {
  const px = useMotionValue(0)
  const py = useMotionValue(0)
  const sx = useSpring(px, { stiffness: 40, damping: 18, mass: 0.8 })
  const sy = useSpring(py, { stiffness: 40, damping: 18, mass: 0.8 })
  // Le fond part dans le sens inverse du pointeur, les particules dans le
  // même : deux plans, donc de la profondeur.
  const bgX = useTransform(sx, (v) => v * -14)
  const bgY = useTransform(sy, (v) => v * -10)
  const fgX = useTransform(sx, (v) => v * 22)
  const fgY = useTransform(sy, (v) => v * 16)

  useEffect(() => {
    if (mobile || !window.matchMedia('(pointer: fine)').matches) return
    const onMove = (e: PointerEvent) => {
      px.set(e.clientX / window.innerWidth - 0.5)
      py.set(e.clientY / window.innerHeight - 0.5)
    }
    window.addEventListener('pointermove', onMove, { passive: true })
    return () => window.removeEventListener('pointermove', onMove)
  }, [mobile, px, py])

  const tint = TINT[scene]

  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden bg-black">
      <motion.div
        className="absolute -inset-[3%]"
        style={{ x: bgX, y: bgY }}
        // L'entrée de l'écran : la scène arrive de plus près, et la sortie
        // replonge dedans.
        initial={{ opacity: 0, scale: 1.22 }}
        animate={leaving ? { opacity: 0, scale: 1.14 } : { opacity: 1, scale: 1 }}
        transition={{ duration: leaving ? 0.75 : 1.8, ease: leaving ? [0.7, 0, 0.84, 0] : [0.16, 1, 0.3, 1] }}
      >
        <AnimatePresence initial={false}>
          <motion.div
            key={scene}
            className="absolute inset-0"
            initial={{ opacity: 0, scale: 1.14 }}
            animate={{ opacity: 1, scale: 1 }}
            // L'ancienne ne s'efface qu'une fois couverte.
            exit={{ opacity: 0, transition: { duration: 0.01, delay: 1.5 } }}
            transition={{ opacity: { duration: 1.1, ease: [0.33, 1, 0.68, 1] }, scale: { duration: 1.6, ease: [0.16, 1, 0.3, 1] } }}
          >
            <img src={IMAGES[scene]} alt="" draggable={false} decoding="async" className="ob-drift h-full w-full object-cover" />
          </motion.div>
        </AnimatePresence>
      </motion.div>

      {/* Lisibilité : sombre là où est le texte (à gauche sur PC, en bas sur
          téléphone), plus un vignettage qui ramène l'œil au centre. */}
      <div
        className="absolute inset-0"
        style={{
          background: mobile
            ? 'linear-gradient(180deg, rgb(0 0 0 / 0.55) 0%, rgb(0 0 0 / 0.15) 30%, rgb(0 0 0 / 0.55) 60%, rgb(0 0 0 / 0.88) 100%)'
            : 'linear-gradient(90deg, rgb(0 0 0 / 0.78) 0%, rgb(0 0 0 / 0.45) 42%, rgb(0 0 0 / 0.2) 65%, rgb(0 0 0 / 0.5) 100%)',
        }}
      />
      <div className="absolute inset-0" style={{ background: 'radial-gradient(120% 90% at 50% 45%, transparent 55%, rgb(0 0 0 / 0.6) 100%)' }} />
      {/* La lueur de la scène, qui change de couleur avec elle. */}
      <div
        className="absolute inset-0 transition-[background] duration-[1400ms]"
        style={{ background: `radial-gradient(60% 50% at ${mobile ? '50% 100%' : '78% 70%'}, rgb(${tint.glow} / 0.18), transparent 70%)` }}
      />

      <motion.div className="absolute inset-0" style={{ x: fgX, y: fgY }}>
        <Particles color={tint.particle} count={mobile ? 12 : 26} />
      </motion.div>
    </div>
  )
}

/** Des carrés qui montent, chacun à son rythme ; tirés une fois pour toutes. */
function Particles({ color, count }: { color: string; count: number }) {
  const specs = useMemo(() => {
    // Graine fixe : la même nuée d'un rendu à l'autre, pas de saut.
    let seed = 7
    const rand = () => {
      seed = (seed * 16807) % 2147483647
      return seed / 2147483647
    }
    return Array.from({ length: count }, () => {
      const size = 3 + Math.round(rand() * 5)
      return {
        left: `${rand() * 100}%`,
        size,
        t: `${11 + rand() * 13}s`,
        d: `${-rand() * 22}s`,
        dx: `${(rand() - 0.5) * 140}px`,
        r: `${(rand() - 0.5) * 360}deg`,
        o: (0.25 + rand() * 0.55).toFixed(2),
      }
    })
  }, [count])

  return (
    <>
      {specs.map((p, i) => (
        <span
          key={i}
          className="ob-particle"
          style={{
            left: p.left,
            width: p.size,
            height: p.size,
            backgroundColor: `rgb(${color})`,
            boxShadow: p.size > 5 ? `0 0 8px rgb(${color} / 0.6)` : undefined,
            ['--ob-t' as string]: p.t,
            ['--ob-d' as string]: p.d,
            ['--ob-dx' as string]: p.dx,
            ['--ob-r' as string]: p.r,
            ['--ob-o' as string]: p.o,
          }}
        />
      ))}
    </>
  )
}
