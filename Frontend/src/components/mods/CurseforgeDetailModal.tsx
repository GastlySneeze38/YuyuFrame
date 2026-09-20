import { useEffect, useState } from 'react'
import { press, pressIf } from '@/lib/motion'
import { motion } from 'framer-motion'
import type { Mod } from '@/types'
import { formatBytes, formatDownloadCount } from '@/lib/format'
import { Spinner } from '@/components/ui/Spinner'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { CloseButton } from '@/components/ui/CloseButton'
import { Toggle } from '@/components/ui/Toggle'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { showError } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import { versionTypeBadge, formatGameVersions } from './modUtils'
import { fetchCurseforgeFiles, filterFilesForLoader, type CurseforgeHit, type CurseforgeFile } from './curseforgeUtils'

const RELEASE_TYPE_LABEL: Record<number, string> = { 1: 'release', 2: 'beta', 3: 'alpha' }

/// Ne garde que les entrées `gameVersions` qui ressemblent à une version MC
/// (commence par un chiffre) — CurseForge mélange loader et version MC dans
/// le même tableau (ex: `["1.20.1", "Fabric"]`), les tags loader n'ont rien
/// à faire dans l'affichage "MC ...".
function mcVersionsOnly(gameVersions: string[]): string[] {
  return gameVersions.filter((v) => /^\d/.test(v))
}

