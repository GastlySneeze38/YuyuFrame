import type { Loader } from '@/types'

// Avant ce fichier, LOADERS et loaderColor existaient en 2 et 4 copies
// identiques (ImportSourceModal/Instances, et Home/Instances/Stats/Sync).

export const LOADERS: Loader[] = ['vanilla', 'fabric', 'quilt', 'forge', 'neoforge', 'horizon']

/**
 * Les plateformes à proposer, `available` venant de `useLoadersFor`.
 *
 * Tant qu'on ne sait pas (`null`), les loaders sont tous proposés — une panne
 * de réseau ne doit pas retirer un choix légitime. Horizon est l'exception :
 * il n'existe que pour une version du jeu, et le Rust le sait sans réseau,
 * donc il n'apparaît que s'il a été annoncé.
 */
export function offeredLoaders(available: string[] | null | undefined): Loader[] {
  if (!available) return LOADERS.filter((l) => l !== 'horizon')
  return LOADERS.filter((l) => available.includes(l))
}

/**
 * Le loader à prendre quand celui choisi n'existe pas pour la version : vanilla
 * s'il est proposé, sinon le premier qui l'est (téléphone : Horizon seul).
 */
export function fallbackLoader(available: string[]): Loader {
  if (available.includes('vanilla')) return 'vanilla'
  return offeredLoaders(available)[0] ?? 'vanilla'
}

/**
 * Un vrai loader de mods Java : il a des versions à choisir et charge le
 * dossier `mods/`. Ni vanilla ni Horizon (le jeu réécrit en Rust, qui ne
 * charge aucun mod Java).
 */
export function isModLoader(loader: string): boolean {
  return loader !== 'vanilla' && loader !== 'horizon'
}

export function clampLoader(loader: string | null | undefined): Loader {
  return (LOADERS as string[]).includes(loader ?? '') ? (loader as Loader) : 'vanilla'
}

export function loaderColor(loader: string): string {
  if (loader === 'fabric') return '#b5a0ff'
  if (loader === 'quilt') return '#8f5cff'
  if (loader === 'forge') return '#f0a040'
  if (loader === 'neoforge') return '#f76b1c'
  if (loader === 'horizon') return '#4fd1c5'
  return 'rgba(255,255,255,0.4)'
}

/**
 * L'instance a-t-elle une mémoire réservée à montrer ? Pas une instance
 * Horizon : sans JVM, il n'y a pas de RAM allouée, et sa `ram_mb` (gardée
 * en base) ne dit rien.
 */
export function showsRam(instance: { loader: string }): boolean {
  return instance.loader !== 'horizon'
}

/**
 * Pourquoi une configuration JVM ne peut pas être reliée à cette instance,
 * `null` quand elle le peut. Le Rust refuse le même lien
 * (`instance_set_jvm_profile`) ; ceci sert à ne pas le proposer.
 */
export function jvmLinkBlock(instance: { loader: string }): string | null {
  if (instance.loader === 'horizon') return 'Horizon, sans Java'
  return null
}
