import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
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
import { useT } from '@/i18n'

export default function Instances() {
  const t = useT()
  const navigate = useNavigate()
  const {
    versions, setVersions,
    instances, setInstances, addInstance, updateInstance, removeInstance,
    selectedInstanceId, setSelectedInstanceId,
    defaultRam, defaultJvmVendor, defaultJvmCustomPath, defaultGcPolicy, syncGameSettings,
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
    }).catch(showError).finally(() => setLoading(false))
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

  const handleOpenFolder = useCallback((inst: Instance) => {
    api.instances.openFolder(inst.id).catch(showError)
  }, [])

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
        onOpenFolder={handleOpenFolder}
      />
    )
  }

  return (
    <div className="flex h-full flex-col bg-[#09090D] text-white">

      <PageHeader px={5}>
        <h1 className="font-black text-white text-[16px] tracking-[-0.01em]">{t('instancesPage.title')}</h1>
        <div className="flex-1" />
        {/* Seul point d'entrée vers la bibliothèque de configs en dehors de la
            modal d'édition — les configs sont transverses aux instances, elles
            n'appartiennent à aucune en particulier. */}
        <button
          onClick={() => navigate('/jvm')}
          className="h-[28px] flex-shrink-0 rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.06)] px-2.5 text-[11px] font-semibold text-[rgba(255,255,255,0.6)] transition-colors hover:border-white/25 hover:text-[rgba(255,255,255,0.85)]"
        >
          Configurations JVM
        </button>
      </PageHeader>

      {/* Body: sidebar + mods panel */}
      <div className="flex flex-1 overflow-hidden">

        {/* Left sidebar — instance list */}
        <div
          className="flex flex-col overflow-hidden w-[22%] min-w-[240px] max-w-[320px] flex-shrink-0 border-r border-[rgba(255,255,255,0.06)]"
        >
          {/* Scrollable list */}
          <div className="flex flex-1 flex-col overflow-y-auto p-3">
            {loading ? (
              <div className="flex h-40 items-center justify-center">
                <ButtonSpinner size={28} color="rgba(75,63,207,0.8)" trackColor="rgba(255,255,255,0.08)" />
              </div>
            ) : instances.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center gap-2">
                <div className="text-[32px]">🧱</div>
                <p className="text-[13px] text-[rgba(255,255,255,0.3)] font-semibold text-center">{t('instancesPage.noInstance')}</p>
              </div>
            ) : (
              <>
                {favorites.length > 0 && (
                  <div className="mb-1">
                    <p className="px-1 pb-1.5 text-xs font-semibold text-[#facc15] tracking-[0.08em] uppercase">
                      ★ {t('instancesPage.favorites')}
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
                        className={`text-[rgba(255,255,255,0.3)] transition-transform duration-150 ${othersExpanded ? 'rotate-90' : 'rotate-0'}`}
                      >
                        <path d="M8 5v14l11-7z" />
                      </svg>
                      <p className="text-xs font-semibold text-[rgba(255,255,255,0.3)] tracking-[0.08em] uppercase">
                        {t('instancesPage.others', { count: others.length })}
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
          <div className="flex-shrink-0 flex flex-col gap-2 p-3 border-t border-[rgba(255,255,255,0.06)]">
            <button
              onClick={() => setShowCreate(true)}
              className="w-full flex items-center justify-center gap-2 font-bold text-white transition-all duration-200 active:scale-95 h-[44px] rounded-xl text-[13px] bg-[#4B3FCF] shadow-[0_4px_20px_rgba(75,63,207,0.3)] hover:bg-[#6155e8]"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={15} height={15}>
                <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
              </svg>
              {t('instancesPage.newInstance')}
            </button>
            <button
              onClick={() => setShowImport(true)}
              className="w-full flex items-center justify-center gap-2 font-semibold transition-all duration-200 active:scale-95 h-[38px] rounded-xl text-[12px] bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.6)] hover:bg-[rgba(255,255,255,0.1)]"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}>
                <path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" />
              </svg>
              {t('instancesPage.importInstance')}
            </button>
          </div>
        </div>

        {/* Right panel — mods */}
        <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
          {selectedInstance ? (
            <ModsContent key={`${selectedInstance.id}-${selectedInstance.mc_version}`} instance={selectedInstance} />
          ) : (
            <div className="flex flex-1 flex-col items-center justify-center gap-3">
              <div className="text-[32px] opacity-40">←</div>
              <p className="text-[14px] text-[rgba(255,255,255,0.3)] font-semibold">
                {t('instancesPage.selectInstance')}
              </p>
              <p className="text-[12px] text-[rgba(255,255,255,0.15)]">
                {t('instancesPage.modsWillAppearHere')}
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
          defaultJvmVendor={defaultJvmVendor}
          defaultJvmCustomPath={defaultJvmCustomPath}
          defaultGcPolicy={defaultGcPolicy}
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
            api.instances.list().then(setInstances).catch(showError)
            setSelectedInstanceId(instanceId)
          }}
        />
      )}
    </div>
  )
}
