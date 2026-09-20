import { motion } from 'framer-motion'
import type { ReactNode } from 'react'
import { ModalShell } from '@/components/ui/ModalShell'
import { listItemVariants, listVariants, pressable } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * Un seul point d'entrée pour ajouter un compte Minecraft.
 *
 * Avant : un gros bouton « ajouter » qui lançait directement Microsoft, et un
 * petit « + » discret dans un coin pour les comptes hors ligne — deux poids,
 * deux mesures, et personne ne trouvait le second. Ici, les deux possibilités
 * sont présentées côte à côte, avec ce que chacune implique.
 */
export function AddAccountModal({
  onClose,
  onMicrosoft,
  onOffline,
}: {
  onClose: () => void
  onMicrosoft: () => void
  onOffline: () => void
}) {
  const t = useT()

  return (
    <ModalShell title={t('login.addAccount')} onClose={onClose} maxWidth="max-w-lg">
      <motion.div variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-2.5">
        <Choice
          onClick={() => { onClose(); onMicrosoft() }}
          title={t('login.microsoftTitle')}
          description={t('login.microsoftDescription')}
          badge={t('login.microsoftBadge')}
          accent
          icon={
            <svg viewBox="0 0 23 23" className="h-5 w-5">
              <path fill="#f25022" d="M1 1h10v10H1z" />
              <path fill="#7fba00" d="M12 1h10v10H12z" />
              <path fill="#00a4ef" d="M1 12h10v10H1z" />
              <path fill="#ffb900" d="M12 12h10v10H12z" />
            </svg>
          }
        />
        <Choice
          onClick={() => { onClose(); onOffline() }}
          title={t('login.offlineTitle')}
          description={t('login.offlineDescription')}
          badge={t('login.offlineBadge')}
          icon={
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" className="h-5 w-5">
              <path d="M12 12a4 4 0 100-8 4 4 0 000 8zM4 20a8 8 0 0116 0" />
            </svg>
          }
        />
      </motion.div>
      <p className="text-[11px] leading-relaxed text-txt-muted">{t('login.offlineWarning')}</p>
    </ModalShell>
  )
}

function Choice({
  onClick,
  title,
  description,
  badge,
  icon,
  accent,
}: {
  onClick: () => void
  title: string
  description: string
  badge: string
  icon: ReactNode
  accent?: boolean
}) {
  return (
    <motion.button
      variants={listItemVariants}
      {...pressable}
      onClick={onClick}
      className={`flex items-center gap-3.5 rounded-xl border p-3.5 text-left transition-colors duration-150 ease-out ${
        accent
          ? 'border-accent/35 bg-accent/10 hover:bg-accent/20'
          : 'border-line bg-surface-1 hover:bg-surface-2'
      }`}
    >
      <span className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-lg border border-line bg-surface-2 text-txt-secondary">
        {icon}
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="flex items-center gap-2">
          <span className="text-[13px] font-semibold text-txt-primary">{title}</span>
          <span className="rounded-full bg-surface-3 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider text-txt-muted">
            {badge}
          </span>
        </span>
        <span className="text-[11px] leading-snug text-txt-secondary">{description}</span>
      </span>
    </motion.button>
  )
}
