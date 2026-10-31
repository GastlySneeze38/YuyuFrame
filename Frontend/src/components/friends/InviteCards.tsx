import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { JoinServerModal } from '@/components/servers/JoinServerModal'
import { loaderColor } from '@/lib/loader'
import { skinOf } from '@/lib/friends'
import { SNAP } from '@/lib/motion'
import { isDemo } from '@/stores/useDevFlags'
import { showError } from '@/stores/useErrorToast'
import { INVITE_LIFETIME_MS, useFriends } from '@/stores/useFriends'

/** Secondes avant qu'une carte flottante ne se retire (l'invitation reste dans le panneau). */
const FLOATING_SECONDS = 15
import { useT } from '@/i18n'
import type { ReceivedInvite } from '@/types/friends'
import { FriendAvatar } from './FriendBits'

/**
 * Les invitations d'un ami à le rejoindre sur son serveur (`invited` du flux).
 *
 * Accepter ouvre la fenêtre « Rejoindre un ami » (`JoinServerModal`) : elle
 * propose les instances de la même version et du même loader (ou en crée une),
 * et lance le jeu directement sur le serveur. Refuser prévient l'ami. Fermer la carte
 * flottante ne répond rien : l'invitation reste dans le panneau des amis
 * jusqu'à son expiration (10 minutes). La carte flottante se retire d'elle-même
 * au bout de 15 s (sa barre verte fait compte à rebours, le survol l'arrête),
 * avec le même effet que la croix.
 *
 * En « Ne pas déranger », pas de carte flottante : seulement le panneau.
 */

/** La fenêtre de lancement d'une invitation acceptée, et de quoi l'ouvrir. */
export function useInviteJoin() {
  const [joining, setJoining] = useState<ReceivedInvite | null>(null)
  const dropInvite = useFriends((s) => s.dropInvite)
  const start = (invite: ReceivedInvite) => {
    dropInvite(invite.invite)
    setJoining(invite)
  }
  // Hors de tout panneau animé : un `fixed` à l'intérieur s'y collerait.
  const modal =
    joining &&
    createPortal(
      <JoinServerModal
        request={{ ip: joining.server, mcVersion: joining.mc_version, loader: joining.loader, inviter: joining.username }}
        onClose={() => setJoining(null)}
      />,
      document.body,
    )
  return { start, modal }
}

/** Les invitations expirées quittent le magasin d'elles-mêmes. */
function useExpiry() {
  const invites = useFriends((s) => s.invites)
  const dropInvite = useFriends((s) => s.dropInvite)
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    if (invites.length === 0) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [invites.length])
  useEffect(() => {
    for (const i of invites) if (i.expiresAt <= now) dropInvite(i.invite)
  }, [invites, now, dropInvite])
  return now
}

