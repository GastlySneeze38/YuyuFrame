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

// ── Sync content ──────────────────────────────────────────────────────────────

function SyncContent() {
  const { instances, yuyuToken, isUltimate, addInstance } = useStore()
  const userIsUltimate = isUltimate()
  const QUOTA_SAVES = userIsUltimate ? 10 : 3

  const [cloudInstances, setCloudInstances] = useState<SyncInstance[]>([])
  const [cloudLoading, setCloudLoading] = useState(false)
  const [error, setError] = useState('')
  const cloudLoaded = useRef(false)

  useEffect(() => {
    if (!yuyuToken || cloudLoaded.current) return
    cloudLoaded.current = true
    setCloudLoading(true)
    api.sync.list()
      .then(setCloudInstances)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
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

  const handleRestore = async (ci: SyncInstance) => {
    try {
      const newInstance = await api.instances.create(ci.instance_name, ci.mc_version, ci.loader, ci.ram_mb)
      addInstance(newInstance)
      await api.sync.pull(ci.id, newInstance.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
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
        <div style={{ fontSize: 24, opacity: 0.18 }}>🧱</div>
        <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.2)', textAlign: 'center' }}>
          Crée une instance pour commencer<br />à synchroniser.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      {error && <p style={{ fontSize: 12, color: 'rgb(248,113,113)', paddingBottom: 4 }}>{error}</p>}

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
          <p style={{ fontSize: 10, fontWeight: 700, color: 'rgba(255,255,255,0.18)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>
            Dans le cloud · sans instance locale
          </p>
          {orphanCloud.map((ci) => (
            <OrphanCloudCard
              key={ci.id}
              ci={ci}
              onRestore={handleRestore}
              onDelete={async (id) => handleCloudDelete(id)}
            />
          ))}
        </div>
      )}

      <p style={{ fontSize: 10, color: 'rgba(255,255,255,0.1)', textAlign: 'center', marginTop: 4 }}>
        mods/ + config/ + saves sélectionnées · {QUOTA_SAVES} saves max · Premium
      </p>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function Sync() {
  const navigate = useNavigate()
  const { yuyuToken, isPremium, yuyuPlan } = useStore()

  const planLabel = yuyuPlan === 'ultimate' ? 'ULTIMATE' : 'PREMIUM'
  const planColor = yuyuPlan === 'ultimate'
    ? { color: '#f59e0b', bg: 'rgba(245,158,11,0.15)' }
    : { color: '#818cf8', bg: 'rgba(75,63,207,0.18)' }

  return (
    <div className="flex h-full flex-col" style={{ background: '#09090D', color: 'white' }}>
      <PageHeader px={5}>
        <h1 className="font-black text-white" style={{ fontSize: 16, letterSpacing: '-0.01em' }}>
          Synchronisation
        </h1>
        {yuyuToken && isPremium() && (
          <span style={{ fontSize: 10, fontWeight: 700, color: planColor.color, background: planColor.bg, padding: '2px 8px', borderRadius: 6, letterSpacing: '0.05em' }}>
            {planLabel}
          </span>
        )}
      </PageHeader>

      <div className="flex-1 overflow-y-auto p-5">
        {!yuyuToken ? (
          <div className="flex flex-col items-center justify-center gap-3 py-10">
            <div style={{ fontSize: 28, opacity: 0.2 }}>🔒</div>
            <p style={{ fontSize: 13, color: 'rgba(255,255,255,0.3)', fontWeight: 600, textAlign: 'center' }}>
              Connecte-toi à YuyuFrame<br />pour synchroniser tes instances
            </p>
          </div>
        ) : !isPremium() ? (
          <PremiumGate
            compact
            onUpgrade={() => navigate('/plans')}
            description="La synchronisation multi-PC est réservée aux abonnés Premium et Ultimate."
            features={[
              'Sync mods, configs & saves entre tes PCs',
              "Jusqu'à 3 saves cloud (10 en Ultimate)",
              'Détail de ce qui est sauvegardé par instance',
            ]}
          />
        ) : (
          <SyncContent />
        )}
      </div>
    </div>
  )
}
