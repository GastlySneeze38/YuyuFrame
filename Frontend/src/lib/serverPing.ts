import { useCallback, useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { ServerPingInfo } from '@/api/client'
import { readCache, writeCache } from './persistentCache'

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
      if (result) writeCache(persistKey(k), result)
      return result
    })
  pending.set(k, p)
  return p
}

/** Dernière réponse d'un serveur, gardée d'un lancement à l'autre
 *  (2026-11-06) : la carte montre tout de suite son icône, son MOTD et son
 *  dernier nombre de joueurs (`stale`) pendant que le nouveau ping court, au
 *  lieu d'une carte vide. Un jour au plus ; un serveur hors ligne n'est
 *  jamais gardé. */
const persistKey = (k: string) => `ping:${k}`
const PERSIST_MAX_AGE = 24 * 3600_000

function lastKnown(address: string): PingState {
  const saved = readCache<ServerPingInfo>(persistKey(key(address)), PERSIST_MAX_AGE)?.value
  return saved ? { status: 'ok', info: saved, stale: true } : { status: 'loading' }
}

/** `stale` : réponse d'un lancement précédent, en attendant la nouvelle. */
export type PingState = { status: 'loading' } | { status: 'ok'; info: ServerPingInfo; stale?: boolean } | { status: 'offline' }

/** Le ping d'une adresse, et de quoi le relancer. Adresse vide = rien. */
export function useServerPing(address: string | null) {
  const [state, setState] = useState<PingState>(() => (address ? lastKnown(address) : { status: 'loading' }))
  const [refreshing, setRefreshing] = useState(false)

  useEffect(() => {
    if (!address) return
    let alive = true
    setState(lastKnown(address))
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

/** Les pings de toute une liste d'adresses, pour pouvoir la trier (en ligne
 *  d'abord). Mêmes réponses mémorisées et fusionnées que `pingServer` ;
 *  `refresh` relance une adresse, ou toutes. */
export function useServerPings(addresses: string[]) {
  const signature = addresses.map(key).join('|')
  const [states, setStates] = useState<Record<string, PingState>>({})

  useEffect(() => {
    let alive = true
    const list = signature ? signature.split('|') : []
    setStates(Object.fromEntries(list.map((a) => [a, lastKnown(a)])))
    for (const a of list) {
      void pingServer(a).then((info) => {
        if (alive) setStates((s) => ({ ...s, [a]: info ? { status: 'ok', info } : { status: 'offline' } }))
      })
    }
    return () => {
      alive = false
    }
  }, [signature])

  const refresh = useCallback(
    (address?: string) => {
      const list = address ? [key(address)] : signature ? signature.split('|') : []
      setStates((s) => ({ ...s, ...Object.fromEntries(list.map((a) => [a, { status: 'loading' } as PingState])) }))
      for (const a of list) {
        void pingServer(a, true).then((info) => setStates((s) => ({ ...s, [a]: info ? { status: 'ok', info } : { status: 'offline' } })))
      }
    },
    [signature],
  )

  const stateOf = useCallback((address: string): PingState => states[key(address)] ?? lastKnown(address), [states])
  return { stateOf, refresh }
}

/** Première ligne du MOTD, sans les codes de couleur `§x` que certains
 *  serveurs laissent dans le texte. */
export function motdLine(motd: string): string {
  return motd.replace(/§./g, '').split('\n')[0]?.trim() ?? ''
}
