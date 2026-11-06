import { create } from 'zustand'
import { useStore } from '@/stores/useStore'
import type { Lang } from '@/stores/useStore'
import { fr } from './translations/fr'
import { sga, SGA_LABEL } from './translations/sga'

export type { Lang }

type Dict = Record<string, unknown>

// Langues chargées à la demande (2026-11-06) : les huit dictionnaires
// pesaient ~1 Mo dans le fichier de démarrage pour n'en utiliser qu'un.
// Le français reste embarqué : c'est le filet de secours de toutes les
// autres, et le galactique (`sga`) se calcule à partir de lui.
// Une langue absente d'ici planterait à la lecture : le type `Lang` et cette
// table doivent être modifiés ensemble.
const LOADERS: Record<Exclude<Lang, 'fr' | 'sga'>, () => Promise<Dict>> = {
  en: () => import('./translations/en').then((m) => m.en),
  es: () => import('./translations/es').then((m) => m.es),
  de: () => import('./translations/de').then((m) => m.de),
  it: () => import('./translations/it').then((m) => m.it),
  pt: () => import('./translations/pt').then((m) => m.pt),
  pl: () => import('./translations/pl').then((m) => m.pl),
  ru: () => import('./translations/ru').then((m) => m.ru),
}

const DICTS: Partial<Record<Lang, Dict>> = { fr, sga }
const pending = new Map<Lang, Promise<void>>()

/** Incrémenté à chaque dictionnaire arrivé : `useT` s'y abonne pour
 *  redessiner ce qui s'affichait en français le temps du chargement. */
const useLoaded = create(() => ({ version: 0 }))

/** Charge le dictionnaire de `lang` (une seule fois). Ne rejette jamais :
 *  un échec laisse simplement le français s'afficher. */
export function loadLanguage(lang: Lang): Promise<void> {
  if (DICTS[lang]) return Promise.resolve()
  const running = pending.get(lang)
  if (running) return running
  const job = LOADERS[lang as keyof typeof LOADERS]()
    .then((dict) => {
      DICTS[lang] = dict
      useLoaded.setState((s) => ({ version: s.version + 1 }))
    })
    .catch((e) => {
      console.error(`Langue « ${lang} » non chargée, repli sur le français`, e)
    })
    .finally(() => pending.delete(lang))
  pending.set(lang, job)
  return job
}

// Un changement de langue (réglages, détection au premier démarrage) charge
// son dictionnaire ; l'interface reste en français les quelques millisecondes
// que ça prend.
useStore.subscribe((state, prev) => {
  if (state.language !== prev.language) void loadLanguage(state.language)
})

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
  useLoaded((s) => s.version)
  return t
}

/** Pour `tIn` : charge une langue qui n'est pas celle de l'interface, et
 *  redessine le composant à son arrivée. */
export function useLanguageLoaded(lang: Lang): boolean {
  useLoaded((s) => s.version)
  if (!DICTS[lang]) void loadLanguage(lang)
  return DICTS[lang] !== undefined
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
