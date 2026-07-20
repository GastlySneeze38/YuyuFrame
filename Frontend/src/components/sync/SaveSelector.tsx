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
      <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.22)' }}>
        Aucune save — mods/ et config/ seront synchronisés
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <span style={{ fontSize: 10, fontWeight: 700, color: 'rgba(255,255,255,0.28)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>
          Saves à inclure
        </span>
        <div className="flex items-center gap-2">
          <span style={{ fontSize: 10, color: selected.size >= maxSaves ? 'rgba(255,180,0,0.7)' : 'rgba(255,255,255,0.2)' }}>
            {selected.size}/{maxSaves}
          </span>
          {saves.length > 1 && (
            <button
              onClick={onSelectAll}
              disabled={disabled}
              style={{ fontSize: 10, color: 'rgba(75,63,207,0.8)', fontWeight: 600, cursor: disabled ? 'not-allowed' : 'pointer' }}
              onMouseEnter={(e) => { if (!disabled) (e.currentTarget as HTMLElement).style.color = '#818cf8' }}
              onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.color = 'rgba(75,63,207,0.8)' }}
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
              className="flex items-center gap-2.5 w-full rounded-xl px-3 py-2 text-left transition-all duration-150"
              style={{
                background: isSelected ? 'rgba(75,63,207,0.14)' : 'rgba(255,255,255,0.03)',
                border: `1px solid ${isSelected ? 'rgba(75,63,207,0.32)' : 'rgba(255,255,255,0.06)'}`,
                opacity: limitReached ? 0.4 : 1,
                cursor: (limitReached || disabled) ? 'not-allowed' : 'pointer',
              }}
            >
              <div
                className="flex h-4 w-4 flex-shrink-0 items-center justify-center rounded"
                style={{
                  background: isSelected ? '#4B3FCF' : 'rgba(255,255,255,0.06)',
                  border: `1.5px solid ${isSelected ? '#4B3FCF' : 'rgba(255,255,255,0.14)'}`,
                }}
              >
                {isSelected && (
                  <svg viewBox="0 0 12 10" fill="white" width={8} height={6}>
                    <path d="M1 5l3.5 3.5L11 1" stroke="white" strokeWidth={1.8} fill="none" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                )}
              </div>
              <div className="flex-1 min-w-0">
                <p className="font-semibold truncate" style={{ fontSize: 12, color: 'rgba(255,255,255,0.85)' }}>
                  {save.name}
                </p>
                <p style={{ fontSize: 10, color: 'rgba(255,255,255,0.28)', marginTop: 1 }}>
                  {formatDateTime(save.updated_at)} · {formatBytes(save.size_bytes)}
                </p>
              </div>
              {isInCloud && (
                <span style={{ fontSize: 9, color: 'rgba(74,222,128,0.6)', fontWeight: 700, letterSpacing: '0.05em', flexShrink: 0 }}>CLOUD</span>
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}
