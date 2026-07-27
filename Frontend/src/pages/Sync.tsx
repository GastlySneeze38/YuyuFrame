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
import { showError } from '@/stores/useErrorToast'
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
      .catch(showError)
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
    } catch (e) { showError(e) }
  }

  const handleRestore = async (ci: SyncInstance) => {
    try {
      const newInstance = await api.instances.create(ci.instance_name, ci.mc_version, ci.loader, ci.ram_mb)
      addInstance(newInstance)
      await api.sync.pull(ci.id, newInstance.id)
    } catch (e) { showError(e) }
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
  const { yuyuToken, isPremium, yuyuPlan } = useStore()

  const planLabel = yuyuPlan === 'ultimate' ? 'ULTIMATE' : 'PREMIUM'

  return (
    <div className="flex h-full flex-col bg-[#09090D] text-white">
      <PageHeader px={5}>
        <h1 className="font-black text-white text-[16px] tracking-[-0.01em]">
          {t('sync.title')}
        </h1>
        {yuyuToken && isPremium() && (
          <span
            className={
              yuyuPlan === 'ultimate'
                ? 'text-[10px] font-bold text-[#f59e0b] bg-[rgba(245,158,11,0.15)] px-2 py-0.5 rounded-md tracking-[0.05em]'
                : 'text-[10px] font-bold text-[#818cf8] bg-[rgba(75,63,207,0.18)] px-2 py-0.5 rounded-md tracking-[0.05em]'
            }
          >
            {planLabel}
          </span>
        )}
      </PageHeader>

      <div className="flex-1 overflow-y-auto p-5">
        {!yuyuToken ? (
          <div className="flex flex-col items-center justify-center gap-3 py-10">
            <div className="text-[28px] opacity-20">🔒</div>
            <p className="text-[13px] text-[rgba(255,255,255,0.3)] font-semibold text-center">
              {t('sync.loginToSyncLine1')}<br />{t('sync.loginToSyncLine2')}
            </p>
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
