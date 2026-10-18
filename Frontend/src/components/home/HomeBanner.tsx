import type { CSSProperties } from 'react'
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
    card: { background: 'rgba(14,13,24,0.74)', borderColor: 'rgba(129,140,248,0.38)', boxShadow: '0 12px 34px rgba(0,0,0,0.45)' },
    kicker: { background: 'rgba(129,140,248,0.18)', color: '#c7d2fe' },
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

export function HomeBanner({ banner, visible }: { banner: FleetAnnouncement | null; visible: boolean }) {
  const look = banner ? CARD_STYLES[banner.theme] ?? CARD_STYLES.none : CARD_STYLES.none
  return (
    <AnimatePresence>
      {banner && visible && (
        <motion.div
          key={banner.id}
          initial={{ opacity: 0, y: -150, scale: 0.6 }}
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
          exit={{ opacity: 0, y: -120, scale: 0.82, transition: { duration: 0.42, ease: [0.4, 0, 1, 1] } }}
          // `z-10` comme le titre : sans lui, elle passerait derrière le rendu
          // 3D, qui couvre tout le panneau.
          // La largeur est bornée pour qu'elle ne vienne jamais toucher le
          // titre, à gauche, même sur une fenêtre étroite.
          className="pointer-events-none absolute right-[clamp(12px,2vw,22px)] top-[clamp(10px,1.8vh,18px)] z-10 flex max-w-[min(58%,420px)] items-center gap-3 rounded-xl border px-5 py-3 backdrop-blur-[8px]"
          style={look.card}
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

// ── Halloween ───────────────────────────────────────────────────────────────
// Même règle que les confettis : tout est figé à l'avance, rien n'est tiré au
// sort pendant le rendu.

/** Silhouette de chauve-souris, ailes ouvertes (viewBox 32 × 14). */
const BAT_PATH =
  'M16 4c-1 0-1.6-1.2-1.6-2 0 0-.8 1.4-1.4 1.6C11 2 8 .5 4 1c1.2 1 1.6 2.4 1.4 3.6C3.8 4 1.8 4.4 0 6c2.4-.2 4 .6 5 2 1-1 2.6-1.2 3.8-.4C9.6 6.4 11 6 12.6 7c.8 1.6 2 3 3.4 4 1.4-1 2.6-2.4 3.4-4 1.6-1 3-.6 3.8.6 1.2-.8 2.8-.6 3.8.4 1-1.4 2.6-2.2 5-2-1.8-1.6-3.8-2-5.4-1.4-.2-1.2.2-2.6 1.4-3.6-4-.5-7 1-8.6 2.6-.6-.2-1.4-1.6-1.4-1.6 0 .8-.6 2-1.6 2z'

const BATS = [
  { top: '22%', size: 26, duration: 17, delay: 0.5, bob: 14, flap: 0.32, reverse: false },
  { top: '12%', size: 16, duration: 23, delay: 4, bob: 8, flap: 0.26, reverse: true },
  { top: '34%', size: 20, duration: 20, delay: 9, bob: 12, flap: 0.3, reverse: false },
  { top: '8%', size: 13, duration: 27, delay: 13, bob: 6, flap: 0.24, reverse: true },
  { top: '27%', size: 15, duration: 25, delay: 18, bob: 10, flap: 0.28, reverse: false },
]

const EMBERS = Array.from({ length: 18 }, (_, i) => ({
  left: `${((i * 29.3 + 7) % 100).toFixed(2)}%`,
  size: i % 4 === 0 ? 3 : 2,
  drift: ((i % 5) - 2) * 9,
  duration: 6 + (i % 5) * 1.3,
  delay: (i % 9) * 0.8,
}))

/** Décor d'Halloween : lune, chauves-souris, braises qui montent de
 *  l'horizon. Purement décoratif, jamais cliquable. */
export function HalloweenSky() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden">
      {/* Lueur de citrouille à l'horizon, derrière la silhouette des arbres. */}
      <motion.div
        animate={{ opacity: [0.65, 1, 0.65] }}
        transition={{ duration: 5, repeat: Infinity, ease: 'easeInOut' }}
        className="absolute inset-0 bg-[radial-gradient(90%_55%_at_50%_100%,rgba(255,110,20,0.38)_0%,rgba(160,40,10,0.16)_45%,transparent_75%)]"
      />

      {/* Lune : un disque pâle et son halo, entre le titre et la bannière. */}
      <div
        className="absolute left-[34%] top-[12%] h-[clamp(46px,9vh,76px)] w-[clamp(46px,9vh,76px)] rounded-full"
        style={{
          background: 'radial-gradient(circle at 38% 36%, #fff3d6 0%, #ffd38a 45%, #e8953f 100%)',
          boxShadow: '0 0 40px 8px rgba(255,180,90,0.35), 0 0 120px 30px rgba(255,120,40,0.18)',
        }}
      >
        {/* Cratères, à peine marqués. */}
        <span className="absolute left-[56%] top-[24%] h-[18%] w-[18%] rounded-full" style={{ background: 'rgba(200,110,40,0.28)' }} />
        <span className="absolute left-[26%] top-[58%] h-[13%] w-[13%] rounded-full" style={{ background: 'rgba(200,110,40,0.22)' }} />
        <span className="absolute left-[62%] top-[62%] h-[9%] w-[9%] rounded-full" style={{ background: 'rgba(200,110,40,0.2)' }} />
      </div>

      {/* Chauves-souris : elles traversent tout le ciel, chacune à son
          rythme, en ondulant et en battant des ailes. */}
      {BATS.map((b, i) => (
        <motion.div
          key={i}
          className="absolute"
          style={{ top: b.top, left: 0, willChange: 'transform' }}
          initial={{ x: b.reverse ? '110vw' : '-10vw' }}
          animate={{ x: b.reverse ? '-10vw' : '110vw', y: [0, -b.bob, 0, b.bob, 0] }}
          transition={{
            x: { duration: b.duration, delay: b.delay, repeat: Infinity, ease: 'linear' },
            y: { duration: b.duration / 4, repeat: Infinity, ease: 'easeInOut' },
          }}
        >
          {/* Le retournement vit sur un parent : Motion réécrit le
              `transform` de l'élément qu'il anime. */}
          <div style={{ transform: b.reverse ? 'scaleX(-1)' : undefined }}>
            <motion.svg
              viewBox="0 0 32 14"
              width={b.size}
              height={(b.size * 14) / 32}
              animate={{ scaleY: [1, 0.3, 1] }}
              transition={{ duration: b.flap, repeat: Infinity, ease: 'easeInOut' }}
            >
              <path d={BAT_PATH} fill="#0a0408" />
            </motion.svg>
          </div>
        </motion.div>
      ))}

      {/* Braises : elles montent depuis le bas et s'éteignent en route. */}
      {EMBERS.map((e, i) => (
        <motion.span
          key={i}
          className="absolute bottom-0 rounded-full"
          style={{ left: e.left, width: e.size, height: e.size, background: '#ffa040', boxShadow: '0 0 6px 1px rgba(255,140,40,0.8)', willChange: 'transform' }}
          initial={{ y: 10, x: 0, opacity: 0 }}
          animate={{ y: -260, x: e.drift, opacity: [0, 0.95, 0.7, 0] }}
          transition={{ duration: e.duration, delay: e.delay, repeat: Infinity, ease: 'easeOut' }}
        />
      ))}
    </div>
  )
}
