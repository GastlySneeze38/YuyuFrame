import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useLocation, useNavigate } from 'react-router-dom'
import { useStore } from '@/stores/useStore'
import { useSupportWatch } from '@/stores/useSupportWatch'
import { useCrashWatch } from '@/stores/useCrashWatch'
import { SkinFace } from '@/components/ui/SkinFace'
import { useT } from '@/i18n'
import { useNavDrawer } from '@/mobile/navDrawer'
import { MOBILE_TABS, NAV_ICONS } from '@/mobile/MobileNav'
import { bindTouchDrag, isTouchControl, rubber } from './gesture'
import { Slide, remaining } from './slide'

/** Distance de glissé vers la gauche, ou vitesse, au-delà de laquelle le menu
 *  se ferme : un coup de doigt rapide suffit, même court. */
const CLOSE_OFFSET = 80
const CLOSE_VELOCITY = 500
/** Entrée 320 ms, sortie 200 ms — fermer est une réponse, plus vive. */
const OPEN_MS = 320
const CLOSE_MS = 200
const SETTLE_MS = 240

/** Les écrans racines du menu : on y arrive par le menu, leur en-tête porte ☰
 *  au lieu d'un retour, et passer de l'un à l'autre est instantané. */
export const DRAWER_ROOTS = [...MOBILE_TABS, '/features', '/support', '/competitive']

/** Noms des offres payantes : des noms propres, non traduits (comme le
 *  badge du PC). L'offre gratuite, elle, se traduit. */
const PLAN_NAMES: Record<string, string> = { premium: 'Premium', ultimate: 'Ultimate' }

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
 * fermer est une réponse, plus vive qu'ouvrir — joués sur la carte graphique
 * (`Slide`). Le panneau suit le doigt vers la gauche et résiste vers la
 * droite (il ne bute pas net), depuis le panneau comme depuis le fond, et
 * même pendant son ouverture (`DrawerLayer`, 2026-11-09). Le défilement
 * vertical de la liste reste au navigateur : seul un geste horizontal
 * déplace le menu (`gesture.ts`).
 *
 * Pas d'ombre portée sur le panneau (retirée le 2026-11-09) : une grande ombre
 * floue sur un élément qui se déplace est redessinée à chaque image, et sur
 * un téléphone milieu de gamme le retrait sautait des images de 130 à 170 ms,
 * soit presque toute sa durée. Sans elle, 33 ms au pire (mesuré sur un Galaxy
 * A32). Le fond assombri suffit à détacher le panneau.
 */
