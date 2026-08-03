import { api } from '@/api/client'
import type { ModrinthSearchFilters } from '@/components/mods/modUtils'

export type { ModrinthSearchFilters }

export interface ModpackHit {
  project_id: string
  slug: string
  title: string
  description: string
  author: string
  icon_url: string | null
  downloads: number
  follows: number
  categories: string[]
  date_modified: string
}

export interface ResolvedModpackFile {
  url: string
  filename: string
  versionId: string
  versionNumber: string
}

/** Recherche de modpacks via le backend (`mods_search_advanced`, voir
 * commands/modrinth.rs) — même chemin que la recherche de mods/plugins, avec
 * `projectType: 'modpack'` et les mêmes filtres avancés (catégories propres
 * aux modpacks, environnement, licence, open source, tri). `gameVersion`/
 * `loader` manquaient jusqu'ici : un modpack Forge/Fabric/NeoForge apparaissait
 * pour n'importe quelle instance, quel que soit son loader ou sa version MC —
 * même filtre `categories:<loader>` que la recherche de mods côté backend. */
export async function searchModrinthModpacks(
  query: string,
  gameVersion?: string,
  loader?: string,
  filters?: ModrinthSearchFilters,
): Promise<ModpackHit[]> {
  const res = await api.mods.searchAdvanced({
    query,
    projectType: 'modpack',
    gameVersion: gameVersion || undefined,
    loader,
    categories: filters?.categories,
    environment: filters?.environment,
    license: filters?.license,
    openSourceOnly: filters?.openSourceOnly,
    sort: filters?.sort,
    limit: 20,
  })
  // Voir modUtils.ts::fetchModrinthSearch pour la même remarque sur ce cast.
  return res.hits as unknown as ModpackHit[]
}

/// Résout la dernière version .mrpack disponible pour un modpack donné.
export async function resolveModpackFile(projectId: string): Promise<ResolvedModpackFile | null> {
  const res = await fetch(`https://api.modrinth.com/v2/project/${projectId}/version`, {
    headers: { 'User-Agent': 'YuyuFrame/1.0' },
  })
  if (!res.ok) return null
  const versions = await res.json() as Array<{
    id: string
    version_number: string
    files: Array<{ url: string; filename: string; primary: boolean }>
  }>
  const version = versions[0]
  if (!version) return null
  const file = version.files.find((f) => f.primary) ?? version.files[0]
  if (!file) return null
  return { url: file.url, filename: file.filename, versionId: version.id, versionNumber: version.version_number }
}

export function formatRelativeDate(iso: string | null): string {
  if (!iso) return ''
  const diffMs = Date.now() - new Date(iso).getTime()
  const days = Math.floor(diffMs / 86_400_000)
  if (days <= 0) return "aujourd'hui"
  if (days === 1) return 'hier'
  if (days < 7) return `il y a ${days} jours`
  if (days < 30) return `il y a ${Math.floor(days / 7)} semaine(s)`
  if (days < 365) return `il y a ${Math.floor(days / 30)} mois`
  return `il y a ${Math.floor(days / 365)} an(s)`
}
