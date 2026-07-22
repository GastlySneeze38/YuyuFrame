import type { SavedServer } from '@/api/client'

/** Carte serveur — même gabarit visuel que les cartes promo remplacées sur
 * Home.tsx (rounded-xl px-3 py-2.5, même hiérarchie icône/titre/sous-texte),
 * réutilisée telle quelle dans ServerManageModal pour garder un style
 * identique entre l'accueil et la modal de gestion. */
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
  return (
    <div
      onClick={onClick}
      className={`relative flex flex-col gap-1.5 rounded-xl px-3 py-2.5 cursor-pointer transition-all duration-150 bg-[rgba(255,255,255,0.02)] border border-[rgba(255,255,255,0.05)] hover:bg-[rgba(75,63,207,0.06)] hover:border-[rgba(120,100,255,0.25)] ${className}`}
    >
      {onToggleFavorite && (
        <button
          onClick={(e) => { e.stopPropagation(); onToggleFavorite() }}
          title={favorite ? 'Retirer des favoris' : 'Épingler sur l’accueil'}
          className="absolute top-1.5 right-1.5 flex items-center justify-center w-5 h-5 rounded transition-colors hover:bg-[rgba(255,255,255,0.08)]"
        >
          <svg viewBox="0 0 24 24" fill={favorite ? '#facc15' : 'none'} stroke={favorite ? '#facc15' : 'rgba(255,255,255,0.35)'} strokeWidth={1.5} strokeLinejoin="round" width={12} height={12}>
            <path d="M11.48 3.499a.562.562 0 011.04 0l2.125 5.111a.563.563 0 00.475.345l5.518.442c.499.04.701.663.321.988l-4.204 3.602a.563.563 0 00-.182.557l1.285 5.385a.562.562 0 01-.84.61l-4.725-2.885a.563.563 0 00-.586 0L6.982 20.54a.562.562 0 01-.84-.61l1.285-5.386a.562.562 0 00-.182-.557l-4.204-3.602a.563.563 0 01.321-.988l5.518-.442a.563.563 0 00.475-.345L11.48 3.5z" />
          </svg>
        </button>
      )}
      <div className="flex items-center gap-1.5 text-[rgba(255,255,255,0.35)] pr-4">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" width={14} height={14}>
          <rect x="3" y="4" width="18" height="6" rx="1" />
          <rect x="3" y="14" width="18" height="6" rx="1" />
          <circle cx="7" cy="7" r="0.6" fill="currentColor" />
          <circle cx="7" cy="17" r="0.6" fill="currentColor" />
        </svg>
        <span className="text-[10px] font-bold text-[rgba(255,255,255,0.6)] truncate">
          {server.name || 'Serveur'}
        </span>
      </div>
      <p className="text-[9px] text-[rgba(255,255,255,0.28)] truncate m-0">
        {server.ip}
      </p>
    </div>
  )
}
