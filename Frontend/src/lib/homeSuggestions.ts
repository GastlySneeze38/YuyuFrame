import { api } from '@/api/client'
import type { CatalogServer } from '@/api/client'
import { readCache, writeCache } from './persistentCache'

/**
 * Les serveurs proposés sur l'accueil quand la rangée n'a pas ses trois
 * serveurs : les premiers serveurs **vanilla** du catalogue de Modrinth, ceux
 * qui acceptent la version de l'instance d'abord (on peut les rejoindre
 * directement), complétés au besoin par les autres.
 *
 * Mémorisés pour la session, par version : l'accueil s'affiche souvent, et le
 * classement du catalogue ne bouge pas d'une minute à l'autre. Une panne de
 * Modrinth rend une liste vide — la rangée garde alors ses emplacements vides.
 */

const WANTED = 8
const cache = new Map<string, Promise<CatalogServer[]>>()

async function search(gameVersion: string | null): Promise<CatalogServer[]> {
  const page = await api.servers.search({
    query: '',
    categories: [],
    region: null,
    language: null,
    gameVersion,
    kind: 'vanilla',
    sort: 'relevance',
    page: 1,
    pageSize: WANTED,
  })
  return page.servers
}

/** Gardées d'un lancement à l'autre (2026-11-06) : la dernière liste connue
 *  s'affiche tout de suite, la nouvelle la remplace au lancement suivant —
 *  l'accueil ne laisse plus ses emplacements vides pendant la requête. */
const persistKey = (key: string) => `home-suggestions:${key}`
const PERSIST_MAX_AGE = 3 * 24 * 3600_000

export function homeSuggestions(mcVersion: string | null): Promise<CatalogServer[]> {
  const key = mcVersion ?? ''
  const hit = cache.get(key)
  if (hit) return hit
  const p = (async () => {
    const compatible = mcVersion ? await search(mcVersion).catch(() => []) : []
    if (compatible.length >= WANTED) return compatible
    const others = await search(null).catch(() => [])
    const seen = new Set(compatible.map((s) => s.address.toLowerCase()))
    return [...compatible, ...others.filter((s) => !seen.has(s.address.toLowerCase()))]
  })()
  // Un échec complet ne reste pas en mémoire : on réessaiera au prochain affichage.
  void p.then((list) => {
    if (list.length === 0) cache.delete(key)
    else writeCache(persistKey(key), list)
  })
  const stale = readCache<CatalogServer[]>(persistKey(key), PERSIST_MAX_AGE)?.value
  if (stale && stale.length > 0) {
    // Réponse immédiate avec la liste gardée ; la requête court quand même et
    // la mémoire de session prend la fraîche dès qu'elle arrive.
    const fresh = p.then((list) => (list.length > 0 ? list : stale))
    cache.set(key, fresh)
    return Promise.resolve(stale)
  }
  cache.set(key, p)
  return p
}

/** Ce serveur accepte-t-il cette version ? Sans liste de versions, on ne
 *  sait pas : on laisse tenter. */
export function acceptsVersion(server: CatalogServer, mcVersion: string | null): boolean {
  return !mcVersion || server.game_versions.length === 0 || server.game_versions.includes(mcVersion)
}
