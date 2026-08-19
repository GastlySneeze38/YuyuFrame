import { useEffect, useState } from 'react'
import { open } from '@tauri-apps/plugin-dialog'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { clampLoader, loaderColor } from '@/lib/loader'
import { formatBytes } from '@/lib/format'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker, type RamStatus } from '@/components/ui/RamPicker'
import { BackArrowIcon } from '@/components/ui/icons/BackArrowIcon'
import { NameInput, VersionSelect, LoaderPicker } from '@/components/instances/InstanceFormFields'
import { showError } from '@/stores/useErrorToast'
import type { DetectedLauncher, DetectedSource, ImportProgressEvent, Loader, ScanResult } from '@/types'
import { useT } from '@/i18n'
import type { t as tFn } from '@/i18n'

function extraDirLabel(t: typeof tFn, dir: string): string {
  if (dir === 'config') return t('import.extraConfig')
  if (dir === 'resourcepacks') return t('import.extraResourcepacks')
  if (dir === 'shaderpacks') return t('import.extraShaderpacks')
  return dir
}

function sourceLabel(t: typeof tFn, source: DetectedSource) {
  switch (source.kind) {
    case 'multimc_prism': return t('import.sourceMultimc')
    case 'curseforge': return t('import.sourceCurseforge')
    case 'atlauncher': return t('import.sourceAtlauncher')
    case 'modrinth_app': return t('import.sourceModrinthApp')
    default: return t('import.sourceGeneric')
  }
}

interface ImportSourceModalProps {
  onClose: () => void
  onImported: (instanceId: string) => void
  /// Si fourni, la destination est verrouillée sur cette instance (appelé depuis Mods.tsx).
  fixedInstanceId?: string
}

