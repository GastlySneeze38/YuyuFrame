import type { SyncInstance } from '@/types'
import { formatBytes } from '@/lib/format'
import { useT } from '@/i18n'

/**
 * Ce que le serveur garde de cette instance.
 *
 * Listait les mondes envoyés ; ils n'en font plus partie (ils relèvent du
 * backup). Montre désormais ce qui compte vraiment sur un envoi par morceaux :
 * combien de fichiers, quelle taille, et à quelle révision — c'est cette
 * révision qu'on compare entre deux PC quand on se demande lequel est en
 * retard.
 */
export function CloudContentSummary({ cloudEntry }: { cloudEntry: SyncInstance }) {
  const t = useT()
  const when = new Date(cloudEntry.updated_at).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

  const chips = [
    { key: 'files', label: t('sync.fileCount', { count: cloudEntry.file_count }), icon: '📄' },
    { key: 'size', label: formatBytes(cloudEntry.total_bytes), icon: '💽' },
    { key: 'revision', label: t('sync.revision', { count: cloudEntry.revision }), icon: '🔁' },
  ]

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span className="text-[10px] font-bold uppercase tracking-[0.1em] text-txt-muted">{t('sync.cloudContent')}</span>
        <span className="text-[10px] text-txt-muted">{when}</span>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {chips.map((chip) => (
          <div key={chip.key} className="flex items-center gap-1.5 rounded-lg border border-line bg-surface-2 px-2 py-1">
            <span className="text-[11px]">{chip.icon}</span>
            <span className="text-[11px] font-medium text-txt-secondary">{chip.label}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
