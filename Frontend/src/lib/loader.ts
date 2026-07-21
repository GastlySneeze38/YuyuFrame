import type { Loader } from '@/types'

// Avant ce fichier, LOADERS et loaderColor existaient en 2 et 4 copies
// identiques (ImportSourceModal/Instances, et Home/Instances/Stats/Sync).

export const LOADERS: Loader[] = ['vanilla', 'fabric', 'forge']

export function clampLoader(loader: string | null | undefined): Loader {
  return (LOADERS as string[]).includes(loader ?? '') ? (loader as Loader) : 'vanilla'
}

export function loaderColor(loader: string): string {
  if (loader === 'fabric') return '#b5a0ff'
  if (loader === 'forge') return '#f0a040'
  return 'rgba(255,255,255,0.4)'
}
