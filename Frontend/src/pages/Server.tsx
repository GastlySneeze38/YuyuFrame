import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { BETA_TEST } from '@/config/beta'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { DebugPanel } from '@/components/server/DebugPanel'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'

export default function Server() {
  const navigate = useNavigate()
  const { selectedInstanceId, selectedInstance, isInstanceRunning, setInstanceRunning } = useStore()

  if (BETA_TEST) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4" style={{ background: '#09090D' }}>
        <div style={{ fontSize: 32, opacity: 0.15 }}>
          <svg viewBox="0 0 24 24" fill="white" width={48} height={48}><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
        </div>
        <p style={{ fontSize: 14, fontWeight: 700, color: 'rgba(255,255,255,0.5)' }}>P2P non disponible en bêta</p>
        <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.2)', textAlign: 'center', maxWidth: 280 }}>
          Le serveur P2P sera accessible dans une prochaine version.
        </p>
        <button
          onClick={() => navigate('/home')}
          className="rounded-xl px-5 py-2 text-sm font-semibold transition-all duration-150"
          style={{ background: 'rgba(75,63,207,0.18)', border: '1px solid rgba(75,63,207,0.35)', color: 'rgba(180,170,255,0.9)' }}
          onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(75,63,207,0.3)' }}
          onMouseLeave={(e) => { e.currentTarget.style.background = 'rgba(75,63,207,0.18)' }}
        >
          Retour
        </button>
      </div>
    )
  }

  const gameRunning = !!selectedInstanceId && isInstanceRunning(selectedInstanceId)
  const instance = selectedInstance()

  useTauriEvent<{ running: boolean; instance_id: string }>('game_state', (payload) => {
    setInstanceRunning(payload.instance_id, payload.running)
  })

  const [reloadStatus, setReloadStatus] = useState<'idle' | 'sent' | 'error'>('idle')

  const handleReloadAgent = async () => {
    try {
      await api.launch.reloadAgent()
      setReloadStatus('sent')
      setTimeout(() => setReloadStatus('idle'), 2500)
    } catch {
      setReloadStatus('error')
      setTimeout(() => setReloadStatus('idle'), 2500)
    }
  }

  return (
    <div className="flex h-full flex-col overflow-hidden" style={{ background: '#09090D' }}>

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="font-black text-white" style={{ fontSize: 16, letterSpacing: '-0.01em', lineHeight: 1.2 }}>
            Serveur P2P
          </h1>
          <p style={{ fontSize: 10, color: 'rgba(255,255,255,0.28)', marginTop: 1 }}>
            {instance ? (
              <span style={{ color: 'rgba(120,110,230,0.7)', fontWeight: 600 }}>{instance.name} — {instance.mc_version}</span>
            ) : (
              <span style={{ color: 'rgba(255,100,100,0.6)' }}>Aucune instance sélectionnée</span>
            )}
          </p>
        </div>

        {gameRunning && (
          <div className="ml-auto flex flex-col items-end gap-1">
            <button
              onClick={handleReloadAgent}
              disabled={reloadStatus !== 'idle'}
              style={{
                height: 30, padding: '0 14px', borderRadius: 8, fontSize: 11, fontWeight: 600,
                letterSpacing: '0.03em', cursor: reloadStatus !== 'idle' ? 'default' : 'pointer',
                background: reloadStatus === 'sent'
                  ? 'rgba(40,160,90,0.18)'
                  : reloadStatus === 'error'
                    ? 'rgba(200,50,50,0.18)'
                    : 'rgba(255,255,255,0.04)',
                border: reloadStatus === 'sent'
                  ? '1px solid rgba(40,160,90,0.35)'
                  : reloadStatus === 'error'
                    ? '1px solid rgba(200,50,50,0.35)'
                    : '1px solid rgba(255,255,255,0.08)',
                color: reloadStatus === 'sent'
                  ? 'rgba(80,210,130,0.9)'
                  : reloadStatus === 'error'
                    ? 'rgba(255,100,100,0.9)'
                    : 'rgba(255,255,255,0.45)',
                transition: 'all 0.2s',
              }}
              onMouseEnter={(e) => {
                if (reloadStatus === 'idle') (e.currentTarget as HTMLElement).style.color = 'rgba(255,255,255,0.7)'
              }}
              onMouseLeave={(e) => {
                if (reloadStatus === 'idle') (e.currentTarget as HTMLElement).style.color = 'rgba(255,255,255,0.45)'
              }}
            >
              {reloadStatus === 'sent'
                ? 'Rechargement envoyé ✓'
                : reloadStatus === 'error'
                  ? 'Erreur d’écriture'
                  : 'Recharger l’agent P2P'}
            </button>
            <p style={{ fontSize: 9, color: 'rgba(255,255,255,0.18)' }}>
              Relit p2p-agent.properties sans relancer Minecraft
            </p>
          </div>
        )}
      </PageHeader>

      {/* Content — pleine largeur, style tableau de bord (liste de serveurs + détail) */}
      <div className="flex-1 overflow-auto">
        <div className="w-full px-6 py-6">
          <DebugPanel />
        </div>
      </div>
    </div>
  )
}
