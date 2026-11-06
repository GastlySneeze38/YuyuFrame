import { motion } from 'framer-motion'
import { press } from '@/lib/motion'
import { useT } from '@/i18n'
import { GlowPulse } from '@/components/ui/GlowPulse'

/**
 * Carte d'offre, dernière de la rangée du bas.
 *
 * Elle occupait auparavant une colonne entière du pied de page, en face de la
 * marque — deux blocs de texte qui encadraient la barre de navigation et la
 * repoussaient au centre d'une zone déjà chargée. Ramenée au format des
 * cartes voisines, elle tient la même place qu'un serveur et le pied de page
 * n'a plus qu'un seul rôle : naviguer.
 *
 * Discrète par construction : elle ne clignote pas, sa lueur enfle et
 * retombe sur plusieurs secondes.
 */

export function ProCard({ onOpen }: { onOpen: () => void }) {
  const t = useT()

  return (
    <div
      className="relative flex min-w-0 flex-1 flex-col gap-1.5 rounded-xl border border-[rgb(var(--accent-hover)/0.28)] bg-[rgb(var(--accent)/0.08)] px-[clamp(8px,1.4vh,12px)] py-[clamp(6px,1.1vh,10px)]"
    >
      <GlowPulse shadow="0 0 22px rgb(var(--accent-hover)/0.18)" duration={4.5} radius="rounded-xl" />
      <div className="flex items-center gap-1.5">
        <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="flex-shrink-0 text-[rgb(var(--accent-text))]">
          <path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" />
        </svg>
        <span className="truncate text-[clamp(9px,1.3vh,11px)] font-bold text-white">
          YuyuFrame <span className="text-[rgb(var(--accent-text))]">Pro</span>
        </span>
      </div>

      <p className="m-0 line-clamp-2 text-[clamp(8px,1.15vh,10px)] leading-[1.55] text-[rgba(255,255,255,0.28)]">
        {t('home.proTagline')}
      </p>

      <motion.button {...press}
        onClick={onOpen}
        title={t('home.proSeePlansTitle')}
        className="mt-auto flex h-7 items-center justify-center gap-1.5 rounded-lg bg-[rgb(var(--accent)/0.35)] text-[11px] font-semibold text-[rgba(255,255,255,0.88)] transition-colors duration-150 hover:bg-[rgb(var(--accent)/0.55)]"
      >
        {t('home.proSeePlans')}
        <span className="text-[10px] font-bold text-[rgb(var(--accent-text))]">-50%</span>
      </motion.button>
    </div>
  )
}
