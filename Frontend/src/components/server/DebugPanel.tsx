import { useEffect, useState } from 'react'
import type { OwnershipData, TabId } from './types'
import { OWNERSHIP_API, POLL_MS } from './utils'
import { ServerSidebar } from './ServerSidebar'
import { AgentDetail } from './AgentDetail'
import { useT } from '@/i18n'

export function DebugPanel() {
  const t = useT()
  const [data, setData] = useState<OwnershipData | null>(null)
  const [error, setError] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [tab, setTab] = useState<TabId>('apercu')

  // Polling — un seul agent local (propriétaire du port 3849) répond ; les autres
  // agents locaux (test multi-instance sur la même machine) lui poussent leurs stats
  // via POST /report, agrégées ici dans peers_reported. Suspendu quand la fenêtre
  // est en arrière-plan (minimisée/masquée) pour ne pas fetch inutilement.
  useEffect(() => {
    let alive = true
    async function poll() {
      while (alive) {
        if (!document.hidden) {
          try {
            const res = await fetch(OWNERSHIP_API)
            if (res.ok) {
              const json: OwnershipData = await res.json()
              setData(json)
              setError(false)
            }
          } catch {
            setError(true)
          }
        }
        await new Promise(r => setTimeout(r, POLL_MS))
      }
    }
    poll()
    return () => { alive = false }
  }, [])

  const agents = data ? [data, ...(data.peers_reported ?? [])] : []
  const activeId = selected ?? agents[0]?.peer_id ?? null
  const active = agents.find(a => a.peer_id === activeId) ?? null
  const isLocal = active === agents[0]

  return (
    <div className="flex w-full gap-4">
      <ServerSidebar agents={agents} error={error} activeId={activeId} onSelect={setSelected} tab={tab} onTabChange={setTab} />
      <div className="flex-1 min-w-0">
        <AgentDetail
          data={active}
          error={error && agents.length === 0}
          label={isLocal ? t('server.localAgentLabel') : t('server.reportedAgentLabel')}
          showPeers={isLocal}
          tab={tab}
        />
      </div>
    </div>
  )
}
