import { useEffect, useState } from 'react'
import { SNAP, press, pressIf } from '@/lib/motion'
import { motion } from 'framer-motion'
import { api } from '@/api/client'
import type { Mod } from '@/types'
import { formatBytes, formatDownloadCount } from '@/lib/format'
import { Spinner } from '@/components/ui/Spinner'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { CloseButton } from '@/components/ui/CloseButton'
import { Toggle } from '@/components/ui/Toggle'
import { RichDescription } from '@/components/ui/RichDescription'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { showError } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import {
  fetchProjectDetail, fetchProjectVersions, versionTypeBadge, formatGameVersions,
  type ModrinthHit, type ModrinthProjectDetail, type ModrinthVersionEntry,
} from './modUtils'

type Tab = 'versions' | 'description'

/**
 * La fiche d'un mod Modrinth.
 *
 * - Ouverte depuis le **catalogue** (ou le bouton de changement de version
 *   d'un mod installé) : deux onglets, **Versions** et **Description**.
 * - Ouverte en cliquant un **mod installé** (`mode="installed"`) : la
 *   description seule — les versions, c'est le bouton aux flèches de la ligne.
 *
 * La description est le Markdown de Modrinth, HTML compris, rendu et nettoyé
 * par `RichDescription` (le même composant que les fiches de serveurs) : elle
 * s'affichait avant en texte brut, balises comprises.
 */
