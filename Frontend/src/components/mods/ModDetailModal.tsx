import { useEffect, useState } from 'react'
import type { Mod } from '@/types'
import { formatBytes, formatDownloadCount } from '@/lib/format'
import { Spinner } from '@/components/ui/Spinner'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import {
  fetchProjectDetail, fetchProjectVersions, stripMarkdown, versionTypeBadge, formatGameVersions,
  type ModrinthHit, type ModrinthProjectDetail, type ModrinthVersionEntry,
} from './modUtils'

export function ModDetailModal({
  hit, mcVersion, loader, installedMod, installedVersionNumber, onClose, onInstall,
}: {
  hit: ModrinthHit
  mcVersion: string
  loader: string
  installedMod: Mod | null
  installedVersionNumber: string | null
  onClose: () => void
  onInstall: (file: { url: string; filename: string }) => Promise<void>
}) {
  const [detail, setDetail] = useState<ModrinthProjectDetail | null>(null)
  const [versions, setVersions] = useState<ModrinthVersionEntry[]>([])
  const [loadingVersions, setLoadingVersions] = useState(true)
  const [showAllVersions, setShowAllVersions] = useState(false)
  const [showFullBody, setShowFullBody] = useState(false)
  const [installingId, setInstallingId] = useState<string | null>(null)
  const [installError, setInstallError] = useState('')

  useEffect(() => {
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
    setInstallError('')
    setInstallingId(version.id)
    try {
      await onInstall(file)
    } catch (e) {
      setInstallError(e instanceof Error ? e.message : "Erreur lors de l'installation")
    } finally {
      setInstallingId(null)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)' }}
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div
        className="flex w-full max-w-2xl flex-col gap-4 rounded-2xl p-6"
        style={{ background: '#111118', border: '1px solid rgba(75,63,207,0.3)', boxShadow: '0 24px 80px rgba(0,0,0,0.6)', maxHeight: '85vh' }}
      >
        {/* Header */}
        <div className="flex flex-shrink-0 items-start gap-3">
          <div style={{ width: 52, height: 52, borderRadius: 14, flexShrink: 0, overflow: 'hidden', background: 'rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            {hit.icon_url ? <img src={hit.icon_url} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} /> : <PlugIcon size={24} color="rgba(255,255,255,0.2)" />}
          </div>
          <div className="min-w-0 flex-1">
            <p className="font-bold text-white" style={{ fontSize: 16 }}>{hit.title}</p>
            <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.4)', marginTop: 2 }}>{hit.description}</p>
            <div className="flex flex-wrap items-center gap-1.5 mt-2">
              <span className="flex items-center gap-1" style={{ fontSize: 11, color: 'rgba(255,255,255,0.3)' }}>
                <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
                {formatDownloadCount(hit.downloads)}
              </span>
              {hit.categories.slice(0, 4).map((c) => (
                <span key={c} className="rounded-full px-2 py-0.5" style={{ fontSize: 10, background: 'rgba(255,255,255,0.06)', color: 'rgba(255,255,255,0.4)' }}>{c}</span>
              ))}
              {installedVersionNumber && (
                <span className="rounded-full px-2 py-0.5 font-semibold" style={{ fontSize: 10, background: 'rgba(75,63,207,0.2)', color: 'rgba(179,163,255,0.9)' }}>
                  Installé : {installedVersionNumber}
                </span>
              )}
            </div>
          </div>
          <button
            onClick={onClose}
            className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-lg"
            style={{ color: 'rgba(255,255,255,0.3)', background: 'rgba(255,255,255,0.05)' }}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}>
              <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
            </svg>
          </button>
        </div>

        {/* Description complète (repliable) */}
        {detail?.body && (
          <div className="flex-shrink-0">
            <button
              onClick={() => setShowFullBody((v) => !v)}
              style={{ fontSize: 11.5, color: 'rgba(179,163,255,0.9)', fontWeight: 600 }}
            >
              {showFullBody ? 'Masquer la description complète' : 'Voir la description complète'}
            </button>
            {showFullBody && (
              <div
                className="mt-2 overflow-y-auto rounded-xl p-3"
                style={{ maxHeight: 160, background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.06)' }}
              >
                <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.6)', whiteSpace: 'pre-wrap', lineHeight: 1.5 }}>
                  {stripMarkdown(detail.body)}
                </p>
              </div>
            )}
          </div>
        )}

        {/* Versions */}
        <div className="flex flex-shrink-0 items-center justify-between">
          <p style={{ fontSize: 11, fontWeight: 700, color: 'rgba(255,255,255,0.4)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
            Versions disponibles
          </p>
          <button
            onClick={() => setShowAllVersions((v) => !v)}
            className="rounded-lg px-2.5 py-1 font-semibold"
            style={{
              fontSize: 10.5,
              background: showAllVersions ? 'rgba(75,63,207,0.3)' : 'rgba(255,255,255,0.05)',
              color: showAllVersions ? 'white' : 'rgba(255,255,255,0.45)',
              border: '1px solid rgba(255,255,255,0.08)',
            }}
          >
            {showAllVersions ? `Toutes versions` : `Compatibles ${mcVersion}`}
          </button>
        </div>

        {installError && <p className="flex-shrink-0" style={{ fontSize: 12, color: 'rgb(248,113,113)' }}>{installError}</p>}

        <div className="flex flex-1 flex-col gap-1.5 overflow-y-auto">
          {loadingVersions ? (
            <Spinner />
          ) : versions.length === 0 ? (
            <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.3)', textAlign: 'center', padding: '16px 0' }}>
              Aucune version {showAllVersions ? '' : `compatible avec ${mcVersion}`}
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
                  className="flex items-center gap-3 rounded-xl px-3 py-2"
                  style={{ background: isInstalledVersion ? 'rgba(75,63,207,0.1)' : 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-white" style={{ fontSize: 12.5 }}>{v.version_number}</span>
                      <span style={{ fontSize: 9.5, fontWeight: 700, color: badge.color, textTransform: 'uppercase' }}>{badge.label}</span>
                    </div>
                    <p style={{ fontSize: 10.5, color: 'rgba(255,255,255,0.3)', marginTop: 2 }}>
                      MC {formatGameVersions(v.game_versions)} · {file ? formatBytes(file.size) : '—'}
                    </p>
                  </div>
                  <button
                    onClick={() => handleInstall(v)}
                    disabled={isInstalledVersion || installing || !file}
                    className="flex-shrink-0 flex items-center gap-1.5 rounded-lg font-semibold transition-all duration-150 active:scale-95"
                    style={{
                      height: 28, padding: '0 12px', fontSize: 11,
                      background: isInstalledVersion ? 'rgba(255,255,255,0.05)' : installing ? 'rgba(40,38,65,0.7)' : 'rgba(75,63,207,0.3)',
                      border: `1px solid ${isInstalledVersion ? 'rgba(255,255,255,0.08)' : 'rgba(75,63,207,0.5)'}`,
                      color: isInstalledVersion ? 'rgba(255,255,255,0.3)' : 'rgba(255,255,255,0.85)',
                      cursor: isInstalledVersion || installing ? 'not-allowed' : 'pointer',
                    }}
                  >
                    {installing ? (
                      <span className="h-3 w-3 animate-spin rounded-full border-2" style={{ borderColor: 'rgba(255,255,255,0.15)', borderTopColor: 'white' }} />
                    ) : isInstalledVersion ? '✓ Installée' : installedMod ? 'Basculer' : 'Installer'}
                  </button>
                </div>
              )
            })
          )}
        </div>
      </div>
    </div>
  )
}
