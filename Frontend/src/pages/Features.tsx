import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Reveal } from '@/components/Reveal'
import { useFleet } from '@/stores/useFleet'
import { SYNC_ENABLED, SYNC_FLAG } from '@/config/features'
import { listItemVariants, listVariants } from '@/lib/motion'
import { useT } from '@/i18n'
import { useIsMobile } from '@/mobile/useIsMobile'
import { MenuButton } from '@/mobile/ui/NavDrawer'
import { LIST, RowChevron, Section } from '@/mobile/ui/kit'

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
  // Sync et sauvegardes : annoncées, pas encore ouvertes. Les deux écrivent
  // sur nos serveurs, et il n'y a pas encore la place pour y garder des
  // instances et des mondes. Elles gardent leur `path` — les routes existent
  // et restent gardées par le plan — mais « bientôt » les rend inertes ici,
  // ce qui est plus honnête que de mener à un écran qui ne pourrait rien
  // stocker.
  { key: 'sync', path: '/sync', status: 'soon', paid: true, icon: 'M7 18a5 5 0 01-.5-9.97A6 6 0 0118 8.5 4.5 4.5 0 0117.5 18H7z' },
  { key: 'backup', path: '/backup', status: 'soon', paid: true, icon: 'M3 7h18v4H3zM5 11v8h14v-8M10 15h4' },
  { key: 'stats', path: '/stats', status: 'ready', icon: 'M4 20V10M10 20V4M16 20v-7M22 20H2' },
  { key: 'jvm', path: '/jvm', status: 'ready', icon: 'M4 6h16M4 12h16M4 18h10' },
  { key: 'skins', path: '/skins', status: 'ready', icon: 'M8 3l4 2 4-2 4 3-2.5 4H16v11H8V10H5.5L3 6z' },
  // Missions et passe de saison : en démonstration seulement (dev), « bientôt » ailleurs.
  { key: 'missions', path: '/missions', status: 'ready', icon: 'M4 21V4M4 4h12l-2 4 2 4H4' },
]

export default function Features() {
  const t = useT()
  const navigate = useNavigate()
  const syncAllowed = useFleet((s) => s.isEnabled(SYNC_FLAG))
  const mobile = useIsMobile()

  const statusOf = (f: Feature): Status => {
    // La sync suit ses deux interrupteurs : celui du code et celui du
    // back-office (voir config/features.ts).
    //
    // `ready` seulement : mettre « en pause » ce qui est déjà annoncé
    // « bientôt » reviendrait à démentir la carte d'à côté. Un interrupteur
    // suspend ce qui tourne, il n'ajourne pas ce qui n'est pas encore sorti.
    if (f.status === 'ready' && f.key === 'sync' && (!SYNC_ENABLED || !syncAllowed)) return 'unavailable'
    return f.status
  }

  // Téléphone : une liste groupée, comme les Réglages. Ce qui s'ouvre
  // d'abord ; ce qui n'est pas encore sorti dans un second groupe, en retrait,
  // pour ne pas mêler des lignes qu'on touche à des lignes inertes.
  if (mobile) {
    const ready = FEATURES.filter((f) => statusOf(f) === 'ready')
    const later = FEATURES.filter((f) => statusOf(f) !== 'ready')
    const row = (f: Feature) => {
      const status = statusOf(f)
      const open = status === 'ready' && f.path
      return (
        <button
          key={f.key}
          onClick={open ? () => navigate(f.path!) : undefined}
          disabled={!open}
          className="flex min-h-[76px] w-full items-center gap-3.5 px-4 py-3 text-left active:bg-white/[0.04] disabled:active:bg-transparent"
        >
          <span className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl ${open ? 'bg-accent/15 text-accent-hover' : 'bg-white/[0.05] text-white/40'}`}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-[22px] w-[22px]"><path d={f.icon} /></svg>
          </span>
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
              <span className={`min-w-0 break-words text-[15px] font-semibold leading-snug ${open ? 'text-white' : 'text-white/60'}`}>{t(`features.${f.key}Title`)}</span>
              {f.paid && <span className="shrink-0 rounded-full bg-accent/15 px-2 py-0.5 text-[13px] font-semibold text-accent-hover">{t('features.paidBadge')}</span>}
              {status === 'unavailable' && <span className="shrink-0 rounded-full bg-white/[0.06] px-2 py-0.5 text-[13px] font-semibold text-white/55">{t('features.pausedBadge')}</span>}
            </span>
            <span className={`line-clamp-2 text-[13px] leading-snug ${open ? 'text-white/60' : 'text-white/40'}`}>{t(`features.${f.key}Text`)}</span>
          </span>
          {open && <RowChevron />}
        </button>
      )
    }
    return (
      <div className="flex h-full flex-col overflow-hidden bg-[rgb(var(--bg-primary))] text-txt-primary">
        <header className="flex shrink-0 items-center gap-1 pb-2 pl-1.5 pr-4 pt-[max(12px,env(safe-area-inset-top))]">
          <MenuButton />
          <h1 className="min-w-0 flex-1 truncate text-[28px] font-bold tracking-tight text-white">{t('features.title')}</h1>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(32px,env(safe-area-inset-bottom))] pt-2">
          <div className="flex flex-col gap-8">
            <p className="px-1 text-[15px] leading-relaxed text-white/60">{t('features.subtitle')}</p>
            <div className={LIST}>{ready.map(row)}</div>
            {later.length > 0 && (
              <Section title={t('features.soonBadge')}>
                <div className={LIST}>{later.map(row)}</div>
              </Section>
            )}
            <p className="px-1 text-center text-[13px] leading-relaxed text-white/45">{t('features.moreComing')}</p>
          </div>
        </div>
      </div>
    )
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
