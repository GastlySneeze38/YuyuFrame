import { useState } from 'react'
import { open } from '@tauri-apps/plugin-dialog'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { LOADERS, clampLoader } from '@/lib/loader'
import { formatBytes, RAM_OPTIONS } from '@/lib/format'
import { ModalShell } from '@/components/ui/ModalShell'
import type { DetectedSource, ImportProgressEvent, Loader, ScanResult } from '@/types'

const EXTRA_DIR_LABELS: Record<string, string> = {
  config: 'Configs des mods',
  resourcepacks: 'Packs de ressources',
  shaderpacks: 'Shaders',
}

function sourceLabel(source: DetectedSource) {
  switch (source.kind) {
    case 'multimc_prism': return 'MultiMC / Prism Launcher détecté'
    case 'curseforge': return 'CurseForge détecté'
    case 'atlauncher': return 'ATLauncher détecté'
    case 'modrinth_app': return 'Modrinth App détecté'
    default: return 'Dossier de mods trouvé'
  }
}

interface ImportSourceModalProps {
  onClose: () => void
  onImported: (instanceId: string) => void
  /// Si fourni, la destination est verrouillée sur cette instance (appelé depuis Mods.tsx).
  fixedInstanceId?: string
}

export default function ImportSourceModal({ onClose, onImported, fixedInstanceId }: ImportSourceModalProps) {
  const { instances, versions, defaultRam } = useStore()
  const releaseVersions = versions.filter((v) => v.version_type === 'release').map((v) => v.id)

  const [step, setStep] = useState<'pick' | 'review' | 'done'>('pick')
  const [scanning, setScanning] = useState(false)
  const [scan, setScan] = useState<ScanResult | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [duplicates, setDuplicates] = useState<Set<string>>(new Set())
  const [checkingDuplicates, setCheckingDuplicates] = useState(false)
  const [extraDirsSelected, setExtraDirsSelected] = useState<Set<string>>(new Set())
  const [error, setError] = useState('')

  const [destMode, setDestMode] = useState<'new' | 'existing'>(fixedInstanceId ? 'existing' : 'new')
  const [targetInstanceId, setTargetInstanceId] = useState(fixedInstanceId ?? instances[0]?.id ?? '')
  const [name, setName] = useState('')
  const [mcVersion, setMcVersion] = useState('')
  const [loader, setLoader] = useState<Loader>('vanilla')
  const [ram, setRam] = useState(defaultRam)

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

  const handlePickFolder = async () => {
    setError('')
    const picked = await open({ directory: true })
    if (!picked || Array.isArray(picked)) return
    setScanning(true)
    try {
      const res = await api.importSource.scanFolder(picked)
      setScan(res)
      setSelected(new Set(res.mods.map((m) => m.name)))
      setExtraDirsSelected(new Set(res.extraDirs))
      setDuplicates(new Set())
      setName(res.source.name ?? '')
      setMcVersion(res.source.mcVersion ?? releaseVersions[0] ?? '')
      setLoader(clampLoader(res.source.loader))
      setStep('review')
      if (fixedInstanceId) {
        runDuplicateCheck(res.modsDir, fixedInstanceId)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : typeof e === 'string' ? e : "Impossible d'analyser ce dossier")
    } finally {
      setScanning(false)
    }
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

  const handleApply = async () => {
    if (!scan) return
    if (destMode === 'new' && !name.trim()) { setError('Nom requis'); return }
    if (destMode === 'existing' && !targetInstanceId) { setError('Choisis une instance'); return }
    if (selected.size === 0 && extraDirsSelected.size === 0) { setError('Sélectionne au moins un mod ou un dossier'); return }

    setError('')
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
      setError(e instanceof Error ? e.message : typeof e === 'string' ? e : "Erreur lors de l'import")
    } finally {
      unlisten()
      setApplying(false)
      setProgress(null)
    }
  }

  return (
    <ModalShell
      title="Importer depuis un autre launcher"
      onClose={onClose}
      maxWidth="max-w-lg"
      cardStyle={{ maxHeight: '85vh' }}
    >

        {step === 'pick' && (
          <div className="flex flex-col items-center gap-4 py-6">
            <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.4)', textAlign: 'center' }}>
              Choisis le dossier d'une instance d'un autre launcher (Modrinth App, CurseForge, MultiMC/Prism, ATLauncher, Feather, Lunar Client...) ou n'importe quel dossier contenant un sous-dossier <code>mods</code>.
            </p>
            <button
              onClick={handlePickFolder}
              disabled={scanning}
              className="flex items-center gap-2 rounded-xl px-5 font-bold text-white transition-all duration-150 active:scale-95"
              style={{ height: 42, background: scanning ? 'rgba(75,63,207,0.3)' : '#4B3FCF', fontSize: 13 }}
            >
              {scanning ? 'Analyse...' : 'Choisir un dossier'}
            </button>
            {error && <p style={{ fontSize: 12, color: 'rgb(248,113,113)' }}>{error}</p>}
          </div>
        )}

        {step === 'review' && scan && (
          <div className="flex flex-1 flex-col gap-3 overflow-hidden">
            <div className="flex-shrink-0 rounded-xl px-3 py-2" style={{ background: 'rgba(75,63,207,0.12)', border: '1px solid rgba(75,63,207,0.3)' }}>
              <p style={{ fontSize: 11.5, color: 'rgba(255,255,255,0.7)', fontWeight: 600 }}>{sourceLabel(scan.source)}</p>
              <p style={{ fontSize: 10.5, color: 'rgba(255,255,255,0.35)' }}>{scan.modsDir}</p>
            </div>

            {!fixedInstanceId && (
              <div className="flex flex-shrink-0 gap-1 rounded-xl p-1" style={{ background: 'rgba(0,0,0,0.3)' }}>
                {(['new', 'existing'] as const).map((m) => (
                  <button
                    key={m}
                    onClick={() => handleDestModeChange(m)}
                    className="flex-1 rounded-lg text-xs font-semibold transition-all duration-150"
                    style={{ height: 32, background: destMode === m ? 'rgba(75,63,207,0.4)' : 'transparent', color: destMode === m ? 'white' : 'rgba(255,255,255,0.4)' }}
                  >
                    {m === 'new' ? 'Nouvelle instance' : 'Instance existante'}
                  </button>
                ))}
              </div>
            )}

            {destMode === 'new' ? (
              <div className="flex flex-shrink-0 flex-col gap-2">
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Nom de l'instance"
                  className="w-full rounded-xl px-3 text-sm font-medium text-white outline-none"
                  style={{ height: 38, background: 'rgba(0,0,0,0.4)', border: '1px solid rgba(255,255,255,0.1)' }}
                />
                <div className="flex gap-2">
                  <select
                    value={mcVersion}
                    onChange={(e) => setMcVersion(e.target.value)}
                    className="flex-1 rounded-xl px-2 text-xs font-medium text-white outline-none"
                    style={{ height: 34, background: 'rgba(0,0,0,0.4)', border: '1px solid rgba(255,255,255,0.1)' }}
                  >
                    {(mcVersion && !releaseVersions.includes(mcVersion) ? [mcVersion, ...releaseVersions] : releaseVersions).map((v) => (
                      <option key={v} value={v} style={{ background: '#111118' }}>{v}</option>
                    ))}
                  </select>
                  <div className="flex gap-1">
                    {LOADERS.map((l) => (
                      <button
                        key={l}
                        onClick={() => setLoader(l)}
                        className="rounded-xl text-xs font-semibold"
                        style={{
                          height: 34, padding: '0 10px',
                          background: loader === l ? 'rgba(75,63,207,0.35)' : 'rgba(0,0,0,0.35)',
                          border: `1px solid ${loader === l ? 'rgba(75,63,207,0.7)' : 'rgba(255,255,255,0.08)'}`,
                          color: loader === l ? 'rgba(255,255,255,0.95)' : 'rgba(255,255,255,0.35)',
                        }}
                      >
                        {l.charAt(0).toUpperCase() + l.slice(1)}
                      </button>
                    ))}
                  </div>
                  <select
                    value={ram}
                    onChange={(e) => setRam(Number(e.target.value))}
                    className="rounded-xl px-2 text-xs font-medium text-white outline-none"
                    style={{ height: 34, background: 'rgba(0,0,0,0.4)', border: '1px solid rgba(255,255,255,0.1)' }}
                  >
                    {RAM_OPTIONS.map((r) => (
                      <option key={r} value={r} style={{ background: '#111118' }}>{r >= 1024 ? `${r / 1024} Go` : `${r} Mo`}</option>
                    ))}
                  </select>
                </div>
              </div>
            ) : (
              <select
                value={targetInstanceId}
                onChange={(e) => handleTargetInstanceChange(e.target.value)}
                className="w-full flex-shrink-0 rounded-xl px-3 text-sm font-medium text-white outline-none"
                style={{ height: 38, background: 'rgba(0,0,0,0.4)', border: '1px solid rgba(255,255,255,0.1)' }}
              >
                {instances.map((i) => (
                  <option key={i.id} value={i.id} style={{ background: '#111118' }}>{i.name}</option>
                ))}
              </select>
            )}

            {scan.extraDirs.length > 0 && (
              <div className="flex flex-shrink-0 flex-wrap gap-2">
                {scan.extraDirs.map((dir) => (
                  <label
                    key={dir}
                    className="flex cursor-pointer items-center gap-1.5 rounded-lg px-2.5 py-1"
                    style={{ background: extraDirsSelected.has(dir) ? 'rgba(75,63,207,0.18)' : 'rgba(255,255,255,0.04)' }}
                  >
                    <input type="checkbox" checked={extraDirsSelected.has(dir)} onChange={() => toggleExtraDir(dir)} />
                    <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.75)', fontWeight: 600 }}>
                      {EXTRA_DIR_LABELS[dir] ?? dir}
                    </span>
                  </label>
                ))}
              </div>
            )}

            <div className="flex flex-shrink-0 items-center justify-between">
              <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.4)', fontWeight: 600 }}>
                {selected.size} / {scan.mods.length} mods sélectionnés
                {checkingDuplicates ? ' · vérification des doublons...' : ''}
              </p>
              <button onClick={toggleAll} style={{ fontSize: 11, color: 'rgba(179,163,255,0.9)', fontWeight: 600 }}>
                {selected.size === scan.mods.length ? 'Tout désélectionner' : 'Tout sélectionner'}
              </button>
            </div>

            <div className="flex flex-1 flex-col gap-1 overflow-y-auto rounded-xl p-2" style={{ background: 'rgba(0,0,0,0.25)', minHeight: 120 }}>
              {scan.mods.length === 0 && (
                <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.3)', textAlign: 'center', padding: '16px 0' }}>Aucun .jar trouvé</p>
              )}
              {scan.mods.map((m) => {
                const isDuplicate = duplicates.has(m.name)
                return (
                  <label key={m.name} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5" style={{ background: selected.has(m.name) ? 'rgba(75,63,207,0.12)' : 'transparent' }}>
                    <input type="checkbox" checked={selected.has(m.name)} onChange={() => toggleMod(m.name)} />
                    <span className="flex-1 truncate" style={{ fontSize: 12, color: isDuplicate ? 'rgba(255,255,255,0.4)' : 'rgba(255,255,255,0.85)' }}>{m.name}</span>
                    {isDuplicate && (
                      <span style={{ fontSize: 9.5, color: 'rgba(179,163,255,0.8)', fontWeight: 600 }}>déjà présent</span>
                    )}
                    <span style={{ fontSize: 10, color: 'rgba(255,255,255,0.3)' }}>{formatBytes(m.size)}</span>
                  </label>
                )
              })}
            </div>

            {error && <p style={{ fontSize: 12, color: 'rgb(248,113,113)' }}>{error}</p>}

            {applying && progress && (
              <div className="flex flex-shrink-0 flex-col gap-1">
                <div className="h-1.5 w-full overflow-hidden rounded-full" style={{ background: 'rgba(255,255,255,0.08)' }}>
                  <div
                    className="h-full rounded-full transition-all duration-150"
                    style={{
                      width: `${progress.total > 0 ? Math.min(100, (progress.current / progress.total) * 100) : 0}%`,
                      background: '#4B3FCF',
                    }}
                  />
                </div>
                <p style={{ fontSize: 10.5, color: 'rgba(255,255,255,0.4)' }}>
                  {progress.phase === 'mods'
                    ? `Copie des mods... ${progress.current}/${progress.total}`
                    : `Copie de ${progress.label ?? 'fichiers additionnels'}...`}
                </p>
              </div>
            )}

            <div className="flex flex-shrink-0 gap-2">
              <button
                onClick={() => setStep('pick')}
                disabled={applying}
                className="rounded-xl px-4 text-xs font-semibold"
                style={{ height: 40, background: 'rgba(255,255,255,0.05)', color: 'rgba(255,255,255,0.5)' }}
              >
                Retour
              </button>
              <button
                onClick={handleApply}
                disabled={applying}
                className="flex-1 rounded-xl font-bold text-white transition-all duration-150 active:scale-95"
                style={{ height: 40, background: applying ? 'rgba(75,63,207,0.3)' : '#4B3FCF', fontSize: 13 }}
              >
                {applying ? 'Import...' : `Importer (${selected.size})`}
              </button>
            </div>
          </div>
        )}

        {step === 'done' && result && (
          <div className="flex flex-col items-center gap-3 py-6">
            <div style={{ fontSize: 32 }}>✅</div>
            <p style={{ fontSize: 13, color: 'white', fontWeight: 600, textAlign: 'center' }}>
              {result.imported} mod(s) importé(s)
              {result.skipped > 0 ? `, ${result.skipped} déjà présent(s) ignoré(s)` : ''}
              {result.extraCopied > 0 ? ` · ${result.extraCopied} fichier(s) de config/resourcepacks/shaders repris` : ''}
            </p>
            <button
              onClick={onClose}
              className="rounded-xl px-5 font-bold text-white"
              style={{ height: 40, background: '#4B3FCF', fontSize: 13 }}
            >
              Fermer
            </button>
          </div>
        )}
    </ModalShell>
  )
}
