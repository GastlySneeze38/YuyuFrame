import { useRef } from 'react'
import { motion, useScroll, useTransform } from 'framer-motion'
import { bannerBackground } from '@/lib/profileBanners'

/** Hauteur de l'en-tête déplié : le bandeau (96) et ce qui dépasse de la tête. */
const OPEN = 144
/** Bandeau replié. */
const BANNER_OPEN = 96
const BANNER_SHUT = 60
/** Distance de défilement sur laquelle l'en-tête se replie. */
const RANGE = 84

/**
 * La mise en page d'une fiche de profil (celle d'un ami, la mienne) : un
 * en-tête qui reste en haut et se replie quand on fait défiler.
 *
 * Déplié : la bannière, la tête à cheval dessus, la bulle de statut à côté,
 * le pseudo en grand dessous. Au défilement, la bannière rapetisse, la tête
 * rétrécit et remonte dans la bannière, la bulle s'efface dès les premiers
 * pixels, et le pseudo réapparaît à droite de la petite tête — le grand, lui,
 * passe sous l'en-tête avec le reste.
 *
 * L'en-tête est posé PAR-DESSUS la zone qui défile (et non au-dessus d'elle) :
 * sa hauteur change pendant qu'on défile, et s'il poussait le contenu, chaque
 * pixel de défilement redimensionnerait la zone qui défile — elle tremblerait.
 */
export function ProfileLayout({ banner, accent, color, corner, avatar, bubble, name, nameExtra, beforeName, children }: {
  banner: string | null
  accent: string | null
  /** Couleur du bandeau sans bannière (celle du statut). */
  color: string
  /** En haut à droite : le menu « … », le bouton de bannière. */
  corner?: React.ReactNode
  /** La tête, à 72 px. */
  avatar: React.ReactNode
  /** La bulle de statut (ou le champ pour l'écrire). */
  bubble?: React.ReactNode
  name: string
  /** À côté du grand pseudo (mon code ami…). */
  nameExtra?: React.ReactNode
  /** Entre l'en-tête et le pseudo (le choix de bannière, déplié). */
  beforeName?: React.ReactNode
  children: React.ReactNode
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const { scrollY } = useScroll({ container: scroller })
  const bannerHeight = useTransform(scrollY, [0, RANGE], [BANNER_OPEN, BANNER_SHUT])
  const avatarTop = useTransform(scrollY, [0, RANGE], [56, 9])
  const avatarLeft = useTransform(scrollY, [0, RANGE], [16, 12])
  const avatarScale = useTransform(scrollY, [0, RANGE], [1, 0.5])
  const bubbleOpacity = useTransform(scrollY, [0, 28], [1, 0])
  const bubbleEvents = useTransform(scrollY, (y) => (y > 20 ? 'none' : 'auto'))
  const nameOpacity = useTransform(scrollY, [RANGE * 0.55, RANGE], [0, 1])
  const nameX = useTransform(scrollY, [RANGE * 0.55, RANGE], [-10, 0])
  const shadow = useTransform(scrollY, [RANGE - 12, RANGE], [0, 1])

  return (
    <div className="relative h-full">
      <div ref={scroller} className="h-full overflow-y-auto">
        <div className="flex min-h-full flex-col">
          {/* La place de l'en-tête déplié. */}
          <div className="shrink-0" style={{ height: OPEN }} />
          {beforeName}
          <div className="flex items-center gap-2 px-4 pt-1">
            <p className="min-w-0 truncate text-[20px] font-black leading-tight">{name}</p>
            {nameExtra}
          </div>
          {children}
        </div>
      </div>

      {/* L'en-tête, par-dessus. Seuls ses éléments actifs captent la souris. */}
      <div className="pointer-events-none absolute inset-x-0 top-0 z-20" style={{ height: OPEN }}>
        <motion.div className="absolute inset-x-0 top-0 overflow-hidden" style={{ height: bannerHeight, background: bannerBackground(banner, color) }}>
          <div className="absolute inset-0 bg-[radial-gradient(70%_100%_at_85%_0%,rgba(255,255,255,0.14),transparent)]" />
          {/* Replié, un voile sombre pour lire le pseudo sur n'importe quelle bannière. */}
          <motion.div className="absolute inset-0 bg-gradient-to-r from-black/55 via-black/25 to-transparent" style={{ opacity: shadow }} />
          {accent && <div className="absolute inset-x-0 bottom-0 h-[3px]" style={{ background: accent }} />}
        </motion.div>
        <motion.div className="absolute inset-x-0 top-0 shadow-[0_10px_24px_rgba(0,0,0,0.45)]" style={{ height: bannerHeight, opacity: shadow }} />

        {bubble && (
          <motion.div className="absolute left-[100px] right-4 top-[80px] flex" style={{ opacity: bubbleOpacity, pointerEvents: bubbleEvents }}>
            {bubble}
          </motion.div>
        )}

        <motion.div
          className="pointer-events-auto absolute rounded-[30%] bg-[rgb(var(--bg-card))] p-1.5"
          style={{ top: avatarTop, left: avatarLeft, scale: avatarScale, transformOrigin: 'top left' }}
        >
          {avatar}
        </motion.div>

        <motion.div className="absolute left-[64px] right-14 top-0 flex items-center" style={{ height: BANNER_SHUT, opacity: nameOpacity, x: nameX }}>
          <span className="truncate text-[16px] font-black text-white [text-shadow:0_1px_6px_rgba(0,0,0,0.6)]">{name}</span>
        </motion.div>

        {corner && <div className="pointer-events-auto absolute right-2 top-2 z-30">{corner}</div>}
      </div>
    </div>
  )
}
