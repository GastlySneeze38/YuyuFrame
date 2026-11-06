import type { ReactNode } from 'react'

/**
 * Les petites pièces des écrans du téléphone, partagées pour que chaque écran
 * ait les mêmes mesures : texte de 15 px (13 px pour le secondaire), cibles de
 * 44 px au moins, 12 px dans une section et 32 px entre deux.
 */

/** Une carte : fond et bord discrets. */
export const CARD = 'rounded-2xl border border-white/[0.06] bg-white/[0.04]'
/** Une liste groupée : une carte dont les lignes sont séparées d'un trait. */
export const LIST = `flex flex-col divide-y divide-white/[0.06] overflow-hidden ${CARD}`

/** Une section : son titre en petites capitales, puis son contenu. */
export function Section({ title, aside, children }: { title: ReactNode; aside?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between gap-3 px-1">
        <h2 className="min-w-0 truncate text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

/** Un message à la place d'un contenu absent (vide, bientôt, erreur). */
export function Note({ children }: { children: ReactNode }) {
  return <p className={`${CARD} px-4 py-6 text-center text-[15px] leading-relaxed text-white/60`}>{children}</p>
}

/**
 * Un commutateur à cases égales, qui tient toujours dans la largeur : rien à
 * faire défiler de côté pour découvrir la dernière case.
 */
export function Segmented<T extends string | number>({ value, options, onChange, size = 'md' }: {
  value: T
  /** `badge` : un nombre posé en coin, pour ne pas prendre la place du libellé. */
  options: { value: T; label: ReactNode; badge?: number }[]
  onChange: (v: T) => void
  size?: 'md' | 'sm'
}) {
  return (
    <div className="grid gap-1 rounded-2xl bg-white/[0.04] p-1" style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}>
      {options.map((o) => (
        <button
          key={String(o.value)}
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={`relative h-10 min-w-0 rounded-xl px-1 font-semibold transition-colors duration-150 ${size === 'sm' ? 'text-[13px]' : 'text-[14px]'} ${
            value === o.value ? 'bg-[rgb(var(--accent)/0.35)] text-white' : 'text-white/60'
          }`}
        >
          <span className="block truncate">{o.label}</span>
          {o.badge !== undefined && o.badge > 0 && (
            <span className="absolute -right-0.5 -top-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full border border-[rgb(var(--bg-primary))] bg-[#3a3550] px-1 text-[11px] font-bold tabular-nums text-white">
              {o.badge}
            </span>
          )}
        </button>
      ))}
    </div>
  )
}

/** Une ligne à choisir dans une feuille : le libellé, et une coche si elle est choisie. */
export function CheckRow({ label, sub, checked, onClick }: { label: ReactNode; sub?: ReactNode; checked: boolean; onClick: () => void }) {
  return (
    <button onClick={onClick} aria-pressed={checked} className="press flex min-h-[52px] w-full items-center gap-3 rounded-2xl px-3 text-left active:bg-white/[0.04]">
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-[15px] text-white">{label}</span>
        {sub && <span className="truncate text-[13px] text-white/55">{sub}</span>}
      </span>
      {checked && (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.6} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5 shrink-0 text-[rgb(var(--accent-text))]">
          <path d="M5 12.5l4.5 4.5L19 7.5" />
        </svg>
      )}
    </button>
  )
}

/** La flèche des lignes qui mènent ailleurs. */
export function RowChevron() {
  return <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0 text-white/35"><path d="M9.3 6.7 10.7 5.3l6.7 6.7-6.7 6.7-1.4-1.4 5.3-5.3z" /></svg>
}
