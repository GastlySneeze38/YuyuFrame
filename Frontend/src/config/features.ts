/** Le P2P n'est pas encore prêt pour un usage public — bascule ici quand ce
 * sera le cas. Contrairement à l'ancien BETA_TEST (retiré), ce flag ne
 * touche QUE le P2P : le reste de l'app (Sync, Plans, connexion YuyuFrame)
 * n'en dépend pas. */
export const P2P_ENABLED = false

/** Sync cloud désactivée le temps de la réécrire sur le protocole par
 * morceaux de /v1 (l'ancien envoi d'un zip entier saturait le VPS).
 *
 * Interrupteur LOCAL, posé dans le code : il s'ajoute à celui du back-office
 * (clé `cloud_sync` de `GET /v1/config`, voir stores/useFleet.ts), qui permet
 * lui de couper la sync à distance sans publier de version. Les deux doivent
 * être au vert pour que l'écran s'ouvre. */
export const SYNC_ENABLED = false

/** Clé de l'interrupteur correspondant côté back-office. */
export const SYNC_FLAG = 'cloud_sync'
