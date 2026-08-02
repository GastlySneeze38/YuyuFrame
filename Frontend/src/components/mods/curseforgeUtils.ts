import { api } from '@/api/client'
import type { Mod } from '@/types'
import type { ModUpdate } from './modUtils'

// Fichier séparé de modUtils.ts (Modrinth) volontairement — les deux sources
// ne sont pas encore fusionnées dans une UI unique (prévu une fois le flux
// CurseForge validé bout en bout).

export interface CurseforgeHit {
  id: number
  slug: string
  name: string
  summary: string
  logoUrl: string | null
  downloadCount: number
}

export interface CurseforgeFile {
  id: number
  fileName: string
  displayName: string
  /// `null` quand l'auteur a désactivé la distribution via l'API tierce —
  /// dans ce cas le fichier n'est pas installable depuis le launcher.
  downloadUrl: string | null
  gameVersions: string[]
  /// CurseForge : 1=release, 2=beta, 3=alpha.
  releaseType: number
  fileLength: number
}

export interface CurseforgeCategory {
  id: number
  name: string
  slug: string
}

export interface CurseforgeSearchFilters {
  categoryId?: number
  sort?: 'relevance' | 'downloads' | 'updated' | 'name'
}

interface CurseforgeApiMod {
  id: number
  slug: string
  name: string
  summary: string
  logo?: { url?: string } | null
  downloadCount: number
}

interface CurseforgeApiFile {
  id: number
  fileName: string
  displayName?: string
  downloadUrl: string | null
  gameVersions: string[]
  fileFingerprint?: number
  releaseType?: number
  fileLength?: number
}

interface CurseforgeApiCategory {
  id: number
  name: string
  slug: string
}

/// classId CurseForge pour "Minecraft Mods" — recherche uniquement les mods
/// pour l'instant (pas les resource packs/plugins Bukkit, gérés plus tard).
const CLASS_ID_MODS = '6'

/// Champs de tri CurseForge (voir `/v1/mods/search`) — 'relevance' n'envoie
/// rien (comportement par défaut de l'API, équivalent à "Featured").
const SORT_FIELD: Record<string, string> = {
  downloads: '6',
  updated: '3',
  name: '4',
}

export async function fetchCurseforgeSearch(
  query: string,
  gameVersion: string,
  filters: CurseforgeSearchFilters = {},
): Promise<CurseforgeHit[]> {
  if (!query.trim()) return []
  const data = (await api.curseforge.search(query, {
    gameVersion: gameVersion || undefined,
    classId: CLASS_ID_MODS,
    pageSize: 20,
    categoryId: filters.categoryId ? String(filters.categoryId) : undefined,
    sortField: filters.sort ? SORT_FIELD[filters.sort] : undefined,
  })) as { data?: CurseforgeApiMod[] }

  return (data?.data ?? []).map(toCurseforgeHit)
}

function toCurseforgeHit(m: CurseforgeApiMod): CurseforgeHit {
  return {
    id: m.id,
    slug: m.slug,
    name: m.name,
    summary: m.summary,
    logoUrl: m.logo?.url ?? null,
    downloadCount: m.downloadCount,
  }
}

/// Détails d'un mod par id — utilisé pour l'écran de switch de version (l'utilisateur clique
/// depuis la liste "Installés", on n'a pas déjà le `CurseforgeHit` sous la main comme c'est le
/// cas depuis un résultat de recherche).
export async function fetchCurseforgeModDetail(modId: number): Promise<CurseforgeHit | null> {
  try {
    const data = (await api.curseforge.modDetails(modId)) as { data?: CurseforgeApiMod }
    return data?.data ? toCurseforgeHit(data.data) : null
  } catch {
    return null
  }
}

export async function fetchCurseforgeCategories(): Promise<CurseforgeCategory[]> {
  try {
    const data = (await api.curseforge.categories()) as { data?: CurseforgeApiCategory[] }
    return (data?.data ?? []).map((c) => ({ id: c.id, name: c.name, slug: c.slug }))
  } catch {
    return []
  }
}

function toCurseforgeFile(f: CurseforgeApiFile): CurseforgeFile {
  return {
    id: f.id,
    fileName: f.fileName,
    displayName: f.displayName || f.fileName,
    downloadUrl: f.downloadUrl,
    gameVersions: f.gameVersions ?? [],
    releaseType: f.releaseType ?? 1,
    fileLength: f.fileLength ?? 0,
  }
}

export async function fetchCurseforgeFiles(modId: number, gameVersion: string): Promise<CurseforgeFile[]> {
  const data = (await api.curseforge.modFiles(modId, gameVersion || undefined)) as { data?: CurseforgeApiFile[] }
  return (data?.data ?? []).map(toCurseforgeFile)
}

/// CurseForge mélange la version MC ET le loader dans le même tableau
/// `gameVersions` (ex: `["1.20.1", "Fabric"]`) — sans ce filtre, le premier
/// fichier avec une `downloadUrl` peut très bien être pour un autre loader
/// que celui de l'instance (vu en test : Sodium installé en NeoForge sur une
/// instance Fabric). Insensible à la casse, "vanilla" ne filtre rien (pas de
/// tag loader CurseForge pour un serveur vanilla).
export function findFileForLoader(files: CurseforgeFile[], loader: string): CurseforgeFile | undefined {
  const withUrl = files.filter((f) => !!f.downloadUrl)
  if (loader === 'vanilla') return withUrl[0]
  const wanted = loader.toLowerCase()
  return (
    withUrl.find((f) => f.gameVersions.some((gv) => gv.toLowerCase() === wanted)) ??
    withUrl[0]
  )
}

