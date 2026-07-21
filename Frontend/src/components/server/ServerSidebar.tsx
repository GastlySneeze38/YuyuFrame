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
    <div className="w-[220px] shrink-0 flex flex-col gap-3 self-start">
      <div className="border border-[rgba(255,255,255,0.08)] rounded-[10px] overflow-hidden">
        <div className="px-[12px] py-[8px] border-b border-[rgba(255,255,255,0.06)] text-[10px] uppercase tracking-[0.8px] text-[rgba(255,255,255,0.35)]">
          Agents ({agents.length})
        </div>
        {agents.length === 0 ? (
          <div className={`p-[14px] text-[11px] font-mono ${error ? 'text-[#ef4444]' : 'text-[rgba(255,255,255,0.3)]'}`}>
            {error ? 'Agent P2P inaccessible' : 'En attente de données…'}
          </div>
        ) : (
          agents.map((a, i) => {
            const isActive = a.peer_id === activeId
            return (
              <button
                key={a.peer_id}
                onClick={() => onSelect(a.peer_id)}
                className={
                  isActive
                    ? 'flex w-full items-center gap-2 text-left px-[12px] py-[9px] text-[11px] font-mono cursor-pointer bg-[rgba(129,140,248,0.12)] border-l-2 border-l-[#818cf8] text-white'
                    : 'flex w-full items-center gap-2 text-left px-[12px] py-[9px] text-[11px] font-mono cursor-pointer bg-transparent border-l-2 border-l-transparent text-[rgba(255,255,255,0.55)]'
                }
              >
                <div className="w-[6px] h-[6px] rounded-full shrink-0" style={{ background: ownerColor(i) }} />
                <div className="min-w-0 flex-1">
                  <div className="overflow-hidden text-ellipsis whitespace-nowrap">{a.peer_name}</div>
                  <div className="text-[9px] text-[rgba(255,255,255,0.3)]">{i === 0 ? 'local' : 'rapporté'}</div>
                </div>
                <span className="text-[10px] font-bold shrink-0" style={{ color: tpsColor(a.tps) }}>{a.tps.toFixed(0)}</span>
              </button>
            )
          })
        )}
      </div>

      <div className="border border-[rgba(255,255,255,0.08)] rounded-[10px] overflow-hidden">
        <div className="px-[12px] py-[8px] border-b border-[rgba(255,255,255,0.06)] text-[10px] uppercase tracking-[0.8px] text-[rgba(255,255,255,0.35)]">
          Catégories
        </div>
        {TABS.map(t => {
          const isActive = t.id === tab
          return (
            <button
              key={t.id}
              onClick={() => onTabChange(t.id)}
              className={
                isActive
                  ? 'flex w-full items-center text-left px-[12px] py-[9px] text-[11px] font-semibold font-mono cursor-pointer bg-[rgba(129,140,248,0.12)] border-l-2 border-l-[#818cf8] text-white'
                  : 'flex w-full items-center text-left px-[12px] py-[9px] text-[11px] font-semibold font-mono cursor-pointer bg-transparent border-l-2 border-l-transparent text-[rgba(255,255,255,0.5)]'
              }
            >
              {t.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}
