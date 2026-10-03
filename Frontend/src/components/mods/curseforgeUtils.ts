import { api } from '@/api/client'
import type { Mod } from '@/types'
import { SEARCH_PAGE_SIZE, type ModUpdate, type SearchPage } from './modUtils'

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

/// Champs de tri CurseForge (voir `/v1/mods/search`). CurseForge n'a PAS de
/// tri par pertinence textuelle : omettre `sortField` retombe sur "Featured"
/// (1), la mise en avant éditoriale de CurseForge, sans rapport avec la
/// requête tapée — les mods réellement pertinents pour une recherche pointue
/// (typiquement Forge, où CurseForge concentre le plus de mods) finissaient
/// noyés derrière des mods "Featured" génériques, parfois en toute fin de
/// liste. "Popularity" descendant est le proxy le plus proche de la
/// pertinence perçue, et c'est déjà le tri par défaut du site CurseForge
/// lui-même pour une recherche texte.
const SORT_FIELD: Record<string, string> = {
  relevance: '2',
  downloads: '6',
  updated: '3',
  name: '4',
}

/// modLoaderType CurseForge (voir `/v1/mods/search`), distinct de sortField —
/// sans lui, CurseForge renvoie des mods de tous les loaders mélangés (ex: du
/// NeoForge proposé sur une instance Fabric). LiteLoader/Cauldron omis, jamais
/// utilisés par ce launcher. Exporté : réutilisé tel quel par
/// curseforgeModpacks.ts (même enum côté CurseForge, mods ou modpacks).
export const MOD_LOADER_TYPE: Record<string, string> = {
  forge: '1',
  fabric: '4',
  quilt: '5',
  neoforge: '6',
}

export async function fetchCurseforgeSearch(
  query: string,
  gameVersion: string,
  loader: string,
  filters: CurseforgeSearchFilters = {},
  page = 1,
): Promise<SearchPage<CurseforgeHit>> {
  if (!query.trim() || page > CURSEFORGE_MAX_PAGE) return { hits: [], total: 0 }
  const data = (await api.curseforge.search(query, {
    gameVersion: gameVersion || undefined,
    classId: CLASS_ID_MODS,
    pageSize: SEARCH_PAGE_SIZE,
    index: (page - 1) * SEARCH_PAGE_SIZE,
    categoryId: filters.categoryId ? String(filters.categoryId) : undefined,
    sortField: SORT_FIELD[filters.sort ?? 'relevance'],
    sortOrder: 'desc',
    // "vanilla" n'a pas d'équivalent modLoaderType côté CurseForge (classId=6
    // = mods, jamais des plugins) — pas de filtre à poser dans ce cas.
    modLoaderType: loader !== 'vanilla' ? MOD_LOADER_TYPE[loader] : undefined,
  })) as { data?: CurseforgeApiMod[]; pagination?: CurseforgePagination }

  return { hits: (data?.data ?? []).map(toCurseforgeHit), total: curseforgeTotal(data?.pagination) }
}

export interface CurseforgePagination {
  totalCount?: number
}

/// CurseForge refuse toute recherche dont `index + pageSize` dépasse 10 000 :
/// au-delà de cette page, on ne lui demande plus rien.
export const CURSEFORGE_MAX_PAGE = Math.floor(10_000 / SEARCH_PAGE_SIZE)

/// Nombre de résultats que CurseForge peut réellement servir : son total
/// annoncé, borné à ce que sa limite de 10 000 laisse atteindre — sans quoi la
/// pagination proposerait des pages qui répondent une erreur.
export function curseforgeTotal(pagination?: CurseforgePagination): number {
  return Math.min(pagination?.totalCount ?? 0, CURSEFORGE_MAX_PAGE * SEARCH_PAGE_SIZE)
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
  } catch (e) {
    console.error('fetchCurseforgeModDetail failed', modId, e)
    return null
  }
}

