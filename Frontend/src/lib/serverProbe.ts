import { api } from '@/api/client'

/** Ce que dit un serveur de lui-même (`servers/probe.rs`). */
export interface ServerProbeInfo {
  reachable: boolean
  /** Il renvoie n'importe quel numéro : ses réponses par version ne prouvent rien. */
  echoes_all: boolean
  /** `forge` / `neoforge` / `modded` quand il exige des mods. */
  modded: string | null
  server_version: string
}

/** `true` acceptée, `false` refusée, `null` on ne sait pas. */
export type VersionVerdict = boolean | null

/** Gardé 10 minutes : un serveur ne change pas de versions d'une heure à l'autre. */
const KEEP_MS = 10 * 60_000

interface Entry {
  at: number
  info: Promise<ServerProbeInfo>
  versions: Map<string, Promise<VersionVerdict>>
  /** Les questions partent l'une après l'autre : des serveurs (CubeCraft)
   *  coupent après trois connexions rapprochées. */
  queue: Promise<unknown>
}

const entries = new Map<string, Entry>()

function entry(address: string): Entry {
  const key = address.toLowerCase()
  const found = entries.get(key)
  if (found && Date.now() - found.at < KEEP_MS) return found
  const info = api.servers.probe(address).catch((): ServerProbeInfo => ({ reachable: false, echoes_all: false, modded: null, server_version: '' }))
  const fresh: Entry = { at: Date.now(), info, versions: new Map(), queue: info }
  entries.set(key, fresh)
  return fresh
}

/** Le serveur répond-il, exige-t-il des mods ? Gardé pour la session. */
export function probeServer(address: string): Promise<ServerProbeInfo> {
  return entry(address).info
}

/** Le serveur accepte-t-il cette version ? Une question à la fois par
 *  serveur, chaque réponse gardée. */
export function probeVersion(address: string, version: string): Promise<VersionVerdict> {
  const e = entry(address)
  const known = e.versions.get(version)
  if (known) return known
  const asked = e.queue.then(() => api.servers.probeVersion(address, version)).catch(() => null)
  e.queue = asked
  e.versions.set(version, asked)
  return asked
}

/** Compare deux versions du jeu (« 1.21.11 » après « 1.21.4 », « 26.1 » après les 1.x). */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map((n) => parseInt(n, 10) || 0)
  const pb = b.split('.').map((n) => parseInt(n, 10) || 0)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d
  }
  return 0
}
