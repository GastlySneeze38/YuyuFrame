import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { Instance } from '@/types'
import { ModsContent } from '@/pages/Mods'
import { ImportSourceModal } from '@/components/import/ImportSourceModal'
import { InstanceCard } from '@/components/instances/InstanceCard'
import { CreateInstanceModal } from '@/components/instances/CreateInstanceModal'
import { EditInstanceModal } from '@/components/instances/EditInstanceModal'
import { DuplicateInstanceModal } from '@/components/instances/DuplicateInstanceModal'
import { PageHeader } from '@/components/ui/PageHeader'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError } from '@/stores/useErrorToast'

export default function Instances() {
  const {
    versions, setVersions,
    instances, setInstances, addInstance, updateInstance, removeInstance,
    selectedInstanceId, setSelectedInstanceId,
    defaultRam, syncGameSettings,
  } = useStore()

  const [loading, setLoading] = useState(true)
  const [showCreate, setShowCreate] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [editTarget, setEditTarget] = useState<Instance | null>(null)
  const [duplicateSource, setDuplicateSource] = useState<Instance | null>(null)
  const [othersExpanded, setOthersExpanded] = useState(true)
  const loaded = useRef(false)

  const selectedInstance = instances.find((i) => i.id === selectedInstanceId) ?? null
  const favorites = instances.filter((i) => i.favorite)
  const others = instances.filter((i) => !i.favorite)

  useEffect(() => {
    if (loaded.current) return
    loaded.current = true

    Promise.all([
      api.instances.list(),
      versions.length === 0 ? api.versions.list() : Promise.resolve(null),
    ]).then(([insts, vers]) => {
      setInstances(insts)
      if (vers) setVersions(vers)
      if (insts.some((i) => i.favorite)) setOthersExpanded(false)
    }).finally(() => setLoading(false))
  }, [])

  const releaseVersions = versions
    .filter((v) => v.version_type === 'release')
    .map((v) => v.id)

  const handleDelete = useCallback(async (id: string) => {
    try {
      await api.instances.delete(id)
      removeInstance(id)
    } catch (e) { showError(e) }
  }, [removeInstance])

  const handleToggleFavorite = useCallback(async (id: string) => {
    try {
      const updated = await api.instances.toggleFavorite(id)
      updateInstance(updated)
    } catch (e) { showError(e) }
  }, [updateInstance])

  function renderCard(inst: Instance) {
    return (
      <InstanceCard
        key={inst.id}
        instance={inst}
        selected={inst.id === selectedInstanceId}
        onSelect={setSelectedInstanceId}
        onToggleFavorite={handleToggleFavorite}
        onDelete={handleDelete}
        onEdit={setEditTarget}
        onDuplicate={setDuplicateSource}
      />
    )
  }

  return (
    <div className="flex h-full flex-col" style={{ background: '#09090D', color: 'white' }}>

      <PageHeader px={5}>
        <h1 className="font-black text-white" style={{ fontSize: 16, letterSpacing: '-0.01em' }}>Instances</h1>
      </PageHeader>

      {/* Body: sidebar + mods panel */}
      <div className="flex flex-1 overflow-hidden">

        {/* Left sidebar — instance list */}
        <div
          className="flex flex-col overflow-hidden"
          style={{ width: '22%', minWidth: 320, flexShrink: 0, borderRight: '1px solid rgba(255,255,255,0.06)' }}
        >
          {/* Scrollable list */}
          <div className="flex flex-1 flex-col overflow-y-auto p-3">
            {loading ? (
              <div className="flex h-40 items-center justify-center">
                <ButtonSpinner size={28} color="rgba(75,63,207,0.8)" trackColor="rgba(255,255,255,0.08)" />
              </div>
            ) : instances.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center gap-2">
                <div style={{ fontSize: 32 }}>🧱</div>
                <p style={{ fontSize: 13, color: 'rgba(255,255,255,0.3)', fontWeight: 600, textAlign: 'center' }}>Aucune instance</p>
              </div>
            ) : (
              <>
                {favorites.length > 0 && (
                  <div className="mb-1">
                    <p className="px-1 pb-1.5 text-xs font-semibold" style={{ color: '#facc15', letterSpacing: '0.08em', textTransform: 'uppercase' }}>
                      ★ Favoris
                    </p>
                    <div className="flex flex-col gap-2">
                      {favorites.map(renderCard)}
                    </div>
                  </div>
                )}

                {others.length > 0 && (
                  <div>
                    <button
                      onClick={() => setOthersExpanded((v) => !v)}
                      className="flex w-full items-center gap-1.5 px-1 pb-1.5"
                    >
                      <svg
                        viewBox="0 0 24 24" fill="currentColor" width={10} height={10}
                        style={{ color: 'rgba(255,255,255,0.3)', transition: 'transform 0.15s', transform: othersExpanded ? 'rotate(90deg)' : 'rotate(0deg)' }}
                      >
                        <path d="M8 5v14l11-7z" />
                      </svg>
                      <p className="text-xs font-semibold" style={{ color: 'rgba(255,255,255,0.3)', letterSpacing: '0.08em', textTransform: 'uppercase' }}>
                        Autres ({others.length})
                      </p>
                    </button>
                    {othersExpanded && (
                      <div className="flex flex-col gap-2">
                        {others.map(renderCard)}
                      </div>
                    )}
                  </div>
                )}
              </>
            )}
          </div>

          {/* Fixed bottom button */}
          <div className="flex-shrink-0 flex flex-col gap-2 p-3" style={{ borderTop: '1px solid rgba(255,255,255,0.06)' }}>
            <button
              onClick={() => setShowCreate(true)}
              className="w-full flex items-center justify-center gap-2 font-bold text-white transition-all duration-200 active:scale-95"
              style={{ height: 44, borderRadius: 12, fontSize: 13, background: '#4B3FCF', boxShadow: '0 4px 20px rgba(75,63,207,0.3)' }}
              onMouseEnter={(e) => { e.currentTarget.style.background = '#6155e8' }}
              onMouseLeave={(e) => { e.currentTarget.style.background = '#4B3FCF' }}
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}>
                <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
              </svg>
              Nouvelle instance
            </button>
            <button
              onClick={() => setShowImport(true)}
              className="w-full flex items-center justify-center gap-2 font-semibold transition-all duration-200 active:scale-95"
              style={{ height: 38, borderRadius: 12, fontSize: 12, background: 'rgba(255,255,255,0.05)', color: 'rgba(255,255,255,0.6)' }}
              onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,0.1)' }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,0.05)' }}
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}>
                <path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" />
              </svg>
              Importer une instance
            </button>
          </div>
        </div>

        {/* Right panel — mods */}
        <div className="flex flex-1 flex-col overflow-hidden">
          {selectedInstance ? (
            <ModsContent key={`${selectedInstance.id}-${selectedInstance.mc_version}`} instance={selectedInstance} />
          ) : (
            <div className="flex flex-1 flex-col items-center justify-center gap-3">
              <div style={{ fontSize: 32, opacity: 0.4 }}>←</div>
              <p style={{ fontSize: 14, color: 'rgba(255,255,255,0.3)', fontWeight: 600 }}>
                Sélectionne une instance
              </p>
              <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.15)' }}>
                Les mods s'afficheront ici
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Modals */}
      {showCreate && (
        <CreateInstanceModal
          versions={releaseVersions}
          defaultRam={defaultRam}
          onClose={() => setShowCreate(false)}
          onCreate={(inst) => {
            addInstance(inst)
            setSelectedInstanceId(inst.id)
            if (syncGameSettings) api.instances.applySettings(inst.id).catch(() => {})
          }}
        />
      )}

      {editTarget && (
        <EditInstanceModal
          instance={editTarget}
          versions={releaseVersions}
          onClose={() => setEditTarget(null)}
          onUpdate={(updated) => {
            updateInstance(updated)
            setEditTarget(null)
          }}
        />
      )}

      {duplicateSource && (
        <DuplicateInstanceModal
          source={duplicateSource}
          versions={releaseVersions}
          onClose={() => setDuplicateSource(null)}
          onDuplicate={(inst) => {
            addInstance(inst)
            setSelectedInstanceId(inst.id)
            setDuplicateSource(null)
          }}
        />
      )}

      {showImport && (
        <ImportSourceModal
          onClose={() => setShowImport(false)}
          onImported={(instanceId) => {
            api.instances.list().then(setInstances)
            setSelectedInstanceId(instanceId)
          }}
        />
      )}
    </div>
  )
}
