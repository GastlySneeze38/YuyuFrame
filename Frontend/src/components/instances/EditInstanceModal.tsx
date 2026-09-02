import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { Instance, Loader } from '@/types'
import { updateModsForNewVersion } from '@/pages/Mods'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker, type RamStatus } from '@/components/ui/RamPicker'
import { showError } from '@/stores/useErrorToast'
import { parseJvmArgs } from '@/lib/jvmFlags'
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
  const navigate = useNavigate()
  const [name, setName] = useState(instance.name)
  const [description, setDescription] = useState(instance.description)
  const [mcVersion, setMcVersion] = useState(instance.mc_version)
  const [loader, setLoader] = useState<Loader>(instance.loader)
  const [ram, setRam] = useState(instance.ram_mb)
  const [loading, setLoading] = useState(false)
  const [loadingLabel, setLoadingLabel] = useState(t('instancesPage.saving'))
  // Mods déjà installés sur cette instance — la recommandation RAM doit en
  // tenir compte dès l'ouverture de la modal.
  const [modCount, setModCount] = useState(0)
  useEffect(() => {
    api.mods.list(instance.id).then((mods) => setModCount(mods.length)).catch(() => {})
  }, [instance.id])
  // Purement informatif ici (pas de blocage) : contrairement à la création,
  // cette RAM existait déjà sur l'instance — un avertissement suffit.
  const [ramStatus, setRamStatus] = useState<RamStatus>({ isKnownTier: true, isRecommended: true })

  const jvmArgCount = parseJvmArgs(instance.jvm_extra_args).length

  const handleSave = async () => {
    if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
    setLoading(true); setLoadingLabel(t('instancesPage.saving'))
    try {
      // Le bloc JVM n'est plus éditable ici (il a son propre écran, voir le
      // bouton plus bas) — il est renvoyé tel quel pour ne pas être réinitialisé
      // par un simple renommage d'instance.
      const updated = await api.instances.update(instance.id, name.trim(), mcVersion, loader, ram, description.trim(), {
        vendor: instance.jvm_vendor,
        customPath: instance.jvm_custom_path ?? undefined,
        gcPolicy: instance.gc_policy,
        extraArgs: instance.jvm_extra_args,
        argsMode: instance.jvm_args_mode,
      })
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

        <RamPicker value={ram} onChange={setRam} loader={loader} modCount={modCount} onStatusChange={setRamStatus} />

        <DescriptionInput value={description} onChange={setDescription} />

        {/* La config JVM a quitté cette modal pour son propre écran : les
            arguments JVM y sont le sujet principal (champ multi-lignes, jeux
            de drapeaux, aperçu de la ligne de commande réelle), ce qu'une
            section repliable de modal ne pouvait pas porter. */}
        <button
          onClick={() => { onClose(); navigate(`/jvm/${instance.id}`) }}
          className="flex items-center justify-between gap-3 rounded-xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-3 py-2.5 text-left transition-colors hover:border-white/20"
        >
          <div className="min-w-0">
            <p className="text-[12px] font-semibold text-[rgba(255,255,255,0.75)]">Configuration JVM</p>
            <p className="truncate text-[11px] text-[rgba(255,255,255,0.35)]">
              {instance.jvm_vendor === 'auto' ? 'Auto' : instance.jvm_vendor}
              {' · '}GC {instance.gc_policy}
              {jvmArgCount > 0 && ` · ${jvmArgCount} drapeau${jvmArgCount > 1 ? 'x' : ''} manuel${jvmArgCount > 1 ? 's' : ''}`}
            </p>
          </div>
          <span className="flex-shrink-0 text-[11px] font-semibold text-[rgba(150,140,240,0.9)]">Ouvrir →</span>
        </button>

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

        {ramStatus.isKnownTier && !ramStatus.isRecommended && (
          <p className="text-[11px] text-[rgba(240,180,90,0.6)] -mt-2">⚠ {t('instancesPage.ramNotOptimal')}</p>
        )}

        <SubmitButton loading={loading} label={t('common.save')} loadingLabel={loadingLabel} onClick={handleSave} />
      </div>
    </ModalShell>
  )
}
