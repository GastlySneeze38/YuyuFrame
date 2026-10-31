import { useCallback, useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { ServerPingInfo } from '@/api/client'

/**
 * Pings de serveurs mémorisés pour la session, et fusionnés quand plusieurs
 * cartes demandent la même adresse en même temps (même principe que
 * `skinCache.ts`). Sans ça, l'écran Serveurs repinguerait toute une liste à
 * chaque changement d'onglet.
 *
 * Une réponse vaut une minute : assez pour passer d'un onglet à l'autre, pas
 * assez pour afficher un nombre de joueurs d'il y a une heure. Le bouton
 * « actualiser » d'une carte passe outre.
 */

const FRESH_MS = 60_000

type Entry = { at: number; result: ServerPingInfo | null }
const done = new Map<string, Entry>()
const pending = new Map<string, Promise<ServerPingInfo | null>>()

function key(address: string): string {
  return address.trim().toLowerCase()
}

/** `null` : le serveur n'a pas répondu. */
export function pingServer(address: string, force = false): Promise<ServerPingInfo | null> {
  const k = key(address)
  const hit = done.get(k)
  if (!force && hit && Date.now() - hit.at < FRESH_MS) return Promise.resolve(hit.result)
  const running = pending.get(k)
  if (running) return running
  const p = api.launch
    .pingServer(k)
    .then((r) => r as ServerPingInfo | null)
    .catch(() => null)
    .then((result) => {
      done.set(k, { at: Date.now(), result })
      pending.delete(k)
      return result
    })
  pending.set(k, p)
  return p
}

export type PingState = { status: 'loading' } | { status: 'ok'; info: ServerPingInfo } | { status: 'offline' }

/** Le ping d'une adresse, et de quoi le relancer. Adresse vide = rien. */
export function useServerPing(address: string | null) {
  const [state, setState] = useState<PingState>({ status: 'loading' })
  const [refreshing, setRefreshing] = useState(false)

  useEffect(() => {
    if (!address) return
    let alive = true
    setState({ status: 'loading' })
    void pingServer(address).then((info) => {
      if (alive) setState(info ? { status: 'ok', info } : { status: 'offline' })
    })
    return () => {
      alive = false
    }
  }, [address])

  const refresh = useCallback(() => {
    if (!address) return
    setRefreshing(true)
    void pingServer(address, true).then((info) => {
      setState(info ? { status: 'ok', info } : { status: 'offline' })
      setRefreshing(false)
    })
  }, [address])

  return { state, refresh, refreshing }
}

/** Première ligne du MOTD, sans les codes de couleur `§x` que certains
 *  serveurs laissent dans le texte. */
export function motdLine(motd: string): string {
  return motd.replace(/§./g, '').split('\n')[0]?.trim() ?? ''
}
