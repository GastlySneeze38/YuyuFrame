import { useEffect, useState } from 'react'
import { api } from '@/api/client'

/**
 * Si le client intégré (LauncherAgent) se charge pour ce couple version /
 * plateforme. Le Rust est seul juge (`agent_compat.rs`) ; les réponses sont
 * gardées pour la session, elles ne changent qu'avec le launcher.
 *
 * `null` tant qu'on ne sait pas : un écran qui cache ce qui ne marche pas ne
 * doit pas montrer puis retirer une section le temps de la réponse.
 */
const cache = new Map<string, Promise<boolean>>()

export function useAgentAvailable(mcVersion: string, loader: string): boolean | null {
  const [available, setAvailable] = useState<boolean | null>(null)

  useEffect(() => {
    let alive = true
    setAvailable(null)
    const key = `${mcVersion}|${loader}`
    let pending = cache.get(key)
    if (!pending) {
      // Un échec se lit « disponible » : c'est le comportement de toujours,
      // et une question qui n'a pas pu être posée ne doit rien retirer.
      pending = api.launch.agentStatus(mcVersion, loader).then((s) => s.available).catch(() => true)
      cache.set(key, pending)
    }
    pending.then((value) => { if (alive) setAvailable(value) })
    return () => { alive = false }
  }, [mcVersion, loader])

  return available
}
