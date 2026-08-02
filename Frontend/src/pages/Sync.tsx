import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { Instance, SyncInstance } from '@/types'
import { PremiumGate } from '@/components/ui/PremiumGate'
import { InstanceSyncCard } from '@/components/sync/InstanceSyncCard'
import { OrphanCloudCard } from '@/components/sync/OrphanCloudCard'
import { PageHeader } from '@/components/ui/PageHeader'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError, showApiError } from '@/stores/useErrorToast'
import { isNetworkError } from '@/lib/apiError'
import { SYNC_ENABLED } from '@/config/features'
import { useT } from '@/i18n'

// ── Sync content ──────────────────────────────────────────────────────────────

function SyncContent() {
  const t = useT()
  const { instances, yuyuToken, isUltimate, addInstance } = useStore()
  const userIsUltimate = isUltimate()
  const QUOTA_SAVES = userIsUltimate ? 10 : 3

  const [cloudInstances, setCloudInstances] = useState<SyncInstance[]>([])
  const [cloudLoading, setCloudLoading] = useState(false)
  const cloudLoaded = useRef(false)

  useEffect(() => {
    api.analytics.track('sync_page_viewed')
  }, [])

  useEffect(() => {
    if (!yuyuToken || cloudLoaded.current) return
    cloudLoaded.current = true
    setCloudLoading(true)
    api.sync.list()
      .then(setCloudInstances)
      // Chargement automatique au montage — une panne réseau est déjà
      // signalée par le badge de la TitleBar, pas la peine d'un toast en plus
      // à chaque visite de la page hors-ligne. Les vraies erreurs restent
      // remontées normalement.
      .catch((e) => { if (!isNetworkError(e)) showError(e) })
      .finally(() => setCloudLoading(false))
  }, [yuyuToken])

  const totalCloudSaves = cloudInstances.reduce((sum, ci) => sum + ci.save_count, 0)

  const maxSavesForInstance = (inst: Instance) => {
    const cloudEntry = cloudInstances.find((ci) => ci.instance_name === inst.name)
    const ownedSaves = cloudEntry?.save_count ?? 0
    return Math.max(0, Math.min(QUOTA_SAVES, QUOTA_SAVES - totalCloudSaves + ownedSaves))
  }

  const handleCloudUpdate = (updated: SyncInstance) => {
    setCloudInstances((prev) => {
      const idx = prev.findIndex((ci) => ci.id === updated.id)
      return idx >= 0
        ? prev.map((ci, i) => (i === idx ? updated : ci))
        : [updated, ...prev]
    })
  }

  const handleCloudDelete = (id: number) => {
    setCloudInstances((prev) => prev.filter((ci) => ci.id !== id))
  }

  /// Suppression d'une entrée cloud "orpheline" (sans instance locale) — appelle
  /// bien l'API avant de retirer l'entrée localement (bug corrigé : la version
  /// précédente ne faisait que la retirer du state, sans jamais la supprimer
  /// côté serveur, donc elle réapparaissait au rechargement).
  const handleOrphanDelete = async (id: number) => {
    try {
      await api.sync.delete(id)
      handleCloudDelete(id)
    } catch (e) { showApiError(e, t('common.serverUnreachable')) }
  }

  const handleRestore = async (ci: SyncInstance) => {
    try {
      const newInstance = await api.instances.create(ci.instance_name, ci.mc_version, ci.loader, ci.ram_mb)
      addInstance(newInstance)
      await api.sync.pull(ci.id, newInstance.id)
    } catch (e) { showApiError(e, t('common.serverUnreachable')) }
  }

  const orphanCloud = cloudInstances.filter(
    (ci) => !instances.some((i) => i.name === ci.instance_name)
  )

  if (cloudLoading) {
    return (
      <div className="flex justify-center py-8">
        <ButtonSpinner size={20} color="rgba(75,63,207,0.8)" trackColor="rgba(255,255,255,0.08)" />
      </div>
    )
  }

  if (instances.length === 0 && orphanCloud.length === 0) {
    return (
      <div className="flex flex-col items-center gap-2 py-8">
        <div className="text-[24px] opacity-[0.18]">🧱</div>
        <p className="text-[12px] text-[rgba(255,255,255,0.2)] text-center">
          {t('sync.createInstanceLine1')}<br />{t('sync.createInstanceLine2')}
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      {/* Local instances */}
      {instances.map((inst) => (
        <InstanceSyncCard
          key={inst.id}
          instance={inst}
          cloudEntry={cloudInstances.find((ci) => ci.instance_name === inst.name)}
          maxSaves={maxSavesForInstance(inst)}
          onCloudUpdate={handleCloudUpdate}
          onCloudDelete={handleCloudDelete}
        />
      ))}

      {/* Orphan cloud entries */}
      {orphanCloud.length > 0 && (
        <div className="flex flex-col gap-2 mt-2">
          <p className="text-[10px] font-bold text-[rgba(255,255,255,0.18)] tracking-[0.1em] uppercase">
            {t('sync.cloudNoLocalInstance')}
          </p>
          {orphanCloud.map((ci) => (
            <OrphanCloudCard
              key={ci.id}
              ci={ci}
              onRestore={handleRestore}
              onDelete={handleOrphanDelete}
            />
          ))}
        </div>
      )}

      <p className="text-[10px] text-[rgba(255,255,255,0.1)] text-center mt-1">
        {t('sync.quotaFooter', { quota: QUOTA_SAVES })}
      </p>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function Sync() {
  const t = useT()
  const navigate = useNavigate()
  const { yuyuToken, isPremium } = useStore()

  if (!SYNC_ENABLED) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-[#09090D]">
        <div className="text-[32px] opacity-[0.15]">
          <svg viewBox="0 0 24 24" fill="white" width={48} height={48}><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z" /></svg>
        </div>
        <p className="text-[14px] font-bold text-[rgba(255,255,255,0.5)]">{t('sync.comingSoon')}</p>
        <p className="text-[11px] text-[rgba(255,255,255,0.2)] text-center max-w-[280px]">
          {t('sync.comingSoonDesc')}
        </p>
        <button
          onClick={() => navigate('/home')}
          className="rounded-xl px-5 py-2 text-sm font-semibold transition-all duration-150 bg-[rgba(75,63,207,0.18)] border border-[rgba(75,63,207,0.35)] text-[rgba(180,170,255,0.9)] hover:bg-[rgba(75,63,207,0.3)]"
        >
          {t('sync.back')}
        </button>
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col bg-[#09090D] text-white">
      <PageHeader px={5}>
        <h1 className="font-black text-white text-[16px] tracking-[-0.01em]">
          {t('sync.title')}
        </h1>
      </PageHeader>

      <div className="flex-1 overflow-y-auto p-5">
        {!yuyuToken ? (
          <div className="flex flex-col items-center justify-center gap-3 py-10">
            <div className="text-[28px] opacity-20">🔒</div>
            <p className="text-[13px] text-[rgba(255,255,255,0.3)] font-semibold text-center">
              {t('sync.loginToSyncLine1')}<br />{t('sync.loginToSyncLine2')}
            </p>
            <button
              onClick={() => navigate('/yuyu')}
              className="rounded-xl px-5 py-2 text-sm font-semibold transition-all duration-150 bg-[rgba(75,63,207,0.18)] border border-[rgba(75,63,207,0.35)] text-[rgba(180,170,255,0.9)] hover:bg-[rgba(75,63,207,0.3)]"
            >
              {t('sync.loginCta')}
            </button>
          </div>
        ) : !isPremium() ? (
          <PremiumGate
            compact
            onUpgrade={() => navigate('/plans')}
            description={t('sync.gateDescription')}
            features={[
              t('sync.gateFeature1'),
              t('sync.gateFeature2'),
              t('sync.gateFeature3'),
            ]}
          />
        ) : (
          <SyncContent />
        )}
      </div>
    </div>
  )
}
