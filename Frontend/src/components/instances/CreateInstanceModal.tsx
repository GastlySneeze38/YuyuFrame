import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { Instance, JvmVendor, Loader } from '@/types'
import { INSTANCE_PRESETS, type InstancePreset } from '@/data/presets'
import { loaderColor } from '@/lib/loader'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker, type RamStatus } from '@/components/ui/RamPicker'
import { BackArrowIcon } from '@/components/ui/icons/BackArrowIcon'
import { showError } from '@/stores/useErrorToast'
import { clearDraft, useDraftState } from '@/stores/useDrafts'
import { NameInput, DescriptionInput, SubmitButton, VersionSelect, LoaderPicker } from './InstanceFormFields'
import { JvmAdvancedSection } from './JvmAdvancedSection'
import { PresetCard } from './PresetCard'
import { useT } from '@/i18n'

const DRAFT_KEY = 'create-instance'

/// Trouve le fichier de la dernière version Modrinth d'un mod compatible avec
/// la version MC + loader donnés. Best-effort : un slug introuvable est ignoré.
async function fetchPresetModFile(
  slug: string,
  mcVersion: string,
  loader: Loader,
): Promise<{ url: string; filename: string } | null> {
  try {
    const params = new URLSearchParams()
    params.set('game_versions', JSON.stringify([mcVersion]))
    if (loader !== 'vanilla') params.set('loaders', JSON.stringify([loader]))
    const res = await fetch(
      `https://api.modrinth.com/v2/project/${slug}/version?${params}`,
      { headers: { 'User-Agent': 'YuyuFrame/1.0' } },
    )
    if (!res.ok) return null
    const versions = await res.json() as Array<{ files: Array<{ url: string; filename: string; primary: boolean }> }>
    if (!versions.length) return null
    const file = versions[0].files.find((f) => f.primary) ?? versions[0].files[0]
    return file ? { url: file.url, filename: file.filename } : null
  } catch {
    return null
  }
}

async function installPresetMods(
  instanceId: string,
  preset: InstancePreset,
  mcVersion: string,
  onProgress: (done: number, total: number) => void,
) {
  for (let i = 0; i < preset.mods.length; i++) {
    onProgress(i, preset.mods.length)
    const entry = preset.mods[i]
    const file = typeof entry === 'string'
      ? await fetchPresetModFile(entry, mcVersion, preset.loader)
      : entry
    if (!file) continue
    try { await api.mods.install(instanceId, file.url, file.filename) } catch { /* best-effort */ }
  }
  onProgress(preset.mods.length, preset.mods.length)
}

