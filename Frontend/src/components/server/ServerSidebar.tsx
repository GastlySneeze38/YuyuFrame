import type { OwnershipData, TabId } from './types'
import { TABS } from './types'
import { ownerColor, tpsColor } from './utils'

/**
 * Sidebar gauche — style panneau d'hébergeur : liste des agents connus (local + rapportés)
 * en haut, puis navigation par catégories (onglets) pour l'agent sélectionné en bas.
 */
export function ServerSidebar({ agents, error, activeId, onSelect, tab, onTabChange }: {
  agents: OwnershipData[]
  error: boolean
  activeId: string | null
  onSelect: (id: string) => void
  tab: TabId
  onTabChange: (t: TabId) => void
}) {
  return (
    <div style={{ width: 220, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 12, alignSelf: 'flex-start' }}>
      <div style={{ border: '1px solid rgba(255,255,255,0.08)', borderRadius: 10, overflow: 'hidden' }}>
        <div style={{ padding: '8px 12px', borderBottom: '1px solid rgba(255,255,255,0.06)', fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.8, color: 'rgba(255,255,255,0.35)' }}>
          Agents ({agents.length})
        </div>
        {agents.length === 0 ? (
          <div style={{ padding: 14, fontSize: 11, color: error ? '#ef4444' : 'rgba(255,255,255,0.3)', fontFamily: 'monospace' }}>
            {error ? 'Agent P2P inaccessible' : 'En attente de données…'}
          </div>
        ) : (
          agents.map((a, i) => {
            const isActive = a.peer_id === activeId
            return (
              <button
                key={a.peer_id}
                onClick={() => onSelect(a.peer_id)}
                className="flex w-full items-center gap-2 text-left"
                style={{
                  padding: '9px 12px', fontSize: 11, fontFamily: 'monospace', cursor: 'pointer',
                  background: isActive ? 'rgba(129,140,248,0.12)' : 'transparent',
                  borderLeft: isActive ? '2px solid #818cf8' : '2px solid transparent',
                  color: isActive ? '#fff' : 'rgba(255,255,255,0.55)',
                }}
              >
                <div style={{ width: 6, height: 6, borderRadius: '50%', background: ownerColor(i), flexShrink: 0 }} />
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.peer_name}</div>
                  <div style={{ fontSize: 9, color: 'rgba(255,255,255,0.3)' }}>{i === 0 ? 'local' : 'rapporté'}</div>
                </div>
                <span style={{ fontSize: 10, color: tpsColor(a.tps), fontWeight: 700, flexShrink: 0 }}>{a.tps.toFixed(0)}</span>
              </button>
            )
          })
        )}
      </div>

      <div style={{ border: '1px solid rgba(255,255,255,0.08)', borderRadius: 10, overflow: 'hidden' }}>
        <div style={{ padding: '8px 12px', borderBottom: '1px solid rgba(255,255,255,0.06)', fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.8, color: 'rgba(255,255,255,0.35)' }}>
          Catégories
        </div>
        {TABS.map(t => {
          const isActive = t.id === tab
          return (
            <button
              key={t.id}
              onClick={() => onTabChange(t.id)}
              className="flex w-full items-center text-left"
              style={{
                padding: '9px 12px', fontSize: 11, fontWeight: 600, fontFamily: 'monospace', cursor: 'pointer',
                background: isActive ? 'rgba(129,140,248,0.12)' : 'transparent',
                borderLeft: isActive ? '2px solid #818cf8' : '2px solid transparent',
                color: isActive ? '#fff' : 'rgba(255,255,255,0.5)',
              }}
            >
              {t.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}
