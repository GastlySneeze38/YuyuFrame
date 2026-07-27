import { useNavigate } from 'react-router-dom'
import type { Instance } from '@/types'
import { loaderColor } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { ModalShell } from '@/components/ui/ModalShell'
import { useT } from '@/i18n'

/** Grille des instances (toutes les infos DB) ouverte depuis Home au lieu du
 * <select> natif — favoris séparés des autres (même découpage que la page
 * Instances), dernière carte = raccourci vers la page Instances. */
export function InstanceSwitchModal({
  instances,
  selectedInstanceId,
  onClose,
  onSelect,
}: {
  instances: Instance[]
  selectedInstanceId: string | null
  onClose: () => void
  onSelect: (id: string) => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const favorites = instances.filter((i) => i.favorite)
  const others = instances.filter((i) => !i.favorite)

  const renderCard = (inst: Instance) => {
    const selected = inst.id === selectedInstanceId
    return (
      <button
        key={inst.id}
        onClick={() => { onSelect(inst.id); onClose() }}
        className={`flex flex-col items-start gap-2 rounded-2xl p-3.5 text-left transition-all duration-150 border ${
          selected
            ? 'bg-[rgba(75,63,207,0.18)] border-[rgba(75,63,207,0.55)] shadow-[0_0_20px_rgba(75,63,207,0.18)]'
            : 'bg-[rgba(255,255,255,0.03)] border-[rgba(255,255,255,0.07)] hover:bg-[rgba(255,255,255,0.06)] hover:border-[rgba(255,255,255,0.12)]'
        }`}
      >
        <div className="flex w-full items-start justify-between gap-2">
          <div className={`flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl text-[15px] ${selected ? 'bg-[rgba(75,63,207,0.3)]' : 'bg-[rgba(255,255,255,0.05)]'}`}>
            🧱
          </div>
          {inst.favorite && (
            <svg viewBox="0 0 24 24" fill="#facc15" width={13} height={13} className="flex-shrink-0 mt-1">
              <path d="M11.48 3.499a.562.562 0 011.04 0l2.125 5.111a.563.563 0 00.475.345l5.518.442c.499.04.701.663.321.988l-4.204 3.602a.563.563 0 00-.182.557l1.285 5.385a.562.562 0 01-.84.61l-4.725-2.885a.563.563 0 00-.586 0L6.982 20.54a.562.562 0 01-.84-.61l1.285-5.386a.562.562 0 00-.182-.557l-4.204-3.602a.563.563 0 01.321-.988l5.518-.442a.563.563 0 00.475-.345L11.48 3.5z" />
            </svg>
          )}
        </div>

        <p className={`font-bold truncate text-[13px] w-full ${selected ? 'text-white' : 'text-[rgba(255,255,255,0.85)]'}`}>
          {inst.name}
        </p>

        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="text-[10px] text-[rgba(255,255,255,0.35)]">{inst.mc_version}</span>
          <span className="text-[10px] text-[rgba(255,255,255,0.2)]">·</span>
          <span className="text-[10px] font-semibold" style={{ color: loaderColor(inst.loader) }}>{inst.loader}</span>
          <span className="text-[10px] text-[rgba(255,255,255,0.2)]">·</span>
          <span className="text-[10px] text-[rgba(255,255,255,0.3)]">{formatRam(inst.ram_mb)}</span>
        </div>

        {inst.description && (
          <p className="text-[10px] text-[rgba(255,255,255,0.3)] line-clamp-2 w-full">
            {inst.description}
          </p>
        )}
      </button>
    )
  }

  const createCard = (
    <button
      key="create"
      onClick={() => { onClose(); navigate('/instances') }}
      className="flex min-h-[110px] flex-col items-center justify-center gap-2 rounded-2xl p-3.5 transition-all duration-200 border-2 border-dashed border-[rgba(255,255,255,0.1)] text-[rgba(255,255,255,0.3)] hover:border-[rgba(75,63,207,0.45)] hover:text-[rgba(140,130,240,0.8)] hover:bg-[rgba(75,63,207,0.05)]"
    >
      <svg viewBox="0 0 24 24" fill="currentColor" width={22} height={22}>
        <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
      </svg>
      <span className="text-[11px] font-semibold">{t('instancesPage.newInstance')}</span>
    </button>
  )

  return (
    <ModalShell title={t('instancesPage.chooseInstance')} onClose={onClose} maxWidth="max-w-2xl">
      <div className="flex flex-col gap-4 max-h-[60vh] overflow-y-auto pr-4">
        {favorites.length > 0 && (
          <div className="flex flex-col gap-2">
            <p className="px-1 text-[10px] font-semibold uppercase tracking-[0.08em] text-[#facc15]">★ {t('instancesPage.favorites')}</p>
            <div className="grid grid-cols-3 gap-3">
              {favorites.map(renderCard)}
              {others.length === 0 && createCard}
            </div>
          </div>
        )}

        {(others.length > 0 || favorites.length === 0) && (
          <div className="flex flex-col gap-2">
            {favorites.length > 0 && (
              <p className="px-1 text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(255,255,255,0.3)]">{t('instancesPage.othersHeader')}</p>
            )}
            <div className="grid grid-cols-3 gap-3">
              {others.map(renderCard)}
              {createCard}
            </div>
          </div>
        )}
      </div>
    </ModalShell>
  )
}
