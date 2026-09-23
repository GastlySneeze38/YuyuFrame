import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { SavedServer, ServerPingInfo } from '@/api/client'
import { useT } from '@/i18n'

function pingColor(ms: number): string {
  return ms <= 100 ? '#4ade80' : ms <= 300 ? '#facc15' : '#f87171'
}

function pingBars(ms: number): number {
  return ms <= 100 ? 4 : ms <= 200 ? 3 : ms <= 400 ? 2 : 1
}

/** Barres de signal façon client vanilla — vertes/jaunes/rouges selon la
 * latence mesurée par le ping/pong (voir ServerPingInfo.latency_ms), grises
 * tant que le ping n'a pas répondu, rouge plein si le serveur est injoignable. */
function SignalBars({ ping, failed }: { ping: ServerPingInfo | null; failed: boolean }) {
  const t = useT()
  const active = failed ? 0 : ping ? pingBars(ping.latency_ms) : 0
  const color = failed ? '#f87171' : ping ? pingColor(ping.latency_ms) : 'rgba(255,255,255,0.15)'
  return (
    <div
      className="flex items-end gap-[1.5px] h-[9px] flex-shrink-0"
      title={failed ? t('servers.offline') : ping ? `${ping.latency_ms} ms` : t('servers.pingInProgress')}
    >
      {[3, 5, 7, 9].map((h, i) => (
        <span key={i} className="w-[2px] rounded-[1px]" style={{ height: h, background: i < active ? color : 'rgba(255,255,255,0.12)' }} />
      ))}
    </div>
  )
}

/** Carte serveur — même gabarit visuel que les cartes promo remplacées sur
 * Home.tsx (rounded-xl px-3 py-2.5, même hiérarchie icône/titre/sous-texte),
 * réutilisée telle quelle dans ServerManageModal pour garder un style
 * identique entre l'accueil et la modal de gestion. Se ping elle-même au
 * montage (Server List Ping, voir ping_server côté Rust) pour afficher MOTD,
 * joueurs en ligne et favicon — indépendamment des autres cartes affichées
 * en même temps (chacune son propre aller-retour réseau, en parallèle). */
