import { api } from '@/api/client'

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
  /// `null` quand l'auteur a désactivé la distribution via l'API tierce —
  /// dans ce cas le fichier n'est pas installable depuis le launcher.
  downloadUrl: string | null
  gameVersions: string[]
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
  downloadUrl: string | null
  gameVersions: string[]
}

/// classId CurseForge pour "Minecraft Mods" — recherche uniquement les mods
/// pour l'instant (pas les resource packs/plugins Bukkit, gérés plus tard).
const CLASS_ID_MODS = '6'

export async function fetchCurseforgeSearch(query: string, gameVersion: string): Promise<CurseforgeHit[]> {
  if (!query.trim()) return []
  const data = (await api.curseforge.search(query, {
    gameVersion: gameVersion || undefined,
    classId: CLASS_ID_MODS,
    pageSize: 20,
  })) as { data?: CurseforgeApiMod[] }

  return (data?.data ?? []).map((m) => ({
    id: m.id,
    slug: m.slug,
    name: m.name,
    summary: m.summary,
    logoUrl: m.logo?.url ?? null,
    downloadCount: m.downloadCount,
  }))
}

export async function fetchCurseforgeFiles(modId: number, gameVersion: string): Promise<CurseforgeFile[]> {
  const data = (await api.curseforge.modFiles(modId, gameVersion || undefined)) as { data?: CurseforgeApiFile[] }
  return (data?.data ?? []).map((f) => ({
    id: f.id,
    fileName: f.fileName,
    downloadUrl: f.downloadUrl,
    gameVersions: f.gameVersions ?? [],
  }))
}
