import type { SaveInfo } from '@/types'
import { formatBytes, formatDateTime } from '@/lib/format'

export function SaveSelector({
  saves,
  selected,
  maxSaves,
  disabled,
  onToggle,
  onSelectAll,
}: {
  saves: SaveInfo[]
  selected: Set<string>
  maxSaves: number
  disabled: boolean
  onToggle: (name: string) => void
  onSelectAll: () => void
}) {
  if (saves.length === 0) {
    return (
      <p className="text-[12px] text-[rgba(255,255,255,0.22)]">
        Aucune save — mods/ et config/ seront synchronisés
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <span className="text-[10px] font-bold text-[rgba(255,255,255,0.28)] tracking-[0.1em] uppercase">
          Saves à inclure
        </span>
        <div className="flex items-center gap-2">
          <span className={`text-[10px] ${selected.size >= maxSaves ? 'text-[rgba(255,180,0,0.7)]' : 'text-[rgba(255,255,255,0.2)]'}`}>
            {selected.size}/{maxSaves}
          </span>
          {saves.length > 1 && (
            <button
              onClick={onSelectAll}
              disabled={disabled}
              className={`text-[10px] font-semibold ${disabled ? 'text-[rgba(75,63,207,0.8)] cursor-not-allowed' : 'text-[rgba(75,63,207,0.8)] cursor-pointer hover:text-[#818cf8]'}`}
            >
              {selected.size === Math.min(saves.length, maxSaves) ? 'Tout désélectionner' : 'Tout sélectionner'}
            </button>
          )}
        </div>
      </div>

      <div className="flex flex-col gap-1">
        {saves.map((save) => {
          const isSelected = selected.has(save.name)
          const limitReached = !isSelected && selected.size >= maxSaves
          const isInCloud = false // could be extended later
          return (
            <button
              key={save.name}
              onClick={() => onToggle(save.name)}
              disabled={limitReached || disabled}
              className={`flex items-center gap-2.5 w-full rounded-xl px-3 py-2 text-left transition-all duration-150 border ${isSelected ? 'bg-[rgba(75,63,207,0.14)] border-[rgba(75,63,207,0.32)]' : 'bg-[rgba(255,255,255,0.03)] border-[rgba(255,255,255,0.06)]'} ${limitReached ? 'opacity-40' : 'opacity-100'} ${(limitReached || disabled) ? 'cursor-not-allowed' : 'cursor-pointer'}`}
            >
              <div
                className={`flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border-[1.5px] ${isSelected ? 'bg-[#4B3FCF] border-[#4B3FCF]' : 'bg-[rgba(255,255,255,0.06)] border-[rgba(255,255,255,0.14)]'}`}
              >
                {isSelected && (
                  <svg viewBox="0 0 12 10" fill="white" width={8} height={6}>
                    <path d="M1 5l3.5 3.5L11 1" stroke="white" strokeWidth={1.8} fill="none" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                )}
              </div>
              <div className="flex-1 min-w-0">
                <p className="font-semibold truncate text-[12px] text-[rgba(255,255,255,0.85)]">
                  {save.name}
                </p>
                <p className="text-[10px] text-[rgba(255,255,255,0.28)] mt-px">
                  {formatDateTime(save.updated_at)} · {formatBytes(save.size_bytes)}
                </p>
              </div>
              {isInCloud && (
                <span className="text-[9px] text-[rgba(74,222,128,0.6)] font-bold tracking-[0.05em] flex-shrink-0">CLOUD</span>
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}
