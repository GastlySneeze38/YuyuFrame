import { useCallback, useEffect, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { listItemVariants, listVariants } from '@/lib/motion'

/**
 * Navigation des réglages : la liste de gauche et le repérage de la section
 * lue.
 *
 * ── Pourquoi ce n'est plus un IntersectionObserver ────────────────────────
 * L'ancienne version observait les sections et retenait la plus haute des
 * visibles. Deux défauts, et c'est ce qui rendait la barre pénible :
 *
 *  1. Un clic lançait un défilement doux, pendant lequel TOUTES les sections
 *     traversées passaient devant l'observateur. La sélection sautait donc de
 *     l'une à l'autre pendant le trajet et finissait souvent ailleurs que sur
 *     celle qu'on avait cliquée.
 *  2. Les dernières sections n'atteignent jamais le haut quand le contenu
 *     s'arrête avant : impossible de les sélectionner en défilant.
 *
 * Ici on lit directement la position de défilement : est retenue la dernière
 * section dont le haut est passé au-dessus d'une ligne de visée, et le bas de
 * la page sélectionne toujours la dernière. Pendant un défilement commandé
 * par un clic, le repérage est mis en pause : la section cliquée reste
 * sélectionnée jusqu'à l'arrivée, quoi qu'il traverse.
 */

/** Distance au haut du cadre où une section est considérée « lue ». */
const AIM_LINE_PX = 140
/** Sécurité si `scrollend` n'arrive jamais (défilement interrompu). */
const SCROLL_TIMEOUT_MS = 900

export interface SettingsCategory {
  id: string
  label: string
  icon: string
}

export function useSectionSpy(categories: readonly SettingsCategory[]) {
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const sectionRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const lockedRef = useRef(false)
  const timerRef = useRef<number | undefined>(undefined)
  const [activeId, setActiveId] = useState(categories[0].id)

  const spy = useCallback(() => {
    const root = scrollRef.current
    if (!root || lockedRef.current) return

    // Tout en bas, la dernière section est forcément celle qu'on regarde,
    // même si son haut n'a jamais franchi la ligne de visée.
    if (root.scrollTop + root.clientHeight >= root.scrollHeight - 4) {
      setActiveId(categories[categories.length - 1].id)
      return
    }

    const rootTop = root.getBoundingClientRect().top + AIM_LINE_PX
    let current = categories[0].id
    for (const { id } of categories) {
      const el = sectionRefs.current[id]
      if (el && el.getBoundingClientRect().top <= rootTop) current = id
    }
    setActiveId(current)
  }, [categories])

  useEffect(() => {
    const root = scrollRef.current
    if (!root) return
    spy()
    root.addEventListener('scroll', spy, { passive: true })
    return () => root.removeEventListener('scroll', spy)
  }, [spy])

  /** Va à une section et l'y garde sélectionnée le temps du trajet. */
  const goTo = useCallback((id: string) => {
    setActiveId(id)
    lockedRef.current = true
    window.clearTimeout(timerRef.current)
    const release = () => {
      lockedRef.current = false
      spy()
    }
    // `scrollend` quand le navigateur le connaît, sinon le délai de secours.
    const root = scrollRef.current
    if (root && 'onscrollend' in root) {
      root.addEventListener('scrollend', release, { once: true })
      timerRef.current = window.setTimeout(release, SCROLL_TIMEOUT_MS * 2)
    } else {
      timerRef.current = window.setTimeout(release, SCROLL_TIMEOUT_MS)
    }
    sectionRefs.current[id]?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [spy])

  useEffect(() => () => window.clearTimeout(timerRef.current), [])

  return { scrollRef, sectionRefs, activeId, goTo }
}

export function SettingsNav({
  categories,
  activeId,
  onPick,
  title,
}: {
  categories: readonly SettingsCategory[]
  activeId: string
  onPick: (id: string) => void
  title: string
}) {
  return (
    <div className="flex w-[228px] shrink-0 flex-col border-r border-line-soft bg-white/[0.02]">
      <div className="px-5 pb-2 pt-4 text-[10px] uppercase tracking-[0.08em] text-txt-muted">{title}</div>

      <motion.div variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-0.5 px-2">
        {categories.map(({ id, label, icon }) => {
          const active = activeId === id
          return (
            <motion.button
              key={id}
              variants={listItemVariants}
              onClick={() => onPick(id)}
              whileHover={{ x: active ? 0 : 3 }}
              whileTap={{ scale: 0.99 }}
              transition={{ type: 'spring', stiffness: 700, damping: 30, mass: 0.4 }}
              className={`relative flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-left text-[13px] font-semibold transition-colors duration-150 ${
                active ? 'text-white' : 'text-txt-secondary hover:text-txt-primary'
              }`}
            >
              {/* Le fond de la sélection glisse d'une entrée à l'autre : c'est
                  le même élément, partagé par `layoutId`. */}
              {active && (
                <motion.span
                  layoutId="settings-nav-active"
                  transition={{ type: 'spring', stiffness: 520, damping: 38 }}
                  className="absolute inset-0 rounded-xl border border-accent/30 bg-accent/12"
                />
              )}
              <span className={`relative flex h-5 w-5 items-center justify-center ${active ? 'text-accent-hover' : 'text-txt-muted'}`}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
                  <path d={icon} />
                </svg>
              </span>
              <span className="relative">{label}</span>
            </motion.button>
          )
        })}
      </motion.div>
    </div>
  )
}
