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