export function CreateInstanceModal({
  versions,
  defaultRam,
  defaultJvmVendor,
  defaultJvmCustomPath,
  defaultGcPolicy,
  onClose,
  onCreate,
}: {
  versions: string[]
  defaultRam: number
  defaultJvmVendor: JvmVendor
  defaultJvmCustomPath: string
  defaultGcPolicy: string
  onClose: () => void
  onCreate: (instance: Instance) => void
}) {
  const t = useT()
  const [step, setStep] = useState<'choose' | 'configure'>('choose')
  const [mode, setMode] = useState<'blank' | 'preset'>('blank')
  const [selectedPreset, setSelectedPreset] = useState<InstancePreset | null>(null)

  // Brouillon conservé tant que l'instance n'est pas créée : on peut fermer
  // la modale, aller vérifier une version ailleurs, et revenir sans retaper
  // (voir stores/useDrafts.ts).
  const [name, setName] = useDraftState(DRAFT_KEY, 'name', '')
  const [description, setDescription] = useDraftState(DRAFT_KEY, 'description', '')
  const [mcVersion, setMcVersion] = useDraftState(DRAFT_KEY, 'mcVersion', versions[0] ?? '')
  const [loader, setLoader] = useDraftState<Loader>(DRAFT_KEY, 'loader', 'vanilla')
  const [ram, setRam] = useDraftState(DRAFT_KEY, 'ram', defaultRam)
  const [jvmVendor, setJvmVendor] = useState<JvmVendor>(defaultJvmVendor)
  const [jvmCustomPath, setJvmCustomPath] = useState(defaultJvmCustomPath)
  const [gcPolicy, setGcPolicy] = useState(defaultGcPolicy)
  // Optimiste par défaut (true/true) pour ne pas griser le bouton "Créer"
  // pendant le premier rendu, avant que RamPicker n'ait pu calculer son
  // premier statut réel (retour utilisateur : bloquer la création tant
  // qu'aucun palier RAM n'a été explicitement choisi).
  const [ramStatus, setRamStatus] = useState<RamStatus>({ isKnownTier: true, isRecommended: true })
  const [loading, setLoading] = useState(false)
  const [loadingLabel, setLoadingLabel] = useState(t('instancesPage.creating'))

  // Un id par ouverture de modal — partagé par tous les événements de cette
  // tentative pour que PostHog puisse reconstituer le funnel de création
  // proprement, même si l'utilisateur rouvre la modal plusieurs fois.
  const flowId = useRef(crypto.randomUUID())

  useEffect(() => {
    api.analytics.track('instance_create_modal_opened', { flow_id: flowId.current })
  }, [])

  useEffect(() => {
    if (versions.length > 0 && !mcVersion) setMcVersion(versions[0])
  }, [versions])

  const handleSelectPreset = (preset: InstancePreset) => {
    setSelectedPreset(preset)
    setName(preset.name)
    setMcVersion(preset.mcVersion)
    setLoader(preset.loader)
    setRam(preset.ramMb)
    api.analytics.track('instance_create_preset_selected', { flow_id: flowId.current, preset_id: preset.id })
  }

  const handleSwitchMode = (m: 'blank' | 'preset') => {
    setMode(m)
    if (m === 'blank') {
      setSelectedPreset(null)
      setName('')
      setDescription('')
      setMcVersion(versions[0] ?? '')
      setLoader('vanilla')
      setRam(defaultRam)
      setJvmVendor(defaultJvmVendor)
      setJvmCustomPath(defaultJvmCustomPath)
      setGcPolicy(defaultGcPolicy)
      api.analytics.track('instance_create_from_scratch_selected', { flow_id: flowId.current })
    }
  }

  const handleContinue = () => {
    if (mode === 'preset' && !selectedPreset) { showError(t('instancesPage.selectPresetFirst')); return }
    if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
    setStep('configure')
  }

  const handleVersionChange = (v: string) => {
    setMcVersion(v)
    api.analytics.track('instance_create_version_changed', { flow_id: flowId.current, mc_version: v })
  }

  const handleCreate = async () => {
    if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
    if (!mcVersion) { showError(t('instancesPage.selectVersion')); return }
    setLoading(true); setLoadingLabel(t('instancesPage.creating'))
    api.analytics.track('instance_create_submitted', { flow_id: flowId.current, mc_version: mcVersion, loader })
    try {
      // Les arguments JVM manuels ne se saisissent pas ici : ils ont leur
      // propre écran (`/jvm/:instanceId`), qui a besoin d'une instance
      // existante pour calculer l'aperçu de la ligne de commande.
      const instance = await api.instances.create(name.trim(), mcVersion, loader, ram, description.trim(), {
        vendor: jvmVendor,
        customPath: jvmCustomPath.trim() || undefined,
        gcPolicy,
        extraArgs: '',
        argsMode: 'append',
      })
      if (selectedPreset) {
        await installPresetMods(instance.id, selectedPreset, mcVersion, (done, total) => {
          setLoadingLabel(t('instancesPage.installingModsProgress', { done, total }))
        })
      }
      // Instance créée : le formulaire repart vierge la prochaine fois.
      clearDraft(DRAFT_KEY)
      onCreate(instance)
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setLoading(false)
    }
  }

  return (
    <ModalShell title={t('instancesPage.newInstance')} onClose={onClose}>
      <div className="flex flex-col gap-4">
        {step === 'choose' ? (
          <>
            <div className="flex gap-1 rounded-xl p-1 bg-[rgba(0,0,0,0.3)]">
              {(['blank', 'preset'] as const).map((m) => (
                <button
                  key={m}
                  onClick={() => handleSwitchMode(m)}
                  className={`flex-1 rounded-lg text-xs font-semibold transition-all duration-150 h-[32px] ${
                    mode === m ? 'bg-[rgba(75,63,207,0.4)] text-white' : 'bg-transparent text-[rgba(255,255,255,0.4)]'
                  }`}
                >
                  {m === 'blank' ? t('instancesPage.blank') : 'Modpack'}
                </button>
              ))}
            </div>

            {mode === 'preset' && (
              <div className="grid grid-cols-1 gap-2 max-h-[320px] overflow-y-auto pr-1">
                {INSTANCE_PRESETS.map((p) => (
                  <PresetCard key={p.id} preset={p} selected={selectedPreset?.id === p.id} onSelect={() => handleSelectPreset(p)} />
                ))}
              </div>
            )}

            {(mode === 'blank' || selectedPreset) && (
              <>
                <NameInput value={name} onChange={setName} onEnter={handleContinue} />
                <SubmitButton loading={false} label={t('instancesPage.continueButton')} loadingLabel="" onClick={handleContinue} />
              </>
            )}
          </>
        ) : (
          <>
            <button
              onClick={() => setStep('choose')}
              className="flex items-center gap-1.5 self-start text-[12px] font-medium text-[rgba(255,255,255,0.35)] transition-colors hover:text-[rgba(255,255,255,0.7)]"
            >
              <BackArrowIcon size={12} />
              {t('instancesPage.backButton')}
            </button>

            {mode === 'blank' ? (
              <div className="flex flex-col gap-4">
                <VersionSelect versions={versions} value={mcVersion} onChange={handleVersionChange} />
                <LoaderPicker value={loader} onChange={setLoader} />
              </div>
            ) : (
              <div className="flex flex-col gap-2">
                <div className="flex items-end gap-3">
                  <VersionSelect versions={versions} value={mcVersion} onChange={handleVersionChange} className="flex-1" />
                  <div className="flex h-[40px] items-center gap-1.5 rounded-xl px-3 bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.06)]">
                    <span className="text-[11px] font-semibold" style={{ color: loaderColor(loader) }}>{loader}</span>
                  </div>
                </div>
                {mcVersion !== selectedPreset!.mcVersion && (
                  <p className="text-[10px] text-[rgba(250,204,21,0.75)]">
                    {t('instancesPage.versionDifferentFromPreset', { preset: selectedPreset!.mcVersion })}
                  </p>
                )}
              </div>
            )}

            <RamPicker value={ram} onChange={setRam} loader={loader} modCount={selectedPreset?.mods.length ?? 0} onStatusChange={setRamStatus} />

            <DescriptionInput value={description} onChange={setDescription} />

            {/* Pas de prévisualisation ici (`preview` omis) : l'instance
                n'existe pas encore, rien à résoudre tant qu'elle n'est pas créée. */}
            <JvmAdvancedSection
              vendor={jvmVendor} onVendorChange={setJvmVendor}
              customPath={jvmCustomPath} onCustomPathChange={setJvmCustomPath}
              gcPolicy={gcPolicy} onGcPolicyChange={setGcPolicy}
              ramMb={ram}
            />

            {!ramStatus.isKnownTier ? (
              <p className="text-[11px] text-[rgba(240,180,90,0.75)] -mt-2">
                ⚠ {t('instancesPage.ramNotSelected')}
              </p>
            ) : !ramStatus.isRecommended ? (
              <p className="text-[11px] text-[rgba(240,180,90,0.6)] -mt-2">
                ⚠ {t('instancesPage.ramNotOptimal')}
              </p>
            ) : null}

            <SubmitButton
              loading={loading}
              disabled={!ramStatus.isKnownTier}
              label={t('instancesPage.createInstanceButton')}
              loadingLabel={loadingLabel}
              onClick={handleCreate}
            />
          </>
        )}
      </div>
    </ModalShell>
  )
}
