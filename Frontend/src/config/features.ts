/** Le P2P n'est pas encore prêt pour un usage public — bascule ici quand ce
 * sera le cas. Contrairement à l'ancien BETA_TEST (retiré), ce flag ne
 * touche QUE le P2P : le reste de l'app (Sync, Plans, connexion YuyuFrame)
 * n'en dépend pas. */
export const P2P_ENABLED = false

/** Sync cloud désactivée le temps de câbler la limite de concurrence côté
 * LauncherAPI (voir audit résilience serveur — un petit VPS sans
 * ConcurrencyLimitLayer + des uploads jusqu'à 200 Mo, ça ne pardonne pas).
 * À remettre à true une fois le correctif serveur fait. */
export const SYNC_ENABLED = false
