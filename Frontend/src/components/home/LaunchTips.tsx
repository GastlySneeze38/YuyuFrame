import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion, type Variants } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'
import { openExternal } from '@/lib/openExternal'

/**
 * Bandeau d'astuces posé sur la rangée des serveurs pendant que l'instance
 * choisie tourne : à ce moment-là, les serveurs ne servent plus à rien (on
 * ne lance pas une deuxième fois), la place est libre pour dire ce que les
 * gens ne savent pas encore.
 *
 * Chaque astuce traverse de droite à gauche comme un wagon : elle arrive en
 * pointe de flèche, se pose (la pointe rentre), reste quelques secondes, puis
 * repart vers la gauche en pointe pendant que la suivante arrive. Le survol
 * met la rotation en pause.
 *
 * Disparaît dès que l'instance choisie ne tourne plus — partie finie, ou
 * changement d'instance (on peut alors relancer, les serveurs redeviennent
 * utiles).
 */

type TipId = 'mobile' | 'pro' | 'horizon' | 'menu'

/** Temps de pose d'une astuce. */
const HOLD_MS = 8000
/** Le gris du bandeau : la surface surélevée du thème, éclaircie d'un cran,
 *  pour rester un gris lisible sur Nuit, OLED comme Graphite. */
const GREY = 'color-mix(in srgb, rgb(var(--bg-raised)) 80%, white)'
/** Courbe de sortie franche : le wagon arrive vite et se pose en douceur. */
const EASE_OUT = [0.23, 1, 0.32, 1] as const

const ICONS: Record<TipId, string> = {
  mobile: 'M8 3h8a2 2 0 012 2v14a2 2 0 01-2 2H8a2 2 0 01-2-2V5a2 2 0 012-2zM11 18h2',
  pro: 'M12 3l2.6 5.6 6 .7-4.5 4.1 1.2 6L12 16.5 6.7 19.4l1.2-6L3.4 9.3l6-.7z',
  horizon: 'M3 17h18M5 17a7 7 0 0114 0M12 6V4M5.6 9.6L4.2 8.2M18.4 9.6l1.4-1.4',
  menu: 'M4 6h16M4 12h16M4 18h10',
}

// Le wagon. `x` en pourcentage de sa propre largeur : il part et finit hors
// de la rangée quelle que soit sa taille.
const wagon: Variants = {
  enter: { x: '108%', opacity: 1 },
  rest: { x: 0, opacity: 1, transition: { duration: 0.62, ease: EASE_OUT } },
  leave: { x: '-108%', opacity: 1, transition: { duration: 0.5, ease: [0.55, 0, 0.75, 0.2] } },
}
// La pointe : sortie pendant le trajet, rentrée une fois posée.
const tip: Variants = {
  enter: { scaleX: 1 },
  rest: { scaleX: 0, transition: { delay: 0.5, duration: 0.18, ease: EASE_OUT } },
  leave: { scaleX: 1, transition: { duration: 0.12, ease: EASE_OUT } },
}

