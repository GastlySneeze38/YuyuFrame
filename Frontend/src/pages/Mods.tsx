import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-dialog'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { formatBytes } from '@/lib/format'
import type { Instance, Mod, ModInstallProgress, ModpackInstallProgress, ModpackMeta } from '@/types'
import { searchModrinthModpacks, resolveModpackFile, type ModpackHit, type ResolvedModpackFile } from '@/lib/modrinthModpacks'
import { ImportSourceModal } from '@/components/import/ImportSourceModal'
import { ImportChoiceModal } from '@/components/import/ImportChoiceModal'
import { InstalledTab } from '@/components/mods/InstalledTab'
import { ModpackBanner } from '@/components/mods/ModpackBanner'
import { ModpackBrowseTab } from '@/components/mods/ModpackBrowseTab'
import { BrowseTab, type MergedHit } from '@/components/mods/BrowseTab'
import {
  fetchCurseforgeSearch, fetchCurseforgeFiles, findFileForLoader, fetchCurseforgeInstalled, fetchCurseforgeModDetail,
  _curseforgeCache, type CurseforgeHit, type CurseforgeMatch,
} from '@/components/mods/curseforgeUtils'
import { ModDetailModal } from '@/components/mods/ModDetailModal'
import { CurseforgeDetailModal } from '@/components/mods/CurseforgeDetailModal'
import { PageHeader } from '@/components/ui/PageHeader'
import { showError, showApiError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import {
  displayName, baseFilename, fetchVersionsByHash, checkForUpdates, fetchModrinthSearch, fetchLatestVersion,
  fetchProjectDetail, _modrinthCache, _iconCache,
  type ModrinthInfo, type ModUpdate, type ModrinthHit, type ModrinthSearchFilters, type Tab,
} from '@/components/mods/modUtils'

export { updateModsForNewVersion } from '@/components/mods/modUtils'

// ── ModsContent — embeddable in any page ──────────────────────────────────────

export function ModsContent({ instance }: { instance: Instance }) {
  const t = useT()
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
  const [importNotice, setImportNotice] = useState('')
  const [showImportChoice, setShowImportChoice] = useState(false)
  const [showImportFolder, setShowImportFolder] = useState(false)

  const mergeVersions = useCallback((fetched: Record<string, ModrinthInfo>) =>
    setVersionMap((prev) => ({ ...prev, ...fetched })), [])

  const [query, setQuery] = useState('')
  const [results, setResults] = useState<ModrinthHit[]>([])
  const [searching, setSearching] = useState(false)
  const [installing, setInstalling] = useState<string | null>(null)
  const [installProgress, setInstallProgress] = useState<{ percent: number; label: string } | null>(null)
  const [searchFilters, setSearchFilters] = useState<ModrinthSearchFilters>({})
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // CurseForge — résultats de recherche fusionnés avec ceux de Modrinth dans le même
  // onglet "Parcourir" (voir mergedResults ci-dessous et BrowseTab.tsx) : une seule
  // barre de recherche déclenche les deux sources, avec dédoublonnage par nom.
  const [cfResults, setCfResults] = useState<CurseforgeHit[]>([])
  const [cfSearching, setCfSearching] = useState(false)
  const [cfInstalling, setCfInstalling] = useState<number | null>(null)
  const [cfInstallProgress, setCfInstallProgress] = useState<{ percent: number; label: string } | null>(null)
  const [cfInstalledModIds, setCfInstalledModIds] = useState<Set<number>>(new Set())
  const [cfMatchByModName, setCfMatchByModName] = useState<Record<string, CurseforgeMatch>>({})
  const [cfUpdates, setCfUpdates] = useState<ModUpdate[]>([])

  const cfModIdByName = useMemo(() => {
    const map: Record<string, number> = {}
    for (const [name, m] of Object.entries(cfMatchByModName)) map[name] = m.modId
    return map
  }, [cfMatchByModName])

  const cfVersionByName = useMemo(() => {
    const map: Record<string, string> = {}
    for (const [name, m] of Object.entries(cfMatchByModName)) map[name] = m.fileName
    return map
  }, [cfMatchByModName])

  /// Fusionne les deux sources de recherche dans une seule liste — Modrinth toujours
  /// affiché en premier (c'est la source "preview"), puis CurseForge en complément. Un
  /// mod CurseForge dont le nom correspond (insensible à la casse) à un résultat Modrinth
  /// déjà présent est retiré : on garde uniquement la version Modrinth (a une preview,
  /// des métadonnées plus riches), jamais les deux pour le même mod.
  const mergedResults = useMemo<MergedHit[]>(() => {
    const modrinthNames = new Set(results.map((r) => r.title.trim().toLowerCase()))
    const cfDeduped = cfResults.filter((r) => !modrinthNames.has(r.name.trim().toLowerCase()))
    return [
      ...results.map((hit): MergedHit => ({ source: 'modrinth', hit })),
      ...cfDeduped.map((hit): MergedHit => ({ source: 'curseforge', hit })),
    ]
  }, [results, cfResults])

  const pinnedCfModIds = useMemo(() => {
    const prefix = `${instanceId}:cf:`
    return new Set(
      Object.keys(pinnedMods)
        .filter((k) => k.startsWith(prefix))
        .map((k) => Number(k.slice(prefix.length))),
    )
  }, [pinnedMods, instanceId])

  const [modSearch, setModSearch] = useState('')
  const [logoCache, setLogoCache] = useState<Record<string, string | null>>({})
  const [detailHit, setDetailHit] = useState<ModrinthHit | null>(null)
  const [cfDetailHit, setCfDetailHit] = useState<CurseforgeHit | null>(null)
  const [switchingModName, setSwitchingModName] = useState<string | null>(null)

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
  const [packVersionUpdate, setPackVersionUpdate] = useState<ResolvedModpackFile | null>(null)
  const [updatingPackVersion, setUpdatingPackVersion] = useState(false)
  const [packQuery, setPackQuery] = useState('')
  const [packFilters, setPackFilters] = useState<ModrinthSearchFilters>({})
  const [packResults, setPackResults] = useState<ModpackHit[]>([])
  const [packSearching, setPackSearching] = useState(false)
  const [packInstalling, setPackInstalling] = useState<string | null>(null)
  const [packInstallProgress, setPackInstallProgress] = useState<{ percent: number; label: string } | null>(null)
  const [packImportingFile, setPackImportingFile] = useState(false)
  const packDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    api.modpacks.getMeta(instanceId).then(setModpackMeta).catch(() => setModpackMeta(null))
  }, [instanceId])

  // Détection d'une nouvelle version du modpack lui-même publiée sur
  // Modrinth (compare le version_id installé au dernier publié) — remplace
  // l'ancienne mise à jour "mod par mod" à l'intérieur du pack : un modpack
  // est un ensemble curé/testé par son auteur, y toucher mod par mod cassait
  // silencieusement la cohérence que le pack garantit. `project_id` vide =
  // pack importé depuis un fichier .mrpack local, pas de projet Modrinth à
  // vérifier. Best-effort : une erreur réseau laisse juste l'indicateur absent.
  useEffect(() => {
    setPackVersionUpdate(null)
    if (!modpackMeta?.project_id) return
    let cancelled = false
    resolveModpackFile(modpackMeta.project_id).then((file) => {
      if (!cancelled && file && file.versionId !== modpackMeta.version_id) {
        setPackVersionUpdate(file)
      }
    }).catch(() => {})
    return () => { cancelled = true }
  }, [modpackMeta?.project_id, modpackMeta?.version_id])

  const packFileSet = new Set((modpackMeta?.mod_files ?? []).map((f) => f.toLowerCase()))
  const isPackMod = (name: string) => packFileSet.has(baseFilename(name).toLowerCase())

  const runPackSearch = async (q: string, filters: ModrinthSearchFilters = packFilters) => {
    setPackSearching(true)
    try {
      setPackResults(await searchModrinthModpacks(q, filters))
    } catch {
      showError(t('mods.cannotReachModrinth'))
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

  const handlePackFiltersChange = (filters: ModrinthSearchFilters) => {
    setPackFilters(filters)
    if (packDebounceRef.current) clearTimeout(packDebounceRef.current)
    runPackSearch(packQuery, filters)
  }

  const handleInstallModpack = async (hit: ModpackHit) => {
    setPackInstalling(hit.project_id)
    setPackInstallProgress({ percent: 0, label: t('mods.downloadingModpack') })
    const unlisten = await listen<ModpackInstallProgress>('modpack_install_progress', (e) => {
      const { current, total, label } = e.payload
      const percent = total > 0 ? Math.min(100, Math.round((current / total) * 100)) : 0
      setPackInstallProgress({ percent, label: `${label} (${current}/${total})` })
    })
    try {
      const file = await resolveModpackFile(hit.project_id)
      if (!file) throw new Error(t('mods.noMrpackAvailable'))
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
      unlisten()
      setPackInstalling(null)
      setPackInstallProgress(null)
    }
  }

  const handleImportModpackFile = async () => {
    const picked = await open({ filters: [{ name: 'Modpack Modrinth', extensions: ['mrpack'] }] })
    if (!picked || Array.isArray(picked)) return

    // Bascule sur l'onglet modpack pour que la barre de progression ci-dessous
    // soit visible — l'import peut être déclenché depuis n'importe quel onglet
    // via la modal "Importer" (ImportChoiceModal), pas seulement depuis ici.
    setTab('modpack')
    setPackImportingFile(true)
    setPackInstallProgress({ percent: 0, label: t('mods.installingModpack') })
    const unlisten = await listen<ModpackInstallProgress>('modpack_install_progress', (e) => {
      const { current, total, label } = e.payload
      const percent = total > 0 ? Math.min(100, Math.round((current / total) * 100)) : 0
      setPackInstallProgress({ percent, label: `${label} (${current}/${total})` })
    })
    try {
      const meta = await api.modpacks.installFromPath(instanceId, picked)
      setModpackMeta(meta)
      setTab('installed')
      delete _modrinthCache[instanceId]
      await loadMods()
    } catch (e) {
      showError(e)
    } finally {
      unlisten()
      setPackImportingFile(false)
      setPackInstallProgress(null)
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

      const cfCached = _curseforgeCache[instanceId]
      if (cfCached) {
        setCfInstalledModIds(cfCached.installedModIds)
        setCfMatchByModName(cfCached.matchByModName)
        setCfUpdates(cfCached.updates)
      } else if (loaded.length > 0) {
        fetchCurseforgeInstalled(instanceId, loaded, mcVersion, loader, pinnedCfModIds).then((result) => {
          setCfInstalledModIds(result.installedModIds)
          setCfMatchByModName(result.matchByModName)
          setCfUpdates(result.updates)
          _curseforgeCache[instanceId] = result
        }).catch((e) => {
          console.error('fetchCurseforgeInstalled failed', e)
          showApiError(e, t('common.serverUnreachable'))
        })
      } else {
        setCfInstalledModIds(new Set())
        setCfMatchByModName({})
        setCfUpdates([])
      }
    } catch {
      setModsError(t('mods.cannotLoadMods'))
    } finally {
      setLoadingMods(false)
    }
  }

  useEffect(() => {
    setVersionMap({}); setUpdates([])
    setCfInstalledModIds(new Set()); setCfMatchByModName({}); setCfUpdates([])
    loadMods()
  }, [instanceId])

  useEffect(() => {
    if (tab === 'browse' && results.length === 0 && !searching) {
      runSearch(query)
    }
    if (tab === 'browse' && cfResults.length === 0 && !cfSearching) {
      runCfSearch(query)
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
      delete _curseforgeCache[instanceId]
    } catch (e) { showError(e) }
  }, [instanceId])

  const handleUpdateMod = useCallback(async (update: ModUpdate) => {
    setUpdatingMods((prev) => new Set([...prev, update.mod.sha1]))
    try {
      // `mods_install` (Modrinth) et `curseforge_mod_install` n'autorisent chacun que le
      // domaine CDN de leur propre source côté Rust — il faut appeler la bonne commande.
      const newMod = update.source === 'curseforge'
        ? await api.mods.installCurseforge(instanceId, update.fileUrl, update.filename)
        : await api.mods.install(instanceId, update.fileUrl, update.filename)
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
      setCfUpdates((prev) => prev.filter((u) => u.mod.sha1 !== update.mod.sha1))
      fetchVersionsByHash([newMod.sha1]).then(mergeVersions)
      delete _modrinthCache[instanceId]
      delete _curseforgeCache[instanceId]

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

  // Mises à jour Modrinth + CurseForge fusionnées dans une seule liste — le
  // mécanisme d'installation (`api.mods.install`) est source-agnostique, donc
  // la même UI (badge, bouton par mod, "tout mettre à jour") sert les deux.
  const allUpdates = useMemo(() => [...updates, ...cfUpdates], [updates, cfUpdates])

  const handleUpdateAll = async () => {
    if (updatingAll) return
    setUpdatingAll(true)
    const pending = allUpdates.filter((u) => u.blockedBy.length === 0 && !isPackMod(u.mod.name))
    for (const update of pending) await handleUpdateMod(update)
    setUpdatingAll(false)
  }

  const handleUpdatePackVersion = async () => {
    if (updatingPackVersion || !packVersionUpdate || !modpackMeta) return
    setModpackMenuOpen(false)
    setUpdatingPackVersion(true)
    setPackInstallProgress({ percent: 0, label: t('mods.downloadingModpack') })
    const unlisten = await listen<ModpackInstallProgress>('modpack_install_progress', (e) => {
      const { current, total, label } = e.payload
      const percent = total > 0 ? Math.min(100, Math.round((current / total) * 100)) : 0
      setPackInstallProgress({ percent, label: `${label} (${current}/${total})` })
    })
    try {
      const meta = await api.modpacks.install({
        instanceId,
        fileUrl: packVersionUpdate.url,
        projectId: modpackMeta.project_id,
        versionId: packVersionUpdate.versionId,
        name: modpackMeta.name,
        author: modpackMeta.author,
        summary: modpackMeta.summary,
        iconUrl: modpackMeta.icon_url,
        versionNumber: packVersionUpdate.versionNumber,
        downloads: modpackMeta.downloads,
        dateModified: modpackMeta.date_modified,
        categories: modpackMeta.categories,
      })
      setModpackMeta(meta)
      setPackVersionUpdate(null)
      delete _modrinthCache[instanceId]
      await loadMods()
    } catch (e) {
      showError(e)
    } finally {
      unlisten()
      setUpdatingPackVersion(false)
      setPackInstallProgress(null)
    }
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
        delete _curseforgeCache[instanceId]
      }
      if (result.skipped.length > 0) {
        setImportNotice(t('mods.skippedAlreadyPresent', { count: result.skipped.length }))
      }
    } catch (e) {
      showError(e)
    } finally {
      setUploading(false)
    }
  }

  const runSearch = async (q: string, filters: ModrinthSearchFilters = searchFilters) => {
    setSearching(true)
    if (q.trim()) api.analytics.track('mod_search_performed', { query: q.trim() })
    try {
      setResults(await fetchModrinthSearch(q, mcVersion, loader, filters))
    } catch {
      showError(t('mods.cannotReachModrinth'))
    } finally {
      setSearching(false)
    }
  }

  const handleQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value
    setQuery(q)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    // Une seule barre de recherche déclenche les deux sources en parallèle — voir
    // mergedResults pour la fusion + dédoublonnage des résultats obtenus.
    debounceRef.current = setTimeout(() => { runSearch(q); runCfSearch(q) }, 450)
  }

  const handleFiltersChange = (filters: ModrinthSearchFilters) => {
    setSearchFilters(filters)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    runSearch(query, filters)
  }

  const handleInstall = async (hit: ModrinthHit) => {
    setInstalling(hit.project_id)
    setInstallProgress({ percent: 0, label: t('mods.preparing') })
    const unlisten = await listen<ModInstallProgress>('mod_install_progress', (e) => {
      const { downloaded, total } = e.payload
      const percent = total > 0 ? Math.min(100, Math.round((downloaded / total) * 100)) : 0
      const label = total > 0 ? `${formatBytes(downloaded)} / ${formatBytes(total)}` : formatBytes(downloaded)
      setInstallProgress({ percent, label })
    })
    try {
      const version = await fetchLatestVersion(hit.slug, mcVersion, loader)
      if (!version) throw new Error(t('mods.noCompatibleVersion'))
      const file = version.files.find((f) => f.primary) ?? version.files[0]
      if (!file) throw new Error(t('mods.noFileAvailable'))
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
      unlisten()
      setInstalling(null)
      setInstallProgress(null)
    }
  }

  // ── CurseForge — résultats fusionnés avec Modrinth dans le même onglet (mergedResults) ──

  const runCfSearch = async (q: string) => {
    setCfSearching(true)
    if (q.trim()) api.analytics.track('mod_search_performed', { query: q.trim(), source: 'curseforge' })
    try {
      setCfResults(await fetchCurseforgeSearch(q, mcVersion))
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      setCfSearching(false)
    }
  }

  const handleCfInstall = async (hit: CurseforgeHit) => {
    setCfInstalling(hit.id)
    setCfInstallProgress({ percent: 0, label: t('mods.preparing') })
    const unlisten = await listen<ModInstallProgress>('mod_install_progress', (e) => {
      const { downloaded, total } = e.payload
      const percent = total > 0 ? Math.min(100, Math.round((downloaded / total) * 100)) : 0
      const label = total > 0 ? `${formatBytes(downloaded)} / ${formatBytes(total)}` : formatBytes(downloaded)
      setCfInstallProgress({ percent, label })
    })
    try {
      const files = await fetchCurseforgeFiles(hit.id, mcVersion)
      const file = findFileForLoader(files, loader)
      if (!file?.downloadUrl) throw new Error(t('mods.noFileAvailable'))
      const newMod = await api.mods.installCurseforge(instanceId, file.downloadUrl, file.fileName)
      const updatedMods = [...mods.filter((m) => m.name !== newMod.name), newMod]
        .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
      setMods(updatedMods)
      delete _curseforgeCache[instanceId]
      // Rafraîchit tout de suite la détection "déjà installé" pour CE mod, sans attendre un
      // rechargement complet de l'instance — sinon le mod qu'on vient d'installer continue
      // d'apparaître "non installé" dans la recherche tant qu'on n'a pas changé d'onglet/instance.
      fetchCurseforgeInstalled(instanceId, updatedMods, mcVersion, loader, pinnedCfModIds).then((result) => {
        setCfInstalledModIds(result.installedModIds)
        setCfMatchByModName(result.matchByModName)
        setCfUpdates(result.updates)
        _curseforgeCache[instanceId] = result
      }).catch((e) => console.error('fetchCurseforgeInstalled failed', e))
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      unlisten()
      setCfInstalling(null)
      setCfInstallProgress(null)
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

  /// Installe un fichier CurseForge précis choisi dans le panneau de détail — mirroir
  /// CurseForge de `handleInstallVersion` (Modrinth) : remplace le jar existant du même
  /// mod s'il y en a un, ce qui permet de changer de version.
  const handleInstallCfVersion = async (hit: CurseforgeHit, file: { url: string; filename: string }) => {
    const existing = mods.find((m) => cfModIdByName[m.name] === hit.id)
    const newMod = await api.mods.installCurseforge(instanceId, file.url, file.filename)
    if (existing && existing.name !== newMod.name) {
      await api.mods.delete(instanceId, existing.name).catch(() => {})
    }
    const withoutOld = existing ? mods.filter((m) => m.name !== existing.name) : mods
    const updatedMods = [...withoutOld.filter((m) => m.name !== newMod.name), newMod]
      .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
    setMods(updatedMods)
    delete _curseforgeCache[instanceId]
    // Même raison que dans handleCfInstall : sans ce refetch immédiat, le fichier tout juste
    // choisi dans le panneau de switch resterait affiché comme "non installé" jusqu'au
    // prochain rechargement complet.
    fetchCurseforgeInstalled(instanceId, updatedMods, mcVersion, loader, pinnedCfModIds).then((result) => {
      setCfInstalledModIds(result.installedModIds)
      setCfMatchByModName(result.matchByModName)
      setCfUpdates(result.updates)
      _curseforgeCache[instanceId] = result
    }).catch((e) => console.error('fetchCurseforgeInstalled failed', e))
  }

  /// Ouvre directement le sélecteur de version d'un mod déjà installé (liste
  /// Installés) — avant, il fallait le rechercher à nouveau dans l'onglet
  /// Parcourir pour accéder à la liste des versions. Fonctionne aussi bien pour un
  /// mod d'origine Modrinth que CurseForge (chacun ouvre son propre écran de switch).
  const handleSwitchVersion = async (mod: Mod) => {
    const projectId = versionMap[mod.sha1]?.projectId
    const cfModId = cfModIdByName[mod.name]
    if (!projectId && !cfModId) return
    setSwitchingModName(mod.name)
    try {
      if (projectId) {
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
      } else {
        const hit = await fetchCurseforgeModDetail(cfModId)
        if (!hit) { showError('Impossible de joindre CurseForge'); return }
        setCfDetailHit(hit)
      }
    } finally {
      setSwitchingModName(null)
    }
  }

  const extraUpdatesCount = allUpdates.filter((u) => u.blockedBy.length === 0 && !isPackMod(u.mod.name)).length

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* Sub-header: 3 zones — gauche/centre/droite. `overflow-x-auto` en
          filet de sécurité : sur un écran trop étroit pour les 3 zones (le
          groupe de boutons du centre ne rétrécit pas en dessous de son
          contenu), la ligne devient scrollable au lieu de clipper des
          boutons devenus inaccessibles. */}
      <div
        className="flex flex-shrink-0 items-center gap-3 overflow-x-auto px-6 py-3 border-b border-b-[rgba(255,255,255,0.05)]"
      >
        {/* Gauche : tab Installés + badge mises à jour */}
        <div className="flex flex-1 items-center gap-1 min-w-max">
          <button
            onClick={() => setTab('installed')}
            className={`rounded-lg px-4 py-1.5 text-xs font-semibold transition-all duration-150 border border-[rgba(75,63,207,0.35)] ${
              tab === 'installed'
                ? 'bg-[rgba(75,63,207,0.25)] text-[rgba(255,255,255,0.9)] border-[rgba(75,63,207,0.5)]'
                : 'bg-transparent text-[rgba(255,255,255,0.35)]'
            }`}
          >
            {t('mods.installedCount', { count: mods.length })}
          </button>
          {tab === 'installed' && extraUpdatesCount > 0 && (
            <button
              onClick={handleUpdateAll}
              disabled={updatingAll}
              title={t('mods.updateAllTitle')}
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
        <div className="flex flex-shrink-0 items-center border border-[rgba(75,63,207,0.35)] rounded-[10px] overflow-hidden">
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
            {isPlugin ? t('mods.browsePlugins') : t('mods.browseModrinth')}
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
            {modpackMeta ? t('mods.replaceModpack') : t('mods.installModpack')}
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
            {uploading ? t('mods.importing') : t('mods.import')}
          </button>
        </div>

        {/* Droite : informations de l'instance — min-w-0 + truncate pour que
            le nom d'instance cède la place aux boutons plutôt que de forcer
            un débordement (voir overflow-x-auto ci-dessus). */}
        <div className="flex min-w-0 flex-1 items-center justify-end gap-3">
          {importNotice && (
            <span className="flex-shrink-0 text-[10.5px] text-[rgba(179,163,255,0.9)]">{importNotice}</span>
          )}
          <span className="truncate text-[11px] text-[rgba(255,255,255,0.25)]">
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
          onPickModpack={handleImportModpackFile}
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

      {cfDetailHit && (() => {
        const installedMod = mods.find((m) => cfModIdByName[m.name] === cfDetailHit.id) ?? null
        return (
          <CurseforgeDetailModal
            hit={cfDetailHit}
            instanceId={instanceId}
            mcVersion={mcVersion}
            loader={loader}
            installedMod={installedMod}
            installedFileId={installedMod ? cfMatchByModName[installedMod.name]?.fileId ?? null : null}
            onClose={() => setCfDetailHit(null)}
            onInstall={(file) => handleInstallCfVersion(cfDetailHit, file)}
          />
        )
      })()}

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-6 py-3">
        {modpackMeta && (
          <ModpackBanner
            meta={modpackMeta}
            menuOpen={modpackMenuOpen}
            showPackContent={showPackContent}
            packVersionUpdate={packVersionUpdate}
            updatingPackVersion={updatingPackVersion}
            onToggleMenu={() => setModpackMenuOpen((v) => !v)}
            onReplace={handleReplaceModpack}
            onRemove={handleRemoveModpack}
            onToggleShowContent={handleToggleShowPackContent}
            onUpdatePackVersion={handleUpdatePackVersion}
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
            cfModIdByName={cfModIdByName}
            cfVersionByName={cfVersionByName}
            updates={allUpdates}
            updatingMods={updatingMods}
            updatingAll={updatingAll}
            onReload={loadMods}
            onToggle={handleToggle}
            onDelete={handleDelete}
            onUpdateMod={handleUpdateMod}
            onSwitchVersion={handleSwitchVersion}
            switchingModName={switchingModName}
            onBrowseExtra={() => setTab('modpack')}
            onUploadExtra={handlePickJars}
          />
        ) : tab === 'browse' ? (
          <BrowseTab
            query={query}
            results={mergedResults}
            searching={searching || cfSearching}
            isPlugin={isPlugin}
            filters={searchFilters}
            onQueryChange={handleQueryChange}
            onFiltersChange={handleFiltersChange}
            installingModrinth={installing}
            installProgressModrinth={installProgress}
            isInstalledModrinth={(hit) => !!installedByProject[hit.project_id]}
            onInstallModrinth={handleInstall}
            onOpenDetailModrinth={setDetailHit}
            installingCurseforge={cfInstalling}
            installProgressCurseforge={cfInstallProgress}
            isInstalledCurseforge={(hit) => cfInstalledModIds.has(hit.id)}
            onInstallCurseforge={handleCfInstall}
            onOpenDetailCurseforge={setCfDetailHit}
          />
        ) : (
          <div className="flex flex-col gap-3">
            {packImportingFile && packInstallProgress && (
              <div className="flex flex-col gap-1.5 rounded-2xl px-4 py-3 bg-[rgba(75,63,207,0.1)] border border-[rgba(75,63,207,0.3)]">
                <p className="text-[12px] font-semibold text-white">{t('mods.importingLocalModpack')}</p>
                <div className="flex items-center gap-2">
                  <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
                    <div
                      className="h-full rounded-full bg-[#4B3FCF] transition-all duration-200"
                      style={{ width: `${packInstallProgress.percent}%` }}
                    />
                  </div>
                  <span className="max-w-[160px] flex-shrink-0 truncate text-[10.5px] text-[rgba(255,255,255,0.5)]">
                    {packInstallProgress.label}
                  </span>
                </div>
              </div>
            )}
            <ModpackBrowseTab
              query={packQuery}
              results={packResults}
              searching={packSearching}
              installing={packInstalling}
              installProgress={packInstallProgress}
              filters={packFilters}
              onQueryChange={handlePackQueryChange}
              onInstall={handleInstallModpack}
              onFiltersChange={handlePackFiltersChange}
            />
          </div>
        )}
      </div>
    </div>
  )
}

// ── Standalone page (kept for /mods route) ────────────────────────────────────

export default function Mods() {
  const navigate = useNavigate()
  const t = useT()
  const { selectedInstance } = useStore()
  const instance = selectedInstance()

  if (!instance) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-[#09090D] text-white">
        <div className="text-[36px]">🧱</div>
        <p className="text-[14px] text-[rgba(255,255,255,0.4)] font-semibold">{t('mods.noInstanceSelected')}</p>
        <button
          onClick={() => navigate('/instances')}
          className="font-semibold transition-all duration-200 active:scale-95 h-[38px] px-5 rounded-[10px] text-[13px] bg-[#4B3FCF] text-white"
        >
          {t('mods.manageInstances')}
        </button>
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col bg-[#09090D] text-white">
      <PageHeader>
        <h1 className="font-black text-white text-[18px] tracking-[-0.01em]">{t('mods.pageTitle')}</h1>
      </PageHeader>
      <ModsContent instance={instance} />
    </div>
  )
}
