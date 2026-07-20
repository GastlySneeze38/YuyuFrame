import { useEffect, useRef, useState } from 'react'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import type { Instance, SaveInfo, SyncInstance, SyncProgress } from '@/types'
import { loaderColor } from '@/lib/loader'
import { formatRelativeTime } from '@/lib/format'
import { ProgressBar } from './ProgressBar'
import { CloudContentSummary } from './CloudContentSummary'
import { SaveSelector } from './SaveSelector'

interface InstanceSyncCardProps {
  instance: Instance
  cloudEntry: SyncInstance | undefined
  maxSaves: number
  onCloudUpdate: (updated: SyncInstance) => void
  onCloudDelete: (id: number) => void
}

export function InstanceSyncCard({
  instance, cloudEntry, maxSaves, onCloudUpdate, onCloudDelete,
}: InstanceSyncCardProps) {
  const [expanded, setExpanded] = useState(false)
  const [saves, setSaves] = useState<SaveInfo[]>([])
  const [selectedSaves, setSelectedSaves] = useState<Set<string>>(new Set())
  const [savesLoaded, setSavesLoaded] = useState(false)
  const [savesLoading, setSavesLoading] = useState(false)
  const [pushing, setPushing] = useState(false)
  const [pulling, setPulling] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [progress, setProgress] = useState<SyncProgress | null>(null)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const unlistenRef = useRef<(() => void) | null>(null)

  const busy = pushing || pulling || deleting
  const hasSynced = !!cloudEntry?.has_data

  // Load saves when first expanded
  useEffect(() => {
    if (!expanded || savesLoaded) return
    setSavesLoading(true)
    api.sync.listSaves(instance.id)
      .then((list) => {
        setSaves(list)
        setSavesLoaded(true)
        // Pre-select saves already in cloud, or the most recent ones
        const inCloud = new Set(cloudEntry?.save_names ?? [])
        const toSelect = list
          .filter((s) => inCloud.has(s.name))
          .map((s) => s.name)
        const auto = toSelect.length > 0
          ? toSelect.slice(0, maxSaves)
          : list.slice(0, maxSaves).map((s) => s.name)
        setSelectedSaves(new Set(auto))
      })
      .catch(() => setSavesLoaded(true))
      .finally(() => setSavesLoading(false))
  }, [expanded])

  const toggleSave = (name: string) => {
    setSelectedSaves((prev) => {
      const next = new Set(prev)
      if (next.has(name)) { next.delete(name) }
      else if (next.size < maxSaves) { next.add(name) }
      return next
    })
  }

  const handleSelectAll = () => {
    if (selectedSaves.size === Math.min(saves.length, maxSaves)) {
      setSelectedSaves(new Set())
    } else {
      setSelectedSaves(new Set(saves.slice(0, maxSaves).map((s) => s.name)))
    }
  }

  const flash = (msg: string) => {
    setSuccess(msg)
    setTimeout(() => setSuccess(''), 3500)
  }

  const handlePush = async () => {
    setPushing(true)
    setError('')
    setProgress({ phase: 'resolving_mods', percent: 0, label: 'Démarrage...' })

    const unlisten = await listen<SyncProgress>('sync_progress', (ev) => {
      setProgress(ev.payload)
    })
    unlistenRef.current = unlisten

    try {
      const updated = await api.sync.push(instance.id, Array.from(selectedSaves))
      onCloudUpdate(updated)
      flash('Sauvegardé dans le cloud !')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      unlisten()
      unlistenRef.current = null
      setTimeout(() => setProgress(null), 1200)
      setPushing(false)
    }
  }

  const handlePull = async () => {
    if (!cloudEntry) return
    setPulling(true)
    setError('')
    setProgress({ phase: 'downloading', percent: 0, label: 'Démarrage de la restauration...' })

    const unlisten = await listen<SyncProgress>('sync_progress', (ev) => {
      setProgress(ev.payload)
    })
    unlistenRef.current = unlisten

    try {
      await api.sync.pull(cloudEntry.id, instance.id)
      flash('Données restaurées !')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      unlisten()
      unlistenRef.current = null
      setTimeout(() => setProgress(null), 1200)
      setPulling(false)
    }
  }

  const handleDelete = async () => {
    if (!cloudEntry) return
    setDeleting(true)
    setError('')
    try {
      await api.sync.delete(cloudEntry.id)
      onCloudDelete(cloudEntry.id)
      setExpanded(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div
      className="rounded-2xl overflow-hidden transition-all duration-200"
      style={{
        background: 'rgba(255,255,255,0.03)',
        border: `1px solid ${expanded ? 'rgba(75,63,207,0.35)' : 'rgba(255,255,255,0.07)'}`,
      }}
    >
      {/* ── Header row ── */}
      <button
        className="flex items-center gap-3 w-full px-4 py-3 text-left"
        onClick={() => { if (!busy) setExpanded((v) => !v) }}
        disabled={busy}
        style={{ cursor: busy ? 'not-allowed' : 'pointer' }}
      >
        <div
          className="flex items-center justify-center rounded-xl flex-shrink-0"
          style={{ width: 36, height: 36, background: hasSynced ? 'rgba(75,63,207,0.12)' : 'rgba(255,255,255,0.05)', fontSize: 15 }}
        >
          🧱
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <p className="font-bold truncate" style={{ fontSize: 13, color: 'rgba(255,255,255,0.88)' }}>
              {instance.name}
            </p>
            <span style={{
              fontSize: 10, fontWeight: 700,
              color: loaderColor(instance.loader),
              background: 'rgba(255,255,255,0.05)',
              padding: '1px 6px', borderRadius: 4, flexShrink: 0,
            }}>
              {instance.mc_version}
            </span>
          </div>
          <p style={{ fontSize: 11, marginTop: 2 }}>
            {hasSynced
              ? <span style={{ color: 'rgba(74,222,128,0.7)' }}>
                  ✓ Sauvegardé {formatRelativeTime(cloudEntry!.updated_at)}
                  {cloudEntry!.save_names.length > 0 && (
                    <span style={{ color: 'rgba(255,255,255,0.2)', marginLeft: 6 }}>
                      · {cloudEntry!.save_names.length} save{cloudEntry!.save_names.length > 1 ? 's' : ''}
                    </span>
                  )}
                </span>
              : <span style={{ color: 'rgba(255,255,255,0.22)' }}>Jamais sauvegardé</span>
            }
          </p>
        </div>

        {/* Chevron */}
        <div
          className="flex items-center justify-center flex-shrink-0 rounded-lg transition-all duration-200"
          style={{
            width: 28, height: 28,
            background: expanded ? 'rgba(75,63,207,0.2)' : 'rgba(255,255,255,0.04)',
            transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)',
          }}
        >
          <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} style={{ color: expanded ? 'rgba(180,170,255,0.8)' : 'rgba(255,255,255,0.3)' }}>
            <path d="M7 10l5 5 5-5z" />
          </svg>
        </div>
      </button>

      {/* ── Expanded panel ── */}
      {expanded && (
        <div
          className="flex flex-col gap-4 px-4 pb-4"
          style={{ borderTop: '1px solid rgba(255,255,255,0.06)', paddingTop: 16 }}
        >
          {/* Cloud content */}
          {hasSynced && cloudEntry && (
            <>
              <CloudContentSummary cloudEntry={cloudEntry} />
              <div className="h-px" style={{ background: 'rgba(255,255,255,0.05)' }} />
            </>
          )}

          {/* Save selector */}
          {savesLoading ? (
            <div className="flex items-center gap-2 py-1">
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 flex-shrink-0" style={{ borderColor: 'rgba(255,255,255,0.08)', borderTopColor: 'rgba(75,63,207,0.8)' }} />
              <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.25)' }}>Chargement des saves...</span>
            </div>
          ) : (
            <SaveSelector
              saves={saves}
              selected={selectedSaves}
              maxSaves={maxSaves}
              disabled={pushing}
              onToggle={toggleSave}
              onSelectAll={handleSelectAll}
            />
          )}

          {/* Progress */}
          {progress && (
            <div className="rounded-xl px-3 py-2.5" style={{ background: 'rgba(75,63,207,0.08)', border: '1px solid rgba(75,63,207,0.2)' }}>
              <ProgressBar progress={progress} />
            </div>
          )}

          {/* Error */}
          {error && <p style={{ fontSize: 12, color: 'rgb(248,113,113)' }}>{error}</p>}

          {/* Action buttons */}
          <div className="flex gap-2">
            {/* Restore */}
            {hasSynced && (
              <button
                onClick={handlePull}
                disabled={busy}
                className="flex items-center justify-center gap-1.5 font-semibold transition-all duration-150"
                style={{
                  flex: 1, height: 38, borderRadius: 10, fontSize: 12,
                  background: 'rgba(255,255,255,0.05)',
                  color: busy ? 'rgba(255,255,255,0.2)' : 'rgba(255,255,255,0.55)',
                  border: '1px solid rgba(255,255,255,0.08)',
                  cursor: busy ? 'not-allowed' : 'pointer',
                }}
                onMouseEnter={(e) => { if (!busy) e.currentTarget.style.background = 'rgba(255,255,255,0.09)' }}
                onMouseLeave={(e) => { if (!busy) e.currentTarget.style.background = 'rgba(255,255,255,0.05)' }}
              >
                {pulling
                  ? <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 flex-shrink-0" style={{ borderColor: 'rgba(255,255,255,0.2)', borderTopColor: 'white' }} />
                  : <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12} style={{ transform: 'rotate(180deg)', flexShrink: 0 }}><path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z" /></svg>
                }
                Restaurer
              </button>
            )}

            {/* Push */}
            <button
              onClick={handlePush}
              disabled={pushing || savesLoading}
              className="flex items-center justify-center gap-1.5 font-bold text-white transition-all duration-150 active:scale-95"
              style={{
                flex: hasSynced ? 2 : 1, height: 38, borderRadius: 10, fontSize: 12,
                background: (pushing || savesLoading) ? 'rgba(40,38,65,0.7)' : '#4B3FCF',
                boxShadow: (pushing || savesLoading) ? 'none' : '0 4px 16px rgba(75,63,207,0.28)',
                cursor: (pushing || savesLoading) ? 'not-allowed' : 'pointer',
              }}
              onMouseEnter={(e) => { if (!pushing && !savesLoading) e.currentTarget.style.background = '#6155e8' }}
              onMouseLeave={(e) => { if (!pushing && !savesLoading) e.currentTarget.style.background = '#4B3FCF' }}
            >
              {pushing
                ? <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 flex-shrink-0" style={{ borderColor: 'rgba(255,255,255,0.2)', borderTopColor: 'white' }} />
                : <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12} style={{ flexShrink: 0 }}><path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z" /></svg>
              }
              {pushing
                ? 'Sauvegarde...'
                : hasSynced
                  ? `Mettre à jour${selectedSaves.size > 0 ? ` (${selectedSaves.size} save${selectedSaves.size > 1 ? 's' : ''})` : ''}`
                  : `Sauvegarder${selectedSaves.size > 0 ? ` (${selectedSaves.size} save${selectedSaves.size > 1 ? 's' : ''})` : ''}`
              }
            </button>

            {/* Delete cloud */}
            {cloudEntry && (
              <button
                onClick={handleDelete}
                disabled={busy}
                title="Supprimer la sauvegarde cloud"
                className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl transition-all duration-150"
                style={{ color: 'rgba(255,255,255,0.18)', background: 'rgba(255,255,255,0.04)', cursor: busy ? 'not-allowed' : 'pointer' }}
                onMouseEnter={(e) => { if (!busy) { e.currentTarget.style.color = 'rgb(248,113,113)'; e.currentTarget.style.background = 'rgba(200,50,50,0.12)' } }}
                onMouseLeave={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.18)'; e.currentTarget.style.background = 'rgba(255,255,255,0.04)' }}
              >
                {deleting
                  ? <span className="h-3.5 w-3.5 animate-spin rounded-full border-2" style={{ borderColor: 'rgba(255,255,255,0.15)', borderTopColor: 'rgb(248,113,113)' }} />
                  : <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" /></svg>
                }
              </button>
            )}
          </div>
        </div>
      )}

      {/* ── Success toast ── */}
      {success && (
        <div
          className="flex items-center gap-2 px-4 py-2"
          style={{ borderTop: '1px solid rgba(74,222,128,0.12)', background: 'rgba(74,222,128,0.05)' }}
        >
          <svg viewBox="0 0 24 24" fill="rgb(74,222,128)" width={12} height={12}>
            <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z" />
          </svg>
          <p style={{ fontSize: 11, color: 'rgb(74,222,128)', fontWeight: 600 }}>{success}</p>
        </div>
      )}
    </div>
  )
}
