import { useState } from 'react'
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
 */
export function FriendsButton() {
  const t = useT()
  const [open, setOpen] = useState(false)
  const online = useFriends((s) => s.list?.friends.filter((f) => isOnline(f.presence)).length ?? 0)
  // Les demandes reçues en attente de réponse : la pastille reste tant qu'on n'y a pas répondu.
  const waiting = useFriends((s) => s.list?.incoming.length ?? 0)

  return (
    <>
      <motion.button
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
        transition={SNAP}
        onClick={() => setOpen(true)}
        title={t('friends.title')}
        aria-label={t('friends.title')}
        className="relative flex h-10 items-center gap-2 rounded-xl border border-white/10 bg-black/35 px-3 text-white/85 backdrop-blur-[8px] transition-colors hover:border-white/25 hover:text-white"
      >
        <FriendsIcon className="h-[18px] w-[18px]" />
        <span className="flex items-center gap-1.5 text-[13px] font-bold tabular-nums">
          <span className="h-2 w-2 rounded-full bg-[#3ba55d] shadow-[0_0_8px_rgba(59,165,93,0.8)]" />
          {online}
        </span>
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