export function ServerCard({
  server,
  onClick,
  favorite,
  onToggleFavorite,
  className = '',
}: {
  server: SavedServer
  onClick: () => void
  favorite?: boolean
  onToggleFavorite?: () => void
  className?: string
}) {
  const t = useT()
  const [ping, setPing] = useState<ServerPingInfo | null>(null)
  const [failed, setFailed] = useState(false)
  const [refreshing, setRefreshing] = useState(false)

  useEffect(() => {
    let cancelled = false
    setPing(null)
    setFailed(false)
    setRefreshing(false)
    api.launch.pingServer(server.ip).then((info) => {
      if (!cancelled) setPing(info)
    }).catch(() => {
      if (!cancelled) setFailed(true)
    })
    return () => { cancelled = true }
  }, [server.ip])

  const refreshPing = () => {
    setRefreshing(true)
    api.launch.pingServer(server.ip).then((info) => {
      setPing(info)
      setFailed(false)
    }).catch(() => {
      setFailed(true)
    }).finally(() => setRefreshing(false))
  }

  const motdLine = ping?.motd.split('\n')[0]?.trim()
  const statusLine = failed
    ? t('servers.offline')
    : ping
      ? `${motdLine || '—'} · ${ping.players_online}/${ping.players_max}`
      : t('servers.connecting')

  return (
    <div
      onClick={onClick}
      // [container-type:inline-size] — la carte se déclare elle-même comme
      // conteneur de container query : tous les `cqw` ci-dessous sont relatifs
      // à SA propre largeur rendue, pas à la fenêtre ni à la ligne entière.
      // Nécessaire car ces cartes sont côte à côte (flex-1) : un `vh` (essayé
      // avant) ne réagit qu'aux changements de HAUTEUR de fenêtre, jamais
      // quand c'est la largeur qui écrase les cartes (3 par ligne).
      className={`group relative flex flex-col overflow-hidden rounded-xl cursor-pointer transition-all duration-150 border [container-type:inline-size] ${
        onToggleFavorite ? 'pb-[24px]' : ''
      } ${
        favorite
          ? 'bg-[rgba(75,63,207,0.12)] border-[rgba(120,100,255,0.4)]'
          : 'bg-[rgba(255,255,255,0.02)] border-[rgba(255,255,255,0.05)] hover:bg-[rgba(75,63,207,0.06)] hover:border-[rgba(120,100,255,0.25)]'
      } ${className}`}
    >
      {/* `flex-1 justify-center` : la carte est étirée sur la hauteur de la
          plus haute de sa rangée (l'offre, sur l'accueil), mais son contenu
          gardait sa hauteur propre et restait collé en haut — d'où le vide
          sous la dernière ligne de texte. Le bloc occupe maintenant toute la
          carte et centre ses lignes dedans : le vide se répartit en haut et
          en bas au lieu de tomber entièrement au pied. */}
      <div className="relative flex flex-1 flex-col justify-center gap-[clamp(3px,1.2cqw,6px)] px-[clamp(6px,4cqw,12px)] py-[clamp(5px,3.5cqw,10px)]">
        <div className="flex items-center gap-[clamp(3px,1.5cqw,6px)] text-[rgba(255,255,255,0.35)]">
          {ping?.favicon ? (
            <img src={ping.favicon} alt="" className="w-[clamp(10px,5.5cqw,14px)] h-[clamp(10px,5.5cqw,14px)] rounded-[2px] [image-rendering:pixelated] flex-shrink-0" />
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" className="w-[clamp(10px,5.5cqw,14px)] h-[clamp(10px,5.5cqw,14px)] flex-shrink-0">
              <rect x="3" y="4" width="18" height="6" rx="1" />
              <rect x="3" y="14" width="18" height="6" rx="1" />
              <circle cx="7" cy="7" r="0.6" fill="currentColor" />
              <circle cx="7" cy="17" r="0.6" fill="currentColor" />
            </svg>
          )}
          <span className="text-[clamp(8px,4cqw,10px)] font-bold text-[rgba(255,255,255,0.6)] truncate flex-1">
            {server.name || t('servers.defaultName')}
          </span>
          <SignalBars ping={ping} failed={failed} />
        </div>
        <button
          onClick={(e) => { e.stopPropagation(); refreshPing() }}
          title={t('servers.refreshPing')}
          className="absolute bottom-1 right-1 flex items-center justify-center w-[clamp(16px,10cqw,28px)] h-[clamp(16px,10cqw,28px)] rounded-lg text-[rgba(255,255,255,0.35)] transition-colors hover:bg-[rgba(255,255,255,0.08)] hover:text-white"
        >
          <svg
            viewBox="0 0 24 24" fill="currentColor" className={`w-[65%] h-[65%] ${refreshing ? 'animate-spin' : ''}`}
          >
            <path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-8 3.58-8 8s3.58 8 8 8a7.994 7.994 0 007.75-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
          </svg>
        </button>
        <p className="text-[clamp(7px,3.5cqw,9px)] text-[rgba(255,255,255,0.32)] truncate m-0 pr-[clamp(16px,10cqw,24px)]">
          {server.ip}
        </p>
        <p className="text-[clamp(7px,3.5cqw,9px)] text-[rgba(255,255,255,0.22)] truncate m-0 pr-[clamp(16px,10cqw,24px)]">
          {statusLine}
        </p>
      </div>

      {/* Bouton d'épinglage — espace fixe (pb-[24px] sur la carte) réservé en
          permanence pour qu'il ne fasse jamais varier la hauteur de la carte
          (seule l'opacité change au survol) : dans une grille, une carte qui
          grandit au survol pousse les cartes des rangées suivantes — voir la
          modal ServerManageModal. Overlay posé par-dessus le contenu écarté
          plus haut pour la même raison (chevauchait favicon/signal/ip). */}
      {onToggleFavorite && (
        <button
          onClick={(e) => { e.stopPropagation(); onToggleFavorite() }}
          className={`absolute inset-x-0 bottom-0 flex h-[24px] items-center justify-center border-t text-[9px] font-semibold transition-opacity duration-150 ${
            favorite ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
          } ${
            favorite
              ? 'bg-[rgba(75,63,207,0.35)] border-[rgba(120,100,255,0.3)] text-white hover:bg-[rgba(75,63,207,0.5)]'
              : 'bg-[rgba(255,255,255,0.05)] border-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.7)] hover:bg-[rgba(255,255,255,0.1)]'
          }`}
        >
          {favorite ? t('servers.removeFromHome') : t('servers.addToHome')}
        </button>
      )}
    </div>
  )
}
