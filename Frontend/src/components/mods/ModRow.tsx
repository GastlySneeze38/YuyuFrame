import { useState } from 'react'
import type { Mod } from '@/types'
import { formatBytes } from '@/lib/format'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { Toggle } from '@/components/ui/Toggle'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { displayName, type ModUpdate } from './modUtils'

export function ModRow({ mod, version, modrinthName, update, updating, logoUrl, onToggle, onDelete, onUpdate }: {
  mod: Mod
  version: string | null
  modrinthName: string | null
  update: ModUpdate | null
  updating: boolean
  logoUrl: string | null
  onToggle: () => void
  onDelete: () => void
  onUpdate: () => void
}) {
  const [confirm, setConfirm] = useState(false)
  return (
    <div
      className="flex items-center rounded-2xl px-4 py-3 transition-all duration-150"
      style={{ background: mod.enabled ? 'rgba(255,255,255,0.04)' : 'rgba(255,255,255,0.018)', border: '1px solid rgba(255,255,255,0.06)', opacity: mod.enabled ? 1 : 0.6 }}
    >
      {/* Section gauche : icône + nom (flex-1) */}
      <div className="flex items-center gap-3 min-w-0" style={{ flex: 1 }}>
        <div style={{ width: 36, height: 36, borderRadius: 10, flexShrink: 0, overflow: 'hidden', background: mod.enabled ? 'rgba(75,63,207,0.15)' : 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          {logoUrl
            ? <img src={logoUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            : <PlugIcon size={18} color={mod.enabled ? 'rgba(120,110,230,0.8)' : 'rgba(255,255,255,0.2)'} />
          }
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold" style={{ fontSize: 13, color: mod.enabled ? 'rgba(255,255,255,0.9)' : 'rgba(255,255,255,0.4)' }}>
            {modrinthName || displayName(mod.name)}
          </p>
          <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.22)', marginTop: 1 }}>{formatBytes(mod.size)}</p>
        </div>
      </div>

      {/* Section centre : version (vraiment au milieu car flanquée de 2 flex-1) */}
      <div style={{ flexShrink: 0, width: 110, textAlign: 'center', padding: '0 8px' }}>
        <span style={{ fontSize: 11, fontWeight: 500, color: version ? 'rgba(255,255,255,0.45)' : 'rgba(255,255,255,0.15)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', display: 'block' }}>
          {version ?? '—'}
        </span>
      </div>

      {/* Section droite : actions (flex-1, alignées à droite) */}
      <div className="flex items-center justify-end gap-2" style={{ flex: 1 }}>
        {update && (() => {
          const blocked = update.blockedBy.length > 0
          return (
            <button
              onClick={onUpdate}
              disabled={updating || blocked}
              title={
                blocked
                  ? `Mise à jour bloquée : casserait ${update.blockedBy.join(', ')}`
                  : `Mettre à jour → ${update.newVersion}`
              }
              className="flex h-7 flex-shrink-0 items-center gap-1 rounded-lg px-2 transition-all duration-150"
              style={{
                fontSize: 10, fontWeight: 700,
                background: blocked ? 'rgba(248,113,113,0.1)' : updating ? 'rgba(255,255,255,0.04)' : 'rgba(250,204,21,0.12)',
                border: blocked ? '1px solid rgba(248,113,113,0.28)' : '1px solid rgba(250,204,21,0.28)',
                color: blocked ? 'rgba(248,113,113,0.7)' : updating ? 'rgba(255,255,255,0.2)' : 'rgba(250,204,21,0.85)',
                cursor: updating || blocked ? 'not-allowed' : 'pointer',
              }}
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
        <Toggle checked={mod.enabled} onChange={onToggle} size="sm" title={mod.enabled ? 'Désactiver' : 'Activer'} />
        {confirm ? (
          <div className="flex items-center gap-1 flex-shrink-0">
            <button onClick={() => { onDelete(); setConfirm(false) }} style={{ fontSize: 10, fontWeight: 600, color: 'rgb(248,113,113)', background: 'rgba(200,50,50,0.15)', borderRadius: 7, padding: '3px 7px' }}>Suppr.</button>
            <button onClick={() => setConfirm(false)} style={{ fontSize: 10, color: 'rgba(255,255,255,0.4)', background: 'rgba(255,255,255,0.06)', borderRadius: 7, padding: '3px 7px' }}>Ann.</button>
          </div>
        ) : (
          <button onClick={() => setConfirm(true)}
            className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-xl transition-all duration-150"
            style={{ color: 'rgba(255,255,255,0.2)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'rgb(248,113,113)'; e.currentTarget.style.background = 'rgba(200,50,50,0.12)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.2)'; e.currentTarget.style.background = 'transparent' }}>
            <svg viewBox="0 0 24 24" fill="currentColor" width={16} height={16}>
              <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
            </svg>
          </button>
        )}
      </div>
    </div>
  )
}
