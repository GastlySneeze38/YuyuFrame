import type { Loader } from '@/types'

// Avant ce fichier, LOADERS et loaderColor existaient en 2 et 4 copies
// identiques (ImportSourceModal/Instances, et Home/Instances/Stats/Sync).

export const LOADERS: Loader[] = ['vanilla', 'fabric', 'quilt', 'forge', 'neoforge']

export function clampLoader(loader: string | null | undefined): Loader {
  return (LOADERS as string[]).includes(loader ?? '') ? (loader as Loader) : 'vanilla'
}

export function loaderColor(loader: string): string {
  if (loader === 'fabric') return '#b5a0ff'
  if (loader === 'quilt') return '#8f5cff'
  if (loader === 'forge') return '#f0a040'
  if (loader === 'neoforge') return '#f76b1c'
  return 'rgba(255,255,255,0.4)'
}
