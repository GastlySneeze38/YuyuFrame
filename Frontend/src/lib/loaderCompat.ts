import { useEffect, useState } from 'react'
import { api } from '@/api/client'

/**
 * Ce qui va avec quoi, entre loaders et versions du jeu.
 *
 * Tous les écrans qui proposent un couple (version, loader) doivent poser la
 * même question — création, import, duplication, paramètres — et aucun n'a
 * intérêt à la poser deux fois. D'où ce module : les réponses sont mémorisées
 * pour la session, et les appels simultanés sur la même clé sont fusionnés,
 * comme le cache d'aperçus de skins (`lib/skinCache.ts`).
 *
 * Mémorisées sans expiration, volontairement : la liste des versions qu'un
 * loader supporte ne bouge qu'à la sortie d'une version de Minecraft, et le
 * launcher redémarre bien plus souvent que ça.
 */

/** Promesses en cours, pour qu'une question posée deux fois ne parte qu'une. */
const gameVersionsByLoader = new Map<string, Promise<string[]>>()
const loadersByGameVersion = new Map<string, Promise<string[]>>()

/**
 * Les versions du jeu utilisables avec ce loader, ou `null` quand il n'y a
 * aucune restriction (vanilla) ou qu'on ne sait pas encore.
 *
 * `null` et non une liste vide : « pas de restriction » et « aucune version
 * possible » mènent à des affichages opposés, et une liste vide dirait le
 * second alors qu'on veut presque toujours le premier.
 */
export function useGameVersionsFor(loader: string): Set<string> | null {
  const [versions, setVersions] = useState<Set<string> | null>(null)

  useEffect(() => {
    if (loader === 'vanilla') { setVersions(null); return }
    let alive = true
    setVersions(null)

    let pending = gameVersionsByLoader.get(loader)
    if (!pending) {
      // L'échec se mémorise comme une absence de restriction plutôt que de se
      // rejouer à chaque frappe : hors ligne, l'écran reste utilisable.
      pending = api.versions.loaderGameVersions(loader).catch(() => [])
      gameVersionsByLoader.set(loader, pending)
    }
    pending.then((list) => {
      if (!alive) return
      setVersions(list.length > 0 ? new Set(list) : null)
    })

    return () => { alive = false }
  }, [loader])

  return versions
}

/**
 * Les loaders qui existent pour cette version du jeu, ou `null` tant qu'on ne
 * sait pas — auquel cas on les propose tous : une panne de réseau ne doit pas
 * retirer un choix légitime.
 */
export function useLoadersFor(mcVersion: string): string[] | null {
  const [loaders, setLoaders] = useState<string[] | null>(null)

  useEffect(() => {
    if (!mcVersion) { setLoaders(null); return }
    let alive = true
    setLoaders(null)

    let pending = loadersByGameVersion.get(mcVersion)
    if (!pending) {
      pending = api.versions.loaderAvailability(mcVersion).catch(() => [])
      loadersByGameVersion.set(mcVersion, pending)
    }
    pending.then((list) => {
      if (!alive) return
      setLoaders(list.length > 0 ? list : null)
    })

    return () => { alive = false }
  }, [mcVersion])

  return loaders
}

/**
 * La liste des versions à proposer, une fois le loader connu.
 *
 * Garde toujours `current` même s'il n'est pas dans la liste : une instance
 * existante peut porter un couple que le loader ne publie plus, et faire
 * disparaître sa propre version du menu donnerait un écran qui ment sur ce
 * qui est installé.
 */
export function allowedVersions(all: string[], allowed: Set<string> | null, current?: string): string[] {
  if (!allowed) return all
  const kept = all.filter((v) => allowed.has(v) || v === current)
  // Un loader dont aucune version ne recoupe la liste veut dire qu'on n'a pas
  // compris quelque chose : mieux vaut tout proposer que rien.
  return kept.length > 0 ? kept : all
}
