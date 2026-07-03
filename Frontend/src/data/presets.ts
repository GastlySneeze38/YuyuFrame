import type { Loader } from '@/types'

/// Soit un slug Modrinth (résolu via l'API au moment de l'install), soit une
/// URL directe — utile pour un mod tout juste publié/pas encore indexé par
/// la recherche Modrinth, ou hébergé ailleurs.
export interface DirectModFile {
  url: string
  filename: string
}

export interface InstancePreset {
  id: string
  name: string
  description: string
  emoji: string
  mcVersion: string
  loader: Loader
  ramMb: number
  /// Slugs Modrinth (project slug ou id) ou fichiers en URL directe,
  /// installés automatiquement à la création.
  mods: (string | DirectModFile)[]
}

// ── Liste des presets — modifiable ici sans toucher au reste du code ───────────
// Pour ajouter un preset : copier un objet ci-dessous, changer les champs.
// `mods` attend des slugs Modrinth (visibles dans l'URL du mod, ex: modrinth.com/mod/<slug>).
export const INSTANCE_PRESETS: InstancePreset[] = [
  {
    id: 'vanilla-plus',
    name: 'Vanilla+',
    description: 'Qualité de vie minimale, fidèle au vanilla',
    emoji: '🌿',
    mcVersion: '1.21.11',
    loader: 'fabric',
    ramMb: 4096,
    mods: ['fabric-api', 'sodium', 'lithium', 'modmenu'],
  },
  {
    id: 'performance',
    name: 'Perf',
    description: 'FPS boost maximal, idéal PC modeste',
    emoji: '⚡',
    mcVersion: '1.21.11',
    loader: 'fabric',
    ramMb: 4096,
    mods: ['fabric-api', 'sodium', 'lithium', 'ferrite-core', 'modmenu', 'Iris Shaders', 'boby'],
  },
  {
    id: 'performance-plus',
    name: 'Perf+',
    description: 'FPS boost maximal, idéal PC modeste',
    emoji: '⚡',
    mcVersion: '1.21.11',
    loader: 'fabric',
    ramMb: 4096,
    mods: ['fabric-api', 'sodium', 'lithium', 'ferrite-core', 'modmenu', 'Iris Shaders', 'boby', 'voxy'],
  },
  {
    id: 'Confort',
    name: 'Confort',
    description: 'All the Mods you need',
    emoji: '',
    mcVersion: '1.21.11',
    loader: 'fabric',
    ramMb: 6144,
    mods: ['fabric-api', 'sodium', 'lithium', 'ferrite-core', 'modmenu', 'Iris Shaders', 'boby', 'voxy', 'Simple Voice Chat', 'Widget', 'wWaypoint', 'FullBright', 'Zoomify', 'Litematica'],
  },
]
