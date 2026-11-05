import type { InputHTMLAttributes, ReactNode } from 'react'

/**
 * Champ de saisie et carte : les deux briques que tous les écrans recopiaient
 * à la main. Comme `Button`, elles ne parlent qu'en jetons de couleur.
 */

export function Field({
  label,
  hint,
  error,
  className = '',
  ...input
}: {
  label?: string
  hint?: string
  error?: string | null
} & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className={`flex flex-col gap-1.5 ${className}`}>
      {label && <span className="text-[12px] font-medium text-txt-secondary max-sm:text-[13px]">{label}</span>}
      {/* Téléphone : 48 px de haut et 16 px de texte (sous 16 px, iOS
          zoome la page à la saisie). */}
      <input
        {...input}
        className={`h-10 rounded-xl border bg-surface-2 px-3 text-[13px] text-txt-primary outline-none transition-colors duration-150 ease-out placeholder:text-txt-muted focus:border-accent/60 disabled:opacity-50 max-sm:h-12 max-sm:px-4 max-sm:text-[16px] ${
          error ? 'border-danger/60' : 'border-line'
        }`}
      />
      {error ? (
        <span className="text-[11px] text-danger max-sm:text-[13px]">{error}</span>
      ) : (
        hint && <span className="text-[11px] text-txt-muted max-sm:text-[13px] max-sm:text-white/55">{hint}</span>
      )}
    </label>
  )
}

/** Bloc de contenu posé sur le fond (réglages, listes, panneaux). */
export function Card({
  children,
  className = '',
  padded = true,
}: {
  children: ReactNode
  className?: string
  padded?: boolean
}) {
  return (
    <div
      className={`rounded-2xl border border-line bg-surface-1 ${padded ? 'p-4' : ''} ${className}`}
    >
      {children}
    </div>
  )
}

/** En-tête de section, à l'intérieur d'une page. */
export function SectionTitle({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <h2 className="text-[11px] font-semibold uppercase tracking-wider text-txt-muted">{children}</h2>
      {action}
    </div>
  )
}