export async function fetchCurseforgeCategories(): Promise<CurseforgeCategory[]> {
  try {
    const data = (await api.curseforge.categories()) as { data?: CurseforgeApiCategory[] }
    return (data?.data ?? []).map((c) => ({ id: c.id, name: c.name, slug: c.slug }))
  } catch (e) {
    console.error('fetchCurseforgeCategories failed', e)
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

/// Tags loader connus pouvant apparaître dans `gameVersions` — sert à distinguer "ce fichier
/// est explicitement tagué pour un AUTRE loader" de "ce fichier n'est tagué pour aucun loader
/// en particulier" (beaucoup de fichiers plus anciens n'ont tout simplement pas de tag loader
/// du tout dans `gameVersions`, sans que ça signifie qu'ils sont incompatibles).
const KNOWN_LOADER_TAGS = ['forge', 'fabric', 'quilt', 'neoforge', 'liteloader', 'cauldron', 'rift']

/// Variante qui garde TOUTE la liste (pas juste le premier match) — utilisée par l'écran de
/// switch de version, qui doit lister plusieurs fichiers compatibles plutôt qu'en choisir un
/// seul automatiquement. Contrairement à `findFileForLoader` (repli sur le 1er fichier), on
/// exclut ici seulement les fichiers explicitement tagués pour un AUTRE loader — exiger un tag
/// EXACT pour notre loader viderait la liste dans la plupart des cas, beaucoup de fichiers
/// CurseForge ne taguant simplement aucun loader dans `gameVersions`.
///
/// `mcVersion` filtre en plus par version MC — appliqué CÔTÉ CLIENT plutôt que via le paramètre
/// `gameVersion` de l'API CurseForge (côté serveur) : ce dernier s'est avéré peu fiable (renvoie
/// vide pour des versions MC pourtant bien présentes dans la liste complète des fichiers), donc
/// on ne fait plus confiance qu'au filtrage client, déjà éprouvé pour le loader.
export function filterFilesForLoader(files: CurseforgeFile[], loader: string, mcVersion?: string): CurseforgeFile[] {
  const withUrl = files.filter((f) => !!f.downloadUrl)
  const wanted = loader.toLowerCase()
  const byLoader = loader === 'vanilla' ? withUrl : withUrl.filter((f) => {
    const versions = f.gameVersions.map((v) => v.toLowerCase())
    const taggedForOtherLoader = versions.some((v) => KNOWN_LOADER_TAGS.includes(v) && v !== wanted)
    return !taggedForOtherLoader
  })
  if (!mcVersion) return byLoader
  const wantedVersion = mcVersion.toLowerCase()
  return byLoader.filter((f) => f.gameVersions.some((v) => v.toLowerCase() === wantedVersion))
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
    if (!versions.includes(wantedVersion)) return false
    const taggedForOtherLoader = versions.some((v) => KNOWN_LOADER_TAGS.includes(v) && v !== wantedLoader)
    return loader === 'vanilla' || !taggedForOtherLoader
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
  /// Nom d'affichage du fichier actuellement installé — CurseForge n'a pas de numéro de
  /// version sémantique comme Modrinth, c'est ce qu'on affiche dans la colonne "Version".
  fileName: string
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
/**
 * Les épinglages (« ignorer les mises à jour ») ne filtrent PAS ici : le
 * résultat est mis en cache par instance, alors que l'état des épinglages
 * change à tout moment sans vider ce cache. C'est l'affichage qui les retire
 * (voir `allUpdates` dans pages/Mods.tsx).
 */
export async function fetchCurseforgeInstalled(
  instanceId: string,
  mods: Mod[],
  mcVersion: string,
  loader: string,
): Promise<{ installedModIds: Set<number>; matchByModName: Record<string, CurseforgeMatch>; updates: ModUpdate[] }> {
  const locals = await api.curseforge.localFingerprints(instanceId)
  if (locals.length === 0) return { installedModIds: new Set(), matchByModName: {}, updates: [] }

  const byName = new Map(mods.map((m) => [m.name, m]))
  const nameByFingerprint = new Map(locals.map((l) => [l.fingerprint, l.name]))

  const raw = (await api.curseforge.fingerprintMatches(locals.map((l) => l.fingerprint))) as {
    data?: { exactMatches?: CurseforgeFingerprintMatch[] }
  }
  const matches = raw?.data?.exactMatches ?? []
  // Diagnostic — la détection "déjà installé" dépend entièrement de ce que CurseForge
  // renvoie ici : si `matches` reste vide alors que des .jar CurseForge sont bien présents
  // dans le dossier mods/, le problème vient du calcul du fingerprint local ou de la
  // correspondance côté CurseForge, pas de l'UI (voir ces logs pour trancher).
  console.debug('[curseforge] fingerprints locaux calculés :', locals.length, '— matches reçus :', matches.length)

  const installedModIds = new Set<number>()
  const matchByModName: Record<string, CurseforgeMatch> = {}
  const updates: ModUpdate[] = []
  for (const m of matches) {
    installedModIds.add(m.id)

    const localName = m.file?.fileFingerprint != null ? nameByFingerprint.get(m.file.fileFingerprint) : undefined
    const mod = localName ? byName.get(localName) : undefined
    if (!mod) {
      console.debug('[curseforge] match sans correspondance locale (fingerprint renvoyé introuvable) :', m.id, m.file?.fileFingerprint)
      continue
    }

    matchByModName[mod.name] = { modId: m.id, fileId: m.file.id, fileName: m.file.displayName || m.file.fileName }

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
