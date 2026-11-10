import { api } from '@/api/client'
import { demoProfile } from '@/lib/friendsDemo'
import { isDemo } from '@/stores/useDevFlags'
import type { PublicProfile } from '@/types/friends'

/**
 * Les fiches d'amis (bannière, couleur, bio, vitrine) retenues pour la
 * session (2026-11-10).
 *
 * Sans elle, chaque ouverture d'une fiche la redemandait au serveur : elle
 * s'affichait d'abord avec la couleur du statut, puis la bannière tombait
 * quelques centaines de millisecondes plus tard et le bandeau changeait sous
 * les yeux. Une fiche déjà vue s'affiche maintenant d'un bloc ; elle n'est
 * relue en silence que si elle a plus de 5 minutes (un ami peut changer de
 * bannière, mais pas toutes les minutes).
 *
 * En mémoire seulement, vidée à la déconnexion (`useFriends.clear`).
 * Appuyer sur un ami dans la liste lance déjà le chargement (`prefetch`) :
 * le temps de l'animation d'entrée de la fiche, la réponse est souvent là.
 */

const STALE_MS = 5 * 60_000

const cache = new Map<string, { profile: PublicProfile; at: number }>()
const pending = new Map<string, Promise<PublicProfile>>()

/** La fiche retenue, s'il y en a une. */
export function cachedFriendProfile(id: string): PublicProfile | null {
  return cache.get(id)?.profile ?? null
}

/** Faut-il la relire ? (aucune, ou plus de 5 minutes) */
export function friendProfileStale(id: string): boolean {
  const entry = cache.get(id)
  return !entry || Date.now() - entry.at > STALE_MS
}

/** Charge la fiche (une seule requête à la fois par ami) et la retient. */
export function loadFriendProfile(id: string): Promise<PublicProfile> {
  const running = pending.get(id)
  if (running) return running
  const request = (isDemo() ? Promise.resolve(demoProfile(id)) : api.friends.profile(id))
    .then((profile) => {
      cache.set(id, { profile, at: Date.now() })
      return profile
    })
    .finally(() => pending.delete(id))
  pending.set(id, request)
  return request
}

/** Charge à l'avance, sans rien attendre ni signaler. */
export function prefetchFriendProfile(id: string): void {
  if (friendProfileStale(id)) loadFriendProfile(id).catch(() => {})
}

export function clearFriendProfiles(): void {
  cache.clear()
  pending.clear()
}
