import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { listen } from '@tauri-apps/api/event'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { FileTree } from '@/components/sync/FileTree'
import { ProgressBar } from '@/components/sync/ProgressBar'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { showError, showNotice } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { formatBytes, formatRelativeTime } from '@/lib/format'
import { loaderColor } from '@/lib/loader'
import { SNAP, listItemVariants, listVariants, press } from '@/lib/motion'
import { useT } from '@/i18n'
import type { SyncDiff, SyncInstance as SyncInstanceType, SyncManifest, SyncProgress } from '@/types'

/**
 * Une instance synchronisée, en détail.
 *
 * Le panneau dépliant ne pouvait montrer qu'un résumé, alors que le serveur
 * connaît chaque fichier : cette page ouvre la boîte. Deux choses y vivent que
 * le protocole par morceaux rendait possibles sans qu'on s'en serve :
 *
 * - **l'arborescence** de ce qui est réellement stocké, avec le poids de
 *   chaque dossier — de quoi savoir ce qui remplit le quota ;
 * - **la comparaison** entre ce PC et le serveur, calculée sans rien
 *   transférer. On sait avant de cliquer que trois mods ont été ajoutés, un
 *   modifié, et que douze mégaoctets partiront — pas la taille des fichiers
 *   changés, mais celle des morceaux que le serveur n'a pas encore.
 */

const KIND_STYLES: Record<string, string> = {
  added: 'bg-success/15 text-success',
  modified: 'bg-warning/15 text-warning',
  removed: 'bg-danger/15 text-danger',
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-xl border border-line bg-surface-1 px-3.5 py-2.5">
      <span className="truncate text-[9.5px] font-bold uppercase tracking-[0.08em] text-txt-muted">{label}</span>
      <span className="truncate text-[15px] font-black leading-none text-txt-primary">{value}</span>
      {hint && <span className="truncate text-[10px] text-txt-muted">{hint}</span>}
    </div>
  )
}

