import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { SNAP, press, pressIf } from '@/lib/motion'
import { formatBytes, formatDate, formatDownloadCount } from '@/lib/format'
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
import { CloseButton } from '@/components/ui/CloseButton'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { PacksTab } from './PacksTab'
import type { Instance } from '@/types'
import type {
  HorizonDataPack, HorizonInstalled, HorizonInstalledPack, HorizonKind, HorizonLeftOut, HorizonNativeReport,
  HorizonNativeStatus, HorizonProject, HorizonWorld,
} from '@/types/horizon'

/**
 * Le contenu d'une instance Horizon tiré de son registre (`horizon-mods`).
 *
 * Deux écrans, comme pour une instance Java :
 * - **Mods** (`HorizonContentTab`) : les mods et mods natifs, les modpacks
 *   `.hpack` ; cartes et fiche au rendu de celles de Modrinth.
 * - **Packs** (`HorizonPacksTab`) : shaders, packs de ressources, packs de
 *   données. Les packs du registre d'Horizon passent **d'abord**, ils sont
 *   faits pour lui ; ceux de Modrinth viennent dessous, séparés et signalés
 *   comme non garantis — un pack Java peut ne pas marcher sur Horizon.
 *
 * Les mods natifs sont du code Rust compilé sur la machine du joueur au
 * lancement : on demande l'accord une fois (`NativeConsent`) avant d'en
 * installer ou d'en allumer un, et l'onglet des installés montre ce que le
 * dernier lancement a dû laisser de côté, avec le build pour lequel un mod
 * est fait quand ce n'est pas celui de l'instance — proposé, jamais imposé.
 */

const PAGE_SIZE = 24
type KindFilter = 'all' | 'mod' | 'native' | 'shader' | 'resourcepack' | 'datapack' | 'modpack'
type Sort = 'popular' | 'recent' | 'name'
type Section = 'mods' | 'native' | 'modpacks' | 'shaders' | 'datapacks'

const KIND_OF: Record<HorizonKind, KindFilter> = {
  'mod': 'mod',
  'native-mod': 'native',
  'shader': 'shader',
  'shaderpack': 'shader',
  'resourcepack': 'resourcepack',
  'datapack': 'datapack',
  'modpack': 'modpack',
}

/** Ce que l'instance a déjà d'un projet : sa version, et son fichier s'il en a un. */
type Mine = { version: string; file?: string }

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

/** Ce que l'instance a du registre, l'état des mods natifs, et l'accord. */
function useHorizonContent(instance: Instance) {
  const loaderVersion = instance.loader_version ?? ''
  const [installed, setInstalled] = useState<HorizonInstalled[]>([])
  const [status, setStatus] = useState<HorizonNativeStatus | null>(null)
  const [report, setReport] = useState<HorizonNativeReport | null>(null)
  const [consent, setConsent] = useState<null | (() => Promise<void>)>(null)
  const [packs, setPacks] = useState<HorizonInstalledPack[]>([])
  const [datapacks, setDatapacks] = useState<HorizonDataPack[]>([])

  const reload = useCallback(async () => {
    try {
      const [list, st, rep, mp, dp] = await Promise.all([
        api.horizon.installed(instance.id, loaderVersion),
        api.horizon.nativeStatus(),
        api.horizon.nativeReport(instance.id),
        api.horizon.modpacks(instance.id),
        api.horizon.datapacks(instance.id),
      ])
      setInstalled(list)
      setStatus(st)
      setReport(rep)
      setPacks(mp)
      setDatapacks(dp)
    } catch (e) {
      showError(e)
    }
  }, [instance.id, loaderVersion])

  useEffect(() => { void reload() }, [reload])
  useTauriEvent<HorizonNativeReport>('horizon_native_report', (r) => {
    if (r.instance_id === instance.id) setReport(r)
  }, [instance.id])

  /** Un geste qui met un mod natif en jeu passe d'abord par l'accord. */
  const withConsent = useCallback(async (action: () => Promise<void>) => {
    if (status?.allowed) return action()
    setConsent(() => action)
  }, [status?.allowed])

  const consentModal = (
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
  )

  return { installed, status, report, packs, datapacks, reload, withConsent, consentModal }
}

