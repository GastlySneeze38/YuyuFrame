import { useEffect, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { useLocation, useNavigate } from 'react-router-dom'
import { useStore } from '@/stores/useStore'
import { useSupportWatch } from '@/stores/useSupportWatch'
import { useCrashWatch } from '@/stores/useCrashWatch'
import { SkinFace } from '@/components/ui/SkinFace'
import { useT } from '@/i18n'
import { useNavDrawer } from '@/mobile/navDrawer'
import { MOBILE_TABS, NAV_ICONS } from '@/mobile/MobileNav'

/** Distance de glissé vers la gauche, ou vitesse, au-delà de laquelle le menu
 *  se ferme : un coup de doigt rapide suffit, même court. */
const CLOSE_OFFSET = 80
const CLOSE_VELOCITY = 500

/** Les écrans racines du menu : on y arrive par le menu, leur en-tête porte ☰
 *  au lieu d'un retour, et passer de l'un à l'autre est instantané. */
export const DRAWER_ROOTS = [...MOBILE_TABS, '/features', '/support', '/competitive']

/** Courbe des tiroirs d'iOS (Ionic). */
const DRAWER_EASE = [0.32, 0.72, 0, 1] as const

interface Entry {
  path: string
  label: string
  icon: ReactNode
  /** Plans garde la couleur de l'offre. */
  accent?: boolean
  badge?: number
}

/**
 * Le menu de navigation du téléphone, qui remplace la barre d'onglets du
 * bas : il sort de la gauche sur une partie de l'écran, par le bouton ☰ des
 * en-têtes (`MenuButton`), et se rejette comme les feuilles du bas mais de
 * côté — on le pousse vers la gauche du doigt, un coup rapide suffit. Toucher
 * l'ombre à droite ou choisir une destination le ferme aussi.
 *
 * En haut, le compte (comme l'en-tête d'un menu Android) ; puis les cinq
 * destinations de l'ancienne barre, dans le même ordre ; puis Outils,
 * Support et Compétitif, les entrées de la barre du PC qui n'y étaient pas.
 *
 * Mouvement : entrée 320 ms à la courbe des tiroirs d'iOS, sortie 200 ms —
 * fermer est une réponse, plus vive qu'ouvrir. Le panneau suit le doigt vers
 * la gauche et résiste vers la droite (il ne bute pas net). Le défilement
 * vertical de la liste reste au navigateur (`touch-action: pan-y`) : seul un
 * geste horizontal déplace le menu.
 */
export function NavDrawer() {
  const t = useT()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const open = useNavDrawer((s) => s.open)
  const setOpen = useNavDrawer((s) => s.setOpen)
  const username = useStore((s) => s.username)
  const uuid = useStore((s) => s.uuid)
  const supportUnread = useSupportWatch((s) => s.unread)
  const crashUpdates = useCrashWatch((s) => s.updates)

  const close = () => setOpen(false)

  // Changer d'écran par un autre chemin (lien, retour) referme le menu.
  useEffect(() => { close() }, [pathname])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  const go = (path: string) => {
    close()
    if (pathname === path) return
    // Entre écrans racines, on remplace au lieu d'empiler : le bouton retour
    // d'Android ramène à l'accueil, il ne repasse pas par chaque écran visité.
    const fromRoot = DRAWER_ROOTS.includes(pathname)
    navigate(path, { replace: fromRoot && pathname !== '/home' })
  }

  const main: Entry[] = [
    { path: '/home', label: t('home.nav.home'), icon: NAV_ICONS.home },
    { path: '/instances', label: t('home.nav.instances'), icon: NAV_ICONS.instances },
    { path: '/plans', label: t('home.nav.plans'), icon: NAV_ICONS.plans, accent: true },
    { path: '/settings', label: t('home.nav.settings'), icon: NAV_ICONS.settings },
    { path: '/login', label: t('home.nav.account'), icon: NAV_ICONS.account },
  ]
  const more: Entry[] = [
    { path: '/features', label: t('home.nav.features'), icon: NAV_ICONS.features },
    { path: '/support', label: t('home.nav.support'), icon: NAV_ICONS.support, badge: supportUnread + crashUpdates },
    { path: '/competitive', label: t('home.nav.competitive'), icon: NAV_ICONS.competitive },
  ]

  const row = (e: Entry) => {
    const active = pathname === e.path || pathname.startsWith(`${e.path}/`)
    return (
      <button
        key={e.path}
        onClick={() => go(e.path)}
        aria-current={active ? 'page' : undefined}
        className={`press flex h-[52px] w-full items-center gap-3.5 rounded-2xl px-3.5 text-left transition-colors duration-150 ${
          active ? 'bg-white/[0.08] text-white' : e.accent ? 'text-[#c4b5fd]' : 'text-white/75'
        }`}
      >
        <svg
          viewBox="0 0 24 24"
          fill="currentColor"
          className={`h-[22px] w-[22px] shrink-0 ${active ? 'text-[#a29bfe]' : e.accent ? 'text-[#a78bfa]' : 'text-white/55'}`}
        >
          {e.icon}
        </svg>
        <span className={`min-w-0 flex-1 truncate text-[16px] ${active ? 'font-semibold' : 'font-medium'}`}>{e.label}</span>
        {!!e.badge && (
          <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-danger px-1.5 text-[12px] font-bold tabular-nums text-white">
            {e.badge > 9 ? '9+' : e.badge}
          </span>
        )}
      </button>
    )
  }

  return createPortal(
    <AnimatePresence>
      {open && (
        <div key="drawer" className="fixed inset-0 z-50" role="dialog" aria-modal="true" aria-label={t('home.mobile.menu')}>
          <motion.div
            className="absolute inset-0 bg-black/60"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1, transition: { duration: 0.25 } }}
            exit={{ opacity: 0, transition: { duration: 0.2 } }}
            onClick={close}
          />
          <motion.nav
            className="absolute inset-y-0 left-0 flex w-[min(84vw,320px)] flex-col overflow-hidden rounded-r-[22px] border-r border-white/[0.08] bg-bg-secondary pb-[max(12px,env(safe-area-inset-bottom))] pt-[max(16px,env(safe-area-inset-top))] shadow-[12px_0_40px_rgba(0,0,0,0.5)]"
            style={{ touchAction: 'pan-y' }}
            initial={{ x: '-100%' }}
            animate={{ x: 0, transition: { duration: 0.32, ease: DRAWER_EASE } }}
            exit={{ x: '-100%', transition: { duration: 0.2, ease: DRAWER_EASE } }}
            drag="x"
            dragDirectionLock
            dragConstraints={{ left: 0, right: 0 }}
            // Vers la gauche il suit le doigt ; vers la droite il résiste.
            dragElastic={{ left: 0.6, right: 0.05 }}
            onDragEnd={(_, info) => {
              if (info.offset.x < -CLOSE_OFFSET || info.velocity.x < -CLOSE_VELOCITY) close()
            }}
          >
            {/* Le compte, en tête. */}
            <button
              onClick={() => go('/login')}
              className="press mx-3 mb-3 flex items-center gap-3 rounded-2xl p-2.5 text-left active:bg-white/[0.06]"
            >
              {uuid ? (
                <SkinFace dataUri={`https://mc-heads.net/skin/${uuid}`} size={44} className="rounded-xl" />
              ) : (
                <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-white/[0.06] text-white/55">
                  <svg viewBox="0 0 24 24" fill="currentColor" className="h-6 w-6">{NAV_ICONS.account}</svg>
                </span>
              )}
              <span className="flex min-w-0 flex-col">
                <span className="truncate text-[17px] font-bold text-white">{username ?? t('home.connect')}</span>
                <span className="truncate text-[13px] text-white/55">YuyuFrame</span>
              </span>
            </button>

            <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-3">
              <div className="flex flex-col gap-1">{main.map(row)}</div>
              <div className="mx-3.5 my-3 h-px bg-white/[0.08]" />
              <div className="flex flex-col gap-1">{more.map(row)}</div>
            </div>
          </motion.nav>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  )
}

/**
 * Le bouton ☰ qui ouvre le menu, à poser à gauche des en-têtes d'écran
 * racine (les écrans poussés gardent leur retour « ‹ »). Cible de 44 px.
 */
export function MenuButton({ className = '' }: { className?: string }) {
  const t = useT()
  const setOpen = useNavDrawer((s) => s.setOpen)
  return (
    <button
      onClick={() => setOpen(true)}
      aria-label={t('home.mobile.menu')}
      aria-haspopup="dialog"
      className={`press flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-white/85 active:bg-white/[0.1] ${className}`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" className="h-6 w-6">
        <path d="M4 7h16M4 12h16M4 17h10" />
      </svg>
    </button>
  )
}
