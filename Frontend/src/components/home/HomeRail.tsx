import { useEffect, useState, type ReactNode } from 'react'
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
 *
 * Petites fenêtres (2026-11-28) — la fenêtre descend à 900 × 560 :
 * - En hauteur, le rail ne tenait pas (une quinzaine de lignes, plus les
 *   étiquettes une fois ouvert) et le bas était coupé : Réglages, l'instance.
 *   Il a maintenant trois étages : le bouton d'ouverture en haut, le pied
 *   (outils, support, compte, réglages, instance) toujours visible, et le
 *   milieu qui défile sans barre, fondu aux deux bords pour qu'on voie qu'il
 *   continue. Sous 720 px de haut, les lignes se resserrent.
 * - En largeur, ouvert, il mangeait 232 px d'un accueil de 900 : sous
 *   `WIDE`, il s'ouvre par-dessus le contenu (ombre, voile qui le referme au
 *   clic, Échap) au lieu de le pousser, et cette ouverture n'est pas gardée.
 */

const OPEN_KEY = 'yf.homeRail.open'

/** Largeur à partir de laquelle le rail ouvert pousse l'accueil au lieu de le recouvrir. */
const WIDE = '(min-width: 1180px)'

function readOpen(): boolean {
  try { return localStorage.getItem(OPEN_KEY) === '1' } catch { return false }
}

