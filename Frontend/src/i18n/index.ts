import { useStore } from '@/stores/useStore'
import type { Lang } from '@/stores/useStore'
import { fr } from './translations/fr'
import { en } from './translations/en'
import { es } from './translations/es'
import { de } from './translations/de'
import { it } from './translations/it'
import { pt } from './translations/pt'
import { pl } from './translations/pl'
import { ru } from './translations/ru'
import { sga, SGA_LABEL } from './translations/sga'

export type { Lang }

// Une langue absente d'ici planterait à la lecture : le type `Lang` et cette
// table doivent être modifiés ensemble.
const DICTS: Record<Lang, Record<string, unknown>> = { fr, en, es, de, it, pt, pl, ru, sga }

function getPath(obj: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, key) => {
    if (acc && typeof acc === 'object') return (acc as Record<string, unknown>)[key]
    return undefined
  }, obj)
}

function interpolate(str: string, vars?: Record<string, string | number>): string {
  if (!vars) return str
  return str.replace(/\{\{(\w+)\}\}/g, (_, key) => String(vars[key] ?? ''))
}

/** Traduit `key` (chemin en pointillés, ex: "settings.lancement.ramLabel")
 * dans la langue actuellement stockée — retombe sur le français puis sur la
 * clé brute si absente dans les deux. Lit l'état directement (pas de
 * souscription) : utilisable hors composant React (handlers, utils...). Dans
 * un composant, préférer `useT()` pour re-render au changement de langue. */
export function t(key: string, vars?: Record<string, string | number>): string {
  const lang = useStore.getState().language
  const value = getPath(DICTS[lang], key) ?? getPath(DICTS.fr, key)
  if (typeof value !== 'string') return key
  return interpolate(value, vars)
}

/** Traduit `key` dans une langue **choisie**, sans toucher à celle de
 * l'interface.
 *
 * Sert à rendre cherchable ce qui n'est pas affiché : on désigne souvent une
 * option de Minecraft par son nom anglais alors que le jeu et le launcher
 * sont en français. La recherche des réglages compare donc aussi aux
 * libellés anglais, sans jamais les montrer (voir OptionsTab). */
export function tIn(lang: Lang, key: string, vars?: Record<string, string | number>): string {
  const value = getPath(DICTS[lang], key) ?? getPath(DICTS.fr, key)
  if (typeof value !== 'string') return key
  return interpolate(value, vars)
}

/** Hook — s'abonne à `language` pour re-render le composant quand elle
 * change (t() seul ne le fait pas, il ne fait que lire l'état courant). */
export function useT() {
  useStore((s) => s.language)
  return t
}

/** Langues proposées dans les réglages, dans leur propre langue.
 *
 *  `secret` : la carte n'apparaît qu'une fois la langue découverte (voir
 *  `sgaUnlocked` et l'easter egg de la section Langue). Elle est une langue
 *  comme les autres pour le reste du code — seule sa présence dans la grille
 *  est conditionnelle. */
export const LANGUAGES: { code: Lang; nativeLabel: string; secret?: boolean }[] = [
  { code: 'fr', nativeLabel: 'Français' },
  { code: 'en', nativeLabel: 'English' },
  { code: 'es', nativeLabel: 'Español' },
  { code: 'de', nativeLabel: 'Deutsch' },
  { code: 'it', nativeLabel: 'Italiano' },
  { code: 'pt', nativeLabel: 'Português' },
  { code: 'pl', nativeLabel: 'Polski' },
  { code: 'ru', nativeLabel: 'Русский' },
  { code: 'sga', nativeLabel: SGA_LABEL, secret: true },
]
