import { useEffect } from 'react'
import { useFleet } from '@/stores/useFleet'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'

/**
 * Ce que le back-office impose au launcher : bannières d'information, mise à
 * jour obligatoire, version interdite. Plus le rappel de licence quand le
 * launcher tourne sur une licence hors ligne bientôt épuisée.
 *
 * Monté une fois dans App.tsx, au-dessus de tout le reste.
 */

const LEVEL_STYLES: Record<string, string> = {
  info: 'bg-accent/15 border-accent/40 text-txt-primary',
  warning: 'bg-amber-500/15 border-amber-500/40 text-amber-100',
  critical: 'bg-red-500/15 border-red-500/40 text-red-100',
}

/** Relecture périodique : le backend rafraîchit, on ne fait que relire. */
const POLL_MS = 60_000

export function FleetNotices() {
  const t = useT()
  const { config, dismissed, load, dismiss } = useFleet()
  const licenseState = useStore((s) => s.yuyuLicenseState)

  useEffect(() => {
    load()
    const timer = setInterval(load, POLL_MS)
    return () => clearInterval(timer)
  }, [load])

  // Version interdite : rien d'autre ne doit être utilisable.
  if (config.launcher_blocked) {
    return (
      <Blocking
        title={t('fleet.blockedTitle')}
        message={config.launcher_blocked}
        hint={t('fleet.blockedHint')}
      />
    )
  }

  const announcements = config.announcements.filter((a) => !dismissed.includes(a.id))
  const showUpdate = config.update_required
  const showGrace = licenseState === 'grace'

  if (!announcements.length && !showUpdate && !showGrace) return null

  return (
    <div className="flex flex-col gap-1.5 px-3 pt-2">
      {showUpdate && (
        <Notice level="warning">
          {config.min_version_message || t('fleet.updateRequired')}
        </Notice>
      )}
      {showGrace && <Notice level="warning">{t('fleet.licenseGrace')}</Notice>}
      {announcements.map((a) => (
        <Notice key={a.id} level={a.level} onClose={() => dismiss(a.id)}>
          {a.message}
        </Notice>
      ))}
    </div>
  )
}

function Notice({
  level,
  children,
  onClose,
}: {
  level: string
  children: React.ReactNode
  onClose?: () => void
}) {
  return (
    <div
      className={`flex items-center gap-3 rounded-lg border px-3 py-2 text-[12px] ${
        LEVEL_STYLES[level] ?? LEVEL_STYLES.info
      }`}
    >
      <span className="flex-1">{children}</span>
      {onClose && (
        <button
          onClick={onClose}
          className="shrink-0 rounded px-1.5 py-0.5 text-[11px] opacity-60 transition-opacity duration-150 hover:opacity-100"
        >
          ✕
        </button>
      )}
    </div>
  )
}

function Blocking({ title, message, hint }: { title: string; message: string; hint: string }) {
  return (
    <div className="fixed inset-0 z-[999] flex flex-col items-center justify-center gap-3 bg-bg-primary px-10 text-center">
      <h1 className="text-[18px] font-semibold text-txt-primary">{title}</h1>
      <p className="max-w-[420px] text-[13px] text-txt-secondary">{message}</p>
      <p className="text-[11px] text-txt-secondary opacity-70">{hint}</p>
    </div>
  )
}
