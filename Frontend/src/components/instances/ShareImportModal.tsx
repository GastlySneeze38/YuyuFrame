import { useEffect, useState } from 'react'
import { open as openFileDialog } from '@tauri-apps/plugin-dialog'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import type { Instance, SharePreview, ShareSource } from '@/types'
import { ModalShell } from '@/components/ui/ModalShell'
import { formatBytes, formatRam } from '@/lib/format'
import { JvmSummary } from './ShareTab'
import { loaderColor } from '@/lib/loader'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * Recevoir une instance partagée : un `.mrpack` ou un lien `yuyuframe://import`.
 *
 * Rien n'est créé avant l'aperçu : on montre d'abord d'où viendra chaque
 * fichier, et surtout **ce qui est copié dans le pack** sans être passé par
 * Modrinth ou CurseForge — un mod embarqué n'a été vérifié par personne, et
 * c'est la seule chose qu'on demande vraiment de regarder avant d'accepter.
 *
 * Ouverte depuis Instances → Importer, ou directement par un lien cliqué
 * (`App.tsx`), auquel cas la source est déjà connue et l'aperçu part tout de
 * suite.
 */

interface Progress {
  current: number
  total: number
  label: string
}

export function ShareImportModal({
  initialSource,
  onClose,
  onImported,
}: {
  initialSource?: ShareSource
  onClose: () => void
  onImported: (instance: Instance) => void
}) {
  const t = useT()
  const defaultRam = useStore((s) => s.defaultRam)
  const [source, setSource] = useState<ShareSource | null>(initialSource ?? null)
  const [linkInput, setLinkInput] = useState('')
  const [preview, setPreview] = useState<SharePreview | null>(null)
  const [loading, setLoading] = useState(false)
  const [name, setName] = useState('')
  const [showEmbedded, setShowEmbedded] = useState(false)
  /** Reprendre la configuration Java jointe — déjà filtrée côté Rust, mais
   *  c'est la RAM de quelqu'un d'autre : elle se décoche. */
  const [applyJvm, setApplyJvm] = useState(true)
  const [progress, setProgress] = useState<Progress | null>(null)
  const [result, setResult] = useState<{ instance: Instance; failed: string[] } | null>(null)

  useEffect(() => {
    if (!source) return
    let alive = true
    setLoading(true)
    setPreview(null)
    api.share
      .preview(source)
      .then((p) => {
        if (!alive) return
        setPreview(p)
        setName(p.name)
      })
      .catch((e) => {
        if (!alive) return
        showError(e)
        setSource(null)
      })
      .finally(() => alive && setLoading(false))
    return () => { alive = false }
  }, [source])

  const pickFile = async () => {
    const picked = await openFileDialog({ filters: [{ name: 'Modrinth pack', extensions: ['mrpack', 'zip'] }] })
    if (!picked || Array.isArray(picked)) return
    setSource({ kind: 'file', path: picked })
  }

  const submitLink = () => {
    const link = linkInput.trim()
    if (!link) return
    setSource({ kind: 'link', link })
  }

  const install = async () => {
    if (!source || !preview) return
    setProgress({ current: 0, total: preview.downloads.length, label: '' })
    const unlisten = await listen<Progress>('share_import_progress', (e) => setProgress(e.payload))
    try {
      const imported = await api.share.import(source, name.trim(), defaultRam, applyJvm && preview.jvm !== null)
      setResult(imported)
    } catch (e) {
      showError(e)
    } finally {
      unlisten()
      setProgress(null)
    }
  }

  const embeddedMods = preview?.embedded.filter((f) => f.path.startsWith('mods/')) ?? []
  const embeddedSize = preview?.embedded.reduce((sum, f) => sum + f.size, 0) ?? 0
  const downloadSize = preview?.downloads.reduce((sum, f) => sum + f.size, 0) ?? 0
  const sources = preview
    ? [...new Set(preview.downloads.map((f) => f.source))].map((s) => s.charAt(0).toUpperCase() + s.slice(1)).join(', ')
    : ''

  return (
    <ModalShell title={t('share.importTitle')} onClose={progress ? () => {} : onClose} closeOnEscape={!progress}>
      <div className="flex flex-col gap-4 py-2">
        {/* 1. Choisir la source */}
        {!source && !result && (
          <>
            <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('share.importDesc')}</p>
            <button
              onClick={pickFile}
              className="rounded-xl bg-accent px-4 py-2.5 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover"
            >
              {t('share.importFile')}
            </button>
            <div className="flex flex-col gap-1.5">
              <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">{t('share.importLink')}</p>
              <div className="flex gap-2">
                <input
                  value={linkInput}
                  onChange={(e) => setLinkInput(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') submitLink() }}
                  placeholder="yuyuframe://import?…"
                  className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-black/40 px-3 text-[12.5px] text-txt-primary outline-none placeholder:text-txt-muted focus:border-accent/60"
                />
                <button
                  onClick={submitLink}
                  disabled={!linkInput.trim()}
                  className="rounded-xl border border-line bg-surface-2 px-4 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:opacity-40"
                >
                  {t('share.importLinkGo')}
                </button>
              </div>
            </div>
          </>
        )}

        {source && loading && <p className="text-[12.5px] text-txt-muted">{t('share.reading')}</p>}

        {/* 2. Aperçu */}
        {preview && !progress && !result && (
          <>
            <div className="flex flex-col gap-1.5">
              <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-muted">{t('instancesPage.name')}</p>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="h-10 w-full rounded-xl border border-line bg-black/40 px-3 text-[13px] text-txt-primary outline-none focus:border-accent/60"
              />
              {preview.summary && <p className="text-[11.5px] text-txt-muted">{preview.summary}</p>}
            </div>

            <div className="flex flex-wrap items-center gap-2 text-[12px] text-txt-secondary">
              <span className="rounded-md bg-white/[0.06] px-2 py-0.5 font-semibold">Minecraft {preview.mcVersion}</span>
              <span className="rounded-md bg-white/[0.06] px-2 py-0.5 font-semibold" style={{ color: loaderColor(preview.loader) }}>
                {preview.loader}
                {preview.loaderVersion && ` ${preview.loaderVersion}`}
              </span>
            </div>

            <div className="flex flex-col gap-1 rounded-xl border border-line bg-surface-2 p-3.5 text-[12px] text-txt-secondary">
              <p>
                {t('share.previewDownloads', { count: preview.downloads.length, size: formatBytes(downloadSize) })}
                {sources && <span className="text-txt-muted"> · {sources}</span>}
              </p>
              {preview.embedded.length > 0 && (
                <button onClick={() => setShowEmbedded((v) => !v)} className="w-fit text-left hover:text-txt-primary">
                  {t('share.previewEmbedded', { count: preview.embedded.length, size: formatBytes(embeddedSize) })}{' '}
                  <span className="text-accent-hover">{showEmbedded ? '▴' : '▾'}</span>
                </button>
              )}
              {showEmbedded && (
                <ul className="max-h-40 overflow-y-auto pl-3 text-[11px] text-txt-muted">
                  {preview.embedded.map((f) => (
                    <li key={f.path} className="truncate">{f.path}</li>
                  ))}
                </ul>
              )}
            </div>

            {preview.jvm && (
              <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-line bg-surface-2 px-3.5 py-2.5">
                <input
                  type="checkbox"
                  checked={applyJvm}
                  onChange={() => setApplyJvm((v) => !v)}
                  className="mt-0.5 h-3.5 w-3.5 shrink-0 accent-accent"
                />
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <p className="text-[12.5px] font-semibold text-txt-primary">{t('share.jvmApply')}</p>
                  <JvmSummary jvm={preview.jvm} />
                  {!applyJvm && (
                    <p className="text-[11px] text-txt-muted">{t('share.jvmDefault', { ram: formatRam(defaultRam) })}</p>
                  )}
                </div>
              </label>
            )}
            {preview.jvmRejected.length > 0 && (
              <p className="text-[11.5px] text-warning">
                ⚠ {t('share.jvmRejectedIn', { count: preview.jvmRejected.length })}{' '}
                <span className="break-all font-mono text-[10.5px]">{preview.jvmRejected.join(' ')}</span>
              </p>
            )}

            {embeddedMods.length > 0 && (
              <p className="text-[11.5px] text-warning">⚠ {t('share.embeddedModsWarning', { count: embeddedMods.length })}</p>
            )}
            {preview.rejected.length > 0 && (
              <p className="text-[11.5px] text-warning">⚠ {t('share.rejected', { count: preview.rejected.length })}</p>
            )}

            <div className="flex gap-2">
              <button
                onClick={install}
                className="rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover"
              >
                {t('share.create')}
              </button>
              {!initialSource && (
                <button
                  onClick={() => { setSource(null); setPreview(null) }}
                  className="rounded-xl border border-line bg-surface-2 px-4 py-2 text-[12.5px] font-semibold text-txt-secondary hover:text-txt-primary"
                >
                  {t('share.back')}
                </button>
              )}
            </div>
          </>
        )}

        {/* 3. Installation */}
        {progress && (
          <div className="flex flex-col gap-2">
            <p className="text-[12.5px] text-txt-secondary">{t('share.installing')}</p>
            <div className="h-1.5 overflow-hidden rounded-full bg-white/[0.06]">
              <div
                className="h-full bg-accent transition-[width]"
                style={{ width: `${progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : 100}%` }}
              />
            </div>
            <p className="truncate text-[11px] text-txt-muted">
              {progress.label} {progress.total > 0 && `(${progress.current}/${progress.total})`}
            </p>
          </div>
        )}

        {/* 4. Terminé */}
        {result && (
          <>
            <p className="text-[12.5px] text-[rgba(134,239,172,0.85)]">{t('share.importDone', { name: result.instance.name })}</p>
            {result.failed.length > 0 && (
              <div className="flex flex-col gap-1">
                <p className="text-[11.5px] text-warning">⚠ {t('share.importFailed', { count: result.failed.length })}</p>
                <ul className="max-h-32 overflow-y-auto pl-3 text-[11px] text-txt-muted">
                  {result.failed.map((f) => (
                    <li key={f} className="truncate">{f}</li>
                  ))}
                </ul>
              </div>
            )}
            <button
              onClick={() => onImported(result.instance)}
              className="w-fit rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-accent-hover"
            >
              {t('share.openInstance')}
            </button>
          </>
        )}
      </div>
    </ModalShell>
  )
}