export function ImportSourceModal({ onClose, onImported, fixedInstanceId }: ImportSourceModalProps) {
  const t = useT()
  const { instances, versions, defaultRam } = useStore()
  const releaseVersions = versions.filter((v) => v.version_type === 'release').map((v) => v.id)

  const [step, setStep] = useState<'pick' | 'config' | 'mods' | 'done'>('pick')
  const [launchers, setLaunchers] = useState<DetectedLauncher[]>([])
  const [detectingLaunchers, setDetectingLaunchers] = useState(true)
  const [expandedLaunchers, setExpandedLaunchers] = useState<Set<string>>(new Set())
  const [scanning, setScanning] = useState(false)
  const [scan, setScan] = useState<ScanResult | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [duplicates, setDuplicates] = useState<Set<string>>(new Set())
  const [checkingDuplicates, setCheckingDuplicates] = useState(false)
  const [extraDirsSelected, setExtraDirsSelected] = useState<Set<string>>(new Set())

  const [destMode, setDestMode] = useState<'new' | 'existing'>(fixedInstanceId ? 'existing' : 'new')
  const [targetInstanceId, setTargetInstanceId] = useState(fixedInstanceId ?? instances[0]?.id ?? '')
  const [name, setName] = useState('')
  const [mcVersion, setMcVersion] = useState('')
  const [loader, setLoader] = useState<Loader>('vanilla')
  const [ram, setRam] = useState(defaultRam)
  // Purement informatif ici (pas de blocage, contrairement à la création
  // "vierge") : l'import garde un objectif de simplicité, une valeur par
  // défaut suffit à continuer même non-optimale.
  const [ramStatus, setRamStatus] = useState<RamStatus>({ isKnownTier: true, isRecommended: true })

  const [applying, setApplying] = useState(false)
  const [progress, setProgress] = useState<ImportProgressEvent | null>(null)
  const [result, setResult] = useState<{ imported: number; skipped: number; extraCopied: number } | null>(null)

  const runDuplicateCheck = async (modsDir: string, instanceId: string) => {
    if (!instanceId) { setDuplicates(new Set()); return }
    setCheckingDuplicates(true)
    try {
      const dupes = await api.importSource.checkDuplicates(modsDir, instanceId)
      setDuplicates(new Set(dupes))
      // Pré-décoche les doublons — l'utilisateur voit d'emblée ce qui compte
      // vraiment plutôt que de le découvrir dans le résumé final.
      setSelected((prev) => new Set([...prev].filter((n) => !dupes.includes(n))))
    } catch {
      // Best-effort : une vérif de doublons ratée ne doit pas bloquer l'import,
      // le dédoublonnage par SHA1 côté backend s'applique de toute façon.
      setDuplicates(new Set())
    } finally {
      setCheckingDuplicates(false)
    }
  }

  // Détection best-effort des launchers tiers installés (Prism/MultiMC/
  // CurseForge/ATLauncher/Modrinth App/GDLauncher) — évite à l'utilisateur de
  // devoir naviguer manuellement jusqu'à un dossier d'instance souvent
  // profond. Le sélecteur de dossier manuel (handlePickFolder) reste le repli
  // pour tout ce qui n'est pas détecté (installation portable, launcher non
  // listé). Silencieux en cas d'échec : cette détection n'est qu'un confort,
  // jamais bloquante pour l'import manuel.
  useEffect(() => {
    api.importSource.detectLaunchers()
      .then(setLaunchers)
      .catch(() => setLaunchers([]))
      .finally(() => setDetectingLaunchers(false))
  }, [])

  const scanPath = async (path: string) => {
    setScanning(true)
    try {
      const res = await api.importSource.scanFolder(path)
      setScan(res)
      setSelected(new Set(res.mods.map((m) => m.name)))
      setExtraDirsSelected(new Set(res.extraDirs))
      setDuplicates(new Set())
      setName(res.source.name ?? '')
      setMcVersion(res.source.mcVersion ?? releaseVersions[0] ?? '')
      setLoader(clampLoader(res.source.loader))
      setStep('config')
      if (fixedInstanceId) {
        runDuplicateCheck(res.modsDir, fixedInstanceId)
      }
    } catch (e) {
      showError(e)
    } finally {
      setScanning(false)
    }
  }

  const toggleLauncherExpanded = (kind: string) => {
    setExpandedLaunchers((prev) => {
      const next = new Set(prev)
      if (next.has(kind)) next.delete(kind)
      else next.add(kind)
      return next
    })
  }

  const handlePickFolder = async () => {
    const picked = await open({ directory: true })
    if (!picked || Array.isArray(picked)) return
    scanPath(picked)
  }

  const handleDestModeChange = (m: 'new' | 'existing') => {
    setDestMode(m)
    if (m === 'existing' && scan && targetInstanceId) {
      runDuplicateCheck(scan.modsDir, targetInstanceId)
    } else {
      setDuplicates(new Set())
    }
  }

  const handleTargetInstanceChange = (id: string) => {
    setTargetInstanceId(id)
    if (scan) runDuplicateCheck(scan.modsDir, id)
  }

  const toggleMod = (modName: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(modName)) next.delete(modName)
      else next.add(modName)
      return next
    })
  }

  const toggleAll = () => {
    if (!scan) return
    setSelected((prev) => (prev.size === scan.mods.length ? new Set() : new Set(scan.mods.map((m) => m.name))))
  }

  const toggleExtraDir = (dir: string) => {
    setExtraDirsSelected((prev) => {
      const next = new Set(prev)
      if (next.has(dir)) next.delete(dir)
      else next.add(dir)
      return next
    })
  }

  const handleContinueToMods = () => {
    if (destMode === 'new' && !name.trim()) { showError(t('import.nameRequired')); return }
    if (destMode === 'existing' && !targetInstanceId) { showError(t('import.chooseInstance')); return }
    setStep('mods')
  }

  const handleApply = async () => {
    if (!scan) return
    if (destMode === 'new' && !name.trim()) { showError(t('import.nameRequired')); return }
    if (destMode === 'existing' && !targetInstanceId) { showError(t('import.chooseInstance')); return }
    if (selected.size === 0 && extraDirsSelected.size === 0) { showError(t('import.selectAtLeastOne')); return }

    setApplying(true)
    setProgress({ phase: 'mods', current: 0, total: selected.size })

    const unlisten = await listen<ImportProgressEvent>('import_progress', (ev) => {
      setProgress(ev.payload)
    })

    try {
      const res = await api.importSource.apply({
        sourceModsDir: scan.modsDir,
        sourceRoot: scan.sourceRoot,
        selectedFiles: Array.from(selected),
        extraDirs: Array.from(extraDirsSelected),
        mode: destMode,
        targetInstanceId: destMode === 'existing' ? targetInstanceId : undefined,
        newInstance: destMode === 'new' ? { name: name.trim(), mcVersion, loader, ramMb: ram } : undefined,
      })
      setResult({ imported: res.imported.length, skipped: res.skipped.length, extraCopied: res.extraCopied })
      setStep('done')
      onImported(res.instanceId)
    } catch (e) {
      showError(e)
    } finally {
      unlisten()
      setApplying(false)
      setProgress(null)
    }
  }

  return (
    <ModalShell
      title={t('import.fromOtherLauncherTitle')}
      onClose={onClose}
      maxWidth="max-w-lg"
      cardStyle={{ maxHeight: '85vh' }}
    >

        {step === 'pick' && (
          <div className="flex flex-1 flex-col gap-3 overflow-hidden py-4">
            {detectingLaunchers && (
              <p className="text-[11.5px] text-[rgba(255,255,255,0.35)] text-center">{t('import.searchingLaunchers')}</p>
            )}

            {!detectingLaunchers && launchers.length > 0 && (
              <div className="flex flex-1 flex-col gap-2 overflow-y-auto pr-1">
                {launchers.map((l) => {
                  const expanded = expandedLaunchers.has(l.kind)
                  return (
                    <div key={l.kind} className="flex flex-col gap-1.5">
                      <button
                        onClick={() => toggleLauncherExpanded(l.kind)}
                        className="flex items-center gap-1.5 py-0.5 text-[10px] text-[rgba(255,255,255,0.35)] tracking-[0.1em] uppercase font-semibold transition-colors hover:text-[rgba(255,255,255,0.6)]"
                      >
                        <svg
                          viewBox="0 0 10 6" fill="currentColor" width={8} height={5}
                          className={`flex-shrink-0 transition-transform duration-150 ${expanded ? 'rotate-0' : '-rotate-90'}`}
                        >
                          <path d="M0 0l5 6 5-6z" />
                        </svg>
                        {l.displayName} ({l.instances.length})
                      </button>
                      {expanded && (
                        <div className="flex flex-col gap-1">
                          {l.instances.map((inst) => (
                            <button
                              key={inst.path}
                              onClick={() => scanPath(inst.path)}
                              disabled={scanning}
                              className="flex items-center gap-2 rounded-xl px-3 py-2 text-left bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)] transition-colors hover:border-[rgba(75,63,207,0.4)]"
                            >
                              <span className="flex-1 truncate text-[12.5px] font-semibold text-[rgba(255,255,255,0.85)]">
                                {inst.source.name ?? inst.path.split(/[\\/]/).pop()}
                              </span>
                              {inst.source.loader && (
                                <span className="text-[10px] font-bold flex-shrink-0" style={{ color: loaderColor(inst.source.loader) }}>
                                  {inst.source.loader}
                                </span>
                              )}
                              {inst.source.mcVersion && (
                                <span className="text-[10.5px] text-[rgba(255,255,255,0.3)] flex-shrink-0">{inst.source.mcVersion}</span>
                              )}
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
            )}

            <div className="flex flex-shrink-0 flex-col items-center gap-3 py-2">
              {!detectingLaunchers && launchers.length === 0 && (
                <p className="text-[12px] text-[rgba(255,255,255,0.4)] text-center">
                  {t('import.noLauncherDetected')} <code>mods</code>.
                </p>
              )}
              <button
                onClick={handlePickFolder}
                disabled={scanning}
                className={`flex items-center gap-2 rounded-xl px-5 font-bold text-white transition-all duration-150 active:scale-95 h-[42px] text-[13px] ${scanning ? 'bg-[rgba(75,63,207,0.3)]' : 'bg-[#4B3FCF]'}`}
              >
                {scanning ? t('import.analyzing') : launchers.length > 0 ? t('import.otherFolder') : t('import.chooseFolder')}
              </button>
            </div>
          </div>
        )}

        {step === 'config' && scan && (
          <div className="flex flex-1 flex-col gap-4 overflow-y-auto pr-1">
            <button
              onClick={() => setStep('pick')}
              className="flex items-center gap-1.5 self-start text-[12px] font-medium text-[rgba(255,255,255,0.35)] transition-colors hover:text-[rgba(255,255,255,0.7)]"
            >
              <BackArrowIcon size={12} />
              {t('import.back')}
            </button>

            <div className="flex-shrink-0 rounded-xl px-3 py-2 bg-[rgba(75,63,207,0.12)] border border-[rgba(75,63,207,0.3)]">
              <p className="text-[11.5px] text-[rgba(255,255,255,0.7)] font-semibold">{sourceLabel(t, scan.source)}</p>
              <p className="text-[10.5px] text-[rgba(255,255,255,0.35)]">{scan.modsDir}</p>
            </div>

            {!fixedInstanceId && (
              <div className="flex flex-shrink-0 gap-1 rounded-xl p-1 bg-[rgba(0,0,0,0.3)]">
                {(['new', 'existing'] as const).map((m) => (
                  <button
                    key={m}
                    onClick={() => handleDestModeChange(m)}
                    className={`flex-1 rounded-lg text-xs font-semibold transition-all duration-150 h-8 ${destMode === m ? 'bg-[rgba(75,63,207,0.4)] text-white' : 'bg-transparent text-[rgba(255,255,255,0.4)]'}`}
                  >
                    {m === 'new' ? t('import.newInstance') : t('import.existingInstance')}
                  </button>
                ))}
              </div>
            )}

            {destMode === 'new' ? (
              <div className="flex flex-shrink-0 flex-col gap-4">
                <NameInput value={name} onChange={setName} onEnter={handleContinueToMods} />
                <VersionSelect
                  versions={mcVersion && !releaseVersions.includes(mcVersion) ? [mcVersion, ...releaseVersions] : releaseVersions}
                  value={mcVersion}
                  onChange={setMcVersion}
                />
                <LoaderPicker value={loader} onChange={setLoader} />
                <RamPicker value={ram} onChange={setRam} loader={loader} modCount={scan.mods.length} onStatusChange={setRamStatus} />
                {ramStatus.isKnownTier && !ramStatus.isRecommended && (
                  <p className="text-[11px] text-[rgba(240,180,90,0.6)] -mt-2">⚠ {t('instancesPage.ramNotOptimal')}</p>
                )}
              </div>
            ) : (
              <select
                value={targetInstanceId}
                onChange={(e) => handleTargetInstanceChange(e.target.value)}
                className="w-full flex-shrink-0 rounded-xl px-3 text-sm font-medium text-white outline-none h-[38px] bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.1)]"
              >
                {instances.map((i) => (
                  <option key={i.id} value={i.id} className="bg-[#111118]">{i.name}</option>
                ))}
              </select>
            )}

            <button
              onClick={handleContinueToMods}
              className="mt-auto flex-shrink-0 rounded-xl font-bold text-white transition-all duration-150 active:scale-95 h-10 text-[13px] bg-[#4B3FCF] hover:bg-[#6155e8]"
            >
              {t('import.continueButton')}
            </button>
          </div>
        )}

        {step === 'mods' && scan && (
          <div className="flex flex-1 flex-col gap-3 overflow-hidden">
            <button
              onClick={() => setStep('config')}
              disabled={applying}
              className="flex flex-shrink-0 items-center gap-1.5 self-start text-[12px] font-medium text-[rgba(255,255,255,0.35)] transition-colors hover:text-[rgba(255,255,255,0.7)] disabled:cursor-not-allowed"
            >
              <BackArrowIcon size={12} />
              {t('import.back')}
            </button>

            {scan.extraDirs.length > 0 && (
              <div className="flex flex-shrink-0 flex-wrap gap-2">
                {scan.extraDirs.map((dir) => (
                  <label
                    key={dir}
                    className={`flex cursor-pointer items-center gap-1.5 rounded-lg px-2.5 py-1 ${extraDirsSelected.has(dir) ? 'bg-[rgba(75,63,207,0.18)]' : 'bg-[rgba(255,255,255,0.04)]'}`}
                  >
                    <input type="checkbox" checked={extraDirsSelected.has(dir)} onChange={() => toggleExtraDir(dir)} />
                    <span className="text-[11px] text-[rgba(255,255,255,0.75)] font-semibold">
                      {extraDirLabel(t, dir)}
                    </span>
                  </label>
                ))}
              </div>
            )}

            <div className="flex flex-shrink-0 items-center justify-between">
              <p className="text-[11px] text-[rgba(255,255,255,0.4)] font-semibold">
                {t('import.modsSelected', { selected: selected.size, total: scan.mods.length })}
                {checkingDuplicates ? t('import.checkingDuplicates') : ''}
              </p>
              <button onClick={toggleAll} className="text-[11px] text-[rgba(179,163,255,0.9)] font-semibold">
                {selected.size === scan.mods.length ? t('import.deselectAll') : t('import.selectAll')}
              </button>
            </div>

            <div className="flex flex-1 flex-col gap-1 overflow-y-auto rounded-xl p-2 bg-[rgba(0,0,0,0.25)] min-h-[120px]">
              {scan.mods.length === 0 && (
                <p className="text-[12px] text-[rgba(255,255,255,0.3)] text-center py-4 px-0">{t('import.noJarFound')}</p>
              )}
              {scan.mods.map((m) => {
                const isDuplicate = duplicates.has(m.name)
                return (
                  <label key={m.name} className={`flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 ${selected.has(m.name) ? 'bg-[rgba(75,63,207,0.12)]' : 'bg-transparent'}`}>
                    <input type="checkbox" checked={selected.has(m.name)} onChange={() => toggleMod(m.name)} />
                    <span className={`flex-1 truncate text-[12px] ${isDuplicate ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(255,255,255,0.85)]'}`}>{m.name}</span>
                    {isDuplicate && (
                      <span className="text-[9.5px] text-[rgba(179,163,255,0.8)] font-semibold">{t('import.alreadyPresent')}</span>
                    )}
                    <span className="text-[10px] text-[rgba(255,255,255,0.3)]">{formatBytes(m.size)}</span>
                  </label>
                )
              })}
            </div>

            {applying && progress && (
              <div className="flex flex-shrink-0 flex-col gap-1">
                <div className="h-1.5 w-full overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
                  <div
                    className="h-full rounded-full transition-all duration-150 bg-[#4B3FCF]"
                    style={{
                      width: `${progress.total > 0 ? Math.min(100, (progress.current / progress.total) * 100) : 0}%`,
                    }}
                  />
                </div>
                <p className="text-[10.5px] text-[rgba(255,255,255,0.4)]">
                  {progress.phase === 'mods'
                    ? t('import.copyingMods', { current: progress.current, total: progress.total })
                    : t('import.copyingFiles', { label: progress.label ?? t('import.additionalFiles') })}
                </p>
              </div>
            )}

            <button
              onClick={handleApply}
              disabled={applying}
              className={`flex-shrink-0 rounded-xl font-bold text-white transition-all duration-150 active:scale-95 h-10 text-[13px] ${applying ? 'bg-[rgba(75,63,207,0.3)]' : 'bg-[#4B3FCF]'}`}
            >
              {applying ? t('import.importing') : t('import.importCount', { count: selected.size })}
            </button>
          </div>
        )}

        {step === 'done' && result && (
          <div className="flex flex-col items-center gap-3 py-6">
            <div className="text-[32px]">✅</div>
            <p className="text-[13px] text-white font-semibold text-center">
              {t('import.modsImported', { count: result.imported })}
              {result.skipped > 0 ? t('import.modsSkipped', { count: result.skipped }) : ''}
              {result.extraCopied > 0 ? t('import.extraCopied', { count: result.extraCopied }) : ''}
            </p>
            <button
              onClick={onClose}
              className="rounded-xl px-5 font-bold text-white h-10 bg-[#4B3FCF] text-[13px]"
            >
              {t('import.close')}
            </button>
          </div>
        )}
    </ModalShell>
  )
}
