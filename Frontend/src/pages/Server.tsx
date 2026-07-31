import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { P2P_ENABLED } from '@/config/features'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { DebugPanel } from '@/components/server/DebugPanel'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { useT } from '@/i18n'

export default function Server() {
  const t = useT()
  const navigate = useNavigate()
  const { selectedInstanceId, selectedInstance, isInstanceRunning, setInstanceRunning } = useStore()

  if (!P2P_ENABLED) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-[#09090D]">
        <div className="text-[32px] opacity-[0.15]">
          <svg viewBox="0 0 24 24" fill="white" width={48} height={48}><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
        </div>
        <p className="text-[14px] font-bold text-[rgba(255,255,255,0.5)]">{t('server.comingSoon')}</p>
        <p className="text-[11px] text-[rgba(255,255,255,0.2)] text-center max-w-[280px]">
          {t('server.comingSoonDesc')}
        </p>
        <button
          onClick={() => navigate('/home')}
          className="rounded-xl px-5 py-2 text-sm font-semibold transition-all duration-150 bg-[rgba(75,63,207,0.18)] border border-[rgba(75,63,207,0.35)] text-[rgba(180,170,255,0.9)] hover:bg-[rgba(75,63,207,0.3)]"
        >
          {t('server.back')}
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
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="font-black text-white text-[16px] tracking-[-0.01em] leading-[1.2]">
            {t('server.title')}
          </h1>
          <p className="text-[10px] text-[rgba(255,255,255,0.28)] mt-[1px]">
            {instance ? (
              <span className="text-[rgba(120,110,230,0.7)] font-semibold">{instance.name} — {instance.mc_version}</span>
            ) : (
              <span className="text-[rgba(255,100,100,0.6)]">{t('server.noInstanceSelected')}</span>
            )}
          </p>
        </div>

        {gameRunning && (
          <div className="ml-auto flex flex-col items-end gap-1">
            <button
              onClick={handleReloadAgent}
              disabled={reloadStatus !== 'idle'}
              className={
                reloadStatus === 'sent'
                  ? 'h-[30px] px-[14px] rounded-lg text-[11px] font-semibold tracking-[0.03em] transition-all duration-200 cursor-pointer disabled:cursor-default bg-[rgba(40,160,90,0.18)] border border-[rgba(40,160,90,0.35)] text-[rgba(80,210,130,0.9)]'
                  : reloadStatus === 'error'
                    ? 'h-[30px] px-[14px] rounded-lg text-[11px] font-semibold tracking-[0.03em] transition-all duration-200 cursor-pointer disabled:cursor-default bg-[rgba(200,50,50,0.18)] border border-[rgba(200,50,50,0.35)] text-[rgba(255,100,100,0.9)]'
                    : 'h-[30px] px-[14px] rounded-lg text-[11px] font-semibold tracking-[0.03em] transition-all duration-200 cursor-pointer disabled:cursor-default bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.45)] hover:text-[rgba(255,255,255,0.7)]'
              }
            >
              {reloadStatus === 'sent'
                ? t('server.reloadSent')
                : reloadStatus === 'error'
                  ? t('server.reloadError')
                  : t('server.reloadAgent')}
            </button>
            <p className="text-[9px] text-[rgba(255,255,255,0.18)]">
              {t('server.reloadHint')}
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
