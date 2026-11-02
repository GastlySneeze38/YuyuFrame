import { listen } from '@tauri-apps/api/event'
import { api } from '@/api/client'
import type { Instance, JvmFormValues } from '@/types'

/** Une version de modpack Modrinth, telle que la fenêtre de création la propose. */
export interface PackVersion {
  id: string
  version_number: string
  game_versions: string[]
  loaders: string[]
  version_type: string
}

/** Les versions d'un modpack Modrinth, de la plus récente à la plus ancienne. */
export async function fetchPackVersions(projectId: string): Promise<PackVersion[]> {
  const res = await fetch(`https://api.modrinth.com/v2/project/${encodeURIComponent(projectId)}/version`, {
    headers: { 'User-Agent': 'YuyuFrame/1.0' },
  })
  if (!res.ok) throw new Error(`Modrinth : ${res.status}`)
  const list = await res.json() as PackVersion[]
  return list.map((v) => ({
    id: v.id,
    version_number: v.version_number,
    game_versions: v.game_versions ?? [],
    loaders: v.loaders ?? [],
    version_type: v.version_type,
  }))
}

/**
 * Crée une instance NEUVE pour une version de modpack Modrinth, puis y
 * installe le pack. Un seul chemin pour la fenêtre « Nouvelle instance » et
 * pour les serveurs moddés du catalogue.
 *
 * La version du jeu, le loader et la version du loader viennent du pack
 * (`modrinth.index.json`), jamais d'un choix de l'utilisateur : l'installation
 * d'un pack ne touche pas à la version de l'instance, donc l'instance doit
 * naître avec celles du pack. La version du loader est épinglée — sinon le
 * lancement prendrait « la plus récente », que le pack n'a jamais vue.
 *
 * Si l'installation échoue, l'instance reste : on n'efface jamais un dossier
 * d'instance sans geste de l'utilisateur.
 */
export async function createInstanceFromPack(opts: {
  versionId: string
  /** Vide = le nom du modpack. */
  name: string
  ramMb: number
  description?: string
  jvm?: JvmFormValues
  /** Auteur du pack pour la bannière de l'écran Mods, s'il est connu. */
  author?: string
  /** Le pack est installé dans l'instance qui vient d'être créée. */
  onCreated?: (instance: Instance) => void
  onProgress?: (step: { kind: 'preparing' } | { kind: 'installing'; current: number; total: number }) => void
}): Promise<Instance> {
  opts.onProgress?.({ kind: 'preparing' })
  const pack = await api.servers.modpack(opts.versionId)
  const index = await api.modpacks.fetchIndex(pack.file_url)
  const mc = index.mc_version ?? pack.game_versions[0]
  if (!mc) throw new Error(`${pack.name} : version de Minecraft inconnue`)
  const loader = index.loader || pack.loaders[0] || 'vanilla'
  const name = opts.name.trim() || pack.name
  const description = opts.description?.trim() ?? ''

  let instance = await api.instances.create(name, mc, loader, opts.ramMb, description, opts.jvm)
  if (index.loader_version && loader !== 'vanilla') {
    instance = await api.instances.update(instance.id, name, mc, loader, opts.ramMb, description, opts.jvm, index.loader_version)
  }
  opts.onCreated?.(instance)

  const off = await listen<{ current: number; total: number }>('modpack_install_progress', (e) => {
    opts.onProgress?.({ kind: 'installing', current: e.payload.current, total: e.payload.total })
  })
  try {
    await api.modpacks.install({
      instanceId: instance.id,
      fileUrl: pack.file_url,
      projectId: pack.project_id,
      versionId: pack.version_id,
      name: pack.name,
      author: opts.author ?? '',
      summary: pack.summary,
      iconUrl: pack.icon_url,
      versionNumber: pack.version_number,
      downloads: pack.downloads,
      dateModified: pack.date_modified,
      categories: pack.categories,
    })
  } finally {
    off()
  }
  return instance
}
