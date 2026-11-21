import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { press, pressIf } from '@/lib/motion'
import { formatBytes, formatDownloadCount } from '@/lib/format'
import { horizonVersionName } from '@/lib/horizonVersion'
import { openExternal } from '@/lib/openExternal'
import { useT } from '@/i18n'
import { useStore, useStorePick } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import { useTauriEvent } from '@/hooks/useTauriEvent'
import { RemoteImage } from '@/components/ui/RemoteImage'
import { RichDescription } from '@/components/ui/RichDescription'
import { ModalShell } from '@/components/ui/ModalShell'
import { Toggle } from '@/components/ui/Toggle'
import { Pagination } from '@/components/ui/Pagination'
import type { Instance } from '@/types'
import type {
  HorizonInstalled, HorizonKind, HorizonLeftOut, HorizonNativeReport, HorizonNativeStatus, HorizonProject,
} from '@/types/horizon'

/**
 * Le contenu d'une instance Horizon tiré de son registre (`horizon-mods`) :
 * mods de contenu, mods natifs, shaders Horizon, packs de ressources.
 *
 * Les mods natifs sont du code Rust compilé sur la machine du joueur au
 * lancement : on demande l'accord une fois (`NativeConsent`) avant d'en
 * installer ou d'en allumer un, et l'onglet des installés montre ce que le
 * dernier lancement a dû laisser de côté, avec le build pour lequel un mod
 * est fait quand ce n'est pas celui de l'instance — proposé, jamais imposé.
 */

const PAGE_SIZE = 24
type KindFilter = 'all' | 'mod' | 'native' | 'shader' | 'resourcepack'
type Sort = 'popular' | 'recent' | 'name'

const KIND_OF: Record<HorizonKind, KindFilter> = {
  'mod': 'mod',
  'native-mod': 'native',
  'shader': 'shader',
  'shaderpack': 'shader',
  'resourcepack': 'resourcepack',
}

// Le registre change rarement : gardé pour la session, par version jouée.
const registryCache = new Map<string, Promise<HorizonProject[]>>()
function loadRegistry(loaderVersion: string, fresh = false): Promise<HorizonProject[]> {
  if (fresh) registryCache.delete(loaderVersion)
  let found = registryCache.get(loaderVersion)
  if (!found) {
    found = api.horizon.registry(loaderVersion).then((r) => r.projects)
    found.catch(() => registryCache.delete(loaderVersion))
    registryCache.set(loaderVersion, found)
  }
  return found
}

export function HorizonContentTab({ instance, mode, onBrowse, onCount }: {
  instance: Instance
  mode: 'installed' | 'browse'
  onBrowse: () => void
  onCount?: (n: number) => void
}) {
  const loaderVersion = instance.loader_version ?? ''
  const [installed, setInstalled] = useState<HorizonInstalled[]>([])
  const [status, setStatus] = useState<HorizonNativeStatus | null>(null)
  const [report, setReport] = useState<HorizonNativeReport | null>(null)
  const [consent, setConsent] = useState<null | (() => Promise<void>)>(null)

  const reload = useCallback(async () => {
    try {
      const [list, st, rep] = await Promise.all([
        api.horizon.installed(instance.id, loaderVersion),
        api.horizon.nativeStatus(),
        api.horizon.nativeReport(instance.id),
      ])
      setInstalled(list)
      setStatus(st)
      setReport(rep)
      onCount?.(list.length)
    } catch (e) {
      showError(e)
    }
  }, [instance.id, loaderVersion, onCount])

  useEffect(() => { void reload() }, [reload])
  useTauriEvent<HorizonNativeReport>('horizon_native_report', (r) => {
    if (r.instance_id === instance.id) setReport(r)
  }, [instance.id])

  /** Un geste qui met un mod natif en jeu passe d'abord par l'accord. */
  const withConsent = useCallback(async (action: () => Promise<void>) => {
    if (status?.allowed) return action()
    setConsent(() => action)
  }, [status?.allowed])

  return (
    <>
      {mode === 'browse' ? (
        <Browse instance={instance} installed={installed} onChanged={reload} withConsent={withConsent} />
      ) : (
        <Installed
          instance={instance}
          items={installed}
          status={status}
          report={report}
          onChanged={reload}
          onBrowse={onBrowse}
          withConsent={withConsent}
        />
      )}
      <AnimatePresence>
        {consent && status && (
          <NativeConsent
            downloadBytes={status.download_bytes}
            onCancel={() => setConsent(null)}
            onAllow={async () => {
              const action = consent
              setConsent(null)
              try {
                await api.horizon.nativeAllow(true)
                setStatus({ ...status, allowed: true })
                await action()
              } catch (e) {
                showError(e)
              }
            }}
          />
        )}
      </AnimatePresence>
    </>
  )
}

