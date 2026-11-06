import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { press } from '@/lib/motion'
import { motion } from 'framer-motion'
import { api } from '@/api/client'
import type { Instance, JvmVendor, Loader } from '@/types'
import { useLoadersFor } from '@/lib/loaderCompat'
import { createInstanceFromPack } from '@/lib/modpackInstance'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker, type RamStatus } from '@/components/ui/RamPicker'
import { BackArrowIcon } from '@/components/ui/icons/BackArrowIcon'
import { showError } from '@/stores/useErrorToast'
import { clearDraft, useDraftState } from '@/stores/useDrafts'
import { NameInput, DescriptionInput, SubmitButton, VersionSelect, LoaderPicker } from './InstanceFormFields'
import { PackPicker, type PackChoice } from './PackPicker'
import { useT } from '@/i18n'

const DRAFT_KEY = 'create-instance'

/// Le nombre de mods d'un modpack moyen : la recommandation de mémoire se
/// calcule avant de savoir lequel sera choisi (les réglages viennent d'abord).
const TYPICAL_PACK_MODS = 150

/**
 * Deux chemins :
 * - **Vierge** : le nom, puis version du jeu, loader, mémoire, description.
 * - **Modpack** : d'abord les réglages de l'instance (nom, mémoire,
 *   description), puis le modpack, cherché sur Modrinth. La version du jeu et
 *   le loader ne se choisissent pas : ce sont ceux du pack
 *   (`lib/modpackInstance.ts`).
 */
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
  const navigate = useNavigate()
  const [step, setStep] = useState<'choose' | 'configure' | 'pack'>('choose')
  const [mode, setMode] = useState<'blank' | 'modpack'>('blank')
  const [pack, setPack] = useState<PackChoice | null>(null)

  // Brouillon conservé tant que l'instance n'est pas créée : on peut fermer
  // la modale, aller vérifier une version ailleurs, et revenir sans retaper
  // (voir stores/useDrafts.ts).
  const [name, setName] = useDraftState(DRAFT_KEY, 'name', '')
  const [description, setDescription] = useDraftState(DRAFT_KEY, 'description', '')
  const [mcVersion, setMcVersion] = useDraftState(DRAFT_KEY, 'mcVersion', versions[0] ?? '')
  const [loader, setLoader] = useDraftState<Loader>(DRAFT_KEY, 'loader', 'vanilla')
  const [ram, setRam] = useDraftState(DRAFT_KEY, 'ram', defaultRam)
  // Les réglages JVM ne se saisissent plus ici, mais l'instance doit quand
  // même naître avec ceux que l'utilisateur a choisis comme valeurs par
  // défaut dans les paramètres — sinon créer une instance les perdrait
  // silencieusement.
  const jvm = {
    vendor: defaultJvmVendor,
    customPath: defaultJvmCustomPath.trim() || undefined,
    gcPolicy: defaultGcPolicy,
    extraArgs: '',
    argsMode: 'append' as const,
  }
  // Optimiste par défaut (true/true) pour ne pas griser le bouton pendant le
  // premier rendu, avant que RamPicker n'ait pu calculer son premier statut.
  const [ramStatus, setRamStatus] = useState<RamStatus>({ isKnownTier: true, isRecommended: true })
  const [loading, setLoading] = useState(false)
  const [loadingLabel, setLoadingLabel] = useState(t('instancesPage.creating'))

  // Un id par ouverture de modal — partagé par tous les événements de cette
  // tentative pour que PostHog puisse reconstituer le funnel de création.
  const flowId = useRef(crypto.randomUUID())

  useEffect(() => {
    api.analytics.track('instance_create_modal_opened', { flow_id: flowId.current })
  }, [])

  useEffect(() => {
    if (versions.length > 0 && !mcVersion) setMcVersion(versions[0])
  }, [versions])

  // En « vierge », la version du jeu est choisie en premier et c'est la liste
  // des loaders qui s'y adapte ; le loader choisi peut disparaître en changeant
  // de version (NeoForge avant 1.20.2) : on retombe sur vanilla.
  const availableLoaders = useLoadersFor(mcVersion)
  useEffect(() => {
    if (mode !== 'blank' || !availableLoaders) return
    if (!availableLoaders.includes(loader)) setLoader('vanilla')
  }, [mode, availableLoaders, loader])

  const handleSwitchMode = (m: 'blank' | 'modpack') => {
    if (m === mode) return
    setMode(m)
    setPack(null)
    if (m === 'blank') {
      setLoader('vanilla')
      api.analytics.track('instance_create_from_scratch_selected', { flow_id: flowId.current })
    } else {
      // Un modpack demande vite plus que la mémoire par défaut.
      setRam(Math.max(ram, 4096))
      api.analytics.track('instance_create_modpack_selected', { flow_id: flowId.current })
    }
  }

  const handleContinue = () => {
    if (mode === 'blank') {
      if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
      setStep('configure')
    } else {
      setStep('pack')
    }
  }

  const handleVersionChange = (v: string) => {
    setMcVersion(v)
    api.analytics.track('instance_create_version_changed', { flow_id: flowId.current, mc_version: v })
  }

  const finish = (instance: Instance) => {
    // Instance créée : le formulaire repart vierge la prochaine fois.
    clearDraft(DRAFT_KEY)
    onCreate(instance)
    onClose()
  }

  const handleCreateBlank = async () => {
    if (!name.trim()) { showError(t('instancesPage.nameRequired')); return }
    if (!mcVersion) { showError(t('instancesPage.selectVersion')); return }
    setLoading(true); setLoadingLabel(t('instancesPage.creating'))
    api.analytics.track('instance_create_submitted', { flow_id: flowId.current, mc_version: mcVersion, loader })
    try {
      // Les arguments JVM manuels ne se saisissent pas ici : ils ont leur
      // propre écran (`/jvm/:instanceId`), qui a besoin d'une instance existante.
      const instance = await api.instances.create(name.trim(), mcVersion, loader, ram, description.trim(), jvm)
      finish(instance)
    } catch (e) {
      showError(e)
    } finally {
      setLoading(false)
    }
  }

  const handleCreateFromPack = async () => {
    if (!pack?.version) { showError(t('instancesPage.selectPackFirst')); return }
    setLoading(true); setLoadingLabel(t('instancesPage.preparingPack'))
    api.analytics.track('instance_create_submitted', {
      flow_id: flowId.current,
      mc_version: pack.version.game_versions[0] ?? '',
      loader: pack.version.loaders[0] ?? '',
      modpack_id: pack.hit.project_id,
    })
    const created: { instance: Instance | null } = { instance: null }
    try {
      const instance = await createInstanceFromPack({
        versionId: pack.version.id,
        name,
        ramMb: ram,
        description,
        jvm,
        author: pack.hit.author,
        onCreated: (i) => { created.instance = i },
        onProgress: (s) => setLoadingLabel(s.kind === 'preparing'
          ? t('instancesPage.preparingPack')
          : t('instancesPage.installingPackProgress', { current: s.current, total: s.total })),
      })
      finish(instance)
    } catch (e) {
      showError(e)
      // L'instance existe déjà si c'est l'installation du pack qui a échoué :
      // on la montre (jamais effacée sans geste de l'utilisateur), le pack se
      // réinstalle depuis son écran Mods.
      if (created.instance) finish(created.instance)
    } finally {
      setLoading(false)
    }
  }

  const backButton = (to: 'choose') => (
    <motion.button {...press}
      onClick={() => setStep(to)}
      disabled={loading}
      className="flex items-center gap-1.5 self-start text-[12px] font-medium text-[rgba(255,255,255,0.35)] transition-colors hover:text-[rgba(255,255,255,0.7)]"
    >
      <BackArrowIcon size={12} />
      {t('instancesPage.backButton')}
    </motion.button>
  )

  // Les deux étapes du chemin « modpack », pour savoir où l'on en est.
  const packSteps = (current: 1 | 2) => (
    <div className="flex items-center gap-2 text-[11px] font-semibold">
      {[t('instancesPage.packStepSettings'), t('instancesPage.packStepPick')].map((label, i) => {
        const n = i + 1
        const active = n === current
        const done = n < current
        return (
          <div key={label} className="flex items-center gap-2">
            {i > 0 && <span className="h-px w-6 bg-[rgba(255,255,255,0.12)]" />}
            <span className={`flex h-5 w-5 items-center justify-center rounded-full text-[10px] ${
              active ? 'bg-[rgb(var(--accent)/0.8)] text-white' : done ? 'bg-[rgb(var(--accent)/0.3)] text-white' : 'bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.4)]'
            }`}>{n}</span>
            <span className={active ? 'text-white' : 'text-[rgba(255,255,255,0.4)]'}>{label}</span>
          </div>
        )
      })}
    </div>
  )

  const jvmLink = (
    <motion.button {...press}
      onClick={() => { onClose(); navigate('/jvm') }}
      className="flex items-center justify-between gap-3 rounded-xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-3 py-2.5 text-left transition-colors hover:border-white/20"
    >
      <div className="min-w-0">
        <p className="text-[12px] font-semibold text-[rgba(255,255,255,0.75)]">{t('instancesPage.jvmConfigTitle')}</p>
        <p className="truncate text-[11px] text-[rgba(255,255,255,0.35)]">{t('instancesPage.jvmConfigAfterCreate')}</p>
      </div>
      <span className="flex-shrink-0 text-[11px] font-semibold text-[rgba(150,140,240,0.9)]">{t('instancesPage.jvmConfigOpen')}</span>
    </motion.button>
  )

  const ramWarning = !ramStatus.isKnownTier ? (
    <p className="-mt-2 text-[11px] text-[rgba(240,180,90,0.75)]">⚠ {t('instancesPage.ramNotSelected')}</p>
  ) : !ramStatus.isRecommended ? (
    <p className="-mt-2 text-[11px] text-[rgba(240,180,90,0.6)]">⚠ {t('instancesPage.ramNotOptimal')}</p>
  ) : null

  return (
    <ModalShell title={t('instancesPage.newInstance')} onClose={onClose}>
      <div className="flex flex-col gap-4">
        {step === 'choose' && (
          <>
            <div className="flex gap-1 rounded-xl bg-[rgba(0,0,0,0.3)] p-1">
              {(['blank', 'modpack'] as const).map((m) => (
                <motion.button {...press}
                  key={m}
                  onClick={() => handleSwitchMode(m)}
                  className={`h-[32px] flex-1 rounded-lg text-xs font-semibold transition-all duration-150 ${
                    mode === m ? 'bg-[rgb(var(--accent)/0.4)] text-white' : 'bg-transparent text-[rgba(255,255,255,0.4)]'
                  }`}
                >
                  {m === 'blank' ? t('instancesPage.blank') : 'Modpack'}
                </motion.button>
              ))}
            </div>

            {mode === 'blank' ? (
              <>
                <NameInput value={name} onChange={setName} onEnter={handleContinue} />
                <SubmitButton loading={false} label={t('instancesPage.continueButton')} loadingLabel="" onClick={handleContinue} />
              </>
            ) : (
              <>
                {packSteps(1)}
                <div className="flex flex-col gap-1.5">
                  <NameInput value={name} onChange={setName} onEnter={handleContinue} />
                  <p className="text-[10.5px] text-[rgba(255,255,255,0.35)]">{t('instancesPage.packNameHint')}</p>
                </div>
                <RamPicker value={ram} onChange={setRam} loader="fabric" modCount={TYPICAL_PACK_MODS} onStatusChange={setRamStatus} />
                {ramWarning}
                <DescriptionInput value={description} onChange={setDescription} />
                {jvmLink}
                <SubmitButton
                  loading={false}
                  disabled={!ramStatus.isKnownTier}
                  label={t('instancesPage.choosePackButton')}
                  loadingLabel=""
                  onClick={handleContinue}
                />
              </>
            )}
          </>
        )}

        {step === 'configure' && (
          <>
            {backButton('choose')}
            <div className="flex flex-col gap-4">
              <VersionSelect versions={versions} value={mcVersion} onChange={handleVersionChange} />
              <LoaderPicker value={loader} onChange={setLoader} available={availableLoaders} />
              {/* Horizon n'est pas un loader : il faut le dire avant la
                  création, pas le découvrir au lancement. */}
              {loader === 'horizon' && (
                <p className="rounded-xl border border-[rgba(79,209,197,0.3)] bg-[rgba(79,209,197,0.08)] px-3 py-2.5 text-[11.5px] leading-relaxed text-[rgba(255,255,255,0.7)]">
                  {t('instancesPage.horizonHint')}
                </p>
              )}
            </div>

            {/* Horizon n'a pas de réglage de mémoire : le proposer serait un
                choix sans effet. */}
            {loader !== 'horizon' && (
              <RamPicker value={ram} onChange={setRam} loader={loader} modCount={0} onStatusChange={setRamStatus} />
            )}

            <DescriptionInput value={description} onChange={setDescription} />

            {/* La JVM a son propre écran, qui montre l'aperçu de la vraie
                ligne de commande : l'instance part sur les réglages par
                défaut, et se relie à une configuration quand on le souhaite. */}
            {loader !== 'horizon' && jvmLink}
            {loader !== 'horizon' && ramWarning}

            <SubmitButton
              loading={loading}
              disabled={loader !== 'horizon' && !ramStatus.isKnownTier}
              label={t('instancesPage.createInstanceButton')}
              loadingLabel={loadingLabel}
              onClick={handleCreateBlank}
            />
          </>
        )}

        {step === 'pack' && (
          <>
            {backButton('choose')}
            {packSteps(2)}
            <PackPicker choice={pack} onChoice={(c) => {
              setPack(c)
              if (c && c.hit.project_id !== pack?.hit.project_id) {
                api.analytics.track('instance_create_modpack_picked', { flow_id: flowId.current, modpack_id: c.hit.project_id })
              }
            }} />
            <SubmitButton
              loading={loading}
              disabled={!pack?.version}
              label={t('instancesPage.createInstanceButton')}
              loadingLabel={loadingLabel}
              onClick={handleCreateFromPack}
            />
          </>
        )}
      </div>
    </ModalShell>
  )
}
