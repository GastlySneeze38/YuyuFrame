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

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-accent/20 border-accent/40 text-white hover:bg-accent/30',
  secondary: 'bg-surface-2 border-line text-txt-primary hover:bg-surface-3',
  ghost: 'bg-transparent border-transparent text-txt-secondary hover:text-txt-primary hover:bg-surface-1',
  danger: 'bg-danger/15 border-danger/40 text-danger hover:bg-danger/25',
}

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-[12px] rounded-lg gap-1.5',
  md: 'h-10 px-5 text-[13px] rounded-xl gap-2',
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