/** Une invitation : qui, où, et les deux réponses. */
export function InviteCard({ invite, now, onAccept, onClose, autoHide }: {
  invite: ReceivedInvite
  now: number
  onAccept: () => void
  /** La croix de la carte flottante (ignorer sans répondre). */
  onClose?: () => void
  /** Carte flottante : elle se retire seule au bout de ces secondes (appelle
   *  `onClose`), la barre du bas faisant compte à rebours. Le survol l'arrête. */
  autoHide?: number
}) {
  const t = useT()
  const friend = useFriends((s) => s.list?.friends.find((f) => f.id === invite.id))
  const dropInvite = useFriends((s) => s.dropInvite)
  const [busy, setBusy] = useState(false)
  const left = Math.max(0, invite.expiresAt - now)
  const minutes = Math.ceil(left / 60_000)

  async function decline() {
    setBusy(true)
    try {
      if (!isDemo()) await api.friends.declineInvite(invite.invite)
      dropInvite(invite.invite)
    } catch (e) {
      showError(e)
      setBusy(false)
    }
  }

  return (
    <div className="group relative flex flex-col gap-2.5 overflow-hidden rounded-2xl border border-line bg-[#18181f] p-3">
      <div className="flex items-center gap-3">
        <FriendAvatar skin={friend ? skinOf(friend) : 'https://mc-heads.net/skin/MHF_Steve'} state={null} size={38} />
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-[13px] font-bold">{t('friends.invites.from', { name: invite.username })}</span>
          <span className="truncate text-[11.5px] text-txt-secondary" title={invite.server}>
            {invite.server}
          </span>
          <span className="truncate text-[10.5px] text-txt-muted">
            Minecraft {invite.mc_version} · <span style={{ color: loaderColor(invite.loader) }}>{invite.loader}</span> · {t('friends.invites.expires', { minutes })}
          </span>
        </div>
        {onClose && (
          <button onClick={onClose} title={t('friends.invites.later')} aria-label={t('friends.invites.later')} className="-mr-1 self-start rounded-md p-1 text-txt-muted hover:bg-white/[0.06] hover:text-white">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" className="h-3.5 w-3.5">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        )}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <button
          onClick={() => void decline()}
          disabled={busy}
          className="rounded-lg border border-line px-3 py-1.5 text-[12px] font-semibold text-txt-secondary transition-colors hover:border-danger/50 hover:text-danger disabled:opacity-50"
        >
          {t('friends.invites.decline')}
        </button>
        <button
          onClick={onAccept}
          disabled={busy}
          className="rounded-lg bg-[#3ba55d] px-3 py-1.5 text-[12px] font-bold text-white shadow-[0_6px_18px_rgba(59,165,93,0.3)] transition-colors hover:bg-[#349852] disabled:opacity-50"
        >
          {t('friends.invites.accept')}
        </button>
      </div>
      {/* Flottante : le temps avant que la carte ne se retire (arrêté au
          survol). Dans le panneau : le temps avant que l'invitation n'expire. */}
      <span className="absolute inset-x-0 bottom-0 h-[3px] bg-white/[0.06]">
        {autoHide ? (
          <span
            className="block h-full origin-left bg-[#3ba55d] group-hover:[animation-play-state:paused]"
            style={{ animation: `inviteCardTimer ${autoHide}s linear forwards` }}
            onAnimationEnd={onClose}
          />
        ) : (
          <span className="block h-full bg-[#3ba55d]/70 transition-[width] duration-1000 ease-linear" style={{ width: `${(left / INVITE_LIFETIME_MS) * 100}%` }} />
        )}
      </span>
    </div>
  )
}

/** Les invitations dans le panneau des amis (onglet des demandes). */
export function InviteList() {
  const invites = useFriends((s) => s.invites)
  const now = useExpiry()
  const { start, modal } = useInviteJoin()
  return (
    <>
      <div className="flex flex-col gap-2 px-3">
        {invites.map((i) => (
          <InviteCard key={i.invite} invite={i} now={now} onAccept={() => start(i)} />
        ))}
      </div>
      {modal}
    </>
  )
}

/** Les cartes flottantes, en bas à droite de la fenêtre, montées une fois dans `App`. */
export function InviteStack() {
  const t = useT()
  const invites = useFriends((s) => s.invites)
  const dnd = useFriends((s) => s.list?.me.status === 'dnd')
  const now = useExpiry()
  const { start, modal } = useInviteJoin()
  // Les cartes fermées d'une croix : elles restent dans le panneau des amis.
  const [hidden, setHidden] = useState<Set<string>>(new Set())
  const shown = dnd ? [] : invites.filter((i) => !hidden.has(i.invite)).slice(0, 3)

  return (
    <>
      <div className="pointer-events-none fixed bottom-4 right-4 z-[90] flex w-[min(340px,calc(100vw-32px))] flex-col-reverse gap-2" aria-live="polite" aria-label={t('friends.invites.title')}>
        <AnimatePresence initial={false}>
          {shown.map((i) => (
            <motion.div
              key={i.invite}
              layout
              initial={{ opacity: 0, x: 40, scale: 0.97 }}
              animate={{ opacity: 1, x: 0, scale: 1 }}
              exit={{ opacity: 0, x: 40, scale: 0.97 }}
              transition={SNAP}
              className="pointer-events-auto rounded-2xl shadow-[0_18px_48px_rgba(0,0,0,0.55)]"
            >
              <InviteCard
                invite={i}
                now={now}
                onAccept={() => start(i)}
                onClose={() => setHidden((s) => new Set(s).add(i.invite))}
                autoHide={FLOATING_SECONDS}
              />
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
      {modal}
    </>
  )
}
