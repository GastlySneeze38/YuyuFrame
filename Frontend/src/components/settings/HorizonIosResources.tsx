import { useEffect, useState } from 'react'
import { open as openDialog, save as saveDialog } from '@tauri-apps/plugin-dialog'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { formatBytes } from '@/lib/format'
import { isAndroidApp, isIosApp } from '@/lib/platform'
import { useT } from '@/i18n'

/**
 * Les fichiers du jeu d'Horizon, du PC vers l'iPhone (`horizon/transfer.rs`).
 * L'iPhone ne peut pas les préparer (il faudrait Java) : sur ordinateur, ce
 * bloc les exporte en un zip ; sur iPhone, il l'importe. Rien sur Android, qui
 * les prépare lui-même.
 */
export function HorizonIosResources() {
  if (isAndroidApp) return null
  return isIosApp ? <IosImport /> : <DesktopExport />
}

const BOX = 'flex flex-col gap-2 rounded-xl border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.03)] px-4 py-3'
const BUTTON = 'self-start rounded-lg bg-white/5 px-3 py-1.5 text-[11px] font-semibold text-white/70 hover:bg-white/10 disabled:opacity-40'

function DesktopExport() {
  const t = useT()
  const [busy, setBusy] = useState(false)
  const [size, setSize] = useState<number | null>(null)
  const exportFiles = async () => {
    const path = await saveDialog({
      defaultPath: 'horizon-26.3-iphone.zip',
      filters: [{ name: 'Zip', extensions: ['zip'] }],
    }).catch(() => null)
    if (!path) return
    setBusy(true)
    setSize(null)
    try {
      setSize(await api.horizon.iosResourcesExport(path))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className={BOX}>
      <p className="text-sm font-medium text-white">{t('horizonEngine.iosResources.exportTitle')}</p>
      <p className="text-[11px] text-white/40">{t('horizonEngine.iosResources.exportDesc')}</p>
      <button onClick={exportFiles} disabled={busy} className={BUTTON}>
        {busy ? t('horizonEngine.iosResources.exporting') : t('horizonEngine.iosResources.exportButton')}
      </button>
      {size !== null && (
        <p className="text-[11px] text-[rgba(134,239,172,0.85)]">
          {t('horizonEngine.iosResources.exported', { size: formatBytes(size) })}
        </p>
      )}
    </div>
  )
}

function IosImport() {
  const t = useT()
  const [ready, setReady] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { api.horizon.iosResourcesStatus().then(setReady).catch(() => {}) }, [])
  const importFiles = async () => {
    const picked = await openDialog({ multiple: false, filters: [{ name: 'Zip', extensions: ['zip'] }] }).catch(() => null)
    if (!picked || Array.isArray(picked)) return
    setBusy(true)
    try {
      await api.horizon.iosResourcesImport(picked)
      setReady(true)
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className={BOX}>
      <p className="text-sm font-medium text-white">{t('horizonEngine.iosResources.importTitle')}</p>
      <p className="text-[11px] text-white/40">{t('horizonEngine.iosResources.importDesc')}</p>
      {ready !== null && (
        <p className={`text-[11px] ${ready ? 'text-[rgba(134,239,172,0.85)]' : 'text-white/55'}`}>
          {ready ? t('horizonEngine.iosResources.ready') : t('horizonEngine.iosResources.missing')}
        </p>
      )}
      <button onClick={importFiles} disabled={busy} className={BUTTON}>
        {busy ? t('horizonEngine.iosResources.importing') : t('horizonEngine.iosResources.importButton')}
      </button>
    </div>
  )
}
