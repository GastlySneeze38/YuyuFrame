import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { press, EASE_OUT } from '@/lib/motion'

/**
 * Bouton de la barre d'outils qui ouvre un court menu de destinations.
 *
 * Les trois boutons du centre ne mènent plus chacun à un écran mais chacun à
 * une famille : les mods, les packs, les réglages. Une famille tient en deux
 * ou trois écrans — les empiler dans la barre donnerait sept boutons, et
 * personne ne lit sept boutons. Le libellé du bouton reste donc celui de la
 * famille, et le menu dit lequel de ses écrans est ouvert.
 *
 * ── Pourquoi un portail ───────────────────────────────────────────────────
 * Rendu en place, le menu passait sous le contenu de la page, et aucun
 * `z-index` n'y changeait rien — il était pris deux fois.
 *
 * D'abord la barre d'outils est en `overflow-x-auto` (filet de sécurité pour
 * les fenêtres étroites). CSS interdit de rogner un seul axe : demander
 * `auto` en horizontal force `auto` en vertical, donc tout ce qui dépasse
 * vers le bas est coupé.
 *
 * Ensuite les écrans sous la barre sont animés par framer-motion, qui pose
 * une transformée. Une transformée crée un contexte d'empilement : le menu,
 * enfermé dans celui de la barre, ne peut pas passer devant celui d'un frère
 * situé plus loin dans le document, quel que soit son `z-index`.
 *
 * Le sortir du document résout les deux d'un coup. Il est alors positionné en
 * `fixed` d'après le rectangle du bouton, relevé à l'ouverture — et refermé
 * au défilement comme au redimensionnement, deux cas où ce rectangle cesse
 * d'être vrai.
 */

export interface ToolbarMenuItem {
  /** Identifiant de l'écran, comparé à `activeId` pour la coche. */
  id: string
  label: string
  /** Chemin SVG 24×24, dessiné à gauche du libellé. */
  icon: string
  onSelect: () => void
  /** Compteur discret à droite (nombre d'éléments installés, par exemple). */
  badge?: number
}

/** Largeur minimale du menu — sert aussi à décider s'il tient à droite du
 *  bouton ou s'il doit être aligné sur son bord droit. */
const MIN_WIDTH = 220
/** Marge entre le bouton et son menu. */
const GAP = 6

export function ToolbarMenu({
  label, icon, items, activeId, active,
}: {
  label: string
  icon: string
  items: ToolbarMenuItem[]
  /** Écran actuellement ouvert, s'il appartient à ce menu. */
  activeId: string | null
  /** Vrai quand l'écran courant appartient à cette famille. */
  active: boolean
}) {
  const [open, setOpen] = useState(false)
  const [anchor, setAnchor] = useState<{ top: number; left: number } | null>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  const toggle = () => {
    if (open) { setOpen(false); return }
    const rect = buttonRef.current?.getBoundingClientRect()
    if (!rect) return
    // Aligné à gauche du bouton, sauf s'il n'y a plus la place à droite de
    // l'écran — auquel cas on aligne sur son bord droit plutôt que de laisser
    // le menu déborder de la fenêtre.
    const left = rect.left + MIN_WIDTH > window.innerWidth
      ? Math.max(8, rect.right - MIN_WIDTH)
      : rect.left
    setAnchor({ top: rect.bottom + GAP, left })
    setOpen(true)
  }

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: MouseEvent) => {
      const target = e.target as Node
      // Le menu n'est plus un descendant du bouton : il faut tester les deux.
      if (buttonRef.current?.contains(target) || menuRef.current?.contains(target)) return
      setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    // Le menu est posé à une position figée : dès que la page bouge sous lui,
    // il pointe à côté. On le referme plutôt que de le faire suivre.
    const close = () => setOpen(false)
    // `mousedown` plutôt que `click` : le menu doit disparaître dès l'appui,
    // sinon un clic sur un bouton situé dessous se fait à travers un menu
    // encore affiché.
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    window.addEventListener('resize', close)
    // En capture : un défilement dans n'importe quel conteneur de la page
    // compte, pas seulement celui de la fenêtre.
    window.addEventListener('scroll', close, true)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('resize', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [open])

  return (
    <>
      <motion.button {...press}
        ref={buttonRef}
        onClick={toggle}
        aria-haspopup="menu"
        aria-expanded={open}
        className={`flex h-8 items-center gap-1.5 px-[14px] text-[12px] font-semibold transition-colors duration-150 cursor-pointer ${
          active
            ? 'bg-[rgba(75,63,207,0.25)] text-[rgba(255,255,255,0.9)]'
            : 'bg-transparent text-[rgba(255,255,255,0.55)] hover:bg-[rgba(75,63,207,0.12)]'
        }`}
      >
        <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="flex-shrink-0">
          <path d={icon} />
        </svg>
        {label}
        {/* Le chevron pivote : il dit que le bouton ouvre quelque chose, et
            dans quel état il se trouve. */}
        <motion.svg
          viewBox="0 0 10 6" fill="currentColor" width={9} height={6}
          animate={{ rotate: open ? 180 : 0 }}
          transition={{ duration: 0.18, ease: EASE_OUT }}
          className="flex-shrink-0 opacity-50"
        >
          <path d="M0 0l5 6 5-6z" />
        </motion.svg>
      </motion.button>

      {createPortal(
        <AnimatePresence>
          {open && anchor && (
            <motion.div
              ref={menuRef}
              role="menu"
              initial={{ opacity: 0, y: -6, scale: 0.97 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -4, scale: 0.98 }}
              transition={{ duration: 0.16, ease: EASE_OUT }}
              style={{ top: anchor.top, left: anchor.left, minWidth: MIN_WIDTH, transformOrigin: 'top left' }}
              className="fixed z-[80] flex flex-col gap-0.5 rounded-xl border border-[rgba(255,255,255,0.09)] bg-[rgba(18,17,27,0.98)] p-1 shadow-[0_12px_38px_rgba(0,0,0,0.6)] backdrop-blur-[10px]"
            >
              {items.map((item) => (
                <button
                  key={item.id}
                  role="menuitem"
                  onClick={() => { item.onSelect(); setOpen(false) }}
                  className={`flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12px] font-medium transition-colors duration-150 ${
                    activeId === item.id
                      ? 'bg-[rgba(75,63,207,0.28)] text-white'
                      : 'text-[rgba(255,255,255,0.62)] hover:bg-[rgba(255,255,255,0.06)] hover:text-[rgba(255,255,255,0.9)]'
                  }`}
                >
                  <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="flex-shrink-0 opacity-70">
                    <path d={item.icon} />
                  </svg>
                  <span className="flex-1 whitespace-nowrap">{item.label}</span>
                  {item.badge !== undefined && (
                    <span className="flex-shrink-0 text-[10.5px] font-semibold text-[rgba(255,255,255,0.3)]">
                      {item.badge}
                    </span>
                  )}
                </button>
              ))}
            </motion.div>
          )}
        </AnimatePresence>,
        document.body,
      )}
    </>
  )
}
