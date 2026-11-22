import type { ReactNode } from 'react'
import { useIsMobile } from '@/mobile/useIsMobile'

/** Les petites pièces des onglets de paramètres d'une instance, partagées par
 *  `InstanceSettingsModal` et les onglets à part (`HorizonGameTab`,
 *  `HorizonFilesTab`). */

export function Separator() {
  return <div className="h-px bg-line" />
}

/** Un champ et son intitulé — la forme de tout ce qui se saisit ici. */
export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="flex items-baseline gap-1.5 text-[10px] font-semibold uppercase tracking-[0.1em] text-txt-muted">
        {label}
        {/* Pas de suffixe d'opacité sur `txt-muted` : le jeton porte déjà son
            alpha, et `/70` donnerait une couleur invalide (voir
            `tailwind.config.js`). */}
        {hint && <span className="text-[10px] normal-case tracking-normal text-txt-muted">{hint}</span>}
      </label>
      {children}
    </div>
  )
}

/** Un réglage et son bouton, alignés — la forme de tout ce qui s'actionne. */
export function Row({ title, desc, action }: { title: string; desc: string; action: ReactNode }) {
  // Sur téléphone, le texte au-dessus et les boutons dessous : côte à côte,
  // l'explication se réduisait à un mot par ligne.
  const mobile = useIsMobile()
  return (
    <div className={mobile ? 'flex flex-col items-start gap-3' : 'flex items-center justify-between gap-4'}>
      <div className="min-w-0">
        <p className={`font-semibold text-txt-primary ${mobile ? 'text-[15px]' : 'text-[13px]'}`}>{title}</p>
        <p className={`mt-0.5 leading-relaxed text-txt-secondary ${mobile ? 'text-[13px]' : 'text-[11.5px]'}`}>{desc}</p>
      </div>
      {action}
    </div>
  )
}

/** Un choix parmi quelques valeurs, en pastilles côte à côte. */
export function Segmented<T extends string | number>({ options, value, onChange }: {
  options: { value: T; label: string }[]
  value: T | null
  onChange: (value: T) => void
}) {
  return (
    <div className="flex w-full flex-wrap gap-2">
      {options.map((o) => (
        <button
          key={String(o.value)}
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={`press h-10 min-w-0 flex-1 rounded-full border px-3 text-[13px] font-semibold transition-colors duration-150 ${
            value === o.value
              ? 'border-[rgb(var(--accent-hover)/0.5)] bg-[rgb(var(--accent)/0.2)] text-white'
              : 'border-white/[0.08] bg-white/[0.03] text-white/65'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}
