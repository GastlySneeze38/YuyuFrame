import type { Lang } from '@/stores/useStore'
import { api } from '@/api/client'

/**
 * Langue du premier démarrage.
 *
 * Deux indices, du plus rapide au plus sûr :
 *
 * 1. **La langue du système** (`navigator.language`, que la webview reprend du
 *    compte Windows). Disponible immédiatement, sans réseau — c'est elle qui
 *    évite que l'interface s'affiche en français une seconde avant de basculer.
 * 2. **Le pays d'où l'on se connecte** (voir `app/locale.rs`). Il arrive
 *    après, et l'emporte : un Windows vendu en anglais dans un pays qui ne
 *    l'est pas est un cas courant, l'inverse beaucoup moins.
 *
 * Rien n'est deviné au-delà : un pays dont on ne parle pas la langue tombe sur
 * l'anglais, jamais sur le français — le launcher est écrit en français, ses
 * utilisateurs ne le sont pas tous.
 */

/** Langue la plus probable par pays, pour les seules langues que l'on parle.
 *  Un pays absent d'ici n'est pas une erreur : il vaut l'anglais. */
const COUNTRY_TO_LANG: Record<string, Lang> = {
  // Français
  FR: 'fr', BE: 'fr', LU: 'fr', MC: 'fr', CH: 'fr', CD: 'fr', CI: 'fr', SN: 'fr',
  CM: 'fr', ML: 'fr', BF: 'fr', NE: 'fr', TD: 'fr', GA: 'fr', CG: 'fr', BJ: 'fr',
  TG: 'fr', GN: 'fr', MG: 'fr', DZ: 'fr', MA: 'fr', TN: 'fr', HT: 'fr',
  // Espagnol
  ES: 'es', MX: 'es', AR: 'es', CO: 'es', CL: 'es', PE: 'es', VE: 'es', EC: 'es',
  GT: 'es', CU: 'es', BO: 'es', DO: 'es', HN: 'es', PY: 'es', SV: 'es', NI: 'es',
  CR: 'es', PA: 'es', UY: 'es', GQ: 'es',
  // Allemand
  DE: 'de', AT: 'de', LI: 'de',
  // Italien
  IT: 'it', SM: 'it', VA: 'it',
  // Portugais
  PT: 'pt', BR: 'pt', AO: 'pt', MZ: 'pt', CV: 'pt',
  // Polonais
  PL: 'pl',
  // Russe
  RU: 'ru', BY: 'ru', KZ: 'ru', KG: 'ru',
}

/** Toutes les langues connues, pour reconnaître un `navigator.language`. */
const KNOWN: Lang[] = ['fr', 'en', 'es', 'de', 'it', 'pt', 'pl', 'ru']

/** Langue du système, ou `null` si ce n'est pas une des nôtres.
 *  `navigator.language` vaut « fr-BE », « pt-BR »… : seule la partie de
 *  gauche nous intéresse, les variantes régionales partagent le dictionnaire. */
export function systemLanguage(): Lang | null {
  const tags = navigator.languages?.length ? navigator.languages : [navigator.language]
  for (const tag of tags) {
    const base = tag.toLowerCase().split('-')[0] as Lang
    if (KNOWN.includes(base)) return base
  }
  return null
}

/** Langue déduite du pays d'où l'on se connecte, ou `null` si la question n'a
 *  pas pu être posée (hors ligne, pays inconnu). */
export async function ipLanguage(): Promise<Lang | null> {
  try {
    const country = await api.locale.detectCountry()
    if (!country) return null
    // Un pays sans langue à nous est une réponse valable : l'anglais est le
    // choix par défaut, et il vaut mieux que la langue du système quand
    // celle-ci n'est pas non plus des nôtres.
    return COUNTRY_TO_LANG[country] ?? 'en'
  } catch {
    return null
  }
}
