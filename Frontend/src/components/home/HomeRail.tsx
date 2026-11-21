import { useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'

/**
 * Rail de navigation de l'accueil PC (essai du 2026-11-21, à la place de la
 * barre du bas). Fermé : des icônes seules, 64 px. Ouvert : le nom de chaque
 * écran, rangé par groupe, et les sous-écrans les plus visités en étiquettes
 * — un clic au lieu de deux ou trois.
 *
 * Plans et Compte n'y sont pas : ils vivent dans le menu du G du panneau
 * d'amis et dans la carte de l'offre. L'état ouvert ou fermé est gardé d'une
 * session à l'autre.
 *
 * Seulement sur l'accueil pour l'instant : si le rendu convient, il passera
 * sur toutes les pages (et le bouton Retour ne servira plus qu'aux
 * sous-écrans).
 */

const OPEN_KEY = 'yf.homeRail.open'

function readOpen(): boolean {
  try { return localStorage.getItem(OPEN_KEY) === '1' } catch { return false }
}

/** Icônes au trait, même grille 24 px que le reste du launcher. */
const ICONS = {
  expand: 'M4 4h16v16H4zM9 4v16M14 10l2 2-2 2',
  collapse: 'M4 4h16v16H4zM9 4v16M16 10l-2 2 2 2',
  home: 'M5 12H3l9-9 9 9h-2M5 12v7a2 2 0 002 2h10a2 2 0 002-2v-7M9 21v-6h6v6',
  instances: 'M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5',
  servers: 'M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01',
  competitive: 'M8 21h8M12 17v4M7 4h10v5a5 5 0 01-10 0zM7 6H4a3 3 0 003 4M17 6h3a3 3 0 01-3 4',
  missions: 'M5 21V4M5 4h12l-2 4 2 4H5',
  skins: 'M8 3l4 2 4-2 4 3-2.5 4H16v11H8V10H5.5L3 6z',
  tools: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  support: 'M4 14v-3a8 8 0 0116 0v3M4 14a2 2 0 002 2h1v-5H6a2 2 0 00-2 2zM20 14a2 2 0 01-2 2h-1v-5h1a2 2 0 012 2zM17 16v1a3 3 0 01-3 3h-2',
  settings: 'M12 9a3 3 0 100 6 3 3 0 000-6zM19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 01-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 010-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 014 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 010 4h-.1a1.7 1.7 0 00-1.5 1z',
} as const

function Icon({ d }: { d: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 flex-shrink-0">
      <path d={d} />
    </svg>
  )
}

export function HomeRail({ supportBadge, instanceCount, instanceName, running }: {
  supportBadge: number
  instanceCount: number
  /** Instance choisie sur l'accueil, `null` s'il n'y en a pas. */
  instanceName: string | null
  /** Une partie de cette instance tourne. */
  running: boolean
}) {
  const t = useT()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [open, setOpen] = useState(readOpen)

  const toggle = () => {
    setOpen((o) => {
      try { localStorage.setItem(OPEN_KEY, o ? '0' : '1') } catch { /* stockage refusé : l'état reste pour la session */ }
      return !o
    })
  }

  return (
    <nav
      // La largeur seule s'anime ; le contenu de l'accueil suit, poussé.
      className={`flex flex-shrink-0 flex-col overflow-hidden border-r border-line-soft bg-bg-secondary py-3 transition-[width] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] ${open ? 'w-[232px]' : 'w-16'}`}
    >
      <RailItem open={open} icon={ICONS[open ? 'collapse' : 'expand']} label={open ? t('home.rail.collapse') : t('home.rail.expand')} onClick={toggle}>
        <span className="font-semibold text-white">YuyuFrame</span>
      </RailItem>

      <Group open={open} label={t('home.rail.groupPlay')} />
      <RailItem open={open} icon={ICONS.home} label={t('home.nav.home')} active={pathname === '/home'} onClick={() => navigate('/home')} />
      <RailItem open={open} icon={ICONS.instances} label={t('home.nav.instances')} count={instanceCount} onClick={() => navigate('/instances')} />
      <RailItem open={open} icon={ICONS.servers} label={t('home.rail.servers')} onClick={() => navigate('/servers')} />

      <Group open={open} label={t('home.rail.groupProgress')} />
      <RailItem open={open} icon={ICONS.competitive} label={t('home.nav.competitive')} onClick={() => navigate('/competitive')} />
      <SubLinks open={open} links={[
        { label: t('competitive.nav.history'), to: '/competitive?view=history' },
        { label: t('competitive.nav.leaderboard'), to: '/competitive?view=leaderboard' },
        { label: t('competitive.nav.season'), to: '/competitive?view=season' },
      ]} />
      <RailItem open={open} icon={ICONS.missions} label={t('home.rail.missions')} onClick={() => navigate('/missions')} />
      <SubLinks open={open} links={[{ label: t('home.rail.pass'), to: '/missions/pass' }]} />

      <Group open={open} label={t('home.rail.groupStyle')} />
      <RailItem open={open} icon={ICONS.skins} label={t('home.rail.skins')} onClick={() => navigate('/skins')} />
      <SubLinks open={open} links={[
        { label: t('home.rail.skinCatalog'), to: '/skins/catalog' },
        { label: t('home.rail.skinEditor'), to: '/skins/editor' },
      ]} />

      <div className="mt-auto" />
      <RailItem open={open} icon={ICONS.tools} label={t('home.nav.features')} onClick={() => navigate('/features')} />
      <RailItem open={open} icon={ICONS.support} label={t('home.nav.support')} badge={supportBadge} onClick={() => navigate('/support')} />
      <RailItem open={open} icon={ICONS.settings} label={t('home.nav.settings')} onClick={() => navigate('/settings')} />

      {/* L'instance choisie et son état : l'anneau vire au vert pendant une
          partie. Sur l'accueil il double le bouton Jouer ; il prendra son
          sens quand le rail sera sur toutes les pages. */}
      {instanceName && (
        <div className="mx-2.5 mt-2 flex h-12 items-center gap-3 overflow-hidden whitespace-nowrap rounded-[10px] bg-bg-card px-[11px]">
          <span className={`flex h-[22px] w-[22px] flex-shrink-0 items-center justify-center rounded-full border-2 ${running ? 'border-success text-success' : 'border-line-strong text-[rgba(255,255,255,0.45)]'}`}>
            <svg viewBox="0 0 24 24" fill="currentColor" className="ml-px h-2.5 w-2.5"><path d="M7 4v16l13-8z" /></svg>
          </span>
          <span className={`min-w-0 leading-tight transition-opacity duration-150 ${open ? 'opacity-100 delay-100' : 'opacity-0'}`}>
            <span className="block truncate text-[13px] text-white">{instanceName}</span>
            <span className={`block text-[11px] ${running ? 'text-success' : 'text-[rgba(255,255,255,0.4)]'}`}>
              {running ? t('home.rail.inGame') : t('home.rail.ready')}
            </span>
          </span>
        </div>
      )}
    </nav>
  )
}

function Group({ open, label }: { open: boolean; label: string }) {
  return (
    <div className={`mx-5 mb-0.5 mt-3 h-3.5 whitespace-nowrap text-[11px] font-semibold uppercase tracking-wider text-[rgba(255,255,255,0.3)] transition-opacity duration-150 ${open ? 'opacity-100 delay-100' : 'opacity-0'}`}>
      {label}
    </div>
  )
}

function RailItem({ open, icon, label, onClick, active, count, badge = 0, children }: {
  open: boolean
  icon: string
  label: string
  onClick: () => void
  active?: boolean
  /** Nombre affiché à droite du libellé, panneau ouvert. */
  count?: number
  /** Éléments en attente : pastille rouge. */
  badge?: number
  children?: ReactNode
}) {
  return (
    <button
      onClick={onClick}
      // Le nom au survol quand le rail est fermé : le libellé est masqué.
      title={open ? undefined : label}
      aria-label={label}
      className={`relative mx-2.5 my-px flex h-10 flex-shrink-0 items-center gap-3 overflow-hidden whitespace-nowrap rounded-[10px] px-[12px] text-left transition-colors duration-150 ${
        active
          ? 'bg-[rgb(var(--accent)/0.18)] text-[rgb(var(--accent-text))]'
          : 'text-[rgba(255,255,255,0.55)] hover:bg-[rgba(255,255,255,0.06)] hover:text-white'
      }`}
    >
      <Icon d={icon} />
      {badge > 0 && !open && (
        <span className="absolute left-[27px] top-[7px] h-2 w-2 rounded-full bg-danger shadow-[0_0_0_2px_rgb(var(--bg-secondary))]" />
      )}
      <span className={`flex min-w-0 flex-1 items-center gap-2 text-[13.5px] font-medium transition-opacity duration-150 ${open ? 'opacity-100 delay-100' : 'opacity-0'}`}>
        {children ?? label}
        {badge > 0 ? (
          <span className="ml-auto rounded-full bg-danger px-1.5 text-[11px] font-bold leading-[18px] text-white">{badge > 9 ? '9+' : badge}</span>
        ) : count != null && count > 0 ? (
          <span className="ml-auto rounded-full bg-[rgb(var(--accent)/0.2)] px-1.5 text-[11px] font-semibold leading-[18px] text-[rgb(var(--accent-text))]">{count}</span>
        ) : null}
      </span>
    </button>
  )
}

/** Sous-écrans en étiquettes sous leur écran, panneau ouvert seulement. */
function SubLinks({ open, links }: { open: boolean; links: { label: string; to: string }[] }) {
  const navigate = useNavigate()
  return (
    <div
      className={`ml-[54px] mr-2.5 flex flex-wrap gap-1 overflow-hidden transition-[max-height,opacity,margin] duration-300 ${open ? 'mb-1.5 max-h-16 opacity-100' : 'mb-0 max-h-0 opacity-0'}`}
      aria-hidden={!open}
    >
      {links.map((l) => (
        <button
          key={l.to}
          tabIndex={open ? 0 : -1}
          onClick={() => navigate(l.to)}
          className="whitespace-nowrap rounded-md bg-[rgba(255,255,255,0.05)] px-2 py-0.5 text-[11.5px] text-[rgba(255,255,255,0.5)] transition-colors duration-150 hover:bg-[rgb(var(--accent)/0.18)] hover:text-[rgb(var(--accent-text))]"
        >
          {l.label}
        </button>
      ))}
    </div>
  )
}
