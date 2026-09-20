import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Reveal } from '@/components/Reveal'
import { useFleet } from '@/stores/useFleet'
import { SYNC_ENABLED, SYNC_FLAG } from '@/config/features'
import { listItemVariants, listVariants } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * Fonctionnalités — le point d'entrée unique vers tout ce que le launcher
 * sait faire au-delà du lancement : sync cloud, statistiques, et ce qui
 * arrive. Sync et Stats ont quitté la barre de navigation de l'accueil pour
 * vivre ici : la barre reste courte, et les nouveautés ont un endroit où
 * apparaître sans la rallonger à l'infini.
 */

type Status = 'ready' | 'unavailable' | 'soon'

interface Feature {
  key: string
  path?: string
  status: Status
  /** Réservé aux abonnés. */
  paid?: boolean
  icon: string
}

const FEATURES: Feature[] = [
  { key: 'sync', path: '/sync', status: 'ready', paid: true, icon: 'M7 18a5 5 0 01-.5-9.97A6 6 0 0118 8.5 4.5 4.5 0 0117.5 18H7z' },
  { key: 'backup', status: 'soon', paid: true, icon: 'M3 7h18v4H3zM5 11v8h14v-8M10 15h4' },
  { key: 'stats', path: '/stats', status: 'ready', icon: 'M4 20V10M10 20V4M16 20v-7M22 20H2' },
  { key: 'jvm', path: '/jvm', status: 'ready', icon: 'M4 6h16M4 12h16M4 18h10' },
  { key: 'skins', status: 'soon', icon: 'M8 3l4 2 4-2 4 3-2.5 4H16v11H8V10H5.5L3 6z' },
]

export default function Features() {
  const t = useT()
  const navigate = useNavigate()
  const syncAllowed = useFleet((s) => s.isEnabled(SYNC_FLAG))

  const statusOf = (f: Feature): Status => {
    // La sync suit ses deux interrupteurs : celui du code et celui du
    // back-office (voir config/features.ts).
    if (f.key === 'sync' && (!SYNC_ENABLED || !syncAllowed)) return 'unavailable'
    return f.status
  }

  return (
    <div className="relative h-full overflow-y-auto bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em] text-txt-primary">
            {t('features.title')}
          </h1>
          <p className="mt-px text-[10px] text-txt-muted">{t('features.subtitle')}</p>
        </div>
      </PageHeader>

      <div className="mx-auto w-full max-w-4xl px-7 py-10">
        <motion.div variants={listVariants} initial="initial" animate="animate" className="grid grid-cols-2 gap-4">
          {FEATURES.map((f) => {
            const status = statusOf(f)
            const clickable = status === 'ready' && f.path
            return (
              <motion.button
                key={f.key}
                variants={listItemVariants}
                whileHover={clickable ? { y: -4 } : undefined}
                whileTap={clickable ? { scale: 0.99 } : undefined}
                transition={{ type: 'spring', stiffness: 420, damping: 26 }}
                onClick={clickable ? () => navigate(f.path!) : undefined}
                disabled={!clickable}
                className={`flex gap-4 rounded-2xl border p-5 text-left transition-colors duration-150 ${
                  status === 'soon'
                    ? 'border-line-soft opacity-55'
                    : status === 'unavailable'
                      ? 'border-line-soft'
                      : 'border-line hover:border-accent/40 hover:bg-accent/5'
                }`}
              >
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent/12 text-accent-hover">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
                    <path d={f.icon} />
                  </svg>
                </span>
                <span className="flex min-w-0 flex-1 flex-col gap-1.5">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="text-[14px] font-semibold">{t(`features.${f.key}Title`)}</span>
                    {f.paid && (
                      <span className="rounded-full bg-accent/15 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider text-accent-hover">
                        {t('features.paidBadge')}
                      </span>
                    )}
                    {status === 'soon' && (
                      <span className="rounded-full bg-surface-3 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider text-txt-muted">
                        {t('features.soonBadge')}
                      </span>
                    )}
                    {status === 'unavailable' && (
                      <span className="rounded-full bg-surface-3 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider text-txt-muted">
                        {t('features.pausedBadge')}
                      </span>
                    )}
                  </span>
                  <span className="text-[12px] leading-relaxed text-txt-secondary">
                    {t(`features.${f.key}Text`)}
                  </span>
                </span>
              </motion.button>
            )
          })}
        </motion.div>

        {/* Ce qui n'est pas encore écrit : on assume plutôt que de laisser
            croire que la liste est figée. */}
        <Reveal delay={0.1} className="mt-10 text-center">
          <p className="text-[12px] text-txt-muted">{t('features.moreComing')}</p>
        </Reveal>
      </div>
    </div>
  )
}
