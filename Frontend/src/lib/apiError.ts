/**
 * Erreurs de la LauncherAPI /v1.
 *
 * Le backend Rust renvoie ses erreurs en JSON à travers la convention
 * `Result<T, String>` des commandes Tauri : `{ code, message, ...extra }`
 * (voir `Backend/src/server/error.rs`, qui doit rester d'accord avec ce fichier).
 * Le `code` est la seule chose stable — le texte, lui, peut changer.
 *
 * Une erreur qui ne vient pas de l'API (message brut d'une commande locale)
 * est rendue telle quelle, avec le code « unknown ».
 */

export interface ApiError {
  code: string
  message: string
  /** Champs propres à certaines erreurs : min_version, reason, until… */
  extra: Record<string, unknown>
}

export function parseApiError(e: unknown): ApiError {
  const raw = e instanceof Error ? e.message : typeof e === 'string' ? e : String(e)
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && typeof parsed.code === 'string') {
      const { code, message, extra, ...rest } = parsed
      return {
        code,
        message: typeof message === 'string' ? message : raw,
        extra: { ...(extra ?? {}), ...rest },
      }
    }
  } catch {
    // Pas du JSON : erreur locale, on garde le texte.
  }
  return { code: 'unknown', message: raw, extra: {} }
}

export function errorCode(e: unknown): string {
  return parseApiError(e).code
}

export function errorMessage(e: unknown): string {
  return parseApiError(e).message
}

/** Panne de transport (DNS, timeout, connexion refusée). */
export function isNetworkError(e: unknown): boolean {
  return errorCode(e) === 'network'
}

/** La session est perdue : il faut revenir à l'écran de connexion. */
export function isSessionExpiredError(e: unknown): boolean {
  const code = errorCode(e)
  return code === 'session_revoked' || code === 'not_signed_in'
}

/**
 * Erreurs que l'interface doit traiter par un écran dédié plutôt que par un
 * simple message : mise à jour obligatoire, version bloquée, compte suspendu
 * ou banni, mot de passe provisoire à changer.
 */
export const BLOCKING_CODES = [
  'update_required',
  'version_blocked',
  'account_suspended',
  'account_banned',
  'password_change_required',
  'email_verification_required',
] as const

export type BlockingCode = (typeof BLOCKING_CODES)[number]

export function blockingCode(e: unknown): BlockingCode | null {
  const code = errorCode(e)
  return (BLOCKING_CODES as readonly string[]).includes(code) ? (code as BlockingCode) : null
}