/** Le menu Mods d'une instance Horizon : parcourir, modpacks, installés. */
export function HorizonContentTab({ instance, mode, onBrowse, onCount }: {
  instance: Instance
  mode: 'installed' | 'browse' | 'modpack'
  onBrowse: () => void
  onCount?: (n: number) => void
}) {
  const c = useHorizonContent(instance)
  const mods = c.installed.filter((i) => i.kind !== 'shader')
  useEffect(() => { onCount?.(mods.length + c.packs.length) }, [mods.length, c.packs.length, onCount])
  return (
    <>
      {mode === 'installed' ? (
        <Installed
          instance={instance}
          sections={['mods', 'native', 'modpacks']}
          items={c.installed}
          packs={c.packs}
          datapacks={c.datapacks}
          status={c.status}
          report={c.report}
          onChanged={c.reload}
          onBrowse={onBrowse}
          withConsent={c.withConsent}
        />
      ) : (
        <Browse
          key={mode}
          instance={instance}
          kinds={mode === 'modpack' ? ['modpack'] : ['mod', 'native-mod']}
          chips={mode === 'modpack' ? [] : ['all', 'mod', 'native']}
          installed={c.installed}
          packs={c.packs}
          onChanged={c.reload}
          withConsent={c.withConsent}
        />
      )}
      {c.consentModal}
    </>
  )
}

/**
 * Le menu Packs d'une instance Horizon. Shaders et packs de ressources : une
 * seule recherche, puis deux blocs l'un sous l'autre — le registre d'Horizon
 * (faits pour lui) d'abord, Modrinth ensuite, signalé comme non garanti. Les
 * packs de données n'existent que dans le registre d'Horizon.
 */
export function HorizonPacksTab({ instance, mode, onInstalledCountChange }: {
  instance: Instance
  mode: 'installed' | 'shader' | 'resourcepack' | 'datapack'
  onInstalledCountChange?: (count: number) => void
}) {
  const t = useT()
  const c = useHorizonContent(instance)
  const [query, setQuery] = useState('')
  const [javaCount, setJavaCount] = useState(0)
  const shaders = c.installed.filter((i) => i.kind === 'shader')
  useEffect(() => {
    onInstalledCountChange?.(javaCount + shaders.length + c.datapacks.length)
  }, [javaCount, shaders.length, c.datapacks.length, onInstalledCountChange])
  useEffect(() => { setQuery('') }, [mode])

  if (mode === 'installed') {
    const horizonCount = shaders.length + c.datapacks.length
    return (
      <div className="flex flex-col gap-5">
        {horizonCount > 0 && (
          <SourceBlock source="horizon">
            <Installed
              instance={instance}
              sections={['shaders', 'datapacks']}
              items={c.installed}
              packs={c.packs}
              datapacks={c.datapacks}
              status={c.status}
              report={null}
              onChanged={c.reload}
              withConsent={c.withConsent}
            />
          </SourceBlock>
        )}
        {horizonCount > 0 && javaCount > 0 && <div className="h-px bg-[rgba(255,255,255,0.07)]" />}
        <div className={horizonCount > 0 && javaCount === 0 ? 'hidden' : ''}>
          <PacksTab instanceId={instance.id} mcVersion={instance.mc_version} mode="installed" onInstalledCountChange={setJavaCount} />
        </div>
      </div>
    )
  }

  if (mode === 'datapack') {
    return (
      <Browse
        key={mode}
        instance={instance}
        kinds={['datapack']}
        chips={[]}
        installed={c.installed}
        packs={c.packs}
        onChanged={c.reload}
        withConsent={c.withConsent}
      />
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="relative">
        <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2">
          <SearchIcon size={14} color="rgba(255,255,255,0.3)" />
        </span>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t(mode === 'shader' ? 'packs.searchShaders' : 'packs.searchResourcepacks')}
          enterKeyHint="search"
          className="h-10 w-full rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.05)] pl-9 pr-3 text-sm text-white outline-none focus:border-[rgb(var(--accent)/0.6)]"
        />
      </div>
      <SourceBlock source="horizon">
        <Browse
          key={mode}
          instance={instance}
          kinds={mode === 'shader' ? ['shader', 'shaderpack'] : ['resourcepack']}
          chips={[]}
          query={query}
          installed={c.installed}
          packs={c.packs}
          onChanged={c.reload}
          withConsent={c.withConsent}
        />
      </SourceBlock>
      <div className="h-px bg-[rgba(255,255,255,0.07)]" />
      <SourceBlock source="modrinth">
        <PacksTab instanceId={instance.id} mcVersion={instance.mc_version} mode={mode} query={query} />
      </SourceBlock>
    </div>
  )
}

