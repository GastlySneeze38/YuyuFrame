import type { ReactNode } from 'react'

// Briques communes aux deux écrans de configuration JVM (liste et éditeur).
// Extraites ici plutôt que dupliquées : elles portent l'espacement et les
// couleurs de ces écrans, et deux copies auraient dérivé au premier ajustement.

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.4)]">{label}</label>
      {children}
      {hint && <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.3)]">{hint}</p>}
    </div>
  )
}

export function Segmented<T extends string>({ options, value, onChange, size = 'md' }: {
  options: { id: T; label: string }[]
  value: T
  onChange: (v: T) => void
  size?: 'sm' | 'md'
}) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {options.map((o) => (
        <button
          key={o.id}
          onClick={() => onChange(o.id)}
          className={`rounded-lg border px-2.5 font-semibold transition-all duration-150 ${
            size === 'sm' ? 'h-[24px] text-[10px]' : 'h-[28px] text-[11px]'
          } ${
            value === o.id
              ? 'border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.35)] text-white'
              : 'border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.35)] text-[rgba(255,255,255,0.45)] hover:border-white/25'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Warn({ children }: { children: ReactNode }) {
  return <p className="text-[11px] leading-relaxed text-[rgba(240,180,90,0.75)]">⚠ {children}</p>
}

export function Card({ title, sub, right, children }: {
  title: string
  sub?: string
  right?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="flex flex-col gap-3 rounded-2xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-[12px] font-black uppercase tracking-[0.12em] text-[rgba(255,255,255,0.75)]">{title}</h2>
          {sub && <p className="mt-1 text-[11px] leading-relaxed text-[rgba(255,255,255,0.35)]">{sub}</p>}
        </div>
        {right}
      </div>
      {children}
    </section>
  )
}
