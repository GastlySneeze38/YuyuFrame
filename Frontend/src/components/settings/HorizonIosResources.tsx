import { useEffect, useState } from 'react'
import { open as openDialog, save as saveDialog } from '@tauri-apps/plugin-dialog'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { formatBytes } from '@/lib/format'
import { isAndroidApp, isIosApp } from '@/lib/platform'
import { useHorizonResources } from '@/lib/horizonResources'
import { useStore } from '@/stores/useStore'
import { ModalShell } from '@/components/ui/ModalShell'
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

/**
 * iPhone (2026-11-28) : les fichiers se **téléchargent** depuis notre serveur
 * (`lib/horizonResources.ts`), compte YuyuFrame connecté. L'importation d'un
 * zip exporté depuis un PC reste, en secours (pas de réseau, serveur sans
 * zip pour cette version).
 */
function IosImport() {
  const t = useT()
  const { ready, refresh } = useHorizonResources()
  const [importing, setImporting] = useState(false)
  useEffect(() => { void refresh() }, [refresh])
  const importFiles = async () => {
    const picked = await openDialog({ multiple: false, filters: [{ name: 'Zip', extensions: ['zip'] }] }).catch(() => null)
    if (!picked || Array.isArray(picked)) return
    setImporting(true)
    try {
      await api.horizon.iosResourcesImport(picked)
      useHorizonResources.setState({ ready: true })
    } catch (e) {
      showError(e)
    } finally {
      setImporting(false)
    }
  }
  return (
    <div className={BOX}>
      <p className="text-sm font-medium text-white">{t('horizonEngine.iosResources.importTitle')}</p>
      <p className="text-[11px] text-white/40">{t('horizonEngine.iosResources.downloadDesc')}</p>
      {ready !== null && (
        <p className={`text-[11px] ${ready ? 'text-[rgba(134,239,172,0.85)]' : 'text-white/55'}`}>
          {ready ? t('horizonEngine.iosResources.ready') : t('horizonEngine.iosResources.missing')}
        </p>
      )}
      <DownloadButton />
      <button onClick={importFiles} disabled={importing} className={BUTTON}>
        {importing ? t('horizonEngine.iosResources.importing') : t('horizonEngine.iosResources.importFile')}
      </button>
    </div>
  )
}

/** Le bouton de téléchargement, ou sa progression tant qu'il tourne. */
function DownloadButton({ onDone }: { onDone?: () => void }) {
  const t = useT()
  const signedIn = useStore((s) => s.yuyuSignedIn)
  const { ready, downloading, progress, download } = useHorizonResources()
  if (!signedIn) return <p className="text-[12px] text-white/55">{t('horizonEngine.iosResources.needSignIn')}</p>
  if (downloading) {
    const ratio = progress && progress.total > 0 ? Math.min(1, progress.done / progress.total) : null
    return (
      <div className="flex flex-col gap-1.5">
        <div className="h-1.5 overflow-hidden rounded-full bg-white/10">
          <div
            className="h-full rounded-full bg-[rgb(var(--accent))] transition-[width] duration-300"
            style={{ width: `${(ratio ?? 0) * 100}%` }}
          />
        </div>
        <p className="text-[11px] tabular-nums text-white/55">
          {ratio === null
            ? t('horizonEngine.iosResources.downloadStarting')
            : ratio < 1
              ? t('horizonEngine.iosResources.downloading', { done: formatBytes(progress!.done), total: formatBytes(progress!.total) })
              : t('horizonEngine.iosResources.importing')}
        </p>
      </div>
    )
  }
  return (
    <button
      onClick={async () => { if (await download()) onDone?.() }}
      className="self-stretch rounded-xl bg-[rgb(var(--accent))] px-4 py-2.5 text-[14px] font-semibold text-white transition-transform duration-150 active:scale-[0.98]"
    >
      {ready ? t('horizonEngine.iosResources.redownload') : t('horizonEngine.iosResources.downloadButton')}
    </button>
  )
}

/**
 * Ouverte au lancement d'une instance Horizon sans fichiers (iPhone,
 * `useMobileLaunch`) : les télécharger, puis le jeu démarre tout seul. Posée
 * une fois, avec les événements du lancement (`NavDrawer`).
 */
export function HorizonResourcesSheet({ onReady }: { onReady: (instanceId: string, server?: string) => void }) {
  const t = useT()
  const { pending, dismiss, downloading } = useHorizonResources()
  if (!pending || !isIosApp) return null
  return (
    <ModalShell title={t('horizonEngine.iosResources.importTitle')} onClose={dismiss}>
      <div className="flex flex-col gap-3">
        <p className="text-[14px] leading-relaxed text-white/70">{t('horizonEngine.iosResources.downloadDesc')}</p>
        <p className="text-[12px] text-white/45">{t('horizonEngine.iosResources.launchAfter')}</p>
        <DownloadButton
          onDone={() => {
            dismiss()
            onReady(pending.instanceId, pending.server)
          }}
        />
        {!downloading && (
          <button onClick={dismiss} className="self-center px-3 py-1.5 text-[13px] text-white/50">
            {t('common.cancel')}
          </button>
        )}
      </div>
    </ModalShell>
  )
}
