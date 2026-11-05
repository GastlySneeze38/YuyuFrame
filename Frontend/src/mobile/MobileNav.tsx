import type { ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'

/**
 * Les icônes de la barre de l'accueil du PC (`NavLink`, pages/Home.tsx),
 * reprises telles quelles : passer d'un appareil à l'autre ne doit rien
 * changer à la façon de reconnaître une entrée. Celles qui ne sont pas dans
 * la barre du téléphone servent aux liens de la page (`MobileHome`).
 */
export const NAV_ICONS = {
  home: <path d="M12 3.2l-9 7.6V21h6.5v-6h5v6H21V10.8z" />,
  instances: <path d="M21 16.5c0 .38-.21.71-.53.88l-7.9 4.44c-.16.12-.36.18-.57.18s-.41-.06-.57-.18l-7.9-4.44A1 1 0 013 16.5v-9c0-.38.21-.71.53-.88l7.9-4.44c.16-.12.36-.18.57-.18s.41.06.57.18l7.9 4.44c.32.17.53.5.53.88v9z" />,
  settings: <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" />,
  features: <path d="M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z" />,
  plans: <path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" />,
  support: <path d="M12 2a9 9 0 00-9 9v5a3 3 0 003 3h1a1 1 0 001-1v-5a1 1 0 00-1-1H5v-1a7 7 0 1114 0v1h-2a1 1 0 00-1 1v5a1 1 0 001 1h1a3 3 0 003-3v-5a9 9 0 00-9-9z" />,
  account: <path d="M12 12c2.7 0 4.8-2.1 4.8-4.8S14.7 2.4 12 2.4 7.2 4.5 7.2 7.2 9.3 12 12 12zm0 2.4c-3.2 0-9.6 1.6-9.6 4.8v2.4h19.2v-2.4c0-3.2-6.4-4.8-9.6-4.8z" />,
  competitive: <path d="M19 4h-2V3a1 1 0 00-1-1H8a1 1 0 00-1 1v1H5a2 2 0 00-2 2v1a5 5 0 004.6 4.98A5.01 5.01 0 0011 15.9V18H8a1 1 0 00-1 1v2h10v-2a1 1 0 00-1-1h-3v-2.1a5.01 5.01 0 003.4-3.92A5 5 0 0021 7V6a2 2 0 00-2-2zM5 7V6h2v3.82A3 3 0 015 7zm14 0a3 3 0 01-2 2.82V6h2v1z" />,
} satisfies Record<string, ReactNode>

/** Les écrans racines des onglets : passer de l'un à l'autre est instantané
 *  (pas de transition de page, `PageTransition`) et ne s'empile pas dans
 *  l'historique. */
export const MOBILE_TABS = ['/home', '/instances', '/plans', '/settings', '/login']

interface Entry {
  path: string
  label: string
  icon: ReactNode
  /** Plans garde la couleur de l'offre, même quand il n'est pas l'onglet ouvert. */
  accent?: boolean
}

/**
 * La barre d'onglets du bas, sur téléphone — telle que l'attendent iOS et
 * Android : cinq onglets de même largeur et de même taille, l'onglet de
 * l'écran ouvert marqué (pastille derrière l'icône, texte blanc), les autres
 * en retrait. Une barre d'onglets sert à savoir où l'on est : elle n'a pas
 * de hiérarchie entre ses entrées. Plans se distingue seulement par sa
 * couleur. Outils, Support et Compétitif sont des tuiles de l'accueil.
 */
export function MobileNav() {
  const t = useT()
  const navigate = useNavigate()
  const { pathname } = useLocation()

  const entries: Entry[] = [
    { path: '/home', label: t('home.nav.home'), icon: NAV_ICONS.home },
    { path: '/instances', label: t('home.nav.instances'), icon: NAV_ICONS.instances },
    { path: '/plans', label: t('home.nav.plans'), icon: NAV_ICONS.plans, accent: true },
    { path: '/settings', label: t('home.nav.settings'), icon: NAV_ICONS.settings },
    { path: '/login', label: t('home.nav.account'), icon: NAV_ICONS.account },
  ]

  return (
    <nav className="shrink-0 border-t border-white/[0.06] bg-[#09090D] pb-[env(safe-area-inset-bottom)]">
      <ul className="flex h-14 items-stretch">
        {entries.map((e) => {
          const active = pathname === e.path || pathname.startsWith(`${e.path}/`)
          const tone = active ? 'text-white' : e.accent ? 'text-[#a78bfa]' : 'text-white/55'
          return (
            <li key={e.path} className="flex min-w-0 flex-1">
              <button
                // `replace` : le bouton retour d'Android ramène à l'écran
                // d'avant les onglets, il ne repasse pas par chaque onglet
                // touché. Depuis un onglet, il ramène à l'accueil.
                onClick={() => {
                  if (active) return
                  const fromTab = MOBILE_TABS.includes(pathname)
                  navigate(e.path, { replace: fromTab && pathname !== '/home' })
                }}
                aria-current={active ? 'page' : undefined}
                className={`press flex w-full min-w-0 flex-col items-center justify-center gap-1 ${tone}`}
              >
                <span className={`flex h-7 w-14 items-center justify-center rounded-full transition-colors duration-150 ${active ? (e.accent ? 'bg-[rgba(120,100,255,0.28)]' : 'bg-white/[0.12]') : ''}`}>
                  <svg viewBox="0 0 24 24" fill="currentColor" className="h-[22px] w-[22px]">{e.icon}</svg>
                </span>
                <span className={`max-w-full truncate px-0.5 text-[11px] leading-tight ${active ? 'font-semibold' : 'font-medium'}`}>{e.label}</span>
              </button>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
