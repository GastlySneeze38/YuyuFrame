import type { PingState } from '@/lib/serverPing'
import { useT } from '@/i18n'
import { Motd, ServerFavicon, Signal } from './bits'

/**
 * Une ligne de serveur disposée comme dans le menu Multijoueur du jeu :
 * l'icône carrée à gauche, le nom en blanc, le MOTD en couleurs sur deux
 * lignes, et à droite le nombre de joueurs au-dessus des barres de signal.
 * On la retrouve partout où un serveur s'affiche en entier, pour qu'elle
 * paraisse familière dès le premier coup d'œil.
 */
export function ServerRow({
  name,
  address,
  state,
  selected,
  onClick,
  onDoubleClick,
  badge,
  showAddress = false,
}: {
  name: string
  address: string
  state: PingState
  selected?: boolean
  onClick?: () => void
  onDoubleClick?: () => void
  badge?: string
  showAddress?: boolean
}) {
  const t = useT()
  const info = state.status === 'ok' ? state.info : null
  return (
    <div
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      className={`flex select-none items-center gap-3.5 rounded-xl border px-3 py-2.5 transition-colors ${onClick ? 'cursor-pointer' : ''} ${
        selected ? 'border-white/40 bg-white/[0.07]' : 'border-transparent hover:bg-white/[0.035]'
      }`}
    >
      <ServerFavicon ping={info} size={64} />
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-[14px] font-bold text-white">{name || t('servers.defaultName')}</span>
          {badge && <span className="flex-shrink-0 rounded-md bg-accent/30 px-1.5 py-px text-[9.5px] font-bold text-white">{badge}</span>}
          {showAddress && <span className="truncate text-[11px] text-txt-muted">{address}</span>}
        </div>
        {info ? (
          <Motd info={info} />
        ) : (
          <p className={`m-0 text-[12px] ${state.status === 'offline' ? 'text-[#ff5555]' : 'text-txt-muted'}`}>
            {state.status === 'offline' ? t('servers.page.list.offline') : t('servers.page.list.pinging')}
          </p>
        )}
      </div>
      <div className="flex flex-shrink-0 flex-col items-end gap-1.5 self-start pt-0.5">
        <div className="flex items-center gap-2">
          {info && (
            <span className="text-[12px] tabular-nums text-[#aaaaaa]">
              {info.players_online}
              <span className="text-[#555555]">/</span>
              {info.players_max}
            </span>
          )}
          <Signal state={state} />
        </div>
        {info?.version_name && <span className="max-w-[160px] truncate text-[10.5px] text-txt-muted">{info.version_name}</span>}
      </div>
    </div>
  )
}
