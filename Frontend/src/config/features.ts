/** Le P2P n'est pas encore prêt pour un usage public — bascule ici quand ce
 * sera le cas. Contrairement à l'ancien BETA_TEST (retiré), ce flag ne
 * touche QUE le P2P : le reste de l'app (Sync, Plans, connexion YuyuFrame)
 * n'en dépend pas. */
export const P2P_ENABLED = false

/** Sync cloud. Réactivée le 2026-09-21 : le client parle désormais le
 * protocole par morceaux de /v1 (voir `Backend/src/sync/chunks.rs`), qui
 * n'envoie que les fichiers modifiés au lieu de re-téléverser un zip de
 * l'instance entière — c'était ce dernier qui saturait le VPS et justifiait
 * la coupure.
 *
 * Interrupteur LOCAL, posé dans le code : il s'ajoute à celui du back-office
 * (clé `cloud_sync` de `GET /v1/config`, voir stores/useFleet.ts), qui permet
 * lui de couper la sync à distance sans publier de version. Les deux doivent
 * être au vert pour que l'écran s'ouvre. */
export const SYNC_ENABLED = true

/** Clé de l'interrupteur correspondant côté back-office. */
export const SYNC_FLAG = 'cloud_sync'