export function LaunchTips({ visible, showPro, showHorizon, showMenu }: {
  /** L'instance choisie tourne. */
  visible: boolean
  /** Le compte est sur le plan gratuit. */
  showPro: boolean
  /** L'instance choisie n'est pas déjà une instance Horizon. */
  showHorizon: boolean
  /** Le client intégré se charge dans cette instance (le menu existe). */
  showMenu: boolean
}) {
  const t = useT()
  const navigate = useNavigate()
  const tips = useMemo(() => {
    const list: TipId[] = ['mobile', 'menu', 'pro', 'horizon']
    return list.filter((id) => (id === 'pro' ? showPro : id === 'horizon' ? showHorizon : id === 'menu' ? showMenu : true))
  }, [showPro, showHorizon, showMenu])

  const [index, setIndex] = useState(0)
  const [paused, setPaused] = useState(false)

  // Chaque lancement repart de la première astuce.
  useEffect(() => { if (visible) setIndex(0) }, [visible])

  useEffect(() => {
    if (!visible || paused || tips.length < 2) return
    const timer = setTimeout(() => setIndex((i) => (i + 1) % tips.length), HOLD_MS)
    return () => clearTimeout(timer)
  }, [visible, paused, index, tips.length])

  const current = tips[index % Math.max(1, tips.length)]

  const act = (id: TipId) => {
    if (id === 'pro') navigate('/plans')
    else if (id === 'horizon') navigate('/instances')
    else if (id === 'mobile') void openExternal('https://yuyuframe.eu')
  }

  return (
    <AnimatePresence>
      {visible && current && (
        // Le cadre : il découpe ce qui dépasse, donc les wagons naissent et
        // meurent au bord de la rangée. Il n'apparaît et ne disparaît qu'en
        // opacité, plus vite qu'il ne vient.
        <motion.div
          key="tips"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1, transition: { duration: 0.15 } }}
          exit={{ opacity: 0, transition: { duration: 0.12 } }}
          onMouseEnter={() => setPaused(true)}
          onMouseLeave={() => setPaused(false)}
          // Fond de la page : entre deux wagons, on ne revoit pas les serveurs.
          className="absolute inset-0 z-20 overflow-hidden rounded-xl bg-[rgb(var(--bg-primary))]"
          role="status"
          aria-live="polite"
        >
          <AnimatePresence initial={true}>
            <motion.div
              key={current}
              variants={wagon}
              initial="enter"
              animate="rest"
              exit="leave"
              className="absolute inset-0"
            >
              {/* La pointe de flèche, collée au bord gauche du wagon. */}
              <motion.svg
                variants={tip}
                viewBox="0 0 16 100"
                preserveAspectRatio="none"
                className="absolute right-full top-0 h-full w-4 origin-right"
                style={{ fill: GREY }}
                aria-hidden
              >
                <polygon points="16,0 0,50 16,100" />
              </motion.svg>
              {/* Coins gauches droits : la pointe s'y raccorde sans encoche,
                  et posé, c'est le cadre arrondi qui les découpe. */}
              <div className="flex h-full items-center gap-[clamp(10px,1.4vw,16px)] rounded-r-xl pl-3 pr-[clamp(10px,1.4vw,16px)]" style={{ background: GREY }}>
                <span className="flex h-[clamp(28px,4.4vh,40px)] w-[clamp(28px,4.4vh,40px)] flex-shrink-0 items-center justify-center rounded-lg bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.7)]">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-[55%] w-[55%]">
                    <path d={ICONS[current]} />
                  </svg>
                </span>
                <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="text-[clamp(9px,1.15vh,10.5px)] font-bold uppercase tracking-[0.12em] text-[rgba(255,255,255,0.35)]">
                    {t('home.tips.kicker')}
                  </span>
                  <span className="truncate text-[clamp(12px,1.7vh,15px)] font-semibold text-white">{t(`home.tips.${current}.title`)}</span>
                  <span className="truncate text-[clamp(10.5px,1.4vh,12.5px)] text-[rgba(255,255,255,0.55)]">
                    {current === 'menu'
                      ? <>{t('home.tips.menu.before')} <Kbd>{t('home.tips.menu.key')}</Kbd> {t('home.tips.menu.after')}</>
                      : t(`home.tips.${current}.text`)}
                  </span>
                </div>
                {current !== 'menu' && (
                  <button
                    type="button"
                    onClick={() => act(current)}
                    className="flex-shrink-0 rounded-lg bg-[rgba(255,255,255,0.08)] px-3 py-1.5 text-[12px] font-semibold text-white transition-[background-color,transform] duration-150 hover:bg-[rgba(255,255,255,0.14)] active:scale-[0.97]"
                  >
                    {t(`home.tips.${current}.cta`)}
                  </button>
                )}
                {/* Où l'on en est, et un clic pour passer à une astuce. */}
                {tips.length > 1 && (
                  <div className="flex flex-shrink-0 items-center gap-1">
                    {tips.map((id, i) => (
                      <button
                        key={id}
                        type="button"
                        aria-label={t(`home.tips.${id}.title`)}
                        onClick={() => setIndex(i)}
                        className="flex h-5 w-3 items-center justify-center"
                      >
                        <span className={`h-1.5 rounded-full transition-[width,background-color] duration-200 ${id === current ? 'w-3 bg-[rgba(255,255,255,0.7)]' : 'w-1.5 bg-[rgba(255,255,255,0.2)]'}`} />
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </motion.div>
          </AnimatePresence>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

function Kbd({ children }: { children: React.ReactNode }) {
  return (
    <kbd className="mx-0.5 rounded-[5px] border border-[rgba(255,255,255,0.15)] bg-[rgba(255,255,255,0.06)] px-1.5 py-px font-sans text-[0.92em] font-semibold text-white">
      {children}
    </kbd>
  )
}
