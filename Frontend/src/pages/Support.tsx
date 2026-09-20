import { motion } from 'framer-motion'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { useT } from '@/i18n'

/**
 * Support — page volontairement vide pour l'instant.
 *
 * À venir : les tickets ouverts depuis le launcher (`/v1/support/tickets`,
 * voir docs/launcher/launcher-api.md § Support) — liste, conversation,
 * rapport de diagnostic anonymisé. L'équipe y répond depuis Discord ou le
 * back-office, et la réponse revient ici.
 */
export default function Support() {
  const t = useT()

  return (
    <div className="relative h-full overflow-y-auto bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em] text-txt-primary">
            {t('support.title')}
          </h1>
          <p className="mt-px text-[10px] text-txt-muted">{t('support.subtitle')}</p>
        </div>
      </PageHeader>

      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
        className="flex flex-col items-center justify-center gap-3 px-7 py-24 text-center"
      >
        <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-accent/12 text-accent-hover">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" className="h-7 w-7">
            <path d="M12 2a9 9 0 00-9 9v5a3 3 0 003 3h1a1 1 0 001-1v-5a1 1 0 00-1-1H5v-1a7 7 0 1114 0v1h-2a1 1 0 00-1 1v5a1 1 0 001 1h1a3 3 0 003-3v-5a9 9 0 00-9-9z" />
          </svg>
        </div>
        <p className="text-[15px] font-semibold">{t('support.emptyTitle')}</p>
        <p className="max-w-sm text-[12px] leading-relaxed text-txt-secondary">{t('support.emptyText')}</p>
      </motion.div>
    </div>
  )
}
