import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { markIntroPlayed } from '@/lib/greeting'
import { useT } from '@/i18n'

/**
 * Séquence d'accueil du panneau de la page d'accueil.
 *
 * Le salut n'apparaît pas puis ne disparaît pas : c'est **un seul élément**
 * qui arrive en grand au centre du panneau, puis file se ranger dans le badge
 * du coin haut-gauche en rétrécissant. Deux textes qui se remplaceraient
 * casseraient l'effet, et le texte ne serait pas rendu pareil au départ et à
 * l'arrivée.
 *
 * La translation est donc mesurée, pas écrite à la main : on relève la place
 * du salut au repos et celle du panneau, et on en déduit où le poser pour
 * qu'il tombe centré une fois agrandi. Des valeurs en dur se décaleraient au
 * premier changement de pseudo, de langue ou de taille de fenêtre.
 *
 * Quatre temps : 0 le salut attend en haut, invisible · 1 il descend au
 * centre, en grand · 2 il se range et l'avatar surgit · 3 le badge prend son
 * fond et le pseudo s'affiche.
 *
 * `playIntro` à faux monte directement au dernier temps : la séquence a
 * déjà été vue depuis l'ouverture du launcher, la rejouer à chaque retour
 * sur l'accueil serait pénible (voir `lib/greeting.ts`).
 */

/** Taille du salut une fois rangé dans le badge, en pixels. */
const REST_FONT_PX = 12
/** Grossissement du salut à son arrivée, borné par la largeur du panneau. */
const BIG_SCALE = 3.4
/** Départ, rangement, badge complet — en millisecondes depuis le montage. */
const PHASE_MS = [320, 2350, 3350]

interface Flight {
  dx: number
  dy: number
  scale: number
}

export function WelcomeSequence({
  username,
  avatarUrl,
  greeting,
  playIntro,
}: {
  username: string | null
  avatarUrl: string | null
  greeting: string
  playIntro: boolean
}) {
  const t = useT()
  const [phase, setPhase] = useState(playIntro ? 0 : 3)
  const [flight, setFlight] = useState<Flight | null>(null)
  const slotRef = useRef<HTMLDivElement>(null)
  const boardRef = useRef<HTMLDivElement>(null)

  // On mesure la CASE du salut, pas le salut lui-même : l'élément animé
  // porte déjà une transformation dès la première image, donc son rectangle
  // mentirait de toute la hauteur du vol. La case, elle, ne bouge jamais.
  const measure = useCallback(() => {
    const board = boardRef.current?.getBoundingClientRect()
    const rest = slotRef.current?.getBoundingClientRect()
    if (!board || !rest || rest.width === 0) return
    const scale = Math.min(BIG_SCALE, (board.width * 0.86) / rest.width)
    setFlight({
      dx: board.left + board.width / 2 - (rest.left + (rest.width * scale) / 2),
      dy: board.top + board.height / 2 - (rest.top + (rest.height * scale) / 2),
      scale,
    })
  }, [])

  useLayoutEffect(measure, [measure, greeting, username])

  useEffect(() => {
    if (!boardRef.current || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(measure)
    ro.observe(boardRef.current)
    return () => ro.disconnect()
  }, [measure])

  useEffect(() => {
    if (!playIntro) return
    setPhase(0)
    const timers = PHASE_MS.map((ms, i) => setTimeout(() => setPhase(i + 1), ms))
    // Marquée jouée seulement une fois arrivée au bout : une page quittée en
    // plein vol rejouera la séquence, plutôt que d'avoir été vue à moitié.
    timers.push(setTimeout(markIntroPlayed, PHASE_MS[PHASE_MS.length - 1]))
    return () => timers.forEach(clearTimeout)
  }, [playIntro, username])

  const big = phase === 1
  const parked = phase >= 2
  // Sans mesure (première image, panneau replié), on ne fait pas voler le
  // salut : il se pose directement dans le badge plutôt que de partir de
  // travers.
  const f = flight ?? { dx: 0, dy: 0, scale: 1 }

  return (
    <div ref={boardRef} className="pointer-events-none absolute inset-0">
      <motion.div
        // Le badge ne prend son fond qu'une fois le salut rangé : avant, il
        // n'y a rien à encadrer.
        animate={{
          backgroundColor: phase >= 3 ? 'rgba(14,13,24,0.66)' : 'rgba(14,13,24,0)',
          borderColor: phase >= 3 ? 'rgba(255,255,255,0.09)' : 'rgba(255,255,255,0)',
        }}
        transition={{ duration: 0.5 }}
        className="absolute left-4 top-4 flex h-[56px] items-center gap-2.5 rounded-xl border p-1.5 pr-4"
      >
        <AnimatePresence>
          {parked && avatarUrl && (
            <motion.img
              src={avatarUrl}
              alt=""
              initial={{ scale: 0.2, rotate: -12, opacity: 0 }}
              animate={{ scale: 1, rotate: 0, opacity: 1 }}
              transition={{ type: 'spring', stiffness: 260, damping: 12, mass: 0.7 }}
              className="h-[42px] w-[42px] flex-none rounded-md shadow-[0_3px_14px_rgba(0,0,0,0.55),0_0_0_1px_rgba(255,255,255,0.14)]"
              style={{ imageRendering: 'pixelated' }}
            />
          )}
        </AnimatePresence>

        <div className="flex flex-col justify-center gap-0.5">
          <motion.div
            animate={{ opacity: phase >= 3 ? 1 : 0 }}
            transition={{ duration: 0.6, delay: 0.1 }}
            className="whitespace-nowrap text-[14px] font-bold leading-[18px] tracking-[-0.015em] text-white"
          >
            {username ?? t('home.welcomeGuest')}
          </motion.div>

          {/* Le salut voyage hors du flux, au-dessus d'une doublure invisible
              qui garde sa place dans le badge. Sans elle, sortir le texte du
              flux ferait s'effondrer la colonne. */}
          <div ref={slotRef} className="relative whitespace-nowrap text-[12px] font-medium leading-[15px]">
            <span className="invisible">{greeting}</span>
            <motion.div
              // Un seul élément du début à la fin : c'est lui qui voyage.
              //
              // Il grossit par sa TAILLE DE POLICE, pas par `scale` : une
              // mise à l'échelle dessine les lettres à 12 px puis étire
              // l'image, d'où un texte flou une fois agrandi. En changeant la
              // taille, le navigateur redessine les glyphes à leur vraie
              // taille et le texte reste net du début à la fin.
              animate={{
                x: big ? f.dx : 0,
                y: big ? f.dy : phase === 0 ? f.dy - 340 : 0,
                fontSize: big ? `${(REST_FONT_PX * f.scale).toFixed(1)}px` : `${REST_FONT_PX}px`,
                opacity: phase === 0 ? 0 : 1,
                color: parked ? 'rgba(233,231,245,0.6)' : 'rgb(255,255,255)',
                fontWeight: parked ? 500 : 800,
              }}
              transition={
                phase === 0
                  ? { duration: 0 }
                  : big
                    ? { duration: 1.05, ease: [0.1, 0.75, 0.12, 1], opacity: { duration: 0.45 } }
                    : { duration: 1.1, ease: [0.16, 1, 0.3, 1], color: { duration: 0.8 } }
              }
              style={{ transformOrigin: 'left top' }}
              className="absolute left-0 top-0 whitespace-nowrap leading-[1.25] tracking-[-0.01em]"
            >
              {greeting}
            </motion.div>
          </div>
        </div>
      </motion.div>
    </div>
  )
}
