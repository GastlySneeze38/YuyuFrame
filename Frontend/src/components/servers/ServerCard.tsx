import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { SavedServer, ServerPingInfo } from '@/api/client'

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
  const active = failed ? 0 : ping ? pingBars(ping.latency_ms) : 0
  const color = failed ? '#f87171' : ping ? pingColor(ping.latency_ms) : 'rgba(255,255,255,0.15)'
  return (
    <div
      className="flex items-end gap-[1.5px] h-[9px] flex-shrink-0"
      title={failed ? 'Serveur hors ligne' : ping ? `${ping.latency_ms} ms` : 'Ping en cours...'}
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
    ? 'Serveur hors ligne'
    : ping
      ? `${motdLine || '—'} · ${ping.players_online}/${ping.players_max}`
      : 'Connexion...'

  return (
    <div
      onClick={onClick}
      className={`group flex flex-col overflow-hidden rounded-xl cursor-pointer transition-all duration-150 border ${
        favorite
          ? 'bg-[rgba(75,63,207,0.12)] border-[rgba(120,100,255,0.4)]'
          : 'bg-[rgba(255,255,255,0.02)] border-[rgba(255,255,255,0.05)] hover:bg-[rgba(75,63,207,0.06)] hover:border-[rgba(120,100,255,0.25)]'
      } ${className}`}
    >
      <div className="relative flex flex-col gap-1.5 px-3 py-2.5">
        <div className="flex items-center gap-1.5 text-[rgba(255,255,255,0.35)]">
          {ping?.favicon ? (
            <img src={ping.favicon} alt="" className="w-[14px] h-[14px] rounded-[2px] [image-rendering:pixelated] flex-shrink-0" />
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" width={14} height={14} className="flex-shrink-0">
              <rect x="3" y="4" width="18" height="6" rx="1" />
              <rect x="3" y="14" width="18" height="6" rx="1" />
              <circle cx="7" cy="7" r="0.6" fill="currentColor" />
              <circle cx="7" cy="17" r="0.6" fill="currentColor" />
            </svg>
          )}
          <span className="text-[10px] font-bold text-[rgba(255,255,255,0.6)] truncate flex-1">
            {server.name || 'Serveur'}
          </span>
          <SignalBars ping={ping} failed={failed} />
        </div>
        <button
          onClick={(e) => { e.stopPropagation(); refreshPing() }}
          title="Actualiser le ping"
          className="absolute bottom-1.5 right-1.5 flex items-center justify-center w-7 h-7 rounded-lg text-[rgba(255,255,255,0.35)] transition-colors hover:bg-[rgba(255,255,255,0.08)] hover:text-white"
        >
          <svg
            viewBox="0 0 24 24" fill="currentColor" width={20} height={20}
            className={refreshing ? 'animate-spin' : ''}
          >
            <path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-8 3.58-8 8s3.58 8 8 8a7.994 7.994 0 007.75-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
          </svg>
        </button>
        <p className="text-[9px] text-[rgba(255,255,255,0.32)] truncate m-0 pr-6">
          {server.ip}
        </p>
        <p className="text-[9px] text-[rgba(255,255,255,0.22)] truncate m-0 pr-6">
          {statusLine}
        </p>
      </div>

      {/* Bouton d'épinglage — hors du contenu de la carte : une bande dépliée
          sous la carte au survol (grid 0fr → 1fr) plutôt qu'un bouton posé
          par-dessus le contenu, qui finissait toujours par en chevaucher un
          bout (favicon, signal, ip...) quel que soit son emplacement. */}
      {onToggleFavorite && (
        <div className="grid grid-rows-[0fr] transition-[grid-template-rows] duration-200 ease-out group-hover:grid-rows-[1fr]">
          <div className="overflow-hidden">
            <button
              onClick={(e) => { e.stopPropagation(); onToggleFavorite() }}
              className={`flex w-full items-center justify-center border-t py-1.5 text-[9px] font-semibold transition-colors ${
                favorite
                  ? 'bg-[rgba(75,63,207,0.35)] border-[rgba(120,100,255,0.3)] text-white hover:bg-[rgba(75,63,207,0.5)]'
                  : 'bg-[rgba(255,255,255,0.05)] border-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.7)] hover:bg-[rgba(255,255,255,0.1)]'
              }`}
            >
              {favorite ? 'Retirer de la page d’accueil' : "Mettre sur la page d'accueil"}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
