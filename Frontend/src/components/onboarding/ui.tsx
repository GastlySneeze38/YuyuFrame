import type { ReactNode } from 'react'
import { motion, type Variants } from 'framer-motion'

/** La courbe de tout l'écran : arrive vite, se pose longtemps. */
export const EASE = [0.16, 1, 0.3, 1] as const

/**
 * Le contenu d'une étape qui arrive : chaque bloc monte d'un cran, 45 ms
 * après le précédent. Court exprès — une cascade lente fait attendre.
 */
const list: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.045, delayChildren: 0.06 } },
}
const item: Variants = {
  hidden: { opacity: 0, y: 14, filter: 'blur(4px)' },
  show: { opacity: 1, y: 0, filter: 'blur(0px)', transition: { duration: 0.55, ease: EASE } },
}

export function Stagger({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <motion.div variants={list} initial="hidden" animate="show" className={className}>
      {children}
    </motion.div>
  )
}

export function Item({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <motion.div variants={item} className={className}>
      {children}
    </motion.div>
  )
}

/** Champ de saisie : 16 px sur téléphone, sans quoi iOS agrandit la page. */
export function Field({
  label, value, onChange, type = 'text', placeholder, autoFocus, inputMode, maxLength, onEnter,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  type?: string
  placeholder?: string
  autoFocus?: boolean
  inputMode?: 'text' | 'numeric' | 'email'
  maxLength?: number
  onEnter?: () => void
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-white/45 max-sm:text-[12px]">{label}</span>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && onEnter) { e.preventDefault(); onEnter() } }}
        placeholder={placeholder}
        autoFocus={autoFocus}
        inputMode={inputMode}
        maxLength={maxLength}
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        className="h-11 w-full rounded-xl border border-white/10 bg-black/35 px-3.5 text-[14px] text-white outline-none transition-[border-color,background-color] duration-200 placeholder:text-white/25 focus:border-[rgb(var(--accent)/0.7)] focus:bg-black/45 max-sm:h-12 max-sm:text-[16px]"
      />
    </label>
  )
}

/** Le bouton qui fait avancer. Il s'enfonce à l'appui. */
export function Primary({ children, onClick, disabled, loading, type = 'button' }: {
  children: ReactNode
  onClick?: () => void
  disabled?: boolean
  loading?: boolean
  type?: 'button' | 'submit'
}) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled || loading}
      className="group relative flex h-12 w-full items-center justify-center gap-2 overflow-hidden rounded-xl bg-[rgb(var(--accent))] text-[14.5px] font-bold text-white shadow-[0_8px_28px_rgb(var(--accent)/0.4)] transition-[transform,background-color,box-shadow,opacity] duration-150 ease-out active:scale-[0.97] enabled:hover:bg-[rgb(var(--accent-hover))] disabled:cursor-not-allowed disabled:opacity-45 disabled:shadow-none max-sm:text-[16px]"
    >
      {loading && <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/25 border-t-white" />}
      {children}
    </button>
  )
}

/** Action secondaire, en texte. */
export function Ghost({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="rounded-lg px-2 py-1.5 text-[12.5px] font-semibold text-white/50 transition-colors duration-150 hover:text-white disabled:opacity-40 max-sm:min-h-[44px] max-sm:text-[15px]"
    >
      {children}
    </button>
  )
}

/** Lien d'accent, dans le corps d'une étape. */
export function TextLink({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="text-[12px] font-semibold text-[rgb(var(--accent-text))] transition-opacity duration-150 hover:opacity-80 max-sm:min-h-[44px] max-sm:text-[14px]"
    >
      {children}
    </button>
  )
}