// ── Parcourir ────────────────────────────────────────────────────────────────

function Browse({ instance, installed, onChanged, withConsent }: {
  instance: Instance
  installed: HorizonInstalled[]
  onChanged: () => Promise<void>
  withConsent: (action: () => Promise<void>) => Promise<void>
}) {
  const t = useT()
  const loaderVersion = instance.loader_version ?? ''
  const [projects, setProjects] = useState<HorizonProject[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState<KindFilter>('all')
  const [sort, setSort] = useState<Sort>('popular')
  const [page, setPage] = useState(1)
  const [busy, setBusy] = useState<string | null>(null)
  const [detail, setDetail] = useState<HorizonProject | null>(null)

  useEffect(() => {
    let cancelled = false
    setFailed(false)
    loadRegistry(loaderVersion)
      .then((p) => { if (!cancelled) setProjects(p) })
      .catch(() => { if (!cancelled) setFailed(true) })
    return () => { cancelled = true }
  }, [loaderVersion])

  const byProject = useMemo(() => {
    const map = new Map<string, HorizonInstalled>()
    for (const i of installed) if (i.project) map.set(i.project, i)
    return map
  }, [installed])

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = (projects ?? []).filter((p) =>
      (kind === 'all' || KIND_OF[p.kind] === kind)
      && (!q || p.name.toLowerCase().includes(q) || p.summary.toLowerCase().includes(q)
        || p.tags.some((tag) => tag.includes(q)) || p.authors.some((a) => a.toLowerCase().includes(q))))
    return [...list].sort((a, b) =>
      sort === 'name' ? a.name.localeCompare(b.name)
        : sort === 'recent' ? b.updated.localeCompare(a.updated)
          : b.downloads - a.downloads)
  }, [projects, query, kind, sort])

  useEffect(() => { setPage(1) }, [query, kind, sort])
  const pageCount = Math.max(1, Math.ceil(shown.length / PAGE_SIZE))
  const pageItems = shown.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)

  const install = async (project: HorizonProject, version?: string) => {
    const run = async () => {
      setBusy(project.id)
      try {
        await api.horizon.install(instance.id, project.id, loaderVersion, version, byProject.get(project.id)?.file)
        await onChanged()
      } catch (e) {
        showError(e)
      } finally {
        setBusy(null)
      }
    }
    if (project.kind === 'native-mod') await withConsent(run)
    else await run()
  }

  const kinds: KindFilter[] = ['all', 'mod', 'native', 'shader', 'resourcepack']

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('mods.horizon.searchPlaceholder')}
          className="h-9 min-w-[220px] flex-1 rounded-lg border border-line-soft bg-bg-card px-3 text-[13px] text-white outline-none placeholder:text-[rgba(255,255,255,0.35)] focus:border-[rgb(var(--accent)/0.6)]"
        />
        <div className="flex overflow-hidden rounded-lg border border-line-soft">
          {kinds.map((k) => (
            <button
              key={k}
              onClick={() => setKind(k)}
              className={`h-9 px-3 text-[12px] font-semibold transition-colors duration-150 ${
                kind === k ? 'bg-[rgb(var(--accent)/0.25)] text-white' : 'text-[rgba(255,255,255,0.55)] hover:bg-[rgba(255,255,255,0.05)]'
              }`}
            >
              {t(`mods.horizon.kinds.${k}`)}
            </button>
          ))}
        </div>
        <select
          value={sort}
          onChange={(e) => setSort(e.target.value as Sort)}
          className="h-9 rounded-lg border border-line-soft bg-bg-card px-2 text-[12px] text-white outline-none"
        >
          <option value="popular">{t('mods.horizon.sort.popular')}</option>
          <option value="recent">{t('mods.horizon.sort.recent')}</option>
          <option value="name">{t('mods.horizon.sort.name')}</option>
        </select>
      </div>

      {failed ? (
        <p className="py-10 text-center text-[13px] text-[rgba(255,255,255,0.5)]">{t('mods.horizon.unreachable')}</p>
      ) : projects === null ? (
        <div className="grid gap-2.5 [grid-template-columns:repeat(auto-fill,minmax(260px,1fr))]">
          {Array.from({ length: 9 }, (_, i) => (
            <div key={i} className="skeleton h-[118px] rounded-xl" />
          ))}
        </div>
      ) : shown.length === 0 ? (
        <p className="py-10 text-center text-[13px] text-[rgba(255,255,255,0.5)]">{t('mods.horizon.empty')}</p>
      ) : (
        <div className="grid gap-2.5 [grid-template-columns:repeat(auto-fill,minmax(260px,1fr))]">
          {pageItems.map((p) => (
            <ProjectCard
              key={p.id}
              project={p}
              mine={byProject.get(p.id)}
              busy={busy === p.id}
              onOpen={() => setDetail(p)}
              onInstall={() => install(p)}
            />
          ))}
        </div>
      )}
      <Pagination page={page} pageCount={pageCount} onChange={setPage} />

      <AnimatePresence>
        {detail && (
          <ProjectDetail
            project={detail}
            mine={byProject.get(detail.id)}
            busy={busy === detail.id}
            onClose={() => setDetail(null)}
            onInstall={(version) => install(detail, version)}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

function KindBadge({ kind }: { kind: HorizonKind }) {
  const t = useT()
  const native = kind === 'native-mod'
  return (
    <span
      title={native ? t('mods.horizon.nativeHint') : undefined}
      className={`rounded px-1.5 py-px text-[10px] font-bold uppercase tracking-wide ${
        native ? 'bg-[rgba(250,204,21,0.14)] text-[rgba(250,204,21,0.9)]' : 'bg-[rgba(255,255,255,0.07)] text-[rgba(255,255,255,0.55)]'
      }`}
    >
      {t(`mods.horizon.kinds.${KIND_OF[kind]}`)}
    </span>
  )
}

function InstallButton({ project, mine, busy, onInstall }: {
  project: HorizonProject
  mine?: HorizonInstalled
  busy: boolean
  onInstall: () => void
}) {
  const t = useT()
  const newest = project.versions[0]?.version
  const upToDate = mine && mine.version === newest
  const label = busy ? t('mods.horizon.installing')
    : upToDate ? t('mods.horizon.installed')
      : mine ? t('mods.horizon.update')
        : t('mods.horizon.install')
  return (
    <motion.button
      {...pressIf(!busy && !upToDate)}
      disabled={busy || !!upToDate}
      onClick={(e) => { e.stopPropagation(); onInstall() }}
      className={`h-7 flex-shrink-0 rounded-md px-3 text-[11.5px] font-bold transition-colors duration-150 ${
        upToDate ? 'cursor-default bg-[rgba(74,222,128,0.12)] text-[rgba(134,239,172,0.9)]'
          : busy ? 'cursor-wait bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.4)]'
            : 'bg-[rgb(var(--accent))] text-white hover:bg-[rgb(var(--accent-hover))]'
      }`}
    >
      {label}
    </motion.button>
  )
}

function ProjectCard({ project, mine, busy, onOpen, onInstall }: {
  project: HorizonProject
  mine?: HorizonInstalled
  busy: boolean
  onOpen: () => void
  onInstall: () => void
}) {
  const t = useT()
  return (
    <motion.div
      {...press}
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => { if (e.key === 'Enter') onOpen() }}
      className="flex cursor-pointer flex-col gap-2 rounded-xl border border-line-soft bg-bg-card p-3 transition-colors duration-150 hover:border-[rgb(var(--accent)/0.35)]"
    >
      <div className="flex gap-3">
        <div className="h-12 w-12 flex-shrink-0 overflow-hidden rounded-lg bg-[rgba(255,255,255,0.05)]">
          <RemoteImage src={project.icon} className="h-full w-full object-cover [image-rendering:pixelated]" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13.5px] font-bold text-white">{project.name}</p>
          <p className="truncate text-[11px] text-[rgba(255,255,255,0.45)]">{project.authors.join(', ')}</p>
          <p className="mt-0.5 line-clamp-2 text-[11.5px] leading-snug text-[rgba(255,255,255,0.65)]">{project.summary}</p>
        </div>
      </div>
      <div className="mt-auto flex items-center gap-2">
        <KindBadge kind={project.kind} />
        <span className="text-[11px] tabular-nums text-[rgba(255,255,255,0.4)]">
          ↓ {formatDownloadCount(project.downloads)}
        </span>
        <div className="flex-1" />
        <InstallButton project={project} mine={mine} busy={busy} onInstall={onInstall} />
      </div>
    </motion.div>
  )
}

