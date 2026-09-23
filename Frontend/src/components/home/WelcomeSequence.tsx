import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { markIntroPlayed } from '@/lib/greeting'
import { SkinBust } from './SkinBust'
import { useT } from '@/i18n'

/**
 * Séquence d'accueil de la bannière.
 *
 * Le salut n'apparaît pas puis ne disparaît pas : c'est **un seul élément**
 * qui arrive en grand au centre de la bannière, puis file se ranger sous le
 * pseudo en rétrécissant. Deux textes qui se remplaceraient casseraient
 * l'effet, et le texte ne serait pas rendu pareil au départ et à l'arrivée.
 *
 * La translation est donc mesurée, pas écrite à la main : on relève la place
 * du salut au repos et celle de la bannière, et on en déduit où le poser pour
 * qu'il tombe centré une fois agrandi. Des valeurs en dur se décaleraient au
 * premier changement de pseudo, de langue ou de taille de fenêtre.
 *
 * Quatre temps : 0 le salut attend en haut, invisible · 1 il descend au
 * centre, en grand · 2 il se range et l'avatar surgit · 3 le pseudo s'affiche.
 *
 * `playIntro` à faux monte directement au dernier temps : la séquence a
 * déjà été vue depuis l'ouverture du launcher, la rejouer à chaque retour
 * sur l'accueil serait pénible (voir `lib/greeting.ts`).
 */

/** Taille du salut une fois rangé sous le pseudo, en pixels. */
const REST_FONT_PX = 12
/** Grossissement du salut à son arrivée, borné par la largeur de la bannière. */
const BIG_SCALE = 3.4
/** Départ, rangement, pseudo — en millisecondes depuis le montage. */
const PHASE_MS = [320, 2350, 3350]

interface Flight {
  dx: number
  dy: number
  scale: number
}

export function WelcomeSequence({
  username,
  skinUrl,
  greeting,
  playIntro,
  onAvatarClick,
}: {
  username: string | null
  /** Texture du skin, rendue en 3D. `null` = aucun compte connecté. */
  skinUrl: string | null
  greeting: string
  playIntro: boolean
  onAvatarClick: () => void
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
    const scale = Math.min(BIG_SCALE, (board.width * 0.7) / rest.width)
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
  // Sans mesure (première image, bannière repliée), on ne fait pas voler le
  // salut : il se pose directement à sa place plutôt que de partir de travers.
  const f = flight ?? { dx: 0, dy: 0, scale: 1 }

  return (
    <div ref={boardRef} className="pointer-events-none absolute inset-0 flex flex-col items-center justify-end">

      {/* Le skin en 3D — il occupe le cœur de la bannière et descend jusqu'au
          bloc de texte, qu'il touche presque. C'est le seul élément cliquable
          de la bannière, d'où le rétablissement des événements de pointeur.
          Le rendu WebGL ne démarre qu'une fois la séquence arrivée à son
          temps 2 : pendant le vol du salut, on regarde le texte. */}
      <AnimatePresence>
        {parked && skinUrl && (
          <motion.button
            initial={{ scale: 0.55, y: 28, opacity: 0 }}
            animate={{ scale: 1, y: 0, opacity: 1 }}
            transition={{ type: 'spring', stiffness: 200, damping: 17, mass: 0.85 }}
            onClick={onAvatarClick}
            title={t('home.manageAccounts')}
            className="pointer-events-auto absolute bottom-[clamp(40px,9vh,72px)] left-1/2 -translate-x-1/2 transition-[filter] duration-200 hover:brightness-110"
          >
            <SkinBust
              skinUrl={skinUrl}
              className="h-[clamp(92px,22vh,220px)] w-[clamp(92px,22vh,220px)]"
            />
          </motion.button>
        )}
      </AnimatePresence>

      {/* Bloc bas-centre : pseudo puis salut. Le dégradé sous lui détache le
          texte du relief de la bannière, quelle que soit sa couleur. */}
      <div className="relative z-10 flex w-full flex-col items-center pb-[clamp(8px,1.6vh,16px)] pt-10 bg-[linear-gradient(to_top,rgba(9,9,13,0.92),rgba(9,9,13,0.55)_45%,transparent)]">
        <motion.div
          // Même raison qu'ailleurs : sans `initial={false}`, le pseudo se
          // monte à son opacité CSS et n'est masqué qu'à l'image suivante.
          initial={false}
          animate={{ opacity: phase >= 3 ? 1 : 0 }}
          transition={{ duration: 0.6, delay: 0.1 }}
          className="whitespace-nowrap text-[clamp(14px,2.4vh,20px)] font-bold leading-tight tracking-[-0.015em] text-white"
        >
          {username ?? t('home.welcomeGuest')}
        </motion.div>

        {/* Le salut voyage hors du flux, au-dessus d'une doublure invisible
            qui garde sa place. Sans elle, sortir le texte du flux ferait
            s'effondrer la colonne. */}
        <div ref={slotRef} className="relative whitespace-nowrap text-[12px] font-medium leading-[15px]">
          <span className="invisible">{greeting}</span>
          <motion.div
            // Un seul élément du début à la fin : c'est lui qui voyage.
            //
            // Il grossit par sa TAILLE DE POLICE, pas par `scale` : une mise
            // à l'échelle dessine les lettres à 12 px puis étire l'image,
            // d'où un texte flou une fois agrandi. En changeant la taille, le
            // navigateur redessine les glyphes à leur vraie taille et le
            // texte reste net du début à la fin.
            //
            // `initial={false}` : le salut doit être hors champ dès la
            // première image, pas à la deuxième.
            initial={false}
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
    </div>
  )
}
