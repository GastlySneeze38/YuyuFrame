import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { Instance } from '@/types'
import { updateModsForNewVersion } from '@/pages/Mods'
import { loaderColor } from '@/lib/loader'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker, type RamStatus } from '@/components/ui/RamPicker'
import { showError } from '@/stores/useErrorToast'
import { NameInput, SubmitButton, VersionSelect } from './InstanceFormFields'
import { useT } from '@/i18n'

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
  const t = useT()
  const [name, setName] = useState(t('instancesPage.copyOf', { name: source.name }))
  const [mcVersion, setMcVersion] = useState(source.mc_version)
  const [ram, setRam] = useState(source.ram_mb)
  const [loading, setLoading] = useState(false)
  const [loadingLabel, setLoadingLabel] = useState(t('instancesPage.duplicating'))
  // Nombre de mods de l'instance source — la copie les emporte tous, la
  // recommandation RAM doit donc en tenir compte dès l'ouverture de la modal.
  const [modCount, setModCount] = useState(0)
  useEffect(() => {
    api.mods.list(source.id).then((mods) => setModCount(mods.length)).catch(() => {})
  }, [source.id])
  // Purement informatif ici (pas de blocage) : contrairement à la création,
  // cette RAM existait déjà sur l'instance source — un avertissement suffit.
  const [ramStatus, setRamStatus] = useState<RamStatus>({ isKnownTier: true, isRecommended: true })

  const handleDuplicate = async () => {
    if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
    setLoading(true); setLoadingLabel(t('instancesPage.duplicating'))
    try {
      const instance = await api.instances.duplicate(source.id, name.trim(), mcVersion, ram)
      if (mcVersion !== source.mc_version) {
        setLoadingLabel(t('instancesPage.updatingMods'))
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
    <ModalShell title={t('instancesPage.duplicateInstance')} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <NameInput value={name} onChange={setName} onEnter={handleDuplicate} />

        <VersionSelect versions={versions} value={mcVersion} onChange={setMcVersion} />

        <RamPicker value={ram} onChange={setRam} loader={source.loader} modCount={modCount} onStatusChange={setRamStatus} />

        <div className="flex items-center gap-2 rounded-xl px-3 py-2 bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.06)]">
          <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="text-[rgba(255,255,255,0.3)] flex-shrink-0">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
          </svg>
          <p className="text-[11px] text-[rgba(255,255,255,0.35)]">
            {t('instancesPage.loaderKeptPrefix')} <span className="font-semibold" style={{ color: loaderColor(source.loader) }}>{source.loader}</span> {t('instancesPage.loaderKeptSuffix')}{' '}
            {mcVersion !== source.mc_version
              ? <>{t('instancesPage.modsWillUpdateForLower')} <span className="text-[rgba(120,110,230,0.9)] font-semibold">{mcVersion}</span>.</>
              : t('instancesPage.modsWillCopy')}
          </p>
        </div>

        {ramStatus.isKnownTier && !ramStatus.isRecommended && (
          <p className="text-[11px] text-[rgba(240,180,90,0.6)] -mt-2">⚠ {t('instancesPage.ramNotOptimal')}</p>
        )}

        <SubmitButton loading={loading} label={t('instancesPage.duplicateButton')} loadingLabel={loadingLabel} onClick={handleDuplicate} />
      </div>
    </ModalShell>
  )
}