/// Variante qui garde TOUTE la liste (pas juste le premier match) — utilisée par l'écran de
/// switch de version, qui doit lister plusieurs fichiers compatibles plutôt qu'en choisir un
/// seul automatiquement. Contrairement à `findFileForLoader`, pas de repli sur les fichiers
/// non filtrés : un fichier au mauvais loader n'a rien à faire dans cette liste.
export function filterFilesForLoader(files: CurseforgeFile[], loader: string): CurseforgeFile[] {
  const withUrl = files.filter((f) => !!f.downloadUrl)
  if (loader === 'vanilla') return withUrl
  const wanted = loader.toLowerCase()
  return withUrl.filter((f) => f.gameVersions.some((gv) => gv.toLowerCase() === wanted))
}

/// Variante stricte pour les suggestions de mise à jour automatiques : contrairement à
/// `findFileForLoader` (utilisé au clic explicite d'installation, où un "meilleur effort"
/// est acceptable), pas de repli sur le premier fichier — `latestFiles` couvre TOUTES les
/// versions MC du mod, pas seulement celle de l'instance, donc un repli imprécis ici
/// suggérerait facilement une mise à jour vers une version MC ou un loader incompatible.
function findExactUpdateCandidate(files: CurseforgeFile[], mcVersion: string, loader: string): CurseforgeFile | undefined {
  const wantedVersion = mcVersion.toLowerCase()
  const wantedLoader = loader.toLowerCase()
  return files.find((f) => {
    if (!f.downloadUrl) return false
    const versions = f.gameVersions.map((v) => v.toLowerCase())
    return versions.includes(wantedVersion) && (loader === 'vanilla' || versions.includes(wantedLoader))
  })
}

interface CurseforgeFingerprintMatch {
  id: number
  file: CurseforgeApiFile
  latestFiles?: CurseforgeApiFile[]
}

export interface CurseforgeMatch {
  modId: number
  fileId: number
}

// Cache module-level : évite de recalculer les fingerprints locaux + de
// rappeler le proxy à chaque ouverture du panel (mirroir de `_modrinthCache`).
export const _curseforgeCache: Record<string, {
  installedModIds: Set<number>
  matchByModName: Record<string, CurseforgeMatch>
  updates: ModUpdate[]
}> = {}

/// Détection "déjà installé" + mises à jour disponibles pour les mods d'origine CurseForge
/// — équivalent CurseForge de `fetchVersionsByHash`/`checkForUpdates` (Modrinth), mais basé
/// sur le fingerprint murmur2 (voir `curseforge_local_fingerprints` côté Rust) plutôt que sha1.
/// Les mises à jour trouvées sont renvoyées au format `ModUpdate` pour pouvoir être fusionnées
/// telles quelles dans le même état/la même UI que les mises à jour Modrinth (le mécanisme
/// d'installation, `api.mods.install(url, filename)`, est déjà source-agnostique).
export async function fetchCurseforgeInstalled(
  instanceId: string,
  mods: Mod[],
  mcVersion: string,
  loader: string,
  pinnedModIds: Set<number> = new Set(),
): Promise<{ installedModIds: Set<number>; matchByModName: Record<string, CurseforgeMatch>; updates: ModUpdate[] }> {
  const locals = await api.curseforge.localFingerprints(instanceId)
  if (locals.length === 0) return { installedModIds: new Set(), matchByModName: {}, updates: [] }

  const byName = new Map(mods.map((m) => [m.name, m]))
  const nameByFingerprint = new Map(locals.map((l) => [l.fingerprint, l.name]))

  const raw = (await api.curseforge.fingerprintMatches(locals.map((l) => l.fingerprint))) as {
    data?: { exactMatches?: CurseforgeFingerprintMatch[] }
  }
  const matches = raw?.data?.exactMatches ?? []

  const installedModIds = new Set<number>()
  const matchByModName: Record<string, CurseforgeMatch> = {}
  const updates: ModUpdate[] = []
  for (const m of matches) {
    installedModIds.add(m.id)

    const localName = m.file?.fileFingerprint != null ? nameByFingerprint.get(m.file.fileFingerprint) : undefined
    const mod = localName ? byName.get(localName) : undefined
    if (!mod) continue

    matchByModName[mod.name] = { modId: m.id, fileId: m.file.id }

    if (pinnedModIds.has(m.id)) continue // downgrade délibéré via l'écran de switch — ne pas re-proposer
    const candidates = (m.latestFiles ?? []).map(toCurseforgeFile)
    const best = findExactUpdateCandidate(candidates, mcVersion, loader)
    if (best && best.downloadUrl && best.id !== m.file.id) {
      updates.push({
        mod,
        modrinthName: '',
        currentVersion: '',
        newVersion: best.fileName,
        fileUrl: best.downloadUrl,
        filename: best.fileName,
        blockedBy: [],
        source: 'curseforge',
      })
    }
  }
  return { installedModIds, matchByModName, updates }
}