export default function SyncInstancePage() {
  const t = useT()
  const navigate = useNavigate()
  const { syncId } = useParams<{ syncId: string }>()
  const id = Number(syncId)
  const instances = useStore((s) => s.instances)

  const [remote, setRemote] = useState<SyncInstanceType | null>(null)
  const [manifest, setManifest] = useState<SyncManifest | null>(null)
  const [diff, setDiff] = useState<SyncDiff | null>(null)
  const [loading, setLoading] = useState(true)
  const [comparing, setComparing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<SyncProgress | null>(null)

  // L'instance locale porte le même nom que l'entrée serveur : c'est la clé
  // du serveur, donc le seul lien possible.
  const local = instances.find((i) => i.name === remote?.instance_name) ?? null

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [list, m] = await Promise.all([api.sync.list(), api.sync.manifest(id)])
      setRemote(list.find((x) => x.id === id) ?? null)
      setManifest(m)
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    load()
  }, [load])

  // La comparaison relit toute l'instance locale : jamais automatique, sinon
  // ouvrir la page ferait tourner le disque pour rien.
  async function compare() {
    if (!local) return
    setComparing(true)
    try {
      setDiff(await api.sync.diff(id, local.id))
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setComparing(false)
    }
  }

  async function transfer(direction: 'push' | 'pull') {
    if (!local) return
    setBusy(true)
    setProgress({ phase: direction === 'push' ? 'uploading' : 'downloading', percent: 0, label: t('sync.starting') })
    const unlisten = await listen<SyncProgress>('sync_progress', (ev) => setProgress(ev.payload))
    try {
      if (direction === 'push') await api.sync.push(local.id)
      else await api.sync.pull(id, local.id)
      showNotice(direction === 'push' ? t('sync.savedToCloud') : t('sync.dataRestored'))
      setDiff(null)
      await load()
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      unlisten()
      setTimeout(() => setProgress(null), 1200)
      setBusy(false)
    }
  }

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div className="flex min-w-0 items-center gap-3">
          <motion.button
            {...press}
            onClick={() => navigate('/sync')}
            className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-txt-muted transition-colors duration-150 hover:bg-surface-2 hover:text-txt-primary"
            title={t('sync.back')}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" className="h-4 w-4">
              <path d="M15 18l-6-6 6-6" />
            </svg>
          </motion.button>
          <div className="min-w-0">
            <h1 className="truncate text-[16px] font-black leading-[1.2] tracking-[-0.01em]">{remote?.instance_name ?? '…'}</h1>
            <p className="mt-px text-[10px] text-txt-muted">
              {remote ? `Minecraft ${remote.mc_version} · ${remote.loader}` : t('common.loading')}
            </p>
          </div>
        </div>
      </PageHeader>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex w-full max-w-4xl flex-col gap-5 px-6 py-6">
          {loading ? (
            <div className="flex items-center justify-center py-20">
              <ButtonSpinner size={30} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
            </div>
          ) : !remote || !manifest ? (
            <p className="py-20 text-center text-[13px] text-txt-muted">{t('sync.instanceGone')}</p>
          ) : (
            <>
              <div className="grid grid-cols-4 gap-3">
                <Stat label={t('sync.statFiles')} value={String(remote.file_count)} />
                <Stat label={t('sync.statSize')} value={formatBytes(remote.total_bytes)} />
                <Stat label={t('sync.statRevision')} value={`#${remote.revision}`} hint={t('sync.statRevisionHint')} />
                <Stat
                  label={t('sync.statUpdated')}
                  value={formatRelativeTime(Math.floor(new Date(remote.updated_at).getTime() / 1000))}
                />
              </div>

              {/* Comparaison avec ce PC. */}
              <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="min-w-0">
                    <p className="text-[12.5px] font-semibold">{t('sync.compareTitle')}</p>
                    <p className="mt-0.5 text-[11px] leading-relaxed text-txt-muted">
                      {local ? t('sync.compareText') : t('sync.compareNoLocal')}
                    </p>
                  </div>
                  {local && (
                    <Button size="sm" variant="secondary" onClick={compare} loading={comparing} disabled={comparing || busy}>
                      {t('sync.compareAction')}
                    </Button>
                  )}
                </div>

                <AnimatePresence initial={false}>
                  {diff && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: 'auto', opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      transition={{ duration: 0.24, ease: [0.16, 1, 0.3, 1] }}
                      className="overflow-hidden"
                    >
                      <div className="flex flex-col gap-3 border-t border-line-soft pt-3">
                        {diff.entries.length === 0 ? (
                          <p className="text-[11.5px] text-success">{t('sync.diffNone', { count: diff.unchanged })}</p>
                        ) : (
                          <>
                            <p className="text-[11.5px] text-txt-secondary">
                              {t('sync.diffSummary', {
                                changed: diff.entries.length,
                                unchanged: diff.unchanged,
                                size: formatBytes(diff.upload_bytes),
                              })}
                            </p>
                            <motion.div variants={listVariants} initial="initial" animate="animate" className="flex max-h-56 flex-col gap-0.5 overflow-y-auto">
                              {diff.entries.map((e) => (
                                <motion.div key={`${e.kind}:${e.path}`} variants={listItemVariants} className="flex items-center gap-2 rounded-md px-1.5 py-1">
                                  <span className={`w-[68px] shrink-0 rounded px-1.5 text-center text-[9px] font-bold uppercase tracking-wide ${KIND_STYLES[e.kind]}`}>
                                    {t(`sync.diff.${e.kind}`)}
                                  </span>
                                  <span className="min-w-0 flex-1 truncate font-mono text-[10.5px] text-txt-secondary">{e.path}</span>
                                  <span className="shrink-0 text-[10px] tabular-nums text-txt-muted">{formatBytes(e.size)}</span>
                                </motion.div>
                              ))}
                            </motion.div>
                          </>
                        )}
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>

                <AnimatePresence initial={false}>
                  {progress && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: 'auto', opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      transition={SNAP}
                      className="overflow-hidden rounded-xl border border-accent/20 bg-accent/[0.08] px-3 py-2.5"
                    >
                      <ProgressBar progress={progress} />
                    </motion.div>
                  )}
                </AnimatePresence>

                {local && (
                  <div className="flex gap-2">
                    <Button size="sm" variant="primary" onClick={() => transfer('push')} disabled={busy} loading={busy && progress?.phase === 'uploading'}>
                      {t('sync.pushFromHere')}
                    </Button>
                    <Button size="sm" variant="secondary" onClick={() => transfer('pull')} disabled={busy}>
                      {t('sync.pullToHere')}
                    </Button>
                  </div>
                )}
              </div>

              <FileTree files={manifest.files} />

              <div className="flex items-center justify-between gap-3 pb-2">
                <span className="text-[10.5px] text-txt-muted">{t('sync.instanceFooter', { loader: remote.loader })}</span>
                <span className="text-[10.5px] font-bold" style={{ color: loaderColor(remote.loader) }}>
                  {remote.mc_version}
                </span>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
