import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-dialog'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import type { Instance, Mod, ModpackMeta } from '@/types'
import { searchModrinthModpacks, resolveModpackFile, type ModpackHit } from '@/lib/modrinthModpacks'
import { ImportSourceModal } from '@/components/import/ImportSourceModal'
import { ImportChoiceModal } from '@/components/import/ImportChoiceModal'
import { InstalledTab } from '@/components/mods/InstalledTab'
import { ModpackBanner } from '@/components/mods/ModpackBanner'
import { ModpackBrowseTab } from '@/components/mods/ModpackBrowseTab'
import { BrowseTab } from '@/components/mods/BrowseTab'
import { ModDetailModal } from '@/components/mods/ModDetailModal'
import { PageHeader } from '@/components/ui/PageHeader'
import { showError } from '@/stores/useErrorToast'
import {
  displayName, baseFilename, fetchVersionsByHash, checkForUpdates, fetchModrinthSearch, fetchLatestVersion,
  fetchProjectDetail, _modrinthCache, _iconCache,
  type ModrinthInfo, type ModUpdate, type ModrinthHit, type Tab,
} from '@/components/mods/modUtils'

export { updateModsForNewVersion } from '@/components/mods/modUtils'

// ── ModsContent — embeddable in any page ──────────────────────────────────────

