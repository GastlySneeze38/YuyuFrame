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
 * Elle monte du bas en dépassant largement sa place avant de se poser : c'est
 * ce dépassement qui la fait remarquer, alors qu'un simple fondu passerait
 * inaperçu derrière le reste de la page.
 */

/** La bannière d'accueil du moment, ou rien. */
export function useHomeBanner(): FleetAnnouncement | null {
  const announcements = useFleet((s) => s.config.announcements)
  return announcements.find((a) => a.placement === 'home' && a.title) ?? null
}

export function HomeBanner({ banner, visible }: { banner: FleetAnnouncement | null; visible: boolean }) {
  return (
    <AnimatePresence>
      {banner && visible && (
        <motion.div
          key={banner.id}
          initial={{ opacity: 0, y: 150, scale: 0.6 }}
          animate={{
            opacity: 1,
            y: 0,
            scale: 1,
            transition: {
              // Un ressort mou : la bannière dépasse sa place puis revient,
              // au lieu de s'arrêter net.
              type: 'spring',
              stiffness: 120,
              damping: 11,
              mass: 1.1,
              delay: 0.35,
              opacity: { duration: 0.35, delay: 0.35 },
            },
          }}
          exit={{ opacity: 0, y: 120, scale: 0.82, transition: { duration: 0.42, ease: [0.4, 0, 1, 1] } }}
          className="pointer-events-none absolute bottom-5 left-1/2 flex max-w-[min(92%,560px)] -translate-x-1/2 items-center gap-3 rounded-xl border px-5 py-3 backdrop-blur-[8px]"
          style={
            banner.theme === 'festive'
              ? { background: 'rgba(28,10,20,0.72)', borderColor: 'rgba(255,183,77,0.38)', boxShadow: '0 12px 34px rgba(0,0,0,0.45)' }
              : { background: 'rgba(14,13,24,0.74)', borderColor: 'rgba(129,140,248,0.38)', boxShadow: '0 12px 34px rgba(0,0,0,0.45)' }
          }
        >
          {banner.kicker && (
            <span
              className="flex-none rounded-md px-2 py-1 text-[10.5px] font-bold uppercase tracking-[0.12em]"
              style={
                banner.theme === 'festive'
                  ? { background: 'rgba(255,183,77,0.18)', color: '#ffd79a' }
                  : { background: 'rgba(129,140,248,0.18)', color: '#c7d2fe' }
              }
            >
              {banner.kicker}
            </span>
          )}
          <div className="flex min-w-0 flex-col gap-0.5">
            <span className="truncate text-[15px] font-bold text-white">{banner.title}</span>
            <span
              className="truncate text-[12px]"
              style={{ color: banner.theme === 'festive' ? 'rgba(255,231,205,0.62)' : 'rgba(226,232,240,0.55)' }}
            >
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
        <motion.div
          key={i}
          initial={{ y: -40, rotate: 0, opacity: 0 }}
          animate={{ y: 520, rotate: 560, opacity: [0, 0.9, 0.9] }}
          transition={{ duration: c.duration, delay: c.delay, repeat: Infinity, ease: 'linear' }}
          className="absolute top-0"
          style={{
            left: c.left,
            width: c.width,
            height: c.height,
            borderRadius: c.round ? '50%' : 1,
            background: c.color,
            willChange: 'transform',
          }}
        />
      ))}
    </div>
  )
}
