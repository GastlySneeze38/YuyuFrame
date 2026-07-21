import { memo, useState } from 'react'
import type { Mod } from '@/types'
import { formatBytes } from '@/lib/format'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { Toggle } from '@/components/ui/Toggle'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { displayName, type ModUpdate } from './modUtils'

/** Mémoïsé : rendu en liste (potentiellement des dizaines de mods) — les
 * callbacks reçoivent l'identifiant du mod pour que le parent puisse passer
 * des références stables (useCallback) au lieu d'une closure par ligne. */
export const ModRow = memo(function ModRow({ mod, version, modrinthName, update, updating, logoUrl, onToggle, onDelete, onUpdate }: {
  mod: Mod
  version: string | null
  modrinthName: string | null
  update: ModUpdate | null
  updating: boolean
  logoUrl: string | null
  onToggle: (mod: Mod) => void
  onDelete: (name: string) => void
  onUpdate: (update: ModUpdate) => void
}) {
  const [confirm, setConfirm] = useState(false)
  return (
    <div
      className={`flex items-center rounded-2xl px-4 py-3 transition-all duration-150 border border-[rgba(255,255,255,0.06)] ${
        mod.enabled ? 'bg-[rgba(255,255,255,0.04)] opacity-100' : 'bg-[rgba(255,255,255,0.018)] opacity-60'
      }`}
    >
      {/* Section gauche : icône + nom (flex-1) */}
      <div className="flex items-center gap-3 min-w-0 flex-1">
        <div className={`w-9 h-9 rounded-[10px] flex-shrink-0 overflow-hidden flex items-center justify-center ${
          mod.enabled ? 'bg-[rgba(75,63,207,0.15)]' : 'bg-[rgba(255,255,255,0.05)]'
        }`}>
          {logoUrl
            ? <img src={logoUrl} alt="" className="w-full h-full object-cover" />
            : <PlugIcon size={18} color={mod.enabled ? 'rgba(120,110,230,0.8)' : 'rgba(255,255,255,0.2)'} />
          }
        </div>
        <div className="min-w-0 flex-1">
          <p className={`truncate font-semibold text-[13px] ${mod.enabled ? 'text-[rgba(255,255,255,0.9)]' : 'text-[rgba(255,255,255,0.4)]'}`}>
            {modrinthName || displayName(mod.name)}
          </p>
          <p className="text-[11px] text-[rgba(255,255,255,0.22)] mt-px">{formatBytes(mod.size)}</p>
        </div>
      </div>

      {/* Section centre : version (vraiment au milieu car flanquée de 2 flex-1) */}
      <div className="flex-shrink-0 w-[110px] text-center px-2">
        <span className={`text-[11px] font-medium whitespace-nowrap overflow-hidden text-ellipsis block ${
          version ? 'text-[rgba(255,255,255,0.45)]' : 'text-[rgba(255,255,255,0.15)]'
        }`}>
          {version ?? '—'}
        </span>
      </div>

      {/* Section droite : actions (flex-1, alignées à droite) */}
      <div className="flex items-center justify-end gap-2 flex-1">
        {update && (() => {
          const blocked = update.blockedBy.length > 0
          return (
            <button
              onClick={() => onUpdate(update)}
              disabled={updating || blocked}
              title={
                blocked
                  ? `Mise à jour bloquée : casserait ${update.blockedBy.join(', ')}`
                  : `Mettre à jour → ${update.newVersion}`
              }
              className={`flex h-7 flex-shrink-0 items-center gap-1 rounded-lg px-2 transition-all duration-150 text-[10px] font-bold border ${
                blocked
                  ? 'bg-[rgba(248,113,113,0.1)] border-[rgba(248,113,113,0.28)] text-[rgba(248,113,113,0.7)]'
                  : updating
                    ? 'bg-[rgba(255,255,255,0.04)] border-[rgba(250,204,21,0.28)] text-[rgba(255,255,255,0.2)]'
                    : 'bg-[rgba(250,204,21,0.12)] border-[rgba(250,204,21,0.28)] text-[rgba(250,204,21,0.85)]'
              } ${updating || blocked ? 'cursor-not-allowed' : 'cursor-pointer'}`}
            >
              {blocked ? (
                <svg viewBox="0 0 24 24" fill="currentColor" width={10} height={10}>
                  <path d="M12 2L1 21h22L12 2zm0 4.5L19.5 19h-15L12 6.5zM11 10v5h2v-5h-2zm0 6v2h2v-2h-2z" />
                </svg>
              ) : updating ? (
                <ButtonSpinner size={12} color="rgba(250,204,21,0.6)" trackColor="rgba(255,255,255,0.1)" />
              ) : (
                <svg viewBox="0 0 24 24" fill="currentColor" width={10} height={10}>
                  <path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z" />
                </svg>
              )}
              {update.newVersion}
            </button>
          )
        })()}
        <Toggle checked={mod.enabled} onChange={() => onToggle(mod)} size="sm" title={mod.enabled ? 'Désactiver' : 'Activer'} />
        {confirm ? (
          <div className="flex items-center gap-1 flex-shrink-0">
            <button onClick={() => { onDelete(mod.name); setConfirm(false) }} className="text-[10px] font-semibold text-[rgb(248,113,113)] bg-[rgba(200,50,50,0.15)] rounded-[7px] py-[3px] px-[7px]">Suppr.</button>
            <button onClick={() => setConfirm(false)} className="text-[10px] text-[rgba(255,255,255,0.4)] bg-[rgba(255,255,255,0.06)] rounded-[7px] py-[3px] px-[7px]">Ann.</button>
          </div>
        ) : (
          <button onClick={() => setConfirm(true)}
            className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-xl transition-all duration-150 text-[rgba(255,255,255,0.2)] hover:text-[rgb(248,113,113)] hover:bg-[rgba(200,50,50,0.12)]">
            <svg viewBox="0 0 24 24" fill="currentColor" width={16} height={16}>
              <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
            </svg>
          </button>
        )}
      </div>
    </div>
  )
})
