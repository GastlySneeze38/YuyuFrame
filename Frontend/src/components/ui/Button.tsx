import { motion } from 'framer-motion'
import type { ReactNode } from 'react'
import { ButtonSpinner } from './ButtonSpinner'
import { pressable } from '@/lib/motion'

/**
 * Bouton unique du launcher. Avant lui, chaque écran recopiait sa propre
 * chaîne de classes (`rgba(75,63,207,0.18)` et compagnie) : les variantes
 * ci-dessous remplacent ces copies et ne parlent qu'en jetons de couleur.
 */

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
export type ButtonSize = 'sm' | 'md'

/**
 * ── Contrastes revus le 2026-09-28 ────────────────────────────────────────
 * Le bouton principal était un aplat d'accent à 20 % : sur le fond sombre
 * d'une modale, il se lisait comme un bouton *désactivé*, et rien ne
 * distinguait au premier coup d'œil « Envoyer à l'équipe » de « Plus tard ».
 * Il est maintenant plein — c'est l'action principale, elle doit se voir sans
 * chercher. Le fantôme, lui, gagne un trait : sans aucune bordure il ne
 * ressemblait pas à un bouton.
 */
const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-accent border-accent text-white hover:bg-accent-hover hover:border-accent-hover shadow-[0_2px_12px_rgba(75,63,207,0.35)]',
  secondary: 'bg-surface-3 border-line-strong text-txt-primary hover:bg-surface-4 hover:border-accent/40',
  ghost: 'bg-transparent border-line text-txt-primary hover:bg-surface-2 hover:border-line-strong',
  danger: 'bg-danger/15 border-danger/40 text-danger hover:bg-danger/25',
}

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3.5 text-[12.5px] rounded-lg gap-1.5',
  md: 'h-11 px-5 text-[13.5px] rounded-xl gap-2',
}

export function Button({
  children,
  onClick,
  variant = 'secondary',
  size = 'md',
  type = 'button',
  disabled,
  loading,
  fullWidth,
  title,
  className = '',
}: {
  children: ReactNode
  onClick?: () => void
  variant?: ButtonVariant
  size?: ButtonSize
  type?: 'button' | 'submit'
  disabled?: boolean
  loading?: boolean
  fullWidth?: boolean
  title?: string
  className?: string
}) {
  const blocked = disabled || loading
  return (
    <motion.button
      type={type}
      onClick={onClick}
      disabled={blocked}
      title={title}
      // Pas d'animation au survol quand le bouton est inactif : un bouton qui
      // bouge alors qu'il ne répond pas est trompeur.
      {...(blocked ? {} : pressable)}
      className={`inline-flex items-center justify-center border font-semibold transition-colors duration-150 ease-out disabled:cursor-not-allowed disabled:opacity-50 ${
        VARIANTS[variant]
      } ${SIZES[size]} ${fullWidth ? 'w-full' : ''} ${className}`}
    >
      {loading && <ButtonSpinner />}
      {children}
    </motion.button>
  )
}
