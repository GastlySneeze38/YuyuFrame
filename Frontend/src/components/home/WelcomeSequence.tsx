import { Suspense, lazy, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { markIntroPlayed } from '@/lib/greeting'
import { useT } from '@/i18n'

// Chargé à part : le buste tire la 3D (skinview3d + three, ~500 Ko). Importé
// d'office, l'accueil attendait ce morceau avant de s'afficher, ce qui se
// voyait sur téléphone. La bannière s'affiche, le personnage la rejoint.
const SkinBust = lazy(() => import('./SkinBust').then((m) => ({ default: m.SkinBust })))

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

/**
 * Le salut coupé en deux lignes à l'espace la plus proche du milieu, quand il
 * est assez long pour que ça vaille la peine. Coupé dans le texte (un retour à
 * la ligne forcé) et non par la largeur : le salut grossit pendant son vol, et
 * une coupure décidée par la largeur ne tomberait pas au même mot en grand et
 * une fois rangé.
 */
function splitInTwo(text: string): string[] {
  if (text.length < 20) return [text]
  const middle = text.length / 2
  let best = -1
  for (let i = 0; i < text.length; i++) {
    if (text[i] === ' ' && (best < 0 || Math.abs(i - middle) < Math.abs(best - middle))) best = i
  }
  return best < 0 ? [text] : [text.slice(0, best), text.slice(best + 1)]
}

function Lines({ lines }: { lines: string[] }) {
  return <>{lines.map((l, i) => <span key={i} className="block">{l}</span>)}</>
}

interface Flight {
  dx: number
  dy: number
  scale: number
}

export function WelcomeSequence({
  username,
  uuid,
  localSkin,
  greeting,
  playIntro,
  onAvatarClick,
  restFontPx = REST_FONT_PX,
  twoLines = false,
}: {
  username: string | null
  /** UUID du compte actif. `null` = aucun compte connecté. */
  uuid: string | null
  /** PNG du skin d'un compte hors ligne, quand il en a défini un. */
  localSkin: string | null
  greeting: string
  playIntro: boolean
  onAvatarClick: () => void
  /** Taille du salut une fois rangé. 12 px sur PC ; le téléphone passe 13,
   *  le plancher de lecture du texte secondaire. */
  restFontPx?: number
  /** Téléphone : le salut sur deux lignes, plus lisible une fois rangé. */
  twoLines?: boolean
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
    // Bornée en largeur et en hauteur : sur deux lignes, le salut agrandi
    // dépasserait d'une bannière basse.
    const scale = Math.min(BIG_SCALE, (board.width * 0.7) / rest.width, (board.height * 0.6) / rest.height)
    setFlight({
      dx: board.left + board.width / 2 - (rest.left + (rest.width * scale) / 2),
      dy: board.top + board.height / 2 - (rest.top + (rest.height * scale) / 2),
      scale,
    })
  }, [])

  useLayoutEffect(measure, [measure, greeting, username, twoLines])
  const lines = twoLines ? splitInTwo(greeting) : [greeting]

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
    // Plus de rangées qui se partagent la hauteur : le buste occupe TOUTE la
    // bannière et le texte se pose par-dessus.
    //
    // C'est ce que demande le rendu visé : la coupe des épaules doit être
    // plaquée au bord bas de la bannière. Tant que le texte prenait sa propre
    // rangée, il repoussait le buste vers le haut et laissait une bande de
    // ciel entre les épaules et le bord.
    //
    // Effet de bord bienvenu : il n'y a plus aucune hauteur à négocier. Le
    // canevas fait la bannière, point — c'était la dernière source de
    // positions imprévisibles.
    <div ref={boardRef} className="pointer-events-none absolute inset-0">

      {/* Le skin en 3D, sur toute la bannière. Il apparaît au temps 2,
          pendant qu'on regarde le salut voler. Seul élément cliquable de la
          bannière, d'où le rétablissement des événements de pointeur. */}
      <div className="absolute inset-0">
        <AnimatePresence>
          {parked && (uuid || localSkin) && (
            <motion.div
              // Le rebond n'appartient qu'à la séquence d'accueil, jouée une
              // fois par jour. Hors d'elle, on revient sur l'accueil des
              // dizaines de fois : le personnage est déjà là, sans entrée.
              initial={playIntro ? { scale: 0.55, y: 28, opacity: 0 } : false}
              animate={{ scale: 1, y: 0, opacity: 1 }}
              transition={{ type: 'spring', stiffness: 200, damping: 17, mass: 0.85 }}
              // Posé sur toute la cellule, mais plus cliquable : le canevas
              // remplit la bannière et n'est opaque que sur le modèle, donc
              // un bouton à cet endroit rendait tout le ciel cliquable. C'est
              // `SkinBust` qui pose sa propre zone, calculée sur le buste.
              className="absolute inset-0"
            >
              <Suspense fallback={null}>
                <SkinBust
                  uuid={uuid}
                  localSkin={localSkin}
                  onClick={onAvatarClick}
                  title={t('home.manageAccounts')}
                  className="h-full w-full"
                />
              </Suspense>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* Bloc bas-centre : pseudo puis salut. Le dégradé sous lui détache le
          texte du relief de la bannière, quelle que soit sa couleur. */}
      {/* Posé par-dessus le buste, ancré au bord bas. Le dégradé fait la
          liaison : le texte se détache du skin sans qu'on voie une découpe. */}
      <div className="absolute inset-x-0 bottom-0 z-10 flex flex-col items-center pb-[clamp(8px,1.6vh,16px)] pt-[clamp(20px,5vh,52px)] bg-[linear-gradient(to_top,rgba(9,9,13,0.94),rgba(9,9,13,0.72)_45%,transparent)]">
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
        <div
          ref={slotRef}
          className={`relative whitespace-nowrap text-center leading-[1.25] ${twoLines ? 'font-semibold' : 'font-medium'}`}
          style={{ fontSize: restFontPx }}
        >
          <span className="invisible block"><Lines lines={lines} /></span>
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
              fontSize: big ? `${(restFontPx * f.scale).toFixed(1)}px` : `${restFontPx}px`,
              opacity: phase === 0 ? 0 : 1,
              // Sur téléphone, rangé, il reste un vrai texte (85 %) et non
              // une mention à peine visible.
              color: parked ? (twoLines ? 'rgba(240,238,250,0.85)' : 'rgba(233,231,245,0.6)') : 'rgb(255,255,255)',
              fontWeight: parked ? (twoLines ? 600 : 500) : 800,
            }}
            transition={
              phase === 0
                ? { duration: 0 }
                : big
                  ? { duration: 1.05, ease: [0.1, 0.75, 0.12, 1], opacity: { duration: 0.45 } }
                  : { duration: 1.1, ease: [0.16, 1, 0.3, 1], color: { duration: 0.8 } }
            }
            style={{ transformOrigin: 'left top' }}
            className="absolute left-0 top-0 whitespace-nowrap text-center leading-[1.25] tracking-[-0.01em]"
          >
            <Lines lines={lines} />
          </motion.div>
        </div>
      </div>
    </div>
  )
}
