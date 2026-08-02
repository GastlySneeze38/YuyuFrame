import { api } from '@/api/client'
import type { Mod } from '@/types'

// ── Types Modrinth ────────────────────────────────────────────────────────────

export interface ModrinthInfo {
  version: string
  modrinthName: string
  projectId: string
}

export interface ModUpdate {
  mod: Mod
  modrinthName: string
  currentVersion: string
  newVersion: string
  fileUrl: string
  filename: string
  /// Mods dont la dépendance déclarée (fabric.mod.json) serait cassée par cette mise à jour.
  blockedBy: string[]
}

export interface ModrinthHit {
  project_id: string
  slug: string
  title: string
  description: string
  icon_url: string | null
  downloads: number
  categories: string[]
  // Présents dans la réponse Modrinth réelle mais pas systématiquement utilisés
  // ailleurs dans l'UI — optionnels pour rester compatibles avec les hits déjà
  // consommés tels quels dans le code existant.
  client_side?: string
  server_side?: string
  license?: string
}

export interface ModrinthSearchFilters {
  categories?: string[]
  environment?: 'client' | 'server'
  license?: string
  openSourceOnly?: boolean
  sort?: 'relevance' | 'downloads' | 'follows' | 'newest' | 'updated'
}

export interface ModrinthVersion {
  files: Array<{ url: string; filename: string; primary: boolean }>
}

export interface ModrinthProjectDetail {
  id: string
  title: string
  description: string
  body: string
  icon_url: string | null
  downloads: number
  categories: string[]
  client_side: string
  server_side: string
}

export interface ModrinthVersionFile {
  url: string
  filename: string
  primary: boolean
  size: number
  hashes?: { sha1?: string }
}

export interface ModrinthVersionEntry {
  id: string
  version_number: string
  version_type: string
  game_versions: string[]
  loaders: string[]
  date_published: string
  files: ModrinthVersionFile[]
}

export type Tab = 'installed' | 'browse' | 'curseforge' | 'modpack'

// Cache module-level : évite de rappeler Modrinth à chaque ouverture du panel
export const _modrinthCache: Record<string, {
  versionMap: Record<string, ModrinthInfo>
  updates: ModUpdate[]
}> = {}

// Cache icônes module-level — clé : "instanceId/modDisplayName"
export const _iconCache: Record<string, string | null> = {}

// ── Helpers ───────────────────────────────────────────────────────────────────

export function displayName(name: string): string {
  return name.replace(/\.jar(\.disabled)?$/, '')
}

export function baseFilename(name: string): string {
  return name.replace(/\.disabled$/, '')
}

/// Détecte une version pré-release (beta/alpha/rc/pre/snapshot) — même heuristique
/// que côté Rust (deps.rs) pour ne jamais proposer ces versions automatiquement.
export function isBetaVersion(version: string): boolean {
  return version.split(/[.\-+]/).some((seg) => {
    const l = seg.toLowerCase()
    return ['alpha', 'beta', 'rc', 'pre', 'snapshot'].some((kw) => l.startsWith(kw))
  })
}

/// Nettoyage minimal du markdown (Modrinth `body`) pour un affichage en texte brut lisible.
export function stripMarkdown(md: string): string {
  return md
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/(\*\*|__)(.*?)\1/g, '$2')
    .replace(/(\*|_)(.*?)\1/g, '$2')
    .replace(/`{1,3}([^`]*)`{1,3}/g, '$1')
    .replace(/^>\s?/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export function versionTypeBadge(t: string): { label: string; color: string } {
  if (t === 'release') return { label: 'release', color: 'rgba(74,222,128,0.85)' }
  if (t === 'beta') return { label: 'beta', color: 'rgba(250,204,21,0.85)' }
  return { label: 'alpha', color: 'rgba(248,113,113,0.85)' }
}

export function formatGameVersions(gvs: string[]): string {
  if (gvs.length === 0) return '—'
  return gvs.length > 3 ? `${gvs.slice(0, 3).join(', ')} +${gvs.length - 3}` : gvs.join(', ')
}

// ── Modrinth fetch helpers ───────────────────────────────────────────────────

export async function fetchProjectDetail(projectId: string): Promise<ModrinthProjectDetail | null> {
  try {
    const res = await fetch(`https://api.modrinth.com/v2/project/${projectId}`, {
      headers: { 'User-Agent': 'YuyuFrame/1.0' },
    })
    if (!res.ok) return null
    return await res.json()
  } catch {
    return null
  }
}