export function NavDrawer() {
  const t = useT()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const open = useNavDrawer((s) => s.open)
  const setOpen = useNavDrawer((s) => s.setOpen)
  const username = useStore((s) => s.username)
  const uuid = useStore((s) => s.uuid)
  const yuyuSignedIn = useStore((s) => s.yuyuSignedIn)
  const yuyuUsername = useStore((s) => s.yuyuUsername)
  const yuyuPlan = useStore((s) => s.yuyuPlan)
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

  // La navigation attend la fin du retrait du menu (`onExitComplete` plus
  // bas) : lancée en même temps, le montage de la page suivante occupait le
  // fil principal pendant les 200 ms du retrait, et l'animation sautait.
  const pendingGo = useRef<{ path: string; replace: boolean } | null>(null)
  const flushGo = () => {
    const next = pendingGo.current
    if (!next) return
    pendingGo.current = null
    navigate(next.path, { replace: next.replace })
  }

  const go = (path: string) => {
    close()
    if (pathname === path) return
    // Entre écrans racines, on remplace au lieu d'empiler : le bouton retour
    // d'Android ramène à l'accueil, il ne repasse pas par chaque écran visité.
    const fromRoot = DRAWER_ROOTS.includes(pathname)
    pendingGo.current = { path, replace: fromRoot && pathname !== '/home' }
    // Filet : si la fin de l'animation ne venait jamais, on navigue quand même.
    setTimeout(flushGo, 400)
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
          active ? 'bg-white/[0.08] text-white' : e.accent ? 'text-[rgb(var(--accent-text))]' : 'text-white/75'
        }`}
      >
        <svg
          viewBox="0 0 24 24"
          fill="currentColor"
          className={`h-[22px] w-[22px] shrink-0 ${active ? 'text-[rgb(var(--accent-text))]' : e.accent ? 'text-[rgb(var(--accent-text))]' : 'text-white/55'}`}
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

  // Monté tant qu'il est ouvert ou en train de se fermer ; démonté à la fin
  // du retrait, qui lance aussi la navigation en attente.
  const [mounted, setMounted] = useState(open)
  useEffect(() => { if (open) setMounted(true) }, [open])
  if (!mounted) return null

  return createPortal(
    <DrawerLayer open={open} onClose={close} onClosed={() => { setMounted(false); flushGo() }} label={t('home.mobile.menu')}>
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

            {/* Le compte YuyuFrame, posé en bas du menu (hors de la partie qui
                défile) : là où les applis rangent le profil et la connexion.
                Connecté : qui, avec quelle offre, et un appui ouvre sa
                gestion. Pas connecté : se connecter ou créer un compte,
                directement. */}
            <div className="mx-3 mt-2 shrink-0 border-t border-white/[0.08] pt-3">
              {yuyuSignedIn ? (
                <button
                  onClick={() => go('/yuyu-account')}
                  className="press flex min-h-14 w-full items-center gap-3 rounded-2xl px-2.5 py-2 text-left active:bg-white/[0.06]"
                >
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent/25 text-[16px] font-black text-white">
                    {(yuyuUsername ?? '?')[0].toUpperCase()}
                  </span>
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-[15px] font-semibold text-white">{yuyuUsername}</span>
                    {/* « YuyuFrame · offre » et non « Compte YuyuFrame · offre » :
                        sur 300 px de menu, la phrase longue était coupée. */}
                    <span className="truncate text-[13px] text-white/55">
                      YuyuFrame ·{' '}
                      <span className={yuyuPlan === 'free' ? '' : 'font-semibold text-[rgb(var(--accent-text))]'}>
                        {PLAN_NAMES[yuyuPlan] ?? t('account.freePlan')}
                      </span>
                    </span>
                  </span>
                  <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0 text-white/35"><path d="M9.3 6.7 10.7 5.3l6.7 6.7-6.7 6.7-1.4-1.4 5.3-5.3z" /></svg>
                </button>
              ) : (
                <div className="flex flex-col gap-2.5 px-1">
                  <span className="px-1 text-[13px] text-white/55">{t('account.signedOutTitle')}</span>
                  {/* L'un sous l'autre : côte à côte, chacun n'avait que 130 px
                      et « Créer un compte » débordait (plus encore en allemand
                      ou en polonais). */}
                  <div className="flex flex-col gap-2">
                    <button
                      onClick={() => go('/yuyu?mode=login')}
                      className="press h-11 w-full truncate rounded-xl bg-[rgb(var(--accent))] px-3 text-[15px] font-bold text-white"
                    >
                      {t('account.signIn')}
                    </button>
                    <button
                      onClick={() => go('/yuyu?mode=register')}
                      className="press h-11 w-full truncate rounded-xl bg-white/[0.07] px-3 text-[15px] font-semibold text-white/85"
                    >
                      {t('yuyuLogin.createAccount')}
                    </button>
                  </div>
                </div>
              )}
            </div>
    </DrawerLayer>,
    document.body,
  )
}

/**
 * Le fond et le panneau du menu, et ses gestes : on le balaie vers la gauche
 * depuis le panneau (hors de la liste qui défile verticalement, le geste est
 * horizontal) ou depuis le fond assombri, à tout moment — y compris pendant
 * son ouverture, que le geste reprend là où elle en est (`Slide`). Vers la
 * droite, il résiste au lieu de buter. Toucher le fond le ferme aussi.
 */
function DrawerLayer({ open, onClose, onClosed, label, children }: {
  open: boolean
  onClose: () => void
  onClosed: () => void
  label: string
  children: ReactNode
}) {
  const panelRef = useRef<HTMLElement>(null)
  const backdropRef = useRef<HTMLDivElement>(null)
  const slide = useRef<Slide | null>(null)
  const width = useRef(0)
  const latest = useRef({ onClose, onClosed })
  latest.current = { onClose, onClosed }

  useLayoutEffect(() => {
    const panel = panelRef.current!
    width.current = panel.offsetWidth
    slide.current = new Slide(panel, 'x', backdropRef.current, (v) => 1 + v / Math.max(1, width.current))
    slide.current.apply(-width.current)
  }, [])

  useEffect(() => {
    const s = slide.current!
    const w = width.current
    if (open) void s.to(0, OPEN_MS)
    else void s.to(-w, remaining(s.position(), -w, w, CLOSE_MS)).then((done) => { if (done) latest.current.onClosed() })
  }, [open])

  useEffect(() => {
    let base = 0
    const drag = {
      axis: 'x' as const,
      accept: (dx: number, dy: number, target: Element) => Math.abs(dx) > Math.abs(dy) && !isTouchControl(target),
      onStart: () => { base = slide.current!.stop() },
      onMove: (d: number) => {
        const v = base + d
        slide.current!.apply(v > 0 ? rubber(v, 60) : v)
      },
      onEnd: (_d: number, velocity: number) => {
        const s = slide.current!
        const pos = s.position()
        if (velocity < -CLOSE_VELOCITY || (pos < -CLOSE_OFFSET && velocity < CLOSE_VELOCITY / 2)) latest.current.onClose()
        else void s.to(0, SETTLE_MS)
      },
    }
    const offPanel = bindTouchDrag(panelRef.current!, drag)
    const offBackdrop = bindTouchDrag(backdropRef.current!, drag)
    return () => { offPanel(); offBackdrop() }
  }, [])

  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-modal="true" aria-label={label}>
      <div ref={backdropRef} className="absolute inset-0 bg-black/60" style={{ opacity: 0 }} onClick={onClose} />
      <nav
        ref={panelRef}
        className="absolute inset-y-0 left-0 flex w-[min(84vw,320px)] flex-col overflow-hidden rounded-r-[22px] border-r border-white/[0.08] bg-bg-secondary pb-[max(12px,env(safe-area-inset-bottom))] pt-[max(16px,env(safe-area-inset-top))]"
      >
        {children}
      </nav>
    </div>
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