function useWide(): boolean {
  const [wide, setWide] = useState(() => window.matchMedia(WIDE).matches)
  useEffect(() => {
    const query = window.matchMedia(WIDE)
    const update = () => setWide(query.matches)
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])
  return wide
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
  accounts: 'M9 11a4 4 0 100-8 4 4 0 000 8zM3 21v-1a6 6 0 0112 0v1M16 3.1a4 4 0 010 7.8M21 21v-1a6 6 0 00-4-5.7',
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
  const wide = useWide()
  // Grand écran : le choix de l'utilisateur, gardé. Petit écran : une
  // ouverture d'un moment, par-dessus le contenu, jamais gardée.
  const [pinned, setPinned] = useState(readOpen)
  const [peek, setPeek] = useState(false)
  const open = wide ? pinned : peek
  const overlay = !wide && peek

  // Au-dessus de l'accueil tant qu'il est ouvert par-dessus, et jusqu'à la
  // fin de sa fermeture : sinon les panneaux le traverseraient en se repliant.
  const [raised, setRaised] = useState(false)
  useEffect(() => { if (overlay) setRaised(true) }, [overlay])

  // Revenir en grand écran referme l'ouverture d'un moment.
  useEffect(() => { if (wide) { setPeek(false); setRaised(false) } }, [wide])
  useEffect(() => {
    if (!overlay) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setPeek(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [overlay])

  const toggle = () => {
    if (!wide) { setPeek((p) => !p); return }
    setPinned((o) => {
      try { localStorage.setItem(OPEN_KEY, o ? '0' : '1') } catch { /* stockage refusé : l'état reste pour la session */ }
      return !o
    })
  }
  const go = (to: string) => {
    setPeek(false)
    navigate(to)
  }

  return (
    // La place que le rail prend dans la page : 64 px, ou 232 px quand il
    // est ouvert sur grand écran. Sur petit écran elle reste à 64 px et le
    // rail ouvert déborde par-dessus l'accueil.
    // Ouvert par-dessus, il passe au-dessus de tout l'accueil : les
    // poignées des panneaux Mods et Packs (`shelf/shared.tsx`, z-50) le
    // traversaient. Fermé, il reste à plat, sous le voile des panneaux.
    <div className={`relative flex-shrink-0 transition-[width] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] ${wide && open ? 'w-[232px]' : 'w-16'} ${overlay || raised ? 'z-[60]' : ''}`}>
      {/* Voile du rail ouvert par-dessus : un clic à côté le referme. */}
      <div
        aria-hidden
        onClick={() => setPeek(false)}
        className={`fixed inset-0 z-30 bg-black/40 transition-opacity duration-200 ${overlay ? 'opacity-100' : 'pointer-events-none opacity-0'}`}
      />
    <nav
      onTransitionEnd={(e) => { if (e.target === e.currentTarget && e.propertyName === 'width' && !overlay) setRaised(false) }}
      className={`absolute inset-y-0 left-0 z-40 flex flex-col overflow-hidden border-r border-line-soft bg-bg-secondary py-3 transition-[width,box-shadow] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] [@media(max-height:720px)]:py-2 ${open ? 'w-[232px]' : 'w-16'} ${overlay ? 'shadow-[12px_0_40px_rgba(0,0,0,0.5)]' : ''}`}
    >
      <RailItem open={open} icon={ICONS[open ? 'collapse' : 'expand']} label={open ? t('home.rail.collapse') : t('home.rail.expand')} onClick={toggle}>
        <span className="font-semibold text-white">YuyuFrame</span>
      </RailItem>

      {/* Le milieu défile quand la hauteur manque, sans barre : un fondu aux
          deux bords dit qu'il continue. Le haut et le pied restent en place. */}
      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto overflow-x-hidden py-1.5 [mask-image:linear-gradient(to_bottom,transparent,black_10px,black_calc(100%-10px),transparent)] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      <Group open={open} label={t('home.rail.groupPlay')} first />
      <RailItem open={open} icon={ICONS.home} label={t('home.nav.home')} active={pathname === '/home'} onClick={() => go('/home')} />
      <RailItem open={open} icon={ICONS.instances} label={t('home.nav.instances')} count={instanceCount} onClick={() => go('/instances')} />
      <RailItem open={open} icon={ICONS.servers} label={t('home.rail.servers')} onClick={() => go('/servers')} />

      <Group open={open} label={t('home.rail.groupProgress')} />
      <RailItem open={open} icon={ICONS.competitive} label={t('home.nav.competitive')} onClick={() => go('/competitive')} />
      <SubLinks open={open} onGo={go} links={[
        { label: t('competitive.nav.history'), to: '/competitive?view=history' },
        { label: t('competitive.nav.leaderboard'), to: '/competitive?view=leaderboard' },
        { label: t('competitive.nav.season'), to: '/competitive?view=season' },
      ]} />
      <RailItem open={open} icon={ICONS.missions} label={t('home.rail.missions')} onClick={() => go('/missions')} />
      <SubLinks open={open} onGo={go} links={[{ label: t('home.rail.pass'), to: '/missions/pass' }]} />

      <Group open={open} label={t('home.rail.groupStyle')} />
      <RailItem open={open} icon={ICONS.skins} label={t('home.rail.skins')} onClick={() => go('/skins')} />
      <SubLinks open={open} onGo={go} links={[
        { label: t('home.rail.skinCatalog'), to: '/skins/catalog' },
        { label: t('home.rail.skinEditor'), to: '/skins/editor' },
      ]} />
      </div>

      {/* Le pied : toujours visible, séparé du milieu par un filet. */}
      <div className="mx-4 mb-1.5 h-px flex-shrink-0 bg-line-soft" />
      <RailItem open={open} icon={ICONS.tools} label={t('home.nav.features')} onClick={() => go('/features')} />
      <RailItem open={open} icon={ICONS.support} label={t('home.nav.support')} badge={supportBadge} onClick={() => go('/support')} />
      {/* Les comptes Minecraft (ajout, changement, hors ligne) : l'écran que
          menait « Compte » dans l'ancienne barre du bas. */}
      <RailItem open={open} icon={ICONS.accounts} label={t('home.nav.account')} active={pathname === '/login'} onClick={() => go('/login')} />
      <RailItem open={open} icon={ICONS.settings} label={t('home.nav.settings')} onClick={() => go('/settings')} />

      {/* L'instance choisie et son état : l'anneau vire au vert pendant une
          partie. Sur l'accueil il double le bouton Jouer ; il prendra son
          sens quand le rail sera sur toutes les pages. */}
      {instanceName && (
        <div className="mx-2.5 mt-2 flex h-12 flex-shrink-0 items-center gap-3 overflow-hidden whitespace-nowrap rounded-[10px] bg-bg-card px-[11px] [@media(max-height:720px)]:mt-1.5 [@media(max-height:720px)]:h-10">
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
    </div>
  )
}

/**
 * Le titre d'un groupe, rail ouvert. Fermé, la même place porte un court
 * filet : avant, le titre devenait transparent et laissait un trou sans
 * raison entre les icônes.
 */
function Group({ open, label, first = false }: { open: boolean; label: string; first?: boolean }) {
  return (
    <div className={`relative mx-5 mb-0.5 h-3.5 flex-shrink-0 whitespace-nowrap ${first ? 'mt-0.5' : 'mt-3 [@media(max-height:720px)]:mt-2'}`}>
      <span className={`absolute inset-0 text-[11px] font-semibold uppercase tracking-wider text-[rgba(255,255,255,0.3)] transition-opacity duration-150 ${open ? 'opacity-100 delay-100' : 'opacity-0'}`}>
        {label}
      </span>
      {!first && (
        <span className={`absolute left-0.5 top-1/2 h-px w-5 bg-line-soft transition-opacity duration-150 ${open ? 'opacity-0' : 'opacity-100'}`} />
      )}
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
      className={`relative mx-2.5 my-px flex h-10 flex-shrink-0 [@media(max-height:720px)]:h-9 items-center gap-3 overflow-hidden whitespace-nowrap rounded-[10px] px-[12px] text-left transition-colors duration-150 ${
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
function SubLinks({ open, links, onGo }: { open: boolean; links: { label: string; to: string }[]; onGo: (to: string) => void }) {
  return (
    <div
      className={`ml-[54px] mr-2.5 flex flex-shrink-0 flex-wrap gap-1 overflow-hidden transition-[max-height,opacity,margin] duration-300 ${open ? 'mb-1.5 max-h-16 opacity-100' : 'mb-0 max-h-0 opacity-0'}`}
      aria-hidden={!open}
    >
      {links.map((l) => (
        <button
          key={l.to}
          tabIndex={open ? 0 : -1}
          onClick={() => onGo(l.to)}
          className="whitespace-nowrap rounded-md bg-[rgba(255,255,255,0.05)] px-2 py-0.5 text-[11.5px] text-[rgba(255,255,255,0.5)] transition-colors duration-150 hover:bg-[rgb(var(--accent)/0.18)] hover:text-[rgb(var(--accent-text))]"
        >
          {l.label}
        </button>
      ))}
    </div>
  )
}