export async function fetchProjectVersions(
  projectId: string,
  loader: string,
  gameVersion: string | null,
): Promise<ModrinthVersionEntry[]> {
  const params = new URLSearchParams()
  if (loader && loader !== 'vanilla') params.set('loaders', JSON.stringify([loader]))
  if (gameVersion) params.set('game_versions', JSON.stringify([gameVersion]))
  try {
    const res = await fetch(`https://api.modrinth.com/v2/project/${projectId}/version?${params}`, {
      headers: { 'User-Agent': 'YuyuFrame/1.0' },
    })
    if (!res.ok) return []
    return await res.json()
  } catch {
    return []
  }
}

export async function fetchVersionsByHash(sha1s: string[]): Promise<Record<string, ModrinthInfo>> {
  const hashes = sha1s.filter(Boolean)
  if (hashes.length === 0) return {}
  try {
    const res = await fetch('https://api.modrinth.com/v2/version_files', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'YuyuFrame/1.0' },
      body: JSON.stringify({ hashes, algorithm: 'sha1' }),
    })
    if (!res.ok) return {}
    const versionData = await res.json() as Record<string, { version_number: string; project_id: string }>

    // Batch-fetch project titles
    const projectIds = [...new Set(Object.values(versionData).map((v) => v.project_id))]
    const projectNames: Record<string, string> = {}
    if (projectIds.length > 0) {
      const pRes = await fetch(
        `https://api.modrinth.com/v2/projects?ids=${encodeURIComponent(JSON.stringify(projectIds))}`,
        { headers: { 'User-Agent': 'YuyuFrame/1.0' } },
      )
      if (pRes.ok) {
        const projects = await pRes.json() as Array<{ id: string; title: string }>
        projects.forEach((p) => { projectNames[p.id] = p.title })
      }
    }

    const out: Record<string, ModrinthInfo> = {}
    for (const [hash, info] of Object.entries(versionData)) {
      out[hash] = { version: info.version_number, modrinthName: projectNames[info.project_id] ?? '', projectId: info.project_id }
    }
    return out
  } catch {
    return {}
  }
}

export async function checkForUpdates(
  instanceId: string,
  mods: Mod[],
  versionData: Record<string, ModrinthInfo>,
  mcVersion: string,
  loader: string,
  avoidBeta: boolean,
  pinnedProjectIds: Set<string> = new Set(),
): Promise<ModUpdate[]> {
  // Un mod épinglé = on a délibérément basculé sur une version plus ancienne
  // via le sélecteur de version — ne pas le re-proposer en mise à jour.
  const eligible = mods.filter((m) => m.sha1 && !pinnedProjectIds.has(versionData[m.sha1]?.projectId ?? ''))
  if (eligible.length === 0) return []
  try {
    const body: Record<string, unknown> = {
      hashes: eligible.map((m) => m.sha1),
      algorithm: 'sha1',
      game_versions: [mcVersion],
    }
    if (loader !== 'vanilla') body.loaders = [loader]
    const res = await fetch('https://api.modrinth.com/v2/version_files/update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'YuyuFrame/1.0' },
      body: JSON.stringify(body),
    })
    if (!res.ok) return []
    const data = await res.json() as Record<string, {
      version_number: string
      files: Array<{ url: string; filename: string; primary: boolean; hashes?: { sha1?: string } }>
    }>
    const updates: ModUpdate[] = []
    for (const mod of eligible) {
      const latest = data[mod.sha1]
      if (!latest) continue
      if (avoidBeta && isBetaVersion(latest.version_number)) continue // jamais de beta auto
      const primary = latest.files.find((f) => f.primary) ?? latest.files[0]
      if (!primary) continue
      if (primary.hashes?.sha1 === mod.sha1) continue // déjà à jour
      updates.push({
        mod,
        modrinthName: versionData[mod.sha1]?.modrinthName || '',
        currentVersion: versionData[mod.sha1]?.version || '',
        newVersion: latest.version_number,
        fileUrl: primary.url,
        filename: primary.filename,
        blockedBy: [],
      })
    }

    // Ne propose pas une mise à jour qui casserait la contrainte de version
    // d'un autre mod installé (ex: Voxy exige Sodium &lt;0.8.13).
    if (updates.length > 0) {
      try {
        const safety = await api.mods.checkUpdateSafety(
          instanceId,
          mcVersion,
          loader,
          updates.map((u) => ({ name: u.mod.name, newVersion: u.newVersion })),
        )
        const blockedByName = new Map(safety.map((s) => [s.name, s.blockedBy]))
        for (const u of updates) {
          u.blockedBy = blockedByName.get(u.mod.name) ?? []
        }
      } catch { /* la vérification de sécurité est best-effort */ }
    }

    return updates
  } catch {
    return []
  }
}