/** Le titre d'un bloc de packs : d'où ils viennent, et ce que ça garantit. */
function SourceBlock({ source, children }: { source: 'horizon' | 'modrinth'; children: React.ReactNode }) {
  const t = useT()
  const horizon = source === 'horizon'
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-start gap-2.5">
        <span className={`mt-[3px] flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full ${horizon ? 'bg-[rgba(74,222,128,0.15)] text-[rgb(134,239,172)]' : 'bg-[rgba(250,204,21,0.14)] text-[rgba(250,204,21,0.9)]'}`}>
          <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
            <path d={horizon ? 'M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z' : 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z'} />
          </svg>
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[13px] font-bold text-white">{t(horizon ? 'mods.horizon.packsHorizonTitle' : 'mods.horizon.packsOtherTitle')}</p>
          <p className="text-[11.5px] text-[rgba(255,255,255,0.45)]">{t(horizon ? 'mods.horizon.packsHorizonHint' : 'mods.horizon.packsOtherHint')}</p>
        </div>
      </div>
      {children}
    </section>
  )
}

// ── Parcourir ────────────────────────────────────────────────────────────────

/**
 * Les projets du registre de sortes `kinds`. `chips` : les filtres proposés
 * (aucun pour une seule sorte). `query` : recherche tenue par l'écran qui
 * l'englobe (packs : une barre pour Horizon et Modrinth), sinon la sienne.
 */
