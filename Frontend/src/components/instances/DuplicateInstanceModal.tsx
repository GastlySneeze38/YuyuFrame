import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { Instance } from '@/types'
import { updateModsForNewVersion } from '@/pages/Mods'
import { loaderColor } from '@/lib/loader'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker, type RamStatus } from '@/components/ui/RamPicker'
import { showError } from '@/stores/useErrorToast'
import { allowedVersions, useGameVersionsFor } from '@/lib/loaderCompat'
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

  const allowed = useGameVersionsFor(source.loader)
  const offered = allowedVersions(versions, allowed, source.mc_version)

  /**
   * Mettre à jour les mods pour la nouvelle version, ou les copier tels quels.
   *
   * C'était automatique et muet : changer la version relançait la recherche
   * d'une version compatible de chaque mod. Ça vaut la plupart du temps, mais
   * pas toujours — on duplique aussi pour garder un état connu qui marche, et
   * repartir de mods figés. Le choix est donc posé, et il se pose seulement
   * quand la version change (sinon il n'y a rien à mettre à jour).
   */
  const [updateMods, setUpdateMods] = useState(true)
  const versionChanged = mcVersion !== source.mc_version

  const handleDuplicate = async () => {
    if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
    setLoading(true); setLoadingLabel(t('instancesPage.duplicating'))
    try {
      const instance = await api.instances.duplicate(source.id, name.trim(), mcVersion, ram)
      if (versionChanged && updateMods) {
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

        {/* Le loader de la source est conservé, donc c'est la liste des
            versions qui se réduit : proposer une version que ce loader ne
            connaît pas ne mènerait qu'à un lancement raté. La version
            d'origine reste proposée même si le loader ne la publie plus. */}
        <VersionSelect versions={offered} value={mcVersion} onChange={setMcVersion} />

        <RamPicker value={ram} onChange={setRam} loader={source.loader} modCount={modCount} onStatusChange={setRamStatus} />

        <div className="flex items-center gap-2 rounded-xl px-3 py-2 bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.06)]">
          <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="text-[rgba(255,255,255,0.3)] flex-shrink-0">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
          </svg>
          <p className="text-[11px] text-[rgba(255,255,255,0.35)]">
            {t('instancesPage.loaderKeptPrefix')} <span className="font-semibold" style={{ color: loaderColor(source.loader) }}>{source.loader}</span> {t('instancesPage.loaderKeptSuffix')}{' '}
            {versionChanged && updateMods
              ? <>{t('instancesPage.modsWillUpdateForLower')} <span className="text-[rgba(120,110,230,0.9)] font-semibold">{mcVersion}</span>.</>
              : t('instancesPage.modsWillCopy')}
          </p>
        </div>

        {versionChanged && (
          <button
            onClick={() => setUpdateMods(!updateMods)}
            className="flex items-center gap-3 rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.03)] px-3 py-2.5 text-left transition-colors hover:border-[rgba(255,255,255,0.18)]"
          >
            <span
              className={`flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-md border transition-colors ${
                updateMods
                  ? 'border-[rgba(75,63,207,0.8)] bg-[#4B3FCF] text-white'
                  : 'border-[rgba(255,255,255,0.18)] bg-transparent text-transparent'
              }`}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" width={11} height={11}>
                <path d="M5 13l4 4L19 7" />
              </svg>
            </span>
            <span className="min-w-0">
              <span className="block text-[12px] font-semibold text-[rgba(255,255,255,0.8)]">
                {t('instancesPage.updateModsLabel')}
              </span>
              <span className="block text-[11px] leading-snug text-[rgba(255,255,255,0.35)]">
                {t('instancesPage.updateModsHint')}
              </span>
            </span>
          </button>
        )}

        {ramStatus.isKnownTier && !ramStatus.isRecommended && (
          <p className="text-[11px] text-[rgba(240,180,90,0.6)] -mt-2">⚠ {t('instancesPage.ramNotOptimal')}</p>
        )}

        <SubmitButton loading={loading} label={t('instancesPage.duplicateButton')} loadingLabel={loadingLabel} onClick={handleDuplicate} />
      </div>
    </ModalShell>
  )
}
