import { useCallback, useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { ModalShell } from '@/components/ui/ModalShell'
import { BackupSettingsPanel } from '@/components/backup/BackupSettingsPanel'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { showError, showNotice } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { formatBytes, formatRelativeTime } from '@/lib/format'
import { SNAP, listItemVariants, listVariants, press } from '@/lib/motion'
import { useT } from '@/i18n'
import type { BackupOverview, BackupSettings, BackupSummary } from '@/types/backup'

/**
 * Sauvegardes.
 *
 * L'écran répond à une seule question — « comment je récupère mon monde ? » —
 * et tout y est rangé pour ça : les sauvegardes d'abord, les réglages après.
 *
 * Le chiffre affiché en haut est la place **réellement** occupée sur le
 * disque, pas la somme des tailles : les morceaux communs à plusieurs
 * sauvegardes n'y sont comptés qu'une fois. Additionner dix instantanés d'un
 * monde de 500 Mo annoncerait 5 Go là où le dépôt en prend six cents.
 */

const TRIGGER_STYLES: Record<string, string> = {
  manual: 'bg-accent/15 text-accent-hover',
  launch: 'bg-success/15 text-success',
  daily: 'bg-warning/15 text-warning',
}

export default function Backup() {
  const t = useT()
  const instances = useStore((s) => s.instances)
  const selectedInstanceId = useStore((s) => s.selectedInstanceId)

  const [instanceId, setInstanceId] = useState<string>(selectedInstanceId ?? '')
  const [data, setData] = useState<BackupOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [settings, setSettings] = useState<BackupSettings | null>(null)
  const [custom, setCustom] = useState(false)
  const [restoring, setRestoring] = useState<BackupSummary | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)

  const scope = instanceId || null

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [overview, s] = await Promise.all([api.backup.list(scope), api.backup.settings(scope)])
      setData(overview)
      setSettings(s.settings)
      setCustom(s.custom)
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setLoading(false)
    }
  }, [scope])

  useEffect(() => {
    load()
  }, [load])

  async function createNow() {
    if (!instanceId) return
    setBusy(true)
    try {
      const made = await api.backup.create(instanceId)
      showNotice(t('backup.created', { files: made.file_count, size: formatBytes(made.total_bytes) }))
      await load()
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function doRestore(replace: boolean) {
    if (!restoring) return
    setBusy(true)
    try {
      const count = await api.backup.restore(restoring.instance_id, restoring.id, replace)
      showNotice(t('backup.restored', { count }))
      setRestoring(null)
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function remove(b: BackupSummary) {
    try {
      await api.backup.delete(b.instance_id, b.id)
      setConfirmDelete(null)
      await load()
    } catch (e) {
      showError(errorMessage(e))
    }
  }

  async function saveSettings(next: BackupSettings) {
    setSettings(next)
    try {
      await api.backup.saveSettings(next, scope)
      setCustom(true)
    } catch (e) {
      showError(errorMessage(e))
    }
  }

  async function resetSettings() {
    if (!instanceId) return
    try {
      await api.backup.resetSettings(instanceId)
      await load()
    } catch (e) {
      showError(errorMessage(e))
    }
  }

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em]">{t('backup.title')}</h1>
          <p className="mt-px text-[10px] text-txt-muted">{t('backup.subtitle')}</p>
        </div>
      </PageHeader>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex w-full max-w-4xl flex-col gap-5 px-6 py-6">
          {/* Choix de l'instance : le backup est par instance, et « toutes »
              sert seulement à voir l'historique complet. */}
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={instanceId}
              onChange={(e) => setInstanceId(e.target.value)}
              aria-label={t('backup.instance')}
              className="h-9 cursor-pointer rounded-xl border border-line bg-surface-1 px-3 text-[12px] font-semibold text-txt-primary outline-none"
            >
              <option value="">{t('backup.allInstances')}</option>
              {instances.map((i) => (
                <option key={i.id} value={i.id}>
                  {i.name}
                </option>
              ))}
            </select>

            {instanceId && (
              <Button size="sm" variant="primary" onClick={createNow} loading={busy} disabled={busy}>
                {t('backup.createNow')}
              </Button>
            )}

            {data && (
              <span className="ml-auto text-[11px] text-txt-muted">
                {t('backup.diskUsage', { size: formatBytes(data.disk_bytes) })}
              </span>
            )}
          </div>

          {loading && !data ? (
            <div className="flex items-center justify-center py-20">
              <ButtonSpinner size={30} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
            </div>
          ) : (
            <>
              {data && data.backups.length === 0 ? (
                <div className="flex flex-col items-center justify-center gap-2 rounded-2xl border border-line bg-surface-1 px-6 py-14 text-center">
                  <p className="text-[13px] font-semibold">{t('backup.emptyTitle')}</p>
                  <p className="max-w-md text-[11.5px] leading-relaxed text-txt-secondary">
                    {instanceId ? t('backup.emptyText') : t('backup.emptyPickInstance')}
                  </p>
                </div>
              ) : (
                <motion.div variants={listVariants} initial="initial" animate="animate" className="flex flex-col gap-1.5">
                  {data?.backups.map((b) => (
                    <motion.div
                      key={b.id}
                      variants={listItemVariants}
                      layout
                      whileHover={{ x: 2 }}
                      transition={SNAP}
                      className="flex items-center gap-3 rounded-xl border border-line-soft bg-surface-2 px-3.5 py-2.5"
                    >
                      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                        <div className="flex min-w-0 items-center gap-2">
                          <span className="truncate text-[12.5px] font-semibold">{b.instance_name}</span>
                          <span className={`shrink-0 rounded px-1.5 text-[9px] font-bold uppercase tracking-wide ${TRIGGER_STYLES[b.trigger] ?? 'bg-surface-3 text-txt-muted'}`}>
                            {t(`backup.trigger.${b.trigger}`)}
                          </span>
                          {b.cloud_id !== null && (
                            <span className="shrink-0 rounded bg-accent/15 px-1.5 text-[9px] font-bold uppercase tracking-wide text-accent-hover">
                              {t('backup.onCloud')}
                            </span>
                          )}
                        </div>
                        <span className="text-[10px] text-txt-muted">
                          {formatRelativeTime(b.created_at)} · {b.includes.map((d) => t(`backup.dir.${d}`)).join(' + ')} ·{' '}
                          {t('backup.fileCount', { count: b.file_count })} · {formatBytes(b.total_bytes)}
                        </span>
                      </div>

                      <Button size="sm" variant="secondary" onClick={() => setRestoring(b)} disabled={busy}>
                        {t('backup.restore')}
                      </Button>
                      <motion.button
                        {...press}
                        onClick={() => (confirmDelete === b.id ? remove(b) : setConfirmDelete(b.id))}
                        onMouseLeave={() => setConfirmDelete((id) => (id === b.id ? null : id))}
                        title={t('backup.delete')}
                        className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-lg transition-colors duration-150 ${
                          confirmDelete === b.id ? 'bg-danger/20 text-danger' : 'text-txt-muted hover:bg-surface-3 hover:text-danger'
                        }`}
                      >
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" className="h-[15px] w-[15px]">
                          <path d="M4 7h16M10 11v6M14 11v6M5 7l1 13h12l1-13M9 7V4h6v3" />
                        </svg>
                      </motion.button>
                    </motion.div>
                  ))}
                </motion.div>
              )}

              {settings && (
                <BackupSettingsPanel
                  settings={settings}
                  onChange={saveSettings}
                  custom={custom}
                  onReset={resetSettings}
                  scope={instanceId ? 'instance' : 'global'}
                />
              )}

              <div className="flex justify-end pb-2">
                <motion.button
                  {...press}
                  onClick={async () => {
                    const freed = await api.backup.collectGarbage().catch(() => 0)
                    showNotice(t('backup.cleaned', { size: formatBytes(freed) }))
                    await load()
                  }}
                  className="text-[11px] text-txt-muted transition-colors duration-150 hover:text-txt-secondary"
                >
                  {t('backup.cleanup')}
                </motion.button>
              </div>
            </>
          )}
        </div>
      </div>

      <AnimatePresence>
        {restoring && (
          <ModalShell onClose={() => setRestoring(null)} title={t('backup.restoreTitle')}>
            <div className="flex flex-col gap-4">
              <p className="text-[12px] leading-relaxed text-txt-secondary">
                {t('backup.restoreText', {
                  instance: restoring.instance_name,
                  when: formatRelativeTime(restoring.created_at),
                  dirs: restoring.includes.map((d) => t(`backup.dir.${d}`)).join(' + '),
                })}
              </p>
              {/* Deux boutons plutôt qu'une case à cocher : la différence entre
                  les deux gestes est trop importante pour être un détail qu'on
                  coche sans lire. */}
              <div className="flex flex-col gap-2">
                <Button variant="secondary" onClick={() => doRestore(false)} disabled={busy}>
                  {t('backup.restoreMerge')}
                </Button>
                <p className="px-1 text-[10.5px] leading-relaxed text-txt-muted">{t('backup.restoreMergeHint')}</p>
                <Button variant="danger" onClick={() => doRestore(true)} disabled={busy}>
                  {t('backup.restoreReplace')}
                </Button>
                <p className="px-1 text-[10.5px] leading-relaxed text-txt-muted">{t('backup.restoreReplaceHint')}</p>
              </div>
              <div className="flex justify-end">
                <Button size="sm" variant="ghost" onClick={() => setRestoring(null)}>
                  {t('common.cancel')}
                </Button>
              </div>
            </div>
          </ModalShell>
        )}
      </AnimatePresence>
    </div>
  )
}