export function ModsContent({ instance }: { instance: Instance }) {
  const instanceId = instance.id
  const mcVersion = instance.mc_version
  const loader = instance.loader
  const isPlugin = loader === 'vanilla'
  const { avoidBetaDependencies, pinnedMods } = useStore()

  const pinnedProjectIds = useMemo(() => {
    const prefix = `${instanceId}:`
    return new Set(
      Object.keys(pinnedMods)
        .filter((k) => k.startsWith(prefix))
        .map((k) => k.slice(prefix.length)),
    )
  }, [pinnedMods, instanceId])

  const [tab, setTab] = useState<Tab>('installed')
  const [mods, setMods] = useState<Mod[]>([])
  const [loadingMods, setLoadingMods] = useState(true)
  const [modsError, setModsError] = useState('')
  const [uploading, setUploading] = useState(false)
  const [versionMap, setVersionMap] = useState<Record<string, ModrinthInfo>>({})
  const [updates, setUpdates] = useState<ModUpdate[]>([])
  const [updatingMods, setUpdatingMods] = useState<Set<string>>(new Set())
  const [updatingAll, setUpdatingAll] = useState(false)
  const [updatingPackAll, setUpdatingPackAll] = useState(false)
  const [importNotice, setImportNotice] = useState('')
  const [showImportChoice, setShowImportChoice] = useState(false)
  const [showImportFolder, setShowImportFolder] = useState(false)

  const mergeVersions = useCallback((fetched: Record<string, ModrinthInfo>) =>
    setVersionMap((prev) => ({ ...prev, ...fetched })), [])

  const [query, setQuery] = useState('')
  const [results, setResults] = useState<ModrinthHit[]>([])
  const [searching, setSearching] = useState(false)
  const [installing, setInstalling] = useState<string | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const [modSearch, setModSearch] = useState('')
  const [logoCache, setLogoCache] = useState<Record<string, string | null>>({})
  const [detailHit, setDetailHit] = useState<ModrinthHit | null>(null)
  const [switchingSha1, setSwitchingSha1] = useState<string | null>(null)

  const installedByProject = useMemo(() => {
    const map: Record<string, Mod> = {}
    for (const mod of mods) {
      const info = versionMap[mod.sha1]
      if (info?.projectId) map[info.projectId] = mod
    }
    return map
  }, [mods, versionMap])

  const [modpackMeta, setModpackMeta] = useState<ModpackMeta | null>(null)
  const [modpackMenuOpen, setModpackMenuOpen] = useState(false)
  const [showPackContent, setShowPackContent] = useState(false)
  const [packQuery, setPackQuery] = useState('')
  const [packResults, setPackResults] = useState<ModpackHit[]>([])
  const [packSearching, setPackSearching] = useState(false)
  const [packInstalling, setPackInstalling] = useState<string | null>(null)
  const packDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    api.modpacks.getMeta(instanceId).then(setModpackMeta).catch(() => setModpackMeta(null))
  }, [instanceId])

  const packFileSet = new Set((modpackMeta?.mod_files ?? []).map((f) => f.toLowerCase()))
  const isPackMod = (name: string) => packFileSet.has(baseFilename(name).toLowerCase())

  const runPackSearch = async (q: string) => {
    setPackSearching(true)
    try {
      setPackResults(await searchModrinthModpacks(q))
    } catch {
      showError('Impossible de joindre Modrinth')
    } finally {
      setPackSearching(false)
    }
  }

  const handlePackQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value
    setPackQuery(q)
    if (packDebounceRef.current) clearTimeout(packDebounceRef.current)
    packDebounceRef.current = setTimeout(() => runPackSearch(q), 450)
  }

  const handleInstallModpack = async (hit: ModpackHit) => {
    setPackInstalling(hit.project_id)
    try {
      const file = await resolveModpackFile(hit.project_id)
      if (!file) throw new Error('Aucun fichier .mrpack disponible')
      const meta = await api.modpacks.install({
        instanceId,
        fileUrl: file.url,
        projectId: hit.project_id,
        versionId: file.versionId,
        name: hit.title,
        author: hit.author,
        summary: hit.description,
        iconUrl: hit.icon_url,
        versionNumber: file.versionNumber,
        downloads: hit.downloads,
        dateModified: hit.date_modified,
        categories: hit.categories,
      })
      setModpackMeta(meta)
      setTab('installed')
      delete _modrinthCache[instanceId]
      await loadMods()
    } catch (e) {
      showError(e)
    } finally {
      setPackInstalling(null)
    }
  }

  const handleRemoveModpack = async () => {
    setModpackMenuOpen(false)
    try {
      await api.modpacks.remove(instanceId)
      setModpackMeta(null)
      delete _modrinthCache[instanceId]
      await loadMods()
    } catch (e) { showError(e) }
  }

  const handleReplaceModpack = () => {
    setModpackMenuOpen(false)
    setTab('modpack')
  }

  const handleToggleShowPackContent = () => {
    setModpackMenuOpen(false)
    setShowPackContent((v) => !v)
  }

  useEffect(() => {
    if (mods.length === 0) return
    const fromCache: Record<string, string | null> = {}
    const missing: Mod[] = []

    for (const mod of mods) {
      const key = displayName(mod.name)
      const cacheKey = `${instanceId}/${key}`
      if (cacheKey in _iconCache) {
        fromCache[key] = _iconCache[cacheKey]
      } else {
        missing.push(mod)
      }
    }

    if (Object.keys(fromCache).length > 0)
      setLogoCache((prev) => ({ ...prev, ...fromCache }))

    missing.forEach(async (mod) => {
      const key = displayName(mod.name)
      const cacheKey = `${instanceId}/${key}`
      try {
        const dataUrl = await api.mods.icon(instanceId, mod.name)
        _iconCache[cacheKey] = dataUrl
        setLogoCache((prev) => ({ ...prev, [key]: dataUrl }))
      } catch {
        _iconCache[cacheKey] = null
        setLogoCache((prev) => ({ ...prev, [key]: null }))
      }
    })
  }, [mods])

  const loadMods = async () => {
    if (!instanceId) return
    setLoadingMods(true)
    setModsError('')
    try {
      const loaded = await api.mods.list(instanceId)
      setMods(loaded)
      const cached = _modrinthCache[instanceId]
      if (cached) {
        setVersionMap(cached.versionMap)
        setUpdates(cached.updates)
      } else {
        fetchVersionsByHash(loaded.map((m) => m.sha1)).then((vd) => {
          mergeVersions(vd)
          checkForUpdates(instanceId, loaded, vd, mcVersion, loader, avoidBetaDependencies, pinnedProjectIds).then((upd) => {
            setUpdates(upd)
            _modrinthCache[instanceId] = { versionMap: vd, updates: upd }
          })
        })
      }
    } catch {
      setModsError('Impossible de charger les mods')
    } finally {
      setLoadingMods(false)
    }
  }

  useEffect(() => { setVersionMap({}); setUpdates([]); loadMods() }, [instanceId])

  useEffect(() => {
    if (tab === 'browse' && results.length === 0 && !searching) {
      runSearch(query)
    }
    if (tab === 'modpack' && packResults.length === 0 && !packSearching) {
      runPackSearch(packQuery)
    }
  }, [tab])

  const handleToggle = useCallback(async (mod: Mod) => {
    try {
      const updated = await api.mods.toggle(instanceId, mod.name)
      setMods((prev) => prev.map((m) => m.name === mod.name ? updated : m))
    } catch (e) { showError(e) }
  }, [instanceId])

  const handleDelete = useCallback(async (name: string) => {
    try {
      await api.mods.delete(instanceId, name)
      setMods((prev) => prev.filter((m) => m.name !== name))
      delete _modrinthCache[instanceId]
    } catch (e) { showError(e) }
  }, [instanceId])

  const handleUpdateMod = useCallback(async (update: ModUpdate) => {
    setUpdatingMods((prev) => new Set([...prev, update.mod.sha1]))
    try {
      const newMod = await api.mods.install(instanceId, update.fileUrl, update.filename)
      if (newMod.name !== update.mod.name) {
        await api.mods.delete(instanceId, update.mod.name).catch(() => {})
        setMods((prev) =>
          [...prev.filter((m) => m.name !== update.mod.name), newMod]
            .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
        )
      } else {
        setMods((prev) => prev.map((m) => m.name === update.mod.name ? newMod : m))
      }
      setUpdates((prev) => prev.filter((u) => u.mod.sha1 !== update.mod.sha1))
      fetchVersionsByHash([newMod.sha1]).then(mergeVersions)
      delete _modrinthCache[instanceId]

      // Si ce mod fait partie du modpack, on garde la référence à jour dans
      // modpack.json (sinon le nouveau fichier "tombe" hors du pack).
      const oldBase = baseFilename(update.mod.name)
      if (modpackMeta?.mod_files.some((f) => f.toLowerCase() === oldBase.toLowerCase())) {
        api.modpacks.renameFile(instanceId, oldBase, newMod.name).then((meta) => {
          if (meta) setModpackMeta(meta)
        }).catch(() => {})
      }
    } catch (e) { showError(e) }
    finally {
      setUpdatingMods((prev) => { const s = new Set(prev); s.delete(update.mod.sha1); return s })
    }
  }, [instanceId, modpackMeta, mergeVersions])

  const handleUpdateAll = async () => {
    if (updatingAll) return
    setUpdatingAll(true)
    const pending = updates.filter((u) => u.blockedBy.length === 0 && !isPackMod(u.mod.name))
    for (const update of pending) await handleUpdateMod(update)
    setUpdatingAll(false)
  }

  const handleUpdateAllPack = async () => {
    if (updatingPackAll) return
    setModpackMenuOpen(false)
    setUpdatingPackAll(true)
    const pending = updates.filter((u) => u.blockedBy.length === 0 && isPackMod(u.mod.name))
    for (const update of pending) await handleUpdateMod(update)
    setUpdatingPackAll(false)
  }

  const handlePickJars = async () => {
    if (uploading) return
    const picked = await open({ multiple: true, filters: [{ name: 'Mod', extensions: ['jar'] }] })
    if (!picked) return
    const paths = Array.isArray(picked) ? picked : [picked]
    if (paths.length === 0) return

    setUploading(true)
    setImportNotice('')
    try {
      const result = await api.mods.importPaths(instanceId, paths)
      if (result.imported.length > 0) {
        setMods((prev) => {
          const byName = new Map(prev.map((m) => [m.name, m]))
          for (const m of result.imported) byName.set(m.name, m)
          return Array.from(byName.values()).sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
        })
        fetchVersionsByHash(result.imported.map((m) => m.sha1)).then(mergeVersions)
        delete _modrinthCache[instanceId]
      }
      if (result.skipped.length > 0) {
        setImportNotice(`${result.skipped.length} mod(s) déjà présent(s) ignoré(s)`)
      }
    } catch (e) {
      showError(e)
    } finally {
      setUploading(false)
    }
  }

  const runSearch = async (q: string) => {
    setSearching(true)
    if (q.trim()) api.analytics.track('mod_search_performed', { query: q.trim() })
    try {
      setResults(await fetchModrinthSearch(q, mcVersion, loader))
    } catch {
      showError('Impossible de joindre Modrinth')
    } finally {
      setSearching(false)
    }
  }

  const handleQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value
    setQuery(q)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => runSearch(q), 450)
  }

  const isInstalled = (slug: string) =>
    mods.some((m) => displayName(m.name).toLowerCase().includes(slug.toLowerCase()))

  const handleInstall = async (hit: ModrinthHit) => {
    setInstalling(hit.project_id)
    try {
      const version = await fetchLatestVersion(hit.slug, mcVersion, loader)
      if (!version) throw new Error('Aucune version compatible')
      const file = version.files.find((f) => f.primary) ?? version.files[0]
      if (!file) throw new Error('Aucun fichier disponible')
      const newMod = await api.mods.install(instanceId, file.url, file.filename)
      setMods((prev) =>
        [...prev.filter((m) => m.name !== newMod.name), newMod]
          .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
      )
      fetchVersionsByHash([newMod.sha1]).then(mergeVersions)
      delete _modrinthCache[instanceId]
    } catch (e) {
      showError(e)
    } finally {
      setInstalling(null)
    }
  }

  /// Installe un fichier de version précis choisi dans le panneau de détail — remplace le
  /// jar existant du même projet Modrinth s'il y en a un (permet de changer de version,
  /// contrairement à `handleInstall` qui refuse les mods déjà installés).
  const handleInstallVersion = async (hit: ModrinthHit, file: { url: string; filename: string }) => {
    const existing = installedByProject[hit.project_id]
    const newMod = await api.mods.install(instanceId, file.url, file.filename)
    if (existing && existing.name !== newMod.name) {
      await api.mods.delete(instanceId, existing.name).catch(() => {})
    }
    setMods((prev) => {
      const withoutOld = existing ? prev.filter((m) => m.name !== existing.name) : prev
      return [...withoutOld.filter((m) => m.name !== newMod.name), newMod]
        .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
    })
    fetchVersionsByHash([newMod.sha1]).then(mergeVersions)
    delete _modrinthCache[instanceId]
  }

  /// Ouvre directement le sélecteur de version d'un mod déjà installé (liste
  /// Installés) — avant, il fallait le rechercher à nouveau dans l'onglet
  /// Parcourir pour accéder à la liste des versions.
  const handleSwitchVersion = async (mod: Mod) => {
    const projectId = versionMap[mod.sha1]?.projectId
    if (!projectId) return
    setSwitchingSha1(mod.sha1)
    try {
      const detail = await fetchProjectDetail(projectId)
      if (!detail) { showError('Impossible de joindre Modrinth'); return }
      setDetailHit({
        project_id: detail.id,
        slug: detail.id,
        title: detail.title,
        description: detail.description,
        icon_url: detail.icon_url,
        downloads: detail.downloads,
        categories: detail.categories,
      })
    } finally {
      setSwitchingSha1(null)
    }
  }

  const extraUpdatesCount = updates.filter((u) => u.blockedBy.length === 0 && !isPackMod(u.mod.name)).length
  const packUpdatesCount = updates.filter((u) => u.blockedBy.length === 0 && isPackMod(u.mod.name)).length

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* Sub-header: 3 zones — gauche/centre/droite */}
      <div
        className="flex flex-shrink-0 items-center px-6 py-3 border-b border-b-[rgba(255,255,255,0.05)]"
      >
        {/* Gauche : tab Installés + badge mises à jour */}
        <div className="flex flex-1 items-center gap-1">
          <button
            onClick={() => setTab('installed')}
            className={`rounded-lg px-4 py-1.5 text-xs font-semibold transition-all duration-150 border ${
              tab === 'installed'
                ? 'bg-[rgba(75,63,207,0.25)] text-[rgba(255,255,255,0.9)] border-[rgba(75,63,207,0.5)]'
                : 'bg-transparent text-[rgba(255,255,255,0.35)] border-transparent'
            }`}
          >
            {`Installés (${mods.length})`}
          </button>
          {tab === 'installed' && extraUpdatesCount > 0 && (
            <button
              onClick={handleUpdateAll}
              disabled={updatingAll}
              title="Tout mettre à jour (contenu supplémentaire uniquement)"
              className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-bold transition-all duration-150 border border-[rgba(250,204,21,0.28)] ${
                updatingAll
                  ? 'bg-[rgba(255,255,255,0.04)] text-[rgba(255,255,255,0.25)] cursor-not-allowed'
                  : 'bg-[rgba(250,204,21,0.12)] text-[rgba(250,204,21,0.9)] cursor-pointer'
              }`}
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}>
                <path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z" />
              </svg>
              {updatingAll ? '...' : extraUpdatesCount}
            </button>
          )}
        </div>

        {/* Centre : boutons d'ajout groupés */}
        <div className="flex items-center border border-[rgba(75,63,207,0.35)] rounded-[10px] overflow-hidden">
          <button
            onClick={() => setTab('browse')}
            className={`flex items-center gap-1.5 font-semibold transition-all duration-150 h-8 pl-[14px] pr-[14px] text-[12px] cursor-pointer border-r border-r-[rgba(75,63,207,0.35)] ${
              tab === 'browse'
                ? 'bg-[rgba(75,63,207,0.25)] text-[rgba(255,255,255,0.9)]'
                : 'bg-transparent text-[rgba(255,255,255,0.55)] hover:bg-[rgba(75,63,207,0.12)]'
            }`}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}>
              <path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
            </svg>
            {isPlugin ? 'Parcourir les plugins' : 'Parcourir Modrinth'}
          </button>
          <button
            onClick={() => setTab('modpack')}
            className={`flex items-center gap-1.5 font-semibold transition-all duration-150 h-8 pl-[14px] pr-[14px] text-[12px] cursor-pointer border-r border-r-[rgba(75,63,207,0.35)] ${
              tab === 'modpack'
                ? 'bg-[rgba(75,63,207,0.25)] text-[rgba(255,255,255,0.9)]'
                : 'bg-transparent text-[rgba(255,255,255,0.55)] hover:bg-[rgba(75,63,207,0.12)]'
            }`}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}>
              <path d="M12 2L1 9l11 7 9-5.73V17h2V9L12 2zM3 13.18v4.91L12 23l9-4.91v-4.91l-9 5.73-9-5.73z" />
            </svg>
            {modpackMeta ? 'Remplacer le modpack' : 'Installer un modpack'}
          </button>
          <button
            onClick={() => setShowImportChoice(true)}
            disabled={uploading}
            className={`flex items-center gap-1.5 font-semibold transition-all duration-150 active:scale-95 h-8 pl-[14px] pr-[14px] text-[12px] cursor-pointer ${
              uploading
                ? 'bg-[rgba(40,38,65,0.7)] text-[rgba(255,255,255,0.3)] cursor-not-allowed'
                : 'bg-[rgba(75,63,207,0.3)] text-[rgba(255,255,255,0.85)] hover:bg-[rgba(75,63,207,0.5)]'
            }`}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}>
              <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
            </svg>
            {uploading ? 'Import...' : 'Importer'}
          </button>
        </div>

        {/* Droite : informations de l'instance */}
        <div className="flex flex-1 items-center justify-end gap-3">
          {importNotice && (
            <span className="text-[10.5px] text-[rgba(179,163,255,0.9)]">{importNotice}</span>
          )}
          <span className="text-[11px] text-[rgba(255,255,255,0.25)]">
            {instance.name} · {mcVersion} · {loader}
          </span>
        </div>
      </div>

      {showImportChoice && (
        <ImportChoiceModal
          isPlugin={isPlugin}
          onClose={() => setShowImportChoice(false)}
          onPickJars={handlePickJars}
          onPickFolder={() => setShowImportFolder(true)}
        />
      )}

      {showImportFolder && (
        <ImportSourceModal
          fixedInstanceId={instanceId}
          onClose={() => setShowImportFolder(false)}
          onImported={() => loadMods()}
        />
      )}

      {detailHit && (
        <ModDetailModal
          hit={detailHit}
          instanceId={instanceId}
          mcVersion={mcVersion}
          loader={loader}
          installedMod={installedByProject[detailHit.project_id] ?? null}
          installedVersionNumber={
            installedByProject[detailHit.project_id]
              ? versionMap[installedByProject[detailHit.project_id].sha1]?.version ?? null
              : null
          }
          onClose={() => setDetailHit(null)}
          onInstall={(file) => handleInstallVersion(detailHit, file)}
        />
      )}

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-6 py-3">
        {modpackMeta && (
          <ModpackBanner
            meta={modpackMeta}
            menuOpen={modpackMenuOpen}
            showPackContent={showPackContent}
            packUpdatesCount={packUpdatesCount}
            updatingPackAll={updatingPackAll}
            onToggleMenu={() => setModpackMenuOpen((v) => !v)}
            onReplace={handleReplaceModpack}
            onRemove={handleRemoveModpack}
            onToggleShowContent={handleToggleShowPackContent}
            onUpdateAllPack={handleUpdateAllPack}
          />
        )}

        {tab === 'installed' ? (
          <InstalledTab
            mods={mods}
            modpackMeta={modpackMeta}
            showPackContent={showPackContent}
            loading={loadingMods}
            error={modsError}
            isPlugin={isPlugin}
            modSearch={modSearch}
            onModSearch={setModSearch}
            logoCache={logoCache}
            versionMap={versionMap}
            updates={updates}
            updatingMods={updatingMods}
            updatingAll={updatingAll}
            onReload={loadMods}
            onToggle={handleToggle}
            onDelete={handleDelete}
            onUpdateMod={handleUpdateMod}
            onSwitchVersion={handleSwitchVersion}
            switchingSha1={switchingSha1}
            onBrowseExtra={() => setTab('modpack')}
            onUploadExtra={handlePickJars}
          />
        ) : tab === 'browse' ? (
          <BrowseTab
            query={query}
            results={results}
            searching={searching}
            installing={installing}
            isInstalled={isInstalled}
            isPlugin={isPlugin}
            onQueryChange={handleQueryChange}
            onInstall={handleInstall}
            onOpenDetail={setDetailHit}
          />
        ) : (
          <ModpackBrowseTab
            query={packQuery}
            results={packResults}
            searching={packSearching}
            installing={packInstalling}
            onQueryChange={handlePackQueryChange}
            onInstall={handleInstallModpack}
          />
        )}
      </div>
    </div>
  )
}

// ── Standalone page (kept for /mods route) ────────────────────────────────────

export default function Mods() {
  const navigate = useNavigate()
  const { selectedInstance } = useStore()
  const instance = selectedInstance()

  if (!instance) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-[#09090D] text-white">
        <div className="text-[36px]">🧱</div>
        <p className="text-[14px] text-[rgba(255,255,255,0.4)] font-semibold">Aucune instance sélectionnée</p>
        <button
          onClick={() => navigate('/instances')}
          className="font-semibold transition-all duration-200 active:scale-95 h-[38px] px-5 rounded-[10px] text-[13px] bg-[#4B3FCF] text-white"
        >
          Gérer les instances
        </button>
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col bg-[#09090D] text-white">
      <PageHeader>
        <h1 className="font-black text-white text-[18px] tracking-[-0.01em]">Mods</h1>
      </PageHeader>
      <ModsContent instance={instance} />
    </div>
  )
}
