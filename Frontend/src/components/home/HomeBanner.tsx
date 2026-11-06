import { useEffect, type CSSProperties } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useFleet } from '@/stores/useFleet'
import type { FleetAnnouncement } from '@/api/client'

/**
 * Bannière d'événement du panneau d'accueil, publiée depuis le back-office
 * (Plateforme → flotte, emplacement « bannière du tableau d'accueil »). Le
 * serveur a déjà fait le tri : ciblage par OS, plan et version, dates de
 * début et de fin. Ici on n'affiche que la première encore valable — deux
 * bannières empilées dans un panneau de cette taille ne se liraient pas.
 *
 * Elle descend du haut en dépassant largement sa place avant de se poser :
 * c'est ce dépassement qui la fait remarquer, alors qu'un simple fondu
 * passerait inaperçu derrière le reste de la page.
 *
 * ── Pourquoi en haut à droite ─────────────────────────────────────────────
 * Elle arrivait du bas, posée à `bottom-5`. Or le bas du panneau appartient
 * déjà à la bande du pseudo (`WelcomeSequence`, `inset-x-0 bottom-0`) et au
 * repère de version de l'instance, dans le coin : la bannière se couchait
 * par-dessus le nom du joueur. Le haut à droite, lui, est libre — le titre
 * est à gauche, et le reste est du ciel.
 */

/** La bannière d'accueil du moment, ou rien. */
export function useHomeBanner(): FleetAnnouncement | null {
  const announcements = useFleet((s) => s.config.announcements)
  const devBanner = useFleet((s) => s.devHomeBanner)
  // Essai depuis l'atelier de développement : il passe devant la vraie.
  if (import.meta.env.DEV && devBanner) return devBanner
  return announcements.find((a) => a.placement === 'home' && a.title) ?? null
}

/** Couleurs de la carte, par habillage. Un habillage inconnu (publié par une
 *  version plus récente du back-office) prend celles du ciel de nuit. */
const CARD_STYLES: Record<FleetAnnouncement['theme'], { card: CSSProperties; kicker: CSSProperties; detail: string }> = {
  none: {
    card: { background: 'rgba(14,13,24,0.74)', borderColor: 'rgb(var(--accent-text)/0.38)', boxShadow: '0 12px 34px rgba(0,0,0,0.45)' },
    kicker: { background: 'rgb(var(--accent-text)/0.18)', color: '#c7d2fe' },
    detail: 'rgba(226,232,240,0.55)',
  },
  festive: {
    card: { background: 'rgba(28,10,20,0.72)', borderColor: 'rgba(255,183,77,0.38)', boxShadow: '0 12px 34px rgba(0,0,0,0.45)' },
    kicker: { background: 'rgba(255,183,77,0.18)', color: '#ffd79a' },
    detail: 'rgba(255,231,205,0.62)',
  },
  halloween: {
    card: { background: 'rgba(16,7,4,0.78)', borderColor: 'rgba(255,122,24,0.45)', boxShadow: '0 12px 34px rgba(0,0,0,0.5), 0 0 22px rgba(255,110,20,0.14)' },
    kicker: { background: 'rgba(255,122,24,0.2)', color: '#ffb36b' },
    detail: 'rgba(255,214,170,0.6)',
  },
}

/** Les annonces déjà apparues pendant cette session : en revenant sur
 *  l'accueil (des dizaines de fois par jour), elles sont déjà en place. */
const SEEN = new Set<string>()

/** Courbe de sortie franche, la même que le retour à l'appui (`.press`). */
const EASE_OUT = [0.23, 1, 0.32, 1] as const

export function HomeBanner({ banner, visible }: { banner: FleetAnnouncement | null; visible: boolean }) {
  const look = banner ? CARD_STYLES[banner.theme] ?? CARD_STYLES.none : CARD_STYLES.none
  const id = banner ? String(banner.id) : null
  // Lu au rendu, noté après : `initial` ne compte qu'au montage, donc
  // l'annonce n'entre en mouvement que la première fois qu'on la voit.
  const firstTime = id !== null && !SEEN.has(id)
  useEffect(() => {
    if (id !== null && visible) SEEN.add(id)
  }, [id, visible])
  return (
    <AnimatePresence>
      {banner && visible && (
        <motion.div
          key={banner.id}
          // Première fois : un court glissement, sans rebond ni attente.
          initial={firstTime ? { opacity: 0, x: -8 } : false}
          animate={{ opacity: 1, x: 0, transition: { duration: 0.2, ease: EASE_OUT } }}
          exit={{ opacity: 0, x: -8, transition: { duration: 0.15, ease: EASE_OUT } }}
          // Posée sous le titre, dans son bloc (`Home.tsx`) : le coin en haut
          // à droite est au bouton Amis. La largeur est bornée pour qu'elle
          // ne vienne pas chercher le personnage, au centre, sur une fenêtre
          // étroite.
          style={{ ...look.card, transformOrigin: 'left center' }}
          className="pointer-events-none flex w-fit max-w-[min(34vw,420px)] items-center gap-3 rounded-xl border px-4 py-2.5 backdrop-blur-[8px]"
        >
          {banner.kicker && (
            <span
              className="flex-none rounded-md px-2 py-1 text-[10.5px] font-bold uppercase tracking-[0.12em]"
              style={look.kicker}
            >
              {banner.kicker}
            </span>
          )}
          <div className="flex min-w-0 flex-col gap-0.5">
            <span className="truncate text-[15px] font-bold text-white">{banner.title}</span>
            <span className="truncate text-[12px]" style={{ color: look.detail }}>
              {banner.message}
            </span>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

// Positions figées : des confettis qui changent à chaque rendu clignoteraient
// au moindre changement d'état du panneau.
const CONFETTI = Array.from({ length: 22 }, (_, i) => ({
  left: `${((i * 33.7) % 100).toFixed(2)}%`,
  width: i % 3 === 0 ? 4 : 6,
  height: i % 4 === 0 ? 10 : 6,
  round: i % 5 === 0,
  color: ['#ffd166', '#ff6b8b', '#5ce6a0', '#7cc9ff', '#ffffff'][i % 5],
  duration: 3.4 + (i % 6) * 0.55,
  delay: (i % 11) * 0.42,
}))

/** Confettis du mode festif — purement décoratifs, jamais cliquables. */
export function Confetti() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden">
      <div className="absolute inset-0 bg-[radial-gradient(120%_70%_at_50%_0%,rgba(255,183,77,0.16)_0%,transparent_60%)]" />
      {CONFETTI.map((c, i) => (
        // Chute en CSS (`confetti-fall`, index.css) : 22 boucles infinies en
        // framer-motion, c'était 22 calculs JavaScript à chaque image.
        <div
          key={i}
          className="absolute top-0"
          style={{
            left: c.left,
            width: c.width,
            height: c.height,
            borderRadius: c.round ? '50%' : 1,
            background: c.color,
            animation: `confetti-fall ${c.duration}s linear ${c.delay}s infinite backwards`,
          }}
        />
      ))}
    </div>
  )
}
