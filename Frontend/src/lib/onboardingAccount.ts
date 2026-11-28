import { api } from '@/api/client'

/**
 * Le compte YuyuFrame laissé en attente par l'écran d'accueil (2026-11-28).
 *
 * Le compte est obligatoire, mais on ne peut pas le créer quand notre serveur
 * ne répond pas (pas de réseau, panne). L'accueil saute alors son étape,
 * le dit, et le retient ici : au lancement suivant — et toutes les deux
 * minutes tant que l'appli tourne —, `App.tsx` sonde le serveur et rouvre
 * l'accueil dès qu'il répond, pour proposer le compte (et les étapes qui en
 * dépendent : amis, profil). Oublié dès qu'une session YuyuFrame s'ouvre.
 *
 * Dans `localStorage` et pas dans le magasin : une seule valeur, lue avant
 * que le reste soit chargé, sans rien à fusionner.
 */
const KEY = 'yf.onboarding.accountPending'

export function accountPending(): boolean {
  try { return localStorage.getItem(KEY) === '1' } catch { return false }
}

export function setAccountPending(pending: boolean) {
  try {
    if (pending) localStorage.setItem(KEY, '1')
    else localStorage.removeItem(KEY)
  } catch { /* stockage refusé : on le redemandera au prochain accueil */ }
}

/** Notre serveur répond (`GET /health`, 5 s au plus). */
export function serverReachable(): Promise<boolean> {
  return api.yuyu.ping().catch(() => false)
}

/**
 * Internet tout court, vu par la connexion Microsoft : sa page répond-elle ?
 * Distinct de notre serveur (il peut être en panne seul). Requête `no-cors` :
 * on ne lit rien, seul compte qu'elle aboutisse — une coupure réseau la fait
 * échouer. 5 s au plus.
 */
export async function internetReachable(): Promise<boolean> {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return false
  const abort = new AbortController()
  const timer = window.setTimeout(() => abort.abort(), 5000)
  try {
    await fetch('https://login.live.com/', { mode: 'no-cors', cache: 'no-store', signal: abort.signal })
    return true
  } catch {
    return false
  } finally {
    window.clearTimeout(timer)
  }
}
