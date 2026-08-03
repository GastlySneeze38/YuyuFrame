import { api } from '@/api/client'

// Miroir de modrinthModpacks.ts côté CurseForge — fichier séparé car les deux
// API n'ont pas le même schéma (id numérique vs project_id string, pas de
// `slug`/`follows` côté CurseForge...), fusionnées dans ModpackBrowseTab.tsx.

/// classId CurseForge pour "Minecraft Modpacks" (distinct de 6="mc-mods",
/// voir curseforgeUtils.ts::CLASS_ID_MODS).
const CLASS_ID_MODPACKS = '4471'

export interface CurseforgeModpackHit {
  id: number
  slug: string
  name: string
  summary: string
  author: string
  logoUrl: string | null
  downloadCount: number
  dateModified: string | null
}

export interface ResolvedCurseforgeModpackFile {
  url: string
  filename: string
  fileId: number
  displayName: string
}

interface CfApiModpack {
  id: number
  slug: string
  name: string
  summary: string
  authors?: Array<{ name: string }>
  logo?: { url?: string } | null
  downloadCount: number
  dateModified?: string
}

function toModpackHit(m: CfApiModpack): CurseforgeModpackHit {
  return {
    id: m.id,
    slug: m.slug,
    name: m.name,
    summary: m.summary,
    author: m.authors?.[0]?.name ?? '',
    logoUrl: m.logo?.url ?? null,
    downloadCount: m.downloadCount,
    dateModified: m.dateModified ?? null,
  }
}

export async function searchCurseforgeModpacks(query: string, gameVersion?: string): Promise<CurseforgeModpackHit[]> {
  if (!query.trim()) return []
  const data = (await api.curseforge.search(query, {
    gameVersion: gameVersion || undefined,
    classId: CLASS_ID_MODPACKS,
    pageSize: 20,
    // Pas de tri "pertinence" côté CurseForge (voir curseforgeUtils.ts) — la
    // popularité reste le proxy le plus proche, même raisonnement que la
    // recherche de mods.
    sortField: '2',
    sortOrder: 'desc',
  })) as { data?: CfApiModpack[] }
  return (data?.data ?? []).map(toModpackHit)
}

/// Résout le fichier du modpack lui-même (le zip manifest.json+overrides) —
/// CurseForge trie déjà les fichiers du plus récent au plus ancien, donc le
/// premier de la liste est la dernière release (même logique que
/// fetchLatestVersion côté Modrinth).
export async function resolveCurseforgeModpackFile(modId: number, gameVersion?: string): Promise<ResolvedCurseforgeModpackFile | null> {
  const data = (await api.curseforge.modFiles(modId, gameVersion || undefined)) as {
    data?: Array<{ id: number; fileName: string; displayName?: string; downloadUrl: string | null }>
  }
  const file = (data?.data ?? []).find((f) => f.downloadUrl)
  if (!file || !file.downloadUrl) return null
  return { url: file.downloadUrl, filename: file.fileName, fileId: file.id, displayName: file.displayName || file.fileName }
}
