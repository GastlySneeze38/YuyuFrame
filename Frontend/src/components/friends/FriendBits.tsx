import { SkinFace } from '@/components/ui/SkinFace'
import { loaderColor } from '@/lib/loader'
import { STATUS_COLORS, liveCustom, relativeTime } from '@/lib/friends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { Activity, FriendPresence, PresenceState } from '@/types/friends'

/** La tête d'un joueur et sa pastille de statut, posée sur le coin. */
export function FriendAvatar({ skin, state, size = 36, ring = '#111116' }: {
  skin: string
  state: PresenceState | 'invisible' | null
  size?: number
  /** Couleur du fond derrière la pastille : elle la détache de la tête. */
  ring?: string
}) {
  const dot = Math.round(size * 0.36)
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <SkinFace dataUri={skin} size={size} className="overflow-hidden rounded-[30%]" />
      {state && (
        <span
          className="absolute rounded-full"
          style={{
            width: dot,
            height: dot,
            right: -dot * 0.18,
            bottom: -dot * 0.18,
            background: STATUS_COLORS[state],
            boxShadow: `0 0 0 ${Math.max(2, Math.round(size * 0.07))}px ${ring}`,
          }}
        />
      )}
    </div>
  )
}

/** « Minecraft 1.21.11 · Fabric », « En duel classé · Masse »… */
export function activityText(activity: Activity, t: ReturnType<typeof useT>): string {
  if (activity.kind === 'launcher') return t('friends.activity.launcher')
  const parts: string[] = []
  if (activity.duel) {
    parts.push(t(activity.duel.ranked ? 'friends.activity.duelRanked' : 'friends.activity.duelCasual'))
    parts.push(t(`friends.activity.modes.${activity.duel.mode}`))
  } else {
    parts.push(t('friends.activity.game', { version: activity.mc_version }))
    if (activity.loader !== 'vanilla') parts.push(activity.loader.charAt(0).toUpperCase() + activity.loader.slice(1))
  }
  return parts.join(' · ')
}

/**
 * La deuxième ligne d'un ami :
 * - en jeu : ce qu'il fait, en vert (le statut personnalisé est dans son profil) ;
 * - en ligne : son statut personnalisé, sinon « Dans le launcher » ;
 * - hors ligne : son statut personnalisé et « vu il y a… », sur la même ligne.
 */
export function PresenceLine({ presence, showServer = false }: { presence: FriendPresence; showServer?: boolean }) {
  const t = useT()
  const language = useStore((s) => s.language)
  const custom = liveCustom(presence)
  if (presence.state === 'offline') {
    const seen = presence.last_seen ? t('friends.lastSeen', { when: relativeTime(presence.last_seen, language) }) : null
    const line = [custom, seen].filter(Boolean).join(' · ')
    return line ? <span className="truncate text-[11.5px] text-txt-muted">{line}</span> : null
  }
  const activity = presence.activity
  if (activity?.kind === 'game') {
    return (
      <span className="flex min-w-0 items-center gap-1 text-[11.5px] font-medium text-[#5fd38d]">
        {activity.duel ? <SwordsIcon className="h-3 w-3 shrink-0" /> : <PadIcon className="h-3 w-3 shrink-0" />}
        <span className="truncate">
          {activityText(activity, t)}
          {showServer && activity.server ? ` · ${activity.server}` : ''}
        </span>
      </span>
    )
  }
  if (custom) return <span className="truncate text-[11.5px] text-txt-secondary">{custom}</span>
  if (activity?.kind === 'launcher') return <span className="truncate text-[11.5px] text-txt-muted">{t('friends.activity.launcher')}</span>
  return null
}

/** La vignette à droite d'un ami en jeu : la couleur de son loader, une
 *  icône de duel s'il se bat. */
export function GameTile({ activity, size = 34 }: { activity: Extract<Activity, { kind: 'game' }>; size?: number }) {
  const color = activity.duel ? (activity.duel.ranked ? '#f5b74a' : '#9aa3b5') : loaderColor(activity.loader)
  return (
    <div
      className="flex shrink-0 items-center justify-center rounded-[10px] border"
      style={{
        width: size,
        height: size,
        background: `linear-gradient(140deg, ${color}38, ${color}12)`,
        borderColor: `${color}55`,
        color,
      }}
    >
      {activity.duel ? <SwordsIcon className="h-[52%] w-[52%]" /> : <CubeIcon className="h-[52%] w-[52%]" />}
    </div>
  )
}

export function PadIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <path d="M7 7h10a5 5 0 014.9 6l-.8 3.6A2.6 2.6 0 0116.5 18l-2-2h-5l-2 2a2.6 2.6 0 01-4.4-1.4L2.1 13A5 5 0 017 7zm1 3v1.5H6.5v1.5H8v1.5h1.5V13H11v-1.5H9.5V10H8zm7.5.5a1 1 0 100 2 1 1 0 000-2zm2 2a1 1 0 100 2 1 1 0 000-2z" />
    </svg>
  )
}

export function SwordsIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="M14.5 17.5L3 6V3h3l11.5 11.5M13 19l6-6M16 16l4 4M19 21l2-2M9.5 6.5L13 3h3v3l-3.5 3.5M5 14l4 4M7 17l-3 3" />
    </svg>
  )
}

export function CubeIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinejoin="round" className={className}>
      <path d="M12 2.8l8 4.4v9.6l-8 4.4-8-4.4V7.2l8-4.4z" />
      <path d="M4 7.2l8 4.4 8-4.4M12 11.6v9.6" />
    </svg>
  )
}

export function FriendsIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <path d="M9 11a4 4 0 100-8 4 4 0 000 8zm7.5 0a3.5 3.5 0 10-1.6-6.6 5.9 5.9 0 01.1 6.2c.5.3 1 .4 1.5.4zM9 13c-3.9 0-7 2-7 4.5V20h14v-2.5C16 15 12.9 13 9 13zm8.6.1c1.4 1 2.4 2.5 2.4 4.4V20h3v-2.5c0-2.2-2.4-4-5.4-4.4z" />
    </svg>
  )
}
