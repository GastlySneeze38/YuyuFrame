import { api } from '@/api/client'

/**
 * Cache mémoire des aperçus de skin, pour la durée de la session.
 *
 * ── Pourquoi ──────────────────────────────────────────────────────────────
 * Trois écrans demandent le même aperçu — la bannière d'accueil, la liste des
 * comptes et l'écran des skins — et chacun le redemandait à chaque montage.
 * Aller-retour vers le Rust, lecture disque, encodage base64 d'une dizaine de
 * kilo-octets : rien de grave pris isolément, mais répété à chaque navigation,
 * et parfois plusieurs fois en même temps quand deux écrans s'affichent
 * ensemble.
 *
 * Le cache du Rust (`skins/cache/`) évite déjà de retélécharger ; celui-ci
 * évite de redemander.
 *
 * ── Les appels simultanés ─────────────────────────────────────────────────
 * `inflight` est la moitié qui compte vraiment. Sans elle, la liste des
 * comptes qui boucle sur cinq comptes pendant que l'écran interroge le compte
 * actif lancerait deux résolutions pour le même compte — et sur un compte
 * Microsoft jamais vu, une résolution veut dire deux appels à Mojang. Une
 * promesse partagée les fusionne.
 *
 * ── Fraîcheur ─────────────────────────────────────────────────────────────
 * Le cache n'expire pas : rien ne change un skin en dehors du launcher pendant
 * qu'il tourne, sauf l'utilisateur lui-même — et dans ce cas c'est
 * `rememberSkinPreview` qui le met à jour, sans nouvel appel.
 */
const cache = new Map<string, string | null>()
const inflight = new Map<string, Promise<string | null>>()

/** Aperçu du skin d'un compte. `null` = aucun skin à montrer. */
export function skinPreview(uuid: string): Promise<string | null> {
  const cached = cache.get(uuid)
  if (cached !== undefined) return Promise.resolve(cached)

  const pending = inflight.get(uuid)
  if (pending) return pending

  const request = api.skin
    .preview(uuid)
    .then((dataUri) => {
      cache.set(uuid, dataUri)
      inflight.delete(uuid)
      return dataUri
    })
    .catch((e) => {
      // Un échec n'est pas mis en cache : le réseau peut revenir, et on ne veut
      // pas condamner l'aperçu pour toute la session.
      inflight.delete(uuid)
      throw e
    })

  inflight.set(uuid, request)
  return request
}

/** Après une application : le nouvel aperçu est déjà connu, inutile de le relire. */
export function rememberSkinPreview(uuid: string, dataUri: string | null): void {
  cache.set(uuid, dataUri)
}

/** Après un retour au skin par défaut : l'aperçu est à recalculer. */
export function forgetSkinPreview(uuid: string): void {
  cache.delete(uuid)
}
