import { useEffect, useState } from 'react'
import { api, type SavedServer, type ServerPingInfo } from '@/api/client'
import { useT } from '@/i18n'

/**
 * L'offre Pro, en pleine largeur. Même contenu que `ProCard` du PC (titre,
 * accroche, « Voir les plans -50 % »), mais lisible au téléphone : celle du PC
 * est taillée pour un tiers de rangée, avec des textes de 8 à 11 px.
 * Toute la carte est la cible ; elle ne s'anime pas en boucle.
 */
export function MobileProCard({ onOpen }: { onOpen: () => void }) {
  const t = useT()
  return (
    <button
      onClick={onOpen}
      className="press flex w-full items-center gap-3.5 rounded-2xl border border-[rgb(var(--accent-hover)/0.3)] bg-[rgb(var(--accent)/0.1)] p-4 text-left"
    >
      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-[rgb(var(--accent-hover)/0.18)] text-[rgb(var(--accent-text))]">
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-[22px] w-[22px]">
          <path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" />
        </svg>
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-[16px] font-bold text-white">
          YuyuFrame <span className="text-[rgb(var(--accent-text))]">Pro</span>
        </span>
        <span className="line-clamp-2 text-[13px] leading-snug text-white/60">{t('home.proTagline')}</span>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1">
        <span className="rounded-full bg-[rgb(var(--accent-text)/0.18)] px-2 py-0.5 text-[12px] font-bold text-[rgb(var(--accent-text))]">-50%</span>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 text-white/50">
          <path d="M9 6l6 6-6 6" />
        </svg>
      </span>
    </button>
  )
}

function pingColor(ms: number): string {
  return ms <= 100 ? '#4ade80' : ms <= 300 ? '#facc15' : '#f87171'
}

function pingBars(ms: number): number {
  return ms <= 100 ? 4 : ms <= 200 ? 3 : ms <= 400 ? 2 : 1
}

/**
 * Un serveur, en ligne pleine largeur : icône du serveur, nom, adresse, état
 * (joueurs en ligne ou hors ligne) et barres de signal. Se pingue lui-même,
 * comme `ServerCard` du PC. Toucher la carte rejoint le serveur.
 */
export function MobileServerCard({ server, badge, onClick }: {
  server: SavedServer
  badge?: string
  onClick: () => void
}) {
  const t = useT()
  const [ping, setPing] = useState<ServerPingInfo | null>(null)
  const [failed, setFailed] = useState(false)
  const [iconShown, setIconShown] = useState(false)

  useEffect(() => {
    let alive = true
    setPing(null)
    setIconShown(false)
    setFailed(false)
    api.launch.pingServer(server.ip)
      .then((info) => alive && setPing(info))
      .catch(() => alive && setFailed(true))
    return () => { alive = false }
  }, [server.ip])

  const status = failed
    ? t('servers.offline')
    : ping
      ? `${ping.players_online} / ${ping.players_max}`
      : t('servers.connecting')
  const bars = failed ? 0 : ping ? pingBars(ping.latency_ms) : 0
  const color = failed ? '#f87171' : ping ? pingColor(ping.latency_ms) : 'rgba(255,255,255,0.2)'

  return (
    <button
      onClick={onClick}
      className={`press flex w-full items-center gap-3.5 rounded-2xl border border-white/[0.08] bg-white/[0.04] p-4 text-left ${failed ? 'opacity-70' : ''}`}
    >
      <span className="flex h-11 w-11 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-white/[0.06] text-white/50">
        {ping?.favicon ? (
          // L'icône du serveur arrive après le ping : un fondu court au lieu
          // d'un remplacement sec de l'icône par défaut.
          <img
            src={ping.favicon}
            alt=""
            onLoad={() => setIconShown(true)}
            className={`h-full w-full transition-opacity duration-150 ease-out [image-rendering:pixelated] ${iconShown ? 'opacity-100' : 'opacity-0'}`}
          />
        ) : (
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-[22px] w-[22px]">
            <rect x="3" y="4" width="18" height="6" rx="1" />
            <rect x="3" y="14" width="18" height="6" rx="1" />
          </svg>
        )}
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="flex min-w-0 items-center gap-2">
          <span className="truncate text-[15px] font-semibold text-white">{server.name || t('servers.defaultName')}</span>
          {badge && (
            <span className="shrink-0 rounded-md bg-[rgb(var(--accent)/0.35)] px-1.5 py-px text-[11px] font-semibold text-white/85">{badge}</span>
          )}
        </span>
        <span className="truncate text-[13px] text-white/55">{server.ip}</span>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1.5">
        <span className="flex h-3 items-end gap-[2px]" aria-hidden>
          {/* À l'arrivée du ping, les barres s'allument de gauche à droite
              (30 ms d'écart) : on lit la force du signal se remplir. */}
          {[4, 6, 9, 12].map((h, i) => (
            <span
              key={h}
              className="w-[3px] rounded-[1px] transition-[background-color] duration-150 ease-out"
              style={{ height: h, background: i < bars ? color : 'rgba(255,255,255,0.14)', transitionDelay: `${i * 30}ms` }}
            />
          ))}
        </span>
        <span className={`text-[12px] font-medium tabular-nums ${failed ? 'text-[#fca5a5]' : 'text-white/55'}`}>{status}</span>
      </span>
    </button>
  )
}