export async function updateModsForNewVersion(
  instanceId: string,
  mcVersion: string,
  loader: string,
): Promise<void> {
  try {
    const mods = await api.mods.list(instanceId)
    if (mods.length === 0) return
    const infoMap = await fetchVersionsByHash(mods.map((m) => m.sha1))
    for (const mod of mods) {
      const info = infoMap[mod.sha1]
      // Mod inconnu de Modrinth (custom/privé) → on ne touche pas
      if (!info?.projectId) continue
      try {
        const params = new URLSearchParams()
        params.set('game_versions', JSON.stringify([mcVersion]))
        if (loader !== 'vanilla') params.set('loaders', JSON.stringify([loader]))
        const res = await fetch(
          `https://api.modrinth.com/v2/project/${info.projectId}/version?${params}`,
          { headers: { 'User-Agent': 'YuyuFrame/1.0' } },
        )
        if (!res.ok) {
          // Erreur réseau → désactiver par précaution si le mod est actif
          if (mod.enabled) await api.mods.toggle(instanceId, mod.name).catch(() => {})
          continue
        }
        const versions = await res.json() as Array<{ files: Array<{ url: string; filename: string; primary: boolean }> }>
        if (!versions.length) {
          // Aucune version compatible → désactiver (ne pas crasher le jeu)
          if (mod.enabled) await api.mods.toggle(instanceId, mod.name).catch(() => {})
          continue
        }
        const file = versions[0].files.find((f) => f.primary) ?? versions[0].files[0]
        if (!file) {
          if (mod.enabled) await api.mods.toggle(instanceId, mod.name).catch(() => {})
          continue
        }
        const newMod = await api.mods.install(instanceId, file.url, file.filename)
        if (newMod.name !== mod.name) {
          await api.mods.delete(instanceId, mod.name).catch(() => {})
        }
      } catch {
        // Erreur inattendue → désactiver par sécurité
        if (mod.enabled) await api.mods.toggle(instanceId, mod.name).catch(() => {})
      }
    }
  } catch { /* ignore */ }
}

/** Recherche Modrinth via le backend (`mods_search_advanced`, voir
 * commands/modrinth.rs) plutôt qu'un fetch direct côté frontend — permet les
 * filtres avancés (catégories de contenu, environnement client/serveur,
 * licence, open source uniquement, tri) en plus de query/version/loader. */
export async function fetchModrinthSearch(
  query: string,
  gameVersion: string,
  loader: string,
  filters?: ModrinthSearchFilters,
): Promise<ModrinthHit[]> {
  const res = await api.mods.searchAdvanced({
    query,
    gameVersion: gameVersion || undefined,
    loader,
    categories: filters?.categories,
    environment: filters?.environment,
    license: filters?.license,
    openSourceOnly: filters?.openSourceOnly,
    sort: filters?.sort,
    limit: 20,
  })
  // Le backend renvoie du JSON brut (Record<string, unknown>) — cast via
  // `unknown` assumé : le champs qu'on utilise (project_id, slug, title...)
  // sont bien présents dans la vraie réponse Modrinth, juste non déclarés
  // dans ce Record générique côté TypeScript.
  return res.hits as unknown as ModrinthHit[]
}

export async function fetchLatestVersion(
  slug: string,
  gameVersion: string,
  loader: string,
): Promise<ModrinthVersion | null> {
  const params = new URLSearchParams()
  if (gameVersion) params.set('game_versions', JSON.stringify([gameVersion]))
  if (loader && loader !== 'vanilla') params.set('loaders', JSON.stringify([loader]))

  const res = await fetch(
    `https://api.modrinth.com/v2/project/${slug}/version?${params}`,
    { headers: { 'User-Agent': 'YuyuFrame/1.0' } },
  )
  if (!res.ok) return null
  const versions: ModrinthVersion[] = await res.json()
  return versions[0] ?? null
}
