import { useRef } from 'react'

// Recherche distante économe en requêtes — partagée par la recherche de mods et de
// modpacks (Modrinth et CurseForge). Chaque source a son propre runner, qui :
//   - ignore une demande identique à la précédente (même clé) ;
//   - sert depuis une mémoire de quelques minutes une recherche déjà faite ;
//   - ne lance jamais deux requêtes à la fois : une demande arrivée pendant qu'une
//     requête est en route attend sa fin, et seule la DERNIÈRE demande en attente
//     part (les intermédiaires sont abandonnées sans avoir touché le réseau) ;
//   - n'affiche jamais une réponse périmée (clé différente de la dernière demandée).
// Un `invoke` Tauri ne peut pas être interrompu une fois parti : d'où cette mise en
// file plutôt qu'un AbortController.

const CACHE_TTL_MS = 5 * 60_000
const CACHE_MAX_ENTRIES = 50
const cache = new Map<string, { at: number; value: unknown }>()

export interface SearchRunner<P> {
  /// @returns `true` si la demande part (ou partira) sur le réseau, `false` si elle
  /// est identique à la précédente ou servie depuis la mémoire.
  request(params: P): boolean
}

interface SearchRunnerOptions<P, R> {
  /// Préfixe de la mémoire — deux runners ne partagent jamais leurs entrées.
  name: string
  keyOf: (params: P) => string
  fetch: (params: P) => Promise<R>
  onResult: (value: R) => void
  onError: (error: unknown) => void
  onBusyChange: (busy: boolean) => void
}

export function useSearchRunner<P, R>(options: SearchRunnerOptions<P, R>): SearchRunner<P> {
  const optionsRef = useRef(options)
  optionsRef.current = options
  const runnerRef = useRef<SearchRunner<P> | null>(null)
  if (!runnerRef.current) runnerRef.current = createSearchRunner(() => optionsRef.current)
  return runnerRef.current
}

function createSearchRunner<P, R>(get: () => SearchRunnerOptions<P, R>): SearchRunner<P> {
  let lastKey: string | null = null
  let inFlightKey: string | null = null
  let queued: P | null = null

  const cacheKey = (key: string) => `${get().name}|${key}`

  const readCache = (key: string): { value: R } | null => {
    const entry = cache.get(cacheKey(key))
    if (!entry) return null
    if (Date.now() - entry.at > CACHE_TTL_MS) {
      cache.delete(cacheKey(key))
      return null
    }
    return { value: entry.value as R }
  }

  const writeCache = (key: string, value: R) => {
    const k = cacheKey(key)
    cache.delete(k)
    cache.set(k, { at: Date.now(), value })
    while (cache.size > CACHE_MAX_ENTRIES) {
      const oldest = cache.keys().next().value
      if (oldest === undefined) break
      cache.delete(oldest)
    }
  }

  const launch = (params: P) => {
    const key = get().keyOf(params)
    inFlightKey = key
    get().onBusyChange(true)
    get().fetch(params)
      .then((value) => {
        writeCache(key, value)
        if (key === lastKey) get().onResult(value)
      })
      .catch((error) => {
        if (key === lastKey) {
          // Oublie la clé : la même recherche redemandée doit pouvoir réessayer.
          lastKey = null
          get().onError(error)
        } else {
          console.warn(`[${get().name}] recherche périmée en échec (${key}) :`, error)
        }
      })
      .finally(() => {
        inFlightKey = null
        const next = queued
        queued = null
        if (next !== null) launch(next)
        else get().onBusyChange(false)
      })
  }

  return {
    request(params) {
      const key = get().keyOf(params)
      if (key === lastKey) return false
      lastKey = key

      const cached = readCache(key)
      if (cached) {
        queued = null
        get().onResult(cached.value)
        return false
      }

      if (inFlightKey !== null) {
        // La requête en route répond déjà à cette clé : rien à mettre en file.
        queued = key === inFlightKey ? null : params
        return queued !== null
      }

      launch(params)
      return true
    },
  }
}