function ProjectDetail({ project, mine, busy, onClose, onInstall }: {
  project: HorizonProject
  mine?: HorizonInstalled
  busy: boolean
  onClose: () => void
  onInstall: (version?: string) => void
}) {
  const t = useT()
  const [tab, setTab] = useState<'description' | 'versions'>('description')
  return (
    <ModalShell onClose={onClose} maxWidth="max-w-3xl">
      <div className="flex max-h-[80vh] flex-col">
        <div className="flex items-start gap-4 border-b border-line-soft p-5">
          <div className="h-16 w-16 flex-shrink-0 overflow-hidden rounded-xl bg-[rgba(255,255,255,0.05)]">
            <RemoteImage src={project.icon} eager className="h-full w-full object-cover [image-rendering:pixelated]" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <h2 className="truncate text-[18px] font-black text-white">{project.name}</h2>
              <KindBadge kind={project.kind} />
            </div>
            <p className="text-[12px] text-[rgba(255,255,255,0.5)]">
              {project.authors.join(', ')}
              {project.license && ` · ${t('mods.horizon.license', { license: project.license })}`}
              {` · ${project.signed ? t('mods.horizon.signed') : t('mods.horizon.unsigned')}`}
            </p>
            <div className="mt-2 flex items-center gap-2">
              <InstallButton project={project} mine={mine} busy={busy} onInstall={() => onInstall()} />
              {project.source.startsWith('https://') && (
                <button onClick={() => openExternal(project.source)} className="h-7 rounded-md px-2.5 text-[11.5px] font-semibold text-[rgba(255,255,255,0.6)] hover:bg-[rgba(255,255,255,0.06)]">
                  {t('mods.horizon.source')} ↗
                </button>
              )}
            </div>
            {project.kind === 'native-mod' && (
              <p className="mt-2 text-[11.5px] text-[rgba(250,204,21,0.8)]">{t('mods.horizon.nativeHint')}</p>
            )}
          </div>
        </div>
        <div className="flex gap-1 border-b border-line-soft px-5">
          {(['description', 'versions'] as const).map((k) => (
            <button
              key={k}
              onClick={() => setTab(k)}
              className={`relative h-10 px-3 text-[12.5px] font-semibold ${tab === k ? 'text-white' : 'text-[rgba(255,255,255,0.5)] hover:text-[rgba(255,255,255,0.75)]'}`}
            >
              {t(`mods.horizon.${k}`)}
              {tab === k && <span className="absolute inset-x-2 bottom-0 h-0.5 rounded-full bg-[rgb(var(--accent))]" />}
            </button>
          ))}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-5">
          {tab === 'description' ? (
            <>
              {project.screenshots.length > 0 && (
                <div className="mb-4 flex gap-2 overflow-x-auto pb-1">
                  {project.screenshots.map((s) => (
                    <div key={s} className="h-36 flex-shrink-0 overflow-hidden rounded-lg bg-[rgba(255,255,255,0.04)]">
                      <RemoteImage src={s} className="h-full w-auto" />
                    </div>
                  ))}
                </div>
              )}
              <RichDescription source={project.description} className="text-[13px] text-[rgba(255,255,255,0.8)]" />
            </>
          ) : (
            <div className="flex flex-col gap-2">
              {project.versions.map((v) => (
                <div key={v.version} className="flex items-start gap-3 rounded-lg border border-line-soft bg-[rgba(255,255,255,0.02)] p-3">
                  <div className="min-w-0 flex-1">
                    <p className="text-[13px] font-bold text-white">
                      {v.version}
                      {mine?.version === v.version && (
                        <span className="ml-2 text-[11px] font-semibold text-[rgba(134,239,172,0.85)]">{t('mods.horizon.installed')}</span>
                      )}
                    </p>
                    <p className="text-[11px] text-[rgba(255,255,255,0.45)]">
                      {v.published} · {formatBytes(v.size)}
                      {v.made_for && ` · ${t('mods.horizon.madeForCommit', { commit: v.made_for.slice(0, 7) })}`}
                    </p>
                    {v.changelog && <p className="mt-1 whitespace-pre-line text-[12px] text-[rgba(255,255,255,0.65)]">{v.changelog}</p>}
                  </div>
                  {mine?.version !== v.version && (
                    <motion.button
                      {...pressIf(!busy)}
                      disabled={busy}
                      onClick={() => onInstall(v.version)}
                      className="h-7 flex-shrink-0 rounded-md bg-[rgba(255,255,255,0.07)] px-3 text-[11.5px] font-semibold text-white hover:bg-[rgba(255,255,255,0.12)]"
                    >
                      {t('mods.horizon.install')}
                    </motion.button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </ModalShell>
  )
}

// ── Installés ────────────────────────────────────────────────────────────────

function Installed({ instance, items, status, report, onChanged, onBrowse, withConsent }: {
  instance: Instance
  items: HorizonInstalled[]
  status: HorizonNativeStatus | null
  report: HorizonNativeReport | null
  onChanged: () => Promise<void>
  onBrowse: () => void
  withConsent: (action: () => Promise<void>) => Promise<void>
}) {
  const t = useT()
  const loaderVersion = instance.loader_version ?? ''
  const [busy, setBusy] = useState<string | null>(null)
  const [prefetch, setPrefetch] = useState<number | null>(null)
  useTauriEvent<{ stage: string; fraction: number }>('horizon_native_prefetch', (p) => {
    setPrefetch(p.stage === 'done' ? null : p.fraction)
    if (p.stage === 'done') void onChanged()
  }, [onChanged])

  const run = async (key: string, action: () => Promise<void>) => {
    setBusy(key)
    try {
      await action()
      await onChanged()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(null)
    }
  }

  const groups: { key: 'mods' | 'native' | 'shaders'; list: HorizonInstalled[] }[] = [
    { key: 'mods', list: items.filter((i) => i.kind === 'mod') },
    { key: 'native', list: items.filter((i) => i.kind === 'native-mod') },
    { key: 'shaders', list: items.filter((i) => i.kind === 'shader') },
  ]
  const hasNative = groups[1].list.some((i) => i.enabled)

  if (items.length === 0) {
    return (
      <div className="flex flex-col items-center gap-3 py-16 text-center">
        <p className="text-[13px] text-[rgba(255,255,255,0.5)]">{t('mods.horizon.nothingInstalled')}</p>
        <motion.button {...press} onClick={onBrowse} className="h-9 rounded-lg bg-[rgb(var(--accent))] px-4 text-[12.5px] font-bold text-white hover:bg-[rgb(var(--accent-hover))]">
          {t('mods.horizon.browse')}
        </motion.button>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      {hasNative && status && !status.unsupported && (
        !status.allowed ? (
          <Notice tone="warn">
            <span className="flex-1">{t('mods.horizon.notAllowed')}</span>
            <motion.button {...press} onClick={() => withConsent(async () => { await onChanged() })} className="h-7 rounded-md bg-[rgba(250,204,21,0.2)] px-3 text-[11.5px] font-bold text-[rgba(253,224,71,0.95)]">
              {t('mods.horizon.allowAction')}
            </motion.button>
          </Notice>
        ) : prefetch !== null ? (
          <Notice tone="info">
            <span className="flex-1">{t('mods.horizon.preparing', { pct: Math.round(prefetch * 100) })}</span>
            <div className="h-1 w-32 overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
              <div className="h-full origin-left rounded-full bg-[rgb(var(--accent))] transition-transform duration-300" style={{ transform: `scaleX(${prefetch})` }} />
            </div>
          </Notice>
        ) : !status.toolchain ? (
          <Notice tone="info">
            <span className="flex-1">{t('mods.horizon.willDownload', { size: formatBytes(status.download_bytes) })}</span>
          </Notice>
        ) : null
      )}

      {report && report.left_out.length > 0 && <ReportCard instance={instance} report={report} />}

      {groups.filter((g) => g.list.length > 0).map((g) => (
        <section key={g.key} className="flex flex-col gap-1.5">
          <h3 className="px-1 text-[11px] font-bold uppercase tracking-wider text-[rgba(255,255,255,0.4)]">
            {t(`mods.horizon.sections.${g.key}`)} · {g.list.length}
          </h3>
          {g.list.map((item) => (
            <Row
              key={item.file}
              item={item}
              busy={busy === item.file}
              onToggle={() => {
                const action = () => run(item.file, async () => {
                  if (item.kind === 'shader') await api.horizon.pickShader(instance.id, item.enabled ? null : item.file)
                  else await api.horizon.toggle(instance.id, item.file, !item.enabled, loaderVersion)
                })
                return item.kind === 'native-mod' && !item.enabled ? withConsent(action) : action()
              }}
              onUpdate={() => run(item.file, async () => {
                if (item.project) await api.horizon.install(instance.id, item.project, loaderVersion, undefined, item.file)
              })}
              onRemove={() => run(item.file, () => api.horizon.remove(instance.id, item.file))}
            />
          ))}
        </section>
      ))}
    </div>
  )
}

function Notice({ tone, children }: { tone: 'warn' | 'info' | 'error'; children: React.ReactNode }) {
  const styles = {
    warn: 'border-[rgba(250,204,21,0.25)] bg-[rgba(250,204,21,0.07)] text-[rgba(253,230,138,0.9)]',
    info: 'border-[rgb(var(--accent)/0.3)] bg-[rgb(var(--accent)/0.08)] text-[rgba(255,255,255,0.8)]',
    error: 'border-[rgba(248,113,113,0.3)] bg-[rgba(248,113,113,0.07)] text-[rgba(254,202,202,0.9)]',
  }[tone]
  return <div className={`flex items-center gap-3 rounded-xl border px-3.5 py-2.5 text-[12px] ${styles}`}>{children}</div>
}

function Row({ item, busy, onToggle, onUpdate, onRemove }: {
  item: HorizonInstalled
  busy: boolean
  onToggle: () => void
  onUpdate: () => void
  onRemove: () => void
}) {
  const t = useT()
  const [armed, setArmed] = useState(false)
  const timer = useRef<number | undefined>(undefined)
  useEffect(() => () => window.clearTimeout(timer.current), [])
  const arm = () => {
    if (armed) { onRemove(); setArmed(false); return }
    setArmed(true)
    timer.current = window.setTimeout(() => setArmed(false), 2500)
  }
  return (
    <div className={`flex items-center gap-3 rounded-xl border border-line-soft bg-bg-card px-3 py-2 transition-opacity duration-150 ${busy ? 'opacity-60' : ''}`}>
      <div className="h-9 w-9 flex-shrink-0 overflow-hidden rounded-lg bg-[rgba(255,255,255,0.05)]">
        {item.icon && <img src={item.icon} alt="" className="h-full w-full object-cover [image-rendering:pixelated]" />}
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13px] font-semibold text-white">
          {item.name}
          <span className="ml-2 text-[11px] font-normal text-[rgba(255,255,255,0.4)]">{item.version}</span>
        </p>
        {item.revoked ? (
          <p className="truncate text-[11px] text-[rgba(248,113,113,0.9)]">{t('mods.horizon.revoked', { reason: item.revoked })}</p>
        ) : item.changes_game ? (
          <p className="truncate text-[11px] text-[rgba(250,204,21,0.8)]" title={t('mods.horizon.changesGameHint')}>{t('mods.horizon.changesGame')}</p>
        ) : (
          <p className="truncate text-[11px] text-[rgba(255,255,255,0.4)]">{formatBytes(item.size)}</p>
        )}
      </div>
      {item.update && (
        <motion.button {...pressIf(!busy)} disabled={busy} onClick={onUpdate} className="h-7 rounded-md border border-[rgba(250,204,21,0.28)] bg-[rgba(250,204,21,0.1)] px-2.5 text-[11px] font-bold text-[rgba(250,204,21,0.9)]">
          {t('mods.horizon.updateTo', { version: item.update })}
        </motion.button>
      )}
      <motion.button
        {...pressIf(!busy)}
        disabled={busy}
        onClick={arm}
        className={`h-7 rounded-md px-2.5 text-[11px] font-semibold transition-colors duration-150 ${
          armed ? 'bg-[rgba(248,113,113,0.2)] text-[rgba(254,202,202,0.95)]' : 'text-[rgba(255,255,255,0.45)] hover:bg-[rgba(255,255,255,0.06)]'
        }`}
      >
        {armed ? t('mods.horizon.confirmRemove') : t('mods.horizon.remove')}
      </motion.button>
      {item.kind === 'shader' ? (
        <motion.button
          {...pressIf(!busy)}
          disabled={busy}
          onClick={onToggle}
          className={`h-7 w-[84px] rounded-md text-[11px] font-bold ${item.enabled ? 'bg-[rgb(var(--accent)/0.3)] text-white' : 'bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.6)] hover:bg-[rgba(255,255,255,0.1)]'}`}
        >
          {item.enabled ? t('mods.horizon.inUse') : t('mods.horizon.use')}
        </motion.button>
      ) : (
        <Toggle size="sm" checked={item.enabled} onChange={() => { if (!busy) onToggle() }} />
      )}
    </div>
  )
}

/** Ce que le dernier lancement a laissé de côté, et pourquoi. */
function ReportCard({ instance, report }: { instance: Instance; report: HorizonNativeReport }) {
  const t = useT()
  const { language } = useStorePick('language')
  const [switching, setSwitching] = useState<string | null>(null)
  const [armed, setArmed] = useState<string | null>(null)

  const reason = (l: HorizonLeftOut): string => {
    switch (l.kind) {
      case 'other_build': return t('mods.horizon.reasons.other_build', { why: l.why })
      case 'does_not_fit': return t('mods.horizon.reasons.does_not_fit', { items: l.items.slice(0, 3).join(', ') + (l.items.length > 3 ? '…' : '') })
      case 'crates': return t('mods.horizon.reasons.crates', { names: l.names.join(', ') })
      case 'missing_dependency': return t('mods.horizon.reasons.missing_dependency', { id: l.id })
      case 'compile': return t('mods.horizon.reasons.compile')
      case 'unreadable': return t('mods.horizon.reasons.unreadable')
      default: return t(`mods.horizon.reasons.${l.kind}`)
    }
  }

  const switchTo = async (version: string) => {
    if (armed !== version) { setArmed(version); return }
    setSwitching(version)
    try {
      const updated = await api.instances.update(
        instance.id, instance.name, instance.mc_version, instance.loader, instance.ram_mb, instance.description,
        { vendor: instance.jvm_vendor, customPath: instance.jvm_custom_path ?? undefined, gcPolicy: instance.gc_policy, extraArgs: instance.jvm_extra_args, argsMode: instance.jvm_args_mode },
        version,
      )
      useStore.getState().updateInstance(updated)
    } catch (e) {
      showError(e)
    } finally {
      setSwitching(null)
      setArmed(null)
    }
  }

  return (
    <div className="flex flex-col gap-2 rounded-xl border border-[rgba(250,204,21,0.22)] bg-[rgba(250,204,21,0.05)] p-3.5">
      <p className="text-[12.5px] font-bold text-[rgba(253,230,138,0.95)]">
        {report.not_allowed
          ? t('mods.horizon.reportNotAllowed')
          : t(report.left_out.length === 1 ? 'mods.horizon.reportOne' : 'mods.horizon.reportMany', { n: report.left_out.length })}
      </p>
      {report.mods.length > 0 && (
        <p className="text-[11.5px] text-[rgba(255,255,255,0.55)]">{t('mods.horizon.reportLoaded', { list: report.mods.join(', ') })}</p>
      )}
      {!report.not_allowed && report.left_out.map((l) => {
        const madeFor = 'made_for' in l ? l.made_for : null
        const version = madeFor ? report.builds[madeFor] : undefined
        return (
          <div key={l.id} className="flex items-center gap-3 rounded-lg bg-[rgba(0,0,0,0.15)] px-3 py-2">
            <div className="min-w-0 flex-1">
              <p className="text-[12px] font-semibold text-white">{l.id}</p>
              <p className="text-[11px] text-[rgba(255,255,255,0.55)]" title={l.kind === 'compile' ? l.log : undefined}>{reason(l)}</p>
              {madeFor && (
                <p className="text-[11px] text-[rgba(255,255,255,0.4)]">
                  {version
                    ? t('mods.horizon.madeFor', { version: horizonVersionName(version, language, t) })
                    : t('mods.horizon.madeForCommit', { commit: madeFor.slice(0, 7) })}
                </p>
              )}
            </div>
            {version && (
              <motion.button
                {...pressIf(switching === null)}
                disabled={switching !== null}
                onClick={() => switchTo(version)}
                className="h-7 flex-shrink-0 rounded-md bg-[rgba(255,255,255,0.08)] px-3 text-[11px] font-bold text-white hover:bg-[rgba(255,255,255,0.13)]"
              >
                {armed === version ? t('mods.horizon.confirmSwitch') : t('mods.horizon.switchTo')}
              </motion.button>
            )}
          </div>
        )
      })}
    </div>
  )
}

// ── Paramètres → Stockage ────────────────────────────────────────────────────

/** Retirer (ou redonner) l'accord, et libérer la place du compilateur. */
export function HorizonNativeSettings() {
  const t = useT()
  const [status, setStatus] = useState<HorizonNativeStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  useEffect(() => { api.horizon.nativeStatus().then(setStatus).catch(() => {}) }, [])
  if (!status || status.unsupported) return null
  const set = async (allow: boolean) => {
    setBusy(true)
    try {
      await api.horizon.nativeAllow(allow)
      setStatus({ ...status, allowed: allow })
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }
  const clear = async () => {
    setBusy(true)
    try {
      await api.horizon.nativeClear()
      setStatus({ ...status, toolchain: false })
      setDone(true)
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex flex-col gap-2 rounded-xl border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.03)] px-4 py-3">
      <p className="text-sm font-medium text-white">{t('mods.horizon.settings.title')}</p>
      <p className="text-[11px] text-white/40">
        {status.allowed ? t('mods.horizon.settings.allowed') : t('mods.horizon.settings.notAllowed')}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <button
          onClick={() => set(!status.allowed)}
          disabled={busy}
          className="rounded-lg bg-white/5 px-3 py-1.5 text-[11px] font-semibold text-white/70 hover:bg-white/10 disabled:opacity-40"
        >
          {status.allowed ? t('mods.horizon.settings.revoke') : t('mods.horizon.settings.allow')}
        </button>
        {status.toolchain && (
          <button
            onClick={clear}
            disabled={busy}
            className="rounded-lg bg-white/5 px-3 py-1.5 text-[11px] font-semibold text-white/70 hover:bg-white/10 disabled:opacity-40"
          >
            {t('mods.horizon.settings.clear')}
          </button>
        )}
        {done && <span className="text-[11px] text-white/40">{t('mods.horizon.settings.cleared')}</span>}
      </div>
    </div>
  )
}

// ── L'accord pour les mods natifs ────────────────────────────────────────────

function NativeConsent({ downloadBytes, onCancel, onAllow }: {
  downloadBytes: number
  onCancel: () => void
  onAllow: () => void
}) {
  const t = useT()
  return (
    <ModalShell title={t('mods.horizon.consent.title')} onClose={onCancel}>
      <div className="flex flex-col gap-3 text-[13px] leading-relaxed text-[rgba(255,255,255,0.75)]">
        <p>{t('mods.horizon.consent.body1')}</p>
        <p>{t('mods.horizon.consent.body2')}</p>
        <p className="text-[12px] text-[rgba(255,255,255,0.5)]">{t('mods.horizon.consent.size', { size: formatBytes(downloadBytes) })}</p>
        <div className="mt-2 flex justify-end gap-2">
          <motion.button {...press} onClick={onCancel} className="h-9 rounded-lg px-4 text-[12.5px] font-semibold text-[rgba(255,255,255,0.65)] hover:bg-[rgba(255,255,255,0.06)]">
            {t('mods.horizon.consent.cancel')}
          </motion.button>
          <motion.button {...press} onClick={onAllow} className="h-9 rounded-lg bg-[rgb(var(--accent))] px-4 text-[12.5px] font-bold text-white hover:bg-[rgb(var(--accent-hover))]">
            {t('mods.horizon.consent.allow')}
          </motion.button>
        </div>
      </div>
    </ModalShell>
  )
}
