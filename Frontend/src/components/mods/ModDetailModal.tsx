import { useEffect, useState } from 'react'
import { api } from '@/api/client'
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
import {
  fetchProjectDetail, fetchProjectVersions, stripMarkdown, versionTypeBadge, formatGameVersions,
  type ModrinthHit, type ModrinthProjectDetail, type ModrinthVersionEntry,
} from './modUtils'

export function ModDetailModal({
  hit, instanceId, mcVersion, loader, installedMod, installedVersionNumber, onClose, onInstall,
}: {
  hit: ModrinthHit
  instanceId: string
  mcVersion: string
  loader: string
  installedMod: Mod | null
  installedVersionNumber: string | null
  onClose: () => void
  onInstall: (file: { url: string; filename: string }) => Promise<void>
}) {
  const t = useT()
  const isModPinned = useStore((s) => s.isModPinned)
  const setModPinned = useStore((s) => s.setModPinned)
  const pinned = !!installedMod && isModPinned(instanceId, hit.project_id)
  const [detail, setDetail] = useState<ModrinthProjectDetail | null>(null)
  const [versions, setVersions] = useState<ModrinthVersionEntry[]>([])
  const [loadingVersions, setLoadingVersions] = useState(true)
  const [showAllVersions, setShowAllVersions] = useState(false)
  const [showFullBody, setShowFullBody] = useState(false)
  const [installingId, setInstallingId] = useState<string | null>(null)

  useEffect(() => {
    api.analytics.track('mod_detail_opened', { project_id: hit.project_id })
    fetchProjectDetail(hit.project_id).then(setDetail)
  }, [hit.project_id])

  useEffect(() => {
    setLoadingVersions(true)
    fetchProjectVersions(hit.project_id, loader, showAllVersions ? null : mcVersion)
      .then(setVersions)
      .finally(() => setLoadingVersions(false))
  }, [hit.project_id, loader, mcVersion, showAllVersions])

  const handleInstall = async (version: ModrinthVersionEntry) => {
    const file = version.files.find((f) => f.primary) ?? version.files[0]
    if (!file) return
    setInstallingId(version.id)
    try {
      await onInstall(file)
      // Choisi une version qui n'est pas la plus récente de la liste affichée
      // → downgrade délibéré, on épingle pour ne plus proposer de mise à jour.
      if (installedMod && versions[0]?.id !== version.id) {
        setModPinned(instanceId, hit.project_id, true)
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
            {hit.icon_url ? <img src={hit.icon_url} alt="" className="w-full h-full object-cover" /> : <PlugIcon size={24} color="rgba(255,255,255,0.2)" />}
          </div>
          <div className="min-w-0 flex-1">
            <p className="font-bold text-white text-[16px]">{hit.title}</p>
            <p className="text-[12px] text-[rgba(255,255,255,0.4)] mt-0.5">{hit.description}</p>
            <div className="flex flex-wrap items-center gap-1.5 mt-2">
              <span className="flex items-center gap-1 text-[11px] text-[rgba(255,255,255,0.3)]">
                <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
                {formatDownloadCount(hit.downloads)}
              </span>
              {hit.categories.slice(0, 4).map((c) => (
                <span key={c} className="rounded-full px-2 py-0.5 text-[10px] bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.4)]">{c}</span>
              ))}
              {installedVersionNumber && (
                <span className="rounded-full px-2 py-0.5 font-semibold text-[10px] bg-[rgba(75,63,207,0.2)] text-[rgba(179,163,255,0.9)]">
                  {t('mods.installedVersion', { version: installedVersionNumber })}
                </span>
              )}
            </div>
          </div>
          <CloseButton onClick={onClose} />
        </div>

        {/* Description complète (repliable) */}
        {detail?.body && (
          <div className="flex-shrink-0">
            <button
              onClick={() => setShowFullBody((v) => !v)}
              className="text-[11.5px] text-[rgba(179,163,255,0.9)] font-semibold"
            >
              {showFullBody ? t('mods.hideFullDescription') : t('mods.showFullDescription')}
            </button>
            {showFullBody && (
              <div
                className="mt-2 overflow-y-auto rounded-xl p-3 max-h-[160px] bg-[rgba(0,0,0,0.3)] border border-[rgba(255,255,255,0.06)]"
              >
                <p className="text-[12px] text-[rgba(255,255,255,0.6)] whitespace-pre-wrap leading-[1.5]">
                  {stripMarkdown(detail.body)}
                </p>
              </div>
            )}
          </div>
        )}

        {/* Versions */}
        <div className="flex flex-shrink-0 items-center justify-between">
          <p className="text-[11px] font-bold text-[rgba(255,255,255,0.4)] uppercase tracking-[0.06em]">
            {t('mods.availableVersions')}
          </p>
          <button
            onClick={() => setShowAllVersions((v) => !v)}
            className={`rounded-lg px-2.5 py-1 font-semibold text-[10.5px] border border-[rgba(255,255,255,0.08)] ${
              showAllVersions ? 'bg-[rgba(75,63,207,0.3)] text-white' : 'bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.45)]'
            }`}
          >
            {showAllVersions ? t('mods.allVersions') : t('mods.compatibleWith', { version: mcVersion })}
          </button>
        </div>

        <div className="flex flex-1 flex-col gap-1.5 overflow-y-auto">
          {loadingVersions ? (
            <Spinner />
          ) : versions.length === 0 ? (
            <p className="text-[12px] text-[rgba(255,255,255,0.3)] text-center py-4">
              {t('mods.noVersion')} {showAllVersions ? '' : t('mods.compatibleWithVersion', { version: mcVersion })}
            </p>
          ) : (
            versions.map((v) => {
              const file = v.files.find((f) => f.primary) ?? v.files[0]
              const isInstalledVersion = !!installedMod && !!file?.hashes?.sha1 && file.hashes.sha1 === installedMod.sha1
              const badge = versionTypeBadge(v.version_type)
              const installing = installingId === v.id
              return (
                <div
                  key={v.id}
                  className={`flex items-center gap-3 rounded-xl px-3 py-2 border border-[rgba(255,255,255,0.06)] ${
                    isInstalledVersion ? 'bg-[rgba(75,63,207,0.1)]' : 'bg-[rgba(255,255,255,0.03)]'
                  }`}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-white text-[12.5px]">{v.version_number}</span>
                      <span className={`text-[9.5px] font-bold uppercase ${
                        badge.label === 'release'
                          ? 'text-[rgba(74,222,128,0.85)]'
                          : badge.label === 'beta'
                            ? 'text-[rgba(250,204,21,0.85)]'
                            : 'text-[rgba(248,113,113,0.85)]'
                      }`}>{badge.label}</span>
                    </div>
                    <p className="text-[10.5px] text-[rgba(255,255,255,0.3)] mt-0.5">
                      MC {formatGameVersions(v.game_versions)} · {file ? formatBytes(file.size) : '—'}
                    </p>
                  </div>
                  <button
                    onClick={() => handleInstall(v)}
                    disabled={isInstalledVersion || installing || !file}
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
                  </button>
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
            <Toggle checked={pinned} onChange={() => setModPinned(instanceId, hit.project_id, !pinned)} size="sm" />
          </div>
        )}
      </div>
    </div>
  )
}
