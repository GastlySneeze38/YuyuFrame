import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { isOnline } from '@/lib/friends'
import { SNAP } from '@/lib/motion'
import { useFriends } from '@/stores/useFriends'
import { useT } from '@/i18n'
import { FriendsIcon } from './FriendBits'
import { FriendsPanel } from './FriendsPanel'

/**
 * Le bouton Amis, en haut à droite de la bannière de l'accueil : combien sont
 * en ligne, une pastille quand des demandes attendent. Il ouvre le panneau.
 *
 * `className` : sa forme, quand il fait partie du carré de raccourcis
 * (`SHORTCUT_TILE` de `home/ChallengeShortcuts.tsx`) ; sinon, son bouton seul.
 * `compact` : la forme de l'en-tête des pages (`PageHeader`), à la place de
 * l'ancien bouton de compte — le compte est désormais dans le panneau.
 */
export function FriendsButton({ className, compact = false, large = false }: { className?: string; compact?: boolean; large?: boolean }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  // Un lien d'ami cliqué (`yuyuframe://friend/…`) ouvre le panneau sur l'ajout.
  const addSeq = useFriends((s) => s.addRequest?.seq ?? 0)
  useEffect(() => {
    if (addSeq > 0 && useFriends.getState().addRequest?.code) setOpen(true)
  }, [addSeq])
  const online = useFriends((s) => s.list?.friends.filter((f) => isOnline(f.presence)).length ?? 0)
  // Les demandes et invitations reçues en attente de réponse : la pastille
  // reste tant qu'on n'y a pas répondu (une invitation expire en 10 min).
  const waiting = useFriends((s) => (s.list?.incoming.length ?? 0) + s.invites.length)

  return (
    <>
      <motion.button
        // Au téléphone (`large`), l'appui vient de la classe `.press` en CSS.
        whileHover={large ? undefined : { scale: className ? 1.03 : 1.05 }}
        whileTap={large ? undefined : { scale: className ? 0.97 : 0.95 }}
        transition={SNAP}
        onClick={() => setOpen(true)}
        title={t('friends.title')}
        aria-label={t('friends.title')}
        className={
          className ??
          (compact
            ? 'relative flex h-10 items-center gap-2 rounded-xl border border-white/[0.08] bg-white/[0.03] px-3.5 text-white/75 transition-colors hover:border-white/[0.18] hover:bg-white/[0.05] hover:text-white'
            : 'relative flex h-10 items-center gap-2 rounded-xl border border-white/10 bg-black/35 px-3 text-white/85 backdrop-blur-[8px] transition-colors hover:border-white/25 hover:text-white')
        }
      >
        {/* La pastille verte au coin de l'icône, seulement si quelqu'un est là. */}
        <span className="relative flex">
          <FriendsIcon className={compact || large ? 'h-5 w-5' : 'h-[18px] w-[18px]'} />
          {online > 0 && (
            <span className="absolute -bottom-0.5 -right-1 h-2.5 w-2.5 rounded-full bg-[#3ba55d] shadow-[0_0_0_2px_rgba(14,14,20,0.95),0_0_8px_rgba(59,165,93,0.8)]" />
          )}
        </span>
        {/* Dans le carré de raccourcis, il porte son nom comme les autres cases. */}
        {className && <span className={`min-w-0 flex-1 truncate text-left font-bold ${large ? 'text-[15px]' : 'text-[12.5px]'}`}>{t('friends.title')}</span>}
        <span className={`shrink-0 font-bold tabular-nums ${compact || large ? 'text-[14px]' : 'text-[13px]'}`}>{online}</span>
        {waiting > 0 && (
          <span className="absolute -right-1.5 -top-1.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-danger px-1 text-[10.5px] font-black text-white shadow-[0_0_0_3px_rgba(10,10,14,0.9)]">
            {waiting}
          </span>
        )}
      </motion.button>
      <FriendsPanel open={open} onClose={() => setOpen(false)} />
    </>
  )
}