export function ModDetailModal({
  hit, instanceId, mcVersion, loader, installedMod, installedVersionNumber, onClose, onInstall, mode = 'catalog',
}: {
  hit: ModrinthHit
  instanceId: string
  mcVersion: string
  loader: string
  installedMod: Mod | null
  installedVersionNumber: string | null
  onClose: () => void
  onInstall: (file: { url: string; filename: string }) => Promise<void>
  mode?: 'catalog' | 'installed'
}) {
  const t = useT()
  const isModPinned = useStore((s) => s.isModPinned)
  const setModPinned = useStore((s) => s.setModPinned)
  const pinned = !!installedMod && isModPinned(instanceId, hit.project_id)
  const descriptionOnly = mode === 'installed'
  const [tab, setTab] = useState<Tab>(descriptionOnly ? 'description' : 'versions')
  const [detail, setDetail] = useState<ModrinthProjectDetail | null>(null)
  const [detailFailed, setDetailFailed] = useState(false)
  const [versions, setVersions] = useState<ModrinthVersionEntry[]>([])
  const [loadingVersions, setLoadingVersions] = useState(true)
  const [showAllVersions, setShowAllVersions] = useState(false)
  const [installingId, setInstallingId] = useState<string | null>(null)

  useEffect(() => {
    api.analytics.track('mod_detail_opened', { project_id: hit.project_id })
    fetchProjectDetail(hit.project_id).then((d) => (d ? setDetail(d) : setDetailFailed(true)))
  }, [hit.project_id])

  useEffect(() => {
    if (descriptionOnly) return
    setLoadingVersions(true)
    fetchProjectVersions(hit.project_id, loader, showAllVersions ? null : mcVersion)
      .then(setVersions)
      .finally(() => setLoadingVersions(false))
  }, [hit.project_id, loader, mcVersion, showAllVersions, descriptionOnly])

  // Échap ferme la fiche.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

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

  const tabs: { key: Tab; label: string }[] = [
    { key: 'versions', label: t('mods.tabs.versions') },
    { key: 'description', label: t('mods.tabs.description') },
  ]

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(0,0,0,0.6)] p-6 backdrop-blur-[4px]"
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div className="flex h-[min(85vh,820px)] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-[rgba(255,255,255,0.08)] bg-[#111116] shadow-[0_24px_80px_rgba(0,0,0,0.6)]">
        {/* En-tête */}
        <div className="flex flex-shrink-0 items-start gap-4 px-6 pb-4 pt-6">
          <div className="flex h-[60px] w-[60px] flex-shrink-0 items-center justify-center overflow-hidden rounded-[16px] bg-[rgba(255,255,255,0.06)]">
            {hit.icon_url ? <img src={hit.icon_url} alt="" className="h-full w-full object-cover" /> : <PlugIcon size={26} color="rgba(255,255,255,0.2)" />}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-[18px] font-black text-white">{hit.title}</p>
            <p className="mt-0.5 line-clamp-2 text-[12.5px] text-[rgba(255,255,255,0.5)]">{hit.description}</p>
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <span className="flex items-center gap-1 text-[11px] text-[rgba(255,255,255,0.35)]">
                <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
                {formatDownloadCount(hit.downloads)}
              </span>
              {hit.categories.slice(0, 4).map((c) => (
                <span key={c} className="rounded-full bg-[rgba(255,255,255,0.06)] px-2 py-0.5 text-[10px] text-[rgba(255,255,255,0.45)]">{c}</span>
              ))}
              {installedVersionNumber && (
                <span className="rounded-full bg-[rgba(75,63,207,0.2)] px-2 py-0.5 text-[10px] font-semibold text-[rgba(179,163,255,0.9)]">
                  {t('mods.installedVersion', { version: installedVersionNumber })}
                </span>
              )}
            </div>
          </div>
          <CloseButton onClick={onClose} />
        </div>

        {/* Onglets — absents pour un mod installé, qui n'a que sa description */}
        {!descriptionOnly && (
          <nav className="flex flex-shrink-0 gap-1 border-b border-[rgba(255,255,255,0.07)] px-6">
            {tabs.map((tb) => (
              <button
                key={tb.key}
                onClick={() => setTab(tb.key)}
                className={`relative px-4 pb-3 pt-1 text-[13px] font-bold transition-colors ${tab === tb.key ? 'text-white' : 'text-[rgba(255,255,255,0.4)] hover:text-[rgba(255,255,255,0.7)]'}`}
              >
                {tb.label}
                {tab === tb.key && <motion.span layoutId="mod-detail-tab" transition={SNAP} className="absolute inset-x-2 -bottom-px h-[2px] rounded-full bg-accent" />}
              </button>
            ))}
          </nav>
        )}

        {tab === 'description' ? (
          <div className={`min-h-0 flex-1 overflow-y-auto px-7 py-6 ${descriptionOnly ? 'border-t border-[rgba(255,255,255,0.07)]' : ''}`}>
            {detail ? (
              detail.body?.trim() ? (
                <RichDescription source={detail.body} />
              ) : (
                <p className="text-[13px] text-[rgba(255,255,255,0.6)]">{hit.description}</p>
              )
            ) : detailFailed ? (
              <p className="text-[13px] text-[rgba(255,255,255,0.6)]">{hit.description}</p>
            ) : (
              <Spinner />
            )}
          </div>
        ) : (
          <div className="flex min-h-0 flex-1 flex-col gap-3 px-6 py-4">
            <div className="flex flex-shrink-0 items-center justify-between">
              <p className="text-[11px] font-bold uppercase tracking-[0.06em] text-[rgba(255,255,255,0.4)]">
                {t('mods.availableVersions')}
              </p>
              <motion.button {...press}
                onClick={() => setShowAllVersions((v) => !v)}
                className={`rounded-lg border border-[rgba(255,255,255,0.08)] px-2.5 py-1 text-[10.5px] font-semibold ${
                  showAllVersions ? 'bg-[rgba(75,63,207,0.3)] text-white' : 'bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.45)]'
                }`}
              >
                {showAllVersions ? t('mods.allVersions') : t('mods.compatibleWith', { version: mcVersion })}
              </motion.button>
            </div>

            <div className="flex min-h-0 flex-1 flex-col gap-1.5 overflow-y-auto pr-1">
              {loadingVersions ? (
                <Spinner />
              ) : versions.length === 0 ? (
                <p className="py-4 text-center text-[12px] text-[rgba(255,255,255,0.3)]">
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
                      className={`flex items-center gap-3 rounded-xl border border-[rgba(255,255,255,0.06)] px-3 py-2 ${
                        isInstalledVersion ? 'bg-[rgba(75,63,207,0.1)]' : 'bg-[rgba(255,255,255,0.03)]'
                      }`}
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="text-[12.5px] font-semibold text-white">{v.version_number}</span>
                          <span className={`text-[9.5px] font-bold uppercase ${
                            badge.label === 'release'
                              ? 'text-[rgba(74,222,128,0.85)]'
                              : badge.label === 'beta'
                                ? 'text-[rgba(250,204,21,0.85)]'
                                : 'text-[rgba(248,113,113,0.85)]'
                          }`}>{badge.label}</span>
                        </div>
                        <p className="mt-0.5 text-[10.5px] text-[rgba(255,255,255,0.3)]">
                          MC {formatGameVersions(v.game_versions)} · {file ? formatBytes(file.size) : '—'}
                        </p>
                      </div>
                      <motion.button {...pressIf(!(isInstalledVersion || installing || !file))}
                        onClick={() => handleInstall(v)}
                        disabled={isInstalledVersion || installing || !file}
                        className={`flex h-7 flex-shrink-0 items-center gap-1.5 rounded-lg border px-3 text-[11px] font-semibold transition-all duration-150 active:scale-95 ${
                          isInstalledVersion
                            ? 'cursor-not-allowed border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.3)]'
                            : installing
                              ? 'cursor-not-allowed border-[rgba(75,63,207,0.5)] bg-[rgba(40,38,65,0.7)] text-[rgba(255,255,255,0.85)]'
                              : 'cursor-pointer border-[rgba(75,63,207,0.5)] bg-[rgba(75,63,207,0.3)] text-[rgba(255,255,255,0.85)]'
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
              <div className="flex flex-shrink-0 items-center justify-between rounded-xl border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.03)] px-3.5 py-2.5">
                <div>
                  <p className="text-[12px] font-semibold text-[rgba(255,255,255,0.75)]">{t('mods.ignoreUpdates')}</p>
                  <p className="mt-0.5 text-[10.5px] text-[rgba(255,255,255,0.3)]">{t('mods.ignoreUpdatesDesc')}</p>
                </div>
                <Toggle checked={pinned} onChange={() => setModPinned(instanceId, hit.project_id, !pinned)} size="sm" />
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
