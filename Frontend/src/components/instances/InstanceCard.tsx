import { memo, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion } from 'framer-motion'
import type { Instance } from '@/types'
import { loaderColor } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { useT } from '@/i18n'
import { SNAP, listItemVariants } from '@/lib/motion'
import { MenuItem } from './MenuItem'

/** Mémoïsé : rendu en liste — les callbacks reçoivent l'id/l'instance pour
 * que le parent puisse passer des références stables (useCallback ou setter
 * Zustand/useState direct) au lieu d'une closure par ligne. */
export const InstanceCard = memo(function InstanceCard({
  instance,
  selected,
  onSelect,
  onToggleFavorite,
  onDelete,
  onSettings,
  onDuplicate,
  onOpenFolder,
}: {
  instance: Instance
  selected: boolean
  onSelect: (id: string) => void
  onToggleFavorite: (id: string) => void
  onDelete: (id: string) => void
  /** Ouvre la modale de paramètres — tout ce qui se règle y vit. */
  onSettings: (instance: Instance) => void
  onDuplicate: (instance: Instance) => void
  onOpenFolder: (instance: Instance) => void
}) {
  const t = useT()
  const [hovered, setHovered] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [confirm, setConfirm] = useState(false)
  /** Coin haut droit du menu, en coordonnées de fenêtre (voir `toggleMenu`). */
  const [menuAt, setMenuAt] = useState({ top: 0, right: 0 })
  const menuRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  const closeMenu = () => {
    setMenuOpen(false)
    setConfirm(false)
  }

  // Fermeture du menu au clic en dehors — évite de devoir le refermer manuellement
  // et empêche tout clic accidentel sur une action pendant que le menu se ferme.
  //
  // Deux zones à tester depuis que le menu vit à la racine du document : le
  // bouton (`menuRef`) et le menu lui-même (`panelRef`), qui n'est plus un
  // descendant du premier.
  useEffect(() => {
    if (!menuOpen) return
    const handler = (e: MouseEvent) => {
      const target = e.target as Node
      if (menuRef.current?.contains(target) || panelRef.current?.contains(target)) return
      closeMenu()
    }
    // Le menu est posé à une place calculée une fois : si la liste défile
    // dessous, il resterait accroché au vide. On le referme — rouvrir est un
    // clic, suivre le défilement à chaque image en coûterait bien plus.
    // `capture` : le défilement d'un conteneur ne remonte pas jusqu'ici.
    document.addEventListener('mousedown', handler)
    document.addEventListener('scroll', closeMenu, true)
    window.addEventListener('resize', closeMenu)
    return () => {
      document.removeEventListener('mousedown', handler)
      document.removeEventListener('scroll', closeMenu, true)
      window.removeEventListener('resize', closeMenu)
    }
  }, [menuOpen])

  /**
   * Ouvre le menu, en notant où le poser.
   *
   * Le menu était posé DANS la carte (`position: absolute`), donc enfermé
   * dans la colonne de la liste, qui rogne ce qui dépasse : sur la dernière
   * carte, il n'en restait qu'une ligne — alors qu'il y avait toute la place
   * voulue en dessous, simplement de l'autre côté du bord.
   *
   * Il est maintenant rendu à la racine du document, en coordonnées de
   * fenêtre : plus aucun ancêtre ne peut le rogner, et aucune pile de
   * contextes d'empilement ne peut le faire passer sous une carte voisine.
   * Le prix est qu'il ne suit plus son bouton tout seul — d'où la mesure
   * ici, et la fermeture au défilement.
   */
  function toggleMenu() {
    if (menuOpen) {
      closeMenu()
      return
    }
    const button = menuRef.current?.getBoundingClientRect()
    if (button) {
      // Aligné à droite sur le bouton, 4 px en dessous : exactement ce que
      // faisaient `right-0` et `mt-1` quand le menu vivait dans la carte.
      setMenuAt({ top: button.bottom + 4, right: window.innerWidth - button.right })
    }
    setMenuOpen(true)
    setConfirm(false)
  }

  return (
    // `layout` : quand une carte part (suppression) ou change de section
    // (mise en favori), les voisines glissent à leur nouvelle place au lieu
    // de sauter. `listItemVariants` la fait entrer avec ses sœurs.
    <motion.div
      layout
      variants={listItemVariants}
      initial="initial"
      animate="animate"
      exit={{ opacity: 0, x: -12, scale: 0.97, transition: { duration: 0.18 } }}
      whileHover={{ x: 3 }}
      whileTap={{ scale: 0.99 }}
      transition={SNAP}
      onClick={() => onSelect(instance.id)}
      // Plus de `z-40` quand le menu est ouvert : il ne vit plus dans la
      // carte, donc plus rien à faire passer au-dessus des voisines.
      className={`flex flex-col rounded-2xl px-4 py-3.5 cursor-pointer transition-colors duration-150 relative border ${
        selected
          ? 'bg-[rgba(75,63,207,0.18)] border-[rgba(75,63,207,0.55)] shadow-[0_0_20px_rgba(75,63,207,0.18)]'
          : hovered
            ? 'bg-[rgba(255,255,255,0.06)] border-[rgba(255,255,255,0.06)] shadow-none'
            : 'bg-[rgba(255,255,255,0.04)] border-[rgba(255,255,255,0.06)] shadow-none'
      }`}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {/* Partie haute : icône + nom + infos */}
      <div className="flex items-start gap-3 relative">
        {/* Le bloc bascule quand la carte est choisie : un repère de plus que
            la couleur, utile quand la liste est longue. */}
        <motion.div
          animate={selected ? { rotate: [0, -8, 6, 0], scale: 1.06 } : { rotate: 0, scale: 1 }}
          transition={{ type: 'spring', stiffness: 420, damping: 16 }}
          className={`flex items-center justify-center rounded-xl flex-shrink-0 w-[36px] h-[36px] text-[15px] ${selected ? 'bg-[rgba(75,63,207,0.3)]' : 'bg-[rgba(255,255,255,0.05)]'}`}
        >
          🧱
        </motion.div>

        <div className="flex flex-col flex-1 min-w-0">
          {/* Nom + étoile + menu */}
          <div>
            <p className={`font-bold truncate text-[13px] pr-[30px] w-full ${selected ? 'text-white' : 'text-[rgba(255,255,255,0.85)]'}`}>
              {instance.name}
            </p>

            <div ref={menuRef} className="absolute flex flex-col items-center gap-0.5 flex-shrink-0 top-1/2 right-0 -translate-y-1/2">
              <motion.button
                onClick={(e) => { e.stopPropagation(); onToggleFavorite(instance.id) }}
                whileHover={{ scale: 1.25, rotate: 12 }}
                whileTap={{ scale: 0.8 }}
                // L'étoile fait un tour sur elle-même quand elle s'allume :
                // c'est une action sans confirmation, elle doit se voir.
                animate={instance.favorite ? { rotate: [0, 360], scale: [1, 1.35, 1] } : {}}
                transition={{ type: 'spring', stiffness: 500, damping: 18 }}
                className={`flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-lg bg-transparent transition-colors duration-150 ${
                  instance.favorite
                    ? 'text-[#facc15] hover:text-[#fde047]'
                    : 'text-[rgba(255,255,255,0.18)] hover:text-[rgba(255,255,255,0.5)]'
                }`}
                title={instance.favorite ? t('instancesPage.removeFromFavorites') : t('instancesPage.addToFavorites')}
              >
                <svg viewBox="0 0 24 24" fill={instance.favorite ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={instance.favorite ? 0 : 1.8} width={13} height={13}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M11.48 3.499a.562.562 0 011.04 0l2.125 5.111a.563.563 0 00.475.345l5.518.442c.499.04.701.663.321.988l-4.204 3.602a.563.563 0 00-.182.557l1.285 5.385a.562.562 0 01-.84.61l-4.725-2.885a.563.563 0 00-.586 0L6.982 20.54a.562.562 0 01-.84-.61l1.285-5.386a.562.562 0 00-.182-.557l-4.204-3.602a.563.563 0 01.321-.988l5.518-.442a.563.563 0 00.475-.345L11.48 3.5z" />
                </svg>
              </motion.button>

              <motion.button
                onClick={(e) => { e.stopPropagation(); toggleMenu() }}
                whileHover={{ scale: 1.15 }}
                whileTap={{ scale: 0.88 }}
                // Les trois points se redressent quand le menu s'ouvre.
                animate={{ rotate: menuOpen ? 90 : 0 }}
                transition={SNAP}
                className={`flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-lg transition-colors duration-150 ${
                  menuOpen
                    ? 'text-[rgba(255,255,255,0.85)] bg-[rgba(255,255,255,0.1)]'
                    : 'text-[rgba(255,255,255,0.25)] bg-transparent hover:text-[rgba(255,255,255,0.6)]'
                }`}
                title={t('instancesPage.moreActions')}
              >
                <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}>
                  <circle cx="5" cy="12" r="2" /><circle cx="12" cy="12" r="2" /><circle cx="19" cy="12" r="2" />
                </svg>
              </motion.button>

              {/* Rendu à la racine du document, hors de la colonne qui rogne
                  ce qui dépasse et hors de toute pile d'empilement locale.
                  C'est ce qui lui rend les deux choses qu'il perdait sur la
                  dernière carte : la place, et le dessus. */}
              {createPortal(
                <AnimatePresence>
                {menuOpen && (
                <motion.div
                  ref={panelRef}
                  onClick={(e) => e.stopPropagation()}
                  initial={{ opacity: 0, y: -6, scale: 0.96 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: -4, scale: 0.97, transition: { duration: 0.12 } }}
                  transition={SNAP}
                  // Le menu s'ouvre depuis son coin haut droit, sous le bouton
                  // qui l'a appelé, au lieu de grandir depuis son centre.
                  style={{ transformOrigin: 'top right', top: menuAt.top, right: menuAt.right }}
                  className="fixed flex flex-col gap-0.5 rounded-xl p-1 w-[190px] z-[60] bg-[#1a1a24] border border-[rgba(255,255,255,0.1)] shadow-[0_12px_32px_rgba(0,0,0,0.5)]"
                >
                  {!confirm ? (
                    <>
                      {/* Un seul chemin vers tout ce qui se règle. « Modifier
                          l'instance » et « Exporter mes paramètres » étaient
                          deux entrées de ce menu, chacune menant à une partie
                          des réglages ; elles sont devenues deux onglets de la
                          modale de paramètres. Ne restent ici que les gestes
                          qui n'ont rien à régler. */}
                      <MenuItem
                        onClick={() => { setMenuOpen(false); onSettings(instance) }}
                        label={t('instancesPage.settings')}
                        icon={<svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M19.14 12.94a7.07 7.07 0 000-1.88l2.03-1.58a.5.5 0 00.12-.64l-1.92-3.32a.5.5 0 00-.6-.22l-2.39.96a7.03 7.03 0 00-1.63-.94l-.36-2.54a.5.5 0 00-.5-.42h-3.84a.5.5 0 00-.5.42l-.36 2.54c-.59.24-1.13.56-1.63.94l-2.39-.96a.5.5 0 00-.6.22L2.65 8.84a.5.5 0 00.12.64l2.03 1.58a7.07 7.07 0 000 1.88l-2.03 1.58a.5.5 0 00-.12.64l1.92 3.32c.13.22.39.3.6.22l2.39-.96c.5.38 1.04.7 1.63.94l.36 2.54c.04.24.25.42.5.42h3.84c.25 0 .46-.18.5-.42l.36-2.54c.59-.24 1.13-.56 1.63-.94l2.39.96c.21.08.47 0 .6-.22l1.92-3.32a.5.5 0 00-.12-.64l-2.03-1.58zM12 15.6A3.6 3.6 0 1112 8.4a3.6 3.6 0 010 7.2z" /></svg>}
                      />
                      <MenuItem
                        onClick={() => { setMenuOpen(false); onDuplicate(instance) }}
                        label={t('instancesPage.duplicate')}
                        icon={<svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" /></svg>}
                      />
                      <MenuItem
                        onClick={() => { setMenuOpen(false); onOpenFolder(instance) }}
                        label={t('instancesPage.openFolder')}
                        icon={<svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M20 6h-8l-2-2H4c-1.1 0-2 .89-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.11-.9-2-2-2z" /></svg>}
                      />
                      <MenuItem
                        onClick={() => setConfirm(true)}
                        label={t('instancesPage.deleteForever')}
                        danger
                        icon={<svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" /></svg>}
                      />
                    </>
                  ) : (
                    <div className="flex flex-col gap-1.5 p-1">
                      <p className="text-[11px] text-[rgba(255,255,255,0.5)]">{t('instancesPage.confirmDeleteInstance')}</p>
                      <div className="flex gap-1.5">
                        <button
                          onClick={() => { setMenuOpen(false); onDelete(instance.id) }}
                          className="flex-1 rounded-lg text-[11px] font-semibold text-[rgb(248,113,113)] bg-[rgba(200,50,50,0.15)] py-[5px]"
                        >
                          {t('common.delete')}
                        </button>
                        <button
                          onClick={() => setConfirm(false)}
                          className="flex-1 rounded-lg text-[11px] text-[rgba(255,255,255,0.5)] bg-[rgba(255,255,255,0.06)] py-[5px]"
                        >
                          {t('common.cancel')}
                        </button>
                      </div>
                    </div>
                  )}
                </motion.div>
                )}
                </AnimatePresence>,
                document.body,
              )}
            </div>
          </div>

          {/* Infos */}
          <motion.div
            variants={{ initial: {}, animate: { transition: { staggerChildren: 0.04, delayChildren: 0.05 } } }}
            className="flex items-center gap-2 mt-0.5"
          >
            {[
              <span key="v" className="text-[11px] text-[rgba(255,255,255,0.3)]">{instance.mc_version}</span>,
              <span key="l" className="text-[10px] font-semibold" style={{ color: loaderColor(instance.loader) }}>{instance.loader}</span>,
              <span key="r" className="text-[10px] text-[rgba(255,255,255,0.2)]">{formatRam(instance.ram_mb)}</span>,
            ].map((child, i) => (
              <motion.span
                key={i}
                variants={{ initial: { opacity: 0, y: 4 }, animate: { opacity: 1, y: 0 } }}
                transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
              >
                {child}
              </motion.span>
            ))}
          </motion.div>
        </div>
      </div>

      {/* Description (si renseignée) — masquée par défaut, dépliée lentement au survol */}
      {instance.description && (
        <div
          className={`overflow-hidden ml-0 [transition:max-height_420ms_cubic-bezier(0.16,1,0.3,1),opacity_380ms_ease,margin-top_420ms_cubic-bezier(0.16,1,0.3,1)] ${
            hovered ? 'max-h-[60px] opacity-100 mt-2' : 'max-h-0 opacity-0 mt-0'
          }`}
        >
          <p className="text-[11px] text-[rgba(255,255,255,0.35)] whitespace-pre-wrap break-words">
            {instance.description}
          </p>
        </div>
      )}
    </motion.div>
  )
})
