import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ModalShell } from '@/components/ui/ModalShell'
import { EmptyState } from '@/components/ui/EmptyState'
import { loaderColor } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { useStore } from '@/stores/useStore'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'

export interface JoinRequest {
  ip: string
  mcVersion: string
  loader: string
}

/** Ouverte quand on arrive via un lien yuyuframe://join?... (bouton "Rejoindre"
 * de la Rich Presence Discord d'un ami, voir discord.rs::build_join_url) —
 * ne propose que les instances déjà compatibles (même version + loader) ;
 * pas de création automatique pour cette première version du funnel (voir
 * discussion — étape volontairement laissée de côté pour l'instant). */
export function JoinServerModal({ request, onClose }: { request: JoinRequest; onClose: () => void }) {
  const navigate = useNavigate()
  const { instances, avoidBetaDependencies, showConsole, setSelectedInstanceId, setInstanceRunning, isAgentEnabled } = useStore()
  const [launchingId, setLaunchingId] = useState<string | null>(null)

  const compatible = instances.filter(
    (i) => i.mc_version === request.mcVersion && i.loader === request.loader,
  )

  const handleJoin = async (instanceId: string) => {
    setLaunchingId(instanceId)
    try {
      // Le choix « avec / sans le client intégré » suit l'instance, quel que
      // soit l'endroit d'où on la lance (voir AgentModal).
      await api.launch.start(instanceId, avoidBetaDependencies, showConsole, request.ip, isAgentEnabled(instanceId))
      setSelectedInstanceId(instanceId)
      setInstanceRunning(instanceId, true)
      navigate('/home')
      onClose()
    } catch (e) {
      showError(e)
      setLaunchingId(null)
    }
  }

  return (
    <ModalShell title="Rejoindre un ami" onClose={onClose} maxWidth="max-w-lg">
      <p className="text-[12px] text-[rgba(255,255,255,0.4)] -mt-2">
        Minecraft {request.mcVersion} ({request.loader}) — {request.ip}
      </p>

      {compatible.length === 0 ? (
        <EmptyState
          icon={
            <svg viewBox="0 0 24 24" fill="none" stroke="rgba(255,255,255,0.2)" strokeWidth={1.5} width={26} height={26}>
              <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
            </svg>
          }
          title="Aucune instance compatible"
          subtitle={`Crée d'abord une instance ${request.mcVersion} (${request.loader}) pour rejoindre ce serveur`}
        />
      ) : (
        <div className="flex flex-col gap-2 max-h-[50vh] overflow-y-auto pr-1">
          {compatible.map((inst) => (
            <button
              key={inst.id}
              onClick={() => handleJoin(inst.id)}
              disabled={launchingId !== null}
              className="flex items-center justify-between gap-3 rounded-2xl px-4 py-3 text-left transition-all duration-150 border bg-[rgba(255,255,255,0.03)] border-[rgba(255,255,255,0.07)] hover:bg-[rgba(75,63,207,0.12)] hover:border-[rgba(75,63,207,0.4)] disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <div className="min-w-0 flex flex-col gap-1">
                <p className="font-bold truncate text-[13px] text-white">{inst.name}</p>
                <div className="flex items-center gap-1.5">
                  <span className="text-[10px] font-semibold" style={{ color: loaderColor(inst.loader) }}>{inst.loader}</span>
                  <span className="text-[10px] text-[rgba(255,255,255,0.2)]">·</span>
                  <span className="text-[10px] text-[rgba(255,255,255,0.3)]">{inst.mc_version}</span>
                  <span className="text-[10px] text-[rgba(255,255,255,0.2)]">·</span>
                  <span className="text-[10px] text-[rgba(255,255,255,0.3)]">{formatRam(inst.ram_mb)}</span>
                </div>
              </div>
              <span className="flex-shrink-0 rounded-xl px-3 py-1.5 text-[11px] font-semibold text-white bg-[#4B3FCF]">
                {launchingId === inst.id ? '...' : 'Rejoindre'}
              </span>
            </button>
          ))}
        </div>
      )}
    </ModalShell>
  )
}
