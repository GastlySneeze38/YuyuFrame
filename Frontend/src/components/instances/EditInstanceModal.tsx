import { useState } from 'react'
import { api } from '@/api/client'
import type { Instance, Loader } from '@/types'
import { updateModsForNewVersion } from '@/pages/Mods'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker } from '@/components/ui/RamPicker'
import { showError } from '@/stores/useErrorToast'
import { NameInput, DescriptionInput, SubmitButton, VersionSelect, LoaderPicker } from './InstanceFormFields'
import { useT } from '@/i18n'

export function EditInstanceModal({
  instance,
  versions,
  onClose,
  onUpdate,
}: {
  instance: Instance
  versions: string[]
  onClose: () => void
  onUpdate: (instance: Instance) => void
}) {
  const t = useT()
  const [name, setName] = useState(instance.name)
  const [description, setDescription] = useState(instance.description)
  const [mcVersion, setMcVersion] = useState(instance.mc_version)
  const [loader, setLoader] = useState<Loader>(instance.loader)
  const [ram, setRam] = useState(instance.ram_mb)
  const [loading, setLoading] = useState(false)
  const [loadingLabel, setLoadingLabel] = useState(t('instancesPage.saving'))

  const handleSave = async () => {
    if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
    setLoading(true); setLoadingLabel(t('instancesPage.saving'))
    try {
      const updated = await api.instances.update(instance.id, name.trim(), mcVersion, loader, ram, description.trim())
      if (mcVersion !== instance.mc_version) {
        setLoadingLabel(t('instancesPage.updatingMods'))
        await updateModsForNewVersion(instance.id, mcVersion, loader)
      }
      onUpdate(updated)
    } catch (e) {
      showError(e)
    } finally {
      setLoading(false)
    }
  }

  return (
    <ModalShell title={t('instancesPage.editInstance')} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <NameInput value={name} onChange={setName} onEnter={handleSave} />

        <VersionSelect versions={versions} value={mcVersion} onChange={setMcVersion} />
        <LoaderPicker value={loader} onChange={setLoader} />

        <RamPicker value={ram} onChange={setRam} />

        <DescriptionInput value={description} onChange={setDescription} />

        {mcVersion !== instance.mc_version && (
          <div className="flex items-center gap-2 rounded-xl px-3 py-2 bg-[rgba(75,63,207,0.08)] border border-[rgba(75,63,207,0.25)]">
            <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="text-[rgba(120,110,230,0.7)] flex-shrink-0">
              <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
            </svg>
            <p className="text-[11px] text-[rgba(255,255,255,0.45)]">
              {t('instancesPage.modsWillUpdatePrefix')} <span className="text-[rgba(120,110,230,0.9)] font-semibold">{mcVersion}</span>.
            </p>
          </div>
        )}

        <SubmitButton loading={loading} label={t('common.save')} loadingLabel={loadingLabel} onClick={handleSave} />
      </div>
    </ModalShell>
  )
}
