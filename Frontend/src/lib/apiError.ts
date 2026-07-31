/**
 * Distingue une panne de transport vers la LauncherAPI (DNS, timeout,
 * connexion refusée) d'une erreur métier (401, quota, validation...) — les
 * deux arrivent en JS comme un simple `string` rejeté par `invoke()`, donc ce
 * préfixe est le seul moyen de les différencier sans changer la convention
 * `Result<T, String>` de toutes les commandes Tauri. Doit rester identique à
 * `network_err()` dans `Backend/src/commands/mod.rs`.
 */
const NETWORK_ERROR_PREFIX = 'Serveur inaccessible : '

export function isNetworkError(e: unknown): boolean {
  const message = e instanceof Error ? e.message : typeof e === 'string' ? e : String(e)
  return message.startsWith(NETWORK_ERROR_PREFIX)
}

/**
 * Un 401 renvoyé par la LauncherAPI sur un appel authentifié (Bearer token) —
 * la session YuyuFrame doit être vidée et l'utilisateur invité à se
 * reconnecter, plutôt que de laisser chaque appel suivant échouer en boucle
 * avec le même message brut. Doit rester identique à `SESSION_EXPIRED_PREFIX`
 * (`bearer_call_error()` dans `Backend/src/commands/mod.rs`).
 */
const SESSION_EXPIRED_PREFIX = 'Session expirée : '

export function isSessionExpiredError(e: unknown): boolean {
  const message = e instanceof Error ? e.message : typeof e === 'string' ? e : String(e)
  return message.startsWith(SESSION_EXPIRED_PREFIX)
}
