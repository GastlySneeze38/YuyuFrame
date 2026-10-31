import { api } from '@/api/client'
import type { CatalogServer } from '@/api/client'

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
  cache.set(key, p)
  // Un échec complet ne reste pas en mémoire : on réessaiera au prochain affichage.
  void p.then((list) => list.length === 0 && cache.delete(key))
  return p
}

/** Ce serveur accepte-t-il cette version ? Sans liste de versions, on ne
 *  sait pas : on laisse tenter. */
export function acceptsVersion(server: CatalogServer, mcVersion: string | null): boolean {
  return !mcVersion || server.game_versions.length === 0 || server.game_versions.includes(mcVersion)
}
