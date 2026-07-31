import { useEffect, useRef, useState } from 'react'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import type { Instance, SaveInfo, SyncInstance, SyncProgress } from '@/types'
import { formatRelativeTime } from '@/lib/format'
import { loaderColor } from '@/lib/loader'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { showError, showApiError } from '@/stores/useErrorToast'
import { isNetworkError } from '@/lib/apiError'
import { ProgressBar } from './ProgressBar'
import { CloudContentSummary } from './CloudContentSummary'
import { SaveSelector } from './SaveSelector'
import { useT } from '@/i18n'

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
  const t = useT()
  const [expanded, setExpanded] = useState(false)
  const [saves, setSaves] = useState<SaveInfo[]>([])
  const [selectedSaves, setSelectedSaves] = useState<Set<string>>(new Set())
  const [savesLoaded, setSavesLoaded] = useState(false)
  const [savesLoading, setSavesLoading] = useState(false)
  const [pushing, setPushing] = useState(false)
  const [pulling, setPulling] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [progress, setProgress] = useState<SyncProgress | null>(null)
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
      // Chargement automatique à l'expansion de la carte — pas de toast pour
      // une panne réseau (badge TitleBar déjà là), juste pour les vraies erreurs.
      .catch((e) => { if (!isNetworkError(e)) showError(e); setSavesLoaded(true) })
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
    setProgress({ phase: 'resolving_mods', percent: 0, label: t('sync.starting') })

    const unlisten = await listen<SyncProgress>('sync_progress', (ev) => {
      setProgress(ev.payload)
    })
    unlistenRef.current = unlisten

    try {
      const updated = await api.sync.push(instance.id, Array.from(selectedSaves))
      onCloudUpdate(updated)
      flash(t('sync.savedToCloud'))
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
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
    setProgress({ phase: 'downloading', percent: 0, label: t('sync.restoreStarting') })

    const unlisten = await listen<SyncProgress>('sync_progress', (ev) => {
      setProgress(ev.payload)
    })
    unlistenRef.current = unlisten

    try {
      await api.sync.pull(cloudEntry.id, instance.id)
      flash(t('sync.dataRestored'))
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
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
    try {
      await api.sync.delete(cloudEntry.id)
      onCloudDelete(cloudEntry.id)
      setExpanded(false)
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div
      className={`rounded-2xl overflow-hidden transition-all duration-200 bg-[rgba(255,255,255,0.03)] border ${expanded ? 'border-[rgba(75,63,207,0.35)]' : 'border-[rgba(255,255,255,0.07)]'}`}
    >
      {/* ── Header row ── */}
      <button
        className={`flex items-center gap-3 w-full px-4 py-3 text-left ${busy ? 'cursor-not-allowed' : 'cursor-pointer'}`}
        onClick={() => { if (!busy) setExpanded((v) => !v) }}
        disabled={busy}
      >
        <div
          className={`flex items-center justify-center rounded-xl flex-shrink-0 w-9 h-9 text-[15px] ${hasSynced ? 'bg-[rgba(75,63,207,0.12)]' : 'bg-[rgba(255,255,255,0.05)]'}`}
        >
          🧱
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <p className="font-bold truncate text-[13px] text-[rgba(255,255,255,0.88)]">
              {instance.name}
            </p>
            <span
              className="text-[10px] font-bold bg-[rgba(255,255,255,0.05)] px-1.5 py-px rounded flex-shrink-0"
              style={{ color: loaderColor(instance.loader) }}
            >
              {instance.mc_version}
            </span>
          </div>
          <p className="text-[11px] mt-0.5">
            {cloudEntry?.has_data
              ? <span className="text-[rgba(74,222,128,0.7)]">
                  {t('sync.savedTimeAgo', { time: formatRelativeTime(cloudEntry.updated_at) })}
                  {cloudEntry.save_names.length > 0 && (
                    <span className="text-[rgba(255,255,255,0.2)] ml-1.5">
                      {t('sync.saveCountSuffix', { count: cloudEntry.save_names.length, s: cloudEntry.save_names.length > 1 ? 's' : '' })}
                    </span>
                  )}
                </span>
              : <span className="text-[rgba(255,255,255,0.22)]">{t('sync.neverSaved')}</span>
            }
          </p>
        </div>

        {/* Chevron */}
        <div
          className={`flex items-center justify-center flex-shrink-0 rounded-lg transition-all duration-200 w-7 h-7 ${expanded ? 'bg-[rgba(75,63,207,0.2)] rotate-180' : 'bg-[rgba(255,255,255,0.04)] rotate-0'}`}
        >
          <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className={expanded ? 'text-[rgba(180,170,255,0.8)]' : 'text-[rgba(255,255,255,0.3)]'}>
            <path d="M7 10l5 5 5-5z" />
          </svg>
        </div>
      </button>

      {/* ── Expanded panel ── */}
      {expanded && (
        <div
          className="flex flex-col gap-4 px-4 pb-4 border-t border-[rgba(255,255,255,0.06)] pt-4"
        >
          {/* Cloud content */}
          {hasSynced && cloudEntry && (
            <>
              <CloudContentSummary cloudEntry={cloudEntry} />
              <div className="h-px bg-[rgba(255,255,255,0.05)]" />
            </>
          )}

          {/* Save selector */}
          {savesLoading ? (
            <div className="flex items-center gap-2 py-1">
              <ButtonSpinner size={14} color="rgba(75,63,207,0.8)" trackColor="rgba(255,255,255,0.08)" />
              <span className="text-[12px] text-[rgba(255,255,255,0.25)]">{t('sync.loadingSaves')}</span>
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
            <div className="rounded-xl px-3 py-2.5 bg-[rgba(75,63,207,0.08)] border border-[rgba(75,63,207,0.2)]">
              <ProgressBar progress={progress} />
            </div>
          )}

          {/* Action buttons */}
          <div className="flex gap-2">
            {/* Restore */}
            {hasSynced && (
              <button
                onClick={handlePull}
                disabled={busy}
                className={`flex-1 flex items-center justify-center gap-1.5 font-semibold transition-all duration-150 h-[38px] rounded-[10px] text-[12px] bg-[rgba(255,255,255,0.05)] border border-[rgba(255,255,255,0.08)] ${busy ? 'text-[rgba(255,255,255,0.2)] cursor-not-allowed' : 'text-[rgba(255,255,255,0.55)] cursor-pointer hover:bg-[rgba(255,255,255,0.09)]'}`}
              >
                {pulling
                  ? <ButtonSpinner size={14} />
                  : <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12} className="rotate-180 flex-shrink-0"><path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z" /></svg>
                }
                {t('sync.restore')}
              </button>
            )}

            {/* Push */}
            <button
              onClick={handlePush}
              disabled={pushing || savesLoading}
              className={`flex items-center justify-center gap-1.5 font-bold text-white transition-all duration-150 active:scale-95 h-[38px] rounded-[10px] text-[12px] ${hasSynced ? 'flex-[2]' : 'flex-1'} ${(pushing || savesLoading) ? 'bg-[rgba(40,38,65,0.7)] shadow-none cursor-not-allowed' : 'bg-[#4B3FCF] shadow-[0_4px_16px_rgba(75,63,207,0.28)] cursor-pointer hover:bg-[#6155e8]'}`}
            >
              {pushing
                ? <ButtonSpinner size={14} />
                : <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12} className="flex-shrink-0"><path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z" /></svg>
              }
              {pushing
                ? t('sync.saving')
                : hasSynced
                  ? `${t('sync.updateLabel')}${selectedSaves.size > 0 ? t('sync.saveCountParen', { count: selectedSaves.size, s: selectedSaves.size > 1 ? 's' : '' }) : ''}`
                  : `${t('sync.saveLabel')}${selectedSaves.size > 0 ? t('sync.saveCountParen', { count: selectedSaves.size, s: selectedSaves.size > 1 ? 's' : '' }) : ''}`
              }
            </button>

            {/* Delete cloud */}
            {cloudEntry && (
              <button
                onClick={handleDelete}
                disabled={busy}
                title={t('sync.deleteCloudBackup')}
                className={`flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl transition-all duration-150 bg-[rgba(255,255,255,0.04)] ${busy ? 'text-[rgba(255,255,255,0.18)] cursor-not-allowed' : 'text-[rgba(255,255,255,0.18)] cursor-pointer hover:text-[rgb(248,113,113)] hover:bg-[rgba(200,50,50,0.12)]'}`}
              >
                {deleting
                  ? <ButtonSpinner size={14} color="rgb(248,113,113)" trackColor="rgba(255,255,255,0.15)" />
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
          className="flex items-center gap-2 px-4 py-2 border-t border-[rgba(74,222,128,0.12)] bg-[rgba(74,222,128,0.05)]"
        >
          <svg viewBox="0 0 24 24" fill="rgb(74,222,128)" width={12} height={12}>
            <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z" />
          </svg>
          <p className="text-[11px] text-[rgb(74,222,128)] font-semibold">{success}</p>
        </div>
      )}
    </div>
  )
}
