import { useEffect, useState } from 'react'
import { api } from '@/api/client'

/**
 * Les images réglées dans le back-office (récompenses du passe, titres),
 * lues par le Rust (`content_asset` : disque d'abord, serveur sinon).
 * Mémorisées pour la session, appels simultanés fusionnés (même principe que
 * `lib/skinCache.ts`). Une image absente rend `null` : l'appelant dessine son
 * icône par défaut.
 */
const cache = new Map<string, Promise<string | null>>()

export function loadAsset(id: string): Promise<string | null> {
  let p = cache.get(id)
  if (!p) {
    p = api.pass.asset(id).catch(() => null)
    cache.set(id, p)
  }
  return p
}

/** L'image `id` en data URI, `null` tant qu'elle n'est pas là ou si elle manque. */
export function useContentAsset(id: string | null | undefined): string | null {
  const [uri, setUri] = useState<string | null>(null)
  useEffect(() => {
    setUri(null)
    if (!id) return
    let alive = true
    loadAsset(id).then((v) => alive && setUri(v))
    return () => {
      alive = false
    }
  }, [id])
  return uri
}