/// Écran de switch de version pour un mod CurseForge déjà installé — mirroir exact de
/// ModDetailModal.tsx (Modrinth), même structure/style, adapté aux données CurseForge
/// (fichiers au lieu de versions Modrinth, pas de description longue disponible).
export function CurseforgeDetailModal({
  hit, instanceId, mcVersion, loader, installedMod, installedFileId, onClose, onInstall,
}: {
  hit: CurseforgeHit
  instanceId: string
  mcVersion: string
  loader: string
  installedMod: Mod | null
  installedFileId: number | null
  onClose: () => void
  onInstall: (file: { url: string; filename: string }) => Promise<void>
}) {
  const t = useT()
  const isModPinned = useStore((s) => s.isModPinned)
  const setModPinned = useStore((s) => s.setModPinned)
  const pinKey = `cf:${hit.id}`
  const pinned = !!installedMod && isModPinned(instanceId, pinKey)
  const [files, setFiles] = useState<CurseforgeFile[]>([])
  const [loadingFiles, setLoadingFiles] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [showAllVersions, setShowAllVersions] = useState(false)
  const [installingId, setInstallingId] = useState<number | null>(null)

  useEffect(() => {
    setLoadingFiles(true)
    setLoadError('')
    // Toujours récupérer TOUS les fichiers (pas de `gameVersion` passé à l'API CurseForge) et
    // filtrer côté client — le filtrage serveur via `gameVersion` s'est avéré peu fiable
    // (renvoyait vide pour des versions MC pourtant présentes dans la liste complète).
    fetchCurseforgeFiles(hit.id, '')
      .then((all) => setFiles(filterFilesForLoader(all, loader, showAllVersions ? undefined : mcVersion)))
      .catch((e) => {
        console.error('fetchCurseforgeFiles failed', hit.id, e)
        setLoadError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => setLoadingFiles(false))
  }, [hit.id, loader, mcVersion, showAllVersions])

  const handleInstall = async (file: CurseforgeFile) => {
    if (!file.downloadUrl) return
    setInstallingId(file.id)
    try {
      await onInstall({ url: file.downloadUrl, filename: file.fileName })
      // Choisi un fichier qui n'est pas le plus récent de la liste affichée
      // → downgrade délibéré, on épingle pour ne plus proposer de mise à jour.
      if (installedMod && files[0]?.id !== file.id) {
        setModPinned(instanceId, pinKey, true)
      }
    } catch (e) {
      showError(e)
    } finally {
      setInstallingId(null)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(0,0,0,0.6)] backdrop-blur-[4px]"
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div
        className="flex w-full max-w-2xl flex-col gap-4 rounded-2xl p-6 bg-[#111118] border border-[rgba(75,63,207,0.3)] shadow-[0_24px_80px_rgba(0,0,0,0.6)] max-h-[85vh]"
      >
        {/* Header */}
        <div className="flex flex-shrink-0 items-start gap-3">
          <div className="w-[52px] h-[52px] rounded-[14px] flex-shrink-0 overflow-hidden bg-[rgba(255,255,255,0.06)] flex items-center justify-center">
            {hit.logoUrl ? <img src={hit.logoUrl} alt="" className="w-full h-full object-cover" /> : <PlugIcon size={24} color="rgba(255,255,255,0.2)" />}
          </div>
          <div className="min-w-0 flex-1">
            <p className="font-bold text-white text-[16px]">{hit.name}</p>
            <p className="text-[12px] text-[rgba(255,255,255,0.4)] mt-0.5">{hit.summary}</p>
            <div className="flex flex-wrap items-center gap-1.5 mt-2">
              <span className="flex items-center gap-1 text-[11px] text-[rgba(255,255,255,0.3)]">
                <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
                {formatDownloadCount(hit.downloadCount)}
              </span>
            </div>
          </div>
          <CloseButton onClick={onClose} />
        </div>

        {/* Versions */}
        <div className="flex flex-shrink-0 items-center justify-between">
          <p className="text-[11px] font-bold text-[rgba(255,255,255,0.4)] uppercase tracking-[0.06em]">
            {t('mods.availableVersions')}
          </p>
          <motion.button {...press}
            onClick={() => setShowAllVersions((v) => !v)}
            className={`rounded-lg px-2.5 py-1 font-semibold text-[10.5px] border border-[rgba(255,255,255,0.08)] ${
              showAllVersions ? 'bg-[rgba(75,63,207,0.3)] text-white' : 'bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.45)]'
            }`}
          >
            {showAllVersions ? t('mods.allVersions') : t('mods.compatibleWith', { version: mcVersion })}
          </motion.button>
        </div>

        <div className="flex flex-1 flex-col gap-1.5 overflow-y-auto">
          {loadingFiles ? (
            <Spinner />
          ) : loadError ? (
            <p className="text-[12px] text-[rgba(248,113,113,0.8)] text-center py-4">
              {loadError}
            </p>
          ) : files.length === 0 ? (
            <p className="text-[12px] text-[rgba(255,255,255,0.3)] text-center py-4">
              {t('mods.noVersion')} {showAllVersions ? '' : t('mods.compatibleWithVersion', { version: mcVersion })}
            </p>
          ) : (
            files.map((file) => {
              const isInstalledVersion = installedFileId != null && file.id === installedFileId
              const badge = versionTypeBadge(RELEASE_TYPE_LABEL[file.releaseType] ?? 'alpha')
              const installing = installingId === file.id
              return (
                <div
                  key={file.id}
                  className={`flex items-center gap-3 rounded-xl px-3 py-2 border border-[rgba(255,255,255,0.06)] ${
                    isInstalledVersion ? 'bg-[rgba(75,63,207,0.1)]' : 'bg-[rgba(255,255,255,0.03)]'
                  }`}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-semibold text-white text-[12.5px]">{file.displayName}</span>
                      <span className={`flex-shrink-0 text-[9.5px] font-bold uppercase ${
                        badge.label === 'release'
                          ? 'text-[rgba(74,222,128,0.85)]'
                          : badge.label === 'beta'
                            ? 'text-[rgba(250,204,21,0.85)]'
                            : 'text-[rgba(248,113,113,0.85)]'
                      }`}>{badge.label}</span>
                    </div>
                    <p className="text-[10.5px] text-[rgba(255,255,255,0.3)] mt-0.5">
                      MC {formatGameVersions(mcVersionsOnly(file.gameVersions))} · {formatBytes(file.fileLength)}
                    </p>
                  </div>
                  <motion.button {...pressIf(!(isInstalledVersion || installing || !file.downloadUrl))}
                    onClick={() => handleInstall(file)}
                    disabled={isInstalledVersion || installing || !file.downloadUrl}
                    className={`flex-shrink-0 flex items-center gap-1.5 rounded-lg font-semibold transition-all duration-150 active:scale-95 h-7 px-3 text-[11px] border ${
                      isInstalledVersion
                        ? 'bg-[rgba(255,255,255,0.05)] border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.3)] cursor-not-allowed'
                        : installing
                          ? 'bg-[rgba(40,38,65,0.7)] border-[rgba(75,63,207,0.5)] text-[rgba(255,255,255,0.85)] cursor-not-allowed'
                          : 'bg-[rgba(75,63,207,0.3)] border-[rgba(75,63,207,0.5)] text-[rgba(255,255,255,0.85)] cursor-pointer'
                    }`}
                  >
                    {installing ? (
                      <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.15)" />
                    ) : isInstalledVersion ? t('mods.installedBadge') : installedMod ? t('mods.switchButton') : t('mods.installButton')}
                  </motion.button>
                </div>
              )
            })
          )}
        </div>

        {installedMod && (
          <div className="flex flex-shrink-0 items-center justify-between rounded-xl px-3.5 py-2.5 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)]">
            <div>
              <p className="text-[12px] font-semibold text-[rgba(255,255,255,0.75)]">{t('mods.ignoreUpdates')}</p>
              <p className="text-[10.5px] text-[rgba(255,255,255,0.3)] mt-0.5">
                {t('mods.ignoreUpdatesDesc')}
              </p>
            </div>
            <Toggle checked={pinned} onChange={() => setModPinned(instanceId, pinKey, !pinned)} size="sm" />
          </div>
        )}
      </div>
    </div>
  )
}
