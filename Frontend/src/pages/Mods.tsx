import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { press, pressIf } from '@/lib/motion'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { open } from '@tauri-apps/plugin-dialog'
import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import type { ModConflict } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { formatBytes } from '@/lib/format'
import type { Instance, Mod, ModInstallProgress, ModpackInstallProgress, ModpackMeta } from '@/types'
import { searchModrinthModpacks, resolveModpackFile, type ModpackHit, type ResolvedModpackFile } from '@/lib/modrinthModpacks'
import { searchCurseforgeModpacks, resolveCurseforgeModpackFile, type CurseforgeModpackHit } from '@/lib/curseforgeModpacks'
import { ImportSourceModal } from '@/components/import/ImportSourceModal'
import { ImportChoiceModal } from '@/components/import/ImportChoiceModal'
import { InstalledTab } from '@/components/mods/InstalledTab'
import { ModpackBanner } from '@/components/mods/ModpackBanner'
import { ConflictBanner } from '@/components/mods/ConflictBanner'
import { ModpackBrowseTab, type MergedModpackHit } from '@/components/mods/ModpackBrowseTab'
import { BrowseTab, type MergedHit } from '@/components/mods/BrowseTab'
import { ToolbarMenu } from '@/components/mods/ToolbarMenu'
import { PacksTab } from '@/components/mods/PacksTab'
import { OptionsTab } from '@/components/mods/OptionsTab'
import {
  fetchCurseforgeSearch, fetchCurseforgeFiles, findFileForLoader, fetchCurseforgeInstalled, fetchCurseforgeModDetail,
  _curseforgeCache, type CurseforgeHit, type CurseforgeMatch,
} from '@/components/mods/curseforgeUtils'
import { ModDetailModal } from '@/components/mods/ModDetailModal'
import { CurseforgeDetailModal } from '@/components/mods/CurseforgeDetailModal'
import { ModpackDetailModal } from '@/components/mods/ModpackDetailModal'
import { PageHeader } from '@/components/ui/PageHeader'
import { showError, showApiError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import {
  displayName, baseFilename, fetchVersionsByHash, checkForUpdates, fetchModrinthSearch, fetchLatestVersion,
  fetchProjectDetail, _modrinthCache, _iconCache,
  type ModrinthInfo, type ModUpdate, type ModrinthHit, type ModrinthSearchFilters, type Tab,
} from '@/components/mods/modUtils'

import { useSearchRunner } from '@/hooks/useSearchRunner'

export { updateModsForNewVersion } from '@/components/mods/modUtils'

/** Chemins SVG 24×24 de la barre d'outils, rassemblés ici plutôt que recopiés
 *  dans chaque entrée de menu — les mêmes servent au bouton et à son menu. */
const TOOLBAR_ICON = {
  mods: 'M21 16.5c0 .38-.21.71-.53.88l-7.9 4.44c-.16.12-.36.18-.57.18s-.41-.06-.57-.18l-7.9-4.44A1 1 0 013 16.5v-9c0-.38.21-.71.53-.88l7.9-4.44c.16-.12.36-.18.57-.18s.41.06.57.18l7.9 4.44c.32.17.53.5.53.88v9z',
  search: 'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  modpack: 'M12 2L1 9l11 7 9-5.73V17h2V9L12 2zM3 13.18v4.91L12 23l9-4.91v-4.91l-9 5.73-9-5.73z',
  list: 'M3 5h18v2H3V5zm0 6h18v2H3v-2zm0 6h12v2H3v-2z',
  grid: 'M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z',
  star: 'M12 3l2.09 6.26L20.5 9.5l-5 3.8 1.9 6.2L12 15.8 6.6 19.5l1.9-6.2-5-3.8 6.41-.24L12 3z',
  sliders: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
} as const

// ── Recherche — voir hooks/useSearchRunner.ts ─────────────────────────────────

const SEARCH_DEBOUNCE_MS = 450

interface SearchParams {
  q: string
  filters: ModrinthSearchFilters
  mcVersion: string
  loader: string
}

/// Deux recherches de même clé renvoient les mêmes résultats : texte sans espaces
/// autour ni casse, catégories triées.
function searchKey({ q, filters, mcVersion, loader }: SearchParams, withFilters: boolean): string {
  const base = [q.trim().toLowerCase(), mcVersion, loader]
  if (!withFilters) return JSON.stringify(base)
  return JSON.stringify([
    ...base,
    [...(filters.categories ?? [])].sort(),
    filters.environment ?? '',
    filters.license?.trim().toLowerCase() ?? '',
    !!filters.openSourceOnly,
    filters.sort ?? 'relevance',
  ])
}

/// Le champ licence est le seul filtre tapé au clavier : lui seul attend la fin de
/// la frappe, les clics (catégories, environnement, tri, réinitialisation) partent
/// tout de suite.
function onlyLicenseChanged(prev: ModrinthSearchFilters, next: ModrinthSearchFilters): boolean {
  return prev.license !== next.license
    && prev.categories === next.categories
    && prev.environment === next.environment
    && prev.openSourceOnly === next.openSourceOnly
    && prev.sort === next.sort
}

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
  /** Nombre de packs installés, remonté par `PacksTab` pour le compteur du
   *  menu. Gardé ici et non dans l'onglet : le menu doit l'afficher même
   *  quand on est sur un autre écran. */
  const [packCount, setPackCount] = useState(0)

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

  const modrinthSearch = useSearchRunner<SearchParams, ModrinthHit[]>({
    name: 'modrinth-mods',
    keyOf: (p) => searchKey(p, true),
    fetch: (p) => fetchModrinthSearch(p.q.trim(), p.mcVersion, p.loader, p.filters),
    onResult: setResults,
    onError: (e) => {
      console.error('[Mods] recherche Modrinth :', e)
      showError(t('mods.cannotReachModrinth'))
    },
    onBusyChange: setSearching,
  })

  // CurseForge ne reçoit pas les filtres Modrinth : sa clé les ignore, un
  // changement de filtre ne le relance donc pas.
  const curseforgeSearch = useSearchRunner<SearchParams, CurseforgeHit[]>({
    name: 'curseforge-mods',
    keyOf: (p) => searchKey(p, false),
    fetch: (p) => fetchCurseforgeSearch(p.q.trim(), p.mcVersion, p.loader),
    onResult: setCfResults,
    onError: (e) => showApiError(e, t('common.serverUnreachable')),
    onBusyChange: setCfSearching,
  })

  /// Une seule barre de recherche interroge les deux sources — voir mergedResults
  /// pour la fusion. Un seul événement analytics, et seulement si une requête part.
  const commitModSearch = (q: string, filters: ModrinthSearchFilters) => {
    const params: SearchParams = { q, filters, mcVersion, loader }
    const modrinthLaunched = modrinthSearch.request(params)
    const curseforgeLaunched = curseforgeSearch.request(params)
    if ((modrinthLaunched || curseforgeLaunched) && q.trim()) {
      api.analytics.track('mod_search_performed', { query: q.trim() })
    }
  }

  /// Classe un hit par qualité de correspondance avec le texte tapé (0 =
  /// meilleur). Calculé côté client car ni Modrinth ni CurseForge n'exposent
  /// un score de pertinence comparable entre les deux API — c'est le seul
  /// critère qu'on peut appliquer uniformément aux deux sources.
  const matchTier = (name: string, q: string): number => {
    const query = q.trim().toLowerCase()
    if (!query) return 0
    const n = name.trim().toLowerCase()
    if (n === query) return 0
    if (n.startsWith(query)) return 1
    if (n.includes(query)) return 2
    return 3
  }

  /// Fusionne les deux sources de recherche par pertinence plutôt que par simple
  /// concaténation (Modrinth-puis-CurseForge) — sinon un mod CurseForge très pertinent
  /// pour la requête (Forge, où CurseForge concentre le plus de mods) atterrissait après
  /// TOUS les résultats Modrinth, même les moins pertinents. Tri en 2 temps : d'abord
  /// `matchTier` (qualité du nom vs texte tapé), puis les téléchargements en repli — seul
  /// terrain d'entente numérique entre les deux API. Uniquement en mode "pertinence"
  /// (par défaut) : un tri explicite (téléchargements, mis à jour...) reste géré par
  /// chaque API elle-même, pas re-mélangé ici.
  /// Un mod CurseForge dont le nom correspond (insensible à la casse) à un résultat
  /// Modrinth déjà présent est retiré : on garde uniquement la version Modrinth (a une
  /// preview, des métadonnées plus riches), jamais les deux pour le même mod.
  const mergedResults = useMemo<MergedHit[]>(() => {
    const modrinthNames = new Set(results.map((r) => r.title.trim().toLowerCase()))
    const cfDeduped = cfResults.filter((r) => !modrinthNames.has(r.name.trim().toLowerCase()))
    const merged: MergedHit[] = [
      ...results.map((hit): MergedHit => ({ source: 'modrinth', hit })),
      ...cfDeduped.map((hit): MergedHit => ({ source: 'curseforge', hit })),
    ]

    const isRelevanceSort = !searchFilters.sort || searchFilters.sort === 'relevance'
    if (!isRelevanceSort) return merged

    return merged.sort((a, b) => {
      const nameA = a.source === 'modrinth' ? a.hit.title : a.hit.name
      const nameB = b.source === 'modrinth' ? b.hit.title : b.hit.name
      const tierDiff = matchTier(nameA, query) - matchTier(nameB, query)
      if (tierDiff !== 0) return tierDiff
      const downloadsA = a.source === 'modrinth' ? a.hit.downloads : a.hit.downloadCount
      const downloadsB = b.source === 'modrinth' ? b.hit.downloads : b.hit.downloadCount
      return downloadsB - downloadsA
    })
  }, [results, cfResults, query, searchFilters.sort])

  const pinnedCfModIds = useMemo(() => {
    const prefix = `${instanceId}:cf:`
    return new Set(
      Object.keys(pinnedMods)
        .filter((k) => k.startsWith(prefix))
        .map((k) => Number(k.slice(prefix.length))),
    )
  }, [pinnedMods, instanceId])

  // Incompatibilités déjà installées. Relues à chaque changement de `mods` :
  // installer, supprimer, activer ou désactiver un mod peut en créer ou en
  // résoudre une, et c'est justement à ce moment-là qu'il faut le dire.
  const [conflicts, setConflicts] = useState<ModConflict[]>([])
  useEffect(() => {
    if (!instanceId || mods.length === 0) {
      setConflicts([])
      return
    }
    let cancelled = false
    api.mods
      .checkConflicts(instanceId, mcVersion, loader)
      .then((c) => { if (!cancelled) setConflicts(c) })
      // Silencieux : une vérification qui échoue ne doit pas empêcher de
      // gérer ses mods, elle n'a rien à dire de plus qu'avant.
      .catch(() => { if (!cancelled) setConflicts([]) })
    return () => { cancelled = true }
  }, [instanceId, mods, mcVersion, loader])

  const [modSearch, setModSearch] = useState('')
  const [logoCache, setLogoCache] = useState<Record<string, string | null>>({})
  const [detailHit, setDetailHit] = useState<ModrinthHit | null>(null)
  const [cfDetailHit, setCfDetailHit] = useState<CurseforgeHit | null>(null)
  const [packDetailHit, setPackDetailHit] = useState<MergedModpackHit | null>(null)
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

  // CurseForge — même principe que cfResults/cfSearching pour les mods (voir
  // plus haut) : une seule barre de recherche déclenche les deux sources,
  // fusionnées dans mergedPackResults ci-dessous.
  const [cfPackResults, setCfPackResults] = useState<CurseforgeModpackHit[]>([])
  const [cfPackSearching, setCfPackSearching] = useState(false)
  const [cfPackInstalling, setCfPackInstalling] = useState<number | null>(null)
  const [cfPackInstallProgress, setCfPackInstallProgress] = useState<{ percent: number; label: string } | null>(null)

  const mergedPackResults = useMemo<MergedModpackHit[]>(() => [
    ...packResults.map((hit): MergedModpackHit => ({ source: 'modrinth', hit })),
    ...cfPackResults.map((hit): MergedModpackHit => ({ source: 'curseforge', hit })),
  ], [packResults, cfPackResults])

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
    resolveModpackFile(modpackMeta.project_id, mcVersion, loader).then((file) => {
      if (!cancelled && file && file.versionId !== modpackMeta.version_id) {
        setPackVersionUpdate(file)
      }
    }).catch(() => {})
    return () => { cancelled = true }
  }, [modpackMeta?.project_id, modpackMeta?.version_id])

  const packFileSet = new Set((modpackMeta?.mod_files ?? []).map((f) => f.toLowerCase()))
  const isPackMod = (name: string) => packFileSet.has(baseFilename(name).toLowerCase())

  const modrinthPackSearch = useSearchRunner<SearchParams, ModpackHit[]>({
    name: 'modrinth-modpacks',
    keyOf: (p) => searchKey(p, true),
    fetch: (p) => searchModrinthModpacks(p.q.trim(), p.mcVersion, p.loader, p.filters),
    onResult: setPackResults,
    onError: (e) => {
      console.error('[Mods] recherche de modpacks Modrinth :', e)
      showError(t('mods.cannotReachModrinth'))
    },
    onBusyChange: setPackSearching,
  })

  const curseforgePackSearch = useSearchRunner<SearchParams, CurseforgeModpackHit[]>({
    name: 'curseforge-modpacks',
    keyOf: (p) => searchKey(p, false),
    fetch: (p) => searchCurseforgeModpacks(p.q.trim(), p.mcVersion, p.loader),
    onResult: setCfPackResults,
    onError: (e) => showApiError(e, t('common.serverUnreachable')),
    onBusyChange: setCfPackSearching,
  })

  const commitPackSearch = (q: string, filters: ModrinthSearchFilters) => {
    const params: SearchParams = { q, filters, mcVersion, loader }
    modrinthPackSearch.request(params)
    curseforgePackSearch.request(params)
  }

  const handlePackQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value
    setPackQuery(q)
    if (packDebounceRef.current) clearTimeout(packDebounceRef.current)
    packDebounceRef.current = setTimeout(() => commitPackSearch(q, packFilters), SEARCH_DEBOUNCE_MS)
  }

  const handlePackFiltersChange = (filters: ModrinthSearchFilters) => {
    const debounce = onlyLicenseChanged(packFilters, filters)
    setPackFilters(filters)
    if (packDebounceRef.current) clearTimeout(packDebounceRef.current)
    if (debounce) packDebounceRef.current = setTimeout(() => commitPackSearch(packQuery, filters), SEARCH_DEBOUNCE_MS)
    else commitPackSearch(packQuery, filters)
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
      const file = await resolveModpackFile(hit.project_id, mcVersion, loader)
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

  const handleInstallCfModpack = async (hit: CurseforgeModpackHit) => {
    setCfPackInstalling(hit.id)
    setCfPackInstallProgress({ percent: 0, label: t('mods.downloadingModpack') })
    const unlisten = await listen<ModpackInstallProgress>('modpack_install_progress', (e) => {
      const { current, total, label } = e.payload
      const percent = total > 0 ? Math.min(100, Math.round((current / total) * 100)) : 0
      setCfPackInstallProgress({ percent, label: `${label} (${current}/${total})` })
    })
    try {
      const file = await resolveCurseforgeModpackFile(hit.id, mcVersion)
      if (!file) throw new Error(t('mods.noMrpackAvailable'))
      const meta = await api.modpacks.installCurseforge({
        instanceId,
        fileUrl: file.url,
        modId: hit.id,
        fileId: file.fileId,
        name: hit.name,
        author: hit.author,
        summary: hit.summary,
        iconUrl: hit.logoUrl,
        versionNumber: file.displayName,
        downloads: hit.downloadCount,
        dateModified: hit.dateModified,
        categories: [],
      })
      setModpackMeta(meta)
      setTab('installed')
      delete _modrinthCache[instanceId]
      delete _curseforgeCache[instanceId]
      await loadMods()
    } catch (e) {
      showError(e)
    } finally {
      unlisten()
      setCfPackInstalling(null)
      setCfPackInstallProgress(null)
    }
  }

  const handleImportModpackFile = async () => {
    // Accepte aussi bien un .mrpack Modrinth qu'un .zip CurseForge (manifest.json
    // + overrides/) ou n'importe quelle autre structure de pack — détectée
    // automatiquement côté backend (voir modpack_install_from_path).
    const picked = await open({ filters: [{ name: 'Modpack', extensions: ['mrpack', 'zip'] }] })
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
      const result = await api.modpacks.installFromPath(instanceId, picked)
      delete _modrinthCache[instanceId]
      delete _curseforgeCache[instanceId]
      if (result.kind === 'structured') {
        setModpackMeta(result.meta)
      } else {
        // Structure non reconnue (Modrinth/CurseForge) : pack "générique"
        // extrait tel quel, pas de bannière modpack ni de projet à suivre.
        setImportNotice(t('mods.genericPackImported', { count: result.imported }))
      }
      setTab('installed')
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

  /// Repli quand l'extraction locale (effet ci-dessus) ne trouve aucune icône
  /// dans le jar lui-même — certains mods (ex: FTB Essentials) n'embarquent
  /// tout simplement pas d'icône dans leur jar, ni `icon` dans fabric.mod.json/
  /// `logoFile` dans mods.toml, ni pack.png : ce n'est pas un bug d'extraction,
  /// il n'y a rien à extraire. Ces mods ont pourtant une icône en recherche
  /// (hébergée par Modrinth/CurseForge, indépendante du jar) — on la réutilise
  /// ici via l'identité déjà résolue ailleurs dans ce composant (sha1 →
  /// Modrinth `versionMap`, nom → CurseForge `cfModIdByName`). `logoCache[key]
  /// === null` cible précisément les mods dont l'extraction locale a échoué
  /// (`undefined` = pas encore vérifié, à ignorer ici). `logoCache` volontairement
  /// absent des dépendances : cet effet l'écrit lui-même, l'y ajouter boucle.
  useEffect(() => {
    if (mods.length === 0) return
    const toResolve = mods.filter((mod) => {
      const key = displayName(mod.name)
      return logoCache[key] === null && (versionMap[mod.sha1]?.projectId || cfModIdByName[mod.name])
    })
    if (toResolve.length === 0) return

    toResolve.forEach(async (mod) => {
      const key = displayName(mod.name)
      const cacheKey = `${instanceId}/${key}`
      let iconUrl: string | null = null
      const projectId = versionMap[mod.sha1]?.projectId
      if (projectId) {
        const detail = await fetchProjectDetail(projectId)
        iconUrl = detail?.icon_url ?? null
      }
      if (!iconUrl) {
        const cfModId = cfModIdByName[mod.name]
        if (cfModId) {
          const detail = await fetchCurseforgeModDetail(cfModId)
          iconUrl = detail?.logoUrl ?? null
        }
      }
      if (iconUrl) {
        _iconCache[cacheKey] = iconUrl
        setLogoCache((prev) => ({ ...prev, [key]: iconUrl }))
      }
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mods, versionMap, cfModIdByName])

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
          checkForUpdates(instanceId, loaded, vd, mcVersion, loader, avoidBetaDependencies).then((upd) => {
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
        fetchCurseforgeInstalled(instanceId, loaded, mcVersion, loader).then((result) => {
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

  // Ouvrir un onglet affiche la recherche en cours — un aller-retour d'onglet
  // redemande la même clé, que les runners ignorent (y compris sur résultat vide).
  useEffect(() => {
    if (tab === 'browse') commitModSearch(query, searchFilters)
    if (tab === 'modpack') commitPackSearch(packQuery, packFilters)
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
      // `_curseforgeCache` n'est qu'un cache pour le PROCHAIN loadMods() — sans
      // mise à jour immédiate de `cfInstalledModIds`/`cfMatchByModName`
      // (contrairement à `installedByProject`, un useMemo dérivé de `mods` en
      // direct), le badge "installé" CurseForge restait affiché en recherche
      // jusqu'à ce qu'un autre install refasse tourner fetchCurseforgeInstalled
      // en entier. Modrinth n'a pas ce souci, son état "installé" recalcule
      // toujours en direct depuis `mods`.
      setCfMatchByModName((prev) => {
        const match = prev[name]
        if (!match) return prev
        setCfInstalledModIds((ids) => {
          const next = new Set(ids)
          next.delete(match.modId)
          return next
        })
        const { [name]: _removed, ...rest } = prev
        return rest
      })
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
  //
  // Les mods épinglés (« ignorer les mises à jour ») sont retirés ICI, et non
  // au calcul : la liste calculée est mise en cache par instance, alors que
  // l'épinglage se change à tout moment. En filtrant à l'affichage, cocher
  // ou décocher l'interrupteur se voit immédiatement, dans les deux sens,
  // sans rien avoir à recalculer ni à invalider.
  const allUpdates = useMemo(
    () =>
      [...updates, ...cfUpdates].filter((u) => {
        const projectId = versionMap[u.mod.sha1]?.projectId
        if (projectId && pinnedProjectIds.has(projectId)) return false
        const cfModId = cfMatchByModName[u.mod.name]?.modId
        if (cfModId !== undefined && pinnedCfModIds.has(cfModId)) return false
        return true
      }),
    [updates, cfUpdates, versionMap, cfMatchByModName, pinnedProjectIds, pinnedCfModIds],
  )

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

  /** Importe des archives locales vers `resourcepacks/` ou `shaderpacks/`.
   *  La famille est demandée dans la fenêtre d'import plutôt que devinée :
   *  les deux sont des `.zip` et rien ne les distingue de façon fiable. */
  const handlePickPacks = async (kind: 'resourcepack' | 'shader') => {
    if (uploading) return
    const picked = await open({ multiple: true, filters: [{ name: 'Pack', extensions: ['zip'] }] })
    if (!picked) return
    const paths = Array.isArray(picked) ? picked : [picked]
    if (paths.length === 0) return

    setUploading(true)
    setImportNotice('')
    try {
      const added = await api.packs.importPaths(instanceId, kind, paths)
      setImportNotice(t('mods.packsImported', { count: added.length }))
      // On atterrit sur la liste : sans ça, l'import serait invisible depuis
      // l'écran où l'on se trouvait.
      setTab('packs-installed')
    } catch (e) {
      showError(e)
    } finally {
      setUploading(false)
    }
  }

  /** Applique le modèle `shared_options.txt` à cette instance (voir
   *  `instance_apply_settings`). Rend `false` quand aucun modèle n'a encore
   *  été enregistré depuis une autre instance. */
  const handleImportOptions = async () => {
    try {
      const applied = await api.instances.applySettings(instanceId)
      setImportNotice(t(applied ? 'mods.optionsApplied' : 'mods.optionsNoTemplate'))
      if (applied) setTab('options')
    } catch (e) {
      showError(e)
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

  const handleQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value
    setQuery(q)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => commitModSearch(q, searchFilters), SEARCH_DEBOUNCE_MS)
  }

  const handleFiltersChange = (filters: ModrinthSearchFilters) => {
    const debounce = onlyLicenseChanged(searchFilters, filters)
    setSearchFilters(filters)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    if (debounce) debounceRef.current = setTimeout(() => commitModSearch(query, filters), SEARCH_DEBOUNCE_MS)
    else commitModSearch(query, filters)
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
      fetchCurseforgeInstalled(instanceId, updatedMods, mcVersion, loader).then((result) => {
        setCfInstalledModIds(result.installedModIds)
        setCfMatchByModName(result.matchByModName)
        setCfUpdates(result.updates)
        _curseforgeCache[instanceId] = result
      }).catch((e) => {
        console.error('fetchCurseforgeInstalled failed', e)
        showApiError(e, t('common.serverUnreachable'))
      })
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
    fetchCurseforgeInstalled(instanceId, updatedMods, mcVersion, loader).then((result) => {
      setCfInstalledModIds(result.installedModIds)
      setCfMatchByModName(result.matchByModName)
      setCfUpdates(result.updates)
      _curseforgeCache[instanceId] = result
    }).catch((e) => {
      console.error('fetchCurseforgeInstalled failed', e)
      showApiError(e, t('common.serverUnreachable'))
    })
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
        {/* Gauche : import + badge mises à jour.
            Ce bouton remplace l'ancien onglet « Installés », qui a rejoint le
            menu « Mods » au centre : tout ce qui entre dans l'instance depuis
            le disque part désormais d'un seul endroit. */}
        <div className="flex flex-1 items-center gap-1 min-w-max">
          <motion.button {...pressIf(!uploading)}
            onClick={() => setShowImportChoice(true)}
            disabled={uploading}
            className={`flex items-center gap-1.5 rounded-lg border border-[rgba(75,63,207,0.35)] px-3.5 py-1.5 text-xs font-semibold transition-colors duration-150 ${
              uploading
                ? 'cursor-not-allowed bg-[rgba(40,38,65,0.7)] text-[rgba(255,255,255,0.3)]'
                : 'bg-[rgba(75,63,207,0.25)] text-[rgba(255,255,255,0.85)] hover:bg-[rgba(75,63,207,0.4)]'
            }`}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
              <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
            </svg>
            {uploading ? t('mods.importing') : t('mods.import')}
          </motion.button>
          {tab === 'installed' && extraUpdatesCount > 0 && (
            <motion.button {...pressIf(!(updatingAll))}
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
            </motion.button>
          )}
        </div>

        {/* Centre : trois familles, chacune ouvrant ses écrans.
            Sept boutons à plat ne se liraient pas ; le libellé dit la famille,
            le menu dit lequel de ses écrans est ouvert. */}
        <div className="flex flex-shrink-0 items-center divide-x divide-[rgba(75,63,207,0.35)] overflow-visible rounded-[10px] border border-[rgba(75,63,207,0.35)]">
          <ToolbarMenu
            label={t('mods.menuMods')}
            icon={TOOLBAR_ICON.mods}
            active={tab === 'installed' || tab === 'browse' || tab === 'modpack'}
            activeId={tab}
            items={[
              { id: 'browse', icon: TOOLBAR_ICON.search, label: isPlugin ? t('mods.browsePlugins') : t('mods.browseModrinth'), onSelect: () => setTab('browse') },
              { id: 'modpack', icon: TOOLBAR_ICON.modpack, label: modpackMeta ? t('mods.replaceModpack') : t('mods.installModpack'), onSelect: () => setTab('modpack') },
              { id: 'installed', icon: TOOLBAR_ICON.list, label: t('mods.menuInstalledMods'), badge: mods.length, onSelect: () => setTab('installed') },
            ]}
          />
          <ToolbarMenu
            label={t('mods.menuPacks')}
            icon={TOOLBAR_ICON.grid}
            active={tab.startsWith('packs-')}
            activeId={tab}
            items={[
              { id: 'packs-shader', icon: TOOLBAR_ICON.star, label: t('mods.menuBrowseShaders'), onSelect: () => setTab('packs-shader') },
              { id: 'packs-resourcepack', icon: TOOLBAR_ICON.grid, label: t('mods.menuBrowseResourcepacks'), onSelect: () => setTab('packs-resourcepack') },
              { id: 'packs-installed', icon: TOOLBAR_ICON.list, label: t('mods.menuInstalledPacks'), badge: packCount, onSelect: () => setTab('packs-installed') },
            ]}
          />
          {/* Pas de menu ici : les réglages Minecraft et ceux de YuyuFrame
              sont deux sections du même écran, pas deux destinations. Un
              menu à deux entrées menant au même endroit promettrait un choix
              qui n'existe pas. */}
          <motion.button {...press}
            onClick={() => setTab('options')}
            className={`flex h-8 items-center gap-1.5 px-[14px] text-[12px] font-semibold transition-colors duration-150 cursor-pointer ${
              tab === 'options'
                ? 'bg-[rgba(75,63,207,0.25)] text-[rgba(255,255,255,0.9)]'
                : 'bg-transparent text-[rgba(255,255,255,0.55)] hover:bg-[rgba(75,63,207,0.12)]'
            }`}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="flex-shrink-0">
              <path d={TOOLBAR_ICON.sliders} />
            </svg>
            {t('mods.menuOptions')}
          </motion.button>
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
          onPickPacks={handlePickPacks}
          onPickOptions={handleImportOptions}
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

      {packDetailHit && (
        <ModpackDetailModal
          hit={packDetailHit}
          mcVersion={mcVersion}
          loader={loader}
          installing={
            packDetailHit.source === 'modrinth'
              ? packInstalling === packDetailHit.hit.project_id
              : cfPackInstalling === packDetailHit.hit.id
          }
          installProgress={packDetailHit.source === 'modrinth' ? packInstallProgress : cfPackInstallProgress}
          onClose={() => setPackDetailHit(null)}
          onInstall={async () => {
            if (packDetailHit.source === 'modrinth') {
              await handleInstallModpack(packDetailHit.hit)
            } else {
              await handleInstallCfModpack(packDetailHit.hit)
            }
            setPackDetailHit(null)
          }}
        />
      )}

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

        {tab === 'installed' && <ConflictBanner conflicts={conflicts} />}

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
            pinnedProjectIds={pinnedProjectIds}
            pinnedCfModIds={pinnedCfModIds}
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
        ) : tab === 'options' ? (
          <OptionsTab instance={instance} />
        ) : tab.startsWith('packs-') ? (
          <PacksTab
            instanceId={instanceId}
            mcVersion={mcVersion}
            mode={tab === 'packs-installed' ? 'installed' : tab === 'packs-shader' ? 'shader' : 'resourcepack'}
            onInstalledCountChange={setPackCount}
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
              results={mergedPackResults}
              searching={packSearching || cfPackSearching}
              installing={packInstalling}
              installProgress={packInstallProgress}
              cfInstalling={cfPackInstalling}
              cfInstallProgress={cfPackInstallProgress}
              filters={packFilters}
              onQueryChange={handlePackQueryChange}
              onInstall={handleInstallModpack}
              onInstallCurseforge={handleInstallCfModpack}
              onFiltersChange={handlePackFiltersChange}
              onOpenDetail={setPackDetailHit}
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
        <motion.button {...press}
          onClick={() => navigate('/instances')}
          className="font-semibold transition-all duration-200 active:scale-95 h-[38px] px-5 rounded-[10px] text-[13px] bg-[#4B3FCF] text-white"
        >
          {t('mods.manageInstances')}
        </motion.button>
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
