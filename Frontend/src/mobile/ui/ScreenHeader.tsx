import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useT } from '@/i18n'

/**
 * L'en-tête d'un écran poussé qui a sa propre route (Statistiques, Skins…) :
 * retour à gauche, titre centré, action facultative à droite — la même grille
 * 48/1fr/48 que `PushedScreen` et le compte YuyuFrame.
 *
 * Le retour remonte l'historique quand il y en a un ; ouvert directement (un
 * lien, un redémarrage), il mène à `fallback` plutôt que de ne rien faire.
 */
export function ScreenHeader({ title, subtitle, right, fallback = '/home' }: {
  title: ReactNode
  subtitle?: ReactNode
  right?: ReactNode
  fallback?: string
}) {
  const t = useT()
  const navigate = useNavigate()
  const back = () => {
    const idx = (window.history.state as { idx?: number } | null)?.idx ?? 0
    if (idx > 0) navigate(-1)
    else navigate(fallback, { replace: true })
  }
  return (
    <header className="grid shrink-0 grid-cols-[48px_1fr_48px] items-center border-b border-white/[0.06] px-1 pb-2 pt-[max(8px,env(safe-area-inset-top))]">
      <button onClick={back} aria-label={t('competitive.back')} className="press flex h-11 w-11 items-center justify-center rounded-full text-[rgb(var(--accent-text))]">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
          <path d="M15 6l-6 6 6 6" />
        </svg>
      </button>
      <div className="flex min-w-0 flex-col items-center px-1 text-center">
        <h1 className="max-w-full truncate text-[17px] font-bold leading-tight text-white">{title}</h1>
        {subtitle && <span className="max-w-full truncate text-[13px] leading-tight text-white/55">{subtitle}</span>}
      </div>
      <div className="flex justify-end">{right}</div>
    </header>
  )
}
