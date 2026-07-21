import { useState } from 'react'
import { api } from '@/api/client'
import type { Instance } from '@/types'
import { updateModsForNewVersion } from '@/pages/Mods'
import { loaderColor } from '@/lib/loader'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker } from '@/components/ui/RamPicker'
import { showError } from '@/stores/useErrorToast'
import { NameInput, SubmitButton, VersionSelect } from './InstanceFormFields'

export function DuplicateInstanceModal({
  source,
  versions,
  onClose,
  onDuplicate,
}: {
  source: Instance
  versions: string[]
  onClose: () => void
  onDuplicate: (instance: Instance) => void
}) {
  const [name, setName] = useState(`Copie de ${source.name}`)
  const [mcVersion, setMcVersion] = useState(source.mc_version)
  const [ram, setRam] = useState(source.ram_mb)
  const [loading, setLoading] = useState(false)
  const [loadingLabel, setLoadingLabel] = useState('Duplication...')

  const handleDuplicate = async () => {
    if (!name.trim()) { showError('Nom requis'); return }
    setLoading(true); setLoadingLabel('Duplication...')
    try {
      const instance = await api.instances.duplicate(source.id, name.trim(), mcVersion, ram)
      if (mcVersion !== source.mc_version) {
        setLoadingLabel('Mise à jour des mods...')
        await updateModsForNewVersion(instance.id, mcVersion, source.loader)
      }
      onDuplicate(instance)
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setLoading(false)
    }
  }

  return (
    <ModalShell title="Dupliquer l'instance" onClose={onClose}>
      <div className="flex flex-col gap-4">
        <NameInput value={name} onChange={setName} onEnter={handleDuplicate} />

        <VersionSelect versions={versions} value={mcVersion} onChange={setMcVersion} />

        <RamPicker value={ram} onChange={setRam} />

        <div className="flex items-center gap-2 rounded-xl px-3 py-2 bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.06)]">
          <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="text-[rgba(255,255,255,0.3)] flex-shrink-0">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
          </svg>
          <p className="text-[11px] text-[rgba(255,255,255,0.35)]">
            Loader <span className="font-semibold" style={{ color: loaderColor(source.loader) }}>{source.loader}</span> conservé —{' '}
            {mcVersion !== source.mc_version
              ? <>les mods compatibles seront mis à jour pour <span className="text-[rgba(120,110,230,0.9)] font-semibold">{mcVersion}</span>.</>
              : 'les mods seront copiés.'}
          </p>
        </div>

        <SubmitButton loading={loading} label="Dupliquer" loadingLabel={loadingLabel} onClick={handleDuplicate} />
      </div>
    </ModalShell>
  )
}
