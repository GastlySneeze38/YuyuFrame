/**
 * L'interface tourne-t-elle dans Tauri ?
 *
 * Relevé une seule fois, au premier import du module, donc AVANT que
 * l'aperçu navigateur (`dev/browserPreview.ts`) ne pose ses simulations : ces
 * simulations créent elles aussi `__TAURI_INTERNALS__`, et une lecture faite
 * après dirait « oui » dans Chrome.
 *
 * Hors de Tauri, il n'y a que l'aperçu navigateur, en développement seulement.
 */
export const inTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window

/**
 * Le système sur lequel tourne le launcher, pour l'afficher (écran
 * Informations). Lu dans l'identification de la vue web, que Tauri ne
 * maquille pas : « Android » dans la WebView du téléphone, « Windows NT »
 * dans WebView2. L'ordre compte — Android annonce aussi « Linux ».
 */
/** L'appli Android elle-même (pas l'aperçu téléphone dans un navigateur) : seul
 *  endroit où le moteur Horizon de l'APK et son API existent. */
export const isAndroidApp = inTauri && typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent)

/** L'appli iPhone ou iPad elle-même : le moteur Horizon y est lié dans l'appli,
 *  et ses fichiers viennent d'un PC (`horizon/transfer.rs`). Un iPad récent se
 *  présente comme un Mac : c'est l'écran tactile qui le trahit. */
export const isIosApp = inTauri && typeof navigator !== 'undefined' && (
  /iPhone|iPad|iPod/i.test(navigator.userAgent)
  || (/Macintosh/i.test(navigator.userAgent) && navigator.maxTouchPoints > 1)
)

export function osName(): string {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent
  if (/Android/i.test(ua)) return 'Android'
  if (/iPhone|iPad|iPod/i.test(ua)) return 'iOS'
  if (/Windows/i.test(ua)) return 'Windows'
  if (/Mac OS X|Macintosh/i.test(ua)) return 'macOS'
  if (/Linux/i.test(ua)) return 'Linux'
  return '—'
}
