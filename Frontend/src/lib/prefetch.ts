import { useRef } from 'react'
import { api, type CatalogServerDetail } from '@/api/client'

/**
 * Préchargement au survol (2026-11-06, audit des chargements réseau).
 *
 * Une fiche (serveur du catalogue, mod Modrinth) ne se chargeait qu'au clic :
 * elle s'ouvrait vide le temps de la requête. Ici, on la demande dès que la
 * souris s'arrête 150 ms sur la carte — le temps qu'il faut pour cliquer
 * suffit souvent à la recevoir, et la fiche s'ouvre remplie.
 *
 * 150 ms : en dessous, un balayage rapide de la grille partirait en requêtes
 * pour des cartes qu'on ne fait que traverser. Au doigt, il n'y a pas de
 * survol : on part à l'appui (`pointerdown`), ce qui gagne déjà le temps du
 * relâchement et de l'animation d'ouverture.
 */
const HOVER_DELAY_MS = 150

export function useHoverPrefetch(prefetch: () => void) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const cancel = () => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
  }
  return {
    onPointerEnter: (e: React.PointerEvent) => {
      if (e.pointerType !== 'mouse') return
      cancel()
      timer.current = setTimeout(prefetch, HOVER_DELAY_MS)
    },
    onPointerLeave: cancel,
    onPointerDown: (e: React.PointerEvent) => {
      if (e.pointerType !== 'mouse') prefetch()
    },
    onFocus: () => prefetch(),
  }
}

// ── Fiches du catalogue de serveurs ────────────────────────────────────────

const SERVER_TTL_MS = 5 * 60_000
const servers = new Map<string, { at: number; p: Promise<CatalogServerDetail> }>()

/** La fiche d'un serveur du catalogue, mémorisée 5 min (partagée par le
 *  préchargement et l'ouverture). Un échec n'est pas gardé. */
export function serverDetail(id: string): Promise<CatalogServerDetail> {
  const hit = servers.get(id)
  if (hit && Date.now() - hit.at < SERVER_TTL_MS) return hit.p
  const p = api.servers.get(id)
  servers.set(id, { at: Date.now(), p })
  p.catch(() => servers.delete(id))
  return p
}

export function prefetchServerDetail(id: string): void {
  serverDetail(id).catch(() => {})
}
