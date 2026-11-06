import type { ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useIsMobile } from '@/mobile/useIsMobile'
import { DRAWER_ROOTS, MenuButton } from '@/mobile/ui/NavDrawer'
import { BackArrowIcon } from './icons/BackArrowIcon'
import { HeaderFriendsShortcut } from './HeaderFriendsShortcut'

// Avant ce fichier, ce bouton retour (icône + hover) était copié-collé à
// l'identique dans 9 pages (Settings, Server, Stats, Instances, Mods, Sync,
// Information, Legal, Login).

export function PageBackButton({ to = '/home' }: { to?: string | number }) {
  const navigate = useNavigate()
  const go = () => { typeof to === 'number' ? navigate(to) : navigate(to) }
  return (
    <button
      onClick={go}
      // Téléphone : 44 px, sans fond, la couleur des retours des écrans poussés.
      className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg transition-all duration-150 text-[rgba(255,255,255,0.35)] bg-[rgba(255,255,255,0.04)] hover:text-[rgba(255,255,255,0.7)] hover:bg-[rgba(255,255,255,0.08)] max-sm:h-11 max-sm:w-11 max-sm:rounded-full max-sm:bg-transparent max-sm:text-[#a29bfe] max-sm:[&_svg]:h-5 max-sm:[&_svg]:w-5"
    >
      <BackArrowIcon />
    </button>
  )
}

export function PageHeaderSeparator() {
  return <div className="w-px h-[28px] bg-[rgba(255,255,255,0.07)] flex-shrink-0" />
}

/** Barre d'en-tête standard (bouton retour + contenu libre) — le contenu après
 * le bouton (séparateur, titre, badges...) varie trop d'une page à l'autre
 * pour être figé dans l'API ; il est passé en children. */
export function PageHeader({ px = 6, backTo, children }: { px?: number; backTo?: string | number; children: ReactNode }) {
  // Téléphone : un écran racine du menu (Réglages, Support…) ouvre le menu
  // latéral par ☰ au lieu de revenir à l'accueil ; un écran poussé garde son
  // retour.
  const mobile = useIsMobile()
  const { pathname } = useLocation()
  const root = mobile && DRAWER_ROOTS.includes(pathname)
  return (
    <div
      className={`flex flex-shrink-0 items-center gap-3 py-3 border-b border-[rgba(255,255,255,0.06)] ${mobile ? 'px-2 pt-[max(12px,env(safe-area-inset-top))]' : px === 5 ? 'px-5' : 'px-6'}`}
    >
      {root ? <MenuButton /> : <PageBackButton to={backTo} />}
      {children}
      <HeaderFriendsShortcut />
    </div>
  )
}
