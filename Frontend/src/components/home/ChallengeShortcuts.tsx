import { useState } from 'react'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { FriendsButton } from '@/components/friends/FriendsButton'
import { SNAP } from '@/lib/motion'
import { usePass } from '@/lib/usePass'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import { MissionsPanel } from './MissionsPanel'

/** Une case du carré : même verre que le bouton Amis, toutes de même taille. */
export const SHORTCUT_TILE =
  'relative flex h-10 w-full min-w-0 items-center gap-2 rounded-xl border border-white/10 bg-black/35 px-3 text-white/85 backdrop-blur-[8px] transition-colors hover:border-white/25 hover:text-white'

const HOVER = { whileHover: { scale: 1.03 }, whileTap: { scale: 0.97 }, transition: SNAP }

/**
 * Les raccourcis du coin haut droit de la bannière d'accueil, en carré de
 * deux sur deux :
 *
 *   Missions │ Amis
 *   Passe    │ Saison
 *
 * - Missions, avec le nombre de missions encore à faire : ouvre le tiroir des
 *   missions (`MissionsPanel`), comme Amis ouvre le sien ;
 * - Amis : en ligne, demandes en attente, le panneau façon Discord ;
 * - Passe : le palier actuel et la barre vers le suivant (seulement quand un
 *   passe existe — sinon Saison prend sa place et le carré n'a que trois cases) ;
 * - Saison : l'écran Saison du Compétitif.
 */
export function HomeShortcuts() {
  const t = useT()
  const navigate = useNavigate()
  const { challenges, pass, meta, reroll } = usePass()
  const owned = useStore((s) => s.yuyuPlan) !== 'free'
  const todo = challenges?.filter((c) => c.progress < c.goal).length ?? 0
  // Les missions s'ouvrent en tiroir : on les consulte sans quitter l'accueil.
  const [missionsOpen, setMissionsOpen] = useState(false)

  return (
    <div className="grid w-[clamp(250px,24vw,300px)] grid-cols-2 gap-2">
      <MissionsPanel open={missionsOpen} onClose={() => setMissionsOpen(false)} challenges={challenges} pass={pass} meta={meta} onReroll={reroll} />

      <motion.button
        {...HOVER}
        onClick={() => setMissionsOpen(true)}
        title={t('home.shortcuts.missionsHint')}
        aria-label={t('home.shortcuts.missions')}
        className={SHORTCUT_TILE}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-[17px] w-[17px] shrink-0">
          <path d="M4 21V4M4 4h12l-2 4 2 4H4" />
        </svg>
        <span className="min-w-0 flex-1 truncate text-left text-[12.5px] font-bold">{t('home.shortcuts.missions')}</span>
        {todo > 0 && (
          <span className="flex h-[18px] min-w-[18px] shrink-0 items-center justify-center rounded-full bg-accent px-1 text-[10.5px] font-black tabular-nums text-white">
            {todo}
          </span>
        )}
      </motion.button>

      <FriendsButton className={SHORTCUT_TILE} />

      {pass && (
        <motion.button
          {...HOVER}
          onClick={() => navigate('/missions/pass')}
          title={t('home.shortcuts.passHint', { tier: pass.tier, total: pass.tiers })}
          aria-label={t('home.shortcuts.pass')}
          className={SHORTCUT_TILE}
        >
          {/* Le palier actuel, dans un losange comme les paliers du passe. */}
          <span className="relative flex h-6 w-6 shrink-0 items-center justify-center">
            <span className="absolute inset-[3px] rotate-45 rounded-[3px] border-2 border-accent bg-accent/30" />
            <span className="relative text-[10px] font-black tabular-nums">{pass.tier}</span>
          </span>
          <span className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="flex items-center justify-between gap-1 text-[10px] font-bold leading-none">
              <span className="truncate uppercase tracking-[0.04em] text-white/70">{t('home.shortcuts.pass')}</span>
              {!owned && (
                <svg viewBox="0 0 24 24" className="h-2.5 w-2.5 shrink-0 text-warning" fill="currentColor">
                  <path d="M12 2a5 5 0 00-5 5v3H6a2 2 0 00-2 2v8a2 2 0 002 2h12a2 2 0 002-2v-8a2 2 0 00-2-2h-1V7a5 5 0 00-5-5zm-3 8V7a3 3 0 016 0v3z" />
                </svg>
              )}
            </span>
            <span className="h-1.5 overflow-hidden rounded-full bg-white/[0.12]">
              <motion.span
                className="block h-full rounded-full bg-white"
                initial={{ width: 0 }}
                animate={{ width: `${pass.tier >= pass.tiers ? 100 : (pass.xpInTier / pass.xpPerTier) * 100}%` }}
                transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1], delay: 0.3 }}
              />
            </span>
          </span>
        </motion.button>
      )}

      <motion.button
        {...HOVER}
        onClick={() => navigate('/competitive?view=season')}
        title={t('home.shortcuts.seasonHint')}
        aria-label={t('home.shortcuts.season')}
        className={SHORTCUT_TILE}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinejoin="round" className="h-[17px] w-[17px] shrink-0">
          <path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" />
        </svg>
        <span className="min-w-0 flex-1 truncate text-left text-[12.5px] font-bold">{t('home.shortcuts.season')}</span>
      </motion.button>
    </div>
  )
}