function Browse({ instance, kinds, chips, query: externalQuery, installed, packs, onChanged, withConsent }: {
  instance: Instance
  kinds: HorizonKind[]
  chips: KindFilter[]
  query?: string
  installed: HorizonInstalled[]
  packs: HorizonInstalledPack[]
  onChanged: () => Promise<void>
  withConsent: (action: () => Promise<void>) => Promise<void>
}) {
  const t = useT()
  const loaderVersion = instance.loader_version ?? ''
  const [projects, setProjects] = useState<HorizonProject[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [ownQuery, setQuery] = useState('')
  const query = externalQuery ?? ownQuery
  const [kind, setKind] = useState<KindFilter>('all')
  const [sort, setSort] = useState<Sort>('popular')
  const [page, setPage] = useState(1)
  const [busy, setBusy] = useState<string | null>(null)
  const [detail, setDetail] = useState<HorizonProject | null>(null)
  /** Un pack de données attend qu'on choisisse son monde. */
  const [toWorld, setToWorld] = useState<{ project: HorizonProject; version?: string } | null>(null)

  useEffect(() => {
    let cancelled = false
    setFailed(false)
    loadRegistry(loaderVersion)
      .then((p) => { if (!cancelled) setProjects(p) })
      .catch(() => { if (!cancelled) setFailed(true) })
    return () => { cancelled = true }
  }, [loaderVersion])

  // Un pack de données peut aller dans plusieurs mondes : il reste toujours installable.
  const byProject = useMemo(() => {
    const map = new Map<string, Mine>()
    for (const i of installed) if (i.project) map.set(i.project, i)
    for (const p of packs) map.set(p.id, { version: p.version })
    return map
  }, [installed, packs])

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = (projects ?? []).filter((p) =>
      kinds.includes(p.kind)
      && (kind === 'all' || KIND_OF[p.kind] === kind)
      && (!q || p.name.toLowerCase().includes(q) || p.summary.toLowerCase().includes(q)
        || p.tags.some((tag) => tag.includes(q)) || p.authors.some((a) => a.toLowerCase().includes(q))))
    return [...list].sort((a, b) =>
      sort === 'name' ? a.name.localeCompare(b.name)
        : sort === 'recent' ? b.updated.localeCompare(a.updated)
          : b.downloads - a.downloads)
  }, [projects, kinds, query, kind, sort])

  useEffect(() => { setPage(1) }, [query, kind, sort])
  const pageCount = Math.max(1, Math.ceil(shown.length / PAGE_SIZE))
  const pageItems = shown.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)

  const install = async (project: HorizonProject, version?: string, world?: string) => {
    if (project.kind === 'datapack' && !world) {
      setToWorld({ project, version })
      return
    }
    const run = async () => {
      setBusy(project.id)
      try {
        await api.horizon.install(instance.id, project.id, loaderVersion, version, byProject.get(project.id)?.file, world)
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

  const chipClass = (active: boolean) =>
    `rounded-lg text-[11px] font-semibold transition-all duration-150 h-7 px-2.5 border ${
      active
        ? 'bg-[rgb(var(--accent)/0.35)] border-[rgb(var(--accent)/0.7)] text-[rgba(255,255,255,0.95)]'
        : 'bg-[rgba(0,0,0,0.3)] border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.4)]'
    }`

  return (
    <div className="flex flex-col gap-3">
      {externalQuery === undefined && (
        <div className="flex gap-2">
          <div className="relative flex-1">
            <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2">
              <SearchIcon size={15} color="rgba(255,255,255,0.3)" />
            </span>
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t('mods.horizon.searchPlaceholder')}
              enterKeyHint="search"
              className="h-10 w-full rounded-xl border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.05)] pl-9 pr-4 text-sm text-white outline-none focus:border-[rgb(var(--accent)/0.6)]"
            />
          </div>
          <select
            value={sort}
            onChange={(e) => setSort(e.target.value as Sort)}
            className="h-10 flex-shrink-0 rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(255,255,255,0.04)] px-3 text-[12px] font-semibold text-[rgba(255,255,255,0.7)] outline-none"
          >
            <option value="popular">{t('mods.horizon.sort.popular')}</option>
            <option value="recent">{t('mods.horizon.sort.recent')}</option>
            <option value="name">{t('mods.horizon.sort.name')}</option>
          </select>
        </div>
      )}
      {chips.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {chips.map((k) => (
            <motion.button {...press} key={k} onClick={() => setKind(k)} className={chipClass(kind === k)}>
              {t(`mods.horizon.kinds.${k}`)}
            </motion.button>
          ))}
        </div>
      )}

      {failed ? (
        <p className="py-10 text-center text-[13px] text-[rgba(255,255,255,0.5)]">{t('mods.horizon.unreachable')}</p>
      ) : projects === null ? (
        <SkeletonRows count={externalQuery === undefined ? 6 : 2} className="flex flex-col gap-2.5" rowClassName="h-[76px] rounded-2xl" />
      ) : shown.length === 0 ? (
        <p className="py-6 text-center text-[13px] text-[rgba(255,255,255,0.45)]">{t('mods.horizon.empty')}</p>
      ) : (
        <div className="flex flex-col gap-2">
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
      <AnimatePresence>
        {toWorld && (
          <WorldPicker
            instanceId={instance.id}
            name={toWorld.project.name}
            onCancel={() => setToWorld(null)}
            onPick={(world) => {
              const { project, version } = toWorld
              setToWorld(null)
              void install(project, version, world)
            }}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

/** Les pastilles à côté du nom, comme « Modrinth » sur ses cartes : la
 *  source, et « natif » pour un mod compilé sur la machine. */
function KindBadge({ kind }: { kind: HorizonKind }) {
  const t = useT()
  const pill = 'flex-shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-[0.03em]'
  return (
    <>
      <span className={`${pill} bg-[rgb(var(--accent)/0.2)] text-[rgb(var(--accent-text))]`}>{t('mods.horizon.badge')}</span>
      {kind === 'native-mod' && (
        <span title={t('mods.horizon.nativeHint')} className={`${pill} bg-[rgba(250,204,21,0.14)] text-[rgba(250,204,21,0.9)]`}>
          {t('mods.horizon.nativeBadge')}
        </span>
      )}
    </>
  )
}

function InstallButton({ project, mine, busy, onInstall }: {
  project: HorizonProject
  mine?: Mine
  busy: boolean
  onInstall: () => void
}) {
  const t = useT()
  const newest = project.versions[0]?.version
  const upToDate = mine && mine.version === newest
  // Même bouton que les cartes Modrinth (`ModrinthCard`).
  return (
    <motion.button
      {...pressIf(!busy && !upToDate)}
      disabled={busy || !!upToDate}
      onClick={(e) => { e.stopPropagation(); onInstall() }}
      className={`flex h-8 flex-shrink-0 items-center gap-1.5 rounded-xl border px-[14px] text-[12px] font-semibold transition-all duration-150 active:scale-95 ${
        upToDate
          ? 'cursor-not-allowed border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.3)]'
          : busy
            ? 'cursor-not-allowed border-[rgb(var(--accent)/0.5)] bg-[rgba(40,38,65,0.7)] text-[rgba(255,255,255,0.85)]'
            : 'cursor-pointer border-[rgb(var(--accent)/0.5)] bg-[rgb(var(--accent)/0.3)] text-[rgba(255,255,255,0.85)] hover:bg-[rgb(var(--accent)/0.5)]'
      }`}
    >
      {busy ? <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.15)" />
        : upToDate ? t('mods.installedButton')
          : mine ? t('mods.horizon.update')
            : t('mods.installButton')}
    </motion.button>
  )
}

function ProjectCard({ project, mine, busy, onOpen, onInstall }: {
  project: HorizonProject
  mine?: Mine
  busy: boolean
  onOpen: () => void
  onInstall: () => void
}) {
  const t = useT()
  // Le rendu exact d'une carte Modrinth (`ModrinthCard`) : même ligne, mêmes
  // tailles, la pastille « Horizon » à la place de « Modrinth ».
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => { if (e.key === 'Enter') onOpen() }}
      className="flex cursor-pointer flex-col gap-2.5 rounded-2xl border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.03)] px-4 py-3 transition-all duration-150 hover:bg-[rgba(255,255,255,0.05)]"
    >
      <div className="flex items-center gap-3">
        <div className="flex h-11 w-11 flex-shrink-0 items-center justify-center overflow-hidden rounded-xl bg-[rgba(255,255,255,0.06)]">
          {project.icon ? (
            <RemoteImage src={project.icon} alt={project.name} className="h-full w-full object-cover" />
          ) : (
            <PlugIcon size={20} color="rgba(255,255,255,0.2)" />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5">
            <p className="truncate text-[13px] font-semibold text-white">{project.name}</p>
            <KindBadge kind={project.kind} />
          </div>
          <p className="mt-0.5 truncate text-[11px] text-[rgba(255,255,255,0.35)]">{project.summary}</p>
          <p className="mt-[3px] text-[10px] text-[rgba(255,255,255,0.2)]">
            {formatDownloadCount(project.downloads)} {t('mods.downloads')}
            {project.authors.length > 0 && ` · ${project.authors.join(', ')}`}
          </p>
        </div>
        <InstallButton project={project} mine={mine} busy={busy} onInstall={onInstall} />
      </div>
    </div>
  )
}

function ProjectDetail({ project, mine, busy, onClose, onInstall }: {
  project: HorizonProject
  mine?: Mine
  busy: boolean
  onClose: () => void
  onInstall: (version?: string) => void
}) {
  const t = useT()
  // Même fiche que celle d'un mod Modrinth (`ModDetailModal`) : en-tête,
  // onglets Versions puis Description, lignes de version identiques.
  const [tab, setTab] = useState<'versions' | 'description'>('versions')
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  const hint = project.kind === 'native-mod' ? t('mods.horizon.nativeHint')
    : project.kind === 'datapack' ? t('mods.horizon.datapackHint')
      : project.kind === 'modpack' ? t('mods.horizon.modpackHint')
        : null
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(0,0,0,0.6)] p-6 backdrop-blur-[4px]"
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div className="flex h-[min(85vh,820px)] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-[rgba(255,255,255,0.08)] bg-[rgb(var(--bg-card))] shadow-[0_24px_80px_rgba(0,0,0,0.6)]">
        <div className="flex flex-shrink-0 items-start gap-4 px-6 pb-4 pt-6">
          <div className="flex h-[60px] w-[60px] flex-shrink-0 items-center justify-center overflow-hidden rounded-[16px] bg-[rgba(255,255,255,0.06)]">
            {project.icon ? <RemoteImage src={project.icon} eager className="h-full w-full object-cover" /> : <PlugIcon size={26} color="rgba(255,255,255,0.2)" />}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5">
              <p className="truncate text-[18px] font-black text-white">{project.name}</p>
              <KindBadge kind={project.kind} />
            </div>
            <p className="mt-0.5 line-clamp-2 text-[12.5px] text-[rgba(255,255,255,0.5)]">{project.summary}</p>
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <span className="flex items-center gap-1 text-[11px] text-[rgba(255,255,255,0.35)]">
                <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
                {formatDownloadCount(project.downloads)}
              </span>
              {project.categories.slice(0, 4).map((c) => (
                <span key={c} className="rounded-full bg-[rgba(255,255,255,0.06)] px-2 py-0.5 text-[10px] text-[rgba(255,255,255,0.45)]">{c}</span>
              ))}
              <span className="rounded-full bg-[rgba(255,255,255,0.06)] px-2 py-0.5 text-[10px] text-[rgba(255,255,255,0.45)]">
                {project.signed ? t('mods.horizon.signed') : t('mods.horizon.unsigned')}
              </span>
              {mine && (
                <span className="rounded-full bg-[rgb(var(--accent)/0.2)] px-2 py-0.5 text-[10px] font-semibold text-[rgb(var(--accent-text))]">
                  {t('mods.installedVersion', { version: mine.version })}
                </span>
              )}
              {project.source.startsWith('https://') && (
                <button onClick={() => openExternal(project.source)} className="rounded-full px-2 py-0.5 text-[10px] font-semibold text-[rgba(255,255,255,0.5)] hover:bg-[rgba(255,255,255,0.06)] hover:text-white">
                  {t('mods.horizon.source')} ↗
                </button>
              )}
            </div>
          </div>
          <CloseButton onClick={onClose} />
        </div>

        {hint && (
          <p className={`mx-6 mb-3 flex-shrink-0 rounded-xl px-3.5 py-2 text-[11.5px] ${
            project.kind === 'native-mod'
              ? 'border border-[rgba(250,204,21,0.22)] bg-[rgba(250,204,21,0.06)] text-[rgba(253,230,138,0.9)]'
              : 'border border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.03)] text-[rgba(255,255,255,0.6)]'
          }`}>{hint}</p>
        )}

        <nav className="flex flex-shrink-0 gap-1 border-b border-[rgba(255,255,255,0.07)] px-6">
          {(['versions', 'description'] as const).map((k) => (
            <button
              key={k}
              onClick={() => setTab(k)}
              className={`relative px-4 pb-3 pt-1 text-[13px] font-bold transition-colors ${tab === k ? 'text-white' : 'text-[rgba(255,255,255,0.4)] hover:text-[rgba(255,255,255,0.7)]'}`}
            >
              {t(k === 'versions' ? 'mods.tabs.versions' : 'mods.tabs.description')}
              {tab === k && <motion.span layoutId="horizon-detail-tab" transition={SNAP} className="absolute inset-x-2 -bottom-px h-[2px] rounded-full bg-accent" />}
            </button>
          ))}
        </nav>

        {tab === 'description' ? (
          <div className="min-h-0 flex-1 overflow-y-auto px-7 py-6">
            {project.screenshots.length > 0 && (
              <div className="mb-4 flex gap-2 overflow-x-auto pb-1">
                {project.screenshots.map((s) => (
                  <div key={s} className="h-36 flex-shrink-0 overflow-hidden rounded-lg bg-[rgba(255,255,255,0.04)]">
                    <RemoteImage src={s} className="h-full w-auto" />
                  </div>
                ))}
              </div>
            )}
            {project.description.trim()
              ? <RichDescription source={project.description} />
              : <p className="text-[13px] text-[rgba(255,255,255,0.6)]">{project.summary}</p>}
          </div>
        ) : (
          <div className="flex min-h-0 flex-1 flex-col gap-3 px-6 py-4">
            <p className="flex-shrink-0 text-[11px] font-bold uppercase tracking-[0.06em] text-[rgba(255,255,255,0.4)]">
              {t('mods.availableVersions')}
            </p>
            <div className="flex min-h-0 flex-1 flex-col gap-1.5 overflow-y-auto pr-1">
              {project.versions.map((v, i) => {
                const isInstalled = mine?.version === v.version
                return (
                  <div
                    key={v.version}
                    className={`flex items-center gap-3 rounded-xl border border-[rgba(255,255,255,0.06)] px-3 py-2 ${
                      isInstalled ? 'bg-[rgb(var(--accent)/0.1)]' : 'bg-[rgba(255,255,255,0.03)]'
                    }`}
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="text-[12.5px] font-semibold text-white">{v.version}</span>
                        {i === 0 && <span className="text-[9.5px] font-bold uppercase text-[rgba(74,222,128,0.85)]">{t('mods.horizon.latest')}</span>}
                      </div>
                      <p className="mt-0.5 text-[10.5px] text-[rgba(255,255,255,0.3)]">
                        {v.published} · {formatBytes(v.size)}
                        {v.made_for && ` · ${t('mods.horizon.madeForCommit', { commit: v.made_for.slice(0, 7) })}`}
                      </p>
                      {v.changelog && <p className="mt-1 line-clamp-3 whitespace-pre-line text-[11px] text-[rgba(255,255,255,0.5)]">{v.changelog}</p>}
                    </div>
                    <motion.button {...pressIf(!(isInstalled || busy))}
                      onClick={() => onInstall(v.version)}
                      disabled={isInstalled || busy}
                      className={`flex h-7 flex-shrink-0 items-center gap-1.5 rounded-lg border px-3 text-[11px] font-semibold transition-all duration-150 active:scale-95 ${
                        isInstalled
                          ? 'cursor-not-allowed border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.3)]'
                          : busy
                            ? 'cursor-not-allowed border-[rgb(var(--accent)/0.5)] bg-[rgba(40,38,65,0.7)] text-[rgba(255,255,255,0.85)]'
                            : 'cursor-pointer border-[rgb(var(--accent)/0.5)] bg-[rgb(var(--accent)/0.3)] text-[rgba(255,255,255,0.85)]'
                      }`}
                    >
                      {busy ? <ButtonSpinner size={12} trackColor="rgba(255,255,255,0.15)" />
                        : isInstalled ? t('mods.installedBadge') : mine ? t('mods.switchButton') : t('mods.installButton')}
                    </motion.button>
                  </div>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

// ── Installés ────────────────────────────────────────────────────────────────

/** Ce qui est installé, limité aux `sections` de l'écran qui l'affiche
 *  (Mods : mods, natifs, modpacks ; Packs : shaders Horizon, packs de données). */
function Installed({ instance, sections, items: allItems, packs: allPacks, datapacks: allDatapacks, status, report, onChanged, onBrowse, withConsent }: {
  instance: Instance
  sections: Section[]
  items: HorizonInstalled[]
  packs: HorizonInstalledPack[]
  datapacks: HorizonDataPack[]
  status: HorizonNativeStatus | null
  report: HorizonNativeReport | null
  onChanged: () => Promise<void>
  onBrowse?: () => void
  withConsent: (action: () => Promise<void>) => Promise<void>
}) {
  const items = allItems.filter((i) =>
    i.kind === 'mod' ? sections.includes('mods') : i.kind === 'native-mod' ? sections.includes('native') : sections.includes('shaders'))
  const packs = sections.includes('modpacks') ? allPacks : []
  const datapacks = sections.includes('datapacks') ? allDatapacks : []
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

  if (items.length === 0 && packs.length === 0 && datapacks.length === 0) {
    return onBrowse ? (
      <div className="flex flex-col items-center gap-3 py-16 text-center">
        <p className="text-[13px] text-[rgba(255,255,255,0.5)]">{t('mods.horizon.nothingInstalled')}</p>
        <motion.button {...press} onClick={onBrowse} className="h-9 rounded-lg bg-[rgb(var(--accent))] px-4 text-[12.5px] font-bold text-white hover:bg-[rgb(var(--accent-hover))]">
          {t('mods.horizon.browse')}
        </motion.button>
      </div>
    ) : null
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

      {packs.length > 0 && (
        <section className="flex flex-col gap-1.5">
          <h3 className="px-1 text-[11px] font-bold uppercase tracking-wider text-[rgba(255,255,255,0.4)]">
            {t('mods.horizon.sections.modpacks')} · {packs.length}
          </h3>
          {packs.map((p) => (
            <SimpleRow
              key={p.id}
              title={p.name}
              version={p.version}
              detail={t('mods.horizon.modpackMods', { n: p.mods.length })}
              busy={busy === `pack:${p.id}`}
              onRemove={() => run(`pack:${p.id}`, () => api.horizon.modpackRemove(instance.id, p.id))}
            />
          ))}
        </section>
      )}

      {datapacks.length > 0 && (
        <section className="flex flex-col gap-1.5">
          <h3 className="px-1 text-[11px] font-bold uppercase tracking-wider text-[rgba(255,255,255,0.4)]">
            {t('mods.horizon.sections.datapacks')} · {datapacks.length}
          </h3>
          {datapacks.map((d) => {
            const key = `data:${d.world}/${d.file}`
            return (
              <SimpleRow
                key={key}
                title={d.file.replace(/\.zip$/i, '')}
                detail={`${t('mods.horizon.datapackWorld', { world: d.world })}${d.description ? ` · ${d.description}` : ''}`}
                busy={busy === key}
                enabled={d.enabled}
                onToggle={() => run(key, () => api.horizon.datapackToggle(instance.id, d.world, d.file, !d.enabled))}
                onRemove={() => run(key, () => api.horizon.datapackRemove(instance.id, d.world, d.file))}
              />
            )
          })}
        </section>
      )}
    </div>
  )
}

/** Une ligne sans fichier de mod : un modpack, ou un pack de données dans un monde. */
function SimpleRow({ title, version, detail, busy, enabled, onToggle, onRemove }: {
  title: string
  version?: string
  detail: string
  busy: boolean
  enabled?: boolean
  onToggle?: () => void
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
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13px] font-semibold text-white">
          {title}
          {version && <span className="ml-2 text-[11px] font-normal text-[rgba(255,255,255,0.4)]">{version}</span>}
        </p>
        <p className="truncate text-[11px] text-[rgba(255,255,255,0.4)]" title={detail}>{detail}</p>
      </div>
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
      {onToggle && enabled !== undefined && (
        <Toggle size="sm" checked={enabled} onChange={() => { if (!busy) onToggle() }} />
      )}
    </div>
  )
}

/** Le monde où poser un pack de données. */
function WorldPicker({ instanceId, name, onCancel, onPick }: {
  instanceId: string
  name: string
  onCancel: () => void
  onPick: (world: string) => void
}) {
  const t = useT()
  const { language } = useStorePick('language')
  const [worlds, setWorlds] = useState<HorizonWorld[] | null>(null)
  useEffect(() => {
    api.horizon.worlds(instanceId).then(setWorlds).catch((e) => { showError(e); setWorlds([]) })
  }, [instanceId])
  return (
    <ModalShell title={t('mods.horizon.worldPicker.title', { name })} onClose={onCancel}>
      <div className="flex flex-col gap-3 text-[13px] text-[rgba(255,255,255,0.75)]">
        <p>{t('mods.horizon.worldPicker.body')}</p>
        {worlds === null ? (
          <div className="skeleton h-24 rounded-lg" />
        ) : worlds.length === 0 ? (
          <p className="rounded-lg bg-[rgba(255,255,255,0.04)] px-3 py-4 text-center text-[12px] text-[rgba(255,255,255,0.5)]">
            {t('mods.horizon.worldPicker.empty')}
          </p>
        ) : (
          <div className="flex max-h-[50vh] flex-col gap-1.5 overflow-y-auto">
            {worlds.map((w) => (
              <motion.button
                {...press}
                key={w.folder}
                onClick={() => onPick(w.folder)}
                className="flex items-center gap-3 rounded-lg border border-line-soft bg-bg-card px-3 py-2 text-left transition-colors duration-150 hover:border-[rgb(var(--accent)/0.4)]"
              >
                <span className="min-w-0 flex-1 truncate font-semibold text-white">{w.folder}</span>
                <span className="text-[11px] text-[rgba(255,255,255,0.4)]">{formatDate(w.modified * 1000, language)}</span>
              </motion.button>
            ))}
          </div>
        )}
        <div className="flex justify-end">
          <motion.button {...press} onClick={onCancel} className="h-9 rounded-lg px-4 text-[12.5px] font-semibold text-[rgba(255,255,255,0.65)] hover:bg-[rgba(255,255,255,0.06)]">
            {t('mods.horizon.consent.cancel')}
          </motion.button>
        </div>
      </div>
    </ModalShell>
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
          <p className="truncate text-[11px] text-[rgba(255,255,255,0.4)]" title={t('mods.horizon.changesGameHint')}>
            {formatBytes(item.size)} · {t('mods.horizon.changesGame')}
          </p>
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
      case 'engine': return t('mods.horizon.reasons.engine')
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
              <p className="text-[11px] text-[rgba(255,255,255,0.55)]" title={l.kind === 'compile' ? l.log : l.kind === 'engine' || l.kind === 'injections' ? l.error : undefined}>{reason(l)}</p>
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
        <p className="text-[12px] text-[rgba(255,255,255,0.5)]">{t('mods.horizon.consent.crates')}</p>
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
