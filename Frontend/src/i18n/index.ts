import { useStore } from '@/stores/useStore'
import type { Lang } from '@/stores/useStore'
import { fr } from './translations/fr'
import { en } from './translations/en'

export type { Lang }

const DICTS: Record<Lang, Record<string, unknown>> = { fr, en }

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

/** Hook — s'abonne à `language` pour re-render le composant quand elle
 * change (t() seul ne le fait pas, il ne fait que lire l'état courant). */
export function useT() {
  useStore((s) => s.language)
  return t
}

export const LANGUAGES: { code: Lang; nativeLabel: string }[] = [
  { code: 'fr', nativeLabel: 'Français' },
  { code: 'en', nativeLabel: 'English' },
]
