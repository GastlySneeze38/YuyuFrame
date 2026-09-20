import { useCallback, useState } from 'react'
import { create } from 'zustand'

/**
 * Brouillons des formulaires de modales.
 *
 * Fermer une modale, en ouvrir une autre, revenir : ce qui avait été saisi
 * est toujours là. La mémoire est **temporaire** — jamais écrite sur le
 * disque, vidée à la fermeture du launcher — et chaque modale vide la sienne
 * une fois sa saisie validée (`clearDraft`).
 *
 * ⚠ Jamais de mot de passe ici : un champ sensible doit repartir vide.
 */

interface DraftStore {
  drafts: Record<string, Record<string, unknown>>
  set: (key: string, field: string, value: unknown) => void
  clear: (key: string) => void
}

const useDraftStore = create<DraftStore>((set) => ({
  drafts: {},
  set: (key, field, value) =>
    set((s) => ({ drafts: { ...s.drafts, [key]: { ...s.drafts[key], [field]: value } } })),
  clear: (key) =>
    set((s) => {
      const { [key]: _removed, ...rest } = s.drafts
      return { drafts: rest }
    }),
}))

/**
 * Comme `useState`, mais la valeur survit à la fermeture de la modale.
 *
 * `key` identifie le formulaire (« create-instance »), `field` le champ.
 * `initial` ne sert qu'à la toute première ouverture, ou après un
 * `clearDraft`.
 */
export function useDraftState<T>(key: string, field: string, initial: T): [T, (value: T) => void] {
  const stored = useDraftStore((s) => s.drafts[key]?.[field]) as T | undefined
  const setStored = useDraftStore((s) => s.set)
  // Valeur locale pour que le composant se redessine même si le store met un
  // instant à propager (et pour garder l'API d'un useState classique).
  const [value, setValue] = useState<T>(stored ?? initial)

  const update = useCallback(
    (next: T) => {
      setValue(next)
      setStored(key, field, next)
    },
    [key, field, setStored],
  )

  return [value, update]
}

/** À appeler une fois la saisie validée : le formulaire repartira vierge. */
export function clearDraft(key: string) {
  useDraftStore.getState().clear(key)
}

/** Efface tous les brouillons — utilisé à la déconnexion. */
export function clearAllDrafts() {
  useDraftStore.setState({ drafts: {} })
}
