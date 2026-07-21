import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { Instance, Loader } from '@/types'
import { INSTANCE_PRESETS, type InstancePreset } from '@/data/presets'
import { loaderColor } from '@/lib/loader'
import { ModalShell } from '@/components/ui/ModalShell'
import { RamPicker } from '@/components/ui/RamPicker'
import { showError } from '@/stores/useErrorToast'
import { NameInput, DescriptionInput, SubmitButton, VersionSelect, LoaderPicker } from './InstanceFormFields'
import { PresetCard } from './PresetCard'

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
  onProgress: (done: number, total: number) => void,
) {
  for (let i = 0; i < preset.mods.length; i++) {
    onProgress(i, preset.mods.length)
    const entry = preset.mods[i]
    const file = typeof entry === 'string'
      ? await fetchPresetModFile(entry, preset.mcVersion, preset.loader)
      : entry
    if (!file) continue
    try { await api.mods.install(instanceId, file.url, file.filename) } catch { /* best-effort */ }
  }
  onProgress(preset.mods.length, preset.mods.length)
}

export function CreateInstanceModal({
  versions,
  defaultRam,
  onClose,
  onCreate,
}: {
  versions: string[]
  defaultRam: number
  onClose: () => void
  onCreate: (instance: Instance) => void
}) {
  const [mode, setMode] = useState<'blank' | 'preset'>('blank')
  const [selectedPreset, setSelectedPreset] = useState<InstancePreset | null>(null)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [mcVersion, setMcVersion] = useState(versions[0] ?? '')
  const [loader, setLoader] = useState<Loader>('vanilla')
  const [ram, setRam] = useState(defaultRam)
  const [loading, setLoading] = useState(false)
  const [loadingLabel, setLoadingLabel] = useState('Création...')

  useEffect(() => {
    if (versions.length > 0 && !mcVersion) setMcVersion(versions[0])
  }, [versions])

  const handleSelectPreset = (preset: InstancePreset) => {
    setSelectedPreset(preset)
    setName(preset.name)
    setMcVersion(preset.mcVersion)
    setLoader(preset.loader)
    setRam(preset.ramMb)
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
    }
  }

  const handleCreate = async () => {
    if (!name.trim()) { showError('Nom requis'); return }
    if (!mcVersion) { showError('Sélectionne une version'); return }
    setLoading(true); setLoadingLabel('Création...')
    try {
      const instance = await api.instances.create(name.trim(), mcVersion, loader, ram, description.trim())
      if (selectedPreset) {
        await installPresetMods(instance.id, selectedPreset, (done, total) => {
          setLoadingLabel(`Installation des mods (${done}/${total})...`)
        })
      }
      onCreate(instance)
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setLoading(false)
    }
  }

  return (
    <ModalShell title="Nouvelle instance" onClose={onClose}>
      <div className="flex flex-col gap-4">
        <div className="flex gap-1 rounded-xl p-1" style={{ background: 'rgba(0,0,0,0.3)' }}>
          {(['blank', 'preset'] as const).map((m) => (
            <button
              key={m}
              onClick={() => handleSwitchMode(m)}
              className="flex-1 rounded-lg text-xs font-semibold transition-all duration-150"
              style={{
                height: 32,
                background: mode === m ? 'rgba(75,63,207,0.4)' : 'transparent',
                color: mode === m ? 'white' : 'rgba(255,255,255,0.4)',
              }}
            >
              {m === 'blank' ? 'Vierge' : 'Modpack'}
            </button>
          ))}
        </div>

        {mode === 'preset' && (
          <div className="grid grid-cols-1 gap-2" style={{ maxHeight: 200, overflowY: 'auto' }}>
            {INSTANCE_PRESETS.map((p) => (
              <PresetCard key={p.id} preset={p} selected={selectedPreset?.id === p.id} onSelect={() => handleSelectPreset(p)} />
            ))}
          </div>
        )}

        {(mode === 'blank' || selectedPreset) && (
          <>
            <NameInput value={name} onChange={setName} onEnter={handleCreate} />

            {mode === 'blank' ? (
              <div className="flex gap-3">
                <VersionSelect versions={versions} value={mcVersion} onChange={setMcVersion} className="flex-1" />
                <LoaderPicker value={loader} onChange={setLoader} />
              </div>
            ) : (
              <div className="flex items-center gap-2 rounded-xl px-3 py-2" style={{ background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.06)' }}>
                <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.3)' }}>{mcVersion}</span>
                <span style={{ fontSize: 10, color: loaderColor(loader), fontWeight: 600 }}>{loader}</span>
                <span style={{ fontSize: 10, color: 'rgba(255,255,255,0.2)' }}>· {selectedPreset!.mods.length} mods installés automatiquement</span>
              </div>
            )}

            <RamPicker value={ram} onChange={setRam} />

            <DescriptionInput value={description} onChange={setDescription} />

            <SubmitButton loading={loading} label="Créer l'instance" loadingLabel={loadingLabel} onClick={handleCreate} />
          </>
        )}
      </div>
    </ModalShell>
  )
}
