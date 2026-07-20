import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'

// Avant ce fichier, ce bouton retour (icône + hover) était copié-collé à
// l'identique dans 6 pages (Settings, Server, Stats, Instances, Mods, Sync).

export function PageBackButton({ to = '/home' }: { to?: string }) {
  const navigate = useNavigate()
  return (
    <button
      onClick={() => navigate(to)}
      className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg transition-all duration-150"
      style={{ color: 'rgba(255,255,255,0.35)', background: 'rgba(255,255,255,0.04)' }}
      onMouseEnter={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.7)'; e.currentTarget.style.background = 'rgba(255,255,255,0.08)' }}
      onMouseLeave={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.35)'; e.currentTarget.style.background = 'rgba(255,255,255,0.04)' }}
    >
      <svg viewBox="0 0 24 24" fill="currentColor" style={{ width: 15, height: 15 }}>
        <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
      </svg>
    </button>
  )
}

export function PageHeaderSeparator() {
  return <div style={{ width: 1, height: 28, background: 'rgba(255,255,255,0.07)', flexShrink: 0 }} />
}

/** Barre d'en-tête standard (bouton retour + contenu libre) — le contenu après
 * le bouton (séparateur, titre, badges...) varie trop d'une page à l'autre
 * pour être figé dans l'API ; il est passé en children. */
export function PageHeader({ px = 6, backTo, children }: { px?: number; backTo?: string; children: ReactNode }) {
  return (
    <div
      className="flex flex-shrink-0 items-center gap-3"
      style={{ padding: `12px ${px * 4}px`, borderBottom: '1px solid rgba(255,255,255,0.06)' }}
    >
      <PageBackButton to={backTo} />
      {children}
    </div>
  )
}
